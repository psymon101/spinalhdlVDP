package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import java.nio.file.{Files, Paths}

/** HAM-DECODER-171 CP-D INTEGRATION proof (closes the gap the unit co-sim left).
  *
  * Faithful mini-TopTang: VdpTop + the REAL BitmapRowFetch + a behavioral SDRAM
  * model loaded with BronzeGate's HAM6 fixture. Drives the register bus exactly
  * like the host (BITMAP_CTRL=0x0007 bpp=0b11, base/stride/height, palette[0..15],
  * LAYER_ENABLE=0x0001), lets the real scheduler grant fetches, and samples the
  * direct-color-bypass internals (`dcActiveDrained`/`dcRgbDrained`, simPublic) over
  * the active region — verifying (a) the bypass ENGAGES across the frame (the
  * black-frame failure would leave it de-asserted) and (b) the carried RGB matches
  * the HAM-decoded reference for sampled pixels. This exercises register decode →
  * fetch delivery → HAM decode → dcLineBuf → drain → bypass end-to-end in RTL.
  *
  * Run: sbt "runMain spinalhdlvdp.HamIntegrationSim"
  */
object HamIntegrationSim {
  val AssetDir = "/home/itadmin/github/spinalhdlVDP/firmware/assets/ham_decoder_171"
  val SrcW = 320; val SrcH = 240
  val BitmapBase = 0x100000; val AttrBase = 0x120000; val Stride = 320

  val pal = Array(0x000, 0xFFF, 0xF00, 0x0F0, 0x00F, 0xFF0, 0xF0F, 0x0FF,
                  0x840, 0x480, 0x048, 0x804, 0xC62, 0x2C6, 0x62C, 0x444)
  def expand888(c12: Int): Int = {
    val r = (c12 >> 8) & 0xF; val g = (c12 >> 4) & 0xF; val b = c12 & 0xF
    (((r << 4) | r) << 16) | (((g << 4) | g) << 8) | ((b << 4) | b)
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
      val dbgFetchByte = out Bits (8 bits)   // diagnostic: the bitmap byte the HAM decoder consumes
    }
    io.dbgFetchByte := fetch.io.bitmapByte
    video.io.regBus.addr := io.regBusAddr; video.io.regBus.data := io.regBusData; video.io.regBus.enable := io.regBusEnable

    // VdpTop <-> BitmapRowFetch coupling (mirrors TopTang20kHdmi).
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

    // SDRAM bus out to model.
    io.sdramAddr := fetch.io.sdramAddr; io.sdramRd := fetch.io.sdramRd; io.sdramWr := fetch.io.sdramWr
    io.sdramBurstLen := fetch.io.sdramBurstLen
    fetch.io.sdramDout := io.sdramDout; fetch.io.sdramDout32 := io.sdramDout32
    fetch.io.sdramDataReady := io.sdramDataReady; fetch.io.sdramBusy := io.sdramBusy
    io.bootDone := fetch.io.bootDone
    io.x := video.io.x; io.y := video.io.y; io.de := video.io.de

    // ---- tie off every other VdpTop input ----
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
    val ham = Files.readAllBytes(Paths.get(s"$AssetDir/ham6_320x240_codes.raw"))
    require(ham.length == SrcW * SrcH)
    // Scala reference: per-source-row HAM decode → expanded 8:8:8.
    val srcRgb = Array.ofDim[Int](SrcH, SrcW)
    for (y <- 0 until SrcH) {
      var hold = pal(0)
      for (x <- 0 until SrcW) {
        val c = ham(y * SrcW + x) & 0x3F; val ctrl = (c >> 4) & 0x3; val d = c & 0xF
        val r = (hold >> 8) & 0xF; val g = (hold >> 4) & 0xF; val b = hold & 0xF
        hold = ctrl match { case 0 => pal(d); case 1 => (r << 8) | (g << 4) | d
                            case 2 => (d << 8) | (g << 4) | b; case 3 => (r << 8) | (d << 4) | b }
        srcRgb(y)(x) = expand888(hold)
      }
    }

    // Maximum display-column shift to scan when empirically measuring the offset.
    val MaxShift = 8

