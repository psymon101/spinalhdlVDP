package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R5 Stage 5 — UnifiedRegMapSim.
  *
  * Coverage for the `regWrite*` decode inside `VdpTop`:
  *   1. Write to 0x0050 updates linestate prepare[50]; commit shows it.
  *   2. Write to 0x0300 updates LAYER_ENABLE — L1 disabled makes the red
  *      channel drop to palette-bank-0 idx-0 (black) where L1 was the
  *      only source.
  *   3. Write to 0x0400 updates copper program RAM; copperEnable then runs
  *      the program and emits a write on 0x0300 that layerEnableReg reflects
  *      in the compositor output.
  */
object UnifiedRegMapSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val hTotal = 800
    val vTotal = 525
    val hActive = 640

    // Defaults (match VdpTopSim conventions)
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.layer0UseSdram #= false
    dut.io.layer0SdramPixel #= 0; dut.io.layer0SdramBank #= 0; dut.io.layer0SdramPriority #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.regWriteAddr #= 0
    dut.io.regWriteData #= 0
    dut.io.regWriteEnable #= false
    dut.io.copperEnable #= false
    dut.clockDomain.waitSampling(5)

    def busWrite(addr: Int, data: Int): Unit = {
      dut.io.regWriteAddr #= addr
      dut.io.regWriteData #= data
      dut.io.regWriteEnable #= true
      dut.clockDomain.waitSampling()
      dut.io.regWriteEnable #= false
      dut.clockDomain.waitSampling()
    }

    def waitVsync(): Unit = {
      while (dut.io.vsync.toBoolean) dut.clockDomain.waitSampling()
      while (!dut.io.vsync.toBoolean) dut.clockDomain.waitSampling()
    }

    // Two vsyncs so the initial commit snapshot loads.
    waitVsync(); waitVsync()

    // ---- Case 1: linestate write via unified bus reaches prepare side ----
    // Write addr 50 with both-layers-disabled record; then write the same
    // slot's committed record via 2 vsyncs and confirm line 50 goes black.
    val disabledBoth = LinestateStore.packRecord(l0en = false, l1en = false, l0sx = 0).toInt
    busWrite(50, disabledBoth)
    // Wait enough vsyncs that line 50's prepare→commit cycle runs at least once.
    waitVsync(); waitVsync()
    // Walk to (0, 50) and sample.
    while (!(dut.io.de.toBoolean && dut.io.x.toInt == 0 && dut.io.y.toInt == 50)) {
      dut.clockDomain.waitSampling()
    }
    val rgb1 = (dut.io.red.toInt, dut.io.green.toInt, dut.io.blue.toInt)
    assert(rgb1 == (0, 0, 0), s"case1 linestate write: line 50 should be black, got $rgb1")
    println("[sim] case1 linestate write via 0x0050 — OK (line 50 = black)")

    // Restore line 50 to both-enabled so subsequent cases start clean.
    val bothEnabled = LinestateStore.packRecord(l0en = true, l1en = true, l0sx = 0).toInt
    busWrite(50, bothEnabled)
    waitVsync(); waitVsync()

    // ---- Case 2: LAYER_ENABLE at 0x0300 gates the compositor ----
    // Globally disable both L0 and L1; verify line 100 (which defaults to
    // both-enabled in linestate) becomes black.
    busWrite(0x0300, 0x0000)
    waitVsync()
    while (!(dut.io.de.toBoolean && dut.io.x.toInt == 0 && dut.io.y.toInt == 100)) {
      dut.clockDomain.waitSampling()
    }
    val rgb2 = (dut.io.red.toInt, dut.io.green.toInt, dut.io.blue.toInt)
    assert(rgb2 == (0, 0, 0), s"case2 LAYER_ENABLE=0: expected black, got $rgb2")
    println("[sim] case2 LAYER_ENABLE @ 0x0300 — OK (global off = black)")

    // Re-enable for case 3.
    busWrite(0x0300, 0x0007)
    waitVsync()

    // ---- Case 3: copper program write via 0x0400 + enable ----
    // Upload a tiny program: WAIT y=200, WRITE 0x0300=0x0000, JUMP 0.
    // Once copperEnable=true, line 200 onward should render black (L0+L1
    // both disabled by copper).
    def WAIT(y: Int): Int = (0 << 14) | (y & 0x3FF)
    def WRITE_OP(addr: Int): Int = (1 << 14) | (addr & 0x3FFF)
    def JUMP(pc: Int): Int = (3 << 14) | (pc & 0x1FF)
    val prog = Seq(
      WAIT(200),
      WRITE_OP(0x0300),
      0x0000,
      JUMP(0)
    )
    for ((w, i) <- prog.zipWithIndex) {
      busWrite(0x0400 + i, w)
    }
    // Reset LAYER_ENABLE to all-on so only copper can disable it.
    busWrite(0x0300, 0x0007)
    waitVsync()
    dut.io.copperEnable #= true
    waitVsync(); waitVsync()

    // Sample line 210 (after copper WAIT y=200 fires).
    while (!(dut.io.de.toBoolean && dut.io.x.toInt == 0 && dut.io.y.toInt == 210)) {
      dut.clockDomain.waitSampling()
    }
    val rgb3 = (dut.io.red.toInt, dut.io.green.toInt, dut.io.blue.toInt)
    assert(rgb3 == (0, 0, 0),
      s"case3 copper write via 0x0400+: line 210 should be black after WAIT+WRITE, got $rgb3")
    println("[sim] case3 copper program via 0x0400 + enable — OK (line 210 = black)")

    dut.io.copperEnable #= false
    waitVsync()

    // ---- Case 4 (R4.1b): VDP_TILE_MODE @ 0x0311 routes to fetch engine ----
    // Write 0x0311 = 1 via the regWrite bus; VdpTop's tileDecodeModeReg
    // should latch at the next hCounter=0, which then flows through the
    // layer0TileDecodeMode output port. Writing 0 resets it.
    busWrite(0x0311, 0x0001)
    // Give hCounter at least one pass through 0 to commit the latch.
    dut.clockDomain.waitSampling(hTotal + 10)
    assert(dut.io.layer0TileDecodeMode.toInt == 1,
      s"case4: after writing 0x0311=1, layer0TileDecodeMode should be 1, got ${dut.io.layer0TileDecodeMode.toInt}")
    println("[sim] case4 VDP_TILE_MODE @ 0x0311 = 1 — OK (planar mode latched)")

    busWrite(0x0311, 0x0000)
    dut.clockDomain.waitSampling(hTotal + 10)
    assert(dut.io.layer0TileDecodeMode.toInt == 0,
      s"case4b: after writing 0x0311=0, layer0TileDecodeMode should be 0, got ${dut.io.layer0TileDecodeMode.toInt}")
    println("[sim] case4b VDP_TILE_MODE back to packed — OK")

    println("[sim] UnifiedRegMapSim: PASS")
  }
}
