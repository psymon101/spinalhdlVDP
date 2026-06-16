package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** 320-pixel planar clipping mask proof sim (PM #9736 lane,
  * MODE0_PLANNING.md §6 rank 3).
  *
  * Verifies the consumer-side clip applied to the L0 planar source mux
  * in `VdpTop`. The planar source has native width `PLANE_PIXELS = 320`;
  * `planarLineFetch.io.pixelIdx` is driven by `hCounter % 320`, which
  * means the planar pixel wraps and repeats for `hCounter` in
  * `[320, 639]` without the clip. The clip suppresses the planar
  * contribution to L0 outside the `[0, 320)` window so the existing
  * legacy L0 source chain (affine → test pattern → bitmap → SDRAM →
  * on-chip BasicPatternSource with `layer0ScrollX/Y`) is preserved
  * bit-identically there.
  *
  * Cases:
  *   Case 1: Clip control signal sanity.
  *           `planarClipActive` (= `hCounter < PLANE_PIXELS`) tracks
  *           `hCounter` correctly over a full line: true on h=[0,319],
  *           false on h=[320, hTotal). With `planarFetchEnable=1`,
  *           `planarFetchEnableClipped` is true on h=[0,319] only.
  *
  *   Case 2: Clip behaviour with a deterministic fallback.
  *           Configure `planarFetchEnable=1` AND `layer0UseSdram=1`
  *           with `layer0SdramPixel = 0xA`. Planar SDRAM is held
  *           quiescent so `planarLineFetch.io.pixel = 0`. Sample
  *           `layer0Index` over a full line.
  *             - h in [0, 320): planar path is allowed → idx = 0
  *             - h in [320, hActive): planar is clipped → mux falls
  *               through to the SDRAM path → idx = 0xA
  *           This deterministic fallback (independent of `fillLine`
  *           or `layer0Scroll*`) cleanly proves the clip without
  *           tangling with the on-chip BasicPatternSource's y-walk.
  *
  *   Case 3: Right-edge wrap absence (explicit witness).
  *           The would-be wrap value at h=320..639 from the
  *           quiescent planar source is 0; the deterministic SDRAM
  *           fallback is 0xA. Case 2 ensures every column in
  *           [320, hActive) reads 0xA, not 0 — proving no planar
  *           contribution leaks past the clip boundary.
  *
  * The "clip is consumer-only" contract — i.e. `planarLineFetch.io
  * .start` and `scheduler.io.schedule(2).enabled` still depend on
  * raw `planarFetchEnable`, not on the clipped variant — is
  * verified by static code review of `VdpTop.scala` (grep for
  * `planarFetchEnable\b`), not by this sim. The fetch FSM is not
  * exercised end-to-end here because the SDRAM is held quiescent.
  *
  * SDRAM is held quiescent (no dataReady) so PlanarLineFetch's
  * `io.pixel` stays at 0 — this lets us verify the clip *gate*
  * without needing SDRAM-driven row content. Hardware proof covers
  * SDRAM-driven planar content separately on the Tang Nano 20K.
  */
