package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 28 — Two-Pass Sprite Evaluator validation sim.
  *
  * Parameters match the Task 28 landing: `descCount=32`, `visiblePerLine=8`,
  * `patternSelBits=4`, `legacyIoCount=4`. The sequential Pass-1 FSM takes
  * `descCount` cycles to complete; tests wait on that before reading
  * active-list outputs.
  *
  * Cases:
  *   1. on-line / off-line selection via legacy IO slots
  *   2. empty line — zero active, no overflow
  *   3. overflow — 9 enabled sprites on one line → only first 8 retained,
  *      `overflowFlag` asserts, slot ordering is lowest-idx-first
  *   4. disabled-skip — disabling an earlier descriptor shifts later ones
  *      forward in the active list
  *   5. stability — active list stays constant through >800 cycles without
  *      a new evalStart strobe
  *   6. Y-boundary — [Y, Y+16) inclusive/exclusive bounds
  *   7. bus programming — Reg-backed slots 4..31 drive active selections
  *      when legacy slots 0..3 are disabled
  */
object SpriteEvaluatorSim extends App {
  val D = 32
  val V = 8
  val P = 4
  val L = 4

  Config.sim.compile(SpriteEvaluator(
      descCount = D, visiblePerLine = V, patternSelBits = P, legacyIoCount = L
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def setLegacy(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(idx >= 0 && idx < L, s"setLegacy idx=$idx out of legacy range [0..$L)")
      dut.io.descX(idx)          #= x
      dut.io.descY(idx)          #= y
      dut.io.descEnabled(idx)    #= enabled
      dut.io.descPatternIdx(idx) #= patIdx
    }

    def pulseBus(slot: Int, word: Int, data: Int): Unit = {
      dut.io.busSlot #= slot
      dut.io.busWord #= word
      dut.io.busData #= data
      dut.io.busWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.busWr   #= false
    }

    def setBusDesc(slot: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(slot >= L && slot < D, s"setBusDesc slot=$slot not in bus range [$L..$D)")
      val word0 = ((if (enabled) 1 else 0) << 15) | ((patIdx & 0xF) << 11) | (y & 0x3FF)
      val word1 = x & 0x3FF
      pulseBus(slot, 0, word0)
      pulseBus(slot, 1, word1)
    }

    def pulseEval(line: Int): Unit = {
      dut.io.evalLine  #= line
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      // Sequential scan takes descCount cycles; wait a safety margin.
      dut.clockDomain.waitSampling(D + 4)
    }

    def activeSet(): Seq[(Boolean, Int, Int, Int, Int)] =
      (0 until V).map { s =>
        (dut.io.activeValid(s).toBoolean,
         dut.io.activeX(s).toInt,
         dut.io.activeRow(s).toInt,
         dut.io.activePatternIdx(s).toInt,
         s)
      }

    // Defaults.
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.io.busSlot   #= 0
    dut.io.busWord   #= 0
    dut.io.busData   #= 0
    dut.io.busWr     #= false
    // Initialize all bus slots to disabled state.
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    dut.clockDomain.waitSampling(5)

    // --- Case 1: on/off line selection via legacy IO slots ---
    setLegacy(0, 100,  50, enabled = true, patIdx = 0)
    setLegacy(1, 200,  50, enabled = true, patIdx = 1)
    setLegacy(2, 300, 200, enabled = true, patIdx = 0)
    setLegacy(3, 400, 200, enabled = true, patIdx = 1)
    pulseEval(55)
    val c1 = activeSet()
    assert(c1(0)._1 && c1(0)._2 == 100 && c1(0)._3 == 5 && c1(0)._4 == 0,
           s"Case 1 slot 0: got $c1")
    assert(c1(1)._1 && c1(1)._2 == 200 && c1(1)._3 == 5 && c1(1)._4 == 1,
           s"Case 1 slot 1: got $c1")
    assert(!c1(2)._1 && !c1(3)._1, s"Case 1 unused slots must be invalid: $c1")
    assert(!dut.io.overflowFlag.toBoolean, "Case 1: unexpected overflow")
    println("[sim] Case 1 on-line select via legacy IO — OK")

    // --- Case 2: empty line ---
    pulseEval(130)
    val c2 = activeSet()
    assert(c2.forall(!_._1), s"Case 2: expected all slots invalid, got $c2")
    assert(!dut.io.overflowFlag.toBoolean, "Case 2: unexpected overflow")
    println("[sim] Case 2 empty line — OK")

    // --- Case 3: overflow — 9 enabled sprites on line 100, only 8 retained ---
    for (d <- 0 until L) setLegacy(d, 10 + 50*d, 100, enabled = true, patIdx = d % 2)
    // Bus slots 4..8 also on line 100.
    for (s <- L until 9) setBusDesc(s, 10 + 50*s, 100, enabled = true, patIdx = s % 2)
    pulseEval(110)
    val c3 = activeSet()
    val validCount = c3.count(_._1)
    assert(validCount == V, s"Case 3: expected $V valid slots, got $validCount in $c3")
    assert(dut.io.overflowFlag.toBoolean, "Case 3: overflow flag must be set")
    // Slot 0 = lowest descriptor index = legacy 0 @ x=10.
    assert(c3(0)._2 == 10, s"Case 3 slot 0 should be legacy desc 0 (x=10): $c3")
    // Slot 7 = 8th-lowest active = bus slot 7 @ x=10+50*7=360.
    assert(c3(7)._2 == 360, s"Case 3 slot 7 should be bus desc 7 (x=360): $c3")
    println(f"[sim] Case 3 overflow: ${validCount}/${V} valid, overflow flag set, ordering lowest-idx-first — OK")

    // --- Case 4: disable legacy 0 → slot 0 should become legacy 1 ---
    setLegacy(0, 10, 100, enabled = false)
    pulseEval(110)
    val c4 = activeSet()
    assert(c4(0)._1 && c4(0)._2 == 60, s"Case 4 slot 0 must be legacy 1 (x=60): $c4")
    println("[sim] Case 4 disable-skip — OK")

    // --- Case 5: stability — no new evalStart for >800 cycles ---
    val snap = activeSet()
    dut.clockDomain.waitSampling(900)
    val later = activeSet()
    assert(snap == later, s"Case 5 stability: snap=$snap later=$later")
    println("[sim] Case 5 active list stable without new evalStart — OK")

    // --- Case 6: Y boundary ---
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setLegacy(0, 77, 50, enabled = true, patIdx = 0)
    pulseEval(50)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 0,
           "Case 6 Y=50 row=0")
    pulseEval(65)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 15,
           "Case 6 Y=65 row=15")
    pulseEval(66)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=66 off-line")
    pulseEval(49)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=49 off-line")
    println("[sim] Case 6 Y-boundary [Y..Y+16) — OK")

