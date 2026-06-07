package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VdpInnerBorderEdgeSim — pixel-exact geometry discriminator for the
  * inner-border auto-compute feature (commit 7d600d9; CoralReef #11935
  * lower-left edge-fringe investigation).
  *
  * BACKGROUND (#11934/#11935): the owner observed a thin colour "fringe"
  * where the inner-border (red) meets the dark-blue content, visible on
  * RTSP AND a physical monitor, and stable (not flicker). BronzeGate
  * (#11934 test 3) showed it persists at native 640x480/1x, where the
  * PixelRepeatScaler is in BYPASS (outRgbReg := io.inRgb) — so it is NOT
  * the scaler line buffer or pixel replication. The only remaining RTL
  * suspect is the border-mux geometry.
  *
  * KEY LOGICAL POINT (why this is not a digital bug): with a SOLID border
  * colour and SOLID content colour, a pipeline-latency mismatch between the
  * border-select (borderActiveR) and the two colour sources (borderRgbR /
  * mathRgb) can only SHIFT the boundary by <=1 pixel — it cannot synthesize
  * a third "bleed" colour. Each source is constant within its region, so
  * the mux always emits one of the two pure colours. A reddish-blue bleed
  * sliver is not representable in the digital output, so it is an analog /
  * HDMI / H.264 chroma-transition artifact on the high-contrast
  * red<->dark-blue edge — confirm on bench with the same-colour test
  * (border palette idx == content colour: the edge disappears).
  *
  * This sim proves the digital geometry is EXACT by checking the computed
  * physical border rectangle (effBorderX0/X1/Y0/Y1) against hand-computed
  * expectations, and proves the underflow clamp produces a safe DEGENERATE
  * (zero-size) window — never an unsigned wrap — under over-range host
  * input (BrightForge #11915 / BronzeGate #11916 FINDING 1 sim coverage,
  * previously noted missing in BrightForge sign-off #11931).
  *
  * Geometry (VdpTop.scala:2361-2379), inner-border mode:
  *   effBorderX0 = ibOffX + ibL*ibScaleX
  *   effBorderX1 = ibOffX + (logicWidth  - ibRSafe)*ibScaleX
  *   effBorderY0 = ibOffY + ibT*ibScaleY
  *   effBorderY1 = ibOffY + (logicHeight - ibBSafe)*ibScaleY
  * with ibOffX/Y = scaler centring offset, ibScaleX/Y = clamped scale.
  *
  * The border comparator (insideBorder = hCounter>=effBorderX0 &&
  * hCounter<effBorderX1 && vCounter>=effBorderY0 && vCounter<effBorderY1)
  * is a direct >=/< on these coordinates, so a correct rectangle implies a
  * pixel-exact, gap-free, overlap-free digital boundary.
  */
object VdpInnerBorderEdgeSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent stimulus (mirror PlanarClipSim defaults).
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false
    dut.io.layer0UseSdram #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.planarSdramBusy      #= false
    dut.io.planarSdramDataReady #= false
    dut.io.planarSdramDout32    #= 0
    dut.clockDomain.waitSampling(5)

    def writeReg(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
      dut.clockDomain.waitSampling()
    }
    // Inner-border / scale / logic regs commit at hCounter===0; wait > hTotal (=800).
    def settle(): Unit = dut.clockDomain.waitSampling(1000)

    // Register addresses (PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md).
    val SCALE_CTRL  = 0x0349
    val LOGIC_W     = 0x034A
    val LOGIC_H     = 0x034B
    val BORDER_CTRL = 0x0347
    val IB_L = 0x034C; val IB_R = 0x034D; val IB_T = 0x034E; val IB_B = 0x034F

    // BORDER_CTRL: bit0=enable, bit1=innerBorderEnable, bits[12:8]=palette idx.
    def borderCtrlInner(idx: Int): Int = (idx << 8) | (1 << 1) | (1 << 0)

    def readRect(): (Int, Int, Int, Int) = (
      dut.effBorderX0.toInt, dut.effBorderX1.toInt,
      dut.effBorderY0.toInt, dut.effBorderY1.toInt
    )

    var failures = 0
    def check(name: String, got: Int, exp: Int): Unit = {
      val ok = got == exp
      if (!ok) failures += 1
      println(f"  [$name%-2s] got=$got%4d  exp=$exp%4d  ${if (ok) "OK" else "**FAIL**"}")
    }

    def configIB(scaleCtrl: Int, w: Int, h: Int, l: Int, r: Int, t: Int, b: Int): Unit = {
      writeReg(SCALE_CTRL, scaleCtrl)
      writeReg(LOGIC_W, w); writeReg(LOGIC_H, h)
      writeReg(IB_L, l); writeReg(IB_R, r); writeReg(IB_T, t); writeReg(IB_B, b)
      writeReg(BORDER_CTRL, borderCtrlInner(1))
      settle()
    }

    // -----------------------------------------------------------------
    // Case 1: native 640x480, 1x (scaler BYPASS), inner border 20/20/12/12.
    //   effBorderX0=20, X1=620, Y0=12, Y1=468.
    //   (Bypass path is exactly the mode where BronzeGate still saw the
    //    fringe in #11934 test 3 — so this is the relevant config.)
    // -----------------------------------------------------------------
    println("[sim] Case 1: native 640x480 @1x (BYPASS), inner border L20 R20 T12 B12")
    configIB(0x00, 640, 480, 20, 20, 12, 12) // scaleX=0->1x, scaleY=0->1x, autoCenter=0
    val (x0a, x1a, y0a, y1a) = readRect()
    check("X0", x0a, 20); check("X1", x1a, 620); check("Y0", y0a, 12); check("Y1", y1a, 468)
    println(f"  content window = [${x0a},${x1a}) x [${y0a},${y1a})  (${x1a - x0a}x${y1a - y0a})")

    // -----------------------------------------------------------------
    // Case 2: 320x240 @2x autoCenter, inner border 10/10/6/6 -> the SAME
    //   physical rectangle as Case 1. Proves the scale-multiply + centring
    //   offset path: effBorderX0 = offX(0) + 10*2 = 20, etc.
    // -----------------------------------------------------------------
    println("[sim] Case 2: 320x240 @2x autoCenter, inner border L10 R10 T6 B6 (must equal Case 1)")
    configIB(0xA2, 320, 240, 10, 10, 6, 6) // scaleX=2, scaleY=2, autoCenter=1
    val (x0b, x1b, y0b, y1b) = readRect()
    check("X0", x0b, 20); check("X1", x1b, 620); check("Y0", y0b, 12); check("Y1", y1b, 468)
    println(f"  content window = [${x0b},${x1b}) x [${y0b},${y1b})  (${x1b - x0b}x${y1b - y0b})")

    // -----------------------------------------------------------------
    // Case 3: clamp / underflow guard (FINDING 1 coverage). Over-range
    //   R=700 (>640) and B=600 (>480) on the native 1x config. The clamp
    //   must collapse the window to ZERO and must NOT unsigned-wrap:
    //     ibR clamps 700->640; ibL+ibR=660>640 -> ibRSafe=640-20=620;
    //     effBorderX1 = 640-620 = 20 = effBorderX0.   (Y mirrors: 12=12.)
    // -----------------------------------------------------------------
    println("[sim] Case 3: clamp guard — over-range R=700, B=600 must degenerate (no wrap)")
    configIB(0x00, 640, 480, 20, 700, 12, 600)
    val (x0c, x1c, y0c, y1c) = readRect()
    check("X0", x0c, 20); check("X1", x1c, 20); check("Y0", y0c, 12); check("Y1", y1c, 12)
    if (x1c > 640) { failures += 1; println(f"  **FAIL** X1=$x1c wrapped past hActive=640") }
    if (y1c > 480) { failures += 1; println(f"  **FAIL** Y1=$y1c wrapped past vActive=480") }
    println(f"  content window collapses to ${x1c - x0c}x${y1c - y0c} (expected 0x0) — safe degenerate, no wrap")

    println("")
    assert(failures == 0, s"VdpInnerBorderEdgeSim: $failures geometry mismatch(es) — inner-border rectangle NOT pixel-exact")
    println("VdpInnerBorderEdgeSim: PASS — inner-border rectangle is pixel-exact (1x + 2x agree); clamp degenerates safely with no unsigned wrap")
  }
}
