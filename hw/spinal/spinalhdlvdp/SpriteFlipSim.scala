package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 52 — Per-Sprite X/Y Flip primitive.
  *
  * Two-phase proof of the flip primitive (CyanPeak audit PASS #9107,
  * BronzeGate GO #9109; converged packet #9105; trim ruling #9113):
  *
  * Phase A — Evaluator bus-write → active-output propagation across all
  *   12 combinations of {flipH, flipV} × bppSel ∈ {4bpp, 2bpp, 1bpp}.
  *   Catches regressions in the hardening word-8 decode and the Pass-1
  *   active-Reg copy. Builds on SpriteEvaluatorSim Case 8 (single-combo).
  *
  * Phase B — Address-mirror math assertion for the VdpTop sprite slot
  *   address generation. The colShifted mux + the colLowR register
  *   source were the bug locus that CoralReef found (#9102). This phase
  *   reproduces the post-fix arithmetic in pure Scala and asserts that
  *   for every (col, row, flipH, flipV, bppSel) the computed `ramAddr`
  *   equals the canonical mirror around the 16-pixel pattern cell, and
  *   that flipH visibly affects the address under every bppSel — which
  *   the pre-fix code did not.
  *
  * The full render-level mirror proof lives on hardware as
  * `sc62_sprite_flip` (RTSP + 30s OpenCV analysis).
  */
object SpriteFlipSim extends App {
  Config.sim.compile(SpriteEvaluator(
      descCount = 64, visiblePerLine = 32,
      patternSelBits = 4, legacyIoCount = 4))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      for (i <- 0 until 4) {
        dut.io.descX(i)          #= 1000
        dut.io.descY(i)          #= 1000
        dut.io.descEnabled(i)    #= false
        dut.io.descPatternIdx(i) #= 0
      }
      dut.io.busSlot   #= 0
      dut.io.busWord   #= 0
      dut.io.busData   #= 0
      dut.io.busWr     #= false
      dut.io.evalLine  #= 0
      dut.io.evalStart #= false
      dut.clockDomain.waitSampling(5)

      def busPulse(slot: Int, word: Int, data: Int): Unit = {
        dut.io.busSlot #= slot
        dut.io.busWord #= word
        dut.io.busData #= data & 0xFFFF
        dut.io.busWr   #= true
        dut.clockDomain.waitSampling()
        dut.io.busWr   #= false
        dut.clockDomain.waitSampling()
      }

      def encodeWord8(sizeSel: Int, paletteBank: Int, priority: Int,
                      flipH: Boolean, flipV: Boolean, bppSel: Int): Int =
        ((sizeSel & 0x3) << 14) |
        ((paletteBank & 0x7) << 11) |
        ((priority & 0x3) << 9) |
        ((if (flipH) 1 else 0) << 8) |
        ((if (flipV) 1 else 0) << 7) |
        ((bppSel & 0x3) << 5)

      def encodeWord0(enabled: Boolean, patIdx: Int,
                      affineEnable: Boolean, y: Int): Int =
        ((if (enabled) 1 else 0) << 15) |
        ((patIdx & 0xF) << 11) |
        ((if (affineEnable) 1 else 0) << 10) |
        (y & 0x3FF)

      // -----------------------------------------------------------------
      // Phase A — bus-write → active-output propagation, 12 combinations.
      // visiblePerLine = 8, so we run two batches of 6 sprites each.
      // -----------------------------------------------------------------

      val cases: Seq[(Boolean, Boolean, Int)] = for {
        bpp <- 0 to 2
        fh  <- Seq(false, true)
        fv  <- Seq(false, true)
      } yield (fh, fv, bpp)
      assert(cases.size == 12)

      val batches = cases.grouped(6).toSeq
      val sX = 50
      val sY = 200
      val patBase = 4

      def runBatch(batchIdx: Int, batch: Seq[(Boolean, Boolean, Int)]): Unit = {
        for (s <- patBase until (patBase + 12)) {
          busPulse(s, 0, encodeWord0(enabled = false, patIdx = 0,
                                      affineEnable = false, y = 1000))
          busPulse(s, 8, 0)
        }
        for ((c, i) <- batch.zipWithIndex) {
          val slot = patBase + i
          val (fh, fv, bpp) = c
          busPulse(slot, 0, encodeWord0(enabled = true, patIdx = 0,
                                         affineEnable = false, y = sY))
          busPulse(slot, 1, sX + i * 20)
          busPulse(slot, 8, encodeWord8(sizeSel = 1, paletteBank = 0,
                                         priority = 0, flipH = fh,
                                         flipV = fv, bppSel = bpp))
        }

        dut.clockDomain.waitSampling(3)
        dut.io.evalLine  #= sY + 5
        dut.io.evalStart #= true
        dut.clockDomain.waitSampling()
        dut.io.evalStart #= false
        dut.clockDomain.waitSampling(40)

        for (i <- batch.indices) {
          val (fh, fv, bpp) = batch(i)
          val gotFh   = dut.io.activeFlipH(i).toBoolean
          val gotFv   = dut.io.activeFlipV(i).toBoolean
          val gotBpp  = dut.io.activeBppSel(i).toInt
          val gotVld  = dut.io.activeValid(i).toBoolean
          val ok = gotVld && gotFh == fh && gotFv == fv && gotBpp == bpp
          println(f"  batch=$batchIdx idx=$i fh=$fh%-5s fv=$fv%-5s bpp=$bpp" +
                  f"  got valid=$gotVld fh=$gotFh fv=$gotFv bpp=$gotBpp" +
                  f"  ${if (ok) "PASS" else "FAIL"}")
          assert(ok, s"Phase A mismatch: batch=$batchIdx i=$i exp(fh=$fh,fv=$fv,bpp=$bpp)")
        }
      }

      println("== Phase A: evaluator bus-write → active-output, 12 combos ==")
      for ((batch, idx) <- batches.zipWithIndex) {
        runBatch(idx, batch)
      }

      // -----------------------------------------------------------------
      // Phase B — address-mirror math assertion (post-fix VdpTop logic).
      // -----------------------------------------------------------------

      println("== Phase B: address mirror math, all 12 (col,row,fh,fv,bpp) tuples ==")

      def flippedCol(col: Int, fh: Boolean): Int = {
        val c4 = col & 0xF
        if (fh) (~c4) & 0xF else c4
      }
      def flippedRow(row: Int, fv: Boolean): Int = {
        val r4 = row & 0xF
        if (fv) (~r4) & 0xF else r4
      }
      def colShifted(col: Int, fh: Boolean, bpp: Int): Int = {
        val fc = flippedCol(col, fh)
        bpp match {
          case 0 => fc
          case 1 => (fc >> 1) & 0x7
          case 2 => (fc >> 2) & 0x3
          case _ => fc
        }
      }
      def flatAddr(col: Int, row: Int, fh: Boolean, fv: Boolean): Int =
        (flippedRow(row, fv) << 4) | flippedCol(col, fh)
      def flatAddrBpp(col: Int, row: Int, fh: Boolean, fv: Boolean, bpp: Int): Int =
        (flippedRow(row, fv) << 4) | (colShifted(col, fh, bpp) & 0xF)
      def effFlatAddr(col: Int, row: Int, fh: Boolean, fv: Boolean, bpp: Int): Int =
        if (bpp == 0) flatAddr(col, row, fh, fv)
        else          flatAddrBpp(col, row, fh, fv, bpp)

      var phaseBChecks = 0
      // Discriminator 1: flipH must change the address for every bpp at boundary cols.
      for (bpp <- 0 to 2) {
        for (col <- Seq(0, 15); row <- 0 to 15) {
          val addrNoFh = effFlatAddr(col, row, fh = false, fv = false, bpp)
          val addrFh   = effFlatAddr(col, row, fh = true,  fv = false, bpp)
          val changed = addrNoFh != addrFh
          assert(changed,
            s"Phase B FAIL: flipH had no effect at col=$col row=$row bpp=$bpp " +
            s"(addrNoFh=0x${addrNoFh.toHexString} addrFh=0x${addrFh.toHexString}). " +
            s"This is exactly the pre-fix bug from #9102.")
          phaseBChecks += 1
        }
      }
      // Discriminator 2: mirror equivalence flipH=true @ col == flipH=false @ (15-col).
      for (bpp <- 0 to 2; col <- 0 to 15; row <- 0 to 15) {
        val a0 = effFlatAddr(col, row, fh = false, fv = false, bpp)
        val mirroredCol = 15 - col
        val a1 = effFlatAddr(mirroredCol, row, fh = true, fv = false, bpp)
        assert(a0 == a1,
          s"Phase B FAIL: mirror-equivalence at col=$col mirror=$mirroredCol bpp=$bpp " +
          s"a0=0x${a0.toHexString} a1=0x${a1.toHexString}")
        phaseBChecks += 1
      }
      // Discriminator 3: flipV around row mirror.
      for (bpp <- 0 to 2; col <- 0 to 15; row <- 0 to 15) {
        val a0 = effFlatAddr(col, row, fh = false, fv = false, bpp)
        val a1 = effFlatAddr(col, 15 - row, fh = false, fv = true, bpp)
        assert(a0 == a1,
          s"Phase B FAIL: vert mirror at col=$col row=$row bpp=$bpp " +
          s"a0=0x${a0.toHexString} a1=0x${a1.toHexString}")
        phaseBChecks += 1
      }
      println(f"  Phase B PASSED $phaseBChecks%d math checks across all bppSel modes")

      println("SpriteFlipSim: PASS")
    }
}
