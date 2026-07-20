package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** HAM6-shelve #14235/#14237 follow-on — 2bpp INDEXED fetch timing under the REAL SDRAM IP.
  *
  * Context: BronzeGate's native 720x480 YUYV capture shows the horizontal banding PERSISTS
  * after the 1080p-MJPEG/vertical-scaling is removed, so the shear is real-SDRAM-timing, not
  * capture. `Indexed2bppFrameCoSim` already proved the display LOGIC is correct (boundary
  * bit-stable) under an IDEALIZED SDRAM. This sim closes the loop: it runs the INDEXED fetch
  * (`directColor=false` -> 160 SINGLE-word reads/row, `burstWords=1`) through the REAL
  * `SdramArbiter` + REAL `sdram.v` (`SdramWithModel`) + auto-refresh -- the same harness as
  * `BitmapConcurrentBwCosim` -- and measures per-display-row fetch duration vs the ~1286-cyc
  * per-line budget (800 px * 40.5/25.2). If the indexed fetch exceeds the budget, the fetch
  * falls behind the scanout -> the display reads a partially-filled / stale line buffer ->
  * the observed horizontal banding.
  *
  * Refresh-only (no upload contention): the 2bpp reference is a STATIC image, so the only
  * bus competitor during display is auto-refresh. INDEXED (single reads) is compared to
  * DIRECTCOLOR (bursts) to quantify the single-read penalty.
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppBwCosim"
  */
object Indexed2bppBwCosim extends App {
  val hTotal = 800
  val lineBudgetSdram = scala.math.round(800.0 * 40.5 / 25.2).toInt   // ≈ 1286 SDRAM cyc / display line

  def run(directColorMode: Boolean, label: String): Unit = {
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, uploadMinGap = 0)
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)   // 25.2 MHz pixel
      dut.sdramCd.forkStimulus(period = 10)       // 40.5 MHz sdram
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= 0x100000; dut.io.attrBase #= 0x200000
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= false; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }

      dut.io.enable #= true; dut.io.directColor #= directColorMode; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      assert(t > 0, s"$label: bootDone timeout")
      dut.io.uploadActive #= false   // static reference image ⇒ refresh is the only bus competitor

      val nRows = 80; val warmup = 16
      var sumDur = 0L; var lateRows = 0; var measured = 0; var maxDur = 0L; var firstLate = -1
      for (row <- 0 until nRows) {
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1) { dut.io.fetchLine #= (row + 2); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
        }
        // Measure how many SDRAM cycles fetchActive stays high for this row.
        var guard = 0; var dur = 0L; var seenActive = false
        while (guard < 20000) {
          val act = dut.io.fetchActive.toBoolean
          if (act) { seenActive = true; dur += 1 }
          else if (seenActive) { guard = 999999 }   // fell -> row fetch done
          dut.sdramCd.waitSampling(); guard += 1
        }
        if (row >= warmup) {
          sumDur += dur; measured += 1; if (dur > maxDur) maxDur = dur
          if (dur > lineBudgetSdram) { lateRows += 1; if (firstLate < 0) firstLate = row }
        }
      }
      val avgDur = sumDur.toDouble / measured
      val util = 100.0 * avgDur / lineBudgetSdram
      val onset = if (firstLate < 0) "none" else s"row $firstLate"
      println(f"[sim] $label%-24s avgFetch=$avgDur%6.0f max=$maxDur%6d cyc/row (budget=$lineBudgetSdram, util=$util%.0f%%) lateRows=$lateRows/$measured onset=$onset")
    }
  }

  println("=== Indexed2bppBwCosim: INDEXED(single) vs DIRECTCOLOR(burst) fetch timing under REAL sdram.v + arbiter + refresh ===")
  run(directColorMode = false, "INDEXED(2bpp,single)")
  run(directColorMode = true,  "DIRECTCOLOR(burst)")
  println("[sim] Verdict: if INDEXED lateRows>0 or util>~100%, the 2bpp bench banding is real-SDRAM-timing")
  println("[sim]          (single-read fetch starvation under refresh). Fix = burst-read the indexed fetch / deeper prefetch.")
}
