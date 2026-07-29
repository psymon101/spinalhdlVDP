package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer

/** HAM6-removal #14261 — end-to-end upload integrity co-sim.
  *
  * Drives the REAL word-drain QSPI transport + REAL QspiSdramBridge + REAL
  * StreamFifoCC crossing + REAL sdram.v controller + SDRAM model, exactly as
  * wired in TopTang20kHdmi (minus display fetch clients), uploads a full
  * checkerboard bitmap, then reads back the SDRAM content and compares it
  * byte-for-byte.
  *
  * This closes the simulation gap between:
  *   - QspiWordDrainSim  (transport decode only)
  *   - QspiSdramBridgeSim (bridge byte ordering only)
  *   - Indexed2bppBwCosim (display fetch from preloaded SDRAM only)
  *
  * If this sim passes at a given virtual SCLK frequency but the bench fails at
  * the same frequency, the defect is outside the digital RTL (SI / host driver /
  * PCB). If this sim fails, we have a deterministic RTL bug to fix.
  *
  * Run: sbt "runMain spinalhdlvdp.QspiUploadIntegritySim"
  */
object QspiUploadIntegritySim extends App {

  /** Upload-only DUT: QSPI front-end -> bridge -> CC FIFO -> SDRAM controller.
    * Mirrors TopTang20kHdmi uploadPopArea but without display-fetch clients.
    * Testbench gets a direct read/write port to the SDRAM controller for preload
    * and readback when the upload path is idle.
    *
    * Pixel clock is the Component default domain; SDRAM clock is external. This
    * matches TopTang20kHdmi, where qspiCore.io.clk is driven from the same signal
    * that defines the pixel ClockDomain. */
  class Harness extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))

    val io = new Bundle {
      val clkSdram    = in Bool()
      val resetn      = in Bool()
      // QSPI pads
      val sclk        = in Bool()
      val csn         = in Bool()
      val ioIn        = in Bits(4 bits)
      val ioOut       = out Bits(4 bits)
      val ioOe        = out Bool()
      // Testbench direct SDRAM access port (sdramCd)
      val testRd      = in  Bool()
      val testWr      = in  Bool()
      val testAddr    = in  UInt(23 bits)
      val testDin     = in  Bits(8 bits)
      // Status
      val uploadDone  = out Bool()
      val uploadBusy  = out Bool()
      val uploadError = out Bool()
      val fifoOverflow= out Bool()
      val overflow    = out Bool()
      val malformed   = out Bool()
      val sdramBusy   = out Bool()
      val dout32      = out Bits(32 bits)
      val dataReady   = out Bool()
      // Debug: bridge ingress visibility
      val dbgHeaderValid = out Bool()
      val dbgByteValid   = out Bool()
      val dbgWrCmdValid  = out Bool()
      val dbgWrCmdReady  = out Bool()
      val dbgCoreHeaderValid = out Bool()
      val dbgCoreByteValid   = out Bool()
    }

    // Default domain = pixel clock. QspiTransportCore's internal sysCd is built
    // from io.clk, which we drive from the default domain clock wire.
    val qspiCore = QspiTransportCore(fifoDepth = 512, dummyCycles = 2)
    qspiCore.io.clk  := ClockDomain.current.readClockWire
    qspiCore.io.csn  := io.csn
    qspiCore.io.sclk := io.sclk
    qspiCore.io.ioIn := io.ioIn
    qspiCore.io.debug_sdram_data := B(0, 32 bits)

    val bridge = QspiSdramBridge()
    // QspiTransportCore builds its own sysCd from io.clk. In the real top-level
    // (TopTang20kHdmi) io.clk is the same wire as the pixel ClockDomain. SpinalHDL
    // cannot prove that across the Component boundary in this standalone sim, so we
    // mark these synchronous connections explicitly. They are physically in the same
    // effective clock domain (driven from the same oscillator wire).
    bridge.io.headerValid := qspiCore.io.sdramHeaderValid.addTag(crossClockDomain)
    bridge.io.addrInit    := qspiCore.io.sdramAddrInit.addTag(crossClockDomain)
    bridge.io.lenBytes    := qspiCore.io.sdramLenBytes.addTag(crossClockDomain)
    bridge.io.byteIn      := qspiCore.io.sdramByteOut.addTag(crossClockDomain)
    bridge.io.byteValid   := qspiCore.io.sdramByteValid.addTag(crossClockDomain)
    bridge.io.allowUpload := True

    io.ioOut := qspiCore.io.ioOut
    io.ioOe  := qspiCore.io.ioOe

    // Clock-crossing FIFO: pixel -> sdram, depth 128, 31-bit {addr(23), din(8)}.
    val uploadCc = StreamFifoCC(Bits(31 bits), depth = 128, pushClock = ClockDomain.current, popClock = sdramCd)
    uploadCc.io.push << bridge.io.wrCmd

    // Real SDRAM controller + model.
    val bb = SdramWithModel(freq = 40500000)
    bb.io.clk       := sdramCd.readClockWire
    bb.io.clk_sdram := io.clkSdram
    bb.io.resetn    := io.resetn
    bb.io.burstLen  := U(1, 4 bits)

    // Upload pop area (mirrors TopTang20kHdmi): pop from FIFO and write to SDRAM
    // whenever the controller is idle. No display clients here, so gating is just
    // !busy. A simple refresh counter keeps the SDRAM model alive.
    val popArea = new ClockingArea(sdramCd) {
      val refreshCounter = Reg(UInt(10 bits)) init 1
      val doRefresh = refreshCounter === 0
      refreshCounter := refreshCounter + 1

      uploadCc.io.pop.ready := !bb.io.busy && !doRefresh && !io.testRd && !io.testWr
      val fire = uploadCc.io.pop.fire
      val addr = uploadCc.io.pop.payload(30 downto 8).asUInt
      val din  = uploadCc.io.pop.payload(7 downto 0)
    }

    // Mux: testbench owns the controller when it asserts rd/wr; otherwise upload pop.
    bb.io.rd      := io.testRd || popArea.fire
    bb.io.wr      := io.testWr || popArea.fire
    bb.io.refresh := popArea.doRefresh && !bb.io.busy && !io.testRd && !io.testWr
    bb.io.addr    := Mux(io.testRd || io.testWr, io.testAddr, popArea.addr)
    bb.io.din     := Mux(io.testWr, io.testDin, popArea.din)

    io.uploadDone  := bridge.io.uploadDone
    io.uploadBusy  := bridge.io.uploadBusy
    io.uploadError := bridge.io.uploadError
    io.fifoOverflow:= bridge.io.fifoOverflow
    io.overflow    := qspiCore.io.overflow
    io.malformed   := qspiCore.io.malformed
    io.sdramBusy   := bb.io.busy
    io.dout32      := bb.io.dout32
    io.dataReady   := bb.io.data_ready
    io.dbgHeaderValid := bridge.io.headerValid
    io.dbgByteValid   := bridge.io.byteValid
    io.dbgWrCmdValid  := bridge.io.wrCmd.valid
    io.dbgWrCmdReady  := bridge.io.wrCmd.ready
    io.dbgCoreHeaderValid := qspiCore.io.sdramHeaderValid.addTag(crossClockDomain)
    io.dbgCoreByteValid   := qspiCore.io.sdramByteValid.addTag(crossClockDomain)
  }

  /** Build the same 2bpp checkerboard the firmware uploads.
    * Default 320x240; can be shrunk for fast smoke tests. */
  def buildCheckerboard(width: Int = 320, height: Int = 240, square: Int = 32): Array[Byte] = {
    val stride = 128
    val buf = new Array[Byte](height * stride)
    for (y <- 0 until height) {
      val rowBase = y * stride
      val sy = y / square
      for (x <- 0 until width) {
        val sx = x / square
        val color = if (((sx ^ sy) & 1) != 0) 1 else 0
        val byteIdx = x / 4
        val shift = 6 - (x & 3) * 2
        buf(rowBase + byteIdx) = (buf(rowBase + byteIdx) | (color << shift)).toByte
      }
    }
    buf
  }

  def runCase(sclkPeriodNs: Int, label: String): Boolean = {
    var pass = true
    println(s"\n=== QspiUploadIntegritySim: $label (SCLK period=${sclkPeriodNs}ns) ===")

    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile(new Harness()).doSim { dut =>
      val sysPeriod = 37   // ~27 MHz pixel-side; matches QspiWordDrainSim.
      dut.clockDomain.forkStimulus(period = sysPeriod)

      // SDRAM 40.5 MHz controller clock, plus 180-degree phased SDRAM chip clock.
      dut.sdramCd.forkStimulus(period = 24)
      dut.io.clkSdram #= false
      fork { sleep(12); while (true) { dut.io.clkSdram #= !dut.io.clkSdram.toBoolean; sleep(12) } }

      dut.io.sclk #= false
      dut.io.csn  #= true
      dut.io.ioIn #= 0
      dut.io.testRd #= false
      dut.io.testWr #= false
      dut.io.testAddr #= 0
      dut.io.testDin  #= 0
      // DEBUG: do not assert default-domain reset; QspiTransportCore's sysCd uses BOOT
      // reset and the SCLK-domain reset is CS#. The SDRAM controller still needs its
      // reset, but we apply that separately below.
      dut.io.resetn #= true
      dut.sdramCd.waitSampling(20)

      // Wait for SDRAM controller init (measured in SDRAM clock cycles).
      println("  [dbg] waiting for SDRAM controller init...")
      var i = 0
      while (dut.io.sdramBusy.toBoolean && i < 20000) {
        dut.sdramCd.waitSampling(); i += 1
        if (i % 5000 == 0) println(s"  [dbg] init wait $i cycles, busy=${dut.io.sdramBusy.toBoolean}")
      }
      println(s"  [dbg] SDRAM init done after $i cycles, busy=${dut.io.sdramBusy.toBoolean}")
      assert(!dut.io.sdramBusy.toBoolean, s"SDRAM controller init timeout (busy stayed high for $i cycles)")

      // QSPI helpers (mode 0, quad IO).
      def clk(): Unit = {
        dut.io.sclk #= true; sleep(sclkPeriodNs / 2)
        dut.io.sclk #= false; sleep(sclkPeriodNs - sclkPeriodNs / 2)
      }
      def sendSingle(v: BigInt, bits: Int): Unit = {
        for (i <- (bits - 1) to 0 by -1) {
          dut.io.ioIn #= (((v >> i) & 1).toInt)
          sleep(sclkPeriodNs / 2); clk()
        }
      }
      def sendQuad(bytes: Seq[Int]): Unit = {
        for (b <- bytes) {
          dut.io.ioIn #= ((b >> 4) & 0xF); sleep(sclkPeriodNs / 2); clk()
          dut.io.ioIn #= (b & 0xF); sleep(sclkPeriodNs / 2); clk()
        }
      }
      def startTxn(): Unit = { dut.io.csn #= false; sleep(2 * sclkPeriodNs) }
      def endTxn():   Unit = { dut.io.sclk #= false; dut.io.csn #= true; sleep(6 * sclkPeriodNs) }
      def hdr(cmd: Int, addr: BigInt): Unit = { sendSingle(cmd, 8); sendSingle(addr, 24) }
      def lenBytes(words: Int): Seq[Int] = Seq(words & 0xFF, (words >> 8) & 0xFF)

      val baseAddr = 0x1000

      // --- Fast smoke test: 16x16 checkerboard (128 bytes = 64 words) ---
      def smoke(bitmap: Array[Byte]): Boolean = {
        // First: prove the QSPI core is alive by reading magic sel=0.
        println("  [dbg] READ_STATUS sel=0 magic check...")
        startTxn()
        hdr(0x04, 0)
        for (_ <- 0 until 1) { clk() }
        val nibs = ArrayBuffer[Int]()
        for (_ <- 0 until 8) { clk(); if (dut.io.ioOe.toBoolean) nibs += (dut.io.ioOut.toInt & 0xF) else nibs += -1 }
        endTxn()
        var magic = BigInt(0)
        for (byteIdx <- 0 until 4) {
          val b = ((nibs(byteIdx * 2) & 0xF) << 4) | (nibs(byteIdx * 2 + 1) & 0xF)
          magic = magic | (BigInt(b) << (byteIdx * 8))
        }
        println(f"  [dbg] magic sel=0 = 0x$magic%08X")

        // Prime the byte path with a tiny REG_WRITE (mirrors QspiWordDrainSim ordering).
        println("  [dbg] REG_WRITE prime...")
        startTxn()
        hdr(0x01, 0x0000)
        sendQuad(lenBytes(1) ++ Seq(0x34, 0x12))
        endTxn()
        sleep(50000)

        // DEBUG: use the exact 64-word pattern from QspiWordDrainSim Test C.
        val nWords = 64
        val scDataBytes = (0 until nWords).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }
        println(s"  [dbg] smoke upload nWords=$nWords (exact QspiWordDrainSim Test C pattern)")
        startTxn()
        hdr(0x02, baseAddr)
        sendQuad(lenBytes(nWords) ++ scDataBytes)
        endTxn()

        var guard = 0
        var lastHeaderValid = false
        var lastCoreHeaderValid = false
        var headerRises = 0
        var byteValids = 0
        var coreHeaderRises = 0
        var coreByteValids = 0
        while ((dut.io.sdramBusy.toBoolean || !dut.io.uploadDone.toBoolean) && guard < 100000) {
          dut.clockDomain.waitSampling(); guard += 1
          val hv = dut.io.dbgHeaderValid.toBoolean
          if (hv && !lastHeaderValid) headerRises += 1
          lastHeaderValid = hv
          if (dut.io.dbgByteValid.toBoolean) byteValids += 1
          val chv = dut.io.dbgCoreHeaderValid.toBoolean
          if (chv && !lastCoreHeaderValid) coreHeaderRises += 1
          lastCoreHeaderValid = chv
          if (dut.io.dbgCoreByteValid.toBoolean) coreByteValids += 1
          if (guard % 20000 == 0) {
            println(f"  [dbg] smoke upload wait $guard cycles, sdramBusy=${dut.io.sdramBusy.toBoolean}, uploadDone=${dut.io.uploadDone.toBoolean}, uploadBusy=${dut.io.uploadBusy.toBoolean}")
            println(f"          headerRises=$headerRises byteValids=$byteValids coreHeaderRises=$coreHeaderRises coreByteValids=$coreByteValids wrCmdValid=${dut.io.dbgWrCmdValid.toBoolean} wrCmdReady=${dut.io.dbgWrCmdReady.toBoolean} uploadError=${dut.io.uploadError.toBoolean} fifoOverflow=${dut.io.fifoOverflow.toBoolean}")
          }
        }
        println(s"  [dbg] smoke upload ended guard=$guard busy=${dut.io.sdramBusy.toBoolean} uploadDone=${dut.io.uploadDone.toBoolean} uploadBusy=${dut.io.uploadBusy.toBoolean}")
        println(f"  [dbg] totals: headerRises=$headerRises byteValids=$byteValids coreHeaderRises=$coreHeaderRises coreByteValids=$coreByteValids uploadError=${dut.io.uploadError.toBoolean} fifoOverflow=${dut.io.fifoOverflow.toBoolean}")
        assert(dut.io.uploadDone.toBoolean, "uploadDone never asserted")
        dut.sdramCd.waitSampling(500)
        true
      }
      smoke(buildCheckerboard(16, 16, 8))

      // --- Full checkerboard ---
      val bitmap = buildCheckerboard()
      val nWords = bitmap.length / 2
      println(s"  [dbg] full upload nWords=$nWords")

      // Send SDRAM_WRITE (CMD=0x02) for the full checkerboard.
      startTxn()
      hdr(0x02, baseAddr)
      sendQuad(lenBytes(nWords) ++ bitmap.map(_ & 0xFF))
      endTxn()

      // Wait for bridge done and FIFO/SDRAM drain.
      var guard = 0
      while ((dut.io.sdramBusy.toBoolean || !dut.io.uploadDone.toBoolean) && guard < 500000) {
        dut.clockDomain.waitSampling(); guard += 1
        if (guard % 50000 == 0) println(s"  [dbg] full upload wait $guard cycles, busy=${dut.io.sdramBusy.toBoolean}, uploadDone=${dut.io.uploadDone.toBoolean}")
      }
      println(s"  [dbg] full upload ended guard=$guard busy=${dut.io.sdramBusy.toBoolean} uploadDone=${dut.io.uploadDone.toBoolean}")
      assert(dut.io.uploadDone.toBoolean, "uploadDone never asserted")
      // Extra drain cycles for SDRAM writes to retire.
      dut.sdramCd.waitSampling(2000)

      val overflow = dut.io.overflow.toBoolean
      val malformed = dut.io.malformed.toBoolean
      println(f"  uploadDone after $guard cycles  overflow=$overflow  malformed=$malformed")

      if (overflow || malformed) {
        println(s"  [FAIL] transport error flags set: overflow=$overflow malformed=$malformed")
        pass = false
      }

      // Read back via direct controller reads and compare (SDRAM controller clock domain).
      def waitNotBusy(max: Int = 2000): Boolean = {
        var k = 0
        while (dut.io.sdramBusy.toBoolean && k < max) { dut.sdramCd.waitSampling(); k += 1 }
        !dut.io.sdramBusy.toBoolean
      }
      def readWord(addr: Int): Long = {
        waitNotBusy()
        dut.io.testAddr #= addr
        dut.io.testRd #= true
        dut.sdramCd.waitSampling()
        dut.io.testRd #= false
        var k = 0
        while (!dut.io.dataReady.toBoolean && k < 600) { dut.sdramCd.waitSampling(); k += 1 }
        val v = dut.io.dout32.toLong & 0xFFFFFFFFL
        dut.sdramCd.waitSampling(2)
        waitNotBusy()
        dut.sdramCd.waitSampling(2)
        v
      }

      // Prime the read path once, then compare every word.
      readWord(baseAddr)
      var mism = 0
      var checks = 0
      for (wordOff <- 0 until nWords by 2) {
        val addr = baseAddr + wordOff * 2
        val got = readWord(addr)
        val exp = (bitmap(wordOff * 2) & 0xFFL) |
                  ((bitmap(wordOff * 2 + 1) & 0xFFL) << 8) |
                  ((bitmap(wordOff * 2 + 2) & 0xFFL) << 16) |
                  ((bitmap(wordOff * 2 + 3) & 0xFFL) << 24)
        checks += 1
        if (got != exp) {
          mism += 1
          if (mism <= 5) println(f"  [mismatch] addr=0x$addr%06X got=0x$got%08X exp=0x$exp%08X")
        }
      }
      println(f"  readback: $checks words checked, $mism mismatches")
      if (mism != 0) pass = false

      println(if (pass) s"  $label PASS" else s"  $label FAIL")
    }
    pass
  }

  println("=== QspiUploadIntegritySim: end-to-end QSPI -> SDRAM upload integrity ===")
  // Test a few SCLK periods that bracket the bench operating points.
  // DEBUG: single 4 MHz case first to measure runtime and catch hangs.
  val cases = Seq(
    (250, "4 MHz")
  )
  var allPass = true
  for ((period, label) <- cases) {
    try {
      allPass &= runCase(period, label)
    } catch {
      case t: Throwable =>
        println(s"  $label THREW: ${t.getMessage}")
        allPass = false
    }
  }
  println(if (allPass) "\nQspiUploadIntegritySim: ALL CASES PASS" else "\nQspiUploadIntegritySim: SOME CASES FAIL")
}
