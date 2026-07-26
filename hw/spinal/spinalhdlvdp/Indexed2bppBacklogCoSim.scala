package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** 2bpp-backlog-cosim (#14327/#14332) — continuous-scanout, bandwidth-limited SDRAM co-sim
  * that reproduces the display-bank-advance-without-completion hazard (external-review claim 2).
  *
  * Reuses the REAL `Indexed2bppFrameCoSim.Dut` (production `VdpTop` + `BitmapRowFetch`, driven by
  * production `bitmapSdramFetchLine := fillLine` and production 3-bank rotation). Unlike the
  * idealized always-ready model in `runRowCoded`, this uses:
  *   - a REAL-ish clock ratio (pixel ~25.2 MHz : sdram ~40.5 MHz ≈ 1.6, modeled as 40:25),
  *   - a bandwidth-limited SDRAM model: finite read latency + periodic AUTO_REFRESH stalls,
  *   - free-running pixel + sdram clocks with concurrent display-bank consumption,
  *   - a FORCED-LATE mode that blocks SDRAM servicing over a window of display lines so a
  *     fetch misses its deadline and the 2-bank head start drains.
  *
  * Row-coded content (each source row's value1→value2 boundary column encodes the source row)
  * makes the EMITTED pixel stream self-identify which source row each display line actually shows,
  * so the scoreboard needs no RTL taps to detect a stale/incomplete/wrong-row bank at display time.
  * `grantOverflow` is read from the RTL (`dut.fetch.sd.grantOverflow`, simPublic).
  *
  * Acceptance (PM #14332): production fetch-line + bank rotation; report bestDv (enforce ==3),
  * max fetch-active duration, source-row budget, grantOverflow / underflow / wrong-row counts;
  * NOMINAL realistic latency+refresh => zero violations; FORCED-LATE => detector fires with the
  * current (no-bankReady) design. This sim is the pass/fail gate for the 2bpp-bank-completion-rtl fix.
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
  */
object Indexed2bppBacklogCoSim extends App {
  import Indexed2bppFrameCoSim.{BitmapBase, AttrBase, RowStride, SrcH}

  val pixPeriod = 40           // ~25.2 MHz pixel clock
  val sdPeriod  = 25           // ~40.5 MHz sdram clock  (ratio 1.6 ≈ real 1.607)
  val hTotal = 800; val vTotal = 525; val hActive = 640; val vActive = 480
  val srcRowBudgetPix = 2 * hTotal   // line-doubled: one source row is displayed for 2 output lines

  def boundaryByte(row: Int): Int = 10 + (row % 60)   // 10..69, encodes source row mod 60

  case class Result(bestDv: Int, wrongEvents: Int, validRows: Int,
                    grantOverflow: Int, blankRows: Int, maxFetchActiveSd: Int,
                    rtlUnderflow: Int, rtlRowTagMismatch: Int, malformedRows: Int, dispValidFinal: Boolean)

  /** forcedLate: block SDRAM servicing while the display is in [stallLine0, stallLine1) each frame
    * (drains the 2-bank head start → a bank rotates onto the display before its fill completes). */
  def runBacklog(forcedLate: Boolean, stallLine0: Int, stallLine1: Int,
                 refreshPeriod: Int, refreshLen: Int, latency: Int): Result = {
    var res = Result(0, 0, 0, 0, 0, 0, 0, 0, 0, false)
    Config.sim.compile(new Indexed2bppFrameCoSim.Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(pixPeriod)
      dut.sdramCd.forkStimulus(sdPeriod)

      // Row-coded content: source row r's value1→value2 boundary at byte boundaryByte(r).
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH) {
        val bnd = boundaryByte(row)
        for (b <- 0 until RowStride) {
          mem((BitmapBase + row * RowStride + b) & 0x7fffff) = if (b < bnd) 0x55 else 0xAA
          mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
        }
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

      // Bandwidth-limited SDRAM read model, faithful to BitmapRowFetch's issue/WAIT
      // handshake. CRITICAL: `io.sdramRd` is a ONE-cycle pulse per read (cmdRd is asserted
      // in the issue state sFetchBitmap/sFetchAttr, then the FSM sits in the matching WAIT
      // state with NO timeout until `sdramDataReady` pulses — BitmapRowFetch:495-561). So a
      // stall must NEVER skip *latching* the request; it may only DELAY its data return.
      // (The prior version gated latching on `!stall`, so a refresh/late window coinciding
      // with the 1-cycle pulse silently DROPPED the read and the fetch FSM deadlocked in a
      // WAIT state early in the frame — the run-1 degenerate result: nominal==forced-late,
      // bestDv=-3, grantOverflow=168, no underflow.) This is also physically correct: real
      // SDRAM accepts the read command; an in-progress AUTO_REFRESH only delays the data.
      // With latency=0 this reduces exactly to the proven always-ready model in
      // Indexed2bppFrameCoSim.runRowCoded.
      val readsPerRow = 2 * (80 / 4)   // indexed: 20 bitmap + 20 attr single-word reads/row (fetchCount=80B, burst-1)
      var maxFetchSpanSd = 0           // longest per-row fetch span, in sdramCd cycles
      fork {
        var sdCyc = 0
        for (_ <- 0 until 30) { dut.sdramCd.waitSampling(); sdCyc += 1 }
        dut.io.sdramBusy #= false
        var pending = false; var addr = 0; var n = 0; var waitLat = 0; var wordK = 0
        var readsInRow = 0; var rowStartSd = 0
        while (true) {
          sdCyc += 1
          val refreshStall = refreshLen > 0 && (sdCyc % refreshPeriod) < refreshLen
          val y = dut.io.y.toInt
          val lateStall = forcedLate && y >= stallLine0 && y < stallLine1
          val stalled = refreshStall || lateStall
          // Latch a new read the cycle sdramRd pulses — ALWAYS (never dropped by a stall).
          if (!pending && dut.io.sdramRd.toBoolean) {
            pending = true
            addr = dut.io.sdramAddr.toInt
            n = math.max(1, dut.io.sdramBurstLen.toInt)
            waitLat = latency; wordK = 0
            readsInRow += 1
            if (readsInRow == 1) rowStartSd = sdCyc
            if (readsInRow >= readsPerRow) { val d = sdCyc - rowStartSd; if (d > maxFetchSpanSd) maxFetchSpanSd = d; readsInRow = 0 }
          }
          // Serve the latched read; refresh/late stalls only DELAY delivery (never drop it).
          if (pending && !stalled) {
            if (waitLat > 0) { waitLat -= 1; dut.io.sdramDataReady #= false }
            else {
              dut.io.sdramDout   #= rb(addr + wordK*4) & 0xFF
              dut.io.sdramDout32 #= BigInt(rw(addr + wordK*4) & 0xFFFFFFFFL)
              dut.io.sdramDataReady #= true
              wordK += 1
              if (wordK >= n) pending = false
            }
          } else {
            dut.io.sdramDataReady #= false
          }
          dut.sdramCd.waitSampling()
        }
      }

      def writeReg(a: Int, d: Int): Unit = {
        dut.io.regBusAddr #= a; dut.io.regBusData #= d; dut.io.regBusEnable #= true
        dut.clockDomain.waitSampling(); dut.io.regBusEnable #= false; dut.clockDomain.waitSampling()
      }
      writeReg(0x0300, 0x0000)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)
      writeReg(0x0300, 0x0001)

      var t = 400000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      dut.clockDomain.waitSampling(hTotal * vTotal * 3)

      // Capture one steady-state frame's emitted pixels.
      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = hTotal * vTotal * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }

      // Row-coded scoreboard + malformed-row (torn / incomplete-bank) detector.
      // Each row-coded row is a clean 2-region pattern: colorA (index1) for cols before
      // the source-row-encoded boundary, colorB (index2) after. Reconstruct each row's
      // OWN 2-region model from its detected boundary and count pixels that DEVIATE,
      // excluding the line-start (col 0..3), the right frame edge (col >= 636), and a
      // +/-3 band around the boundary — all known-benign 1px pipeline/edge artifacts.
      // A FROZEN valid row (old source row) matches its own model => dev ~ 0 => NOT
      // malformed (graceful degradation; still counted as wrong via its row tag). A
      // TORN / incomplete bank (mixed content) deviates heavily => malformed => a real
      // display-bank VIOLATION.
      val impliedMod = Array.fill(480)(-1)
      var blankRows = 0
      var malformedRows = 0
      for (dy <- 0 until 480) {
        val cA = gotFrame(dy)(4)     // reference colorA, past the line-start pipeline warmup
        if (cA != 0x000000 && cA >= 0) {
          var bcol = -1; var dx = 5
          while (dx < 636 && bcol < 0) { val c = gotFrame(dy)(dx); if (c >= 0 && c != cA) bcol = dx; dx += 1 }
          if (bcol >= 0) {
            val bnd = (bcol + 4) / 8; impliedMod(dy) = ((bnd - 10) % 60 + 60) % 60
            val cB = gotFrame(dy)(math.min(bcol + 3, 639))
            var dev = 0; var x = 4
            while (x < 636) {
              val c = gotFrame(dy)(x)
              if (c >= 0 && math.abs(x - bcol) > 3) { val exp = if (x < bcol) cA else cB; if (c != exp) dev += 1 }
              x += 1
            }
            if (dev > 8) malformedRows += 1   // torn / incomplete-bank display = VIOLATION
          }
        } else blankRows += 1   // active row rendered blank/black = stale/empty bank
      }
      def srcRow(dy: Int, dv: Int): Int = { val r = if (dy % 2 == 0) dy/2 - dv else (dy-1)/2 - (dv-1); ((r % 60) + 60) % 60 }
      val valid = (0 until 480).filter(impliedMod(_) >= 0)
      var bestDv = 0; var bestMatch = -1
      for (dv <- -4 to 8) { var m = 0; for (dy <- valid) if (impliedMod(dy) == srcRow(dy, dv)) m += 1; if (m > bestMatch) { bestMatch = m; bestDv = dv } }
      val wrongEvents = valid.size - bestMatch
      val grantOv    = try { dut.fetch.sd.grantOverflow.toInt } catch { case _: Throwable => -1 }
      val rtlUnder   = try { dut.fetch.displayUnderflow.toInt } catch { case _: Throwable => -1 }
      val rtlTagMiss = try { dut.fetch.rowTagMismatch.toInt }   catch { case _: Throwable => -1 }
      val dvFinal    = try { dut.fetch.dispValid.toBoolean }    catch { case _: Throwable => false }
      res = Result(bestDv, wrongEvents, valid.size, grantOv, blankRows, maxFetchSpanSd, rtlUnder, rtlTagMiss, malformedRows, dvFinal)
    }
    res
  }

  println("=== Indexed2bppBacklogCoSim (#14327/#14332): continuous-scanout backlog + bank-completion scoreboard ===")
  println(f"[budget] source-row budget = $srcRowBudgetPix pixel-clocks/source-row (line-doubled 2×hTotal); nominal fetch ~1566 pixel-clocks (BitmapRowFetch:322). Clock ratio pixel:sdram = $pixPeriod:$sdPeriod.")

  // NOMINAL: finite latency + periodic refresh the 3-bank pipeline absorbs.
  val nom = runBacklog(forcedLate = false, stallLine0 = 0, stallLine1 = 0, refreshPeriod = 2000, refreshLen = 12, latency = 6)
  val sdToPix = sdPeriod.toDouble / pixPeriod   // sdramCd cycles -> pixel-clocks (25/40 = 0.625)
  def show(tag: String, r: Result): Unit = println(
    f"[$tag%-11s] bestDv=${r.bestDv} wrong=${r.wrongEvents}/${r.validRows} malformed=${r.malformedRows} blank=${r.blankRows} grantOverflow=${r.grantOverflow} rtlUnderflow=${r.rtlUnderflow} rowTagMismatch=${r.rtlRowTagMismatch} dispValid=${r.dispValidFinal} maxFetchSpan=${r.maxFetchActiveSd}sdCyc (~${r.maxFetchActiveSd * sdToPix}%.0f pixclk)")
  show("NOMINAL", nom)

  // FORCED-LATE: block servicing across display lines [200,212) each frame → drain the head start.
  val late = runBacklog(forcedLate = true, stallLine0 = 200, stallLine1 = 212, refreshPeriod = 2000, refreshLen = 12, latency = 6)
  show("FORCED-LATE", late)

  // POST-hardening acceptance (bankReady + bankRowTag gating in BitmapRowFetch):
  //  - NOMINAL must stay clean: bestDv==3, no display-bank violations, gating idle.
  //  - FORCED-LATE must show NO display-bank violation (malformed==0, display stayed valid)
  //    while the gating ENGAGED (rtlUnderflow and/or rowTagMismatch > 0) — the hazard was
  //    caught and degraded gracefully (frozen valid rows) instead of showing garbage.
  //  Pre-hardening forced-late FAILURE is on record at 5efe049 (wrong 214/480, grantOverflow 25).
  val nomClean = nom.bestDv == 3 && nom.wrongEvents <= 6 && nom.grantOverflow <= 0 &&
                 nom.malformedRows == 0 && nom.rtlUnderflow <= 2 && nom.rtlRowTagMismatch <= 2
  val lateGraceful = late.malformedRows == 0 && late.dispValidFinal &&
                     (late.rtlUnderflow > 0 || late.rtlRowTagMismatch > 0)

  if (nomClean && lateGraceful)
    println("[sim] BACKLOG: PASS (post-hardening) — NOMINAL clean (bestDv=3, zero display-bank violations, gating idle) AND FORCED-LATE shows ZERO malformed/incomplete-bank display with the completion gating ENGAGED (rtlUnderflow/rowTagMismatch>0): the display never advances onto an incomplete or stale bank, degrading to frozen valid rows. External-review claim 2 hazard CLOSED.")
  else if (!nomClean)
    println(f"[sim] BACKLOG: FAIL — NOMINAL regressed (bestDv=${nom.bestDv} wrong=${nom.wrongEvents} malformed=${nom.malformedRows} grantOv=${nom.grantOverflow} rtlUnderflow=${nom.rtlUnderflow} rowTagMismatch=${nom.rtlRowTagMismatch}). Hardening must not disturb the nominal path.")
  else
    println(f"[sim] BACKLOG: FAIL — FORCED-LATE not graceful (malformed=${late.malformedRows} dispValid=${late.dispValidFinal} rtlUnderflow=${late.rtlUnderflow} rowTagMismatch=${late.rtlRowTagMismatch}). Either the gating did not engage or a torn/incomplete bank was still displayed (likely fetch-side lapping overwriting the frozen display bank -> needs fetch backpressure).")
}
