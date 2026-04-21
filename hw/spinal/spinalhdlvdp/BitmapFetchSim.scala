package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 44 Checkpoint A — BitmapFetch pixel decoder unit sim.
  *
  * Validates:
  *   - 1bpp decode: bit==0 → paper colour; bit==1 → ink colour.
  *   - 1bpp bright flag propagates to paletteBank bit 0.
  *   - 2bpp decode: pair selects one of four slots; slot value
  *     becomes pixelIndex low 2 bits.
  *   - Bit ordering: leftmost pixel is bit [7] for 1bpp and bits
  *     [7:6] for 2bpp (MSB-first).
  */
object BitmapFetchSim extends App {
  Config.sim.compile(BitmapFetch()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.bitmapByte #= 0
    dut.io.attrByte   #= 0
    dut.io.pixelWithinByte #= 0
    dut.io.bpp #= 0
    dut.clockDomain.waitSampling(2)

    // === Case 1: 1bpp, bit==0 everywhere → pixelIndex == paper ===
    dut.io.bpp #= 0
    dut.io.bitmapByte #= 0x00       // all pixels = 0 → paper
    // attr: flash=0, bright=1, paper=5, ink=2 → 0x6A
    dut.io.attrByte   #= 0x6A
    for (p <- 0 until 8) {
      dut.io.pixelWithinByte #= p
      dut.clockDomain.waitSampling(); sleep(1)
      val idx  = dut.io.pixelIndex.toInt
      val bank = dut.io.paletteBank.toInt
      assert(idx  == 0x5,  s"case1 px=$p pixelIndex got $idx  exp 5")
      assert(bank == 0x1,  s"case1 px=$p bank got $bank exp 1 (bright)")
    }
    println("[sim] case1 1bpp all-0 → paper=5 + bright=1 — OK")

    // === Case 2: 1bpp, bit==1 everywhere → pixelIndex == ink ===
    dut.io.bitmapByte #= 0xFF       // all pixels = 1 → ink
    for (p <- 0 until 8) {
      dut.io.pixelWithinByte #= p
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.pixelIndex.toInt == 0x2, s"case2 px=$p pixelIndex got ${dut.io.pixelIndex.toInt} exp 2")
      assert(dut.io.paletteBank.toInt == 0x1, s"case2 px=$p bank got ${dut.io.paletteBank.toInt} exp 1")
    }
    println("[sim] case2 1bpp all-1 → ink=2 + bright=1 — OK")

    // === Case 3: 1bpp bit ordering — alternating bits ===
    dut.io.bitmapByte #= 0xAA       // 10101010: px 0,2,4,6 = 1; px 1,3,5,7 = 0
    // attr: bright=0, paper=3, ink=6 → 0x1E
    dut.io.attrByte   #= 0x1E
    for (p <- 0 until 8) {
      dut.io.pixelWithinByte #= p
      dut.clockDomain.waitSampling(); sleep(1)
      val expected = if ((p % 2) == 0) 6 else 3
      assert(dut.io.pixelIndex.toInt == expected,
             s"case3 px=$p pixelIndex got ${dut.io.pixelIndex.toInt} exp $expected")
    }
    println("[sim] case3 1bpp bit ordering (0xAA pattern) — OK")

    // === Case 4: 2bpp decode ===
    // pair[0..3] = { byte[7:6], byte[5:4], byte[3:2], byte[1:0] }
    // pixelWithinByte 0 & 1 → pair 3 (bits [7:6])
    // pixelWithinByte 2 & 3 → pair 2 (bits [5:4])
    // pixelWithinByte 4 & 5 → pair 1 (bits [3:2])
    // pixelWithinByte 6 & 7 → pair 0 (bits [1:0])
    //
    // Attribute: slot0=0, slot1=1, slot2=2, slot3=3 → 0xE4
    //   attr[1:0]=00, attr[3:2]=01, attr[5:4]=10, attr[7:6]=11
    //   = 11_10_01_00 = 0xE4
    dut.io.bpp        #= 1
    dut.io.attrByte   #= 0xE4
    // bitmapByte = 0xE4 means left-to-right pixel pairs = 11,10,01,00
    dut.io.bitmapByte #= 0xE4
    val expected = Seq(3, 3, 2, 2, 1, 1, 0, 0)  // px 0..7
    for (p <- 0 until 8) {
      dut.io.pixelWithinByte #= p
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.pixelIndex.toInt == expected(p),
             s"case4 px=$p pixelIndex got ${dut.io.pixelIndex.toInt} exp ${expected(p)}")
    }
    println("[sim] case4 2bpp 4-pair decode — OK")

    println("[sim] BitmapFetchSim: PASS")
  }
}
