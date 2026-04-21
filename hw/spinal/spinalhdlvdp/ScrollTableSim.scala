package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 31 Checkpoint A sim — ScrollTable primitive correctness. */
object ScrollTableSim extends App {
  Config.sim.compile(ScrollTable(entries = 128, offsetWidth = 10)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.wrAddr #= 0
    dut.io.wrData #= 0
    dut.io.wr     #= false
    dut.io.rdAddr #= 0
    dut.clockDomain.waitSampling(3)

    // === Case 1: default zeros ===
    for (i <- 0 until 128) {
      dut.io.rdAddr #= i
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.rdData.toInt == 0, s"case1: entry $i default must be 0, got ${dut.io.rdData.toInt}")
    }
    println("[sim] case1 default-zero readout (128 entries) — OK")

    // === Case 2: write+read single entry ===
    def bwrite(a: Int, v: Int): Unit = {
      dut.io.wrAddr #= a; dut.io.wrData #= v; dut.io.wr #= true
      dut.clockDomain.waitSampling()
      dut.io.wr #= false; dut.clockDomain.waitSampling()
    }
    bwrite(0, 0x123)
    dut.io.rdAddr #= 0
    dut.clockDomain.waitSampling(); sleep(1)
    assert(dut.io.rdData.toInt == 0x123, f"case2: expected 0x123, got 0x${dut.io.rdData.toInt}%X")
    println("[sim] case2 single write/read — OK")

    // === Case 3: multi-entry pattern ===
    val pat = Seq((5, 0x100), (10, 0x200), (50, 0x3FF), (127, 0x0AA))
    for ((a, v) <- pat) bwrite(a, v)
    for ((a, v) <- pat) {
      dut.io.rdAddr #= a
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.rdData.toInt == v, f"case3: addr $a exp 0x$v%X got 0x${dut.io.rdData.toInt}%X")
    }
    println("[sim] case3 multi-entry pattern — OK")

    // === Case 4: read previously-unwritten entry stays zero ===
    dut.io.rdAddr #= 99
    dut.clockDomain.waitSampling(); sleep(1)
    assert(dut.io.rdData.toInt == 0, s"case4: addr 99 should still be 0")
    println("[sim] case4 unwritten entries remain zero — OK")

    println("[sim] ScrollTableSim: PASS")
  }
}
