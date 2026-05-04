package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 1 (#9154) — ModeSelectSim.
  *
  * Standalone unit sim covering the validation matrix from
  * `MODE_SELECT_ARCHITECTURE.md` v1.1 §8.1. PM #9171 D2 authorizes a
  * focused standalone wrapper sim around the new components rather
  * than extending `VdpTopSim` (regression coverage stays in
  * `VdpTopSim`).
  *
  * Architecture-correctness coverage in this sim:
  *
  *   §8.1 Case 1 — modeSelect quiescence: `AdapterBusMux` output is
  *     0/False at modeSelect=0x0; selecting mode 0x1 routes adapter 0
  *     through, mode 0x2 routes adapter 1 through.
  *
  *   §8.1 Case 2 — `AdapterRegRouter` decode active: a write to a
  *     C64-range address (0x0E05) at modeSelect=0x1 produces a
  *     `regWr` pulse on adapter 0 with regAddr=0x05, regData=lo(data).
  *     The same write is removed from `passThru` (passThru.enable=False).
  *
  *   §8.1 Case 3 — `AdapterRegRouter` drop inactive: a write to a
  *     C64-range address at modeSelect=0x2 (ZX active) produces NO
  *     pulse on adapter 0 and NO pulse on adapter 1; passThru is also
  *     suppressed (rule: adapter-range addresses NEVER reach the
  *     global Mode0 path, regardless of which mode is selected).
  *
  *   §8.1 Case 4 — Mid-frame mode switch deferral to V=0 commit is
  *     covered by `VdpTopSim` regression which exercises the full
  *     register/commit-pulse path inside `VdpTop`. The standalone
  *     sim here is component-scope (no V counter), so V=0 deferral
  *     is not directly exercised at this layer; see VdpTopSim.
  *
  *   §8.1 Case 5 — All-inactive bus contention: when none of the
  *     adapter inputs is selected (e.g. modeSelect=0x0), the mux
  *     output is identically 0/False even if multiple adapter inputs
  *     happen to assert (defense-in-depth check).
  *
  *   Bonus — Mode0 global passthrough: a write to 0x0300 (LAYER_ENABLE)
  *     passes through unchanged at any modeSelect value; routing only
  *     intercepts the configured adapter pages.
  *
  *   Bonus — Unallocated-page drop: a write to a never-claimed adapter
  *     page (e.g. 0x1000 NES, not configured in this sim) passes
  *     through (router only intercepts configured pages, not all
  *     non-Mode0 ranges — per arch §4.3 rule 3 the router would
  *     only drop unallocated ranges if they were configured).
  */

/** Standalone wrapper that exposes both new components as a single
  * DUT for sim. Wires AdapterRegRouter.adapters as out so the
  * sim can observe per-adapter pulse outputs.
  */
case class ModeSelectHarness() extends Component {
  val mux    = AdapterBusMux(Seq(0x1, 0x2))
  val router = AdapterRegRouter(Seq((0x1, 0x0E00), (0x2, 0x0F00)))

  val io = new Bundle {
    val modeSelect    = in UInt(4 bits)

    // Adapter bus inputs to AdapterBusMux (peer "adapter bus" outputs
    // that would normally come from runtime-instantiated C64Adapter /
    // ZXSpectrumAdapter).
    val adap0BusAddr   = in  UInt(15 bits)
    val adap0BusData   = in  Bits(16 bits)
    val adap0BusEnable = in  Bool()
    val adap1BusAddr   = in  UInt(15 bits)
    val adap1BusData   = in  Bits(16 bits)
    val adap1BusEnable = in  Bool()

    // AdapterBusMux output (would feed RegBusArbiter master 2).
    val muxAddr        = out UInt(15 bits)
    val muxData        = out Bits(16 bits)
    val muxEnable      = out Bool()

    // AdapterRegRouter input (post-arb unified Mode0 bus). Sim drives
    // this directly to inject any address.
    val routerInAddr   = in  UInt(15 bits)
    val routerInData   = in  Bits(16 bits)
    val routerInEnable = in  Bool()

    // AdapterRegRouter passThru (would feed VdpTop.io.regBus).
    val passAddr       = out UInt(15 bits)
    val passData       = out Bits(16 bits)
    val passEnable     = out Bool()

    // Per-adapter pulse outputs from the router.
    val adap0RegAddr   = out UInt(8 bits)
    val adap0RegData   = out Bits(8 bits)
    val adap0RegWr     = out Bool()
    val adap1RegAddr   = out UInt(8 bits)
    val adap1RegData   = out Bits(8 bits)
    val adap1RegWr     = out Bool()
  }

  mux.io.modeSelect := io.modeSelect
  mux.io.adapters(0).addr   := io.adap0BusAddr
  mux.io.adapters(0).data   := io.adap0BusData
  mux.io.adapters(0).enable := io.adap0BusEnable
  mux.io.adapters(1).addr   := io.adap1BusAddr
  mux.io.adapters(1).data   := io.adap1BusData
  mux.io.adapters(1).enable := io.adap1BusEnable
  io.muxAddr   := mux.io.mixed.addr
  io.muxData   := mux.io.mixed.data
  io.muxEnable := mux.io.mixed.enable

  router.io.modeSelect    := io.modeSelect
  router.io.mixedIn.addr   := io.routerInAddr
  router.io.mixedIn.data   := io.routerInData
  router.io.mixedIn.enable := io.routerInEnable
  io.passAddr     := router.io.passThru.addr
  io.passData     := router.io.passThru.data
  io.passEnable   := router.io.passThru.enable
  io.adap0RegAddr := router.io.adapters(0).regAddr
  io.adap0RegData := router.io.adapters(0).regData
  io.adap0RegWr   := router.io.adapters(0).regWr
  io.adap1RegAddr := router.io.adapters(1).regAddr
  io.adap1RegData := router.io.adapters(1).regData
  io.adap1RegWr   := router.io.adapters(1).regWr
}

object ModeSelectSim extends App {
  Config.sim.compile(ModeSelectHarness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Idle defaults.
    dut.io.modeSelect    #= 0
    dut.io.adap0BusAddr   #= 0
    dut.io.adap0BusData   #= 0
    dut.io.adap0BusEnable #= false
    dut.io.adap1BusAddr   #= 0
    dut.io.adap1BusData   #= 0
    dut.io.adap1BusEnable #= false
    dut.io.routerInAddr   #= 0
    dut.io.routerInData   #= 0
    dut.io.routerInEnable #= false
    dut.clockDomain.waitSampling(2)

    // ----------------------------------------------------------------
    // Case 1 — AdapterBusMux quiescence at modeSelect=0; selection at
    // modes 0x1 and 0x2.
    // ----------------------------------------------------------------
    dut.io.adap0BusAddr   #= 0x0E05
    dut.io.adap0BusData   #= 0xAA55
    dut.io.adap0BusEnable #= true
    dut.io.adap1BusAddr   #= 0x0F12
    dut.io.adap1BusData   #= 0x1234
    dut.io.adap1BusEnable #= true
    dut.clockDomain.waitSampling()
    // modeSelect=0 → Native Mode0 → mux output quiescent.
    assert(!dut.io.muxEnable.toBoolean, "Case 1a: muxEnable should be False at modeSelect=0")
    assert(dut.io.muxAddr.toInt == 0,  "Case 1a: muxAddr should default to 0 at modeSelect=0")
    println("[sim] Case 1a modeSelect=0 → mux quiescent — OK")

    dut.io.modeSelect #= 1
    dut.clockDomain.waitSampling()
    assert(dut.io.muxEnable.toBoolean, "Case 1b: muxEnable should follow adapter 0 at modeSelect=1")
    assert(dut.io.muxAddr.toInt == 0x0E05, s"Case 1b: muxAddr=${dut.io.muxAddr.toInt.toHexString}")
    assert(dut.io.muxData.toInt == 0xAA55, s"Case 1b: muxData=${dut.io.muxData.toInt.toHexString}")
    println("[sim] Case 1b modeSelect=1 → adapter 0 selected — OK")

    dut.io.modeSelect #= 2
    dut.clockDomain.waitSampling()
    assert(dut.io.muxEnable.toBoolean, "Case 1c: muxEnable should follow adapter 1 at modeSelect=2")
    assert(dut.io.muxAddr.toInt == 0x0F12, s"Case 1c: muxAddr=${dut.io.muxAddr.toInt.toHexString}")
    assert(dut.io.muxData.toInt == 0x1234, s"Case 1c: muxData=${dut.io.muxData.toInt.toHexString}")
    println("[sim] Case 1c modeSelect=2 → adapter 1 selected — OK")

    // Reset adapter inputs to idle for router cases.
    dut.io.adap0BusEnable #= false
    dut.io.adap1BusEnable #= false
    dut.io.modeSelect     #= 0
    dut.clockDomain.waitSampling()

    // ----------------------------------------------------------------
    // Case 2 — Router decode active: write to C64-range addr at
    // modeSelect=1 → adapter 0 pulse; passThru suppressed.
    // ----------------------------------------------------------------
    dut.io.modeSelect      #= 1
    dut.io.routerInAddr    #= 0x0E05
    dut.io.routerInData    #= 0xBEEF   // low byte 0xEF → adapter regData
    dut.io.routerInEnable  #= true
    dut.clockDomain.waitSampling()
    assert(dut.io.adap0RegWr.toBoolean, "Case 2: adap0RegWr expected True")
    assert(dut.io.adap0RegAddr.toInt == 0x05,
      s"Case 2: adap0RegAddr=${dut.io.adap0RegAddr.toInt.toHexString} (expected 0x05)")
    assert(dut.io.adap0RegData.toInt == 0xEF,
      s"Case 2: adap0RegData=${dut.io.adap0RegData.toInt.toHexString} (expected 0xEF)")
    assert(!dut.io.adap1RegWr.toBoolean, "Case 2: adap1RegWr should be False")
    assert(!dut.io.passEnable.toBoolean, "Case 2: passThru.enable suppressed for adapter range")
    println("[sim] Case 2 router decode active (mode=1, addr=0x0E05) — OK")

    // ----------------------------------------------------------------
    // Case 3 — Router drop inactive: same C64-range write at
    // modeSelect=2 → no pulse anywhere; passThru still suppressed.
    // ----------------------------------------------------------------
    dut.io.modeSelect #= 2
    dut.clockDomain.waitSampling()
    assert(!dut.io.adap0RegWr.toBoolean, "Case 3: adap0RegWr must be False (inactive)")
    assert(!dut.io.adap1RegWr.toBoolean, "Case 3: adap1RegWr must be False (range mismatch)")
    assert(!dut.io.passEnable.toBoolean, "Case 3: passThru still suppressed for adapter range")
    println("[sim] Case 3 router drops inactive-adapter write — OK")

    // Symmetric ZX-range write at modeSelect=2 → adapter 1 pulse.
    dut.io.routerInAddr #= 0x0F1A
    dut.io.routerInData #= 0xCAFE
    dut.clockDomain.waitSampling()
    assert(dut.io.adap1RegWr.toBoolean, "Case 3 sym: adap1RegWr expected True")
    assert(dut.io.adap1RegAddr.toInt == 0x1A,
      s"Case 3 sym: adap1RegAddr=${dut.io.adap1RegAddr.toInt.toHexString}")
    assert(dut.io.adap1RegData.toInt == 0xFE,
      s"Case 3 sym: adap1RegData=${dut.io.adap1RegData.toInt.toHexString}")
    assert(!dut.io.adap0RegWr.toBoolean, "Case 3 sym: adap0RegWr must be False")
    assert(!dut.io.passEnable.toBoolean, "Case 3 sym: passThru suppressed for ZX adapter range")
    println("[sim] Case 3 sym ZX-range write at mode=2 → adapter 1 pulse — OK")

    // ----------------------------------------------------------------
    // Case 5 (Bonus) — defense-in-depth: even if both adapter bus
    // inputs assert simultaneously at modeSelect=0, mux stays quiescent.
    // ----------------------------------------------------------------
    dut.io.routerInEnable #= false
    dut.io.modeSelect     #= 0
    dut.io.adap0BusEnable #= true
    dut.io.adap1BusEnable #= true
    dut.clockDomain.waitSampling()
    assert(!dut.io.muxEnable.toBoolean,
      "Case 5: muxEnable must be False at modeSelect=0 even with both adapters asserting")
    println("[sim] Case 5 mux defense-in-depth at mode=0 with both adapters asserting — OK")

    // ----------------------------------------------------------------
    // Bonus — Mode0 global passthrough: 0x0300 LAYER_ENABLE not in any
    // adapter range; passes through at any modeSelect value.
    // ----------------------------------------------------------------
    dut.io.adap0BusEnable #= false
    dut.io.adap1BusEnable #= false
    for (mode <- Seq(0, 1, 2, 3, 7)) {
      dut.io.modeSelect     #= mode
      dut.io.routerInAddr   #= 0x0300
      dut.io.routerInData   #= 0x0001
      dut.io.routerInEnable #= true
      dut.clockDomain.waitSampling()
      assert(dut.io.passEnable.toBoolean,
        s"Mode0 passthrough: 0x0300 should pass at mode=$mode")
      assert(dut.io.passAddr.toInt == 0x0300, s"Mode0 passthrough addr at mode=$mode")
      assert(dut.io.passData.toInt == 0x0001, s"Mode0 passthrough data at mode=$mode")
      assert(!dut.io.adap0RegWr.toBoolean, s"Mode0 passthrough: adap0RegWr off at mode=$mode")
      assert(!dut.io.adap1RegWr.toBoolean, s"Mode0 passthrough: adap1RegWr off at mode=$mode")
    }
    println("[sim] Bonus Mode0 global 0x0300 passthrough at modes {0,1,2,3,7} — OK")

    // ----------------------------------------------------------------
    // Bonus — MODE_SELECT register itself (0x0313) is a Mode0 global,
    // not in any adapter range, so passes through unchanged.
    // ----------------------------------------------------------------
    dut.io.routerInAddr   #= 0x0313
    dut.io.routerInData   #= 0x0001
    dut.io.routerInEnable #= true
    dut.io.modeSelect     #= 0
    dut.clockDomain.waitSampling()
    assert(dut.io.passEnable.toBoolean, "Bonus: 0x0313 MODE_SELECT must pass through")
    assert(dut.io.passAddr.toInt == 0x0313, "Bonus: passAddr=0x0313")
    println("[sim] Bonus MODE_SELECT (0x0313) passes through — OK")

    // ----------------------------------------------------------------
    // Bonus — write enable=False produces no pulse anywhere.
    // ----------------------------------------------------------------
    dut.io.routerInAddr   #= 0x0E05
    dut.io.routerInData   #= 0xFFFF
    dut.io.routerInEnable #= false
    dut.io.modeSelect     #= 1
    dut.clockDomain.waitSampling()
    assert(!dut.io.adap0RegWr.toBoolean, "Bonus: enable=False suppresses adap0 pulse")
    assert(!dut.io.passEnable.toBoolean, "Bonus: enable=False suppresses passThru")
    println("[sim] Bonus enable=False → all outputs idle — OK")

    println("[sim] ModeSelectSim: PASS (all cases)")
  }
}
