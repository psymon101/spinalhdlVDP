package spinalhdlvdp

import spinal.core._

/** Standard FPGA video test pattern generator for hardware validation.
  *
  * Outputs a 4-bit pixel index and 3-bit palette bank so the pattern feeds
  * directly into the existing VdpTop palette path. Patterns are chosen to
  * make timing anomalies, bandwidth drops, and color channel errors
  * unambiguous on a single static capture — no frame-by-frame analysis needed.
  *
  * Pattern list:
  *   0 = Color bars (8 vertical bars using palette indices 0..7, bank 0)
  *   1 = Solid red field    (bank 1)
  *   2 = Solid green field  (bank 2)
  *   3 = Solid blue field   (bank 3)
  *   4 = Solid gray field   (bank 4)
  *   5 = Checkerboard       (16x16 black/white tiles, bank 0)
  *   6 = Grid               (white lines every 64 px on black, bank 0)
  *   7 = Vertical stripes   (1 px black/white, highest horizontal frequency, bank 0)
  */
case class TestPatternSource() extends Component {
  val io = new Bundle {
    val x = in UInt(10 bits)
    val y = in UInt(10 bits)
    val patternSelect = in UInt(3 bits)
    val pixelIndex = out Bits(4 bits)
    val paletteBank = out UInt(3 bits)
  }

  val barWidth = 80  // 640 / 8 bars

  // Default to black
  val idx = Bits(4 bits)
  val bank = UInt(3 bits)
  idx := B(0, 4 bits)
  bank := U(0, 3 bits)

  switch(io.patternSelect) {
    // Pattern 0: Color bars (8 classic colors)
    is(U(0, 3 bits)) {
      val bar = (io.x / U(barWidth, 10 bits)).resize(3)
      bank := U(0, 3 bits)
      idx := bar.asBits.resize(4 bits)
    }

    // Pattern 1: Solid red field
    is(U(1, 3 bits)) {
      bank := U(1, 3 bits)
      idx := B(0xF, 4 bits)
    }

    // Pattern 2: Solid green field
    is(U(2, 3 bits)) {
      bank := U(2, 3 bits)
      idx := B(0xF, 4 bits)
    }

    // Pattern 3: Solid blue field
    is(U(3, 3 bits)) {
      bank := U(3, 3 bits)
      idx := B(0xF, 4 bits)
    }

    // Pattern 4: Solid gray field
    is(U(4, 3 bits)) {
      bank := U(4, 3 bits)
      idx := B(0xF, 4 bits)
    }

    // Pattern 5: Checkerboard (16x16 black/white)
    is(U(5, 3 bits)) {
      bank := U(0, 3 bits)
      val cx = io.x(4 downto 4)
      val cy = io.y(4 downto 4)
      when(cx.asBool ^ cy.asBool) {
        idx := B(0xF, 4 bits) // white
      } otherwise {
        idx := B(0x0, 4 bits) // black
      }
    }

    // Pattern 6: Grid (white lines every 64px on black)
    is(U(6, 3 bits)) {
      bank := U(0, 3 bits)
      val hLine = io.x(5 downto 0) === U(0, 6 bits)
      val vLine = io.y(5 downto 0) === U(0, 6 bits)
      when(hLine || vLine) {
        idx := B(0xF, 4 bits) // white
      } otherwise {
        idx := B(0x0, 4 bits) // black
      }
    }

    // Pattern 7: Vertical stripes (1px black/white)
    is(U(7, 3 bits)) {
      bank := U(0, 3 bits)
      when(io.x(0 downto 0) === U(1, 1 bit)) {
        idx := B(0xF, 4 bits) // white
      } otherwise {
        idx := B(0x0, 4 bits) // black
      }
    }
  }

  io.pixelIndex := idx
  io.paletteBank := bank
}
