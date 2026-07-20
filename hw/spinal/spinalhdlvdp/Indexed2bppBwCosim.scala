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

  // ---- DATA-correctness check: preload a known signature, verify the INDEXED single-read
  // fetch reads it byte-perfect through the real sdram.v + arbiter + refresh (no upload
  // contention — the 2bpp reference is static). Splits "fetch/controller corrupts data"
  // (RTL bug) from "SDRAM content is wrong" (upload path). Mirrors BitmapConcurrentBwCosim.runContent
  // but for the indexed geometry (hardwired 128-byte row stride, byte index = col/8).
  def sig(a: Int): Int = ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
  def runContent(): Unit = {
    val base = 0x100000; val attrBase = 0x200000; val idxStride = 128
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, 0)
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)
      dut.sdramCd.forkStimulus(period = 10)
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= base; dut.io.attrBase #= attrBase
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= true; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }
      def wrByte(addr: Int, data: Int): Unit = {
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
        dut.io.uplAddr #= addr; dut.io.uplDin #= data; dut.io.uplWr #= true
        var g = 20; while (!dut.io.ctrlBusy.toBoolean && g > 0) { dut.sdramCd.waitSampling(); g -= 1 }
        dut.io.uplWr #= false
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      }
      val nLines = 24
      for (row <- 0 until nLines; j <- 0 until 80) wrByte(base     + row*idxStride + j, sig(base     + row*idxStride + j))
      for (row <- 0 until nLines; j <- 0 until 80) wrByte(attrBase + row*idxStride + j, sig(attrBase + row*idxStride + j))

      dut.io.uploadMode #= false
      dut.sdramCd.waitSampling(4); dut.clockDomain.waitSampling(4)
      dut.io.enable #= true; dut.io.directColor #= false; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      dut.io.uploadActive #= false

      var mism = 0; var attrMism = 0; var checks = 0
      val firsts = scala.collection.mutable.ArrayBuffer[String]()
      val warmup = 8; val nScreen = warmup + 8
      for (screenLine <- 0 until nScreen) {
        val srcRow = screenLine >> 1
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1 && (screenLine % 2 == 1)) { dut.io.fetchLine #= (screenLine + 5); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
          if (screenLine >= warmup && h < 640 && (h % 8 == 0)) {
            sleep(1)
            val byteIdx = h / 8
            val got  = dut.io.bitmapByte.toInt & 0xFF; val gotA = dut.io.attrByte.toInt & 0xFF
            val exp  = sig(base     + srcRow*idxStride + byteIdx)
            val expA = sig(attrBase + srcRow*idxStride + byteIdx)
            checks += 1
            if (got  != exp)  { mism     += 1; if (firsts.size < 10) firsts += f"BMP scr=$screenLine srcRow=$srcRow byte=$byteIdx got=0x$got%02X exp=0x$exp%02X" }
            if (gotA != expA) { attrMism += 1 }
          }
        }
      }
      println(f"[sim] INDEXED CONTENT (real sdram.v+refresh): checks=$checks bitmapMismatch=$mism attrMismatch=$attrMism")
      firsts.foreach(m => println(s"[sim]   $m"))
      if (mism == 0 && attrMism == 0)
        println("[sim] INDEXED CONTENT PASS — indexed single-read fetch reads SDRAM byte-perfect under real sdram.v+refresh => fetch/controller data path is CLEAN; the bench speckle is UPLOAD (bad SDRAM content) or downstream, not the read path.")
      else
        println("[sim] INDEXED CONTENT FAIL — the indexed single-read fetch CORRUPTS data under real sdram.v => real RTL/controller data bug (matches the bench speckle).")
    }
  }

  println("=== Indexed2bppBwCosim: INDEXED(single) vs DIRECTCOLOR(burst) fetch timing under REAL sdram.v + arbiter + refresh ===")
  run(directColorMode = false, "INDEXED(2bpp,single)")
  run(directColorMode = true,  "DIRECTCOLOR(burst)")
  println("=== DATA-correctness (the bench artifact is REAL per operator; timing was clean, so check the values) ===")
  runContent()
}
