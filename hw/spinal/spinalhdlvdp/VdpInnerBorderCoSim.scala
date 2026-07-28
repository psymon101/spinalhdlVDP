package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import java.io.{File, FileOutputStream, PrintWriter, BufferedOutputStream}
import scala.collection.mutable


/** VdpInnerBorderCoSim — full-frame digital co-sim of BronzeGate's inner-border
  * test #3 (TopazCliff lane #11939; CoralReef/BronzeGate #11934 edge-fringe
  * investigation).
  *
  * WHY: the owner observed a thin colour "fringe" where the (red) inner border
  * meets the dark-blue content, visible on RTSP AND a physical monitor, stable
  * (not flicker), and persisting at native 640x480/1x where the PixelRepeatScaler
  * is in BYPASS (#11934 test 3 / 4). VdpInnerBorderEdgeSim already proved the
  * computed border RECTANGLE (effBorderX0/X1/Y0/Y1) is pixel-exact. This sim
  * goes one level further: it actually RENDERS a full frame through VdpTop and
  * dumps the digital RGB of every active pixel, so we can inspect the
  * border<->content boundary pixel-by-pixel and decide definitively:
  *   clean 1-pixel red/blue transition  -> fringe is analog / HDMI / capture chain
  *   any intermediate ("bleed") colour  -> there is an RTL bug to fix
  *
  * Replays test #3 exactly:
  *   LOGIC_WIDTH=640, LOGIC_HEIGHT=480, SCALE_CTRL=0x00 (1x/1x, autoCenter off),
  *   INNER_BORDER L/R/T/B = 20/20/12/12,
  *   BORDER_CTRL = enable | innerBorderEnable | paletteIdx=2 (red),
  *   BACKDROP_INDEX = 1 (dark blue). No planar, no copper, no layers/sprites.
  *
  * Output path note (VdpTop.scala:2427-2443): io.x, io.y, io.de and
  * io.red/green/blue are ALL emitted at the same +2 display pipeline depth, so
  * on any cycle where io.de is high the pixel (io.x, io.y) carries exactly the
  * colour (io.red, io.green, io.blue). Sampling is therefore alignment-free:
  * we key the framebuffer by (io.x, io.y).
  */
object VdpInnerBorderCoSim extends App {
  // ----- geometry / timing (must match VdpTop 640x480@60) -----
  val hActive = 640; val hTotal = 800
  val vActive = 480; val vTotal = 525
  val framePix = hTotal * vTotal

  // ----- test colours -----
  // Border = palette[2] = pure red. Content = palette[1] = the same dark blue
  // BronzeGate sampled at the bench centre in #11928 (1,18,150) so the digital
  // dump is directly comparable to the captured frame.
  val RED  = (255, 0, 0)
  val BLUE = (1, 18, 150)
  def pack(r: Int, g: Int, b: Int): Int = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
  val RED_P  = pack(RED._1, RED._2, RED._3)
  val BLUE_P = pack(BLUE._1, BLUE._2, BLUE._3)

  val outDir = new File("/tmp/inner_border_cosim"); outDir.mkdirs()

  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // ---- quiescent stimulus (mirror VdpInnerBorderEdgeSim / PlanarClipSim) ----
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.layer2ScrollX #= 0; dut.io.layer2ScrollY #= 0
    dut.io.layer3ScrollX #= 0; dut.io.layer3ScrollY #= 0
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
    dut.io.layer1UseSdram #= false
    dut.io.layer1SdramPixel #= 0
    dut.io.layer1SdramBank #= 0
    dut.io.layer1SdramPriority #= false
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

    // Register addresses (PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md).
    val PALETTE_DATA = 0x0600; val PALETTE_PTR = 0x0601
    val BORDER_CTRL  = 0x0347; val BACKDROP_IDX = 0x0348
    val SCALE_CTRL   = 0x0349; val LOGIC_W = 0x034A; val LOGIC_H = 0x034B
    val IB_L = 0x034C; val IB_R = 0x034D; val IB_T = 0x034E; val IB_B = 0x034F

    // Palette write: PTR=entry*2, then DATA={G,B} (first) then DATA={0,R} (commit).
    // commit = effData[7:0] ## acc16 = {R, G, B} (VdpTop.scala:1861).
    def writePalette(idx: Int, r: Int, g: Int, b: Int): Unit = {
      writeReg(PALETTE_PTR, idx * 2)
      writeReg(PALETTE_DATA, ((g & 0xFF) << 8) | (b & 0xFF))
      writeReg(PALETTE_DATA, r & 0xFF)
    }
    // BORDER_CTRL: bit0=enable, bit1=innerBorderEnable, bits[12:8]=palette idx.
    def borderCtrlInner(idx: Int): Int = (idx << 8) | (1 << 1) | (1 << 0)

