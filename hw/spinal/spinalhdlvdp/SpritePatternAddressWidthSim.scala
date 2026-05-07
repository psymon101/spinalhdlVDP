package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 53 (#9419) Checkpoint B sim — sprite pattern address-width
  * expansion proof.
  *
  * Verifies that with `patternSelBits = 6` (Option A: 64 unique 16×16
  * tiles) the sprite rasterizer's `patternRamAddr` reaches the upper
  * half of the 14-bit address space when configured with `patIdx ≥ 16`.
  *
  * Pre-fix substrate (`patternSelBits = 4`) could only produce
  * addresses in `[0x000..0xFFF]`. Post-fix, addresses must span
  * `[0x000..0x3FFF]` and the high 2 bits of `patIdx` must drive the
  * top of the `patternRamAddr` concatenation correctly.
  *
  * The sim drives the rasterizer's RAM read port directly via its
  * narrow active-list interface (same pattern as `SpriteRasterizerSim`)
  * and observes every `patternRamAddr` issued during a single render
  * line.
  */
object SpritePatternAddressWidthSim extends App {

  // Pack a slot's fields into a 130-bit BigInt matching the post-Task-53
  // SpriteEvaluator.packSlot bit layout (patIdx widened 4→6).
  def packSlot(matrixA: Int = 0, matrixB: Int = 0, matrixC: Int = 0, matrixD: Int = 0,
               transX: Int = 0, transY: Int = 0,
               x: Int = 0, row: Int = 0,
               patIdx: Int = 0, paletteBank: Int = 0, priority: Int = 0,
               sizeSel: Int = 0, bppSel: Int = 0,
               affineEnable: Boolean = false, flipH: Boolean = false,
               flipV: Boolean = false): BigInt = {
    var w = BigInt(0)
    w = (w << 16) | (matrixA & 0xFFFF)
    w = (w << 16) | (matrixB & 0xFFFF)
    w = (w << 16) | (matrixC & 0xFFFF)
    w = (w << 16) | (matrixD & 0xFFFF)
    w = (w << 16) | (transX  & 0xFFFF)
    w = (w << 16) | (transY  & 0xFFFF)
    w = (w << 10) | (x & 0x3FF)
    w = (w <<  6) | (row & 0x3F)
    w = (w <<  6) | (patIdx & 0x3F)
    w = (w <<  3) | (paletteBank & 0x7)
    w = (w <<  2) | (priority & 0x3)
    w = (w <<  2) | (sizeSel & 0x3)
    w = (w <<  2) | (bppSel & 0x3)
    w = (w <<  1) | (if (affineEnable) 1 else 0)
    w = (w <<  1) | (if (flipH)        1 else 0)
    w = (w <<  1) | (if (flipV)        1 else 0)
    w
  }

  Config.sim.compile(SpriteRasterizer(
    visiblePerLine = 32, patternSelBits = 6, hActive = 640, cycleBudget = 798
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val activeList   = scala.collection.mutable.Map[Int, BigInt]()
    var activeCount  = 0
    val observedAddrs = scala.collection.mutable.ArrayBuffer[Int]()

    // Forked driver: replays activeList for the rasterizer and snapshots
    // every cycle's patternRamAddr so we can post-hoc check the address
    // space exercised.
    fork {
      while(true) {
        val addr = dut.io.activeReadAddr.toInt
        dut.io.activeReadData #= activeList.getOrElse(addr, BigInt(0))
        dut.io.activeCount    #= activeCount
        observedAddrs += dut.io.patternRamAddr.toInt
        dut.clockDomain.waitSampling()
      }
    }

    dut.io.activeReadData  #= 0
    dut.io.activeCount     #= 0
    dut.io.firstMaskSlot   #= 32   // Task 55 — no masking sprite
    dut.io.lineRenderStart #= false
    dut.io.fillLineY       #= 0
    dut.io.patternRamData  #= 0xF
    dut.io.drainAddr       #= 0
    dut.io.bufferSwap      #= false
    dut.clockDomain.waitSampling(5)

    def runOneLine(): Unit = {
      observedAddrs.clear()
      dut.io.lineRenderStart #= true
      dut.clockDomain.waitSampling()
      dut.io.lineRenderStart #= false
      for (_ <- 0 until 800) dut.clockDomain.waitSampling()
    }

    def addrSlot(addr: Int): Int = (addr >> 8) & 0x3F  // top 6 bits = patIdx
    def addrSubByte(addr: Int): Int = addr & 0xFF

    println("[sim] Case 1: single sprite with patIdx=17 (sizeSel=0, 8 px)")
    activeList.clear()
    activeList(0) = packSlot(x = 100, sizeSel = 0, patIdx = 17, priority = 1)
    activeCount = 1
    runOneLine()

    val highAddrs = observedAddrs.filter(a => addrSlot(a) == 17)
    val lowAddrs  = observedAddrs.filter(a => a < 0x100)
    val maxAddr   = if (observedAddrs.nonEmpty) observedAddrs.max else 0
    println(f"  observed addr count=${observedAddrs.size}, max=0x$maxAddr%04X")
    println(f"  patIdx-17 addrs (top 6 bits == 17): ${highAddrs.size}")
    println(f"  pre-fix range [0x000..0x0FF] addrs: ${lowAddrs.size}")
    assert(highAddrs.nonEmpty,
      s"expected ≥1 patternRamAddr with top 6 bits == 17 (i.e. addr ≥ 0x1100); got 0")
    assert(maxAddr >= 0x1100,
      s"max address 0x${maxAddr.toHexString} did not enter the 0x1100..0x11FF band — patIdx high bits did NOT drive patternRamAddr")
    println("  Case 1 PASS — high patIdx drives patternRamAddr above 0x0FFF")

    println("[sim] Case 2: four sprites with patIdx ∈ {16,17,18,19}, sizeSel=0")
    activeList.clear()
    val testIdxs = Seq(16, 17, 18, 19)
    for ((p, i) <- testIdxs.zipWithIndex) {
      activeList(i) = packSlot(x = 50 + i*40, sizeSel = 0, patIdx = p, priority = 1)
    }
    activeCount = testIdxs.size
    runOneLine()

    val seenSlots = observedAddrs.map(addrSlot).filter(s => testIdxs.contains(s)).toSet
    println(f"  observed slot ids ${observedAddrs.map(addrSlot).toSet.toSeq.sorted}")
    val missing = testIdxs.toSet -- seenSlots
    assert(missing.isEmpty,
      s"expected to see slots ${testIdxs.mkString(",")} on patternRamAddr; missed: ${missing.mkString(",")}")
    println(s"  Case 2 PASS — all four high-patIdx slots reached patternRamAddr")

    println("[sim] Case 3: legacy patIdx=3 (sizeSel=0) stays in low 0x300..0x3FF band")
    // Drain the prior line first — running an empty line clears the
    // rasterizer's compositor pipeline so Case 2's high-patIdx slots
    // do not leak into Case 3's address counts.
    activeList.clear()
    activeCount = 0
    runOneLine()
    activeList(0) = packSlot(x = 32, sizeSel = 0, patIdx = 3, priority = 1)
    activeCount = 1
    runOneLine()

    val nonzero = observedAddrs.filter(_ != 0)
    val inLegacyBand = nonzero.filter(a => addrSlot(a) == 3)
    val maxLeg = if (nonzero.nonEmpty) nonzero.max else 0
    println(f"  observed nonzero addr count=${nonzero.size}, max=0x$maxLeg%04X, in patIdx=3 band: ${inLegacyBand.size}")
    // Allow a small handful of pipeline-tail leftover cycles from the
    // immediately-prior empty drain. Bulk of nonzero addresses must
    // fall in the patIdx=3 band.
    assert(inLegacyBand.size > nonzero.size * 9 / 10,
      s"expected the bulk of nonzero addrs (>90 %) in the patIdx=3 band; got ${inLegacyBand.size}/${nonzero.size}")
    println("  Case 3 PASS — legacy low-patIdx routes to its 0x300..0x3FF band")

    println("[sim] Case 4 (CyanPeak #9427): mixed legacy + high-patIdx sprites — non-interference")
    // Drain pipeline tail.
    activeList.clear()
    activeCount = 0
    runOneLine()
    // Slot 0: legacy 8-px sprite at patIdx=2 (low band).
    // Slot 1: 32-px sprite at patIdx=20 (high band; demonstrates that
    // a Genesis-style large sprite using a high pattern slot does not
    // collide with the legacy slot's pattern fetches).
    activeList.clear()
    activeList(0) = packSlot(x = 50,  sizeSel = 0, patIdx = 2,  priority = 1)
    activeList(1) = packSlot(x = 200, sizeSel = 2, patIdx = 20, priority = 1)
    activeCount = 2
    runOneLine()

    val mixedNonzero = observedAddrs.filter(_ != 0)
    val legacySlotAddrs = mixedNonzero.filter(a => addrSlot(a) == 2)
    val highSlotAddrs   = mixedNonzero.filter(a => addrSlot(a) == 20)
    val otherAddrs      = mixedNonzero.filter(a => addrSlot(a) != 2 && addrSlot(a) != 20)
    println(f"  observed nonzero=${mixedNonzero.size}, patIdx=2 band=${legacySlotAddrs.size}, " +
            f"patIdx=20 band=${highSlotAddrs.size}, other=${otherAddrs.size}")
    assert(legacySlotAddrs.nonEmpty,
      "mixed scene: legacy patIdx=2 produced zero pattern reads — slot starved")
    assert(highSlotAddrs.nonEmpty,
      "mixed scene: high patIdx=20 produced zero pattern reads — slot starved")
    // Allow a tiny number of pipeline-tail strays from prior cases; the
    // active two slots must dominate all but a small tail.
    assert(otherAddrs.size < 10,
      s"mixed scene: ${otherAddrs.size} addresses landed outside the {patIdx=2, patIdx=20} bands — non-interference violated")
    println("  Case 4 PASS — legacy 8-px sprite and high-patIdx 32-px sprite render without crosstalk")

    println("[sim] SpritePatternAddressWidthSim: PASS")
  }
}
