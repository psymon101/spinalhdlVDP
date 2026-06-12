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
    // pixel clock 25.2 MHz (period ~40 in 10-unit ticks -> use 16), sdram 40.5 (~10)
    dut.clockDomain.forkStimulus(period = 16)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false
    dut.io.sdramBusy #= false; dut.io.fetchGrant #= false; dut.io.fetchLine #= 0
    dut.io.col #= 0; dut.io.enable #= false; dut.io.directColor #= false
    dut.io.tileBootDone #= false
    dut.io.bitmapBase #= base; dut.io.attrBase #= 0x200000
    dut.io.bitmapStride #= stride; dut.io.attrStride #= stride; dut.io.bitmapHeight #= 240

    // SDRAM read model: 5-cycle latency, return dout32 = 4 line-distinguishing
    // bytes for the requested word address.
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong.toInt
          dut.sdramCd.waitSampling(5)
          def b(o: Int): Long = {
            val a = addr + o
            ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF).toLong
          }
          dut.io.sdramDout32 #= b(0) | (b(1) << 8) | (b(2) << 16) | (b(3) << 24)
          dut.io.sdramDout #= b(0).toInt
          dut.io.sdramDataReady #= true
          dut.sdramCd.waitSampling()
          dut.io.sdramDataReady #= false
        } else dut.sdramCd.waitSampling()
      }
    }

    dut.sdramCd.waitSampling(10); dut.clockDomain.waitSampling(10)
    dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
    var t = 2000
    while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
    assert(t > 0, "bootDone timeout")

    // Run several full lines. On each line: drive col=hCounter through the line,
    // pulse fetchGrant at hCounter==hActive (prefetch next line), and during the
    // active region sample io.bitmapByte and compare to the CURRENT line's source.
    var mismatches = 0; var checks = 0
    val firstMismatch = scala.collection.mutable.ArrayBuffer[String]()
    // prime: prefetch line 0 before its display line
    dut.io.fetchLine #= 0
    dut.io.fetchGrant #= true; dut.clockDomain.waitSampling(4); dut.io.fetchGrant #= false
    dut.clockDomain.waitSampling(900) // let line 0 fetch land

    for (dispLine <- 0 until 4) {
      for (h <- 0 until hTotal) {
        dut.io.col #= h
        // prefetch next line at hActive
        if (h == hActive) {
          dut.io.fetchLine #= ((dispLine + 1) * 2) // VdpTop fillLine ~ next display line; source = >>1
          dut.io.fetchGrant #= true
        }
        if (h == hActive + 4) dut.io.fetchGrant #= false
        dut.clockDomain.waitSampling()
        // sample during active region, at even columns (one source pixel / 2 cols)
        if (h < hActive && (h % 2 == 0)) {
          sleep(1)
          val got = dut.io.bitmapByte.toInt
          val pixel = h / 2
          val exp = srcByte(dispLine, pixel)
          checks += 1
          if (got != exp) {
            mismatches += 1
            if (firstMismatch.size < 6)
              firstMismatch += f"line=$dispLine pixel=$pixel col=$h got=0x$got%02X exp=0x$exp%02X"
          }
        }
      }
    }
    println(f"[sim] concurrent checks=$checks mismatches=$mismatches (${100.0*mismatches/checks}%.1f%%)")
    firstMismatch.foreach(m => println(s"[sim]   $m"))
    if (mismatches == 0) println("[sim] BitmapRowFetchConcurrentSim: PASS — full-frame fill/scanout coherent")
    else println("[sim] BitmapRowFetchConcurrentSim: FAIL — reproduces the HW wrong-content race")
  }
}
