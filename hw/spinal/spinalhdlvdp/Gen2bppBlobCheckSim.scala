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
    println("[sim] Gen2bppBlobCheckSim 2bpp-planar: PASS")
  }

  // -------- 4bpp packed golden vector (#10848 reconciled) ------------------
  // The 4bpp path has no separate reconstruction component; HW decode is the
  // inline slice  px4Packed = unpackRow.subdivideIn(4 bits)(unpackIdx)  where
  // subdivideIn index 0 = unpackRow[3:0] = byte0 low nibble = leftmost pixel.
  // So pixel(2n) = byte n low nibble, pixel(2n+1) = byte n high nibble. This
  // block pins the generator's chunky LSB-first byte output to that HW
  // convention. Generator test image was (x+y)%16, so row 0 is a 0..15 ramp.
  {
    val blob4 = Array(0x10, 0x32, 0x54, 0x76, 0x98, 0xBA, 0xDC, 0xFE)
    val expected4 = (0 until 16).toArray            // 0..15 ramp
    val decoded4 = new Array[Int](16)
    for (n <- 0 until 8) {
      decoded4(2 * n)     = blob4(n) & 0xF          // low nibble  -> even pixel
      decoded4(2 * n + 1) = (blob4(n) >> 4) & 0xF   // high nibble -> odd pixel
    }
    println(s"[sim] 4bpp blob    = ${blob4.map(x => f"$x%02x").mkString(" ")}")
    println(s"[sim] 4bpp decoded = ${decoded4.mkString("[", ",", "]")}")
    println(s"[sim] 4bpp expected= ${expected4.mkString("[", ",", "]")}")
    assert(decoded4.sameElements(expected4),
      s"Gen2bppBlobCheckSim 4bpp golden mismatch: got ${decoded4.mkString(",")}")
    println("[sim] Gen2bppBlobCheckSim 4bpp-packed: PASS")
  }
}
