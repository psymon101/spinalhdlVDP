package spinalhdlvdp

import spinal.core._

/** Task 44b — linear bitmap + attribute row fetch.
  *
  * Delivers `bitmapByte` / `attrByte` to the existing `BitmapFetch`
  * decoder (Task 44) for a given per-pixel column. Addressing is purely
  * linear:
  *   bitmapAddr = bitmapBase + fetchLine × bitmapStride + col / 8
  *   attrAddr   = attrBase   + fetchLine × attrStride   + col / cellWidth
  *
  * Checkpoint A (this commit): the row data is held in an on-chip
  * `Mem` (dual-port read-async) that is pre-populated with a test
  * bitmap via `initialContent`. This proves the linear-addressing
  * contract end-to-end at the simulation level. Checkpoint B replaces
  * the on-chip ROM with a live SDRAM-domain row buffer (ping-pong +
  * double-latch CDC) driven by scheduler client 1.
  *
  * GT-022 compliance: store size is power-of-two.
  */
case class BitmapRowFetch(
    storeBytes:  Int = 1024,    // power-of-two; holds bitmap + attribute bytes
    cellShift:   Int = 3        // log2(attr cell width); 3 => 8-pixel cell
) extends Component {
  require(isPow2(storeBytes), s"storeBytes must be power-of-two, got $storeBytes")

  val addrBits = log2Up(storeBytes)

  val io = new Bundle {
    val fetchLine     = in  UInt(10 bits)
    val bitmapBase    = in  UInt(addrBits bits)
    val bitmapStride  = in  UInt(10 bits)
    val attrBase      = in  UInt(addrBits bits)
    val attrStride    = in  UInt(10 bits)
    val col           = in  UInt(10 bits)
    val bitmapByte    = out Bits(8 bits)
    val attrByte      = out Bits(8 bits)
  }

  // ---------------------------------------------------------------------
  // Test ROM for CP-A. Holds a distinguishable pattern so the sim can
  // match against expected values. Half the store holds the bitmap
  // region; the other half holds attributes. CP-B replaces this with
  // an SDRAM-backed line buffer.
  //
  // Contents:
  //   index 0..storeBytes/2-1   = "bitmap" region
  //       byte[i] = (i[7:0] XOR i[15:8])
  //   index storeBytes/2..end-1 = "attribute" region
  //       byte[i] = i[7:0] (simple ramp — distinct from bitmap)
  // ---------------------------------------------------------------------
  val initData: Seq[Bits] = (0 until storeBytes).map { i =>
    if (i < storeBytes / 2) {
      val lo = i & 0xFF
      val hi = (i >> 8) & 0xFF
      B((lo ^ hi) & 0xFF, 8 bits)
    } else {
      B(i & 0xFF, 8 bits)
    }
  }
  val mem = Mem(Bits(8 bits), storeBytes).init(initData)

  // ---------------------------------------------------------------------
  // Address computation (pixel domain, combinational for CP-A; CP-B
  // will latch per-line into a line buffer).
  // ---------------------------------------------------------------------
  val bitmapCol = io.col(9 downto 3)                                       // col / 8
  val attrCol   = (io.col >> cellShift).resize(10)                          // col / cellWidth

  val lineTimesBitmapStride = (io.fetchLine.resize(addrBits) *
                               io.bitmapStride.resize(addrBits)).resize(addrBits)
  val bitmapAddr = (io.bitmapBase + lineTimesBitmapStride +
                    bitmapCol.resize(addrBits)).resize(addrBits)

  val lineTimesAttrStride = (io.fetchLine.resize(addrBits) *
                             io.attrStride.resize(addrBits)).resize(addrBits)
  val attrAddr = (io.attrBase + lineTimesAttrStride +
                  attrCol.resize(addrBits)).resize(addrBits)

  io.bitmapByte := mem.readAsync(bitmapAddr)
  io.attrByte   := mem.readAsync(attrAddr)
}
