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
  * Same faithful mini-Top as HamIntegrationSim (VdpTop + real BitmapRowFetch + a
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

    def runOne(writeDelay: Int): (Int, Int, Int, Array[Long], String) = {
      var resActive = 0; var resBypass = 0; var resInRange = 0
      val resMatchByShift = Array.fill(MaxShift + 1)(0L)
      var resFirstMism = ""
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

        val sampleCycles = 800 * 525 * 2
        for (_ <- 0 until sampleCycles) {
          if (dut.io.de.toBoolean) {
            resActive += 1
            if (dut.video.dcActiveDrained.toBoolean) {
              resBypass += 1
              val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
              if (dx < 640 && dy < 480) {
                resInRange += 1
                val got = dut.video.dcRgbDrained.toInt & 0xFFFFFF
                var s = 0
                while (s <= MaxShift) {
                  val sx = dx - s
                  if (sx >= 0 && got == srcRgb(dy/2)(sx/2)) resMatchByShift(s) += 1
                  s += 1
                }
                if (resFirstMism.isEmpty && got != srcRgb(dy/2)(dx/2))
                  resFirstMism = f"x=$dx y=$dy got=0x$got%06x exp=0x${srcRgb(dy/2)(dx/2)}%06x"
              }
            }
          }
          dut.clockDomain.waitSampling()
        }
      }
      (resActive, resBypass, resInRange, resMatchByShift, resFirstMism)
    }

    // ---- Pass 1: legacy build (delay=0) — MEASURE the latent directcolor offset ----
    val (a0, b0, r0, shift0, _) = runOne(0)
    val bypass0 = if (a0 > 0) b0.toDouble / a0 else 0.0
    val measured = shift0.indices.maxBy(shift0(_))
    println(f"[sim] LEGACY delay=0: active=$a0 bypassOn=$b0 ($bypass0%.3f) inRange=$r0")
    println("[sim] LEGACY per-shift match: " +
      shift0.indices.map(s => f"s=$s:${shift0(s).toDouble/math.max(1,r0)}%.3f").mkString(" "))
    println(s"[sim] MEASURED directcolor pipeline offset (best-fit display-column shift) = $measured")
    assert(bypass0 > 0.95, f"directcolor bypass not engaged on legacy build ($bypass0%.3f)")
    assert(measured > 0, s"expected a nonzero pre-fix offset on legacy directcolor build, measured=$measured")
    assert(shift0(0).toDouble / math.max(1, r0) < 0.6,
      "legacy directcolor build already aligned at s=0 — the latent shift is NOT present (unexpected)")

    // ---- Pass 2: aligned build (delay=measured) — PROVE byte-exact at s==0 ----
    val (a1, b1, r1, shift1, fm1) = runOne(measured)
    val bypass1 = if (a1 > 0) b1.toDouble / a1 else 0.0
    val exact1 = shift1(0)
    val exactFrac = exact1.toDouble / math.max(1, r1)
    println(f"[sim] ALIGNED delay=$measured: active=$a1 bypassOn=$b1 ($bypass1%.3f) inRange=$r1 exactMatch@s0=$exact1 ($exactFrac%.4f)")
    if (fm1.nonEmpty) println(s"[sim] ALIGNED first mismatch: $fm1")
    assert(bypass1 > 0.95, f"directcolor bypass not engaged on aligned build ($bypass1%.3f)")
    assert(exact1 == r1, s"directcolor not byte-exact at aligned delay=$measured: ${r1 - exact1}/$r1 px mismatched; first: $fm1")
    println(f"[sim] DirectColorFrameCoSim: PASS — measured latent offset=$measured; aligned build byte-exact (${exact1}/${r1}) vs RGB565 reference; legacy build reproduced the +$measured-col shift in the SHIPPED directcolor path")
  }
}
