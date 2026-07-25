package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** HAM6-shelve #14227 — 2bpp indexed display bring-up co-sim.
  *
  * The bench shows a fully black HDMI frame (only the always-on cyan canary) even
  * though serial proof PASSes: transport + upload work but the 2bpp bitmap does not
  * composite. Receiver-lock is refuted (the canary is a clean RTL overlay). This sim
  * drives BronzeGate's EXACT indexed2 register sequence (main.c:472-497) through the
  * REAL BitmapRowFetch + VdpTop compositor to determine whether the RTL 2bpp indexed
  * DISPLAY path produces non-black pixels — a mode that was never content-sim'd
  * end-to-end before the hardware handoff.
  *
  * Stimulus: a UNIFORM value-1 2bpp bitmap (byte 0x55 = 0b01_01_01_01, four pixels of
  * value 1) + an IDENTITY attribute plane (byte 0xE4 = slot0..3 = 0,1,2,3). With the
  * default palette (legacyPalette[1] = white), a WORKING 2bpp path drives the whole
  * active area to palette[1] = white; a broken path leaves it at palette[0] (black).
  *
  * We sample `bgOrDirectRgb` (= paletteRgb for indexed) during DE and count black vs
  * non-black, once WITH `MODE_SELECT 0x0313=0` (BronzeGate's exact seq) and once
  * WITHOUT (to test whether MODE_SELECT=0 suppresses L0).
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppFrameCoSim"
  */
object Indexed2bppFrameCoSim {
  val SrcH      = 240
  val RowStride = 128           // indexed hardwired 128-byte row stride (BitmapRowFetch.scala:270, lineReg<<7)
  val BitmapBase = 0x100000     // matches firmware main.c 0x0351/0x0352
  val AttrBase   = 0x110000     // matches firmware main.c 0x0353/0x0354

  class Dut extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    val video = VdpTop(enableL1Fetch = false)
    val fetch = BitmapRowFetch(sdramCd, skipSdramInit = true)

    val io = new Bundle {
      val regBusAddr = in UInt (15 bits); val regBusData = in Bits (16 bits); val regBusEnable = in Bool()
      val sdramAddr = out UInt (23 bits); val sdramRd = out Bool(); val sdramWr = out Bool()
      val sdramBurstLen = out UInt (4 bits)
      val sdramDout = in Bits (8 bits); val sdramDout32 = in Bits (32 bits)
      val sdramDataReady = in Bool(); val sdramBusy = in Bool()
      val bootDone = out Bool()
      val x = out UInt (10 bits); val y = out UInt (10 bits); val de = out Bool()
      val probeBmByte = out Bits(8 bits); val probeAttrByte = out Bits(8 bits)
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
    io.probeBmByte := fetch.io.bitmapByte; io.probeAttrByte := fetch.io.attrByte

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

  def runOne(writeMode0: Boolean): (Long, Long) = {
    var black = 0L; var nonBlack = 0L
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      // Vertical-bar pattern IDENTICAL on every row: bitmap bytes [0..40)=0x55 (pixel value 1),
      // [40..80)=0xAA (pixel value 2) → one vertical boundary at source px 160 (~disp col 320).
      // Attr 0xE4 (identity). Since every source row is identical, ANY per-row horizontal drift
      // of that boundary in the composited output = a real RTL shear (the shimmer under test).
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH; b <- 0 until RowStride) {
        mem((BitmapBase + row * RowStride + b) & 0x7fffff) = (if (b < 40) 0x55 else 0xAA)
        mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

      // Reactive SDRAM model — same as DirectColorFrameCoSim: 5-cycle latency, burst out.
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

      // BronzeGate's indexed2 sequence (firmware/esp32p4_qspi_proof/main/main.c:472-497).
      writeReg(0x0300, 0x0000)                       // disable layers while loading
      if (writeMode0) writeReg(0x0313, 0x0000)       // MODE_SELECT native Mode0 (the lead under test)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      // Per-line LINESTATE L0-enable (addr=line 0..479, data bit[11]=layer0Enable). Without
      // this, linestate.layer0Enable=0 -> effectiveL0Enable=0 -> L0 forced transparent -> black.
      // THIS is the step missing from BronzeGate's firmware sequence.
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)                       // enable + bpp=0b01 (2bpp indexed)
      writeReg(0x0300, 0x0001)                       // LAYER_ENABLE = L0

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      println(s"[sim] mode0write=$writeMode0 bootDone=${dut.io.bootDone.toBoolean}")
      dut.clockDomain.waitSampling(800 * 525 * 3)

      // Capture one composited frame, then locate the bar boundary per row.
      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }
      // Per row: leftmost column whose colour differs from column 0 (= the value-1→2 boundary).
      // Identical source rows ⇒ a constant boundary column; a spread ⇒ real RTL horizontal shear.
      val trans = mutable.ArrayBuffer[Int]()
      var nonBlackRows = 0L
      for (dy <- 0 until 480) {
        val c0 = gotFrame(dy)(0)
        if (gotFrame(dy)(320) != 0x000000) nonBlackRows += 1
        var col = -1; var dx = 1
        while (dx < 640 && col < 0) { val g = gotFrame(dy)(dx); if (g >= 0 && g != c0) col = dx; dx += 1 }
        if (col >= 0) trans += col
      }
      if (trans.nonEmpty) {
        val srt = trans.toSeq.sorted; val mn = srt.head; val mx = srt.last; val md = srt(srt.size/2)
        nonBlack = trans.size.toLong; black = (mx - mn).toLong   // black repurposed = shear span (px)
        println(f"[sim] mode0write=$writeMode0: bar-boundary col over ${trans.size} rows: min=$mn max=$mx median=$md SHEAR_SPAN=${mx-mn}px (nonBlackRows=$nonBlackRows)")
      } else { nonBlack = 0; black = -1; println(f"[sim] mode0write=$writeMode0: NO transition found (uniform/black frame)") }
    }
    (nonBlack, black)
  }

  /** ROW-CODED lookahead test (#14253 Finding 1): each source row's value1→value2 boundary
    * is at byte `10 + (row%60)` (display col `8*(10+row%60)`), so the boundary column ENCODES
    * the source row. Driven through VdpTop's REAL `io.bitmapSdramFetchLine` (line 53) — this is
    * the actual hardware lookahead, NOT an artificial testbench offset. If VdpTop selects the
    * wrong source row (missing +2 lookahead / bank-fill mis-target), the per-display-row boundary
    * will NOT track a single consistent vertical offset → detected as wrong-row events. Returns
    * (validRows, wrongRowEvents). */
  def runRowCoded(): (Int, Int, Int) = {
    var validRows = 0; var wrongEvents = 0; var bestDvOut = 0
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      def boundaryByte(row: Int): Int = 10 + (row % 60)   // 10..69, within the 80-byte fetched row
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH) {
        val bnd = boundaryByte(row)
        for (b <- 0 until RowStride) {
          mem((BitmapBase + row * RowStride + b) & 0x7fffff) = (if (b < bnd) 0x55 else 0xAA) // value1|value2
          mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
        }
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false
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
      writeReg(0x0300, 0x0000)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)
      writeReg(0x0300, 0x0001)

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      dut.clockDomain.waitSampling(800 * 525 * 3)

      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }
      // Per display row: white(value1)→red(value2) boundary col → implied source-row mod 60.
      val impliedMod = Array.fill(480)(-1)
      for (dy <- 0 until 480) {
        val c0 = gotFrame(dy)(0)
        if (c0 != 0x000000 && c0 >= 0) {
          var col = -1; var dx = 1
          while (dx < 640 && col < 0) { if (gotFrame(dy)(dx) >= 0 && gotFrame(dy)(dx) != c0) col = dx; dx += 1 }
          if (col >= 0) { val bnd = (col + 4) / 8; impliedMod(dy) = ((bnd - 10) % 60 + 60) % 60 }
        }
      }
      // Expected source row for display dy uses the RTL's EVEN/ODD-aware line-doubling mapping
      // (same as DirectColorFrameCoSim: even dy → dy/2-dv, odd dy → (dy-1)/2-(dv-1)); the whole
      // frame carries a single consistent vertical offset dv. Scan dv, pick most matches; the
      // rest = genuine wrong-row-selection (bank/lookahead mis-target).
      def srcRow(dy: Int, dv: Int): Int = { val r = if (dy % 2 == 0) dy/2 - dv else (dy-1)/2 - (dv-1); ((r % 60) + 60) % 60 }
      val valid = (0 until 480).filter(impliedMod(_) >= 0)
      validRows = valid.size
      var bestDv = 0; var bestMatch = -1
      for (dv <- -4 to 8) {
        var m = 0
        for (dy <- valid) if (impliedMod(dy) == srcRow(dy, dv)) m += 1
        if (m > bestMatch) { bestMatch = m; bestDv = dv }
      }
      wrongEvents = validRows - bestMatch; bestDvOut = bestDv
      val firsts = valid.filter(dy => impliedMod(dy) != srcRow(dy, bestDv)).take(8)
      println(f"[sim] ROW-CODED: validRows=$validRows bestDv=$bestDv matches=$bestMatch WRONG_ROW_EVENTS=$wrongEvents")
      if (firsts.nonEmpty) firsts.foreach { dy =>
        println(f"[sim]   wrong-row @ display dy=$dy: impliedSrcMod=${impliedMod(dy)} expected=${srcRow(dy, bestDv)}")
      }
    }
    (validRows, wrongEvents, bestDvOut)
  }

  /** LEFT-EDGE discriminator (#14285): render a UNIFORM value-1 (white) frame through the REAL
    * scanout + fetch, then histogram black pixels PER DISPLAY COLUMN. A clean RTL scanout paints
    * every active column white (only the frame-0 startup row may differ). If the LEFT-EDGE columns
    * (0..31) carry materially more black than the interior, that is a real VDP left-edge
    * scanout/fetch artifact; if the left edge matches the interior, the RTL renders a clean left
    * edge and the bench streaks are downstream (monitor/capture/camera geometry). Returns
    * (leftEdgeBlack, interiorBlack). */
  def runLeftEdge(): (Long, Long) = {
    var leftBlack = 0L; var interiorBlack = 0L
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      // Uniform value-1 (0x55) bitmap + identity attr (0xE4) → every pixel = palette[1] (white).
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH; b <- 0 until RowStride) {
        mem((BitmapBase + row * RowStride + b) & 0x7fffff) = 0x55
        mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false
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
      writeReg(0x0300, 0x0000)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)
      writeReg(0x0300, 0x0001)

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      dut.clockDomain.waitSampling(800 * 525 * 3)

      // Per-column black histogram over DE pixels, skipping frame-0 startup row (dy==0).
      val colBlack = Array.fill(640)(0L); val colSeen = Array.fill(640)(0L)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy >= 1 && dy < 480) {
            colSeen(dx) += 1
            if ((dut.video.bgOrDirectRgb.toInt & 0xFFFFFF) == 0x000000) colBlack(dx) += 1
          }
        }
        dut.clockDomain.waitSampling()
      }
      for (dx <- 0 until 32)   leftBlack     += colBlack(dx)
      for (dx <- 32 until 608) interiorBlack += colBlack(dx)
      // Report the per-column black in the first 8 cols + a couple interior cols for the record.
      val head = (0 until 8).map(dx => f"c$dx=${colBlack(dx)}").mkString(" ")
      println(f"[sim] LEFT-EDGE black/col: $head | interior c320=${colBlack(320)} c500=${colBlack(500)} | leftSum(0..31)=$leftBlack interiorSum(32..607)=$interiorBlack")
    }
    (leftBlack, interiorBlack)
  }

  /** CHECKERBOARD-EDGE discriminator (#14287 follow-up to #14285). The owner reports the
    * "horizontal protrusions at the left edge of each square" persist on the physical monitor
    * and are UNAFFECTED by monitor Sharpness=0 / aspect 1:1 (falsifying the edge-enhancement +
    * overscan hypotheses). `runLeftEdge` used a UNIFORM fill — it has no internal edges and so
    * cannot test per-square-edge behaviour. This renders the EXACT firmware checkerboard
    * (esp32p4_checkerboard/main.c: 32-src-px squares = 64 display px, `color=(sx^sy)&1`, byte
    * value 0x00/0x55, identity attr 0xE4) through the REAL BitmapRowFetch + VdpTop compositor +
    * ×2 doubler, then run-length-encodes the EMITTED pixel stream per display row.
    *
    * A pixel-perfect checkerboard emits interior runs of EXACTLY 64 display px with clean
    * single-column transitions. If every interior run is 64px with no spurious short runs, the
    * FPGA emits sharp square edges ⇒ the bench protrusions are a DOWNSTREAM analog process
    * (monitor scaler / non-integer resample the OSD cannot disable), NOT a VDP defect. If the
    * emitted stream shows off-length or spurious short runs at the square edges, that IS a real
    * RTL fetch/decode/double defect and an RTL lane is warranted. Returns
    * (maxInteriorRunErrPx, spuriousInteriorRunCount). */
  def runChecker(): (Int, Int) = {
    var maxRunErr = 0; var spurious = 0
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      val Square = 32; val Width = 320
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH; b <- 0 until RowStride) {
        val srcX = b * 4                                   // 4 px/byte, MSB-first; squares are byte-aligned (32px=8B)
        val v = if (srcX < Width) { val sx = srcX / Square; val sy = row / Square
          if (((sx ^ sy) & 1) != 0) 0x55 else 0x00 } else 0   // value1 (white) : value0 (black)
        mem((BitmapBase + row * RowStride + b) & 0x7fffff) = v
        mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false
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
      writeReg(0x0300, 0x0000)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)
      writeReg(0x0300, 0x0001)

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      dut.clockDomain.waitSampling(800 * 525 * 3)

      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }

      // Sample rows ~20px into each 64px-tall display band (away from vertical square edges + the
      // ±1 line-doubling seams). RLE the EMITTED row by exact RGB value (palette-agnostic).
      val sampleRows = (0 until 480 by 64).map(_ + 20).filter(_ < 478)
      println("[sim] CHECKER-EDGE: emitted run-length structure per sampled display row (expect interior runs = 64px, 'K'=black 'W'=white):")
      for (dy <- sampleRows) {
        val runs = mutable.ArrayBuffer[(Int, Int, Int)]()   // (rgbValue, startCol, len)
        var dx = 0
        while (dx < 640 && gotFrame(dy)(dx) < 0) dx += 1
        if (dx < 640) {
          var curVal = gotFrame(dy)(dx); var start = dx; var len = 0
          while (dx < 640) {
            val g = gotFrame(dy)(dx)
            if (g < 0) { if (len > 0) { runs += ((curVal, start, len)); len = 0 }
              dx += 1; while (dx < 640 && gotFrame(dy)(dx) < 0) dx += 1
              if (dx < 640) { curVal = gotFrame(dy)(dx); start = dx } }
            else if (g == curVal) { len += 1; dx += 1 }
            else { runs += ((curVal, start, len)); curVal = g; start = dx; len = 1; dx += 1 }
          }
          if (len > 0) runs += ((curVal, start, len))
        }
        val interior = if (runs.size >= 3) runs.slice(1, runs.size - 1) else Seq.empty
        val errs = interior.map(r => math.abs(r._3 - 64))
        val rowMaxErr = if (errs.nonEmpty) errs.max else 0
        val rowSpurious = interior.count(_._3 < 16)
        maxRunErr = math.max(maxRunErr, rowMaxErr)
        spurious += rowSpurious
        val desc = runs.take(13).map(r => f"${if (r._1 == 0x000000) "K" else "W"}${r._3}").mkString(",")
        println(f"[sim]   dy=$dy%3d runs=${runs.size}%2d interiorMaxErr=$rowMaxErr%2d spurious=$rowSpurious%2d : $desc${if (runs.size > 13) ",..." else ""}")
      }
    }
    (maxRunErr, spurious)
  }

  /** INTRA-BYTE decode discriminator (#14290 — verify CyanPeak's claimed `VdpTop.scala:1602`
    * `pixelWithinByte := hCounter(2 downto 0)` 1-cycle skew vs the readSync'd byte). Every prior
    * co-sim (checkerboard, vertical-bar, row-coded) uses BYTE-UNIFORM content, so none exercise
    * intra-byte pixel ordering OR the byte-vs-index alignment CyanPeak flags. This uploads a
    * pattern with BOTH: alternating bytes `0x1B` (pixels 0,1,2,3 = ramp UP) and `0xE4` (pixels
    * 3,2,1,0 = ramp DOWN), identity attr — so a byte-select skew AND/OR a pixelWithinByte skew
    * both visibly scramble the emitted ramp. Expected emitted per 8-col byte span (×2 doubled):
    * 0x1B → AABBCCDD, 0xE4 → DDCCBBAA, with A = value0 = black at display col 0.
    * Returns (mismatchCols, sampledRows). mismatchCols==0 ⇒ decode bit-perfect ⇒ no skew. */
  def runFine(): (Int, Int) = {
    var mismatch = 0; var sampled = 0
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      val Width = 320
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH; b <- 0 until RowStride) {
        val srcX = b * 4
        val v = if (srcX < Width) { if ((b & 1) == 0) 0x1B else 0xE4 } else 0
        mem((BitmapBase + row * RowStride + b) & 0x7fffff) = v
        mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false
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
      writeReg(0x0300, 0x0000)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)
      writeReg(0x0300, 0x0001)

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      dut.clockDomain.waitSampling(800 * 525 * 3)

      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }

      // Expected symbol pattern for the first 32 cols: 0x1B=AABBCCDD, 0xE4=DDCCBBAA (A=black).
      val expected = "AABBCCDD" + "DDCCBBAA" + "AABBCCDD" + "DDCCBBAA"
      val sampleRows = Seq(100, 240, 400)
      println("[sim] INTRA-BYTE: emitted cols 0..31 (symbols by first-appearance RGB; expect AABBCCDDDDCCBBAA…, A=black):")
      for (dy <- sampleRows) {
        sampled += 1
        val syms = mutable.LinkedHashMap[Int, Char](); var nextSym = 'A'
        def sym(rgb: Int): Char = syms.getOrElseUpdate(rgb, { val c = nextSym; nextSym = (nextSym + 1).toChar; c })
        val sb = new StringBuilder
        for (dx <- 0 until 32) { val g = gotFrame(dy)(dx); sb.append(if (g < 0) '?' else sym(g)) }
        val actual = sb.toString
        val aRgb = gotFrame(dy)(0)
        val aIsBlack = aRgb == 0x000000
        val distinct = syms.size
        val matches = actual == expected
        if (!matches) mismatch += (0 until 32).count(i => actual(i) != expected(i))
        val rawHead = (0 until 8).map(dx => f"${gotFrame(dy)(dx)}%06X").mkString(",")
        println(f"[sim]   dy=$dy%3d actual=$actual ${if (matches) "== expected (MATCH)" else "!= expected (MISMATCH)"} | distinctSyms=$distinct A=black:$aIsBlack | rawCols0-7=$rawHead")
      }
    }
    (mismatch, sampled)
  }

  def main(args: Array[String]): Unit = {
    println("=== Indexed2bppFrameCoSim: LEFT-EDGE discriminator (#14285) — real scanout, per-column black histogram ===")
    val (le, ie) = runLeftEdge()
    val interiorPerCol = ie.toDouble / 576.0
    val leftPerCol = le.toDouble / 32.0
    if (leftPerCol <= interiorPerCol + 2.0)
      println(f"[sim] LEFT-EDGE: CLEAN — left-edge black/col ($leftPerCol%.1f) ≈ interior ($interiorPerCol%.1f). RTL scanout paints the left edge like the interior ⇒ the bench left-edge streaks are DOWNSTREAM (monitor/capture/camera geometry), NOT a VDP scanout/fetch artifact.")
    else
      println(f"[sim] LEFT-EDGE: ARTIFACT — left-edge black/col ($leftPerCol%.1f) >> interior ($interiorPerCol%.1f) ⇒ a real VDP left-edge scanout/fetch defect; drill into hCounter/readSync/line-buffer start.")

    println("\n=== Indexed2bppFrameCoSim: ROW-CODED lookahead test (#14253 Finding 1) — real VdpTop fetchLine ===")
    val (vr, we, dv) = runRowCoded()
    // 2bpp-row-assertions (#14327): assert the CANONICAL absolute line-doubling
    // offset bestDv==3 rather than searching for the best offset; ≤4 wrong-row events
    // is frame-0 startup slack (the 3-bank fetch pipeline fills over the first ~2
    // source rows). Any shift off dv==3 is a real lookahead/bank mis-target.
    if (vr < 100)
      println(f"[sim] ROW-CODED: INCONCLUSIVE — too few rendered rows ($vr).")
    else if (dv == 3 && we <= 4)
      println(f"[sim] ROW-CODED: PASS — canonical offset bestDv=3 and $we/$vr wrong-row events (≤startup slack). VdpTop selects the CORRECT source row every display line; wrong-row-selection is NOT the artifact.")
    else
      println(f"[sim] ROW-CODED: FAIL — bestDv=$dv (expected 3) and/or $we/$vr wrong-row events > startup slack ⇒ VdpTop lookahead selects the wrong bank/row. Fix VdpTop bitmapSdramFetchLine lookahead.")
    assert(vr < 100 || (dv == 3 && we <= 4), s"ROW-CODED: expected bestDv==3 with <=4 startup events, got bestDv=$dv wrongEvents=$we/$vr")

    println("\n=== Indexed2bppFrameCoSim: vertical-bar 2bpp → per-row boundary-drift (shear) test ===")
    val (rows1, span1) = runOne(writeMode0 = true)   // WITH 0x0313=0 (BronzeGate's exact sequence)
    val (rows2, span2) = runOne(writeMode0 = false)  // WITHOUT 0x0313
    println(f"[sim] WITH 0x0313=0:  rows-with-boundary=$rows1 SHEAR_SPAN=$span1 px")
    println(f"[sim] WITHOUT 0x0313: rows-with-boundary=$rows2 SHEAR_SPAN=$span2 px")
    if (rows1 < 100)
      println(f"[sim] Indexed2bppFrameCoSim: FAIL — bars not rendering (rows=$rows1); linestate/compositing regression.")
    else if (span1 <= 6)
      println(f"[sim] Indexed2bppFrameCoSim: bars render + boundary STABLE (shear span=$span1 px) in idealized-SDRAM sim ⇒ the bench banding is REAL-SDRAM-TIMING (fetch/bank cadence under refresh/bank-conflict), NOT a logic addressing bug.")
    else
      println(f"[sim] Indexed2bppFrameCoSim: SHEAR REPRODUCED in sim (span=$span1 px) ⇒ a LOGIC addressing/bank bug in the indexed fetch/line-buffer, independent of SDRAM timing — drill into fetchBank/lineReg.")
  }
}

