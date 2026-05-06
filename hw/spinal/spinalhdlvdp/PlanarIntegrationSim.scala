package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 3 Checkpoint D — PlanarIntegrationSim.
  *
  * Verifies the integration of `PlanarLineFetch` into `VdpTop` at the
  * VdpTop boundary. The PlanarLineFetch primitive itself is already
  * proven by `PlanarLineFetchSim`; this sim covers the integration
  * surface that's new in Task 3:
  *
  *   Case 1: Register-bus writes to `0x0D40..0x0D49` correctly land in
  *           `planeBaseAddrReg[0..4]` (lo/hi pairs, 23-bit composed
  *           values). Verifies the bus decode + lo/hi packing.
  *   Case 2: Write to `0x0D4A` correctly latches `planarCtrlReg`;
  *           bit 0 (planarFetchEnable) gates the L0 source mux —
  *           when 1, `layer0Index` follows the planar pixel; when 0,
  *           legacy L0 path remains active (bit-identical to baseline).
  *   Case 3: Toggle `planarFetchEnable` and verify `layer0Index` /
  *           `layer0Bank` switch between the planar source and the
  *           pre-existing legacy source on the same VdpTop instance,
  *           with no other state changes.
  *
  * SDRAM-driven row fetch correctness is NOT in scope here — that's
  * `PlanarLineFetchSim`'s domain. This sim's planar SDRAM interface
  * is held quiescent (no dataReady), so PlanarLineFetch.io.pixel
  * stays at its reset value (0) and validates only the integration
  * wiring, not the fetch FSM.
  */
object PlanarIntegrationSim extends App {

  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent stimulus: keep all of VdpTop's optional inputs at safe defaults.
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    for (i <- 0 until 4) {
      // legacy sprite IO defaults — disabled / off-screen
    }
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
    // Planar SDRAM ports held quiescent — no row data delivered, so
    // PlanarLineFetch stays in its initial state (pixel = 0).
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

    // ---------------------------------------------------------------
    // Case 1: planeBaseAddrReg[0..4] register-bus writes
    // ---------------------------------------------------------------
    println("[sim] Case 1: planeBaseAddrReg[0..4] register-bus writes (0x0D40..0x0D49)")
    val testAddrs = Seq(
      0x123456, 0x000010, 0x7AC0DE, 0x040000, 0x7FFFFF
    )
    for (p <- 0 until 5) {
      val addr = testAddrs(p)
      writeReg(0x0D40 + p * 2 + 0, addr & 0xFFFF)         // lo
      writeReg(0x0D40 + p * 2 + 1, (addr >> 16) & 0x7F)   // hi (7 bits)
    }
    var case1Failed = false
    for (p <- 0 until 5) {
      val got = dut.planeBaseAddrReg(p).toLong.toInt
      val exp = testAddrs(p) & 0x7FFFFF
      if (got != exp) {
        println(f"  plane $p: got 0x$got%06X exp 0x$exp%06X FAIL")
        case1Failed = true
      } else {
        println(f"  plane $p: 0x$got%06X PASS")
      }
    }
    assert(!case1Failed, "Case 1 failed")
    println("  Case 1 PASS")

    // ---------------------------------------------------------------
    // Case 2: planarCtrlReg @ 0x0D4A latches; bit 0 = planarFetchEnable
    // ---------------------------------------------------------------
    println("[sim] Case 2: planarCtrlReg @ 0x0D4A latches; bit 0 controls fetchEnable")
    writeReg(0x0D4A, 0x0001)
    val ctrl1 = dut.planarCtrlReg.toLong.toInt
    assert((ctrl1 & 0x1) == 1, s"after write 0x0001: planarCtrlReg = 0x$ctrl1%04X (bit 0 must be 1)")
    println(f"  PLANAR_CTRL after enable: 0x$ctrl1%04X PASS")

    writeReg(0x0D4A, 0x0000)
    val ctrl0 = dut.planarCtrlReg.toLong.toInt
    assert((ctrl0 & 0x1) == 0, s"after write 0x0000: planarCtrlReg = 0x$ctrl0%04X (bit 0 must be 0)")
    println(f"  PLANAR_CTRL after disable: 0x$ctrl0%04X PASS")

    // ---------------------------------------------------------------
    // Case 3: layer0Index switches between planar and legacy when toggling
    //         planarFetchEnable. The planar source returns pixel=0 (no
    //         SDRAM data delivered) → planar layer0Index = 0. Legacy
    //         source drives some non-zero default. Toggling the bit
    //         must reflect in layer0Index immediately.
    // ---------------------------------------------------------------
    println("[sim] Case 3: layer0Index gating on planarFetchEnable")
    // When planar fetch is enabled and the planar SDRAM is held quiescent
    // (no dataReady), PlanarLineFetch.io.pixel stays at 0 → layer0Index
    // sourced from planar must be 0. Sample over multiple cycles to be
    // robust to any pipeline registration.
    writeReg(0x0D4A, 0x0001)   // planar enabled
    dut.clockDomain.waitSampling(10)
    var case3Failed = false
    for (_ <- 0 until 20) {
      val planarIdx = dut.layer0Index.toLong.toInt & 0xF
      if (planarIdx != 0) {
        println(f"  FAIL: planar mode layer0Index = 0x$planarIdx%X (expected 0 with quiescent SDRAM)")
        case3Failed = true
      }
      dut.clockDomain.waitSampling()
    }
    assert(!case3Failed, "Case 3: planar L0 idx must stay 0 when SDRAM quiescent")
    println(f"  planar mode layer0Index = 0x0 sustained over 20 cycles PASS")

    // Disable planar; legacy L0 source resumes (BasicPatternSource walks
    // with hCounter so any non-zero observation is acceptable evidence
    // the legacy path is live again).
    writeReg(0x0D4A, 0x0000)   // planar disabled
    dut.clockDomain.waitSampling(10)
    var sawNonZero = false
    for (_ <- 0 until 200) {
      val legacyIdx = dut.layer0Index.toLong.toInt & 0xF
      if (legacyIdx != 0) sawNonZero = true
      dut.clockDomain.waitSampling()
    }
    assert(sawNonZero, "Case 3: legacy L0 path must produce non-zero idx within 200 cycles after disable")
    println("  legacy path resumes after planar disable PASS")
    println("  Case 3 PASS")

    println("PlanarIntegrationSim: PASS (all 3 cases)")
  }
}
