package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** First-pass sim for the rebuilt SdramTileFetch.
  *
  * Uses a Scala-side behavioral model of the reused `sdram.v` controller
  * protocol rather than running the Verilog controller itself. This keeps the
  * sim loop inside SpinalSim without needing iverilog/verilator co-sim.
  *
  * Covers:
  *   - boot-copy completes and tileMap bytes land at expected SDRAM addresses
  *   - one line of fetched pixels matches `BasicPatternSource.expectedPixelIndex`
  *
  * Refresh cadence and multi-line / scroll coverage deferred to a follow-up.
  */
object SdramTileFetchSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(64800000 Hz))
    SdramTileFetch(sdramCd)
  }.doSim { dut =>
    // Both clocks run on the same period for sim simplicity. StreamFifoCC still
    // applies its sync stages, so the CDC path is exercised; true async timing
    // jitter coverage is out of scope for this first-cut sim.
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    // ------- Behavioral SDRAM model -----------------------------------------
    val mem = mutable.HashMap[Int, Int]()
    def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
    def readWord(a: Int): Long = {
      val base = a & ~3
      (readByte(base).toLong & 0xFF) |
        ((readByte(base + 1).toLong & 0xFF) << 8) |
        ((readByte(base + 2).toLong & 0xFF) << 16) |
        ((readByte(base + 3).toLong & 0xFF) << 24)
    }

    // Initialize IO
    dut.io.sdramDout      #= 0
    dut.io.sdramDout32    #= 0
    dut.io.sdramDataReady #= false
    dut.io.sdramBusy      #= true  // power-on busy
    dut.io.fetchStart     #= false
    dut.io.fetchLine      #= 0
    dut.io.fetchScrollX   #= 0
    dut.io.fetchScrollY   #= 0
    dut.io.pixelAddr      #= 0

    // ------- Refresh cadence watcher (SDRAM clock domain) -------------------
    // Asserts that refresh pulses arrive at least once every MaxRefreshGap
    // SDRAM cycles. The DUT uses a 950-cycle internal timer; allow 200 cycles
    // of slack for FSM state transitions / preempted refresh delays.
    val MaxRefreshGap = 1150
    @volatile var lastRefreshCycle: Long = 0L
    @volatile var sdramCycle:       Long = 0L
    @volatile var refreshCount:     Int  = 0
    @volatile var maxGapSeen:       Long = 0L

    // Refresh watcher uses onSamplings so it doesn't interleave as a separate
    // fork (which can shift scheduling relative to the controller fork and
    // perturb same-edge assignment ordering in SpinalSim).
    dut.sdramCd.onSamplings {
      sdramCycle += 1
      if (dut.io.sdramRefresh.toBoolean) {
        val gap = sdramCycle - lastRefreshCycle
        if (lastRefreshCycle > 0 && gap > maxGapSeen) maxGapSeen = gap
        lastRefreshCycle = sdramCycle
        refreshCount += 1
      } else if (lastRefreshCycle > 0) {
        val gap = sdramCycle - lastRefreshCycle
        assert(gap <= MaxRefreshGap,
          f"Refresh cadence violated: $gap%d cycles (max $MaxRefreshGap%d)")
      }
    }

    fork {
      // Initial power-on delay: hold busy for 30 cycles then drop.
      for (_ <- 0 until 30) dut.sdramCd.waitSampling()
      dut.io.sdramBusy #= false

      var state = "idle"
      var timer = 0
      var op = ""
      var latchedAddr = 0
      var latchedDin = 0
      var writesDone = 0

      while (true) {
        dut.sdramCd.waitSampling()
        dut.io.sdramDataReady #= false  // default

        state match {
          case "idle" =>
            if (dut.io.sdramRd.toBoolean) {
              op = "rd"; latchedAddr = dut.io.sdramAddr.toInt
              dut.io.sdramBusy #= true
              state = "wait"; timer = 3   // T_RCD+CAS = 1+2
            } else if (dut.io.sdramWr.toBoolean) {
              op = "wr"; latchedAddr = dut.io.sdramAddr.toInt
              latchedDin = dut.io.sdramDin.toInt & 0xFF
              dut.io.sdramBusy #= true
              state = "wait"; timer = 5   // T_RCD+T_WR+T_RP
            } else if (dut.io.sdramRefresh.toBoolean) {
              op = "rf"
              dut.io.sdramBusy #= true
              state = "wait"; timer = 4   // T_RC
            }

          case "wait" =>
            timer -= 1
            if (timer == 0) {
              op match {
                case "rd" =>
                  dut.io.sdramDout   #= readByte(latchedAddr) & 0xFF
                  dut.io.sdramDout32 #= BigInt(readWord(latchedAddr) & 0xFFFFFFFFL)
                  dut.io.sdramDataReady #= true
                  state = "rdDone"
                case "wr" =>
                  mem(latchedAddr & 0x7fffff) = latchedDin
                  writesDone += 1
                  if (writesDone <= 5 || writesDone % 400 == 0)
                    println(f"[sim] wr#$writesDone addr=0x${latchedAddr}%06x din=0x${latchedDin}%02x")
                  dut.io.sdramBusy #= false
                  state = "idle"
                case "rf" =>
                  dut.io.sdramBusy #= false
                  state = "idle"
              }
            }

          case "rdDone" =>
            dut.io.sdramBusy #= false
            state = "idle"
        }
      }
    }

    // Let reset propagate + BufferCC stages settle before sampling bootDone.
    dut.clockDomain.waitSampling(50)

    // ------- Wait for boot-copy complete ------------------------------------
    var timeout = 400000
    while (!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone")
    println(s"[sim] bootDone after ${400000 - timeout} pixel cycles; mem entries=${mem.size}")

    // ------- Wait for memtest to complete -----------------------------------
    timeout = 400000
    while (!dut.io.memtestPass.toBoolean && !dut.io.memtestFail.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for memtest")
    assert(dut.io.memtestPass.toBoolean, "memtestFail asserted — readback mismatch in behavioral model")
    assert(!dut.io.memtestFail.toBoolean, "memtestFail is set")
    println(s"[sim] memtestPass after ${400000 - timeout} pixel cycles")

    // Spot-check boot-copied tileMap bytes (base matches DUT's TileMapBase).
    val TileMapBaseSim = 0x4000  // keep in sync with SdramTileFetch.TileMapBase
    val TileRowBaseSim = 0x5000
    for (i <- Seq(0, 1, 40, 41, 80, 100, 1199)) {
      val tileY = i / 40
      val tileX = i % 40
      val exp = ((tileX >> 1) + tileY) & 0x7
      val got = readByte(TileMapBaseSim + i)
      assert(got == exp, s"tileMap byte[$i] got=$got exp=$exp")
    }
    println("[sim] tileMap boot-copy readback OK")

    // Spot-check one tileRow byte: tile 0, row 0, byte 0 should be low 8 bits of
    // row(0) for tile0 pattern. tile0: red/black checker every 4 pixels, so
    // pixels 0..3 = red (2), 4..7 = black (0). Packed low 12 bits = 3-bit per
    // pixel × 4 pixels = 0b010_010_010_010 = 0x492 → low byte = 0x92.
    val tile0Row0Byte0 = readByte(TileRowBaseSim)
    assert(tile0Row0Byte0 == 0x92, s"tile0 row0 byte0 got=0x${tile0Row0Byte0.toHexString} exp=0x92")
    println("[sim] tileRow boot-copy spot-check OK")

    // ------- Per-config line fetch + pixel verification ---------------------
    def fireFetch(y: Int, scrollX: Int, scrollY: Int): Unit = {
      dut.io.fetchLine    #= y
      dut.io.fetchScrollX #= scrollX
      dut.io.fetchScrollY #= scrollY
      dut.io.fetchStart   #= true
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      dut.io.fetchStart   #= false
      for (_ <- 0 until 20000) dut.clockDomain.waitSampling()
    }

    def checkLine(y: Int, scrollX: Int, scrollY: Int, label: String): Unit = {
      dut.io.pixelAddr    #= 0        // park out of the underrun-trigger zone
      // Ping-pong semantics: each fetchStart flips the write buffer. L0 reads
      // the OTHER (previously-filled) buffer. Two pulses for the same line so
      // the second pulse flips the reader onto the buffer written by the first.
      fireFetch(y, scrollX, scrollY)
      // Small settling gap before the second pulse so back-to-back fetchStart
      // rises separate cleanly in the pixel-domain edge detect.
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      fireFetch(y, scrollX, scrollY)

      var mismatches = 0
      for (x <- 0 until 640) {
        dut.io.pixelAddr #= x
        dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        val got = dut.io.pixelIndex.toInt
        val exp = BasicPatternSource.expectedPixelIndex(x, y, scrollX, scrollY)
        if (got != exp) {
          if (mismatches < 4) println(s"[sim] $label x=$x got=$got exp=$exp")
          mismatches += 1
        }
      }
      assert(mismatches == 0, s"$label: $mismatches mismatches")
      // After every line fetch, assert the sticky underrun flag was not set.
      assert(!dut.io.underrun.toBoolean, s"$label: underrun flag asserted")
      println(s"[sim] $label OK")
    }

    // Coverage matrix:
    //   - line 0 (frame top)
    //   - line 1 (inside first tile row)
    //   - line 15 (last row of first tile)
    //   - line 16 (first row of second tile)
    //   - line 239 (mid-frame)
    //   - line 479 (last visible line)
    // Scroll cases exercise the horizontal + vertical wrap paths.
    checkLine(y = 0,   scrollX = 0,   scrollY = 0,   label = "y=0 sx=0 sy=0")
    checkLine(y = 1,   scrollX = 0,   scrollY = 0,   label = "y=1 sx=0 sy=0 (intra-tile)")
    checkLine(y = 15,  scrollX = 0,   scrollY = 0,   label = "y=15 sx=0 sy=0 (tile-row end)")
    checkLine(y = 16,  scrollX = 0,   scrollY = 0,   label = "y=16 sx=0 sy=0 (tile-row start)")
    checkLine(y = 239, scrollX = 0,   scrollY = 0,   label = "y=239 sx=0 sy=0 (mid-frame)")
    checkLine(y = 479, scrollX = 0,   scrollY = 0,   label = "y=479 sx=0 sy=0 (last visible)")

    // Non-zero scrolls, including wraparound
    checkLine(y = 0,   scrollX = 7,   scrollY = 0,   label = "y=0 sx=7 (intra-tile hscroll)")
    checkLine(y = 0,   scrollX = 16,  scrollY = 0,   label = "y=0 sx=16 (tile-aligned hscroll)")
    checkLine(y = 0,   scrollX = 33,  scrollY = 0,   label = "y=0 sx=33 (mixed hscroll)")
    checkLine(y = 0,   scrollX = 632, scrollY = 0,   label = "y=0 sx=632 (near-wrap hscroll)")
    checkLine(y = 100, scrollX = 0,   scrollY = 55,  label = "y=100 sy=55 (vscroll)")
    checkLine(y = 100, scrollX = 0,   scrollY = 480, label = "y=100 sy=480 (vwrap @ mapH)")
    checkLine(y = 50,  scrollX = 0,   scrollY = 960, label = "y=50 sy=960 (vwrap @ 2*mapH)")
    checkLine(y = 200, scrollX = 320, scrollY = 240, label = "y=200 sx=320 sy=240 (combined)")

    // ------- Concurrent-read race test (regression guard for the Sequence-2
    // red-band bug). Drives pixelAddr as if the raster were actively reading
    // while fetch is running in the background. With the ping-pong buffer, L0
    // must see the PREVIOUS fetch's data, stable across the concurrent window.
    def concurrentReadTest(stableY: Int, raceY: Int): Unit = {
      // Prime: land stableY's data in the read buffer (needs two pulses).
      fireFetch(stableY, 0, 0)
      fireFetch(stableY, 0, 0)

      // Now fire a fetch for raceY (writes other buffer), and IMMEDIATELY start
      // walking pixelAddr as the fetch runs. Reader should still return stableY.
      dut.io.fetchLine    #= raceY
      dut.io.fetchScrollX #= 0
      dut.io.fetchScrollY #= 0
      dut.io.fetchStart   #= true
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      dut.io.fetchStart   #= false

      var mismatches = 0
      for (x <- 0 until 640) {
        dut.io.pixelAddr #= x
        dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        val got = dut.io.pixelIndex.toInt
        val exp = BasicPatternSource.expectedPixelIndex(x, stableY, 0, 0)
        if (got != exp) {
          if (mismatches < 4) println(s"[sim] concurrent-race stableY=$stableY raceY=$raceY x=$x got=$got exp=$exp")
          mismatches += 1
        }
      }
      assert(mismatches == 0,
        s"concurrent-race stableY=$stableY raceY=$raceY: $mismatches mismatches (single-buffer race regression)")
      // Let the race-fetch finish for clean state.
      for (_ <- 0 until 20000) dut.clockDomain.waitSampling()
      println(s"[sim] concurrent-read stableY=$stableY during raceY=$raceY fetch OK")
    }

    concurrentReadTest(stableY = 100, raceY = 200)
    concurrentReadTest(stableY = 7,   raceY = 300)

    println(f"[sim] refresh pulses observed: $refreshCount, max gap: $maxGapSeen%d SDRAM cycles (limit $MaxRefreshGap)")
    assert(refreshCount > 0, "No refresh pulses observed — refresh scheduler not running?")

    val totalStalls = dut.io.debugPushStalls.toInt
    // Loose upper bound: per-line ~41 tiles × 2 words × ~10 cycles headroom = 820;
    // across 14 lines + boot that's ~12000. A few hundred is expected back-pressure;
    // a stuck FIFO would saturate at 65535.
    println(s"[sim] FIFO push-stall cycles (cumulative, all lines): $totalStalls")
    assert(totalStalls < 5000, s"Excessive FIFO push stalls: $totalStalls")

    println("[sim] SdramTileFetchSim: PASS")
  }
}