    // --- Case 7: bus programming of slot 10 with legacy disabled ---
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    setBusDesc(10, x = 222, y = 80, enabled = true, patIdx = 3)
    pulseEval(88)
    val c7 = activeSet()
    assert(c7(0)._1 && c7(0)._2 == 222 && c7(0)._4 == 3,
           s"Case 7: slot 10 bus-programmed descriptor should drive slot 0: $c7")
    println("[sim] Case 7 bus-programmed slot selection — OK")

    // ====================================================================
    // Sprite Envelope Hardening cases (CyanPeak #8577).
    // ====================================================================

    /** Pack the new word-8 control fields per the assessment §4.3 layout
      * + Phase 2 (#8614) extensions:
      *   {sizeSel[15:14], paletteBank[13:11], priority[10:9],
      *    flipH[8], flipV[7], bppSel[6:5], _[4:0]} */
    def packWord8Full(sizeSel: Int, paletteBank: Int, priority: Int,
                      flipH: Boolean, flipV: Boolean, bppSel: Int): Int =
      ((sizeSel & 0x3) << 14) |
      ((paletteBank & 0x7) << 11) |
      ((priority & 0x3) << 9) |
      ((if (flipH) 1 else 0) << 8) |
      ((if (flipV) 1 else 0) << 7) |
      ((bppSel & 0x3) << 5)

