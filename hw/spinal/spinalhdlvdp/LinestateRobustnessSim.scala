package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BH-6 unit sim: LinestateStore commit/write contention robustness.
  *
  * Verifies the write-vs-commitStrobe collision handling added in BH-6.
  *
  * Cases:
  *   1. Normal commit (no write) — readAsync of prepare flows to commit
  *      unchanged.
  *   2. Write to prepare in a non-commit cycle, then commit on a later
  *      cycle — the new value reaches commit on that later cycle.
  *   3. Write to prepare during a commit cycle but to a DIFFERENT line —
  *      the commit reads the unchanged commitLine entry; the write
  *      lands in prepare for use on a future commit. No race because
  *      addresses differ.
  *   4. Write to prepare during a commit cycle to the SAME line as
  *      commitLine — the new write data is forwarded into commit
  *      directly (BH-6 collision-forward). Pre-BH-6 behavior would have
  *      been read-undefined; post-BH-6 the new value is guaranteed.
  *   5. Commit without writeEnable but writeAddr happens to equal
  *      commitLine — no collision (writeEnable is False), commit reads
  *      stored value as in case 1.
  */
object LinestateRobustnessSim extends App {
  Config.sim.compile(LinestateStore(lineCount = 64)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.writeAddr   #= 0
    dut.io.writeData   #= 0
    dut.io.writeEnable #= false
    dut.io.commitLine  #= 0
    dut.io.commitStrobe #= false
    dut.io.readAddr    #= 0
    dut.clockDomain.waitSampling(2)

    /** Drive a single one-cycle write to prepare. */
    def writeOnly(addr: Int, data: Int): Unit = {
      dut.io.writeAddr   #= addr
      dut.io.writeData   #= data
      dut.io.writeEnable #= true
      dut.clockDomain.waitSampling()
      dut.io.writeEnable #= false
      dut.io.writeAddr   #= 0
      dut.io.writeData   #= 0
      dut.clockDomain.waitSampling()
    }

    /** Drive a single one-cycle commit on `line`. */
    def commitOnly(line: Int): Unit = {
      dut.io.commitLine   #= line
      dut.io.commitStrobe #= true
      dut.clockDomain.waitSampling()
      dut.io.commitStrobe #= false
      dut.io.commitLine   #= 0
      dut.clockDomain.waitSampling()
    }

    /** Drive write + commit in the same cycle. */
    def writeAndCommit(writeAddr: Int, writeData: Int, commitLine: Int): Unit = {
      dut.io.writeAddr    #= writeAddr
      dut.io.writeData    #= writeData
      dut.io.writeEnable  #= true
      dut.io.commitLine   #= commitLine
      dut.io.commitStrobe #= true
      dut.clockDomain.waitSampling()
      dut.io.writeEnable  #= false
      dut.io.commitStrobe #= false
      dut.io.writeAddr    #= 0
      dut.io.writeData    #= 0
      dut.io.commitLine   #= 0
      dut.clockDomain.waitSampling()
    }

    /** Read the commit-side record at line `line`. Returns (l0en, l1en, l0sx). */
    def readCommit(line: Int): (Boolean, Boolean, Int) = {
      dut.io.readAddr #= line
      dut.clockDomain.waitSampling()  // settle async read
      dut.clockDomain.waitSampling()
      val l0 = dut.io.layer0Enable.toBoolean
      val l1 = dut.io.layer1Enable.toBoolean
      val sx = dut.io.layer0ScrollX.toInt
      (l0, l1, sx)
    }

    def packed(l0en: Boolean, l1en: Boolean, l0sx: Int): Int =
      LinestateStore.packRecord(l0en, l1en, l0sx).toInt

    // --- Case 1: normal commit propagates init defaults --------------------
    // Init for line 5: l0=true, l1=true, sx=0 (per defaultInit).
    commitOnly(5)
    val c1 = readCommit(5)
    assert(c1 == (true, true, 0), s"Case 1: expected (T,T,0), got $c1")
    println(s"[sim] Case 1 normal commit propagates init values — OK")

    // --- Case 2: separate write, then commit on later cycle ----------------
    writeOnly(10, packed(false, true, 0x123))   // l0=F, l1=T, sx=0x123
    commitOnly(10)
    val c2 = readCommit(10)
    assert(c2 == (false, true, 0x123), s"Case 2: expected (F,T,0x123), got $c2")
    println(s"[sim] Case 2 sequential write+commit — OK")

    // --- Case 3: same-cycle write+commit, DIFFERENT lines -------------------
    // Pre-state: line 20 has init values (l0=T, l1=T, sx=0). Write to line 30
    // while committing line 20. Line 20's commit must reflect its INIT
    // (not the line-30 write). Line 30's prepare gets the write.
    writeAndCommit(writeAddr = 30, writeData = packed(true, false, 0x055), commitLine = 20)
    val c3a = readCommit(20)
    assert(c3a == (true, true, 0), s"Case 3a: line 20 commit must hold init (T,T,0), got $c3a")
    // Now commit line 30 separately to surface the deferred write.
    commitOnly(30)
    val c3b = readCommit(30)
    assert(c3b == (true, false, 0x055), s"Case 3b: line 30 commit must show new write, got $c3b")
    println(s"[sim] Case 3 different-line write+commit (no race, deferred update) — OK")

    // --- Case 4: same-cycle write+commit, SAME line — BH-6 forward --------
    // Pre-state: line 40 has init (T,T,0). Write line 40 while committing
    // line 40. Per BH-6 collision-forward, commit must show the NEW value.
    writeAndCommit(writeAddr = 40, writeData = packed(false, false, 0x2A8), commitLine = 40)
    val c4 = readCommit(40)
    assert(c4 == (false, false, 0x2A8),
      s"Case 4: same-line collision should forward write into commit, got $c4")
    println(s"[sim] Case 4 same-line collision forwards new write into commit — OK")

    // --- Case 5: commit without writeEnable, ignore stale writeAddr -------
    // Drive writeAddr=50 but writeEnable=false; commit line 50. Commit must
    // see stored prepare value (init), not the bus garbage.
    dut.io.writeAddr    #= 50
    dut.io.writeData    #= packed(false, false, 0x3FF)
    dut.io.writeEnable  #= false                // KEY: not enabled
    dut.io.commitLine   #= 50
    dut.io.commitStrobe #= true
    dut.clockDomain.waitSampling()
    dut.io.commitStrobe #= false
    dut.io.writeAddr    #= 0
    dut.clockDomain.waitSampling()
    val c5 = readCommit(50)
    assert(c5 == (true, true, 0),
      s"Case 5: writeEnable=false must not affect commit, got $c5")
    println(s"[sim] Case 5 writeEnable=false is not a collision — OK")

    println("[sim] LinestateRobustnessSim: PASS")
  }
}
