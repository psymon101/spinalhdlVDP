package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** PlanarTileFetchIntegrationSim — 2bpp planar HW-proof lane (thread #10888, PM #10898 Task B).
  *
  * THE GAP THIS CLOSES: every existing VdpTop-level sim (PlanarIntegrationSim,
  * MultiLayerSdramFetchSim) drives `layer0SdramPixel` SYNTHETICALLY and never
  * instantiates `SdramTileAttributeFetch`. The component sims
  * (PlanarWhiteTileFetchSim, TileAttributeFetchSim) drive the fetch with a
  * FORCED grant/slotValid and an idealized SDRAM. So NO sim today exercises the
  * real path that failed on silicon:
  *
  *     VdpTop scheduler grant  ->  (BufferCC into sdram domain inside fetch)
  *                             ->  SdramTileAttributeFetch FSM
  *                             ->  line buffer write
  *                             ->  pixelIndex read at pixelAddr=hCounter
  *
  * This harness wires VdpTop's REAL scheduler outputs into a REAL
  * SdramTileAttributeFetch, exactly as TopTang20kHdmi.scala:510-524 does, and
  * feeds the fetch's SDRAM bus from a behavioral SDRAM model (same model shape
  * as PlanarWhiteTileFetchSim). tileDecodeMode is set to planar (01) via a real
  * 0x0311 register write — NOT the DIAG hardwired init — so the sim does not
  * depend on the working-tree diagnostic diff.
  *
  * DISCRIMINANT (mirrors the bench uniform-white test):
  *   - all planar tile rows at 0xA000 overwritten to white (plane0=plane1=0xFFFF)
  *     so every tile decodes to index 3 regardless of which tile the tilemap
  *     selects (same trick PlanarWhiteTileFetchSim uses to sidestep the tilemap)
  *   - after boot, with real grants flowing, fetch.io.pixelIndex over the active
  *     region must read 3.
  *   FAIL in sim  -> bug reproduced in the integration path; bisect grant/CDC/linebuf.
  *   PASS in sim  -> bug is below RTL sim (Gowin synth / BSRAM inference / silicon
  *                   timing); pivot to on-silicon probing.
  *
  * STATUS (PM #10898 Task B): compile-verified, NOT yet authorized to execute.
  * Runtime-tuning points flagged inline (RUNTIME-TUNE) need confirmation on first
  * sanctioned run — primarily the post-boot settle and the active-region sample
  * window. Do not treat a green/red result as valid until those are checked.
  */
object PlanarTileFetchIntegrationSim {

  /** Faithful mini-TopTang: VdpTop + real SdramTileAttributeFetch, grant wiring
    * identical to TopTang20kHdmi.scala:510-524. SDRAM bus + diagnostics surfaced
    * to the testbench; all other VdpTop inputs tied to safe defaults.
    */
  class Dut extends Component {
    // sdram-side clock for the fetch FSM (matches TopTang sdramClockDomain freq).
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(64800000 Hz))

    val video = VdpTop(enableL1Fetch = false)
    // skipSdramInit=false so the boot ROM runs (writes planar tiles incl. the
    // tile-0=solid-0 boot pattern to 0xA000) — mirrors the real bring-up that
    // the bench test exercises. runMemtest=false matches the production fetch.
    val fetch = SdramTileAttributeFetch(sdramCd, skipSdramInit = false, runMemtest = false)

    // ---- REAL grant wiring — identical to TopTang20kHdmi.scala:510-518 ----
    fetch.io.fetchGrant       := video.io.layer0FetchGrant
    fetch.io.fetchSlotValid   := video.io.layer0FetchSlotValid
    fetch.io.fetchPreAnnounce := video.io.layer0FetchPreAnnounce
    fetch.io.tileDecodeMode   := video.io.layer0TileDecodeMode
    fetch.io.attributeMode    := video.io.layer0AttributeMode
    fetch.io.fetchLine        := video.io.layer0FetchLine
    fetch.io.fetchScrollX     := video.io.layer0FetchScrollX
    fetch.io.fetchScrollY     := video.io.layer0FetchScrollY
    fetch.io.pixelAddr        := video.io.layer0FetchPixelAddr

    // ---- pixel back into VdpTop L0 — matches TopTang20kHdmi.scala:521-524 ----
    video.io.layer0SdramPixel    := fetch.io.pixelIndex
    video.io.layer0SdramBank     := fetch.io.pixelPaletteBank
    video.io.layer0SdramPriority := fetch.io.pixelPriority
    video.io.layer0UseSdram      := True

    // ---- testbench-facing surface ----
    val io = new Bundle {
      // register-bus write port (flattened to avoid IMasterSlave plumbing).
      val regBusAddr   = in UInt(15 bits)
      val regBusData   = in Bits(16 bits)
      val regBusEnable = in Bool()
      // SDRAM behavioral-model bus (fetch master side, sdram domain).
      val sdramRd        = out Bool()
      val sdramWr        = out Bool()
      val sdramRefresh   = out Bool()
      val sdramAddr      = out UInt(23 bits)
      val sdramDin       = out Bits(8 bits)
      val sdramDout      = in  Bits(8 bits)
      val sdramDout32    = in  Bits(32 bits)
      val sdramDataReady = in  Bool()
      val sdramBusy      = in  Bool()
      // diagnostics
      val pixelIndex  = out Bits(4 bits)
      val bootDone    = out Bool()
      val memtestPass = out Bool()
      val memtestFail = out Bool()
    }
    video.io.regBus.addr       := io.regBusAddr
    video.io.regBus.data       := io.regBusData
    video.io.regBus.enable     := io.regBusEnable
    io.sdramRd         := fetch.io.sdramRd
    io.sdramWr         := fetch.io.sdramWr
    io.sdramRefresh    := fetch.io.sdramRefresh
    io.sdramAddr       := fetch.io.sdramAddr
    io.sdramDin        := fetch.io.sdramDin
    fetch.io.sdramDout      := io.sdramDout
    fetch.io.sdramDout32    := io.sdramDout32
    fetch.io.sdramDataReady := io.sdramDataReady
    fetch.io.sdramBusy      := io.sdramBusy
    io.pixelIndex  := fetch.io.pixelIndex
    io.bootDone    := fetch.io.bootDone
    io.memtestPass := fetch.io.memtestPass
    io.memtestFail := fetch.io.memtestFail

    // ---- tie off every other VdpTop input to a safe default ----
    video.io.layer0ScrollX := 0; video.io.layer0ScrollY := 0
    video.io.layer1ScrollX := 0; video.io.layer1ScrollY := 0
    video.io.layer2ScrollX := 0; video.io.layer2ScrollY := 0
    video.io.layer3ScrollX := 0; video.io.layer3ScrollY := 0
    video.io.sprite0X := 1000; video.io.sprite0Y := 1000; video.io.sprite0Enabled := False; video.io.sprite0PatternIdx := 0
    video.io.sprite1X := 1000; video.io.sprite1Y := 1000; video.io.sprite1Enabled := False; video.io.sprite1PatternIdx := 1
    video.io.sprite2X := 1000; video.io.sprite2Y := 1000; video.io.sprite2Enabled := False; video.io.sprite2PatternIdx := 0
    video.io.sprite3X := 1000; video.io.sprite3Y := 1000; video.io.sprite3Enabled := False; video.io.sprite3PatternIdx := 1
    video.io.layer0TestPatternSelect := 0; video.io.layer0TestPatternEnable := False
    video.io.layer1UseSdram := False; video.io.layer1SdramPixel := 0
    video.io.layer1SdramBank := 0; video.io.layer1SdramPriority := False
    video.io.bitmapSdramByte := 0; video.io.bitmapSdramAttrByte := 0
    video.io.rasterTriggerLine := 0; video.io.rasterTriggerPixel := 0
    video.io.rasterTriggerPxEnable := False; video.io.rasterTriggerEnable := False
    video.io.rasterTriggerClear := False
    video.io.statusEvQspiReady := False; video.io.statusEvQspiError := False
    video.io.planarSdramBusy := False; video.io.planarSdramDataReady := False
    video.io.planarSdramDout32 := 0
  }

  def main(args: Array[String]): Unit = {
    SimConfig.withWave.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.sdramCd.forkStimulus(10)

      // ---- behavioral SDRAM model (same shape as PlanarWhiteTileFetchSim) ----
      val mem = mutable.HashMap[Int, Int]()
      def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
      def readWord(a: Int): Long = {
        val base = a & ~3
        (readByte(base).toLong & 0xFF) | ((readByte(base + 1).toLong & 0xFF) << 8) |
          ((readByte(base + 2).toLong & 0xFF) << 16) | ((readByte(base + 3).toLong & 0xFF) << 24)
      }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false
      dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

      fork {
        for (_ <- 0 until 30) dut.sdramCd.waitSampling()
        dut.io.sdramBusy #= false
        var state = "idle"; var timer = 0; var op = ""; var latchedAddr = 0; var latchedDin = 0
        while (true) {
          dut.sdramCd.waitSampling(); dut.io.sdramDataReady #= false
          state match {
            case "idle" =>
              if (dut.io.sdramRd.toBoolean) { op = "rd"; latchedAddr = dut.io.sdramAddr.toInt; dut.io.sdramBusy #= true; state = "wait"; timer = 3 }
              else if (dut.io.sdramWr.toBoolean) { op = "wr"; latchedAddr = dut.io.sdramAddr.toInt; latchedDin = dut.io.sdramDin.toInt & 0xFF; dut.io.sdramBusy #= true; state = "wait"; timer = 5 }
              else if (dut.io.sdramRefresh.toBoolean) { op = "rf"; dut.io.sdramBusy #= true; state = "wait"; timer = 4 }
            case "wait" =>
              timer -= 1
              if (timer == 0) op match {
                case "rd" => dut.io.sdramDout #= readByte(latchedAddr) & 0xFF; dut.io.sdramDout32 #= BigInt(readWord(latchedAddr) & 0xFFFFFFFFL); dut.io.sdramDataReady #= true; state = "rdDone"
                case "wr" => mem(latchedAddr & 0x7fffff) = latchedDin; dut.io.sdramBusy #= false; state = "idle"
                case "rf" => dut.io.sdramBusy #= false; state = "idle"
              }
            case "rdDone" => dut.io.sdramBusy #= false; state = "idle"
          }
        }
      }

      def writeReg(addr: Int, data: Int): Unit = {
        dut.io.regBusAddr #= addr; dut.io.regBusData #= data; dut.io.regBusEnable #= true
        dut.clockDomain.waitSampling(); dut.io.regBusEnable #= false; dut.clockDomain.waitSampling()
      }

      // Set planar tile mode via the REAL 0x0311 commit path (not the DIAG init).
      writeReg(0x0311, 0x0001)   // VDP_TILE_MODE = planar
      writeReg(0x0312, 0x0000)   // VDP_ATTR_MODE = linear
      writeReg(0x0300, 0x0001)   // LAYER_ENABLE = L0

      // Wait for boot copy + (skipped) memtest. RUNTIME-TUNE: bound generous.
      var timeout = 2000000
      while (!dut.io.bootDone.toBoolean && timeout > 0) { dut.clockDomain.waitSampling(); timeout -= 1 }
      assert(timeout > 0, "timed out waiting for bootDone")
      println("[sim] bootDone")

      // Overwrite ALL planar tile rows at 0xA000 with white (both planes 0xFFFF)
      // so every tile decodes to index 3 regardless of tilemap selection — same
      // tilemap-sidestep PlanarWhiteTileFetchSim uses, mirroring the bench test.
      val pbase = PlanarTileAssets.SdramBase
      val whiteRow = Array(0xFF, 0xFF, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00)
      for (t <- 0 until PlanarTileAssets.TileCount; y <- 0 until PlanarTileAssets.TileHeight; b <- 0 until 8) {
        mem((pbase + (t * PlanarTileAssets.TileHeight + y) * 8 + b) & 0x7fffff) = whiteRow(b)
      }
      println(f"[sim] overwrote all planar tiles white at 0x$pbase%X")

      // RUNTIME-TUNE: let several frames elapse so the real scheduler grant
      // fetches a line and the ping-pong line buffer presents it on the read
      // side. One 640x480 frame ~ 800*525 pixel clocks; sample a few frames in.
      dut.clockDomain.waitSampling(800 * 525 * 3)

      // RUNTIME-TUNE: sample fetch.io.pixelIndex over a span of active video and
      // assert it reads white (3). pixelAddr is driven by video's hCounter, so
      // we just observe over many cycles and histogram the result.
      val hist = mutable.HashMap[Int, Int]().withDefaultValue(0)
      for (_ <- 0 until 4000) {
        hist(dut.io.pixelIndex.toInt) += 1
        dut.clockDomain.waitSampling()
      }
      val total = hist.values.sum
      val threes = hist.getOrElse(3, 0)
      println(s"[sim] pixelIndex histogram over $total samples: ${hist.toSeq.sortBy(-_._2)}")
      println(f"[sim] index==3 fraction = ${threes.toDouble / total}%.4f")

      // NOTE: assertion intentionally lenient pending RUNTIME-TUNE confirmation
      // of the sample window. On a clean run this should be ~1.0 (active region).
      assert(threes > 0,
        "integration path produced ZERO white pixels — fetch/grant/linebuffer delivers nothing (bug reproduced in sim)")
      println("[sim] PlanarTileFetchIntegrationSim: ran (see fraction; tune window before trusting PASS/FAIL)")
    }
  }
}
