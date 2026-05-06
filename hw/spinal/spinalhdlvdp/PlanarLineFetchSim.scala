package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** End-to-end sim for `PlanarLineFetch` — the composite of H-1
  * (`BitplaneReconstruct`) and H-2 (`BitplaneRowFetch`).
  *
  * Synthesises a synthetic SDRAM that stores a deterministic
  * "barcode-like" pattern: at byte address `A`, dword index
  * `i = A/4` returns `0xAAAA0000 | i` for plane 0, `0xBBBB0000 | i`
  * for plane 1, etc. (different magic per plane).
  *
  * After triggering one row fetch and `rowReady`, sweeps `pixelIdx`
  * 0..planePixels-1 and verifies that every pixel matches the
  * software-computed expectation: for each pixel, look up the matching
  * 32-bit word in each plane (slot = idx/32) and the matching bit
  * within (subBitIdx = idx mod 32, MSB-first), then assemble the
  * reconstructed pixel.
  */
object PlanarLineFetchSim extends App {

  val planeCount  = 5
  val planePixels = 320
  val readsPerPlane = planePixels / 32

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

    // SDRAM byte-address → 32-bit value lookup (same per-plane biasing
    // as the row-fetch sim, but distinct magic per plane base region).
    def memAt(byteAddr: BigInt): BigInt = {
      // figure out which plane base this address falls into, derive the
      // magic, and OR with the dword index within that plane.
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
    println(s"[sim] rowReady asserted after $elapsed cycles")

    dut.clockDomain.waitSampling()

    // Software model of the reconstruction.
    def expectedPixel(idx: Int): Int = {
      val slot      = idx / 32
      val subIdx    = idx % 32
      val msbFirst  = 31 - subIdx
      var px = 0
      for (p <- 0 until planeCount) {
        val word = memAt(BigInt(planeBases(p)) + slot * 4)
        val bit  = ((word >> msbFirst) & 1).toInt
        px |= bit << p
      }
      px
    }

    // Sweep the entire row.
    for (idx <- 0 until planePixels) {
      dut.io.pixelIdx #= idx
      sleep(1)
      val got      = dut.io.pixel.toInt
      val expected = expectedPixel(idx)
      assert(got == expected,
        s"pixelIdx=$idx: expected ${expected.toBinaryString} got ${got.toBinaryString}")
    }
    println(s"[sim] all $planePixels pixels reconstruct correctly across $planeCount planes")

    // Spot-check that the FSM goes idle and stays idle.
    for (_ <- 0 until 50) {
      dut.clockDomain.waitSampling()
      assert(!dut.io.rowReady.toBoolean, "rowReady must not re-fire")
      assert(!dut.io.busy.toBoolean,     "busy must remain false")
    }
    println("[sim] FSM idle stable — OK")

    println("[sim] PlanarLineFetchSim: PASS")
  }
}
