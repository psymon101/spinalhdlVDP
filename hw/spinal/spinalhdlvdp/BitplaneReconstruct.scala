package spinalhdlvdp

import spinal.core._

/** Generic N-plane bitplane → pixel reconstruction.
  *
  * Mode0 Fetch Envelope Hardening — H-1 (per assessment §6.1).
  *
  * Given `planeCount` plane rows (each `planeWidth` bits, MSB-first by
  * convention — bit 7 is leftmost on screen) and a `bitIdx` 0..`planeWidth-1`
  * counted from the LEFT, returns the reconstructed pixel as
  * `{plane[N-1] ## plane[N-2] ## … ## plane[1] ## plane[0]}` so that
  * `plane(0)` is the pixel's LSB. This matches how Amiga / Atari ST low-res
  * bitplanes encode the colour-register index.
  *
  * The component is purely combinational and has no internal state; it
  * exists so the planar reconstruction algorithm has its own tested
  * artifact independent of the larger `SdramTileAttributeFetch` /
  * `BitplaneRowFetch` integrations that will use it.
  *
  * @param planeCount   number of bitplanes (1..8). 5 is the Amiga OCS / ST
  *                     low-res target (32 colours). 2 reproduces the
  *                     existing R4.1b 2bpp path bit-identically.
  * @param planeWidth   bits per plane row (e.g. 8 for one byte, 16 for a
  *                     half-word, 32 for `dout32` aperture).
  */
case class BitplaneReconstruct(planeCount: Int, planeWidth: Int) extends Component {
  require(planeCount >= 1 && planeCount <= 8,
    s"planeCount must be 1..8, got $planeCount")
  require(planeWidth >= 1, s"planeWidth must be ≥ 1, got $planeWidth")

  val idxWidth = log2Up(planeWidth)

  val io = new Bundle {
    val planes = in  Vec(Bits(planeWidth bits), planeCount)
    val bitIdx = in  UInt(idxWidth bits)
    val pixel  = out Bits(planeCount bits)
  }

  // MSB-first pixel ordering: bitIdx 0 selects the leftmost on-screen bit
  // (the high bit of each plane row). Mirrors `SdramTileAttributeFetch`'s
  // existing `planarBitIdx = (15 - unpackIdx)` flip for the 16-bit case.
  val msbFirstIdx = (U(planeWidth - 1, idxWidth bits) - io.bitIdx)

  io.pixel := Vec((0 until planeCount).map { i =>
    io.planes(i)(msbFirstIdx)
  }).asBits
}