    /** Compile + sim VdpTop at a given write-pipeline delay; return per-shift match
      * counts so the caller can MEASURE the residual offset (don't assume it is 3). */
    def runOne(writeDelay: Int): (Int, Int, Int, Array[Long], String, mutable.HashMap[Int, Int], mutable.HashMap[Int, Int]) = {
    var resActive = 0; var resBypass = 0; var resInRange = 0
    val resMatchByShift = Array.fill(MaxShift + 1)(0L)
    var resFirstMism = ""
    val resRow0 = mutable.HashMap[Int, Int]()   // diagnostic: dx -> got, first display row (dy==0)
    val resByte0 = mutable.HashMap[Int, Int]()  // diagnostic: dx -> fetched bitmapByte the decoder consumes
    SimConfig.compile(new Dut(writeDelay)).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)
      val mem = mutable.HashMap[Int, Int]()
      for (i <- ham.indices) {
        val r = i / SrcW; val c = i % SrcW
        mem((BitmapBase + r * Stride + c) & 0x7fffff) = ham(i) & 0xFF
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

      // Reactive SDRAM model — matches the proven-good BitmapRowFetchDirectColorSim:
      // keep sdramBusy FALSE throughout (toggling it desyncs the burst FSM and drops
      // reads mid-row → systemic line-buffer under-fill), 5-cycle latency, then BURST
      // out `sdramBurstLen` consecutive words (one dataReady pulse/cycle).
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
      // palette[0..15]: 0x0601 = ptr (entry*2+half), 0x0600 = data; half0 = G:B, half1 = R commits
      for (i <- 0 until 16) {
        val r8 = (((pal(i)>>8)&0xF)*0x11); val g8 = (((pal(i)>>4)&0xF)*0x11); val b8 = ((pal(i)&0xF)*0x11)
        writeReg(0x0601, i*2);     writeReg(0x0600, (g8<<8)|b8)
        writeReg(0x0601, i*2+1);   writeReg(0x0600, r8)
      }
      writeReg(0x0351, BitmapBase & 0xFFFF);        writeReg(0x0352, (BitmapBase>>16)&0x7F)
      writeReg(0x0353, AttrBase & 0xFFFF);          writeReg(0x0354, (AttrBase>>16)&0x7F)
      writeReg(0x0355, Stride);                     writeReg(0x0356, Stride)
      writeReg(0x0357, SrcH)
      writeReg(0x0350, 0x0007)   // BITMAP_CTRL: enable | bpp=0b11 (HAM)
      writeReg(0x0300, 0x0001)   // LAYER_ENABLE = L0

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      println(s"[sim] bitmap fetch bootDone=${dut.io.bootDone.toBoolean}")

      // Let several frames fill the ping-pong line buffer, then sample 2 frames.
      dut.clockDomain.waitSampling(800 * 525 * 3)

      // For each sampled display pixel, score it against the reference at every
      // candidate display-column shift s (got(dx) ?= ref(dx - s)). The shift that
      // maximizes matches IS the measured pipeline offset — at the correct write
      // delay the best (and a perfect) match must be s == 0.
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
              if (dy == 0 && dx < 40 && !resRow0.contains(dx)) {
                resRow0(dx) = got
                resByte0(dx) = dut.io.dbgFetchByte.toInt & 0xFF
              }
            }
          }
        }
        dut.clockDomain.waitSampling()
      }
    }
    (resActive, resBypass, resInRange, resMatchByShift, resFirstMism, resRow0, resByte0)
    }

    // ---- Pass 1: legacy build (delay=0) — MEASURE the residual offset ----
    val (a0, b0, r0, shift0, _, row0, byte0) = runOne(0)
    val bypass0 = if (a0 > 0) b0.toDouble / a0 else 0.0
    val measured = shift0.indices.maxBy(shift0(_))
    println(f"[sim] LEGACY delay=0: active=$a0 bypassOn=$b0 ($bypass0%.3f) inRange=$r0")
    println("[sim] LEGACY per-shift match: " +
      shift0.indices.map(s => f"s=$s:${shift0(s).toDouble/math.max(1,r0)}%.3f").mkString(" "))
    println(s"[sim] MEASURED pipeline offset (best-fit display-column shift) = $measured")
    // Diagnostic: first display row — got vs source-code/expected per display column.
    println("[sim] row0 diag (dx: srcCol=dx/2  refCode=ham[sc]  fetchedByte  got  expRef):")
    for (dx <- 0 until 40 if row0.contains(dx)) {
      val sc = dx / 2
      val code = ham(0 * SrcW + sc) & 0x3F
      val fb   = byte0.getOrElse(dx, -1)
      val exp  = srcRgb(0)(sc)
      val tag  = if (row0(dx) == exp) "ok" else "X"
      println(f"  dx=$dx%2d sc=$sc%2d refCode=0x$code%02x fetched=0x$fb%02x got=0x${row0(dx)}%06x exp=0x$exp%06x $tag")
    }
    if (args.contains("diag")) { println("[sim] diag-only mode: stopping after legacy dump"); return }
    assert(bypass0 > 0.95, f"directcolor bypass not engaged on legacy build ($bypass0%.3f) — reproduces black frame")
    assert(measured > 0, s"expected a nonzero pre-fix offset on legacy build, measured=$measured")
    assert(shift0(0).toDouble / math.max(1, r0) < 0.6,
      "legacy build already aligned at s=0 — the offset bug is not reproduced")

    // ---- Pass 2: aligned build (delay=measured) — PROVE byte-exact at s==0 ----
    val (a1, b1, r1, shift1, fm1, _, _) = runOne(measured)
    val bypass1 = if (a1 > 0) b1.toDouble / a1 else 0.0
    val exact1 = shift1(0)                       // matches at zero shift = fully aligned
    val exactFrac = exact1.toDouble / math.max(1, r1)
    println(f"[sim] ALIGNED delay=$measured: active=$a1 bypassOn=$b1 ($bypass1%.3f) inRange=$r1 exactMatch@s0=$exact1 ($exactFrac%.4f)")
    if (fm1.nonEmpty) println(s"[sim] ALIGNED first mismatch: $fm1")
    assert(bypass1 > 0.95, f"directcolor bypass not engaged on aligned build ($bypass1%.3f)")
    assert(exact1 == r1, s"HAM not byte-exact at aligned delay=$measured: ${r1 - exact1}/$r1 px mismatched; first: $fm1")
    println(f"[sim] HamIntegrationSim: PASS — measured offset=$measured; aligned build byte-exact (${exact1}/${r1}) vs HAM reference; legacy build reproduced the +$measured-col shift")
  }
}
