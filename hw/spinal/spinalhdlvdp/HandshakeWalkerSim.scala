package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** HandshakeWalkerSim — verifies the handshake continuity walker: D0 advances the
  * one-hot drive through D2..D7 (data walk), the next advance enters CTRL echo where
  * D2..D5 mirror CS/WR/RD/DC, D1 bumps the pass counter, both-high resyncs. (P21.) */
object HandshakeWalkerSim extends App {
  Config.sim.compile(HandshakeWalker()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.d0 #= false; dut.io.d1 #= false
    dut.io.cs #= false; dut.io.wr #= false; dut.io.rd #= false; dut.io.dc #= false
    dut.clockDomain.waitSampling(5)

    def pulse(sig: spinal.core.Bool): Unit = {
      sig #= true;  dut.clockDomain.waitSampling(4)
      sig #= false; dut.clockDomain.waitSampling(4)
    }

    assert(dut.io.testIdx.toInt == 1, "should start idle (idx=1)")
    assert((dut.io.dOut.toInt & 0xFC) == 0, "idle drives nothing on D2..D7")

    // --- DATA walk: D2..D7 one-hot ---
    for (pin <- 2 to 7) {
      pulse(dut.io.d0)
      val idx = dut.io.testIdx.toInt
      val dv  = dut.io.dOut.toInt
      println(f"[sim] DATA advance -> idx=$idx dOut=0x$dv%02X")
      assert(idx == pin, s"idx $idx != $pin")
      assert(dv == (1 << pin), f"dOut 0x$dv%02X not one-hot at D$pin")
      pulse(dut.io.d1)
    }
    assert(dut.io.passCount.toInt == 6, s"passCount ${dut.io.passCount.toInt} != 6")

    // --- next advance enters CTRL echo (idx 8): D2..D5 mirror CS/WR/RD/DC ---
    pulse(dut.io.d0)
    assert(dut.io.testIdx.toInt == 8, s"should be CTRL echo (idx=8), got ${dut.io.testIdx.toInt}")
    // drive each control one at a time, expect only its echo bit set
    val ctrls = Seq(("CS", dut.io.cs, 2), ("WR", dut.io.wr, 3), ("RD", dut.io.rd, 4), ("DC", dut.io.dc, 5))
    for ((name, sig, bit) <- ctrls) {
      sig #= true; dut.clockDomain.waitSampling(4)
      val dv = dut.io.dOut.toInt
      println(f"[sim] CTRL $name high -> dOut=0x$dv%02X (expect bit $bit)")
      assert(dv == (1 << bit), f"$name echo wrong: dOut=0x$dv%02X expected only D$bit")
      sig #= false; dut.clockDomain.waitSampling(4)
    }

    // --- next advance wraps CTRL(8) -> D2 ---
    pulse(dut.io.d0)
    assert(dut.io.testIdx.toInt == 2, s"wrap failed: idx=${dut.io.testIdx.toInt}")

    // --- resync: both high -> idle + pass cleared ---
    dut.io.d0 #= true; dut.io.d1 #= true; dut.clockDomain.waitSampling(6)
    dut.io.d0 #= false; dut.io.d1 #= false; dut.clockDomain.waitSampling(4)
    assert(dut.io.testIdx.toInt == 1, s"resync failed: idx=${dut.io.testIdx.toInt}")
    assert(dut.io.passCount.toInt == 0, "resync should clear passCount")
    println("HandshakeWalkerSim: PASS — D2..D7 walk + CS/WR/RD/DC echo + wrap + resync all verified")
  }
}
