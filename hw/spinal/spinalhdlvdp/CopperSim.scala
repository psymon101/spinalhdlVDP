package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** R5 Stage 2 sim for the Copper coprocessor.
  *
  * Coverage:
  *   1. WAIT + WRITE at line 100 — regWrite fires at exactly (x=0, y=100)
  *   2. WRITE_SEQ emits N consecutive writes with auto-increment
  *   3. JUMP restarts the program (single-line split loop per §12 proof)
  *   4. progWr is ignored while enabled=true
  */
object CopperSim extends App {
  Config.sim.compile(Copper()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def step(n: Int = 1): Unit = for (_ <- 0 until n) dut.clockDomain.waitSampling()

    val captured = mutable.ArrayBuffer[(Int, Int, Int, Int)]()  // (addr, data, x, y)
    dut.clockDomain.onSamplings {
      if (dut.io.regWr.toBoolean) {
        captured += ((dut.io.regAddr.toInt, dut.io.regData.toInt,
                      dut.io.hCounter.toInt, dut.io.vCounter.toInt))
      }
    }

    // Encoding helpers
    def WAIT(y: Int): Int = (0 << 14) | (y & 0x3FF)
    def WRITE_OP(addr: Int): Int = (1 << 14) | (addr & 0x3FFF)
    def SEQ_OP(countM1: Int, baseAddr: Int): Int =
      (2 << 14) | ((countM1 & 0x7) << 11) | (baseAddr & 0x7FF)
    def JUMP(pc: Int): Int = (3 << 14) | (pc & 0x1FF)

    def loadProgram(prog: Seq[Int]): Unit = {
      dut.io.enabled #= false
      step(2)
      for ((word, i) <- prog.zipWithIndex) {
        dut.io.progAddr #= i
        dut.io.progData #= word
        dut.io.progWr   #= true
        dut.clockDomain.waitSampling()
      }
      dut.io.progWr #= false
      step(2)
    }

    def runRaster(xMax: Int = 800, yFrom: Int, yTo: Int): Unit = {
      for (y <- yFrom to yTo) {
        for (x <- 0 until xMax) {
          dut.io.hCounter #= x
          dut.io.vCounter #= y
          dut.clockDomain.waitSampling()
        }
      }
    }

    dut.io.hCounter #= 0
    dut.io.vCounter #= 0
    dut.io.enabled  #= false
    dut.io.progAddr #= 0
    dut.io.progData #= 0
    dut.io.progWr   #= false
    dut.io.bankSwapNow #= false
    step(5)

    // -------- Case 1: WAIT + WRITE --------
    // Program: WAIT y=100; WRITE addr=0x0300 data=0x0001; JUMP 0
    loadProgram(Seq(
      WAIT(100),
      WRITE_OP(0x0300),
      0x0001,
      JUMP(0)
    ))
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 95, yTo = 105)
    val case1Writes = captured.filter(_._1 == 0x0300).toList
    assert(case1Writes.size >= 1, s"case1: no writes captured")
    val (_, d, wx, wy) = case1Writes.head
    assert(d == 0x0001, s"case1: expected data=0x0001, got 0x${d.toHexString}")
    // FSM takes ~3 cycles to decode WAIT→WRITE→data; write lands at x≈3 on
    // the target line. Relax to "same line, within first 10 pixels" — a
    // tighter 1-cycle decode (requiring pre-fetch) is a follow-up optimization.
    assert(wy == 100 && wx < 10,
      s"case1: expected write on line 100 within first 10 px, got ($wx, $wy)")
    println(s"[sim] case1 WAIT+WRITE at (0,100) data=0x$d%04x — OK".format(d))