    /** Legacy single-bit-priority shim used by Stage A test cases. */
    def packWord8(sizeSel: Int, paletteBank: Int, priority: Boolean,
                  flipH: Boolean, flipV: Boolean): Int =
      packWord8Full(sizeSel, paletteBank, if (priority) 1 else 0,
                    flipH, flipV, bppSel = 0)

    // --- Case 8: word-8 fields propagate to active outputs ---
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    // Slot 12: sizeSel=10 (32×32), bank=5, priority=1, flipH=1, flipV=0.
    setBusDesc(12, x = 100, y = 200, enabled = true, patIdx = 7)
    pulseBus(12, 8, packWord8(sizeSel = 2, paletteBank = 5,
                              priority = true, flipH = true, flipV = false))
    pulseEval(210)
    assert(dut.io.activeValid(0).toBoolean,                  "Case 8: slot 0 valid")
    assert(dut.io.activeFlipH(0).toBoolean,                  "Case 8: flipH = true")
    assert(!dut.io.activeFlipV(0).toBoolean,                 "Case 8: flipV = false")
    assert(dut.io.activePaletteBank(0).toInt == 5,           "Case 8: paletteBank = 5")
    assert(dut.io.activePriority(0).toInt == 1,              "Case 8: priority bit 0 set (legacy boolean true → priority=1)")
    assert(dut.io.activeBppSel(0).toInt == 0,                "Case 8: bppSel default 4bpp (00)")
    assert(dut.io.activeSizeSel(0).toInt == 2,               "Case 8: sizeSel = 2 (32×32)")
    println("[sim] Case 8 word-8 field propagation — OK")

    // --- Case 9: sizeSel-aware Y-range, sizeSel=10 → 32 px tall ---
    // Sprite at y=200, sizeSel=10 covers lines [200..232).
    pulseEval(231)
    assert(dut.io.activeValid(0).toBoolean,
           "Case 9: line 231 must be in 32-px sprite Y-range [200..232)")
    pulseEval(232)
    assert(!dut.io.activeValid(0).toBoolean,
           "Case 9: line 232 must be off-line (Y-range half-open)")
    pulseEval(199)
    assert(!dut.io.activeValid(0).toBoolean,
           "Case 9: line 199 must be off-line")
    println("[sim] Case 9 sizeSel=2 → Y-range 32 px — OK")

    // --- Case 10: sizeSel=11 (64×64) — covers [Y..Y+64), activeRow up to 63 ---
    setBusDesc(13, x = 0, y = 100, enabled = true, patIdx = 0)
    pulseBus(13, 8, packWord8(sizeSel = 3, paletteBank = 0,
                              priority = false, flipH = false, flipV = false))
    // Disable slot 12 so this is the only active sprite.
    pulseBus(12, 0, ((1 << 15) | (7 << 11) | 1023))   // enabled=1 stays, but y=1023 off-line
    pulseEval(163)
    val c10row = dut.io.activeRow(0).toInt
    assert(dut.io.activeValid(0).toBoolean,        "Case 10: line 163 in [100..164)")
    assert(c10row == 63,                           s"Case 10: 6-bit row should be 63, got $c10row")
    pulseEval(164)
    assert(!dut.io.activeValid(0).toBoolean,       "Case 10: line 164 off-line for 64-px sprite")
    println(s"[sim] Case 10 sizeSel=3 → 64-px sprite, activeRow span 0..63 — OK")

