package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 40 — C64Adapter unit sim.
  *
  * Covers the 5-case validation matrix from TASK_40_FIRST_PLATFORM_ADAPTER.md §4.1:
  *
  *   1. Write $D012 = 100 → rasterTriggerLine = 100 (low 8 bits; $D011[7] = 0).
  *   2. Write $D015 = 0x03 → sprite0/1 enable bits asserted.
  *   3. Write $D000 = 200, $D001 = 150 → sprite0 X=200, Y=150 (low 8 bits;
  *      $D010 bit 0 = 0 leaves MSB clear).
  *   4. Write $D011 with DEN=0 → LAYER_ENABLE bus write asserted with bit 0
  *      clear; write with DEN=1 → LAYER_ENABLE bus write asserted with bit 0 set.
  *   5. Two-bar split scaffold: write $D012 = 120 then write $D019 = 0x01 →
  *      rasterTriggerClear pulse observed exactly on the cycle of the
  *      acknowledge write (models the "swap on raster trigger" animator
  *      sequence the scenario uses).
  */
object C64AdapterSim extends App {
  Config.sim.compile(C64Adapter()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.regAddr #= 0
    dut.io.regData #= 0
    dut.io.regWr   #= false
    dut.clockDomain.waitSampling(3)

    def c64Write(addr: Int, data: Int): Unit = {
      dut.io.regAddr #= addr & 0xFF
      dut.io.regData #= data & 0xFF
      dut.io.regWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.regWr   #= false
      dut.io.regData #= 0
    }

    // --- Case 1: $D012 = 100 ---
    c64Write(0x12, 100)
    dut.clockDomain.waitSampling()
    assert(dut.io.rasterTriggerLine.toInt == 100,
      s"Case 1: expected rasterTriggerLine=100, got ${dut.io.rasterTriggerLine.toInt}")
    println("[sim] Case 1 $D012=100 → rasterTriggerLine=100 — OK")

    // --- Case 2: $D015 = 0x03 ---
    c64Write(0x15, 0x03)
    dut.clockDomain.waitSampling()
    assert(dut.io.sprite0Enabled.toBoolean,
      "Case 2: expected sprite0Enabled=true")
    assert(dut.io.sprite1Enabled.toBoolean,
      "Case 2: expected sprite1Enabled=true")
    println("[sim] Case 2 $D015=0x03 → sprite0/1 enable asserted — OK")

    // --- Case 3: $D000=200, $D001=150 ---
    c64Write(0x00, 200)
    c64Write(0x01, 150)
    dut.clockDomain.waitSampling()
    assert(dut.io.sprite0X.toInt == 200,
      s"Case 3: expected sprite0X=200, got ${dut.io.sprite0X.toInt}")
    assert(dut.io.sprite0Y.toInt == 150,
      s"Case 3: expected sprite0Y=150, got ${dut.io.sprite0Y.toInt}")
    println("[sim] Case 3 $D000=200, $D001=150 → sprite0 X=200, Y=150 — OK")

    // --- Case 4: $D011 DEN bit toggles ---
    // Sample bus output via an onSamplings hook so we see the one-cycle
    // emit pulse deterministically.
    val busRec = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    dut.clockDomain.onSamplings {
      if (dut.io.busWr.toBoolean)
        busRec += ((dut.io.busAddr.toInt, dut.io.busData.toInt))
    }

    // DEN=0 (bit 4 of $D011 clear): LAYER_ENABLE bit 0 should be 0.
    c64Write(0x11, 0x00)
    dut.clockDomain.waitSampling(3)
    assert(busRec.nonEmpty, "Case 4: expected a bus write after $D011 clear-DEN")
    val (addr4a, data4a) = busRec.last
    assert(addr4a == 0x0300,
      s"Case 4a: expected addr 0x0300, got 0x${addr4a.toHexString}")
    assert((data4a & 0x1) == 0, s"Case 4a: expected DEN/L0=0, got data=0x${data4a.toHexString}")
    assert((data4a & 0x4) != 0, s"Case 4a: expected sprite bit (bit2) set, got 0x${data4a.toHexString}")

    // DEN=1 (bit 4 set): LAYER_ENABLE bit 0 should be 1.
    c64Write(0x11, 0x10)
    dut.clockDomain.waitSampling(3)
    val (addr4b, data4b) = busRec.last
    assert(addr4b == 0x0300,
      s"Case 4b: expected addr 0x0300, got 0x${addr4b.toHexString}")
    assert((data4b & 0x1) == 1,
      s"Case 4b: expected DEN/L0=1, got 0x${data4b.toHexString}")
    assert((data4b & 0x4) != 0,
      s"Case 4b: expected sprite bit set, got 0x${data4b.toHexString}")
    println("[sim] Case 4 $D011 DEN toggle → LAYER_ENABLE bus writes (0x0300) — OK")

    // --- Case 5: raster trigger ack pulse on $D019 write ---
    c64Write(0x12, 120)
    dut.clockDomain.waitSampling(2)
    assert(dut.io.rasterTriggerLine.toInt == 120, "Case 5 setup: line must track 120")

    // Observe rasterTriggerClear during the write cycle, low before/after.
    assert(!dut.io.rasterTriggerClear.toBoolean,
      "Case 5: clear must be low before ack write")
    dut.io.regAddr #= 0x19
    dut.io.regData #= 0x01
    dut.io.regWr   #= true
    dut.clockDomain.waitSampling()       // deliver the write; clear asserts combinationally
    // Sample the clear that was observed DURING the write cycle via the
    // onSamplings hook. We instead check it directly AFTER the sample,
    // since rasterTriggerClear is combinational from (regWr, regAddr, regData(0)).
    // To deterministically capture the pulse we sample BEFORE deasserting regWr:
    // reassert the same inputs and inspect live.
    dut.io.regAddr #= 0x19
    dut.io.regData #= 0x01
    dut.io.regWr   #= true
    sleep(1)
    assert(dut.io.rasterTriggerClear.toBoolean,
      "Case 5: expected rasterTriggerClear=true during $D019 ack write with bit0 set")
    dut.io.regWr #= false
    dut.clockDomain.waitSampling()
    assert(!dut.io.rasterTriggerClear.toBoolean,
      "Case 5: clear must deassert after write ends")
    println("[sim] Case 5 $D019 ack → rasterTriggerClear one-cycle pulse — OK")

    println("[sim] C64AdapterSim: PASS")
  }
}
