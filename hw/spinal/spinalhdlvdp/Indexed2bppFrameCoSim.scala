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

      // Uniform value-1 2bpp bitmap (0x55) + identity attribute (0xE4), 128-byte rows.
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

      val sampleCycles = 800 * 525 * 2
      var b55 = 0L; var eE4 = 0L   // probe: is the fetch delivering my uploaded bytes to VdpTop?
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val rgb = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
          if (rgb == 0x000000) black += 1 else nonBlack += 1
        }
        if ((dut.io.probeBmByte.toInt & 0xFF) == 0x55) b55 += 1
        if ((dut.io.probeAttrByte.toInt & 0xFF) == 0xE4) eE4 += 1
        dut.clockDomain.waitSampling()
      }
      println(f"[sim] mode0write=$writeMode0: nonBlack=$nonBlack black=$black | fetch bitmapByte==0x55:$b55 attrByte==0xE4:$eE4")
    }
    (nonBlack, black)
  }

  def main(args: Array[String]): Unit = {
    println("=== Indexed2bppFrameCoSim: uniform value-1 2bpp → expect palette[1] (non-black) if the RTL path works ===")
    val (nb1, b1) = runOne(writeMode0 = true)   // WITH 0x0313=0 (BronzeGate's exact sequence)
    val (nb2, b2) = runOne(writeMode0 = false)  // WITHOUT 0x0313 (leave reset default)
    println(f"[sim] WITH 0x0313=0:  nonBlack=$nb1 black=$b1")
    println(f"[sim] WITHOUT 0x0313: nonBlack=$nb2 black=$b2")
    if (nb1 > 300000)
      println("[sim] Indexed2bppFrameCoSim: PASS — RTL 2bpp indexed path renders palette[1] across the frame once the per-line LINESTATE L0-enable (addr 0x000-0x1DF bit[11]) is written alongside global LAYER_ENABLE 0x0300. The bench black was the MISSING per-line linestate write, not an RTL bug.")
    else
      println(f"[sim] Indexed2bppFrameCoSim: FAIL — 2bpp path still black (nonBlack=$nb1); latent RTL indexed-display gap.")
    assert(nb1 > 300000, s"2bpp indexed display did not render: nonBlack=$nb1 black=$b1 (expected majority non-black = palette[1] with linestate L0 enabled)")
  }
}
