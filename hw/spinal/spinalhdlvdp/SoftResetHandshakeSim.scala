package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VDP-SOFT-RESET-135 increment #1 — host soft-reset HANDSHAKE proof.
  *
  * Cleanest direct test of the soft-reset controller: drive VdpTop's register
  * bus with a write to VDP_CTRL @ 0x0310 bit[2]=1 (0x0004 = SOFT_RESET_REQUEST)
  * and observe `io.softResetBusy`. Proves the host-facing contract that
  * BronzeGate's wrapper (write 0x0004, poll bit2) is built against:
  *   (a) busy is low at rest (no request),
  *   (b) busy asserts when a request is accepted,
  *   (c) the bounded sequence completes and busy drops — NO deadlock,
  *   (d) the request AUTO-CLEARS: busy stays low afterwards with no new write
  *       (a stuck request would immediately re-trigger), and
  *   (e) a second 0x0004 re-triggers cleanly (re-armable).
  *
  * Scope is the controller only — no i80 / arbiter / copper / video payload,
  * per the clean-minimal-test discipline. The i80 0x0310 live-status readback
  * mux (TopTang) is the trivial combinational dual of io.softResetBusy and is
  * exercised by the full i80 build; this sim isolates the controller logic.
  *
  * NOTE: increment #1 wires the request/busy/auto-clear handshake; the reset
  * ACTIONS (mem clear, SDRAM fill, core reg reset) land in later increments
  * inside the same bounded sequence without changing this contract.
  */
case class SoftResetDut() extends Component {
  val io = new Bundle {
    val regAddr       = in UInt (15 bits)
    val regData       = in Bits (16 bits)
    val regEnable     = in Bool ()
    val softResetBusy = out Bool ()
  }
  val vdp = VdpTop()
  vdp.palette.simPublic()
  vdp.spritePatternRams.head.simPublic()
  vdp.scrollTable0.mem.simPublic(); vdp.scrollTable1.mem.simPublic()
  vdp.vScrollTable0.mem.simPublic(); vdp.vScrollTable1.mem.simPublic()
  vdp.linestate.prepare.simPublic(); vdp.linestate.commit.simPublic()
  vdp.io.regBus.addr   := io.regAddr
  vdp.io.regBus.data   := io.regData
  vdp.io.regBus.enable := io.regEnable
  io.softResetBusy := vdp.io.softResetBusy

  // Tie off all other VdpTop inputs (quiescent — the controller is the DUT).
  vdp.io.layer0ScrollX := 0; vdp.io.layer0ScrollY := 0
  vdp.io.layer1ScrollX := 0; vdp.io.layer1ScrollY := 0
  vdp.io.layer2ScrollX := 0; vdp.io.layer2ScrollY := 0
  vdp.io.layer3ScrollX := 0; vdp.io.layer3ScrollY := 0
  vdp.io.sprite0X := 1023; vdp.io.sprite0Y := 1023; vdp.io.sprite0Enabled := False; vdp.io.sprite0PatternIdx := 0
  vdp.io.sprite1X := 1023; vdp.io.sprite1Y := 1023; vdp.io.sprite1Enabled := False; vdp.io.sprite1PatternIdx := 0
  vdp.io.sprite2X := 1023; vdp.io.sprite2Y := 1023; vdp.io.sprite2Enabled := False; vdp.io.sprite2PatternIdx := 0
  vdp.io.sprite3X := 1023; vdp.io.sprite3Y := 1023; vdp.io.sprite3Enabled := False; vdp.io.sprite3PatternIdx := 0
  vdp.io.layer0UseSdram := False; vdp.io.layer0SdramPixel := 0; vdp.io.layer0SdramBank := 0; vdp.io.layer0SdramPriority := False
  vdp.io.layer0TestPatternSelect := 0; vdp.io.layer0TestPatternEnable := False
  vdp.io.layer1UseSdram := False; vdp.io.layer1SdramPixel := 0; vdp.io.layer1SdramBank := 0; vdp.io.layer1SdramPriority := False
  vdp.io.bitmapSdramByte := 0; vdp.io.bitmapSdramAttrByte := 0
  vdp.io.rasterTriggerLine := 0; vdp.io.rasterTriggerPixel := 0; vdp.io.rasterTriggerPxEnable := False
  vdp.io.rasterTriggerEnable := False; vdp.io.rasterTriggerClear := False
  vdp.io.statusEvQspiReady := False; vdp.io.statusEvQspiError := False
  vdp.io.planarSdramBusy := False; vdp.io.planarSdramDataReady := False; vdp.io.planarSdramDout32 := 0
}

