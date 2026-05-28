package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Sanity-check for the generator-fix 2bpp-planar sample blob (#10844).
  *
  * Feeds the raw .bin bytes the generator emits through the SAME
  * `BitplaneReconstruct(planeCount = 2, planeWidth = 16)` instance the
  * production `SdramTileAttributeFetch` planar path uses (`planarRecon`),
  * with the exact byte->plane extraction the fetch FSM performs:
  *   plane0 = word0[15:0]  (unpackRow(15:0))
  *   plane1 = word1[15:0]  (unpackRow(47:32))
  * and the MSB-first pixel rule  pixel x = {plane1(15-x), plane0(15-x)}.
  */
object Gen2bppBlobCheckSim extends App {
  // Generator sample blob (little-endian 8-byte tile row).
  val blob = Array(0x01, 0x80, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00)
  val expectedPixels = Array(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

  // Reproduce the fetch-FSM byte->word->plane extraction.
  def leWord(b: Array[Int], o: Int): Long =
    (b(o) & 0xFFL) | ((b(o + 1) & 0xFFL) << 8) |
      ((b(o + 2) & 0xFFL) << 16) | ((b(o + 3) & 0xFFL) << 24)
  val word0 = leWord(blob, 0)
  val word1 = leWord(blob, 4)
  val plane0 = (word0 & 0xFFFF).toInt
  val plane1 = (word1 & 0xFFFF).toInt

  Config.sim.compile(BitplaneReconstruct(planeCount = 2, planeWidth = 16)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.planes(0) #= plane0
    dut.io.planes(1) #= plane1

    println(f"[sim] blob=${blob.map(x => f"$x%02x").mkString(" ")}")
    println(f"[sim] word0=0x$word0%08x word1=0x$word1%08x plane0=0x$plane0%04x plane1=0x$plane1%04x")

    var allOk = true
    val decoded = new Array[Int](16)
    for (x <- 0 until 16) {
      dut.io.bitIdx #= x            // bitIdx counts from the LEFT; comp flips to (15-x)
      sleep(1)
      val got = dut.io.pixel.toInt
      decoded(x) = got
      if (got != expectedPixels(x)) {
        allOk = false
        println(f"[sim] MISMATCH x=$x: expected ${expectedPixels(x)} got $got")
      }
    }
    println(s"[sim] decoded = ${decoded.mkString("[", ",", "]")}")
    println(s"[sim] expected= ${expectedPixels.mkString("[", ",", "]")}")
    assert(allOk, "Gen2bppBlobCheckSim: decoded pixels did not match generator intent")
    println("[sim] Gen2bppBlobCheckSim: PASS")
  }
}
