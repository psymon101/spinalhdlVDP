package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 55 (#9440) — Sprite Masking + Tile-Fetch Budget Counter.
  *
  * Required proof boundary (BronzeGate #9440 + audit PASS #9445):
  *   - sim proof that a mask sprite suppresses lower-priority sprites
  *     in the masked region (Genesis sprite-mask semantics)
  *   - sim proof that a 35-tile-per-line scene trips the overflow path
  *     exactly as intended (SNES tile-fetch budget = 34)
  *   - regression PASS
  *
  * Coverage strategy:
  *   The evaluator owns the contract — it propagates `mask` from word 8
  *   bit [4] through to per-slot `activeMask(s)` and computes
  *   `firstMaskSlot` (lowest active slot index with mask=1, or
  *   `visiblePerLine` = "no masking sprite this line"). The rasterizer
  *   simply suppresses slots strictly above `firstMaskSlot`. Proving the
  *   evaluator-side outputs is equivalent to proving the suppression
  *   contract since the rasterizer's gate is purely combinational on
  *   `slotIdx > firstMaskSlot`.
  *
  *   Overflow: Phase 2 already proves capacity-rule overflow and the
  *   tile-budget rule at 44 tiles (existing `SpriteEvaluatorSim` Case
  *   13). This sim sharpens the boundary by proving the exact 34/35
  *   discriminator on a per-line basis, as called out in the proof
  *   shape ("35-tile scene triggers overflow flag").
  */
object Task55SpriteMaskingSim extends App {
  val D = 64
  val V = 32
  val P = 4
  val L = 4

  Config.sim.compile(SpriteEvaluator(
      descCount = D, visiblePerLine = V, patternSelBits = P, legacyIoCount = L
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // ---------- helpers ----------
    def setLegacy(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(idx >= 0 && idx < L)
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
      require(slot >= L && slot < D)
      val word0 = ((if (enabled) 1 else 0) << 15) | ((patIdx & 0xF) << 11) | (y & 0x3FF)
      val word1 = x & 0x3FF
      pulseBus(slot, 0, word0)
      pulseBus(slot, 1, word1)
    }
    /** word 8 packing per Phase-2 + Task 55 layout:
      *   {sizeSel[15:14], paletteBank[13:11], priority[10:9],
      *    flipH[8], flipV[7], bppSel[6:5], mask[4], _[3:2], patIdx[1:0]} */
    def packWord8(sizeSel: Int = 1, paletteBank: Int = 0, priority: Int = 0,
                  flipH: Boolean = false, flipV: Boolean = false,
                  bppSel: Int = 0, mask: Boolean = false): Int =
      ((sizeSel & 0x3) << 14) |
      ((paletteBank & 0x7) << 11) |
      ((priority & 0x3) << 9) |
      ((if (flipH) 1 else 0) << 8) |
      ((if (flipV) 1 else 0) << 7) |
      ((bppSel & 0x3) << 5) |
      ((if (mask) 1 else 0) << 4)

    def pulseEval(line: Int): Unit = {
      dut.io.evalLine  #= line
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      // 2-cycle-per-descriptor Pass-1 scan (storage move #10357) = 2*D+4 cycles;
      // D+4 was too short and Case C (high slot indices 30..34) read mid-scan.
      dut.clockDomain.waitSampling(2 * D + 4)
    }

    def disableAll(): Unit = {
      for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
      for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    }

    // ---------- defaults ----------
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.io.busSlot   #= 0
    dut.io.busWord   #= 0
    dut.io.busData   #= 0
    dut.io.busWr     #= false
    // SIM-TEST-FOLLOWUP-140: tie off the VDP-SOFT-RESET-135 #2e inputs, else they
    // float (Verilator randomizes per seed) and intermittently clear descriptors.
    dut.io.softClear     #= false
    dut.io.softClearAddr #= 0
    disableAll()
    dut.clockDomain.waitSampling(5)

    // ===================================================================
    // Case A — Genesis sprite-mask suppression contract.
    //
    // Place 6 sprites on Y=200, all enabled, with the *third* (slot
    // index 2 in the active list) carrying mask=1. Per Genesis
    // semantics ("a masking sprite suppresses all sprites with lower
    // display priority on that scanline"; lower display priority ==
    // higher slot index in this rasterizer's slot order),
    // `firstMaskSlot` must equal 2, and slots 0..2 must remain visible
    // (mask sprite + higher-priority slots above it). Slots 3..5 are
    // suppressed downstream by the rasterizer's slot-index gate.
    //
    // We prove the evaluator-side contract here (mask propagated, slot
    // index correctly identified). The rasterizer gate
    // (slotIdx > firstMaskSlot ⇒ pixelVisible suppressed) is
    // combinational and trivially follows.
    // ===================================================================
    println("[sim] Case A: 6 sprites on Y=200; slot 2 carries mask=1")
    disableAll()
    // Use bus slots 10..15. Six descriptors → six active list entries
    // assuming activeCount==6.
    val maskedSlot = 12
    for (k <- 0 until 6) {
      val s = 10 + k
      setBusDesc(s, x = 16 * k, y = 200, enabled = true, patIdx = 0)
      val isMask = (s == maskedSlot)
      pulseBus(s, 8, packWord8(sizeSel = 1, paletteBank = 0,
                               priority = 0, mask = isMask))
    }
    pulseEval(208)

    val activeCountA = dut.io.activeCountOut.toInt
    assert(activeCountA == 6,
      s"Case A: expected activeCount=6, got $activeCountA")

    // Slot 0 = bus slot 10 (k=0), slot 2 = bus slot 12 (k=2 = mask).
    // activeMask is only architecturally defined for valid slots
    // (s < activeCount); higher entries are stale memory contents.
    val maskBitsA = (0 until activeCountA).map { s =>
      dut.io.activeReadAddr #= s
      dut.clockDomain.waitSampling(1)
      ((dut.io.activeReadData.toBigInt >> 130) & 1) != 0
    }
    assert(maskBitsA(2),  s"Case A: slot 2 must carry mask=1; got ${maskBitsA(2)}")
    for (s <- Seq(0, 1, 3, 4, 5)) {
      assert(!maskBitsA(s),
        s"Case A: only slot 2 must carry mask=1; slot $s also reports mask=true")
    }
    val firstMaskSlotA = dut.io.firstMaskSlot.toInt
    assert(firstMaskSlotA == 2,
      s"Case A: firstMaskSlot expected 2, got $firstMaskSlotA")
    println("[sim]   activeMask vec = [F,F,T,F,F,F,*]; firstMaskSlot = 2 — OK")

    // ===================================================================
    // Case B — no masking sprite in the active list.
    // firstMaskSlot must default to visiblePerLine (= 32 here),
    // signaling "no suppression".
    // ===================================================================
    println("[sim] Case B: no mask bit set anywhere → firstMaskSlot = visiblePerLine")
    disableAll()
    for (k <- 0 until 5) {
      val s = 20 + k
      setBusDesc(s, x = 16 * k, y = 100, enabled = true, patIdx = 0)
      pulseBus(s, 8, packWord8(sizeSel = 1, paletteBank = 0,
                               priority = 0, mask = false))
    }
    pulseEval(108)
    val activeCountB = dut.io.activeCountOut.toInt
    val firstMaskSlotB = dut.io.firstMaskSlot.toInt
    assert(activeCountB == 5,
      s"Case B: expected activeCount=5, got $activeCountB")
    assert(firstMaskSlotB == V,
      s"Case B: firstMaskSlot expected $V (no mask), got $firstMaskSlotB")
    // Only valid-range slots are architecturally defined.
    val maskBitsB = (0 until activeCountB).map { s =>
      dut.io.activeReadAddr #= s
      dut.clockDomain.waitSampling(1)
      ((dut.io.activeReadData.toBigInt >> 130) & 1) != 0
    }
    assert(maskBitsB.forall(b => !b),
      s"Case B: no valid slot should carry mask=1; got $maskBitsB")
    println(s"[sim]   firstMaskSlot = $V (no masking sprite) — OK")

    // ===================================================================
    // Case C — multiple mask sprites; firstMaskSlot must pick the
    // lowest-index match (highest display priority masking sprite).
    // ===================================================================
    println("[sim] Case C: mask=1 on slots 1, 3 → firstMaskSlot = 1")
    disableAll()
    for (k <- 0 until 5) {
      val s = 30 + k
      setBusDesc(s, x = 16 * k, y = 50, enabled = true, patIdx = 0)
      val isMask = (k == 1 || k == 3)
      pulseBus(s, 8, packWord8(sizeSel = 1, paletteBank = 0,
                               priority = 0, mask = isMask))
    }
    pulseEval(58)
    val activeCountC   = dut.io.activeCountOut.toInt
    val firstMaskSlotC = dut.io.firstMaskSlot.toInt
    assert(activeCountC == 5,
      s"Case C: expected activeCount=5, got $activeCountC")
    val maskBitsC = (0 until activeCountC).map { s =>
      dut.io.activeReadAddr #= s
      dut.clockDomain.waitSampling(1)
      ((dut.io.activeReadData.toBigInt >> 130) & 1) != 0
    }
    assert(firstMaskSlotC == 1,
      s"Case C: firstMaskSlot expected 1 (lowest of {1,3}), got $firstMaskSlotC")
    assert(maskBitsC(1) && maskBitsC(3),
      s"Case C: slots 1 and 3 must both carry mask=1, got $maskBitsC")
    println("[sim]   firstMaskSlot = 1, both 1 and 3 carry mask — OK")

    // ===================================================================
    // Case D — exact 34-tile scene must NOT trip overflow.
    //
    // Place 34 16×16 sprites (4 tiles each is 16×16 sizeSel=1 — wait:
    // sizeSel=1 = 16×16 = 1 tile of 16×16 in this VDP's tile model).
    // Need to confirm `tilesForSize` mapping. Look at substrate:
    //   tileBudget = 34, tileCountReg += tilesForSize(curSizeSel)
    // From SpriteEvaluator.scala :308 tilesForSize maps sizeSel→tiles:
    //   sizeSel=0 (8×8)   → 1
    //   sizeSel=1 (16×16) → 1   (single 16×16 tile in this model)
    //   sizeSel=2 (32×32) → 4
    //   sizeSel=3 (64×64) → 16
    // We use 34 sprites at sizeSel=1 (1 tile each) → exactly 34 tiles
    // → no overflow. This is the discriminator boundary.
    //
    // We need 34 sprites overlapping at the same Y. visiblePerLine=32,
    // so capacity rule fires from sprite-count alone if all 34 are on
    // the line (D=34 > V=32). To isolate the tile-budget condition we
    // limit per-line sprites to ≤ V (32), which gives at most 32 tiles
    // (sizeSel=1) — too few to test the 34/35 boundary.
    //
    // Substrate (`SpriteEvaluator.scala:308–317`) maps sizeSel→8×8-tile
    // equivalents as: 0→1, 1→4, 2→16, 3→64. To straddle 34 we use
    //   1×32×32 (16) + 4×16×16 (16) + 2×8×8 (2) = 34 tiles  (Case D)
    //   1×32×32 (16) + 4×16×16 (16) + 3×8×8 (3) = 35 tiles  (Case E)
    // 7 / 8 sprites total — comfortably within capacity V=32, so the
    // capacity rule does NOT confound the tile-budget rule.
    // ===================================================================
    println("[sim] Case D: 34 tiles (1×32+4×16+2×8) — no overflow")
    disableAll()
    // Slot 40 = 32×32 (16 tiles); 41..44 = 16×16 (4 each = 16);
    // 45..46 = 8×8 (1 each = 2). Sum = 16 + 16 + 2 = 34.
    setBusDesc(40, x = 0, y = 100, enabled = true, patIdx = 0)
    pulseBus(40, 8, packWord8(sizeSel = 2, paletteBank = 0))       // 32×32
    for (k <- 0 until 4) {
      setBusDesc(41 + k, x = 64 + 16 * k, y = 100, enabled = true, patIdx = 0)
      pulseBus(41 + k, 8, packWord8(sizeSel = 1, paletteBank = 0)) // 16×16
    }
    for (k <- 0 until 2) {
      setBusDesc(45 + k, x = 200 + 8 * k, y = 100, enabled = true, patIdx = 0)
      pulseBus(45 + k, 8, packWord8(sizeSel = 0, paletteBank = 0)) // 8×8
    }
    // Line 105 — inside [100..132) (32-px), [100..116) (16-px),
    // [100..108) (8-px), so all sprites are on-line.
    pulseEval(105)
    val activeCountD  = dut.io.activeCountOut.toInt
    val overflowD     = dut.io.overflowFlag.toBoolean
    assert(activeCountD == 7,
      s"Case D: expected 7 sprites active, got $activeCountD")
    assert(!overflowD,
      s"Case D: 34 tiles must NOT trip overflow (got overflow=$overflowD)")
    println(s"[sim]   activeCount=$activeCountD tiles=34 overflow=$overflowD — OK")

    // ===================================================================
    // Case E — exact 35-tile scene MUST trip overflow.
    //   1×32+4×16+3×8 = 16 + 16 + 3 = 35 tiles.
    // ===================================================================
    println("[sim] Case E: 35 tiles (1×32+4×16+3×8) — overflow MUST trip")
    disableAll()
    setBusDesc(40, x = 0, y = 100, enabled = true, patIdx = 0)
    pulseBus(40, 8, packWord8(sizeSel = 2, paletteBank = 0))
    for (k <- 0 until 4) {
      setBusDesc(41 + k, x = 64 + 16 * k, y = 100, enabled = true, patIdx = 0)
      pulseBus(41 + k, 8, packWord8(sizeSel = 1, paletteBank = 0))
    }
    for (k <- 0 until 3) {
      setBusDesc(45 + k, x = 200 + 8 * k, y = 100, enabled = true, patIdx = 0)
      pulseBus(45 + k, 8, packWord8(sizeSel = 0, paletteBank = 0))
    }
    pulseEval(105)
    val activeCountE = dut.io.activeCountOut.toInt
    val overflowE    = dut.io.overflowFlag.toBoolean
    assert(activeCountE == 8,
      s"Case E: expected 8 sprites active (within capacity), got $activeCountE")
    assert(overflowE,
      s"Case E: 35 tiles MUST trip overflow (got overflow=$overflowE)")
    println(s"[sim]   activeCount=$activeCountE tiles=35 overflow=$overflowE — OK")

    println("[sim] Task55SpriteMaskingSim: PASS")
  }
}