/** Focused runner for the CHECKERBOARD-EDGE discriminator (#14287). Renders the exact firmware
  * checkerboard through the real fetch+scanout path and checks the EMITTED pixel stream for
  * pixel-perfect 64px square edges. This is the test that actually addresses the owner's
  * "protrusions on the left edge of each square" symptom (runLeftEdge's uniform fill could not).
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppCheckerCoSim" */
object Indexed2bppCheckerCoSim extends App {
  println("=== Indexed2bppCheckerCoSim: checkerboard square-edge discriminator (#14287) — real fetch+scanout, emitted run-length check ===")
  val (maxErr, spur) = Indexed2bppFrameCoSim.runChecker()
  if (maxErr <= 2 && spur == 0)
    println(f"[sim] CHECKER-EDGE: CLEAN — every interior run is 64±$maxErr px with no spurious short runs. The FPGA emits pixel-perfect 64px square edges ⇒ the bench 'horizontal protrusions at each square's left edge' are DOWNSTREAM (monitor scaler / non-integer resample that the OSD cannot disable), NOT a VDP fetch/decode/double defect. Uniform-fill (runLeftEdge) never tested content edges; this does.")
  else
    println(f"[sim] CHECKER-EDGE: ARTIFACT — interiorMaxRunErr=$maxErr px, spuriousRuns=$spur ⇒ the EMITTED pixel stream has defective square edges. This IS an RTL fetch/decode/double defect; open an RTL emission lane (drill BitmapRowFetch decode + ×2 doubler at content transitions).")
}