object PlanarClipSim extends App {

  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent stimulus — match PlanarIntegrationSim defaults.
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
      dut.clockDomain.waitSampling()
    }

    // Constants from VdpTop.scala. hTotal=800, hActive=640.
    val hTotal       = 800
    val hActive      = 640
    val PLANE_PIXELS = 320

    case class LineSample(
        h: Int, layer0Idx: Int,
        clipActive: Boolean, fetchClipped: Boolean
    )
    def captureLine(): IndexedSeq[LineSample] = {
      // SIM-TEST-DEBT-138: sample the display `hCounter` directly (simPublic),
      // NOT io.layer0FetchPixelAddr. planarClipActive (= hCounter < planarWidth)
      // and layer0Index are combinational off hCounter, so reading hCounter in
      // the same step is an in-phase comparison. io.layer0FetchPixelAddr is a
      // gated prefetch address (hCounter+1, zeroed outside the fetch window) in a
      // different pipeline phase — the source of the pre-existing sample-skew.
      // Wait for a wrap so sampling starts cleanly at h=0.
      var prev = dut.hCounter.toLong.toInt
      var waited = 0
      while (!(prev != 0 && dut.hCounter.toLong.toInt == 0) && waited < 2 * hTotal) {
        prev = dut.hCounter.toLong.toInt
        dut.clockDomain.waitSampling()
        waited += 1
      }
      assert(waited < 2 * hTotal, s"never observed hCounter wrap within ${2*hTotal} cycles (saw $prev)")
      val out = scala.collection.mutable.ArrayBuffer[LineSample]()
      for (_ <- 0 until hTotal) {
        val h    = dut.hCounter.toLong.toInt
        val idx  = dut.layer0Index.toLong.toInt & 0xF
        val pca  = dut.planarClipActive.toBoolean
        val pfec = dut.planarFetchEnableClipped.toBoolean
        out += LineSample(h, idx, pca, pfec)
        dut.clockDomain.waitSampling()
      }
      out.toIndexedSeq
    }

    // ---------------------------------------------------------------
    // Case 1: clip control signals track hCounter
    // ---------------------------------------------------------------
    println("[sim] Case 1: planarClipActive / planarFetchEnableClipped track hCounter")
    writeReg(0x0D4A, 0x0001) // planar enabled
    dut.clockDomain.waitSampling(20)
    val line1 = captureLine()
    assert(line1.length == hTotal, s"captured ${line1.length} samples, expected $hTotal")
    var case1Failed = false
    for (s <- line1) {
      val expectClip   = s.h < PLANE_PIXELS
      val expectFetchC = expectClip // planarFetchEnable=1 here
      if (s.clipActive != expectClip) {
        println(f"  h=${s.h}%3d planarClipActive=${s.clipActive} expected=$expectClip FAIL")
        case1Failed = true
      }
      if (s.fetchClipped != expectFetchC) {
        println(f"  h=${s.h}%3d planarFetchEnableClipped=${s.fetchClipped} expected=$expectFetchC FAIL")
        case1Failed = true
      }
    }
    val keyHs = Seq(0, 1, 159, 318, 319, 320, 321, 360, 639, 700, hTotal - 1)
    for (h <- keyHs if h < hTotal) {
      val s = line1(h)
      println(f"  h=$h%4d clipActive=${s.clipActive}%5s fetchClipped=${s.fetchClipped}%5s idx=0x${s.layer0Idx}%X")
    }
    assert(!case1Failed, "Case 1 failed: clip gate did not track hCounter as expected")
    println("  Case 1 PASS — planarClipActive=true on h=[0,319], false on h=[320, hTotal)")

    // ---------------------------------------------------------------
    // Case 2: clip behaviour with deterministic SDRAM fallback
    // ---------------------------------------------------------------
    println("[sim] Case 2: clip behaviour with layer0Sdram fallback (planar pixel=0, SDRAM=0xA)")
    // planar already enabled from Case 1. Layer fallthrough cleanly
    // routes to `io.layer0SdramPixel` when affineEnable=0,
    // layer0TestPatternEnable=0, bitmapEnable=0, layer0UseSdram=1.
    dut.io.layer0UseSdram #= true
    dut.io.layer0SdramPixel #= 0xA
    dut.io.layer0SdramBank  #= 0
    dut.io.layer0SdramPriority #= false
    dut.clockDomain.waitSampling(20)
    val line2 = captureLine()
    var inWindowMisses    = 0
    var outOfWindowMisses = 0
    for (s <- line2 if s.h < hActive) {
      if (s.h < PLANE_PIXELS) {
        if (s.layer0Idx != 0x0) {
          inWindowMisses += 1
          if (inWindowMisses <= 5) {
            println(f"  h=${s.h}%3d in-window L0 idx=0x${s.layer0Idx}%X (expected 0)")
          }
        }
      } else {
        if (s.layer0Idx != 0xA) {
          outOfWindowMisses += 1
          if (outOfWindowMisses <= 5) {
            println(f"  h=${s.h}%3d clipped-region L0 idx=0x${s.layer0Idx}%X (expected 0xA — SDRAM fallback)")
          }
        }
      }
    }
    println(f"  in-window  (h=[0,$PLANE_PIXELS)) misses : $inWindowMisses")
    println(f"  out-window (h=[$PLANE_PIXELS,$hActive)) misses : $outOfWindowMisses")
    assert(inWindowMisses == 0,
      s"Case 2: planar-on run produced $inWindowMisses non-zero L0 idx values in [0,$PLANE_PIXELS) — quiescent planar should drive 0")
    assert(outOfWindowMisses == 0,
      s"Case 2: clip leaked planar through in $outOfWindowMisses columns in [$PLANE_PIXELS,$hActive) — SDRAM fallback (0xA) not selected")
    println(s"  Clip window [0,$PLANE_PIXELS): all $PLANE_PIXELS columns L0 idx=0 (planar pixel) ✓")
    println(s"  Clipped region [$PLANE_PIXELS,$hActive): all ${hActive - PLANE_PIXELS} columns L0 idx=0xA (SDRAM fallback) ✓")
    println("  Case 2 PASS")

    // ---------------------------------------------------------------
    // Case 3: explicit right-edge wrap absence witness
    // ---------------------------------------------------------------
    println("[sim] Case 3: explicit right-edge wrap absence witness")
    // Pick representative columns just past the clip boundary and
    // dump the observed L0 idx alongside the planar wrap value (0).
    val witnessHs = Seq(PLANE_PIXELS, PLANE_PIXELS + 1, PLANE_PIXELS + 10, 480, hActive - 1)
    var case3Failed = false
    for (h <- witnessHs) {
      val idx = line2(h).layer0Idx
      println(f"  h=$h%3d L0 idx=0x$idx%X (would-be planar wrap value = 0; SDRAM fallback = 0xA)")
      if (idx != 0xA) case3Failed = true
    }
    assert(!case3Failed, "Case 3: at least one clipped-region column did not match SDRAM fallback — wrap leak detected")
    println("  Case 3 PASS — no right-edge wrap from planar path observed")

    println("PlanarClipSim: PASS (all 3 cases)")
  }
}
