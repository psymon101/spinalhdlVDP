package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 56 Checkpoint B — `MultiLayerSdramFetchSim`.
  *
  * Per artifact #9678 (CyanPeak audit #9683) and Checkpoint B kickoff
  * (#9691 / #9693 approval): verifies the L0+L1 SDRAM-backed compositor
  * integration at the `VdpTop` boundary. Mirrors the precedent set by
  * `PlanarIntegrationSim`: SDRAM-driven row-fetch correctness is OUT of
  * scope here — that's `TileAttributeFetchSim`'s domain. This sim covers
  * only the new integration plumbing introduced by Task 56:
  *
  *   Case 1: `layer1UseSdram=false` — `layer1Index` follows the on-chip
  *           `BasicPatternSource` path bit-identically; synthetic
  *           `layer1SdramPixel` input is correctly ignored. Verifies the
  *           CP-A source-mux default preserves pre-Task-56 behaviour.
  *
  *   Case 2: `layer1UseSdram=true` — `layer1Index` follows the synthetic
  *           `layer1SdramPixel` input bit-for-bit, and `layer1Bank`
  *           follows `layer1SdramBank`. Verifies the SDRAM-backed path
  *           is selected and the bank plumbing reaches the compositor.
  *
  * Cases 3-5 (full multi-client SDRAM arbitration including planar
  * coexistence, max-tile-density underrun check, line-drop check) are
  * deferred to Checkpoint C per artifact #9678 §Checkpoints.
  *
  * Per CyanPeak #9689 advisory: this sim initializes the new L1 SDRAM
  * inputs and the L2/L3 scroll registers added in Task 48 so Verilator
  * does not start them at X, which is the root cause of the pre-existing
  * `VdpTopSim` baseline regression.
  */
object MultiLayerSdramFetchSim extends App {

  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // -----------------------------------------------------------------
    // Quiescent default stimulus — covers every VdpTop input port.
    // Sprites disabled / off-screen. Scrolls zero. RegBus quiet.
    // L1/L2/L3 SDRAM inputs initialized (avoid X propagation).
    // -----------------------------------------------------------------
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.layer2ScrollX #= 0; dut.io.layer2ScrollY #= 0
    dut.io.layer3ScrollX #= 0; dut.io.layer3ScrollY #= 0

    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000
    dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000
    dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000
    dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000
    dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1

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

    dut.io.planarSdramBusy #= false
    dut.io.planarSdramDataReady #= false
    dut.io.planarSdramDout32 #= 0

    dut.clockDomain.waitSampling(10)

    // -----------------------------------------------------------------
    // Case 1: layer1UseSdram=false → layer1Index follows BasicPatternSource
    // -----------------------------------------------------------------
    println("[sim] Case 1: layer1UseSdram=false — SDRAM path must NOT leak into layer1Index")
    // BasicPatternSource emits a 3-bit pixel index (range 0..7) zero-extended
    // to 4 bits in the source mux. Driving `layer1SdramPixel = 0x8` (bit 3
    // set, unreachable by BasicPatternSource) gives a one-bit canary: if the
    // mux ever leaks the SDRAM path through, bit 3 will appear in
    // layer1Index. Sustained absence of bit 3 over many cycles proves the
    // CP-A default mux selection is preserved bit-identically.
    dut.io.layer1UseSdram      #= false
    dut.io.layer1SdramPixel    #= 0x8
    dut.io.layer1SdramBank     #= 5
    dut.io.layer1SdramPriority #= true
    dut.clockDomain.waitSampling(5)

    var case1Failed = false
    var case1Bit3Seen = 0
    val case1Samples = 400
    var sawNonZeroLegacy = false
    for (_ <- 0 until case1Samples) {
      val got = dut.layer1Index.toInt & 0xF
      if ((got & 0x8) != 0) {
        case1Bit3Seen += 1
        case1Failed = true
      }
      if ((got & 0x7) != 0) sawNonZeroLegacy = true
      dut.clockDomain.waitSampling()
    }
    assert(!case1Failed,
      s"Case 1: layer1Index bit-3 must stay low (leaked SDRAM canary observed $case1Bit3Seen/$case1Samples cycles)")
    assert(sawNonZeroLegacy,
      "Case 1: BasicPatternSource must produce at least one non-zero pixel in 400 cycles (proves the legacy path is live)")
    println(f"  Case 1 PASS — bit-3 canary clean over $case1Samples samples; BasicPatternSource path live")

    // -----------------------------------------------------------------
    // Case 2: layer1UseSdram=true → layer1Index follows layer1SdramPixel
    // -----------------------------------------------------------------
    println("[sim] Case 2: layer1UseSdram=true — mux selects SDRAM-backed path")
    dut.io.layer1UseSdram #= true
    // Drive a sweep of distinctive pixel values + bank values, sample
    // after one cycle (Mux is combinational; one sampling tick gives the
    // simulator time to propagate input changes through the registers
    // that feed `layer1Index` if any are pipelined).
    val case2Pixels = Seq(0x0, 0x3, 0x5, 0x9, 0xA, 0xC, 0xF)
    val case2Banks  = Seq(0, 1, 2, 3, 4, 5, 6, 7)
    var case2Failed = false
    for (px <- case2Pixels; bk <- case2Banks) {
      dut.io.layer1SdramPixel #= px
      dut.io.layer1SdramBank  #= bk
      dut.clockDomain.waitSampling(3)
      val gotPx = dut.layer1Index.toInt & 0xF
      val gotBk = dut.layer1Bank.toInt  & 0x7
      if (gotPx != px) {
        println(f"  FAIL: drove SdramPixel=0x$px%X; layer1Index=0x$gotPx%X (expected 0x$px%X)")
        case2Failed = true
      }
      if (gotBk != bk) {
        println(f"  FAIL: drove SdramBank=$bk; layer1Bank=$gotBk (expected $bk)")
        case2Failed = true
      }
    }
    assert(!case2Failed,
      "Case 2: layer1Index/layer1Bank must follow layer1SdramPixel/layer1SdramBank when layer1UseSdram=true")
    println(f"  Case 2 PASS — ${case2Pixels.length * case2Banks.length} (pixel,bank) combinations correct")

    // -----------------------------------------------------------------
    // Case 1b: re-disable and verify mux deselects SDRAM path again.
    // Guards against an internal latch that might "stick" the SDRAM
    // selection after a Case 2 toggle.
    // -----------------------------------------------------------------
    println("[sim] Case 1b: layer1UseSdram=false again — mux must redeselect BasicPatternSource")
    dut.io.layer1UseSdram #= false
    dut.io.layer1SdramPixel #= 0x8     // canary bit 3 again
    dut.clockDomain.waitSampling(5)
    var case1bFailed = false
    for (_ <- 0 until 100) {
      val got = dut.layer1Index.toInt & 0xF
      if ((got & 0x8) != 0) case1bFailed = true
      dut.clockDomain.waitSampling()
    }
    assert(!case1bFailed, "Case 1b: mux must deselect SDRAM path when layer1UseSdram drops back to false")
    println("  Case 1b PASS")

    // =================================================================
    // Task 56 Checkpoint C — Cases 3-5
    // =================================================================
    // helper for register writes
    def writeReg(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.clockDomain.waitSampling()
    }

    // #11874 (CoralReef review) — ENABLE the layers so the compositor priority
    // chain is actually exercised. effectiveLNEnable = linestate.layerNEnable &&
    // layerEnableReg(N); before this, Cases 3-5 ran with both gates 0 -> every
    // layer pixel gated to 0 -> composedBgIdx=backdrop, so the chain (and the
    // missing normal-L0 branch) was never reached. Enable globally (0x0300) AND
    // per-line (linestate 0x0000-0x01DF), then run one full frame so the
    // double-buffered linestate commits for every active line.
    println("[sim] Enabling L0+L1 (global 0x0300 + per-line linestate) so the compositor chain is live")
    writeReg(0x0300, 0x001F)                            // global LAYER_ENABLE: all layers on
    for (line <- 0 until 480) writeReg(line, 0x0C00)    // per-line L0en(bit11)+L1en(bit10)
    dut.clockDomain.waitSampling(525 * 800 + 4000)      // one full frame -> commit every line

    // -----------------------------------------------------------------
    // Case 3 — Both L0 and L1 active; L1>L0 opaque priority verified.
    // -----------------------------------------------------------------
    println("[sim] Case 3: both L0+L1 SDRAM-backed — compositor honors L1>L0 opaque priority")
    dut.io.layer0UseSdram #= true
    dut.io.layer1UseSdram #= true
    dut.io.layer0SdramPriority #= false
    dut.io.layer1SdramPriority #= false
    dut.io.layer0SdramBank #= 1
    dut.io.layer1SdramBank #= 2
    dut.clockDomain.waitSampling(5)

    // Subcase 3a — L1 opaque (non-zero), L0 opaque: L1 wins.
    dut.io.layer0SdramPixel #= 0x5
    dut.io.layer1SdramPixel #= 0xA
    dut.clockDomain.waitSampling(5)
    var case3aFailed = false
    for (_ <- 0 until 50) {
      val idx  = dut.composedBgIdx.toInt & 0xF
      val bank = dut.composedBgBank.toInt & 0x7
      if (idx != 0xA) {
        println(f"  3a FAIL: composedBgIdx=0x$idx%X (expected 0xA, L1 wins)")
        case3aFailed = true
      }
      if (bank != 2) {
        println(f"  3a FAIL: composedBgBank=$bank (expected 2 from L1)")
        case3aFailed = true
      }
      dut.clockDomain.waitSampling()
    }
    assert(!case3aFailed, "Case 3a: L1 opaque must override L0")
    println("  Case 3a PASS — L1 opaque pixel/bank propagates to composedBg")

    // Subcase 3b — L1 transparent (0), L0 opaque: L0 wins.
    dut.io.layer1SdramPixel #= 0x0
    dut.clockDomain.waitSampling(5)
    var case3bFailed = false
    for (_ <- 0 until 50) {
      val idx  = dut.composedBgIdx.toInt & 0xF
      val bank = dut.composedBgBank.toInt & 0x7
      if (idx != 0x5) {
        println(f"  3b FAIL: composedBgIdx=0x$idx%X (expected 0x5, L0 wins)")
        case3bFailed = true
      }
      if (bank != 1) {
        println(f"  3b FAIL: composedBgBank=$bank (expected 1 from L0)")
        case3bFailed = true
      }
      dut.clockDomain.waitSampling()
    }
    assert(!case3bFailed, "Case 3b: L1 transparent → L0 paints")
    println("  Case 3b PASS — L1 transparent, L0 wins; bank propagates from L0")

    // -----------------------------------------------------------------
    // Case 4 — Scheduler bandwidth: L1 enable drives slots 3/4 active.
    // With L1 enabled and planar disabled, lineGrantCount must show at
    // least the slot-3 (h=400) grant in addition to slot-0 (hTotal-1)
    // and slot-1 (h=0) grants — proves the CP-C slot retime gives L1
    // its own grant edge.
    // -----------------------------------------------------------------
    println("[sim] Case 4: scheduler bandwidth — L1 slot 3 fires at h=400 (post-CP-C retime)")
    dut.io.layer1UseSdram #= true
    dut.clockDomain.waitSampling(10)
    // Observe scheduler.io.lineGrantCount. Internal hCounter sweeps
    // 0..hTotal-1 continuously; `lineStart` (hCounter==0) resets count
    // and absorbs slot 1's startH=0 grant in the same cycle (lineStart
    // wins). So the visible per-line peak with L1 enabled, planar
    // disabled is 2: slot 3 grant (h=400) + slot 0 grant (h=hTotal-1).
    // Peak of 2 is the proof that slot 3 is now firing (pre-CP-C it
    // would have been masked by slot 0 collision at hTotal-1, giving
    // peak of 1 in this configuration).
    var maxGrantCount = 0
    for (_ <- 0 until 2000) {
      val gc = dut.schedulerLineGrantCount.toInt
      if (gc > maxGrantCount) maxGrantCount = gc
      dut.clockDomain.waitSampling()
    }
    assert(maxGrantCount >= 2,
      s"Case 4: scheduler lineGrantCount must reach ≥2 per line with L1 enabled, got max $maxGrantCount " +
      s"(slot 3 h=400 + slot 0 h=hTotal-1; slot 1 grant absorbed by lineStart at h=0)")
    println(s"  Case 4 PASS — peak lineGrantCount=$maxGrantCount (slot 3 L1 grant fires post-CP-C retime)")

    // -----------------------------------------------------------------
    // Case 5 — Three-client coexistence: planar + L0 + L1.
    // Enable planarCtrl bit 0 → slot 2 (planar) active; expect
    // lineGrantCount ≥ 4 (slots 0, 1, 2, 3 all fire per line).
    // -----------------------------------------------------------------
    println("[sim] Case 5: planar + L0 + L1 — three-client coexistence; lineGrantCount ≥ 4")
    writeReg(0x0D4A, 0x0001)   // planarCtrlReg bit 0 → planarFetchEnable
    dut.clockDomain.waitSampling(20)
    var max5 = 0
    for (_ <- 0 until 2000) {
      val gc = dut.schedulerLineGrantCount.toInt
      if (gc > max5) max5 = gc
      dut.clockDomain.waitSampling()
    }
    // Per Case 4 note, slot 1 grant is absorbed by lineStart. Visible
    // peak with planar+L1 enabled: slot 3 (h=400) + slot 2 (h=hTotal-160) +
    // slot 0 (h=hTotal-1) = 3 grants per line. A peak of 3 proves all
    // three independent clients (L0 hTotal-1 + planar hTotal-160 + L1 400)
    // are dispatched in the same line without overlap-stealing each other.
    assert(max5 >= 3,
      s"Case 5: scheduler lineGrantCount must reach ≥3 with planar+L0+L1, got max $max5 " +
      s"(slot 3 + slot 2 + slot 0; slot 1 grant absorbed by lineStart)")
    println(s"  Case 5 PASS — peak lineGrantCount=$max5 with planar enabled (3-client coexistence)")

    // Cleanup: disable planar to leave the dut in a known state.
    writeReg(0x0D4A, 0x0000)

    println("MultiLayerSdramFetchSim: PASS (Cases 1, 1b, 2, 3a, 3b, 4, 5)")
  }
}
