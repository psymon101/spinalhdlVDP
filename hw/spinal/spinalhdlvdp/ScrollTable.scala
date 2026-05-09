package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 31 — programmable scroll-offset table.
  *
  * Small `Mem`-backed lookup that adds a per-column band offset to a
  * layer's global `scrollX`. Indexed by `hCounter(9 downto 3)` (7-bit
  * address) so 128 entries cover a 640-pixel active area at 5 px per
  * band.
  *
  * Host programs entries via the bus-write port; offsets default to
  * zero so the table is a no-op until the host populates it (existing
  * global-scroll scenes render bit-identically).
  *
  * GT-022 compliance: 128 entries (power-of-two depth).
  */
case class ScrollTable(
    entries:       Int = 128,
    offsetWidth:   Int = 10
) extends Component {
  require(isPow2(entries), s"entries must be power-of-two (GT-022), got $entries")

  val addrBits = log2Up(entries)

  val io = new Bundle {
    // Read port — combinational lookup during rendering.
    val rdAddr = in  UInt(addrBits bits)
    val rdData = out UInt(offsetWidth bits)

    // Write port — bus-driven.
    val wrAddr = in  UInt(addrBits bits)
    val wrData = in  UInt(offsetWidth bits)
    val wr     = in  Bool()
  }

  val mem = Mem(UInt(offsetWidth bits), entries)
  // Task 57 test: removed init to test SSRAM inference.
  // Host must clear table before use.

  mem.write(address = io.wrAddr, data = io.wrData, enable = io.wr)
  io.rdData := mem.readAsync(io.rdAddr)
}
