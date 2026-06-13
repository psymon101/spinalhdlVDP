package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** RGB565-FULLFRAME-132 Checkpoint B.2 — CONCURRENT fetch+scanout integration sim.
  *
  * The unit sims (BitmapRowFetchDirectColorSim) settle the fetch fully, THEN
  * read the line buffer — so they never exercise the compositor reading
  * `bitmapLineBuf` WHILE BitmapRowFetch is filling it for the next line. The
  * HW proof failed (#12290: wrong content) on exactly that race.
  *
  * This sim drives BitmapRowFetch the way VdpTop does — col = hCounter, fetchLine
  * = next line, fetchGrant pulse at hCounter==hActive — and reads io.bitmapByte
  * during the active region CONCURRENTLY with the fetch. The SDRAM model returns
  * a line-distinguishing signature so we can tell which source line each
  * displayed pixel actually came from.
  *
  * Pass = every displayed pixel on line L returns line L's source byte.
  * Fail (reproduces HW) = pixels return stale / wrong-line / unwritten data.
  */
object BitmapRowFetchConcurrentSim extends App {
  val hActive = 640; val hTotal = 800
  val stride  = 512
  val base    = 0x100000
  // line-distinguishing source byte for (line, pixel)
  def srcByte(line: Int, pixel: Int): Int = {
    val a = base + line * stride + pixel
    ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
  }

  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    BitmapRowFetch(sdramCd, skipSdramInit = true)
  }.doSim { dut =>
    // Real HW clocks: pixel 25.2 MHz (period 16), SDRAM 40.5 MHz (period ~10).
    // The SDRAM PLL is 40.5 MHz (tang20k_sdram_pll.v: 27*3/2; SDC clk_sdram period
    // 24.691 ns), NOT the stale "64.8 MHz" header comment. period 10 / period 16 =
    // 0.625, matching 40.5/25.2 * (16/10)... i.e. 40.5 MHz vs 25.2 MHz.
    dut.clockDomain.forkStimulus(period = 16)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false
    dut.io.sdramBusy #= false; dut.io.fetchGrant #= false; dut.io.fetchLine #= 0
    dut.io.col #= 0; dut.io.enable #= false; dut.io.directColor #= false
    dut.io.tileBootDone #= false
    dut.io.bitmapBase #= base; dut.io.attrBase #= 0x200000
    dut.io.bitmapStride #= stride; dut.io.attrStride #= stride; dut.io.bitmapHeight #= 240

    // SDRAM read model: 5-cycle latency, then BURST out `sdramBurstLen` consecutive
    // 32-bit words (one data_ready pulse per cycle), word k = the 4 line-distinguishing
    // bytes at addr + k*4 — exactly what the real sdram.v manual-burst path delivers.
    // RGB565-FULLFRAME-132: direct-color fetch now requests burstLen=8, so a single-word
    // response would stall the FSM (it waits for 8 push.fire per burst).
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong.toInt
          val n    = math.max(1, dut.io.sdramBurstLen.toInt)
          dut.sdramCd.waitSampling(5)
          def b(base: Int, o: Int): Long = {
            val a = base + o
            ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF).toLong
          }
          for (k <- 0 until n) {
            val wa = addr + k * 4
            dut.io.sdramDout32 #= b(wa, 0) | (b(wa, 1) << 8) | (b(wa, 2) << 16) | (b(wa, 3) << 24)
            dut.io.sdramDout #= b(wa, 0).toInt
            dut.io.sdramDataReady #= true
            dut.sdramCd.waitSampling()
          }
          dut.io.sdramDataReady #= false
        } else dut.sdramCd.waitSampling()
      }
    }

    dut.sdramCd.waitSampling(10); dut.clockDomain.waitSampling(10)
    dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
    var t = 2000
    while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
    assert(t > 0, "bootDone timeout")

    // Drive BitmapRowFetch with the SAME per-SOURCE-ROW cadence VdpTop uses (and the
    // integration cosim proves): each source row is shown on two output lines, the
    // triple-buffer fetches two rows ahead, and the grant fires at hTotal-1 of the ODD
    // output line (fetchLine = screenLine+5 → row+2). The first `warmup` lines fill the
    // deeper pipeline and are not checked. Sample io.bitmapByte/attrByte CONCURRENTLY
    // with the fetch during the active region.
    var mismatches = 0; var checks = 0; var attrMismatches = 0
    val firstMismatch = scala.collection.mutable.ArrayBuffer[String]()
    def attrByteOf(line: Int, pixel: Int): Int = {
      val a = 0x200000 + line * stride + pixel
      (a ^ (a >> 8) ^ (a >> 16)) & 0xFF
    }
    val warmup  = 8
    val nScreen = warmup + 8
    dut.io.fetchGrant #= false; dut.io.fetchLine #= 0

    for (screenLine <- 0 until nScreen) {
      val srcRow = screenLine >> 1
      for (h <- 0 until hTotal) {
        dut.io.col #= h
        if (h == 4) dut.io.fetchGrant #= false
        if (h == hTotal - 1 && (screenLine % 2 == 1)) {
          dut.io.fetchLine #= (screenLine + 5); dut.io.fetchGrant #= true
        }
        dut.clockDomain.waitSampling()
        // sample during active region, at even columns (one source pixel / 2 cols)
        if (screenLine >= warmup && h < hActive && (h % 2 == 0)) {
          sleep(1)
          val got = dut.io.bitmapByte.toInt
          val gotAttr = dut.io.attrByte.toInt
          val pixel = h / 2
          val exp = srcByte(srcRow, pixel)
          val expAttr = attrByteOf(srcRow, pixel)
          checks += 1
          if (gotAttr != expAttr) attrMismatches += 1
          if (got != exp) {
            mismatches += 1
            if (firstMismatch.size < 6)
              firstMismatch += f"screen=$screenLine row=$srcRow px=$pixel: bitmapByte got=0x$got%02X exp=0x$exp%02X | attrByte got=0x$gotAttr%02X exp=0x$expAttr%02X"
          }
        }
      }
    }
    println(f"[sim] concurrent checks=$checks bitmapMismatches=$mismatches attrMismatches=$attrMismatches")
    firstMismatch.foreach(m => println(s"[sim]   $m"))
    if (mismatches == 0 && attrMismatches == 0) println("[sim] BitmapRowFetchConcurrentSim: PASS — full-frame fill/scanout coherent (both planes)")
    else println("[sim] BitmapRowFetchConcurrentSim: FAIL — reproduces the HW wrong-content race")
  }
}
