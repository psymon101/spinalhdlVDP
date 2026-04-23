package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 47 — DmaEngine unit sim.
  *
  * Covers all five validation cases required by the Task 47 artifact
  * (PROJECT_PLAN/artifacts/TASK_47_DMA_STYLE_TRANSFER_PRIMITIVE.md §6.1.1)
  * and CyanPeak #8208's explicit note to prove lowest-priority / pause
  * behaviour under overlapping external writes:
  *
  *   A. FILL — 8-word fill of 0xABCD into 0x0800..0x0807.
  *   B. COPY — 4-word copy from staging[0..3] into 0x0900..0x0903.
  *   C. Pause-on-external-write — start FILL, assert busBusy mid-transfer,
  *      verify counter holds and dmaWr goes low, then resumes.
  *   D. Zero-length (LEN=0) — single write, done-pulse fires.
  *   E. Done pulse — verify single-cycle assertion.
  */
object DmaEngineSim extends App {
  Config.sim.compile(DmaEngine()).doSim { dut =>
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

    def setDst(v: Int)  = busWrite(0x0B00, v & 0x7FFF)
    def setLen(v: Int)  = busWrite(0x0B01, v & 0x3FF)
    def setFill(v: Int) = busWrite(0x0B02, v & 0xFFFF)
    def kickCtrl(mode: Int): Unit = {
      // bit0 = go (self-clearing), bit1 = mode (0=FILL, 1=COPY).
      busWrite(0x0B03, 0x0001 | ((mode & 1) << 1))
    }
    def writeStaging(slot: Int, data: Int) = busWrite(0x0B10 + slot, data)

    // Collect DMA writes as (addr, data) for validation. Sample the
    // outputs as they stand *now* (set up by the previous waitSampling in
    // busWrite / kickCtrl), then advance. Otherwise we skip the first
    // RUN cycle's write.
    // Capture dmaWr assertions via a `doAtRisingEdge` callback that
    // samples combinational io_dmaWr at the instant the rising edge
    // occurs (i.e., before any register updates propagate). This gives a
    // deterministic view of every cycle where io_dmaWr was high, without
    // depending on Scala-side waitSampling/sleep alignment.
    // Install a sampling-edge observer for io.dmaWr. Each transfer test
    // calls `armTransfer()` before kickCtrl to snapshot the counter and
    // clear liveDone; `collectTransfer()` waits for liveDone, then returns
    // the records accumulated since the snapshot.
    val liveRecords = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var liveDone = false
    dut.clockDomain.onSamplings {
      if (dut.io.dmaWr.toBoolean) {
        liveRecords += ((dut.io.dmaAddr.toInt, dut.io.dmaData.toInt))
      }
      if (dut.io.done.toBoolean) liveDone = true
    }
    var transferStartIdx = 0
    def armTransfer(): Unit = {
      transferStartIdx = liveRecords.size
      liveDone = false
    }
    def collectTransfer(maxCycles: Int = 2000): Seq[(Int, Int)] = {
      var cycles = 0
      while (!liveDone && cycles < maxCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(liveDone, s"Transfer did not complete within $maxCycles cycles")
      liveRecords.slice(transferStartIdx, liveRecords.size).toSeq
    }

    // --- Case A: FILL ---
    setDst(0x0800)
    setLen(7)                         // length = 7 → 8 transfers
    setFill(0xABCD)
    armTransfer()
    kickCtrl(0)                       // FILL mode
    val recA = collectTransfer()
    assert(recA.length == 8, s"Case A expected 8 writes, got ${recA.length}")
    for (((addr, data), i) <- recA.zipWithIndex) {
      assert(addr == 0x0800 + i, s"Case A write $i expected addr 0x${(0x0800 + i).toHexString}, got 0x${addr.toHexString}")
      assert(data == 0xABCD,    f"Case A write $i expected data 0xABCD, got 0x${data}%04X")
    }
    println("[sim] Case A FILL 8 words of 0xABCD into 0x0800..0x0807 — OK")

    // --- Case B: COPY ---
    writeStaging(0, 0x1111)
    writeStaging(1, 0x2222)
    writeStaging(2, 0x3333)
    writeStaging(3, 0x4444)
    setDst(0x0900)
    setLen(3)                         // 4 transfers
    armTransfer()
    kickCtrl(1)                       // COPY mode
    val recB = collectTransfer()
    assert(recB.length == 4, s"Case B expected 4 writes, got ${recB.length}")
    val expectedB = Seq(0x1111, 0x2222, 0x3333, 0x4444)
    for (((addr, data), i) <- recB.zipWithIndex) {
      assert(addr == 0x0900 + i,    s"Case B write $i wrong addr: 0x${addr.toHexString}")
      assert(data == expectedB(i), f"Case B write $i expected 0x${expectedB(i)}%04X, got 0x${data}%04X")
    }
    println("[sim] Case B COPY 4 words from staging[0..3] into 0x0900..0x0903 — OK")

    // --- Case C: Pause-on-external-write ---
    setDst(0x0A00)
    setLen(7)
    setFill(0xCAFE)
    armTransfer()
    kickCtrl(0)
    // While the transfer runs, pulse busBusy after the first 2 observed
    // writes; then verify the full 8-write completion.
    var pauseConfirmed = false
    var stPhase = 0      // 0=pre-pause, 1=busy-held, 2=resumed
    var busyCyclesLeft = 3
    var cyclesC = 0
    val maxCyclesC = 300
    while (!liveDone && cyclesC < maxCyclesC) {
      val writesSoFar = liveRecords.size - transferStartIdx
      if (stPhase == 0 && writesSoFar >= 2) {
        dut.io.busBusy #= true
        stPhase = 1
      } else if (stPhase == 1) {
        if (!dut.io.dmaWr.toBoolean) pauseConfirmed = true
        busyCyclesLeft -= 1
        if (busyCyclesLeft <= 0) {
          dut.io.busBusy #= false
          stPhase = 2
        }
      }
      dut.clockDomain.waitSampling()
      cyclesC += 1
    }
    val recC = liveRecords.slice(transferStartIdx, liveRecords.size).toSeq
    assert(liveDone,           "Case C did not complete")
    assert(pauseConfirmed,     "Case C never observed a pause cycle")
    assert(recC.length == 8,   s"Case C expected 8 total writes, got ${recC.length}")
    for (((addr, data), i) <- recC.zipWithIndex) {
      assert(addr == 0x0A00 + i, s"Case C write $i addr 0x${addr.toHexString}")
      assert(data == 0xCAFE,     f"Case C write $i data 0x${data}%04X")
    }
    println("[sim] Case C Pause-on-external: busBusy held counter, DMA resumed, 8/8 writes — OK")

    // --- Case D: Zero length ---
    setDst(0x0B80)
    setLen(0)                         // single transfer
    setFill(0xFEED)
    armTransfer()
    kickCtrl(0)
    val recD = collectTransfer()
    assert(recD.length == 1, s"Case D expected 1 write, got ${recD.length}")
    assert(recD(0) == (0x0B80, 0xFEED), s"Case D wrong: ${recD(0)}")
    println("[sim] Case D zero-length → single write + done — OK")

    // --- Case E: Done pulse — one cycle only ---
    setDst(0x0C00); setLen(0); setFill(0xDEAD)
    armTransfer()
    kickCtrl(0)
    val recE = collectTransfer()
    assert(recE.length == 1, s"Case E expected 1 write, got ${recE.length}")
    // liveDone was set by the one-cycle done pulse during collectTransfer's
    // wait loop; ensuring it only asserted once requires observing that
    // io.done is low on the subsequent cycle.
    dut.clockDomain.waitSampling()
    assert(!dut.io.done.toBoolean, "Case E: done did not deassert after one cycle")
    println("[sim] Case E done pulse is one cycle wide — OK")

    println("[sim] DmaEngineSim: PASS")
  }
}
