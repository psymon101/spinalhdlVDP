package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R3 FetchSlotScheduler validation sim (Reading B — tile-only scope).
  *
  * Covers every bullet in TASK_R3_FETCH_SLOT_SCHEDULER.md §11:
  *   1. Single slot: grants once at startH
  *   2. Multiple slots: round-robin through 2-4 slots
  *   3. Pre-announce timing: grant arrives 1 cycle after pre-announce
  *   4. Window boundaries: no grant outside startH/endH
  *   5. Disabled slot skip: disabled slots don't consume bandwidth
  *   6. Budget accounting: lineGrantCount tracks grants per line
  *   7. Slot A/B round-robin with same client (Reading B adjustment)
  *   8. End-of-line-strobe equivalence: scheduler mimics the reactive strobe
  */
object FetchSlotSchedulerSim extends App {
  Config.sim.compile(FetchSlotScheduler(slotCount = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def step(n: Int = 1): Unit = for (_ <- 0 until n) dut.clockDomain.waitSampling()

    def disableAll(): Unit = {
      for (i <- 0 until 8) {
        dut.io.schedule(i).enabled  #= false
        dut.io.schedule(i).clientId #= 0
        dut.io.schedule(i).startH   #= 0
        dut.io.schedule(i).endH     #= 0
      }
    }

    def setSlot(i: Int, en: Boolean, client: Int, startH: Int, endH: Int): Unit = {
      dut.io.schedule(i).enabled  #= en
      dut.io.schedule(i).clientId #= client
      dut.io.schedule(i).startH   #= startH
      dut.io.schedule(i).endH     #= endH
    }

    // Walk hCounter over [0, hTotal) once, pulsing lineStart at h=0.
    // Returns list of (hCounter-cycle-observed, grant, preAnnounce, clientId, lineGrantCount).
    def walkLine(hTotal: Int = 800): Seq[(Int, Boolean, Boolean, Int, Int)] = {
      val log = scala.collection.mutable.ArrayBuffer[(Int, Boolean, Boolean, Int, Int)]()
      for (h <- 0 until hTotal) {
        dut.io.hCounter  #= h
        dut.io.lineStart #= (h == 0)
        step()
        log += ((h,
                 dut.io.grant.toBoolean,
                 dut.io.preAnnounce.toBoolean,
                 dut.io.grantClientId.toInt,
                 dut.io.lineGrantCount.toInt))
      }
      dut.io.lineStart #= false
      log.toSeq
    }

    // Defaults
    disableAll()
    dut.io.hCounter  #= 0
    dut.io.lineStart #= false
    step(2)

    // --- Case 1: single slot grants once at startH ---
    disableAll()
    setSlot(0, en = true, client = 0, startH = 100, endH = 103)
    step()
    val log1 = walkLine()
    val grantCycles1 = log1.filter(_._2).map(_._1)
    assert(grantCycles1 == Seq(100), s"case1: expected grant at hCounter=100, got $grantCycles1")
    assert(log1.last._5 == 1, s"case1: expected lineGrantCount=1, got ${log1.last._5}")
    println("[sim] case1 single slot grants once at startH — OK")

    // --- Case 2: round-robin through 3 slots ---
    disableAll()
    setSlot(0, en = true, client = 0, startH = 50,  endH = 60)
    setSlot(1, en = true, client = 0, startH = 200, endH = 210)
    setSlot(2, en = true, client = 0, startH = 500, endH = 510)
    step()
    val log2 = walkLine()
    val grants2 = log2.filter(_._2).map(_._1)
    assert(grants2 == Seq(50, 200, 500), s"case2: expected grants at 50,200,500, got $grants2")
    assert(log2.last._5 == 3, s"case2: lineGrantCount expected 3, got ${log2.last._5}")
    println("[sim] case2 round-robin 3 slots — OK")

    // --- Case 3: pre-announce arrives exactly 1 cycle before grant ---
    disableAll()
    setSlot(0, en = true, client = 1, startH = 400, endH = 405)
    step()
    val log3 = walkLine()
    val preCycles = log3.filter(_._3).map(_._1)
    val grantCycles3 = log3.filter(_._2).map(_._1)
    assert(preCycles == Seq(399), s"case3: expected preAnnounce at 399, got $preCycles")
    assert(grantCycles3 == Seq(400), s"case3: expected grant at 400, got $grantCycles3")
    println("[sim] case3 pre-announce 1 cycle before grant — OK")

    // --- Case 4: window boundaries — slotValid inside [startH, endH], no grant outside ---
    disableAll()
    setSlot(0, en = true, client = 0, startH = 100, endH = 105)
    step()
    dut.io.lineStart #= true
    dut.io.hCounter  #= 0
    step()
    dut.io.lineStart #= false
    var slotValidCount = 0
    for (h <- 0 until 800) {
      dut.io.hCounter #= h
      step()
      val inWindow = h >= 100 && h <= 105
      assert(dut.io.slotValid.toBoolean == inWindow,
        s"case4: at h=$h expected slotValid=$inWindow got ${dut.io.slotValid.toBoolean}")
      if (dut.io.slotValid.toBoolean) slotValidCount += 1
      if (dut.io.grant.toBoolean) assert(inWindow, s"case4: grant outside window at h=$h")
    }
    assert(slotValidCount == 6, s"case4: slotValid cycles expected 6, got $slotValidCount")
    println("[sim] case4 window boundaries — OK")

    // --- Case 5: disabled slot skip ---
    disableAll()
    setSlot(0, en = false, client = 0, startH = 100, endH = 110)
    setSlot(1, en = true,  client = 0, startH = 300, endH = 310)
    step()
    val log5 = walkLine()
    val grants5 = log5.filter(_._2).map(_._1)
    assert(grants5 == Seq(300), s"case5: disabled slot fired; grants=$grants5")
    println("[sim] case5 disabled slot skipped — OK")

    // --- Case 6: budget accounting / lineGrantCount resets on lineStart ---
    disableAll()
    setSlot(0, en = true, client = 0, startH = 50,  endH = 55)
    setSlot(1, en = true, client = 0, startH = 150, endH = 155)
    step()
    val log6a = walkLine()
    assert(log6a.last._5 == 2, s"case6: line1 count expected 2, got ${log6a.last._5}")
    val log6b = walkLine()
    assert(log6b.last._5 == 2, s"case6: line2 count expected 2 (reset), got ${log6b.last._5}")
    // Snapshot intermediate: after first grant but before second, count must be 1
    val afterFirst = log6a.find(r => r._1 == 100).get
    assert(afterFirst._5 == 1, s"case6: mid-line count at h=100 expected 1, got ${afterFirst._5}")
    println("[sim] case6 budget accounting across lines — OK")

    // --- Case 7: Reading B — slot A and slot B both clientId=0 (tile only), round-robin ---
    disableAll()
    setSlot(0, en = true, client = 0, startH = 100, endH = 110)
    setSlot(1, en = true, client = 0, startH = 400, endH = 410)
    step()
    val log7 = walkLine()
    val grants7 = log7.filter(_._2)
    assert(grants7.map(_._1) == Seq(100, 400), s"case7: grants=${grants7.map(_._1)}")
    assert(grants7.forall(_._4 == 0), s"case7: all grants should be clientId=0")
    println("[sim] case7 same-client multi-slot round-robin — OK")

    // --- Case 8: end-of-line strobe equivalence ---
    // Configuring a single slot at startH=799 (hTotal-1) produces exactly the
    // same 1-cycle pulse at end-of-line as the pre-R3 reactive strobe. This is
    // the configuration the VdpTop integration uses for no-regression.
    disableAll()
    setSlot(0, en = true, client = 0, startH = 799, endH = 799)
    step()
    val log8 = walkLine()
    val grants8 = log8.filter(_._2).map(_._1)
    assert(grants8 == Seq(799), s"case8: end-of-line equivalence — expected grant at 799, got $grants8")
    val pre8 = log8.filter(_._3).map(_._1)
    assert(pre8 == Seq(798), s"case8: preAnnounce expected at 798, got $pre8")
    println("[sim] case8 end-of-line strobe equivalence (mimics pre-R3 reactive) — OK")

    println("[sim] FetchSlotSchedulerSim: PASS")
  }
}