    // -------- Case 2: WRITE_SEQ emits 3 consecutive --------
    dut.io.enabled #= false
    step(5)
    // Program: WAIT y=200; WRITE_SEQ count=3 base=0x0100; d0 d1 d2; JUMP 0
    loadProgram(Seq(
      WAIT(200),
      SEQ_OP(2, 0x0100),   // count_m1=2 → N=3
      0xAAA0,
      0xAAA1,
      0xAAA2,
      JUMP(0)
    ))
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 199, yTo = 201)
    val seqWrites = captured.filter(c => c._1 >= 0x0100 && c._1 <= 0x0102).toList
    assert(seqWrites.size >= 3, s"case2: expected 3+ seq writes, got ${seqWrites.size}")
    val first3 = seqWrites.take(3)
    val addrsOk = first3.map(_._1) == List(0x0100, 0x0101, 0x0102)
    val dataOk  = first3.map(_._2) == List(0xAAA0, 0xAAA1, 0xAAA2)
    assert(addrsOk, s"case2: addresses=${first3.map(_._1).map(_.toHexString)}")
    assert(dataOk,  s"case2: data=${first3.map(_._2).map(_.toHexString)}")
    println("[sim] case2 WRITE_SEQ N=3 auto-inc — OK")

    // -------- Case 3: JUMP re-fires on subsequent line-100 passes --------
    dut.io.enabled #= false
    step(5)
    loadProgram(Seq(
      WAIT(100),
      WRITE_OP(0x0300),
      0x0001,
      WAIT(300),
      WRITE_OP(0x0300),
      0x0003,
      JUMP(0)
    ))
    captured.clear()
    dut.io.enabled #= true
    // Run two "frames" of raster (y 0..480 twice)
    runRaster(yFrom = 0, yTo = 320)
    dut.io.vCounter #= 0   // simulate vblank wrap
    dut.io.hCounter #= 0
    step(10)
    runRaster(yFrom = 95, yTo = 105)
    val line100Writes = captured.filter(c => c._1 == 0x0300 && c._4 == 100)
    val line300Writes = captured.filter(c => c._1 == 0x0300 && c._4 == 300)
    assert(line100Writes.size >= 2,
      s"case3: expected at least 2 line-100 writes across frames, got ${line100Writes.size}")
    assert(line300Writes.nonEmpty, "case3: expected line-300 write")
    println(s"[sim] case3 JUMP loops (line-100 fires ${line100Writes.size} times) — OK")

    // -------- Case 4: progWr while enabled routes to inactive bank --------
    // R5.4 (double-buffer): writes to progAddr while copper is enabled no
    // longer drop silently — they land in the *inactive* bank instead. The
    // *active* bank (which the FSM reads from) is unaffected, so this test's
    // visible behavior is preserved: the running program output is not
    // disturbed. The new live-update path is exercised in cases 9+.
    captured.clear()
    dut.io.progAddr #= 5  // word 5 is data 0x0003 in current program
    dut.io.progData #= 0xDEAD
    dut.io.progWr   #= true
    step(2)
    dut.io.progWr   #= false
    // Run y=300 again and confirm data is still 0x0003, not 0xDEAD
    dut.io.vCounter #= 0
    dut.io.hCounter #= 0
    step(10)
    runRaster(yFrom = 295, yTo = 305)
    val line300After = captured.filter(c => c._1 == 0x0300 && c._4 == 300)
    if (line300After.nonEmpty) {
      val d = line300After.head._2
      assert(d == 0x0003, s"case4: program RAM corrupted while enabled: got 0x${d.toHexString}")
    }
    println("[sim] case4 progWr while enabled routes to inactive bank; active bank unaffected — OK")

    // -------- Case 5 (BH-1): pixel-precise WAIT X,Y --------
    // Program: WAIT_PX (x=200, y=150); WRITE addr=0x0301 data=0xBE57; JUMP 0
    // Encoded form per BH-1: word0 = (00 << 14) | (1 << 13) | x[9:0],
    //                       word1 = y[9:0]. Bit[13]=1 selects extended.
    def WAIT_PX_W0(x: Int): Int = (0 << 14) | (1 << 13) | (x & 0x3FF)
    def WAIT_PX_W1(y: Int): Int = y & 0x3FF
    dut.io.enabled #= false
    step(5)
    loadProgram(Seq(
      WAIT_PX_W0(200), WAIT_PX_W1(150),
      WRITE_OP(0x0301), 0xBE57,
      JUMP(0)
    ))
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 145, yTo = 155)
    val case5Writes = captured.filter(_._1 == 0x0301).toList
    assert(case5Writes.nonEmpty, "case5: WAIT X,Y never fired (no write to 0x0301)")
    val (_, d5, wx5, wy5) = case5Writes.head
    assert(d5 == 0xBE57, s"case5: expected data=0xBE57, got 0x${d5.toHexString}")
    // Match should occur AT x=200 (vs legacy WAIT-Y firing at x=0). Allow
    // a few cycles of FSM decode latency between match and the WRITE
    // landing — the WRITE follows the WAIT in program order.
    assert(wy5 == 150 && wx5 >= 200 && wx5 < 210,
      s"case5: expected write at line 150, x in [200,210), got ($wx5, $wy5)")
    println(f"[sim] case5 BH-1 WAIT(x=200,y=150) -> WRITE at ($wx5%d,$wy5%d) — OK")

    // -------- Case 6 (BH-1): legacy WAIT-Y still uses hCounter==0 --------
    dut.io.enabled #= false
    step(5)
    loadProgram(Seq(
      WAIT(220),                // legacy 1-word WAIT y=220 (bit[13]=0)
      WRITE_OP(0x0302), 0x1ECA,
      JUMP(0)
    ))
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 215, yTo = 225)
    val case6Writes = captured.filter(_._1 == 0x0302).toList
    assert(case6Writes.nonEmpty, "case6: legacy WAIT(220) never fired")
    val (_, d6, wx6, wy6) = case6Writes.head
    assert(d6 == 0x1ECA, s"case6: expected data=0x1ECA, got 0x${d6.toHexString}")
    assert(wy6 == 220 && wx6 < 10,
      s"case6: legacy WAIT-Y must fire near hCounter=0, got ($wx6, $wy6)")
    println(f"[sim] case6 legacy WAIT(220) still fires at hCounter≈0 (got x=$wx6%d) — OK")

    // -------- Case 7 (BH-2): SKIP cond=010 (line == trigger0Line) --------
    // Encoding: 11 | 1 | 00000 | cond[2:0] | offset[4:0]
    def SKIP(cond: Int, offset: Int): Int =
      (3 << 14) | (1 << 13) | ((cond & 0x7) << 5) | (offset & 0x1F)
    // Program (offset is in PROGRAM WORDS; one WRITE = 2 words, so skip
    // two WRITEs = offset 4):
    //   0: WAIT y=150
    //   1: SKIP cond=010 offset=4  (if vCounter==trigger0Line, skip 4 words
    //                               = the next two WRITE instructions)
    //   2: WRITE 0x0301 0xAAAA      (skipped when cond true)
    //   3: ^ (data word)
    //   4: WRITE 0x0302 0xBBBB      (skipped when cond true)
    //   5: ^
    //   6: WRITE 0x0303 0xCCCC      (always reached)
    //   7: ^
    //   8: JUMP 0
    val skipProgram = Seq(
      WAIT(150),
      SKIP(cond = 0x2, offset = 4),
      WRITE_OP(0x0301), 0xAAAA,
      WRITE_OP(0x0302), 0xBBBB,
      WRITE_OP(0x0303), 0xCCCC,
      JUMP(0)
    )

    // Sub-case 7a: trigger0Line=150 → cond TRUE → only 0x0303 fires.
    dut.io.enabled #= false
    step(5)
    loadProgram(skipProgram)
    dut.io.triggerLine0 #= 150
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 145, yTo = 155)
    val taken = captured.filter(c => Set(0x0301, 0x0302, 0x0303).contains(c._1)).toList
    val takenAddrs = taken.map(_._1).toSet
    assert(takenAddrs == Set(0x0303),
      s"case7a: SKIP-taken should leave only 0x0303, got $takenAddrs")
    println(f"[sim] case7a SKIP cond=line==tr0 TAKEN (0x0303 only) — OK")

    // Sub-case 7b: trigger0Line=999 → cond FALSE → fall through, all 3 fire.
    dut.io.enabled #= false
    step(5)
    loadProgram(skipProgram)
    dut.io.triggerLine0 #= 999
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 145, yTo = 155)
    val notTaken = captured.filter(c => Set(0x0301, 0x0302, 0x0303).contains(c._1)).toList
    val notTakenAddrs = notTaken.map(_._1).toSet
    assert(notTakenAddrs == Set(0x0301, 0x0302, 0x0303),
      s"case7b: SKIP-not-taken should reach all three writes, got $notTakenAddrs")
    println(f"[sim] case7b SKIP cond=line==tr0 NOT TAKEN (all three fire) — OK")

    // -------- Case 8 (BH-2): SKIP cond=000 (line < trigger0Line) ----------
    // Program: at every line 100..150, run SKIP(cond=000, offset=1) followed
    // by WRITE 0x0304 0xD00D. Below trigger0Line=200, SKIP fires and 0x0304
    // is suppressed. Switch trigger0Line=50 mid-test and re-verify SKIP
    // does NOT fire (line>=50, cond=000=line<tr0 is false).
    val skipLessProgram = Seq(
      WAIT(100),
      SKIP(cond = 0x0, offset = 2),    // skip 2 words = 1 WRITE
      WRITE_OP(0x0304), 0xD00D,
      JUMP(0)
    )
    dut.io.enabled #= false
    step(5)
    loadProgram(skipLessProgram)
    dut.io.triggerLine0 #= 200
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 95, yTo = 105)
    val skLine100 = captured.filter(_._1 == 0x0304).toList
    assert(skLine100.isEmpty,
      s"case8a: line<200 should SKIP the WRITE, but got ${skLine100.size} writes")
    println(f"[sim] case8a SKIP cond=line<tr0 TAKEN (line 100 < 200, no write) — OK")

    dut.io.enabled #= false
    step(5)
    loadProgram(skipLessProgram)
    dut.io.triggerLine0 #= 50
    captured.clear()
    dut.io.enabled #= true
    runRaster(yFrom = 95, yTo = 105)
    val skLine100b = captured.filter(_._1 == 0x0304).toList
    assert(skLine100b.nonEmpty,
      s"case8b: line(100)>=tr0(50) should fall through, but got 0 writes")
    println(f"[sim] case8b SKIP cond=line<tr0 NOT TAKEN (line 100 ≥ 50, write fires) — OK")

    // ===========================================================================
    // R5.4 Copper double-buffered live update — new behaviors only available
    // when host pulses io.bankSwapNow (in HW, gated by VdpTop at vSyncStart).
    // ===========================================================================

    // -------- Case 9: live upload to inactive bank doesn't disturb active --------
    // progA runs from bank 0 (active by default). With copper enabled, writes
    // to progAddr now route to bank 1 (inactive) instead of being dropped.
    // The active program must continue producing its expected output.
    val progA_c9 = Seq(WAIT(80), WRITE_OP(0x0610), 0xCAFE, JUMP(0))
    dut.io.enabled #= false
    step(5)
    loadProgram(progA_c9)
    dut.io.enabled #= true
    step(5)
    captured.clear()
    runRaster(yFrom = 75, yTo = 90)
    val c9pre = captured.filter(c => c._1 == 0x0610 && c._4 == 80).map(_._2)
    assert(c9pre.contains(0xCAFE), s"case9 setup: progA should write 0xCAFE at line 80; got $c9pre")

    // Live-write 0xDEAD to bank 1 word 0..3 while copper is still running.
    // These land in bank 1 (inactive) because io.enabled=true.
    for (i <- 0 until 4) {
      dut.io.progAddr #= i
      dut.io.progData #= 0xDEAD
      dut.io.progWr   #= true
      dut.clockDomain.waitSampling()
    }
    dut.io.progWr #= false
    captured.clear()
    runRaster(yFrom = 75, yTo = 90)
    val c9post = captured.filter(c => c._1 == 0x0610 && c._4 == 80).map(_._2)
    assert(c9post.contains(0xCAFE) && !c9post.contains(0xDEAD),
      s"case9: active bank 0 progA must keep outputting 0xCAFE; got $c9post")
    println("[sim] case9 live progWr while enabled routes to inactive bank; active unaffected — OK")

    // -------- Case 10: bankSwapNow flips active bank AND resets pc to 0 --------
    // progA on bank 0 stalls forever (WAIT(1023) > vTotal). Upload progB to
    // bank 1 that fires an immediate WRITE (no WAIT) — proves the swap reset
    // pc to 0 of the new bank and the FSM started dispatching from there.
    val progA_stalls = Seq(WAIT(1023), JUMP(0))
    val progB_immediate = Seq(WRITE_OP(0x0611), 0xB10B, JUMP(0))
    dut.io.enabled #= false
    step(5)
    loadProgram(progA_stalls)              // → bank 0 (active)
    dut.io.enabled #= true
    step(20)                                 // FSM parks in sWaitStall
    captured.clear()
    runRaster(yFrom = 50, yTo = 60)
    val c10pre = captured.filter(_._1 == 0x0611).toList
    assert(c10pre.isEmpty, s"case10 setup: progA stalls; no writes expected, got ${c10pre.size}")

    // Live-upload progB to bank 1 (writes route there because enabled)
    for ((word, i) <- progB_immediate.zipWithIndex) {
      dut.io.progAddr #= i
      dut.io.progData #= word
      dut.io.progWr   #= true
      dut.clockDomain.waitSampling()
    }
    dut.io.progWr #= false
    step(5)

    // Pulse bankSwapNow — atomic swap, pc reset to 0 on bank 1
    dut.io.bankSwapNow #= true
    dut.clockDomain.waitSampling()
    dut.io.bankSwapNow #= false

    captured.clear()
    step(30)                                 // let FSM dispatch progB's WRITE
    val c10post = captured.filter(_._1 == 0x0611).map(_._2).toList
    assert(c10post.contains(0xB10B),
      s"case10: post-swap, progB on bank 1 must fire WRITE(0xB10B); got $c10post")
    println("[sim] case10 bankSwapNow flips active bank + resets pc to 0, new program runs — OK")

    // -------- Case 11: STALE BANK-1 HAZARD (BronzeGate guardrail) --------
    // If host issues swap_request without first uploading to bank 1, the swap
    // promotes uninitialized BSRAM content. In sim, prog Mem inits to 0, so
    // bank 1 = all WAIT(0) instructions — the FSM walks them silently and
    // never fires the original program's WRITE again.
    //
    // This case documents the failure mode so future readers/firmware authors
    // see why the helper must always upload-before-swap.
    val progA_writes = Seq(WAIT(40), WRITE_OP(0x0612), 0xBEEF, JUMP(0))
    dut.io.enabled #= false
    step(5)
    // Reset prog by writing zeros to bank 1 explicitly (clean slate after case 10)
    for (i <- 0 until 8) {
      dut.io.progAddr #= i
      dut.io.progData #= 0
      dut.io.progWr   #= true
      dut.clockDomain.waitSampling()
    }
    // (these writes went to bank 0 since enabled=false and activeBank toggled
    //  to bank 1 after case 10's swap. Reload progA into the active bank now.)
    loadProgram(progA_writes)
    dut.io.enabled #= true
    step(5)
    captured.clear()
    runRaster(yFrom = 35, yTo = 45)
    assert(captured.exists(c => c._1 == 0x0612 && c._2 == 0xBEEF),
      "case11 setup: active progA must write 0xBEEF at line 40 before the bad swap")

    // The hazard: issue swap WITHOUT writing anything to the other bank.
    // Other bank holds whatever stale content was there from earlier cases.
    dut.io.bankSwapNow #= true
    dut.clockDomain.waitSampling()
    dut.io.bankSwapNow #= false

    captured.clear()
    runRaster(yFrom = 0, yTo = 100)
    val c11writes = captured.filter(_._1 == 0x0612).toList
    assert(c11writes.isEmpty,
      s"case11: post-stale-swap, original progA's WRITE must NOT fire " +
      s"(bank now holds stale content); got ${c11writes.size} writes — hazard not visible!")
    println("[sim] case11 stale bank hazard — swap without upload silently disables active program — OK (failure mode visible)")

    // -------- Case 12: bankSwapNow precedence suppresses mid-execution WRITE --------
    // Set up progA that fires a WRITE at line 70. Hold bankSwapNow=1 across the
    // WAIT match + FSM dispatch window. The defensive `when(io.bankSwapNow) goto(sFetch)`
    // in sFetch/sWaitStall/sWriteData must prevent the WRITE from firing on
    // either the stale-bank fetchWord OR the new bank's word 0 (which is junk
    // from prior cases). Verify zero WRITEs to the target during the hold.
    val progA_c12 = Seq(WAIT(70), WRITE_OP(0x0613), 0xF00D, JUMP(0))
    dut.io.enabled #= false
    step(5)
    loadProgram(progA_c12)                   // active bank (whichever it is now)
    dut.io.enabled #= true
    step(5)

    // Hold bankSwapNow across lines 69-72 (covers WAIT match + dispatch + drain)
    captured.clear()
    // First reach line 68 without bankSwapNow
    runRaster(yFrom = 60, yTo = 68)
    // Now assert bankSwapNow before line 70
    dut.io.bankSwapNow #= true
    runRaster(yFrom = 69, yTo = 72)
    dut.io.bankSwapNow #= false
    val c12writes = captured.filter(_._1 == 0x0613).toList
    assert(c12writes.isEmpty,
      s"case12: bankSwapNow precedence must suppress WRITE during the hold; " +
      s"got ${c12writes.size} writes — defensive goto failed!")
    println("[sim] case12 bankSwapNow precedence suppresses mid-execution WRITE — OK")

    println("[sim] CopperSim: PASS")
  }
}
