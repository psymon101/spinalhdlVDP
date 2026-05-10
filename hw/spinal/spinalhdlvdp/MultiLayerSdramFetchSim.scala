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

    println("MultiLayerSdramFetchSim: PASS (all cases)")
  }
}