    // ---- program test #3 ----
    writePalette(1, BLUE._1, BLUE._2, BLUE._3)   // content / backdrop = dark blue
    writePalette(2, RED._1,  RED._2,  RED._3)    // inner+outer border = red
    writeReg(BACKDROP_IDX, 1)                    // backdrop fallthrough -> palette[1]
    writeReg(SCALE_CTRL, 0x00)                   // 1x/1x, autoCenter off (scaler BYPASS)
    writeReg(LOGIC_W, 640); writeReg(LOGIC_H, 480)
    writeReg(IB_L, 20); writeReg(IB_R, 20); writeReg(IB_T, 12); writeReg(IB_B, 12)
    writeReg(BORDER_CTRL, borderCtrlInner(2))

    // Safe-boundary regs commit at hCounter===0; let several lines pass.
    dut.clockDomain.waitSampling(2000)

    // Warm-up: `primed` only asserts at the first full-frame end (VdpTop:262),
    // and output is gated by deRR && primedRR. Run one full frame so the next
    // frame renders with primed=true and all safe-boundary regs committed.
    dut.clockDomain.waitSampling(framePix)

    // ---- capture one full frame, keyed by (io.x, io.y) ----
    val UNSET = -1
    val fb = Array.fill(vActive * hActive)(UNSET)
    var deCycles = 0
    var i = 0
    val bgHistory = mutable.Queue[Int]()
    // +hTotal slack so the +2 pipeline boundary cannot drop the last line.
    while (i < framePix + hTotal) {
      dut.clockDomain.waitSampling()
      val bgVal = dut.bgOrDirectRgb.toInt & 0xFFFFFF
      bgHistory.enqueue(bgVal)
      if (bgHistory.size > 2) {
        val delayedBg = bgHistory.dequeue()
        if (dut.io.de.toBoolean) {
          val x = dut.io.x.toInt
          val y = dut.io.y.toInt
          // Inside the active content area (accounting for the 2-cycle pin latency, so x-2 >= 20
          // and x-2 < 620, i.e., x >= 22 && x < 620), the output pin color (stage +4) must
          // match the compositor background bgOrDirectRgb (stage +2) delayed by 2 cycles.
          if (x >= 22 && x < 620 && y >= 12 && y < 468) {
            val r = dut.io.red.toInt; val g = dut.io.green.toInt; val b = dut.io.blue.toInt
            val pinColor = pack(r, g, b)
            assert(pinColor == delayedBg, s"Pipeline alignment assertion failed: pinColor 0x${pinColor.toHexString} vs bg 0x${delayedBg.toHexString} at (x=$x, y=$y)")
          }
        }
      }
      if (dut.io.de.toBoolean) {
        val x = dut.io.x.toInt
        val y = dut.io.y.toInt
        if (x < hActive && y < vActive) {
          val r = dut.io.red.toInt; val g = dut.io.green.toInt; val b = dut.io.blue.toInt
          fb(y * hActive + x) = pack(r, g, b)
          deCycles += 1
        }
      }
      i += 1
    }

    // ---- coverage check ----
    val unsampled = fb.count(_ == UNSET)
    println(s"[cosim] de-active samples captured: $deCycles; unsampled active pixels: $unsampled")
    assert(unsampled == 0, s"frame incompletely sampled ($unsampled active pixels missed) — capture window too short")

    // ---- write PPM (P6) for Python/Pillow inspection ----
    val ppm = new File(outDir, "inner_border_cosim.ppm")
    val os = new BufferedOutputStream(new FileOutputStream(ppm))
    os.write(s"P6\n$hActive $vActive\n255\n".getBytes("US-ASCII"))
    val row = new Array[Byte](hActive * 3)
    for (y <- 0 until vActive) {
      for (x <- 0 until hActive) {
        val p = fb(y * hActive + x)
        row(x * 3 + 0) = ((p >> 16) & 0xFF).toByte
        row(x * 3 + 1) = ((p >> 8) & 0xFF).toByte
        row(x * 3 + 2) = (p & 0xFF).toByte
      }
      os.write(row)
    }
    os.close()
    println(s"[cosim] wrote ${ppm.getAbsolutePath}")

