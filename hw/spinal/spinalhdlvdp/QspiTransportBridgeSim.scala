package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Diagnostic bridge-integration sim.
  *
  * Compiles the REAL QspiTransportCore + REAL QspiSdramBridge in a single shared
  * clock domain. Proves the SDRAM_WRITE byte path reaches the bridge's wrCmd sink
  * when both halves are clocked identically.
  *
  * Run: sbt "runMain spinalhdlvdp.QspiTransportBridgeSim"
  */
object QspiTransportBridgeSim extends App {

  class Top extends Component {
    val io = new Bundle {
      val clk      = in Bool()
      val sclk     = in Bool()
      val csn      = in Bool()
      val ioIn     = in Bits(4 bits)
      val ioOut    = out Bits(4 bits)
      val ioOe     = out Bool()
      val uploadDone = out Bool()
      val uploadBusy = out Bool()
      val wrCmdValid = out Bool()
      val wrCmdReady = in  Bool()
      val dbgHdrValid = out Bool()
      val dbgByteValid = out Bool()
      val dbgByteOut = out Bits(8 bits)
      val dbgBridgeHdrPushed = out Bool()
      val dbgBridgeBytePushed = out Bool()
      val dbgBridgeFsm = out Bits(3 bits)
    }

    // BOOT reset matches the original harness; the bridge StateMachine reaches sIdle and
    // fires correctly under it (verified — reset was NOT the bug; the measurement was).
    val sysCd = ClockDomain(clock = io.clk, config = ClockDomainConfig(resetKind = BOOT))

    // Don't wrap core in a ClockingArea; pass externalSysCd directly.
    val qspiCore = QspiTransportCore(fifoDepth = 512, dummyCycles = 2, externalSysCd = sysCd)
    qspiCore.io.csn  := io.csn
    qspiCore.io.sclk := io.sclk
    qspiCore.io.ioIn := io.ioIn
    qspiCore.io.debug_sdram_data := B(0, 32 bits)

    io.dbgHdrValid  := qspiCore.io.sdramHeaderValid
    io.dbgByteValid := qspiCore.io.sdramByteValid
    io.dbgByteOut   := qspiCore.io.sdramByteOut

    val bridge = new ClockingArea(sysCd) { val logic = QspiSdramBridge() }.logic
    io.dbgBridgeHdrPushed  := bridge.io.dbgHdrPushed
    io.dbgBridgeBytePushed := bridge.io.dbgBytePushed
    io.dbgBridgeFsm        := bridge.io.dbgFsmState
    bridge.io.headerValid := qspiCore.io.sdramHeaderValid
    bridge.io.addrInit    := qspiCore.io.sdramAddrInit
    bridge.io.lenBytes    := qspiCore.io.sdramLenBytes
    bridge.io.byteIn      := qspiCore.io.sdramByteOut
    bridge.io.byteValid   := qspiCore.io.sdramByteValid
    bridge.io.allowUpload := True
    bridge.io.wrCmd.ready := io.wrCmdReady

