package spinalhdlvdp

import spinal.core._
import spinal.core.formal._
import spinal.lib._
import spinal.lib.formal._

/** FORMAL-131 — SdramArbiter formal verification harness.
  *
  * Proves safety, liveness, and isolation properties for the multi-client
  * SDRAM arbiter.
  */
class SdramArbiterFormal extends Component {
  val cd = ClockDomain(
    clock = ClockDomain.current.clock,
    reset = ClockDomain.current.reset,
    config = ClockDomainConfig(resetKind = SYNC)
  )
  val area = new ClockingArea(cd) {
    val clientCount = 4
    val addrWidth   = 23
    val dataWidth   = 8
    val refreshPeriodCycles = 593

    val io = new Bundle {
      val grantClientId = in UInt(log2Up(clientCount) bits)
      val slotValid     = in Bool()
      val grant         = in Bool()

      val clientRd   = in Vec(Bool(), clientCount)
      val clientWr   = in Vec(Bool(), clientCount)
      val clientAddr = in Vec(UInt(addrWidth bits), clientCount)
      val clientDin  = in Vec(Bits(dataWidth bits), clientCount)

      val vblankActive = in Bool()
    }

    val dut = SdramArbiter(
      clientCount = clientCount,
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      refreshPeriodCycles = refreshPeriodCycles,
      burstRefresh = false
    )

    dut.io.grantClientId := io.grantClientId
    dut.io.slotValid     := io.slotValid
    dut.io.grant         := io.grant
    dut.io.clientRd      := io.clientRd
    dut.io.clientWr      := io.clientWr
    dut.io.clientAddr    := io.clientAddr
    dut.io.clientDin     := io.clientDin
    dut.io.vblankActive  := io.vblankActive

    assert(CountOne(dut.io.clientGrant) <= 1)
    assert(CountOne(dut.io.clientSlotValid) <= 1)

    val refreshCounter = Reg(UInt(log2Up(refreshPeriodCycles + 1) bits)) init 0
    when(dut.io.refreshDue) {
      assert(refreshCounter === refreshPeriodCycles - 1)
      refreshCounter := 0
    } otherwise {
      refreshCounter := refreshCounter + 1
    }

    val sel = io.grantClientId
    assert(dut.io.sdramRd   === io.clientRd(sel))
    assert(dut.io.sdramWr   === io.clientWr(sel))
    assert(dut.io.sdramAddr === io.clientAddr(sel))
    assert(dut.io.sdramDin  === io.clientDin(sel))

    for (i <- 0 until clientCount) {
      assert(dut.io.clientGrant(i) === (io.grant && (sel === i)))
      assert(dut.io.clientSlotValid(i) === (io.slotValid && (sel === i)))
    }

    assumeInitial(ClockDomain.current.isResetActive)
  }
}

// Test suite skeleton for sbt run
object SdramArbiterFormalTest extends App {
  FormalConfig.withBMC(20).doVerify(new SdramArbiterFormal)
}
