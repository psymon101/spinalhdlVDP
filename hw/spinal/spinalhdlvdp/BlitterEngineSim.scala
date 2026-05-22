package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 49 — BlitterEngine unit sim.
  *
  * Covers the 7-case validation matrix from TASK_49_BLITTER_CLASS_BLOCK_TRANSFER_ENGINE.md §5.1:
  *
  *   1. RECT_FILL 4×3 region with constant, DST_STRIDE = 4 (packed rows).
  *   2. RECT_COPY 2×2 from source RAM (offsets 0,1,4,5) to packed destination.
  *   3. Strided copy: SRC_STRIDE=8, DST_STRIDE=8, WIDTH=3, HEIGHT=2 (row gap).
  *   4. Pause-under-busBusy: assert busy mid-transfer, verify counter holds.
  *   5. LINE_FILL: mode=2, WIDTH=7, HEIGHT=don't-care → 8 consecutive writes.
  *   6. Done pulse is exactly one cycle wide.
  *   7. Zero-size (WIDTH=0, HEIGHT=0) → single write.
  */
object BlitterEngineSim extends App {
  Config.sim.compile(BlitterEngine()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.busAddr #= 0
    dut.io.busData #= 0
    dut.io.busWr   #= false
    dut.io.busBusy #= false
    dut.clockDomain.waitSampling(3)

    def busWrite(addr: Int, data: Int): Unit = {
      dut.io.busAddr #= addr
      dut.io.busData #= data & 0xFFFF
      dut.io.busWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.busWr   #= false
      dut.clockDomain.waitSampling()
    }

    // Control-register setters (per artifact §3.2).
    def setCtrl(mode: Int, go: Boolean): Unit =
      busWrite(0x0C00, (if (go) 1 else 0) | ((mode & 3) << 1))
    def setWidth(v: Int)     = busWrite(0x0C01, v & 0x3FF)
    def setHeight(v: Int)    = busWrite(0x0C02, v & 0x3FF)
    def setDstAddr(v: Int)   = busWrite(0x0C03, v & 0x7FFF)
    def setDstStride(v: Int) = busWrite(0x0C04, v & 0x7FFF)
    def setSrcAddr(v: Int)   = busWrite(0x0C05, v & 0x1FF)
    def setSrcStride(v: Int) = busWrite(0x0C06, v & 0x1FF)
    def setFillVal(v: Int)   = busWrite(0x0C07, v & 0xFFFF)
    def writeSrcRam(slot: Int, data: Int) = busWrite(0x0C10 + slot, data)

    // Capture writes live via an onSamplings callback — mirrors DmaEngineSim
    // so every cycle with blitWr=1 is recorded in (addr, data) order.
    val liveRecords = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var liveDone = false
    var doneCycleIdx = -1
    var cycleIdx = 0
    dut.clockDomain.onSamplings {
      if (dut.io.blitWr.toBoolean) {
        liveRecords += ((dut.io.blitAddr.toInt, dut.io.blitData.toInt))
      }
      if (dut.io.done.toBoolean) {
        liveDone = true
        doneCycleIdx = cycleIdx
      }
      cycleIdx += 1
    }

    var xferStartIdx = 0
    def armTransfer(): Unit = {
      xferStartIdx = liveRecords.size
      liveDone = false
      doneCycleIdx = -1
    }
    def collectTransfer(maxCycles: Int = 5000): Seq[(Int, Int)] = {
      var cycles = 0
      while (!liveDone && cycles < maxCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(liveDone, s"Transfer did not complete within $maxCycles cycles")
      liveRecords.slice(xferStartIdx, liveRecords.size).toSeq
    }

    // --- Case 1: RECT_FILL 4x3 packed ---
    setFillVal(0xABCD)
    setWidth(3)            // cols = 4
    setHeight(2)           // rows = 3
    setDstAddr(0x0800)
    setDstStride(4)        // packed
    armTransfer()
    setCtrl(mode = 0, go = true)
    val rec1 = collectTransfer()
    assert(rec1.length == 12, s"Case 1 expected 12 writes, got ${rec1.length}")
    for (row <- 0 until 3; col <- 0 until 4) {
      val idx = row * 4 + col
      val expAddr = 0x0800 + row * 4 + col
      assert(rec1(idx)._1 == expAddr,
        f"Case 1 write $idx expected addr 0x${expAddr}%04X got 0x${rec1(idx)._1}%04X")
      assert(rec1(idx)._2 == 0xABCD,
        f"Case 1 write $idx data 0x${rec1(idx)._2}%04X")
    }
    println("[sim] Case 1 RECT_FILL 4x3 packed with 0xABCD — OK")

    // --- Case 2: RECT_COPY 2x2, source offsets 0,1,4,5 -> packed destination ---
    writeSrcRam(0, 0x1111)
    writeSrcRam(1, 0x2222)
    writeSrcRam(4, 0x3333)
    writeSrcRam(5, 0x4444)
    setWidth(1)             // cols = 2
    setHeight(1)            // rows = 2
    setDstAddr(0x0900)
    setDstStride(2)         // packed
    setSrcAddr(0)
    setSrcStride(4)         // source row stride = 4
    armTransfer()
    setCtrl(mode = 1, go = true)
    val rec2 = collectTransfer()
    assert(rec2.length == 4, s"Case 2 expected 4 writes, got ${rec2.length}")
    val exp2 = Seq(
      (0x0900, 0x1111),
      (0x0901, 0x2222),
      (0x0902, 0x3333),
      (0x0903, 0x4444)
    )
    for (i <- 0 until 4) {
      assert(rec2(i) == exp2(i),
        f"Case 2 write $i expected (0x${exp2(i)._1}%04X, 0x${exp2(i)._2}%04X) got (0x${rec2(i)._1}%04X, 0x${rec2(i)._2}%04X)")
    }
    println("[sim] Case 2 RECT_COPY 2x2 from source RAM — OK")

    // --- Case 3: Strided copy, SRC_STRIDE=DST_STRIDE=8, 4 cols, 2 rows ---
    // src RAM contents at offsets 0..3 and 8..11
    for (i <- 0 until 4)  writeSrcRam(i,     0xA000 | i)
    for (i <- 0 until 4)  writeSrcRam(8 + i, 0xB000 | i)
    setWidth(3)             // cols = 4
    setHeight(1)            // rows = 2
    setDstAddr(0x0A00)
    setDstStride(8)
    setSrcAddr(0)
    setSrcStride(8)
    armTransfer()
    setCtrl(mode = 1, go = true)
    val rec3 = collectTransfer()
    assert(rec3.length == 8, s"Case 3 expected 8 writes, got ${rec3.length}")
    val exp3 = (0 until 4).map(i => (0x0A00 + i, 0xA000 | i)) ++
               (0 until 4).map(i => (0x0A08 + i, 0xB000 | i))
    for (i <- 0 until 8) {
      assert(rec3(i) == exp3(i),
        f"Case 3 write $i expected (0x${exp3(i)._1}%04X, 0x${exp3(i)._2}%04X) got (0x${rec3(i)._1}%04X, 0x${rec3(i)._2}%04X)")
    }
    println("[sim] Case 3 strided RECT_COPY 4x2 with row-gap=4 — OK")

    // --- Case 4: Pause under busBusy mid-transfer ---
    setWidth(3)             // cols = 4
    setHeight(2)            // rows = 3 → 12 writes
    setDstAddr(0x0B00)
    setDstStride(4)
    setFillVal(0xCAFE)
    armTransfer()
    setCtrl(mode = 0, go = true)
    var pauseSeen = false
    var phase = 0          // 0=pre-pause, 1=busy-held, 2=resumed
    var busyRemain = 4
    var cyc4 = 0
    val maxCyc4 = 500
    while (!liveDone && cyc4 < maxCyc4) {
      val writes = liveRecords.size - xferStartIdx
      if (phase == 0 && writes >= 3) {
        dut.io.busBusy #= true
        phase = 1
      } else if (phase == 1) {
        if (!dut.io.blitWr.toBoolean) pauseSeen = true
        busyRemain -= 1
        if (busyRemain <= 0) {
          dut.io.busBusy #= false
          phase = 2
        }
      }
      dut.clockDomain.waitSampling()
      cyc4 += 1
    }
    val rec4 = liveRecords.slice(xferStartIdx, liveRecords.size).toSeq
    assert(liveDone,        "Case 4 did not complete")
    assert(pauseSeen,       "Case 4 never observed a paused cycle")
    assert(rec4.length == 12, s"Case 4 expected 12 total writes, got ${rec4.length}")
    for (row <- 0 until 3; col <- 0 until 4) {
      val idx = row * 4 + col
      val expAddr = 0x0B00 + row * 4 + col
      assert(rec4(idx)._1 == expAddr,
        f"Case 4 write $idx expected addr 0x${expAddr}%04X got 0x${rec4(idx)._1}%04X")
      assert(rec4(idx)._2 == 0xCAFE, f"Case 4 write $idx data 0x${rec4(idx)._2}%04X")
    }
    println("[sim] Case 4 Pause-under-busBusy: counters held, 12/12 writes preserved — OK")

    // --- Case 5: LINE_FILL (mode=2) — treats HEIGHT as 0 internally ---
    setWidth(7)             // cols = 8
    setHeight(5)            // intentionally non-zero; LINE_FILL must override
    setDstAddr(0x0C00)      // NOTE: reuses our own control-range base, but
                            // blitter only *reads* via self-decode of busWr,
                            // and only *writes* to dst. No self-recursion
                            // risk in this sim (busBusy stays low).
    setDstStride(1)
    setFillVal(0xBEEF)
    armTransfer()
    setCtrl(mode = 2, go = true)
    val rec5 = collectTransfer()
    assert(rec5.length == 8, s"Case 5 expected 8 writes, got ${rec5.length}")
    for (i <- 0 until 8) {
      assert(rec5(i)._1 == 0x0C00 + i,
        f"Case 5 write $i expected addr 0x${0x0C00 + i}%04X got 0x${rec5(i)._1}%04X")
      assert(rec5(i)._2 == 0xBEEF, f"Case 5 write $i data 0x${rec5(i)._2}%04X")
    }
    println("[sim] Case 5 LINE_FILL: HEIGHT ignored, 8 linear writes — OK")

    // --- Case 6: Done pulse is exactly one cycle wide ---
    setWidth(0)
    setHeight(0)
    setDstAddr(0x0D00)
    setDstStride(1)
    setFillVal(0xDEAD)
    armTransfer()
    setCtrl(mode = 0, go = true)
    val rec6 = collectTransfer()
    assert(rec6.length == 1, s"Case 6 expected 1 write, got ${rec6.length}")
    // After collectTransfer returns, liveDone was latched this cycle.
    // Advance one more cycle and ensure io.done has fallen.
    dut.clockDomain.waitSampling()
    assert(!dut.io.done.toBoolean, "Case 6: done did not deassert after one cycle")
    println("[sim] Case 6 done-pulse width = 1 cycle — OK")

    // --- Case 7: Zero-size (WIDTH=0, HEIGHT=0) → single write ---
    setWidth(0)
    setHeight(0)
    setDstAddr(0x0E00)
    setDstStride(1)
    setFillVal(0xFEED)
    armTransfer()
    setCtrl(mode = 0, go = true)
    val rec7 = collectTransfer()
    assert(rec7.length == 1, s"Case 7 expected 1 write, got ${rec7.length}")
    assert(rec7(0) == (0x0E00, 0xFEED), s"Case 7 wrong: ${rec7(0)}")
    println("[sim] Case 7 zero-size → single write + done — OK")

    // --- Case 8: RECT_COPY under busBusy — exercises the readSync
    // lookahead stall path (srcReadAddr must hold the current column
    // while the FSM is paused, so resumed writes still read correct src).
    for (i <- 0 until 4) writeSrcRam(i,     0xC000 | i)
    for (i <- 0 until 4) writeSrcRam(4 + i, 0xD000 | i)
    setWidth(3)             // cols = 4
    setHeight(1)            // rows = 2 → 8 writes
    setDstAddr(0x0F00)
    setDstStride(4)
    setSrcAddr(0)
    setSrcStride(4)
    armTransfer()
    setCtrl(mode = 1, go = true)
    var pauseSeen8 = false
    var phase8 = 0
    var busyRemain8 = 4
    var cyc8 = 0
    while (!liveDone && cyc8 < 500) {
      val writes = liveRecords.size - xferStartIdx
      if (phase8 == 0 && writes >= 3) {
        dut.io.busBusy #= true
        phase8 = 1
      } else if (phase8 == 1) {
        if (!dut.io.blitWr.toBoolean) pauseSeen8 = true
        busyRemain8 -= 1
        if (busyRemain8 <= 0) { dut.io.busBusy #= false; phase8 = 2 }
      }
      dut.clockDomain.waitSampling()
      cyc8 += 1
    }
    val rec8 = liveRecords.slice(xferStartIdx, liveRecords.size).toSeq
    assert(liveDone, "Case 8 did not complete")
    assert(pauseSeen8, "Case 8 never observed a paused cycle")
    assert(rec8.length == 8, s"Case 8 expected 8 writes, got ${rec8.length}")
    val exp8 = (0 until 4).map(i => (0x0F00 + i, 0xC000 | i)) ++
               (0 until 4).map(i => (0x0F04 + i, 0xD000 | i))
    for (i <- 0 until 8) {
      assert(rec8(i) == exp8(i),
        f"Case 8 write $i expected (0x${exp8(i)._1}%04X, 0x${exp8(i)._2}%04X) got (0x${rec8(i)._1}%04X, 0x${rec8(i)._2}%04X)")
    }
    println("[sim] Case 8 RECT_COPY under busBusy: lookahead holds, 8/8 src reads correct — OK")

    println("[sim] BlitterEngineSim: PASS")
  }
}
