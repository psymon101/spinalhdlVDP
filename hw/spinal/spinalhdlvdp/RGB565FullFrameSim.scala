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
  // 2-scanline source-row budget in SDRAM cycles (line-doubled display).
  val SCANLINE_PIX = 800
  val PIX_HZ = 25.2e6
  val SDRAM_HZ = 40.5e6
  val rowBudgetCycles = math.round(2 * SCANLINE_PIX / PIX_HZ * SDRAM_HZ).toInt  // ~2572

  def measureRowFetchCycles(readLatency: Int): Long = {
    var result = 0L
    Config.sim.compile {
      val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(SDRAM_HZ.toLong Hz))
      BitmapRowFetch(sdramCd, skipSdramInit = true)
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.sdramCd.forkStimulus(period = 10)
      dut.io.sdramDout #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= false
      dut.io.fetchGrant #= false; dut.io.fetchLine #= 0; dut.io.col #= 0
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= 0x100000; dut.io.attrBase #= 0x200000
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240

      // SDRAM read model with parameterised latency; also count bytes delivered.
      var bytesDelivered = 0
      fork {
        while (true) {
          if (dut.io.sdramRd.toBoolean) {
            dut.sdramCd.waitSampling(readLatency)
            dut.io.sdramDout #= 0x55
            dut.io.sdramDataReady #= true
            dut.sdramCd.waitSampling()
            dut.io.sdramDataReady #= false
            bytesDelivered += 1
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

  println(s"[sim] 2-scanline source-row SDRAM budget = $rowBudgetCycles cycles")
  for (lat <- Seq(2, 4, 6)) {
    val c = measureRowFetchCycles(lat)
    val ratio = c.toDouble / rowBudgetCycles
    val verdict = if (c > rowBudgetCycles) "EXCEEDS budget -> rows drop" else "fits"
    println(f"[sim] readLatency=$lat%d cyc/byte: one row fetch = $c%d SDRAM cycles  (${ratio}%.2fx budget) -> $verdict")
  }
  println("[sim] RGB565FullFrameSim: reproduction complete")
}
