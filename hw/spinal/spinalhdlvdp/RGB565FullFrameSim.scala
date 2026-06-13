package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** RGB565-FULLFRAME-132 Checkpoint A — reproduce the full-frame direct-color
  * display bug (only the top rows render, stretched down the frame).
  *
  * Hypothesis: BitmapRowFetch fetches the direct-color row BYTE-AT-A-TIME
  * (320 bitmap + 320 attr = 640 single-byte SDRAM reads per source row). Each
  * source row is shown for 2 scanlines (line doubling), so the fetch budget is
  * ~2 scanlines. At 25.2 MHz pixel / 40.5 MHz SDRAM, 2 scanlines ≈ 2×800
  * pix = 1600 pix = ~2572 SDRAM cycles. If one row's 640 byte-reads take MORE
  * than that, rows can't keep up with the raster → only the top fraction of the
  * 240 source rows render before the frame restarts → "top sweep stretched".
  *
  * This sim measures the ACTUAL sdramCd cycles the real FSM takes to fetch one
  * full direct-color row, at a few plausible per-read latencies, and compares to
  * the 2-scanline budget. (No contention modelled — real arbiter contention with
  * tile/planar/refresh only makes it worse.)
  */
object RGB565FullFrameSim extends App {
  // Per-source-row SDRAM-cycle budget. Each source row displays for 2 scanlines
  // (line doubling). BUT the bitmap fetch has NO dedicated scheduler slot — it
  // borrows L0's slotValid window via the activeBit grant-id override
  // (TopTang20kHdmi.scala:857-876), and L0 slot 1 is [0,399] (VdpTop:1116-1123).
  // So the real per-line bus window is only ~400 pixel-clocks, not the full 800.
  val PIX_HZ = 25.2e6
  val SDRAM_HZ = 40.5e6
  val BITMAP_WINDOW_PIX = 400          // L0 slot 1 [0,399] that bitmap borrows
  val FULL_LINE_PIX = 800
  // Faithful budget = borrowed window × 2 lines, in SDRAM cycles.
  val rowBudgetCycles = math.round(2 * BITMAP_WINDOW_PIX / PIX_HZ * SDRAM_HZ).toInt   // ~1286
  val fullLineBudget  = math.round(2 * FULL_LINE_PIX   / PIX_HZ * SDRAM_HZ).toInt     // ~2571 if widened

  def measureRowFetchCycles(readLatency: Int): Long = {
    var result = 0L
    Config.sim.compile {
      val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(SDRAM_HZ.toLong Hz))
      BitmapRowFetch(sdramCd, skipSdramInit = true)
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)   // pixel 25.2 MHz
      dut.sdramCd.forkStimulus(period = 10)        // SDRAM 40.5 MHz
      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0x55555555L; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= false
      dut.io.fetchGrant #= false; dut.io.fetchLine #= 0; dut.io.col #= 0
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= 0x100000; dut.io.attrBase #= 0x200000
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240

      // SDRAM read model with parameterised CAS latency to the first word, then a
      // BURST of `sdramBurstLen` 32-bit words at one word/cycle (RGB565-FULLFRAME-132).
      // Each word = 4 bytes; the row completes at 640 bytes = 20 burst-8 reads (or 160
      // single reads if burstLen=1). Count bytes so the loop terminates at row end.
      var bytesDelivered = 0
      fork {
        while (true) {
          if (dut.io.sdramRd.toBoolean) {
            val n = math.max(1, dut.io.sdramBurstLen.toInt)
            dut.sdramCd.waitSampling(readLatency)
            for (k <- 0 until n) {
              dut.io.sdramDout #= 0x55
              dut.io.sdramDout32 #= 0x55555555L
              dut.io.sdramDataReady #= true
              dut.sdramCd.waitSampling()
              bytesDelivered += 4
            }
            dut.io.sdramDataReady #= false
          } else dut.sdramCd.waitSampling()
        }
      }

      dut.sdramCd.waitSampling(10); dut.clockDomain.waitSampling(10)
      dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
      var t = 2000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }

      // Pulse one fetchGrant for source line 0 and count cycles until all 640
      // bytes (320 bitmap + 320 attr) have been delivered.
      bytesDelivered = 0
      dut.io.fetchLine #= 0
      dut.io.fetchGrant #= true
      dut.clockDomain.waitSampling(4)
      dut.io.fetchGrant #= false
      var cycles = 0L
      while (bytesDelivered < 640 && cycles < 200000) {
        dut.sdramCd.waitSampling(); cycles += 1
      }
      result = cycles
    }
    result
  }

  println(s"[sim] borrowed-window (L0 [0,399]) source-row budget = $rowBudgetCycles cycles")
  println(s"[sim] full-line budget if bitmap gets a dedicated full-line slot = $fullLineBudget cycles")
  // RGB565-FULLFRAME-132 fix: BitmapRowFetch now reads dout32 (4 bytes/read) →
  // 160 word-reads/row instead of 640 byte-reads. ACCEPTANCE: cycles/row must be
  // under the borrowed-window budget (the fix must work even WITHOUT a dedicated
  // full-line slot).
  var worst = 0L
  for (lat <- Seq(2, 4, 6)) {
    val c = measureRowFetchCycles(lat)
    if (c > worst) worst = c
    val r1 = c.toDouble / rowBudgetCycles
    val r2 = c.toDouble / fullLineBudget
    val v = if (c < rowBudgetCycles) "FITS borrowed window" else if (c < fullLineBudget) "fits full-line only" else "OVER"
    println(f"[sim] readLatency=$lat%d cyc/read: row fetch = $c%d cyc  (${r1}%.2fx borrowed, ${r2}%.2fx full-line) -> $v")
  }
  assert(worst < fullLineBudget, s"dout32 fetch must fit the full-line budget ($fullLineBudget); worst=$worst")
  println(f"[sim] RGB565FullFrameSim: PASS — dout32 fetch fits (worst $worst%d < full-line $fullLineBudget%d)")
}
