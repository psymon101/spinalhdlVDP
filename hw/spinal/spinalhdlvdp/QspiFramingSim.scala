package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** #11337 — QSPI framing desync reproduction.
  *
  * HW (#11330): 32 back-to-back SDRAM_WRITE transactions -> stuck bridge + last_err
  * =0x40 (decoder saw an UNKNOWN opcode = framing desync). The seam sim proved the
  * bridge/arbiter DATAPATH is clean, so the desync is upstream in QspiSlave +
  * QspiDecoder command FRAMING under rapid back-to-back transactions.
  *
  * This sim wires the REAL QspiSlave -> QspiDecoder, drives bit-level QSPI, and
  * sweeps (a) SCK half-period H and (b) inter-transaction CS-idle gap. For each
  * combo it fires N back-to-back SDRAM_WRITE transactions and checks every one is
  * framed correctly (sdramHeaderValid pulse per txn, last_error stays 0). A combo
  * that drops headers / sets last_error reproduces the HW desync and pins the
  * threshold (CS-idle margin vs the 2-FF CS synchronizer, or SCK oversample margin).
  */
object QspiFramingSim extends App {
  class Harness extends Component {
    val io = new Bundle {
      val spi_cs_n  = in Bool()
      val spi_sck   = in Bool()
      val spi_io_in = in Bits(4 bits)
      val cmd_opcode      = out Bits(8 bits)
      val cmd_valid       = out Bool()
      val last_error      = out Bits(8 bits)
      val sdramHeaderValid = out Bool()
      val sdramAddrInit    = out UInt(23 bits)
    }
    val slave = QspiSlave()
    val dec   = QspiDecoder()
    slave.io.spi_cs_n  := io.spi_cs_n
    slave.io.spi_sck   := io.spi_sck
    slave.io.spi_io_in := io.spi_io_in
    dec.io.cmd_opcode    := slave.io.cmd_opcode
    dec.io.cmd_addr      := slave.io.cmd_addr
    dec.io.cmd_len       := slave.io.cmd_len
    dec.io.cmd_valid     := slave.io.cmd_valid
    dec.io.payload_byte  := slave.io.payload_byte
    dec.io.payload_valid := slave.io.payload_valid
    dec.io.tx_byte_sent  := slave.io.tx_byte_sent
    dec.io.active        := slave.io.active
    slave.io.tx_byte := dec.io.tx_byte
    slave.io.tx_load := dec.io.tx_load
    dec.io.status_sticky    := 0
    dec.io.live_mode        := 0
    dec.io.debug_sdram_data := 0
    dec.io.upload_busy      := False
    dec.io.upload_done      := False
    io.cmd_opcode       := slave.io.cmd_opcode
    io.cmd_valid        := slave.io.cmd_valid
    io.last_error       := dec.io.last_error
    io.sdramHeaderValid := dec.io.sdramHeaderValid
    io.sdramAddrInit    := dec.io.sdramAddrInit
  }

  Config.sim.compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.spi_cs_n  #= true
    dut.io.spi_sck   #= false
    dut.io.spi_io_in #= 0
    dut.clockDomain.waitSampling(20)

    // observers
    var hdrCount = 0
    var lastErr  = 0
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.sdramHeaderValid.toBoolean) hdrCount += 1
        val e = dut.io.last_error.toInt
        if (e != 0) lastErr = e
      }
    }

    // bit-level QSPI drive, parameterized SCK half-period H + CS-idle gap.
    def run(h: Int, csIdle: Int, withReads: Boolean, glitch: Boolean = false): (Int, Int) = {
      def sendNibble(n: Int): Unit = {
        dut.io.spi_io_in #= (n & 0xF)
        dut.clockDomain.waitSampling(h)
        dut.io.spi_sck #= true
        dut.clockDomain.waitSampling(h)
        dut.io.spi_sck #= false
      }
      def sendByte(b: Int): Unit = { sendNibble((b >> 4) & 0xF); sendNibble(b & 0xF) }
      def clockEdge(): Unit = {   // toggle SCK without driving data (read response phase)
        dut.clockDomain.waitSampling(h)
        dut.io.spi_sck #= true
        dut.clockDomain.waitSampling(h)
        dut.io.spi_sck #= false
      }
      def writeTxn(addr: Int): Unit = {
        dut.io.spi_cs_n #= false
        dut.clockDomain.waitSampling(3)
        Seq(0x02, addr & 0xFF, (addr >> 8) & 0xFF, (addr >> 16) & 0xFF, 0x01, 0x00).foreach(sendByte)
        Seq(0xAA, 0x55).foreach(sendByte)
        dut.clockDomain.waitSampling(h * 2)
        dut.io.spi_cs_n #= true
        dut.clockDomain.waitSampling(csIdle)
      }
      def readTxn(sel: Int): Unit = {     // READ_STATUS: header (len=0) + turnaround + 4 resp bytes
        dut.io.spi_cs_n #= false
        dut.clockDomain.waitSampling(3)
        Seq(0x04, sel & 0xFF, 0x00, 0x00, 0x00, 0x00).foreach(sendByte)
        for (_ <- 0 until 12) clockEdge()   // 2 turnaround + 8 response nibble edges (+margin)
        dut.clockDomain.waitSampling(h * 2)
        dut.io.spi_cs_n #= true
        dut.clockDomain.waitSampling(csIdle)
      }
      // Model the SPI2 split-rate TEARDOWN/re-add transient during "idle": the
      // peripheral remove/re-add can momentarily drive a spurious CS-low and a
      // runt SCK edge with garbage IO before the next real transaction.
      def teardownGlitch(): Unit = {
        dut.io.spi_io_in #= 0xF
        dut.io.spi_cs_n  #= false          // spurious CS assert
        dut.clockDomain.waitSampling(1)
        dut.io.spi_sck   #= true           // runt SCK rising while CS low
        dut.clockDomain.waitSampling(1)
        dut.io.spi_sck   #= false
        dut.io.spi_cs_n  #= true           // deassert
        dut.clockDomain.waitSampling(2)
      }
      hdrCount = 0; lastErr = 0
      val nTxn = 32
      for (t <- 0 until nTxn) {
        if (glitch) teardownGlitch()        // model SPI2 re-add transient before each txn
        writeTxn(0xB000 + t * 4)
        if (withReads) { if (glitch) teardownGlitch(); readTxn(8) }
      }
      dut.clockDomain.waitSampling(50)
      (hdrCount, lastErr)
    }

    // Sweep 1: write-only back-to-back. SCK half-period x CS-idle gap.
    println("[framing] WRITE-ONLY: H(px-cyc), csIdle(px-cyc) -> headers/32, lastErr")
    for (h <- Seq(8, 4, 2, 1); csIdle <- Seq(40, 8, 4, 2, 1)) {
      val (hc, le) = run(h, csIdle, withReads = false)
      val verdict = if (hc == 32 && le == 0) "OK" else "*** DESYNC ***"
      println(f"[framing] WR  H=$h%-2d csIdle=$csIdle%-3d -> headers=$hc%2d/32 lastErr=0x$le%02X  $verdict")
    }
    // Sweep 2: write+read interleave (matrix pattern — exercises Respond/turnaround
    // -> next-write framing, the path neither prior sim covered).
    println("[framing] WRITE+READ interleave:")
    for (h <- Seq(8, 4, 2, 1); csIdle <- Seq(40, 8, 4, 2, 1)) {
      val (hc, le) = run(h, csIdle, withReads = true)
      val verdict = if (hc == 32 && le == 0) "OK" else "*** DESYNC ***"
      println(f"[framing] WR+RD H=$h%-2d csIdle=$csIdle%-3d -> headers=$hc%2d/32 lastErr=0x$le%02X  $verdict")
    }
    // Sweep 3: TEARDOWN-TRANSIENT injection (#11353 gap-closer). The host SPI2
    // split-rate switch removes/re-adds the peripheral mid-session; that can drive
    // a spurious CS-low + runt SCK edge during the "idle" before the next real
    // transaction. If the QspiSlave misframes on that glitch, the next header is
    // mis-parsed -> decoder sees an UNKNOWN opcode -> last_error=0x40 + desync,
    // EXACTLY the HW symptom (#11330) that no fixed-rate sim reproduced. A DESYNC
    // row here reproduces the HW failure in sim and motivates an FPGA CS-debounce.
    println("[framing] TEARDOWN-GLITCH injected before each txn:")
    for (h <- Seq(8, 4, 2); csIdle <- Seq(40, 8, 4)) {
      val (hc, le) = run(h, csIdle, withReads = true, glitch = true)
      val verdict = if (hc == 32 && le == 0) "OK (robust)" else "*** DESYNC — reproduces HW 0x40 ***"
      println(f"[framing] GLITCH H=$h%-2d csIdle=$csIdle%-3d -> headers=$hc%2d/32 lastErr=0x$le%02X  $verdict")
    }
    // Sweep 3b: MERGED-FRAME — CS held low across N transactions (the one RTL-
    // visible desync mode). QspiSlave resets all framing state on cs_start (CS
    // high->low edge), so aborted/partial frames recover. The ONLY way to desync
    // is if CS never deasserts between transactions: the 2nd header's bytes are
    // consumed as the 1st transaction's payload -> opcode misalignment -> 0x40.
    // This characterizes the exact HOST CONTRACT: CS MUST deassert (>= 2 px-clk,
    // the 2-FF sync depth) between every transaction, including across the SPI2
    // rate switch. If the teardown leaves CS low / glitches it too narrow to
    // register, this is the HW failure.
    def runMerged(h: Int): (Int, Int) = {
      hdrCount = 0; lastErr = 0
      def sendNibbleM(n: Int): Unit = {
        dut.io.spi_io_in #= (n & 0xF); dut.clockDomain.waitSampling(h)
        dut.io.spi_sck #= true; dut.clockDomain.waitSampling(h); dut.io.spi_sck #= false
      }
      def sendByteM(b: Int): Unit = { sendNibbleM((b >> 4) & 0xF); sendNibbleM(b & 0xF) }
      dut.io.spi_cs_n #= false            // assert ONCE
      dut.clockDomain.waitSampling(3)
      for (t <- 0 until 4) {               // 4 "transactions" with NO CS deassert
        Seq(0x02, (0xB000 + t) & 0xFF, 0xB0, 0x00, 0x01, 0x00).foreach(sendByteM)
        Seq(0xAA, 0x55).foreach(sendByteM)
      }
      dut.clockDomain.waitSampling(h * 2)
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(50)
      (hdrCount, lastErr)
    }
    println("[framing] MERGED-FRAME (CS held low across 4 txns, no deassert):")
    val (mhc, mle) = runMerged(4)
    val mverdict = if (mhc >= 4 && mle == 0) "OK" else "*** DESYNC (expected) — bounds host CS-deassert contract ***"
    println(f"[framing] MERGED -> headers=$mhc%2d/4 lastErr=0x$mle%02X  $mverdict")
    println("QspiFramingSim: done (see DESYNC rows above)")
  }
}
