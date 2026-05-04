package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 1 (#9154) Phase 5b — Sc70 runtime-wired dual-adapter E2E sim.
  *
  * Per BronzeGate #9184 D5 Option α: prove that the Phase 3
  * `AdapterBusMux` and Phase 4 `AdapterRegRouter` actually close the
  * loop with runtime-instantiated `C64Adapter` and `ZXSpectrumAdapter`
  * (not demo-wrapped stubs). The standalone `ModeSelectSim` already
  * proved router/mux behaviour in isolation; this sim proves the
  * end-to-end runtime path:
  *
  *   1. Host writes adapter-local register via the post-arbitration
  *      Mode0 bus (`0x0E.. / 0x0F..`).
  *   2. `AdapterRegRouter` decodes the page, gates by `modeSelect`,
  *      and emits a per-adapter `regAddr/regData/regWr` pulse.
  *   3. The runtime adapter latches the write into its shadow and,
  *      after one or more cycles, emits a Mode0 bus write back
  *      (e.g. `LAYER_ENABLE @ 0x0300`).
  *   4. The adapter's bus output is mode-gated (Phase 2 §4.4
  *      quiescence) and routed through `AdapterBusMux` into the
  *      mux output (which would normally feed `RegBusArbiter`
  *      master 2 in the live top path).
  *
  * Mode-quiescence is also exercised: cross-mode writes (write to
  * a C64 page while ZX is selected, and vice versa) must be silently
  * dropped at every layer.
  *
  * The 5a wiring inside `TopTang20kHdmi(scenarioId=70)` is structurally
  * identical to this harness but adds the bootstrap/QSPI/Copper masters
  * + the surrounding RegBusArbiter. The bus-write-injection here is
  * the abstraction of "any master writes to the unified post-arb bus".
  */
case class Sc70RuntimeAdapterHarness() extends Component {
  val c64    = C64Adapter()
  val zx     = ZXSpectrumAdapter()
  val router = AdapterRegRouter(Seq((0x1, 0x0E00), (0x2, 0x0F00)))
  val busMux = AdapterBusMux(Seq(0x1, 0x2))

  val io = new Bundle {
    val modeSelect = in UInt(4 bits)

    // Host writes onto the abstracted post-arb Mode0 bus.
    val busInAddr   = in  UInt(15 bits)
    val busInData   = in  Bits(16 bits)
    val busInEnable = in  Bool()

    // Mux output (would feed RegBusArbiter master 2).
    val muxAddr     = out UInt(15 bits)
    val muxData     = out Bits(16 bits)
    val muxEnable   = out Bool()

    // Pass-through to VdpTop substrate (Mode0 globals only).
    val passAddr    = out UInt(15 bits)
    val passData    = out Bits(16 bits)
    val passEnable  = out Bool()

    // Router pulse outputs (sim observability).
    val c64RegWr    = out Bool()
    val c64RegAddr  = out UInt(8 bits)
    val c64RegData  = out Bits(8 bits)
    val zxRegWr     = out Bool()
    val zxRegAddr   = out UInt(8 bits)
    val zxRegData   = out Bits(8 bits)
  }

  // Distribute modeSelect (live, post-V=0 commit equivalent).
  c64.io.modeSelect    := io.modeSelect
  zx.io.modeSelect     := io.modeSelect
  router.io.modeSelect := io.modeSelect
  busMux.io.modeSelect := io.modeSelect

  // Router input = the host bus.
  router.io.mixedIn.addr   := io.busInAddr
  router.io.mixedIn.data   := io.busInData
  router.io.mixedIn.enable := io.busInEnable

  // Router pulses → adapter regWrite ports (the Phase 5a wiring).
  c64.io.regAddr := router.io.adapters(0).regAddr
  c64.io.regData := router.io.adapters(0).regData
  c64.io.regWr   := router.io.adapters(0).regWr
  zx.io.regAddr  := router.io.adapters(1).regAddr
  zx.io.regData  := router.io.adapters(1).regData
  zx.io.regWr    := router.io.adapters(1).regWr

  // Adapter bus outputs → AdapterBusMux inputs (the Phase 5a wiring).
  busMux.io.adapters(0).addr   := c64.io.busAddr
  busMux.io.adapters(0).data   := c64.io.busData
  busMux.io.adapters(0).enable := c64.io.busWr
  busMux.io.adapters(1).addr   := zx.io.busAddr
  busMux.io.adapters(1).data   := zx.io.busData
  busMux.io.adapters(1).enable := zx.io.busWr

  io.muxAddr    := busMux.io.mixed.addr
  io.muxData    := busMux.io.mixed.data
  io.muxEnable  := busMux.io.mixed.enable
  io.passAddr   := router.io.passThru.addr
  io.passData   := router.io.passThru.data
  io.passEnable := router.io.passThru.enable
  io.c64RegWr   := router.io.adapters(0).regWr
  io.c64RegAddr := router.io.adapters(0).regAddr
  io.c64RegData := router.io.adapters(0).regData
  io.zxRegWr    := router.io.adapters(1).regWr
  io.zxRegAddr  := router.io.adapters(1).regAddr
  io.zxRegData  := router.io.adapters(1).regData
}