    // --- Case 11: sizeSel=00 (8×8) — Y-range half as tall as 16-px default ---
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setBusDesc(14, x = 0, y = 50, enabled = true, patIdx = 0)
    pulseBus(14, 8, packWord8(sizeSel = 0, paletteBank = 0,
                              priority = false, flipH = false, flipV = false))
    pulseEval(57)
    assert(dut.io.activeValid(0).toBoolean,        "Case 11: 8-px sprite covers lines 50..57")
    pulseEval(58)
    assert(!dut.io.activeValid(0).toBoolean,       "Case 11: line 58 off-line for 8-px sprite")
    println("[sim] Case 11 sizeSel=0 → 8-px sprite — OK")

    // --- Case 12: legacy (IO) slot retains back-compat 16-px Y-range
    //              even though the new sizeSel field exists ---
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setLegacy(0, 50, 300, enabled = true, patIdx = 0)
    pulseEval(315)
    assert(dut.io.activeValid(0).toBoolean,        "Case 12: legacy sprite Y in [300..316)")
    assert(dut.io.activeSizeSel(0).toInt == 1,     "Case 12: legacy sprite reports sizeSel=1 (16×16)")
    assert(dut.io.activePaletteBank(0).toInt == 0, "Case 12: legacy sprite reports paletteBank=0")
    assert(dut.io.activePriority(0).toInt == 0,    "Case 12: legacy sprite reports priority=0 (2-bit)")
    assert(dut.io.activeBppSel(0).toInt == 0,      "Case 12: legacy sprite reports bppSel=0 (4bpp)")
    pulseEval(316)
    assert(!dut.io.activeValid(0).toBoolean,       "Case 12: legacy sprite off-line at Y+16")
    println("[sim] Case 12 legacy back-compat (sizeSel=1, paletteBank=0, priority=0) — OK")

    // ====================================================================
    // Phase 2 P2-4 — tile-fetch budget counter (CyanPeak #8614)
    // ====================================================================
    // Place 1 32×32 sprite (16 tiles) + 7 16×16 sprites (4 tiles each =
    // 28 tiles) on the same line. Total: 8 visible sprites (capacity OK
    // at visiblePerLine=8) but 44 tiles > 34 SNES budget → overflow flag
    // must fire on the tile-budget condition alone.
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setBusDesc(15, x = 0,   y = 200, enabled = true, patIdx = 0)
    pulseBus(15, 8, packWord8(sizeSel = 2, paletteBank = 0,
                              priority = false, flipH = false, flipV = false))
    for (k <- 0 until 7) {
      setBusDesc(16 + k, x = 64 + 32*k, y = 200, enabled = true, patIdx = 0)
      pulseBus(16 + k, 8, packWord8(sizeSel = 1, paletteBank = 0,
                                    priority = false, flipH = false, flipV = false))
    }
    pulseEval(210)
    val activeAtP24 = (0 until V).count(s => dut.io.activeValid(s).toBoolean)
    assert(activeAtP24 == V,
      s"Case 13: expected $V visible sprites (capacity met), got $activeAtP24")
    assert(dut.io.overflowFlag.toBoolean,
      "Case 13: tile budget = 44 > 34 must trigger overflow")
    println(s"[sim] Case 13 tile-budget overflow @ 44 tiles (capacity OK) — OK")

    // ====================================================================
    // Phase 2 P2-2 + P2-3b — bppSel and 2-bit priority field round-trip
    // ====================================================================
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setBusDesc(20, x = 0, y = 100, enabled = true, patIdx = 0)
    pulseBus(20, 8, packWord8Full(sizeSel = 1, paletteBank = 0, priority = 3,
                                   flipH = false, flipV = false, bppSel = 2))
    pulseEval(105)
    assert(dut.io.activeValid(0).toBoolean,                    "Case 14: slot 0 active")
    assert(dut.io.activePriority(0).toInt == 3,                "Case 14: priority = 3 (full 2-bit)")
    assert(dut.io.activeBppSel(0).toInt == 2,                  "Case 14: bppSel = 2 (1bpp)")
    println("[sim] Case 14 P2-2/P2-3b — priority=3 + bppSel=2 (1bpp) round-trip — OK")

    println("[sim] SpriteEvaluatorSim: PASS")
  }
}