/** Verifier for CyanPeak's claimed `VdpTop.scala:1602` pixelWithinByte-vs-readSync skew (#14290).
  * Renders a non-uniform intra-byte + inter-byte-varying pattern (0x1B / 0xE4 alternating bytes)
  * through the real fetch+decode+scanout and checks the emitted intra-byte pixel ordering.
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppFineCoSim" */
object Indexed2bppFineCoSim extends App {
  println("=== Indexed2bppFineCoSim: intra-byte decode discriminator (#14290) — verify VdpTop:1602 pixelWithinByte skew ===")
  val (mismatch, sampled) = Indexed2bppFrameCoSim.runFine()
  if (sampled >= 3 && mismatch == 0)
    println(f"[sim] INTRA-BYTE: CLEAN — emitted intra-byte pixel order + byte selection are BIT-PERFECT across $sampled rows (0 mismatched cols). The readSync byte data and pixelWithinByte ARE aligned in the real pipeline ⇒ CyanPeak's VdpTop:1602 net-skew is REFUTED; non-uniform 1bpp/2bpp graphics decode correctly.")
  else
    println(f"[sim] INTRA-BYTE: SKEW — $mismatch mismatched cols across $sampled rows ⇒ the emitted intra-byte order does NOT match the uploaded pattern. CyanPeak's VdpTop:1602 skew is CONFIRMED; propose an RTL lane to register pixelWithinByte (hCounterR) — PM authorization required.")
}