    // ---- boundary pixel report ----
    val rep = new PrintWriter(new File(outDir, "boundary_report.txt"))
    def emit(s: String): Unit = { println(s); rep.println(s) }
    def rgb(p: Int): String =
      if (p == UNSET) "UNSAMPLED" else f"(${(p >> 16) & 0xFF}%3d,${(p >> 8) & 0xFF}%3d,${p & 0xFF}%3d)"
    def label(p: Int): String = p match {
      case `RED_P`  => "RED  (border)"
      case `BLUE_P` => "BLUE (content)"
      case `UNSET`  => "----"
      case _        => "**OTHER**"
    }
    def at(x: Int, y: Int): Int = fb(y * hActive + x)
    def strip(name: String, xs: Range, ys: Range): Unit = {
      emit(s"  $name:")
      for (y <- ys; x <- xs) emit(f"    (x=$x%3d,y=$y%3d) ${rgb(at(x, y))} ${label(at(x, y))}")
    }

    emit("")
    emit("=== Inner-border digital boundary report (test #3, 640x480 @1x BYPASS) ===")
    emit(s"  border  = palette[2] RED  ${rgb(RED_P)}")
    emit(s"  content = palette[1] BLUE ${rgb(BLUE_P)}")
    emit("  Expected geometry: content rect [20,620) x [12,468); border elsewhere.")
    emit("")
    // Left edge transition at a mid content row (boundary x=19 border | x=20 content)
    strip("LEFT  edge  @ y=240, x=15..25", 15 to 25, 240 to 240)
    // Right edge transition (boundary x=619 content | x=620 border)
    strip("RIGHT edge  @ y=240, x=615..624", 615 to 624, 240 to 240)
    // Top edge transition (boundary y=11 border | y=12 content) — task's y=12 row
    strip("TOP   edge  @ x=320, y=7..17", 320 to 320, 7 to 17)
    // Bottom edge transition (boundary y=467 content | y=468 border) — task's y=467..468
    strip("BOT   edge  @ x=320, y=463..472", 320 to 320, 463 to 472)
    // Task's explicit sample sets
    strip("TASK left-row  @ y=12,  x=19..24", 19 to 24, 12 to 12)
    strip("TASK right-col @ y=240, x=619..620", 619 to 620, 240 to 240)
    // Lower-left corner region (the reported fringe location): both edges meet
    strip("LOWER-LEFT corner @ x=18..22, y=466..469", 18 to 22, 466 to 469)

    // ---- full-frame purity scan ----
    emit("")
    emit("=== Full-frame purity scan (every active pixel must be RED or BLUE) ===")
    var other = 0
    val firstOffenders = scala.collection.mutable.ArrayBuffer[String]()
    for (y <- 0 until vActive; x <- 0 until hActive) {
      val p = at(x, y)
      if (p != RED_P && p != BLUE_P) {
        other += 1
        if (firstOffenders.size < 32) firstOffenders += f"    (x=$x%3d,y=$y%3d) ${rgb(p)}"
      }
    }
    val redCount  = fb.count(_ == RED_P)
    val blueCount = fb.count(_ == BLUE_P)
    emit(f"  RED  pixels : $redCount%7d")
    emit(f"  BLUE pixels : $blueCount%7d")
    emit(f"  OTHER pixels: $other%7d   <-- intermediate/bleed colours")
    if (other > 0) {
      emit("  First offenders:")
      firstOffenders.foreach(emit)
    }
    emit("")
    if (other == 0) {
      emit("CONCLUSION: CLEAN 1-pixel RED/BLUE transition. Every active pixel is exactly")
      emit("  the border red or the content blue — NO intermediate colour exists in the")
      emit("  digital output. The observed lower-left fringe is therefore an ANALOG /")
      emit("  HDMI / capture-chain artifact on the high-contrast red<->dark-blue edge,")
      emit("  NOT an RTL bug.")
    } else {
      emit(s"CONCLUSION: DIGITAL BLEED PRESENT — $other active pixel(s) are neither pure")
      emit("  border-red nor pure content-blue. This is an RTL bug; coordinates above.")
    }
    rep.close()

    assert(redCount > 0 && blueCount > 0, "degenerate frame: expected both red border and blue content present")
    println("")
    println("VdpInnerBorderCoSim: DONE")
    if (other == 0) println("VdpInnerBorderCoSim: PASS — clean digital transition, no bleed (fringe is analog).")
    else            println(s"VdpInnerBorderCoSim: BLEED — $other non-pure boundary pixel(s) found (RTL bug).")
  }
}
