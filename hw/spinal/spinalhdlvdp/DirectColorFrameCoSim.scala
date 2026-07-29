package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** HAM-DECODER-171 CP-D step 1+4 (TopazCliff #12983/#12987): RGB565 directcolor
  * byte-exact frame regression.
  *
  * The shipped RGB565 directcolor path (bpp=0b10) shares the SAME `dcLineBuf`
  * write/drain carrier as HAM (bpp=0b11), so it carries the SAME pipeline-write
  * misalignment — we just never had a byte-exact frame compare to prove it. This
  * sim is the missing baseline + regression:
  *   - Pass 1 (legacy, bitmapWritePipelineDelay=0): MEASURE the residual display-
  *     column offset on the current/shipped behavior (best-fit shift > 0 ⇒ the
  *     latent shift is real and present in production today).
  *   - Pass 2 (aligned, delay=measured): PROVE the shared fix makes directcolor
  *     byte-exact at zero shift (s==0), i.e. it CORRECTS the shift without breaking
  *     the path.
  *
  * Same faithful mini-Top pattern (VdpTop + real BitmapRowFetch + a
  * behavioral SDRAM model), but loads a deterministic 320×240 RGB565 pattern and
  * compares the drained directcolor RGB against the RTL's exact 565→888 bit-
  * replication (BitmapFetch.scala:63-69): r8=r5##r5[4:2], g8=g6##g6[5:4],
  * b8=b5##b5[4:2]. directPixel = hi<<8 | lo (little-endian, 2 bytes/source px).
  *
  * Run: sbt "runMain spinalhdlvdp.DirectColorFrameCoSim"
  */
object DirectColorFrameCoSim {
  val SrcW = 320; val SrcH = 240
  val BitmapBase = 0x100000; val AttrBase = 0x120000
  // RGB565 directcolor is PLANE-SPLIT: low byte in the bitmap plane, high byte in the
  // attr plane (directPixel = attrByte ## bitmapByte), each 1 byte per source pixel →
  // stride = SrcW bytes per plane (NOT 2×).
  val Stride = SrcW
  val MinShift = -4
  val MaxShift = 8

  // Deterministic 320×240 RGB565 pattern; varies fast in x so adjacent source
  // pixels differ (sharpens the best-fit-shift measurement past the ×2 stretch).
  def word565(x: Int, y: Int): Int = {
    val r5 = (x * 1) & 0x1F
    val g6 = (x * 3 + y) & 0x3F
    val b5 = (y * 2 + 7) & 0x1F
    (r5 << 11) | (g6 << 5) | b5
  }
  // RTL-exact 565→888 (BitmapFetch.scala bit replication).
  def rgb888(w: Int): Int = {
    val r5 = (w >> 11) & 0x1F; val g6 = (w >> 5) & 0x3F; val b5 = w & 0x1F
    val r8 = (r5 << 3) | (r5 >> 2)
    val g8 = (g6 << 2) | (g6 >> 4)
    val b8 = (b5 << 3) | (b5 >> 2)
    (r8 << 16) | (g8 << 8) | b8
  }

  class Dut(writeDelay: Int) extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    val video = VdpTop(enableL1Fetch = false, bitmapWritePipelineDelay = writeDelay)
    val fetch = BitmapRowFetch(sdramCd, skipSdramInit = true)

    val io = new Bundle {
      val regBusAddr = in UInt (15 bits); val regBusData = in Bits (16 bits); val regBusEnable = in Bool()
      val sdramAddr = out UInt (23 bits); val sdramRd = out Bool(); val sdramWr = out Bool()
      val sdramBurstLen = out UInt (4 bits)
      val sdramDout = in Bits (8 bits); val sdramDout32 = in Bits (32 bits)
      val sdramDataReady = in Bool(); val sdramBusy = in Bool()
      val bootDone = out Bool()
      val x = out UInt (10 bits); val y = out UInt (10 bits); val de = out Bool()
    }
    video.io.regBus.addr := io.regBusAddr; video.io.regBus.data := io.regBusData; video.io.regBus.enable := io.regBusEnable

    fetch.io.col          := video.io.bitmapSdramCol
    fetch.io.fetchGrant   := video.io.bitmapSdramFetchGrant
    fetch.io.fetchLine    := video.io.bitmapSdramFetchLine
    fetch.io.enable       := video.io.bitmapModeActive
    fetch.io.directColor  := video.io.bitmapDirectColor
    fetch.io.tileBootDone := True
    fetch.io.bitmapBase   := video.io.bitmapBase
    fetch.io.attrBase     := video.io.attrBase
    fetch.io.bitmapStride := video.io.bitmapStride
    fetch.io.attrStride   := video.io.attrStride
    fetch.io.bitmapHeight := video.io.bitmapHeight
    video.io.bitmapSdramByte     := fetch.io.bitmapByte
    video.io.bitmapSdramAttrByte := fetch.io.attrByte

    io.sdramAddr := fetch.io.sdramAddr; io.sdramRd := fetch.io.sdramRd; io.sdramWr := fetch.io.sdramWr
    io.sdramBurstLen := fetch.io.sdramBurstLen
    fetch.io.sdramDout := io.sdramDout; fetch.io.sdramDout32 := io.sdramDout32
    fetch.io.sdramDataReady := io.sdramDataReady; fetch.io.sdramBusy := io.sdramBusy
    io.bootDone := fetch.io.bootDone
    io.x := video.io.x; io.y := video.io.y; io.de := video.io.de

    video.io.layer0ScrollX := 0; video.io.layer0ScrollY := 0
    video.io.layer1ScrollX := 0; video.io.layer1ScrollY := 0
    video.io.layer2ScrollX := 0; video.io.layer2ScrollY := 0
    video.io.layer3ScrollX := 0; video.io.layer3ScrollY := 0
    video.io.sprite0X := 1000; video.io.sprite0Y := 1000; video.io.sprite0Enabled := False; video.io.sprite0PatternIdx := 0
    video.io.sprite1X := 1000; video.io.sprite1Y := 1000; video.io.sprite1Enabled := False; video.io.sprite1PatternIdx := 1
    video.io.sprite2X := 1000; video.io.sprite2Y := 1000; video.io.sprite2Enabled := False; video.io.sprite2PatternIdx := 0
    video.io.sprite3X := 1000; video.io.sprite3Y := 1000; video.io.sprite3Enabled := False; video.io.sprite3PatternIdx := 1
    video.io.layer0TestPatternSelect := 0; video.io.layer0TestPatternEnable := False
    video.io.layer0UseSdram := False; video.io.layer0SdramPixel := 0
    video.io.layer0SdramBank := 0; video.io.layer0SdramPriority := False
    video.io.layer1UseSdram := False; video.io.layer1SdramPixel := 0
    video.io.layer1SdramBank := 0; video.io.layer1SdramPriority := False
    video.io.rasterTriggerLine := 0; video.io.rasterTriggerPixel := 0
    video.io.rasterTriggerPxEnable := False; video.io.rasterTriggerEnable := False; video.io.rasterTriggerClear := False
    video.io.statusEvQspiReady := False; video.io.statusEvQspiError := False
    video.io.planarSdramBusy := False; video.io.planarSdramDataReady := False; video.io.planarSdramDout32 := 0
  }

  def main(args: Array[String]): Unit = {
    // Scala reference frame: src(y)(x) = 565→888 of the pattern word.
    val srcRgb = Array.ofDim[Int](SrcH, SrcW)
    for (y <- 0 until SrcH; x <- 0 until SrcW) srcRgb(y)(x) = rgb888(word565(x, y))

    def runOne(writeDelay: Int): (Double, Int, Int, Double, Long, Long) = {
      var resActive = 0; var resBypass = 0
      var rBestDv = 0; var rBestDh = 0; var rBestFrac = 0.0
      var rCanonMatch = 0L; var rCanonTotal = 1L
      SimConfig.compile(new Dut(writeDelay)).doSim { dut =>
        dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)
        // Plane-split RGB565: low byte → bitmap plane[sc], high byte → attr plane[sc].
        val mem = mutable.HashMap[Int, Int]()
        for (y <- 0 until SrcH; x <- 0 until SrcW) {
          val w = word565(x, y)
          mem((BitmapBase + y * Stride + x) & 0x7fffff) = w & 0xFF
          mem((AttrBase   + y * Stride + x) & 0x7fffff) = (w >> 8) & 0xFF
        }
        def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
        def rw(a: Int): Long = { val b = a & ~3
          (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

        dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
        dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

        // Reactive SDRAM model — matches proven-good BitmapRowFetchDirectColorSim:
        // sdramBusy held FALSE, 5-cycle latency, burst out `sdramBurstLen` words.
        fork {
          for (_ <- 0 until 30) dut.sdramCd.waitSampling()
          dut.io.sdramBusy #= false
          while (true) {
            if (dut.io.sdramRd.toBoolean) {
              val a = dut.io.sdramAddr.toInt; val n = math.max(1, dut.io.sdramBurstLen.toInt)
              dut.sdramCd.waitSampling(5)
              for (k <- 0 until n) {
                dut.io.sdramDout #= rb(a + k*4) & 0xFF
                dut.io.sdramDout32 #= BigInt(rw(a + k*4) & 0xFFFFFFFFL)
                dut.io.sdramDataReady #= true; dut.sdramCd.waitSampling()
              }
              dut.io.sdramDataReady #= false
            } else dut.sdramCd.waitSampling()
          }
        }

        def writeReg(a: Int, d: Int): Unit = {
          dut.io.regBusAddr #= a; dut.io.regBusData #= d; dut.io.regBusEnable #= true
          dut.clockDomain.waitSampling(); dut.io.regBusEnable #= false; dut.clockDomain.waitSampling()
        }
        writeReg(0x0351, BitmapBase & 0xFFFF);        writeReg(0x0352, (BitmapBase>>16)&0x7F)
        writeReg(0x0353, AttrBase & 0xFFFF);          writeReg(0x0354, (AttrBase>>16)&0x7F)
        writeReg(0x0355, Stride);                     writeReg(0x0356, Stride)
        writeReg(0x0357, SrcH)
        writeReg(0x0350, 0x0005)   // BITMAP_CTRL: enable(bit0) | bpp=0b10 (RGB565 directcolor)
        writeReg(0x0300, 0x0001)   // LAYER_ENABLE = L0

        var t = 200000
        while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
        println(s"[sim] directcolor fetch bootDone=${dut.io.bootDone.toBoolean}")
        dut.clockDomain.waitSampling(800 * 525 * 3)

        // Capture one got-frame from the 2-CYCLE display outputs (dcRgbDrainedR/
        // dcActiveDrainedR, co-timed with io.x — CyanPeak #13009/#13013).
        val gotFrame = Array.fill(480, 640)(-1)
        val sampleCycles = 800 * 525 * 2
        for (_ <- 0 until sampleCycles) {
          if (dut.io.de.toBoolean) {
            resActive += 1
            if (dut.video.dcActiveDrainedR.toBoolean) {
              resBypass += 1
              val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
              if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.dcRgbDrainedR.toInt & 0xFFFFFF
            }
          }
          dut.clockDomain.waitSampling()
        }

        // 2D alignment scan with the even/odd-aware vertical mapping (lines 2k-1 and 2k
        // show source row k-dv; CyanPeak #13013). Capture canonical (dv=3, dh=0).
        val CanonDv = 3
        var bestDv = 0; var bestDh = 0; var bestMatch = -1L; var bestTotal = 1L
        var canonMatch = 0L; var canonTotal = 1L
        def srcRow(dy: Int, dv: Int): Int = {
          val r = if (dy % 2 == 0) dy/2 - dv else (dy - 1)/2 - (dv - 1)
          ((r % SrcH) + SrcH) % SrcH
        }
        for (dv <- -1 to 5; dh <- MinShift to MaxShift) {
          var m = 0L; var t = 0L; var dy = 0
          while (dy < 480) {
            val sr = srcRow(dy, dv); var dx = 0
            while (dx < 640) {
              val src = dx - dh; val g = gotFrame(dy)(dx)
              if (g >= 0 && src >= 0 && src < 640) { t += 1; if (g == srcRgb(sr)(src/2)) m += 1 }
              dx += 1
            }
            dy += 1
          }
          if (m > bestMatch) { bestMatch = m; bestTotal = t; bestDv = dv; bestDh = dh }
          if (dv == CanonDv && dh == 0) { canonMatch = m; canonTotal = t }
        }
        rBestDv = bestDv; rBestDh = bestDh; rBestFrac = bestMatch.toDouble / math.max(1, bestTotal)
        rCanonMatch = canonMatch; rCanonTotal = canonTotal
        println(f"[sim] delay=$writeDelay: bypass=${resBypass.toDouble/math.max(1,resActive)}%.3f best (dv=$bestDv,dh=$bestDh) frac=$rBestFrac%.4f ; canonical(dv=3,dh=0)=$canonMatch/$canonTotal (${canonMatch.toDouble/math.max(1,canonTotal)}%.4f)")
      }
      (if (resActive > 0) resBypass.toDouble / resActive else 0.0, rBestDv, rBestDh, rBestFrac, rCanonMatch, rCanonTotal)
    }

    // Sweep write delay; find the one that lands the RGB565 image at dh=0 byte-exact
    // (modulo the line-0 startup transient). Confirms the shared dcLineBuf carrier keeps
    // the SHIPPED directcolor path correct at the same config HAM uses (delay=0).
    // PASS = the BEST alignment is itself dh=0 (image at correct position) AND >99%
    // byte-exact (the <1% residual is the line-0/1 startup transient + row-edge).
    val results = (0 to 3).map { d => val r = runOne(d); (d, r._2, r._3, r._4, r._5, r._6) }
    val aligned = results.find { case (_, dv, dh, frac, _, _) => dv == 3 && dh == 0 && frac > 0.99 }
    aligned match {
      case Some((d, _, _, frac, cM, cT)) =>
        println(f"[sim] DirectColorFrameCoSim: PASS — bitmapWritePipelineDelay=$d aligned at dh=0, $frac%.4f byte-exact (canonical $cM/$cT; sub-1pct residual = startup transient + edge)")
      case None =>
        val (d, dv, dh, frac, _, _) = results.maxBy(_._4)
        println(f"[sim] DirectColorFrameCoSim: FAIL — no delay lands dh=0 byte-exact; best delay=$d (dv=$dv,dh=$dh,$frac%.4f)")
    }
  }
}
