package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 44b Checkpoint A sim — linear BitmapRowFetch addressing proof.
  *
  * Pre-populated ROM contents (must mirror `BitmapRowFetch.initData`):
  *   - bitmap region [0, storeBytes/2):  byte[i] = (i[7:0] XOR i[15:8])
  *   - attribute region [storeBytes/2, end):  byte[i] = i[7:0]
  *
  * Verified:
  *   1. bitmapByte at (line, col) = byte[bitmapBase + line×stride + col/8]
  *   2. attrByte   at (line, col) = byte[attrBase + line×stride + col/cellWidth]
  *   3. stride changes shift the line boundary as expected.
  *   4. col-to-byte division is byte-accurate across 8-pixel windows.
  */
object BitmapRowFetchSim extends App {
  Config.sim.compile(BitmapRowFetch(storeBytes = 1024, cellShift = 3)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Place bitmap region at 0, attributes at 512.
    val bitmapBase   = 0
    val attrBase     = 512
    val bitmapStride = 80     // 640 px / 8 bits = 80 bytes per row
    val attrStride   = 80     // (same cell count for this sim)

    dut.io.bitmapBase   #= bitmapBase
    dut.io.attrBase     #= attrBase
    dut.io.bitmapStride #= bitmapStride
    dut.io.attrStride   #= attrStride
    dut.io.fetchLine    #= 0
    dut.io.col          #= 0
    dut.clockDomain.waitSampling(3)

    def expBitmap(line: Int, col: Int): Int = {
      val i = (bitmapBase + line * bitmapStride + (col / 8)) & (1024 - 1)
      if (i < 512) (i & 0xFF) ^ ((i >> 8) & 0xFF) else 0  // bitmap half only
    }
    def expAttr(line: Int, col: Int): Int = {
      val i = (attrBase + line * attrStride + (col / 8)) & (1024 - 1)
      if (i >= 512) i & 0xFF else 0
    }

    // === Case 1: line 0, stepping col 0..79*8 ===
    var total = 0
    for (col <- 0 to 79 * 8 by 8) {
      dut.io.fetchLine #= 0
      dut.io.col       #= col
      dut.clockDomain.waitSampling(); sleep(1)
      val bGot = dut.io.bitmapByte.toInt & 0xFF
      val bExp = expBitmap(0, col)
      val aGot = dut.io.attrByte.toInt & 0xFF
      val aExp = expAttr(0, col)
      assert(bGot == bExp, s"case1 line=0 col=$col bitmap got 0x${bGot.toHexString} exp 0x${bExp.toHexString}")
      assert(aGot == aExp, s"case1 line=0 col=$col attr got 0x${aGot.toHexString} exp 0x${aExp.toHexString}")
      total += 1
    }
    println(s"[sim] case1 line=0 sweep ($total byte positions) — OK")

    // === Case 2: line 3, verify byte indices shift by 3*stride ===
    for (col <- Seq(0, 64, 200, 504)) {
      dut.io.fetchLine #= 3
      dut.io.col       #= col
      dut.clockDomain.waitSampling(); sleep(1)
      val bGot = dut.io.bitmapByte.toInt & 0xFF
      val bExp = expBitmap(3, col)
      assert(bGot == bExp, s"case2 line=3 col=$col bitmap got 0x${bGot.toHexString} exp 0x${bExp.toHexString}")
    }
    println("[sim] case2 line=3 sample points — OK")

    // === Case 3: within-cell stability (col 0..7 all read same byte) ===
    dut.io.fetchLine #= 1
    dut.io.col       #= 0
    dut.clockDomain.waitSampling(); sleep(1)
    val baseByte = dut.io.bitmapByte.toInt & 0xFF
    for (col <- 1 until 8) {
      dut.io.col #= col
      dut.clockDomain.waitSampling(); sleep(1)
      val g = dut.io.bitmapByte.toInt & 0xFF
      assert(g == baseByte, s"case3 col=$col: byte unstable within cell (got 0x${g.toHexString} exp 0x${baseByte.toHexString})")
    }
    println("[sim] case3 within-cell stability (cols 0..7) — OK")

    // === Case 4: stride changes shift byte position ===
    dut.io.bitmapStride #= 40           // half stride
    dut.io.fetchLine    #= 2
    dut.io.col          #= 0
    dut.clockDomain.waitSampling(); sleep(1)
    val bGot4 = dut.io.bitmapByte.toInt & 0xFF
    val i4    = (bitmapBase + 2 * 40 + 0) & 0x1FF   // half stride
    val bExp4 = (i4 & 0xFF) ^ ((i4 >> 8) & 0xFF)
    assert(bGot4 == bExp4, s"case4 stride change got 0x${bGot4.toHexString} exp 0x${bExp4.toHexString}")
    println("[sim] case4 stride change — OK")

    println("[sim] BitmapRowFetchSim: PASS")
  }
}