object SoftResetHandshakeSim extends App {
  Config.sim.compile(SoftResetDut()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def idle(): Unit = { dut.io.regEnable #= false; dut.io.regAddr #= 0; dut.io.regData #= 0 }
    def regWrite(addr: Int, data: Int): Unit = {
      dut.io.regAddr #= addr; dut.io.regData #= data; dut.io.regEnable #= true
      dut.clockDomain.waitSampling()
      idle(); dut.clockDomain.waitSampling()
    }

    idle(); dut.clockDomain.waitSampling(5)
    var fail = false

    // (a) busy low at rest
    if (dut.io.softResetBusy.toBoolean) { println("[sim] FAIL: busy high without a request"); fail = true }

    // Trigger a soft reset; return busy duration (or -1 on failure).
    def triggerAndMeasure(tag: String): Int = {
      regWrite(0x0310, 0x0004) // SOFT_RESET_REQUEST
      var guard = 0
      while (!dut.io.softResetBusy.toBoolean && guard < 10) { dut.clockDomain.waitSampling(); guard += 1 }
      if (!dut.io.softResetBusy.toBoolean) { println(s"[sim] FAIL ($tag): busy never asserted after 0x0004"); fail = true; return -1 }
      var cyc = 0
      while (dut.io.softResetBusy.toBoolean && cyc < 20000) { dut.clockDomain.waitSampling(); cyc += 1 }
      if (dut.io.softResetBusy.toBoolean) { println(s"[sim] FAIL ($tag): busy stuck high (deadlock) > 20000 cyc"); fail = true; return -1 }
      println(f"[sim] ($tag) busy duration = $cyc cycles")
      cyc
    }

    // ===== #2a memory-zeroing proof =====
    // Preset palette + sprite-pattern Mems to non-zero via the sim API (tests the
    // zero-sweep directly, no host write protocol as a discriminator), then reset
    // and verify they read back zero. Indices sampled across each Mem's depth.
    val palIdx = Seq(0, 1, 64, 127)                  // PaletteDepth = 128
    val patIdx = Seq(0, 100, 8000, 16383)            // pattern RAM = 16384
    val sclIdx = Seq(0, 50, 127)                      // scroll tables = 128
    val lsIdx  = Seq(0, 100, 300, 479)                // linestate = 480 (vActive)
    for (i <- palIdx) dut.vdp.palette.setBigInt(i, BigInt("ABCDEF", 16))
    for (i <- patIdx) dut.vdp.spritePatternRams.head.setBigInt(i, BigInt(0xF))
    for (i <- sclIdx) {
      dut.vdp.scrollTable0.mem.setBigInt(i, BigInt(0x3AA)); dut.vdp.scrollTable1.mem.setBigInt(i, BigInt(0x355))
      dut.vdp.vScrollTable0.mem.setBigInt(i, BigInt(0x2CC)); dut.vdp.vScrollTable1.mem.setBigInt(i, BigInt(0x199))
    }
    for (i <- lsIdx) { dut.vdp.linestate.prepare.setBigInt(i, BigInt(0xFFF)); dut.vdp.linestate.commit.setBigInt(i, BigInt(0xFFF)) }
    dut.clockDomain.waitSampling(2)
    // sanity: presets took
    for (i <- palIdx) if (dut.vdp.palette.getBigInt(i) == 0) { println(s"[sim] FAIL: palette[$i] preset did not take"); fail = true }

    // (b)+(c) first reset asserts and completes (bounded)
    val d1 = triggerAndMeasure("first")

    // verify the swept Mems are now zero
    def chk(name: String, v: BigInt, i: Int): Unit = if (v != 0) { println(f"[sim] FAIL: $name[$i] = 0x$v%X after reset (expected 0)"); fail = true }
    for (i <- palIdx) chk("palette", dut.vdp.palette.getBigInt(i), i)
    for (i <- patIdx) chk("pattern", dut.vdp.spritePatternRams.head.getBigInt(i), i)
    for (i <- sclIdx) {
      chk("scrollTable0", dut.vdp.scrollTable0.mem.getBigInt(i), i); chk("scrollTable1", dut.vdp.scrollTable1.mem.getBigInt(i), i)
      chk("vScrollTable0", dut.vdp.vScrollTable0.mem.getBigInt(i), i); chk("vScrollTable1", dut.vdp.vScrollTable1.mem.getBigInt(i), i)
    }
    for (i <- lsIdx) { chk("ls.prepare", dut.vdp.linestate.prepare.getBigInt(i), i); chk("ls.commit", dut.vdp.linestate.commit.getBigInt(i), i) }
    if (!fail) println("[sim] palette + pattern RAM + scroll tables + linestate(prepare+commit) zeroed by the sweep")

    // (d) AUTO-CLEAR proof: with no new write, busy must STAY low. A request bit
    // that failed to auto-clear would immediately re-assert busy here.
    dut.clockDomain.waitSampling(64)
    if (dut.io.softResetBusy.toBoolean) { println("[sim] FAIL: busy re-asserted with no new request (request did not auto-clear)"); fail = true }

    // (e) re-armable: a second request re-triggers cleanly
    val d2 = triggerAndMeasure("second")
    if (d1 > 0 && d2 > 0 && d1 != d2) { println(f"[sim] FAIL: non-deterministic duration d1=$d1 d2=$d2"); fail = true }

    dut.clockDomain.waitSampling(50)
    if (dut.io.softResetBusy.toBoolean) { println("[sim] FAIL: busy still high at end"); fail = true }

    println(if (fail) "[sim] SoftResetHandshakeSim: FAIL"
            else "[sim] SoftResetHandshakeSim: PASS — handshake (bounded/auto-clear/re-armable) + #2a/#2b mem zeroing (palette, pattern, scroll x4, linestate prepare+commit)")
  }
}
