package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** TopTang20kI80ContinuitySim — functional test of the i80 pin-continuity exerciser
  * (lane P21 side-lane, #12039). Verifies (1) O_led[0..3] mirror the four control
  * inputs exactly as BronzeGate's sketch will drive them (each HIGH individually),
  * and (2) IO_i80_d cycles the full walking-1 0x01..0x80. Uses stepShift=2 so the
  * 8-step walk completes in a few dozen cycles (bench bitstream uses 19). */
object TopTang20kI80ContinuitySim extends App {
  Config.sim.compile(TopTang20kI80Continuity(stepShift = 2)).doSim { dut =>
    dut.I_i80_cs #= true; dut.I_i80_wr #= true; dut.I_i80_rd #= true; dut.I_i80_dc #= false
    dut.I_clk #= false
    sleep(1)

    def tick(): Unit = { dut.I_clk #= true; sleep(1); dut.I_clk #= false; sleep(1) }

    // (1) LED mapping: O_led[0..3] are direct from the control inputs. Drive each
    // control HIGH individually (others LOW) — exactly BronzeGate's CP-B sequence.
    def checkLed(cs: Boolean, wr: Boolean, rd: Boolean, dc: Boolean): Unit = {
      dut.I_i80_cs #= cs; dut.I_i80_wr #= wr; dut.I_i80_rd #= rd; dut.I_i80_dc #= dc
      sleep(1)
      val l = dut.O_led.toInt
      val bits = Seq(cs, wr, rd, dc).map(if (_) 1 else 0)
      for (i <- 0 until 4) assert(((l >> i) & 1) == bits(i), s"O_led[$i] != control($i) for ${(cs,wr,rd,dc)} (led=0x${l.toHexString})")
    }
    checkLed(true,  false, false, false)
    checkLed(false, true,  false, false)
    checkLed(false, false, true,  false)
    checkLed(false, false, false, true)
    println("[sim] LED mapping OK: O_led[0..3] follow CS/WR/RD/DC (driven-high pin -> its LED bit high)")

    // (2) walking-1 on D0-7: collect IO_i80_d over enough ticks for >=2 full walks.
    val seen = scala.collection.mutable.LinkedHashSet[Int]()
    for (_ <- 0 until 80) { tick(); seen += dut.IO_i80_d.toInt }
    println(s"[sim] D0-7 values seen: ${seen.toSeq.sorted.map(v => f"0x$v%02X").mkString(",")}")
    val pow2 = Set(1, 2, 4, 8, 16, 32, 64, 128)
    assert(seen.forall(pow2.contains), s"walking-1 produced a non-single-bit value: $seen")
    assert(seen == pow2, s"walking-1 did not cover all of D0..D7: $seen")
    println("TopTang20kI80ContinuitySim: PASS — walking-1 covers D0..D7 (0x01..0x80); LEDs mirror controls")
  }
}
