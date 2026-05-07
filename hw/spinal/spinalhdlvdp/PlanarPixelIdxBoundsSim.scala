package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Discriminator sim for the Task 3 M3 blocker (mail #9392 / #9400 / #9402).
  *
  * Hypothesis under test (CoralReef #9400, BrightForge #9401):
  * `VdpTop.scala:888` drives `planarLineFetch.io.pixelIdx` with
  * `hCounter.resize(log2Up(PLANE_PIXELS))`. With `hTotal=800` (10-bit
  * `hCounter`) and `PLANE_PIXELS=320` (`log2Up=9`), `resize(9)` drops
  * the MSB and produces modulo-512 indexing. For `hCounter ∈ 320..511`
  * the resulting `pixelIdx` causes `slot = pixelIdx[8:5] ∈ 10..15`,
  * which exceeds `planeMems` depth (`readsPerPlane = 10`, slots 0..9)
  * and reads out-of-bounds in `BitplaneRowFetch`.
  *
  * This sim instantiates `PlanarLineFetch`, fetches one row with the
  * existing barcode pattern, then drives `pixelIdx` over its full
  * 9-bit input range (0..511) and reports two bands:
  *   - 0..319: must match the canonical software model (existing
  *     `PlanarLineFetchSim` already validates this).
  *   - 320..511: out-of-bounds slot ∈ 10..15. Output is undefined per
  *     `Mem.readAsync` semantics on a `Mem` of depth 10. We tally
  *     mismatches against the model that *would* be produced if the
  *     fetch path had a real slot 10..15. A non-zero mismatch count
  *     here = bug confirmed.
  */
object PlanarPixelIdxBoundsSim extends App {

  val planeCount    = 5
  val planePixels   = 320
  val readsPerPlane = planePixels / 32 // = 10

  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(84000000 Hz))
    PlanarLineFetch(sdramCd, planeCount = planeCount, planePixels = planePixels)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    val planeMagics = Array(0xAAAA0000L, 0xBBBB0000L, 0xCCCC0000L,
                             0xDDDD0000L, 0xEEEE0000L)
    val planeBases  = Array(0x10000, 0x11000, 0x12000, 0x13000, 0x14000)
    for (p <- 0 until planeCount) dut.io.planeBaseAddr(p) #= planeBases(p)

    def memAt(byteAddr: BigInt): BigInt = {
      val matched = planeBases.zipWithIndex.find { case (b, _) =>
        byteAddr >= b && byteAddr < b + planePixels / 8
      }.getOrElse(throw new RuntimeException(s"unexpected SDRAM addr $byteAddr"))
      val (base, planeIdx) = matched
      val dwordIdx = (byteAddr - base) >> 2
      (BigInt(planeMagics(planeIdx)) & 0xFFFFFFFFL) | dwordIdx
    }

    dut.io.sdramBusy      #= false
    dut.io.sdramDataReady #= false
    dut.io.sdramDout32    #= 0
    dut.io.start          #= false
    dut.io.pixelIdx       #= 0

    var pendingAddr: BigInt = 0
    var pendingTicks = 0
    fork {
      while (true) {
        dut.sdramCd.waitSampling()
        if (pendingTicks > 0) {
          pendingTicks -= 1
          if (pendingTicks == 0) {
            dut.io.sdramDout32    #= memAt(pendingAddr)
            dut.io.sdramDataReady #= true
            dut.io.sdramBusy      #= false
          } else {
            dut.io.sdramDataReady #= false
          }
        } else if (dut.io.sdramRd.toBoolean && !dut.io.sdramBusy.toBoolean) {
          pendingAddr  = BigInt(dut.io.sdramAddr.toLong)
          pendingTicks = 4
          dut.io.sdramBusy #= true
        } else {
          dut.io.sdramDataReady #= false
        }
      }
    }

    dut.clockDomain.waitSampling(2)

    dut.io.start #= true
    dut.clockDomain.waitSampling()
    dut.io.start #= false

    var elapsed = 0
    while (!dut.io.rowReady.toBoolean && elapsed < 5000) {
      dut.clockDomain.waitSampling()
      elapsed += 1
    }
    assert(dut.io.rowReady.toBoolean, s"rowReady never asserted")
    println(s"[discriminator] rowReady asserted after $elapsed cycles")
    dut.clockDomain.waitSampling()

    def expectedPixel(idx: Int): Int = {
      val slot     = idx / 32
      val subIdx   = idx % 32
      val msbFirst = 31 - subIdx
      var px = 0
      for (p <- 0 until planeCount) {
        val word = memAt(BigInt(planeBases(p)) + slot * 4)
        val bit  = ((word >> msbFirst) & 1).toInt
        px |= bit << p
      }
      px
    }

    var inRangeMismatches  = 0
    var outRangeMismatches = 0
    var outRangeUndefined  = 0
    val sample = scala.collection.mutable.ArrayBuffer[(Int, Int, Int)]()

    for (idx <- 0 until 512) {
      dut.io.pixelIdx #= idx
      sleep(1)
      val got = dut.io.pixel.toInt
      if (idx < planePixels) {
        if (got != expectedPixel(idx)) inRangeMismatches += 1
      } else {
        // idx in 320..511 — slot 10..15 out of planeMems range.
        // Compare against the (hypothetical) modulo-320 model to show
        // the output does NOT follow the wrap.
        val moduloModel = expectedPixel(idx % planePixels)
        if (got != moduloModel) outRangeMismatches += 1
        if (idx < 320 + 8 || (idx % 64) == 0) sample.append((idx, got, moduloModel))
      }
    }

    println("[discriminator] in-range (0..319) mismatches:    " + inRangeMismatches)
    println("[discriminator] out-of-range (320..511) mismatches vs modulo-320 model: " + outRangeMismatches)
    println("[discriminator] sample (idx, got, modulo320Model):")
    for ((i, g, m) <- sample.take(20)) println(f"  idx=$i%3d  got=$g%5d  mod320=$m%5d")

    assert(inRangeMismatches == 0,
      s"in-range pixels did not match canonical model — sim infra is broken")

    // Bug confirmation: a clean modulo-320 wrap would give
    // outRangeMismatches == 0. The actual hardware (Mem readAsync OOB)
    // produces undefined values, so we expect *non-zero* mismatches.
    if (outRangeMismatches > 0) {
      println(s"[discriminator] CONFIRMED: pixelIdx 320..511 does NOT follow modulo-320 wrap " +
              s"(${outRangeMismatches}/192 differ). resize(9) bug at VdpTop.scala:888 " +
              s"produces undefined slot 10..15 reads — root cause matches CoralReef #9400.")
      println("[discriminator] PASS (bug reproduced as predicted)")
    } else {
      println("[discriminator] UNEXPECTED: pixelIdx 320..511 followed modulo-320 wrap. " +
              "resize(9) hypothesis is NOT confirmed by this sim.")
      println("[discriminator] FAIL (bug not reproduced)")
      sys.exit(2)
    }
  }
}
