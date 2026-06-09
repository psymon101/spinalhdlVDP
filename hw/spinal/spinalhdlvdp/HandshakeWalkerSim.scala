package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** HandshakeWalkerSim — verifies the handshake continuity walker: each D0 rising
  * edge advances the one-hot drive through D2..D7 (wraps 7->2); D1 bumps the pass
  * counter; D0&D1 both-high resyncs to idle. (Lane P21 side-lane.) */
object HandshakeWalkerSim extends App {
  Config.sim.compile(HandshakeWalker()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.d0 #= false; dut.io.d1 #= false
    dut.clockDomain.waitSampling(5)

    def pulse(sig: spinal.core.Bool): Unit = {
      sig #= true;  dut.clockDomain.waitSampling(4)
      sig #= false; dut.clockDomain.waitSampling(4)
    }
    def oneHotBit(d: Int): Int = if (d == 0) -1 else Integer.numberOfTrailingZeros(d)

    assert(dut.io.testIdx.toInt == 1, "should start idle (idx=1)")
    assert((dut.io.dOut.toInt & 0xFC) == 0, "idle drives nothing on D2..D7")

    // walk D2..D7
    val expected = Seq(2, 3, 4, 5, 6, 7)
    for (pin <- expected) {
      pulse(dut.io.d0)
      val idx = dut.io.testIdx.toInt
      val dv  = dut.io.dOut.toInt
      println(f"[sim] advance -> idx=$idx dOut=0x$dv%02X (one-hot bit ${oneHotBit(dv)})")
      assert(idx == pin, s"idx $idx != expected $pin")
      assert(dv == (1 << pin), f"dOut 0x$dv%02X not one-hot at D$pin")
      pulse(dut.io.d1)   // ack -> pass++
    }
    assert(dut.io.passCount.toInt == 6, s"passCount ${dut.io.passCount.toInt} != 6")

    // one more advance wraps 7 -> 2
    pulse(dut.io.d0)
    assert(dut.io.testIdx.toInt == 2, s"wrap failed: idx=${dut.io.testIdx.toInt}")
    assert(dut.io.dOut.toInt == (1 << 2), "wrap should drive D2")
    println(s"[sim] wrap OK -> idx=${dut.io.testIdx.toInt}")

    // resync: both high -> idle + pass cleared
    dut.io.d0 #= true; dut.io.d1 #= true; dut.clockDomain.waitSampling(6)
    dut.io.d0 #= false; dut.io.d1 #= false; dut.clockDomain.waitSampling(4)
    assert(dut.io.testIdx.toInt == 1, s"resync failed: idx=${dut.io.testIdx.toInt}")
    assert(dut.io.passCount.toInt == 0, s"resync should clear passCount")
    println("HandshakeWalkerSim: PASS — D0 walks D2..D7 one-hot (wraps), D1 counts passes, both-high resyncs")
  }
}
