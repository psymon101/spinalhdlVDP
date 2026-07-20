package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** #11377 — FIXED-RATE failure repro: SDRAM_WRITE then READ_STATUS, no transport glitch.
  *
  * HW (BronzeGate #11379, per-write poll on 803aeb3c at a SINGLE fixed 3 MHz, NO
  * split-rate switching, NO SPI2 remove/re-add): after the FIRST sentinel
  * SDRAM_WRITE, `uploadBusy` is ALREADY stuck AND READ_STATUS sel=6 polling
  * returns last_error=0x40. "write-path / command-framing failure, not a debug-
  * capture race." My earlier teardown-glitch theory was REFUTED by this fixed-rate
  * result (#11375).
  *
  * This sim wires the REAL QspiSlave -> QspiDecoder -> QspiSdramBridge (so
  * uploadBusy is the real bridge FSM), drives bit-level QSPI at a fixed rate with
  * NO glitches, and probes the two HW symptoms separately:
  *
  *   CASE A  clean SDRAM_WRITE (LEN=words convention, payload bytes = 2*LEN) then
  *           READ_STATUS sel=6 -> EXPECT: uploadBusy clears, last_error=0x00.
  *           (baseline — proves the chain is clean for a compliant host.)
  *   CASE B  LEN/payload-count MISMATCH (host sends LEN in BYTES not WORDS, so the
  *           bridge expects 2x the bytes it receives) -> EXPECT: uploadBusy STUCK,
  *           with NO glitch. Clean-stimulus reproduction of the stuck-bridge symptom.
  *   CASE C  nibble-phase slip on the READ_STATUS header (one extra SCK edge at CS
  *           assert) -> EXPECT: opcode 0x04 misframes to 0x40 -> last_error=0x40.
  *   CASE D  nibble-phase slip on the WRITE header -> garbage LEN -> uploadBusy STUCK.
  *
  * A/B/C/D map the HW symptom to a CAUSE class: B = host LEN convention bug
  * (clean), C/D = a nibble-phase slip (needs a per-transaction framing offset).
  * This sim does NOT claim which one the HW hits — it shows which stimulus
  * produces which symptom so BronzeGate's scope + host LEN check can decide.
  */
object QspiWriteStatusReproSim extends App {
  class Harness extends Component {
    val io = new Bundle {
      val spi_cs_n  = in Bool()
      val spi_sck   = in Bool()
      val spi_io_in = in Bits(4 bits)
      val cmd_opcode  = out Bits(8 bits)
      val cmd_valid   = out Bool()
      val last_error  = out Bits(8 bits)
      val uploadBusy  = out Bool()
      val uploadError = out Bool()
      val wrCmdFireCnt = out UInt(16 bits)
      val sdramHeaderValid = out Bool()
      val sdramLenBytes    = out UInt(17 bits)
    }
    val slave  = QspiSlave()
    val dec    = QspiDecoder()
    // CP-A1: small stallTimeout so the watchdog fires quickly in sim (prod default 65536).
    val bridge = QspiSdramBridge(stallTimeout = 64)

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

    // Real bridge — this is where uploadBusy lives.
    bridge.io.headerValid := dec.io.sdramHeaderValid
    bridge.io.addrInit    := dec.io.sdramAddrInit
    bridge.io.lenBytes    := dec.io.sdramLenBytes
    bridge.io.byteIn      := dec.io.sdramByteOut
    bridge.io.byteValid   := dec.io.sdramByteValid
    bridge.io.allowUpload := True            // F5 production: continuous drain
    bridge.io.wrCmd.ready := True            // downstream (uploadCc) always accepts here
    // Feed bridge status back to the decoder so READ_STATUS sel=6 reflects it.
    dec.io.upload_busy := bridge.io.uploadBusy
    dec.io.upload_done := bridge.io.uploadDone
    dec.io.upload_error := bridge.io.uploadError
    dec.io.upload_overflow := bridge.io.fifoOverflow
    // Word-drain byte/word egress inputs (added by transport-core cherry-pick a4dcdf4);
    // this legacy write-status sim exercises the payload_byte path, so tie them inactive.
    dec.io.payload_word       := 0
    dec.io.payload_word_valid := False

    val fireCnt = Reg(UInt(16 bits)) init 0
    when(bridge.io.wrCmd.fire) { fireCnt := fireCnt + 1 }

    io.cmd_opcode       := slave.io.cmd_opcode
    io.cmd_valid        := slave.io.cmd_valid
    io.last_error       := dec.io.last_error
    io.uploadBusy       := bridge.io.uploadBusy
    io.uploadError      := bridge.io.uploadError
    io.wrCmdFireCnt     := fireCnt
    io.sdramHeaderValid := dec.io.sdramHeaderValid
    io.sdramLenBytes    := dec.io.sdramLenBytes
  }

  Config.sim.compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.spi_cs_n  #= true
    dut.io.spi_sck   #= false
    dut.io.spi_io_in #= 0
    dut.clockDomain.waitSampling(20)

    var lastErr = 0
    var capOpcode = -1     // opcode the slave decoded on the most recent header
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        val e = dut.io.last_error.toInt
        if (e != 0) lastErr = e
        if (dut.io.cmd_valid.toBoolean) capOpcode = dut.io.cmd_opcode.toInt
      }
    }

    val H = 4   // fixed SCK half-period (pixel cycles) — no rate switching anywhere

    def sendNibble(n: Int): Unit = {
      dut.io.spi_io_in #= (n & 0xF)
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= true
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= false
    }
    def sendByte(b: Int): Unit = { sendNibble((b >> 4) & 0xF); sendNibble(b & 0xF) }
    def clockEdge(): Unit = {
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= true
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= false
    }

    def fires(): BigInt = dut.io.wrCmdFireCnt.toBigInt
    def clearObs(): Unit = { lastErr = 0; capOpcode = -1 }

    // csSetup = pixel cycles between CS-assert and the first SCK edge. The QspiSlave
    // 2-FF CS synchronizer needs ~2-3 cycles before cs_start fires; a host that drives
    // the first SCK edge too soon (csSetup < sync depth) makes the slave MISS the
    // leading nibble -> all nibbles shift by one -> opcode 0x04 re-pairs as 0x40.
    def sdramWrite(addr: Int, lenWords: Int, payload: Seq[Int], csSetup: Int = 3): Unit = {
      dut.io.spi_cs_n #= false
      dut.clockDomain.waitSampling(csSetup)
      Seq(0x02, addr & 0xFF, (addr >> 8) & 0xFF, (addr >> 16) & 0xFF,
          lenWords & 0xFF, (lenWords >> 8) & 0xFF).foreach(sendByte)
      payload.foreach(sendByte)
      dut.clockDomain.waitSampling(H * 2)
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(40)
    }
    def readStatus(sel: Int, csSetup: Int = 3): Unit = {
      dut.io.spi_cs_n #= false
      dut.clockDomain.waitSampling(csSetup)
      Seq(0x04, sel & 0xFF, 0x00, 0x00, 0x00, 0x00).foreach(sendByte)
      for (_ <- 0 until 12) clockEdge()
      dut.clockDomain.waitSampling(H * 2)
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(40)
    }

    def drain(): Unit = dut.clockDomain.waitSampling(400)

    // No DUT reset between cases (assertReset deadlocks forkStimulus). Instead the
    // bridge-stick check (CASE B) runs BEFORE contamination, and the framing checks
    // (C/D) read the decoded opcode directly — independent of bridge stick state.
    def line(tag: String, ok: Boolean, detail: String): Unit = {
      val verdict = if (ok) "as-expected" else "*** UNEXPECTED ***"
      println(f"[repro] $tag%-44s $detail%-46s $verdict")
    }

    // ---- CASE A: clean write (LEN=2 words -> 4 payload bytes) + READ_STATUS sel=6.
    // Baseline: bridge drains, uploadBusy clears, no error. Proves the chain is clean
    // for a compliant fixed-rate host (matches my earlier exoneration sims).
    clearObs()
    val f0 = fires()
    sdramWrite(0xB000, lenWords = 2, payload = Seq(0x11, 0x22, 0x33, 0x44))
    drain()
    readStatus(6)
    drain()
    val aBusy = dut.io.uploadBusy.toBoolean; val aFires = fires() - f0
    line("A clean write(len=2,4B)+RS6", !aBusy && aFires == 4 && lastErr == 0,
      f"uploadBusy=$aBusy fires=+$aFires lastErr=0x$lastErr%02X")

    // ---- CASE B: LEN/payload MISMATCH (no glitch). Host sends LEN=4 words header but
    // only 4 payload bytes; bridge expects 2*LEN=8 -> byteFifo dry at 4. PRE-CP-A1 this
    // wedged sActive FOREVER. WITH the CP-A1 watchdog, after stallTimeout cycles with no
    // committed byte the bridge ABORTS -> sFlush -> sIdle, sets sticky uploadError, and
    // uploadBusy CLEARS. drain()=400 cycles >> stallTimeout=64, so it must recover.
    clearObs()
    val f1 = fires()
    sdramWrite(0xB100, lenWords = 4, payload = Seq(0xAA, 0xBB, 0xCC, 0xDD))
    drain()
    val bBusy = dut.io.uploadBusy.toBoolean; val bErr = dut.io.uploadError.toBoolean; val bFires = fires() - f1
    line("B LEN/payload mismatch -> watchdog abort", !bBusy && bErr,
      f"uploadBusy=$bBusy(recovered) uploadError=$bErr(sticky) fires=+$bFires")

    // ---- CASE B2: SELF-HEAL — a perfectly-formed write AFTER the abort MUST land now
    // that the bridge recovered to idle. PASS = uploadBusy clears AND the 4 bytes commit
    // (fires += 4). uploadError stays sticky (set in B) until the host clears it.
    clearObs()
    val f2 = fires()
    sdramWrite(0xB300, lenWords = 2, payload = Seq(0x01, 0x02, 0x03, 0x04))
    drain()
    val healBusy = dut.io.uploadBusy.toBoolean; val healFires = fires() - f2
    line("B2 good write after abort (self-heal)", !healBusy && healFires == 4,
      f"uploadBusy=$healBusy fires=+$healFires => RECOVERED, write landed")

    // ---- CASE C: TIGHT CS-to-SCK setup on READ_STATUS (csSetup=1). HYPOTHESIS was
    // that the slave misses the leading nibble (0x04 -> 0x40). RESULT: it does NOT —
    // the 2-FF CS sync + the H-cycle SCK-low lead-in mean the first edge still lands
    // after cs_start. PASS = slave decoded 0x04 correctly (i.e. ROBUST to a 1-cycle
    // setup). The HW 0x40 is therefore NOT a simple tight-CS nibble slip — origin open.
    clearObs()
    readStatus(6, csSetup = 1)
    drain()
    line("C tight-CS READ_STATUS robustness", capOpcode == 0x04,
      f"decodedOpcode=0x$capOpcode%02X (0x04=robust; 0x40 NOT reproduced by tight-CS)")

    // ---- CASE D: TIGHT CS-to-SCK setup on a WRITE. Same finding: header decodes
    // correctly (opcode 0x02). Slave framing robust to a 1-cycle setup at fixed rate.
    clearObs()
    sdramWrite(0xB200, lenWords = 2, payload = Seq(0x55, 0x66, 0x77, 0x88), csSetup = 1)
    drain()
    line("D tight-CS WRITE robustness", capOpcode == 0x02,
      f"decodedOpcode=0x$capOpcode%02X (0x02=robust; header NOT corrupted by tight-CS)")

    println("QspiWriteStatusReproSim: done")
  }
}
