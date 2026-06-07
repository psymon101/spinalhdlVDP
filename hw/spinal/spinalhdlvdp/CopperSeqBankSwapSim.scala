package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** CopperSeqBankSwapSim — P0 regression for the Copper WRITE_SEQ + bankSwapNow
  * data-loss bug (#11966).
  *
  * BUG: when io.bankSwapNow fired during sSeqData, the FSM did `goto(sFetch)`
  * WITHOUT emitting the in-flight register write, silently dropping the current
  * WRITE_SEQ data word (missing palette/scroll entries on a mid-upload bank
  * swap). FIX (Copper.scala sSeqData): commit the write before re-dispatching;
  * leave pcNext to the top-level bankSwapNow reset (do NOT set pc+1 — that would
  * override the pc:=0 re-dispatch).
  *
  * This sim uploads a 4-word WRITE_SEQ, fires bankSwapNow on the LAST data word,
  * and asserts all 4 words reach the register bus (the bug delivered only 3).
  */
object CopperSeqBankSwapSim extends App {
  Config.sim.compile(Copper()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    def step(n: Int = 1): Unit = for (_ <- 0 until n) dut.clockDomain.waitSampling()

    def SEQ_OP(countM1: Int, baseAddr: Int): Int =
      (2 << 14) | ((countM1 & 0x7) << 11) | (baseAddr & 0x7FF)
    def JUMP(pc: Int): Int = (3 << 14) | (pc & 0x1FF)

    // init every input (avoid X-prop)
    dut.io.hCounter #= 100; dut.io.vCounter #= 100   // non-zero: post-swap WAIT(0) won't match
    dut.io.enabled #= false
    dut.io.progAddr #= 0; dut.io.progData #= 0; dut.io.progWr #= false
    dut.io.bankSwapNow #= false
    dut.io.hdmaCtrlAddr #= 0; dut.io.hdmaData #= 0; dut.io.hdmaWr #= false
    dut.io.triggerLine0 #= 0; dut.io.triggerPixel0 #= 0
    dut.clockDomain.waitSampling(5)

    // Program: WRITE_SEQ of 4 words to base 0x100, then JUMP-self (park).
    val base = 0x100
    val data = Seq(0x1111, 0x2222, 0x3333, 0x4444)
    val prog = Seq(SEQ_OP(3, base)) ++ data ++ Seq(JUMP(5))

    // Upload to the active bank (enabled=false).
    for ((word, i) <- prog.zipWithIndex) {
      dut.io.progAddr #= i; dut.io.progData #= word; dut.io.progWr #= true
      dut.clockDomain.waitSampling()
    }
    dut.io.progWr #= false
    step(2)

    // Run: enable, capture register writes, fire bankSwapNow on the 4th (last)
    // data word — i.e. the cycle after the 3rd write is observed.
    val writes = mutable.ArrayBuffer[(Int, Int)]()
    var swapped = false
    var deassert = false
    var done = false
    dut.io.enabled #= true
    for (_ <- 0 until 50 if !done) {
      dut.clockDomain.waitSampling()
      if (deassert) { dut.io.bankSwapNow #= false; deassert = false }
      if (dut.io.regWr.toBoolean) {
        writes += ((dut.io.regAddr.toInt, dut.io.regData.toInt))
        if (writes.size == 3 && !swapped) {
          dut.io.bankSwapNow #= true     // takes effect next cycle = 4th data word
          swapped = true; deassert = true
        }
      }
      // Stop once the seq's 4th write is captured — the post-swap (uninitialised)
      // inactive bank is out of scope for this regression; park the copper.
      if (swapped && writes.size >= 4) { dut.io.enabled #= false; done = true }
    }

    println(s"[sim] bankSwapNow fired mid-WRITE_SEQ; register writes observed = ${writes.size} (expect 4)")
    writes.zipWithIndex.foreach { case ((a, d), i) => println(f"  write $i: addr=0x$a%03X data=0x$d%04X") }

    val expected = data.zipWithIndex.map { case (d, i) => (base + i, d) }
    var fail = false
    if (writes.size < 4) {
      fail = true
      println(s"  **FAIL** only ${writes.size} writes — the bankSwap-cycle word was DROPPED (#11966 bug)")
    }
    for (i <- 0 until math.min(4, writes.size)) {
      if (writes(i) != expected(i)) {
        fail = true
        println(f"  **FAIL** write $i = (0x${writes(i)._1}%X,0x${writes(i)._2}%X)  exp (0x${expected(i)._1}%X,0x${expected(i)._2}%X)")
      }
    }
    assert(!fail && writes.size >= 4,
      "CopperSeqBankSwapSim: WRITE_SEQ data word lost on bankSwapNow (#11966 regression)")
    println("CopperSeqBankSwapSim: PASS — all 4 WRITE_SEQ words reached the bus despite mid-sequence bankSwapNow")
  }
}
