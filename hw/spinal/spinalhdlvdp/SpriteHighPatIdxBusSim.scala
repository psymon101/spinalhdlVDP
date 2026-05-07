package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 53 (#9419) Checkpoint B sim — bus-write round-trip for the
  * word-0+word-8 high-patIdx path.
  *
  * Verifies that with `patternSelBits = 6` (Option A) a host can
  * program patIdx ≥ 16 by writing the low nibble into word 0 [14:11]
  * and the high 2 bits into word 8 [1:0], in either order, and the
  * Evaluator's `regPatternIndex` reflects the joint value.
  *
  * Backward-compat: word-0-only writes (legacy hosts) still produce
  * patIdx ∈ 0..15 with word 8 [1:0] = 0.
  */
object SpriteHighPatIdxBusSim extends App {

  val D = 64
  val V = 32
  val P = 6
  val L = 4

  Config.sim.compile(SpriteEvaluator(
      descCount = D, visiblePerLine = V, patternSelBits = P, legacyIoCount = L
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.busWr   #= false
    dut.io.busSlot #= 0
    dut.io.busWord #= 0
    dut.io.busData #= 0
    for (i <- 0 until L) {
      dut.io.descX(i)          #= 0
      dut.io.descY(i)          #= 0
      dut.io.descEnabled(i)    #= false
      dut.io.descPatternIdx(i) #= 0
    }
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.clockDomain.waitSampling(2)

    def busPulse(slot: Int, word: Int, data: Int): Unit = {
      dut.io.busSlot #= slot
      dut.io.busWord #= word
      dut.io.busData #= data
      dut.io.busWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.busWr   #= false
      dut.clockDomain.waitSampling()
    }

    // Slot indices in the Reg-backed extended block: extCount = D - L = 60.
    // Test slots: 4 (rel 0), 5 (rel 1), 6 (rel 2), 7 (rel 3).
    case class Vec(slot: Int, low: Int, high: Int)
    val vectors = Seq(
      Vec(slot = 4, low = 0x0, high = 0x0),  // patIdx = 0  (legacy)
      Vec(slot = 5, low = 0xF, high = 0x0),  // patIdx = 15 (legacy max)
      Vec(slot = 6, low = 0x0, high = 0x1),  // patIdx = 16 (first new)
      Vec(slot = 7, low = 0x5, high = 0x2),  // patIdx = 0x25 = 37
      Vec(slot = 8, low = 0xF, high = 0x3)   // patIdx = 0x3F = 63 (max)
    )

    println("[sim] Phase A — word 0 then word 8 (forward order)")
    for (v <- vectors) {
      // Word 0: enabled=1, patIdx[3:0] @ [14:11], y=0
      val w0 = (1 << 15) | (v.low << 11)
      // Word 8: sizeSel=1, paletteBank=0, priority=0, flipH=0, flipV=0,
      //         bppSel=0, _[4:2]=0, patIdx[5:4] @ [1:0]
      val w8 = (1 << 14) | v.high
      busPulse(v.slot, 0, w0)
      busPulse(v.slot, 8, w8)
      val rel = v.slot - L
      val got = dut.regPatternIndex(rel).toInt
      val exp = (v.high << 4) | v.low
      val ok  = got == exp
      println(f"  slot ${v.slot} rel $rel exp=0x$exp%02X got=0x$got%02X  ${if (ok) "PASS" else "FAIL"}")
      assert(ok, s"forward-order patIdx round-trip failed at slot ${v.slot}: exp 0x${exp.toHexString}, got 0x${got.toHexString}")
    }

    println("[sim] Phase B — word 8 then word 0 (reverse order, validates field independence)")
    val baseSlot = 16
    for ((v, i) <- vectors.zipWithIndex) {
      val s = baseSlot + i
      val w0 = (1 << 15) | (v.low << 11)
      val w8 = (1 << 14) | v.high
      busPulse(s, 8, w8)   // high first
      busPulse(s, 0, w0)   // low second
      val rel = s - L
      val got = dut.regPatternIndex(rel).toInt
      val exp = (v.high << 4) | v.low
      val ok  = got == exp
      println(f"  slot $s rel $rel exp=0x$exp%02X got=0x$got%02X  ${if (ok) "PASS" else "FAIL"}")
      assert(ok, s"reverse-order patIdx round-trip failed at slot $s: exp 0x${exp.toHexString}, got 0x${got.toHexString}")
    }

    println("[sim] Phase C — legacy host (word 0 only, no word 8 write) yields patIdx ∈ 0..15")
    val legacySlot = 32
    busPulse(legacySlot, 0, (1 << 15) | (0xC << 11))   // patIdx low = 0xC, no word-8 write
    val gotLegacy = dut.regPatternIndex(legacySlot - L).toInt
    println(f"  slot $legacySlot got=0x$gotLegacy%02X (expected 0x0C)")
    assert(gotLegacy == 0x0C,
      s"legacy word-0-only write should produce 0x0C; got 0x${gotLegacy.toHexString}")
    println("  Phase C PASS — legacy host backward-compat preserved")

    println("[sim] SpriteHighPatIdxBusSim: PASS")
  }
}