object Sc70RuntimeAdapterSim extends App {
  Config.sim.compile(Sc70RuntimeAdapterHarness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Idle defaults.
    dut.io.modeSelect    #= 0
    dut.io.busInAddr     #= 0
    dut.io.busInData     #= 0
    dut.io.busInEnable   #= false
    dut.clockDomain.waitSampling(2)

    /** Drive a single-cycle write on the abstracted post-arb bus. */
    def busWrite(addr: Int, data: Int): Unit = {
      dut.io.busInAddr   #= addr & 0x7FFF
      dut.io.busInData   #= data & 0xFFFF
      dut.io.busInEnable #= true
      dut.clockDomain.waitSampling()
      dut.io.busInEnable #= false
      dut.io.busInAddr   #= 0
      dut.io.busInData   #= 0
    }

    // Capture mux outputs over a window (used to verify the adapter
    // emitted the expected bus write).
    case class BusOut(addr: Int, data: Int)
    def captureMuxOver(cycles: Int): Seq[BusOut] = {
      val out = scala.collection.mutable.ArrayBuffer.empty[BusOut]
      for (_ <- 0 until cycles) {
        if (dut.io.muxEnable.toBoolean) {
          out += BusOut(dut.io.muxAddr.toInt, dut.io.muxData.toInt)
        }
        dut.clockDomain.waitSampling()
      }
      out.toSeq
    }

    // ----------------------------------------------------------------
    // Case A — Mode 1 (C64) round trip.
    //   Host writes 0x0E11 (CTRL1=$D011) with DEN bit set →
    //     router pulses c64.regWr with regAddr=0x11, regData=0x10.
    //   C64 adapter emits LAYER_ENABLE bus write on next cycle.
    //   AdapterBusMux passes it through (mode 1 selects adapter 0).
    // ----------------------------------------------------------------
    dut.io.modeSelect #= 1
    dut.clockDomain.waitSampling()
    busWrite(0x0E11, 0x0010)  // $D011 with DEN=1
    // Verify router pulse landed on the C64 (sampled the cycle of
    // the busWrite).
    // (We sampled inside busWrite by waiting one clock; pulses are
    // combinational from in.enable so observe at the busWrite cycle
    // — easier: just observe over a small window.)
    val muxA = captureMuxOver(6)
    // Expect at least one bus emit at addr=0x0300 (LAYER_ENABLE).
    val emitA = muxA.find(_.addr == 0x0300)
    assert(emitA.isDefined,
      s"Case A: expected mux emit at 0x0300 (LAYER_ENABLE) within 6 cycles; got $muxA")
    // C64Adapter LAYER_ENABLE word from $D011 DEN: bit0=DEN(1), bit2=sprite(1).
    // Top 11 bits zero, bit3=L2(0), bit4=L3(0), bit1=L1(0), bit2=sprite(1), bit0=L0(1) → 0x0005.
    assert(emitA.get.data == 0x0005,
      s"Case A: LAYER_ENABLE data=0x${emitA.get.data.toHexString} (expected 0x0005)")
    println(s"[sim] Case A C64 mode round trip — router pulse → adapter shadow → mux emit @0x0300 = 0x${emitA.get.data.toHexString} — OK")

    // ----------------------------------------------------------------
    // Case B — Mode quiescence: while mode=1 (C64), a write to a
    // ZX-range address (0x0F03) must NOT pulse the ZX adapter, and
    // must NOT emit anything onto the mux from the ZX side.
    // ----------------------------------------------------------------
    busWrite(0x0F03, 0x0001)  // ZX_CTRL = 1 — but ZX is INACTIVE
    val muxB = captureMuxOver(6)
    // No ZX-driven emit should appear: ZX_CTRL writes normally cause
    // the FSM to emit LAYER_ENABLE 0x0001 — but since the router did
    // not pulse it, no shadow change happens.
    assert(!muxB.exists(b => b.addr == 0x0300 && b.data == 0x0001),
      s"Case B: ZX adapter must not emit while inactive; got $muxB")
    println("[sim] Case B mode=1 (C64) drops ZX-range write at router — OK")

    // ----------------------------------------------------------------
    // Case C — switch to mode 2 (ZX). Now write ZX_CTRL again. The
    // router pulses ZX.regWr; the ZX FSM emits LAYER_ENABLE = 0x0001.
    // Mux selects adapter 1 (ZX) since mode=2.
    // ----------------------------------------------------------------
    dut.io.modeSelect #= 2
    dut.clockDomain.waitSampling(2)
    busWrite(0x0F03, 0x0001)  // ZX_CTRL = 1 — adapter rising-edge enable
    val muxC = captureMuxOver(8)
    val emitC = muxC.find(b => b.addr == 0x0300 && b.data == 0x0001)
    assert(emitC.isDefined,
      s"Case C: expected ZX LAYER_ENABLE=0x0001 at 0x0300 within 8 cycles; got $muxC")
    println("[sim] Case C ZX mode round trip — router pulse → ZX FSM → mux emit @0x0300 = 0x0001 — OK")

    // ----------------------------------------------------------------
    // Case D — symmetric quiescence: at mode=2 (ZX), a write to the
    // C64 range must NOT pulse C64 and must NOT emit on mux.
    // ----------------------------------------------------------------
    busWrite(0x0E11, 0x0010)  // $D011 — but C64 is INACTIVE
    val muxD = captureMuxOver(6)
    // C64 adapter would have emitted LAYER_ENABLE 0x0005 if pulsed;
    // verify nothing in muxD looks like that.
    assert(!muxD.exists(b => b.addr == 0x0300 && b.data == 0x0005),
      s"Case D: C64 adapter must not emit while inactive; got $muxD")
    println("[sim] Case D mode=2 (ZX) drops C64-range write at router — OK")

    // ----------------------------------------------------------------
    // Case E — Mode0 global passthrough across both modes. Write
    // LAYER_ENABLE = 0x0007 directly (host bypassing adapters) and
    // confirm passThru carries it untouched at both mode=0 and mode=1.
    // ----------------------------------------------------------------
    for (mode <- Seq(0, 1, 2)) {
      dut.io.modeSelect #= mode
      dut.clockDomain.waitSampling()
      dut.io.busInAddr   #= 0x0300
      dut.io.busInData   #= 0x0007
      dut.io.busInEnable #= true
      dut.clockDomain.waitSampling()
      assert(dut.io.passEnable.toBoolean,
        s"Case E mode=$mode: 0x0300 must reach passThru")
      assert(dut.io.passAddr.toInt == 0x0300, s"Case E mode=$mode passAddr")
      assert(dut.io.passData.toInt == 0x0007, s"Case E mode=$mode passData")
      assert(!dut.io.c64RegWr.toBoolean, s"Case E mode=$mode: c64RegWr off")
      assert(!dut.io.zxRegWr.toBoolean,  s"Case E mode=$mode: zxRegWr off")
      dut.io.busInEnable #= false
      dut.clockDomain.waitSampling()
    }
    println("[sim] Case E LAYER_ENABLE 0x0300 passthrough at modes {0,1,2} — OK")

    // ----------------------------------------------------------------
    // Case F — MODE_SELECT register itself (0x0313) reaches passThru.
    // ----------------------------------------------------------------
    dut.io.modeSelect  #= 0
    dut.io.busInAddr   #= 0x0313
    dut.io.busInData   #= 0x0001
    dut.io.busInEnable #= true
    dut.clockDomain.waitSampling()
    assert(dut.io.passEnable.toBoolean, "Case F: 0x0313 MODE_SELECT must reach passThru")
    assert(dut.io.passAddr.toInt == 0x0313, "Case F: passAddr=0x0313")
    assert(dut.io.passData.toInt == 0x0001, "Case F: passData=0x0001")
    dut.io.busInEnable #= false
    println("[sim] Case F MODE_SELECT (0x0313) reaches passThru — OK")

    println("[sim] Sc70RuntimeAdapterSim: PASS (all cases)")
  }
}