    io.ioOut      := qspiCore.io.ioOut
    io.ioOe       := qspiCore.io.ioOe
    io.uploadDone := bridge.io.uploadDone
    io.uploadBusy := bridge.io.uploadBusy
    io.wrCmdValid := bridge.io.wrCmd.valid
  }

  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile(new Top()).doSim { dut =>
    val sysPeriod = 37
    dut.io.clk  #= false; dut.io.sclk #= false; dut.io.csn #= true; dut.io.ioIn #= 0
    dut.io.wrCmdReady #= true

    fork {
      while (true) {
        dut.io.clk #= true;  sleep(sysPeriod / 2)
        dut.io.clk #= false; sleep(sysPeriod - sysPeriod / 2)
      }
    }

    // Shared measurement (updated by the continuous monitor fork below). The FSM fires all
    // wrCmds and pulses uploadDone DURING the SCLK transaction; a main-loop sampler that starts
    // after the send races past them (SilentCrane's false "wrFires=0"). Count continuously.
    var monWrFires = 0
    var uploadDoneSeen = false

    fork {
      var pClk = false
      var nHdr = 0
      var nByte = 0
      var nBridgeHdr = 0
      var nBridgeByte = 0
      var pFsm = -1
      while (true) {
        sleep(1)
        val c = dut.io.clk.toBoolean
        if (c && !pClk) {
          if (dut.io.dbgHdrValid.toBoolean) { nHdr += 1; println(s"[mon] sdramHeaderValid nHdr=$nHdr") }
          if (dut.io.dbgByteValid.toBoolean) { nByte += 1; if (nByte <= 10 || nByte % 16 == 0) println(s"[mon] sdramByte nByte=$nByte data=${dut.io.dbgByteOut.toInt}") }
          if (dut.io.dbgBridgeHdrPushed.toBoolean) { nBridgeHdr += 1; println(s"[mon] bridgeHdrPushed nBridgeHdr=$nBridgeHdr") }
          if (dut.io.dbgBridgeBytePushed.toBoolean) { nBridgeByte += 1; if (nBridgeByte <= 10 || nBridgeByte % 16 == 0) println(s"[mon] bridgeBytePushed nBridgeByte=$nBridgeByte") }
          val fsmv = dut.io.dbgBridgeFsm.toInt   // {sIdle,sActive,sDone}
          if (fsmv != pFsm) { println(s"[mon] FSM state {sIdle,sActive,sDone}=0b${fsmv.toBinaryString} uploadBusy=${dut.io.uploadBusy.toBoolean} wrCmdValid=${dut.io.wrCmdValid.toBoolean}"); pFsm = fsmv }
          // Count wrCmd fires CONTINUOUSLY (main loop starts too late and misses fires during the txn).
          if (dut.io.wrCmdValid.toBoolean && dut.io.wrCmdReady.toBoolean) { monWrFires += 1; if (monWrFires <= 3 || monWrFires % 32 == 0 || monWrFires == 128) println(s"[mon] wrCmd FIRE monWrFires=$monWrFires") }
          if (dut.io.uploadDone.toBoolean) { uploadDoneSeen = true; println(s"[mon] uploadDone PULSE seen at monWrFires=$monWrFires") }
        }
        pClk = c
      }
    }

    sleep(10 * sysPeriod)

    val sclkPeriod = 250
    def clk(): Unit = { dut.io.sclk #= true; sleep(sclkPeriod / 2); dut.io.sclk #= false; sleep(sclkPeriod - sclkPeriod / 2) }
    def sendSingle(v: BigInt, bits: Int): Unit = for (i <- (bits - 1) to 0 by -1) { dut.io.ioIn #= (((v >> i) & 1).toInt); sleep(sclkPeriod / 2); clk() }
    def sendQuad(bytes: Seq[Int]): Unit = for (b <- bytes) { dut.io.ioIn #= ((b >> 4) & 0xF); sleep(sclkPeriod / 2); clk(); dut.io.ioIn #= (b & 0xF); sleep(sclkPeriod / 2); clk() }
    def startTxn(): Unit = { dut.io.csn #= false; sleep(2 * sclkPeriod) }
    def endTxn():   Unit = { dut.io.sclk #= false; dut.io.csn #= true; sleep(6 * sclkPeriod) }
    def hdr(cmd: Int, addr: BigInt): Unit = { sendSingle(cmd, 8); sendSingle(addr, 24) }
    def lenBytes(words: Int): Seq[Int] = Seq(words & 0xFF, (words >> 8) & 0xFF)

    // SDRAM_WRITE 64 words @ 0x1000.
    val nWords = 64
    val data = (0 until nWords).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }
    println("[dbg] QspiTransportBridgeSim: sending SDRAM_WRITE...")
    startTxn()
    hdr(0x02, 0x1000)
    sendQuad(lenBytes(nWords) ++ data)
    endTxn()

    // Settle: the FSM completes the whole transaction (all 128 wrCmd fires + uploadDone pulse)
    // DURING the SCLK transaction above. Wait for the bridge to return to idle, then assert on
    // the CONTINUOUSLY-counted values (monWrFires / uploadDoneSeen) — not a post-hoc level sample.
    var guard = 0
    while (dut.io.uploadBusy.toBoolean && guard < 20000) { sleep(sysPeriod); guard += 1 }
    sleep(50 * sysPeriod)
    println(s"[dbg] ended: monWrFires=$monWrFires uploadDoneSeen=$uploadDoneSeen uploadBusy=${dut.io.uploadBusy.toBoolean}")

    assert(uploadDoneSeen, "uploadDone pulse never observed (continuous monitor)")
    assert(monWrFires == 128, s"expected 128 wrCmd fires, got $monWrFires")
    println("QspiTransportBridgeSim: PASS — core->bridge SDRAM_WRITE fires 128 wrCmds + uploadDone (integration correct)")
  }
}
