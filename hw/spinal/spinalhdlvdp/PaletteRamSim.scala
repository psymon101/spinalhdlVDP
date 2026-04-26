package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VdpTop-scoped unit sim for the runtime-writable palette RAM
  * (Color/Window Hardening, mail #8629).
  *
  * Cases:
  *   1. Reset state — palette RAM holds the legacy `paletteInit` content
  *      from `TileAttributeAssets` (8 banks × 16 entries = 128 RGB888).
  *   2. Pointer write at 0x0601 sets the next data-write half-pointer;
  *      a paired data write (low 16 = G:B, then high 8 = R) commits the
  *      24-bit entry into the addressed location.
  *   3. Streaming consecutive entries: pointer auto-increments through
  *      the `data,data,data,...` stream; alternating low/high halves
  *      commit one new RGB entry every two writes.
  *   4. Bank-0 (sprite/legacy) entries written in case 3 read back
  *      exactly; untouched banks unchanged.
  *   5. Pointer wrap: with ptr=0xFE (entry 127, low half), two writes
  *      commit entry 127 and roll the pointer to 0x00 (entry 0 low),
  *      so the next pair overwrites entry 0.
  */
object PaletteRamSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init mirroring SpritePatternRamSim.
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.layer2ScrollX #= 0; dut.io.layer2ScrollY #= 0
    dut.io.layer3ScrollX #= 0; dut.io.layer3ScrollY #= 0
    dut.io.sprite0X #= 1023; dut.io.sprite0Y #= 1023; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1023; dut.io.sprite1Y #= 1023; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1023; dut.io.sprite2Y #= 1023; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1023; dut.io.sprite3Y #= 1023; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 0
    dut.io.layer0UseSdram #= false
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.bitmapSdramByte #= 0
    dut.io.bitmapSdramAttrByte #= 0
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.statusEvQspiReady #= false; dut.io.statusEvQspiError #= false
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false

    dut.clockDomain.waitSampling(10)

    def busPulse(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
      dut.clockDomain.waitSampling()
    }

    def paletteAt(idx: Int): BigInt = dut.palette.getBigInt(idx)
    def packRgb(r: Int, g: Int, b: Int): BigInt =
      (BigInt(r & 0xFF) << 16) | (BigInt(g & 0xFF) << 8) | BigInt(b & 0xFF)

    // Build the expected init from the same generator the design uses.
    val expectedInit: Seq[BigInt] =
      (0 until TileAttributeAssets.PaletteBanks)
        .flatMap(b => TileAttributeAssets.ramp(b))

    // --- Case 1: reset state matches paletteInit ---
    for (i <- 0 until TileAttributeAssets.PaletteDepth) {
      val got = paletteAt(i)
      assert(got == expectedInit(i),
        f"Case 1 entry $i%3d: expected 0x${expectedInit(i)}%06X, got 0x$got%06X")
    }
    println("[sim] Case 1 reset state matches TileAttributeAssets.paletteInit — OK")

    // --- Case 2: write entry 0 to a known sentinel (0xAA, 0xBB, 0xCC) ---
    busPulse(0x0601, 0x00)              // ptr = 0 (entry 0, low half)
    busPulse(0x0600, 0xBBCC)            // low half: G=0xBB, B=0xCC; ptr -> 1
    busPulse(0x0600, 0x00AA)            // high half: R=0xAA; commits entry 0; ptr -> 2
    val expected0 = packRgb(0xAA, 0xBB, 0xCC)
    val got0 = paletteAt(0)
    assert(got0 == expected0,
      f"Case 2 entry 0: expected 0x$expected0%06X got 0x$got0%06X")
    println("[sim] Case 2 single-entry write (entry 0 ← 0xAABBCC) — OK")

    // --- Case 3: stream entries 1..7 with distinct sentinels ---
    // Pointer is currently at 2 (entry 1, low half) after case 2.
    val streamed = (1 to 7).map(i => (i * 0x10, i * 0x20, i * 0x30))
    for ((r, g, b) <- streamed) {
      busPulse(0x0600, ((g & 0xFF) << 8) | (b & 0xFF))
      busPulse(0x0600, r & 0xFF)
    }
    for ((triple, k) <- streamed.zipWithIndex) {
      val (r, g, b) = triple
      val idx = 1 + k
      val want = packRgb(r, g, b)
      val have = paletteAt(idx)
      assert(have == want,
        f"Case 3 entry $idx%3d: expected 0x$want%06X got 0x$have%06X")
    }
    println("[sim] Case 3 streaming entries 1..7 — OK")

    // --- Case 4: untouched higher banks remain at init values ---
    for (i <- 16 until TileAttributeAssets.PaletteDepth) {
      val got = paletteAt(i)
      assert(got == expectedInit(i),
        f"Case 4 untouched entry $i%3d: expected 0x${expectedInit(i)}%06X got 0x$got%06X")
    }
    println("[sim] Case 4 untouched banks unchanged — OK")

    // --- Case 5: pointer wrap at 0xFE (entry 127 low) → 0x00 ---
    busPulse(0x0601, 0xFE)              // ptr = 0xFE (entry 127 low half)
    busPulse(0x0600, 0x1234)            // low half: G=0x12, B=0x34; ptr -> 0xFF
    busPulse(0x0600, 0x0056)            // high: R=0x56; commits entry 127; ptr -> 0x00
    busPulse(0x0600, 0x789A)            // low half: G=0x78, B=0x9A; ptr -> 0x01
    busPulse(0x0600, 0x00DE)            // high: R=0xDE; commits entry 0; ptr -> 0x02
    val expect127 = packRgb(0x56, 0x12, 0x34)
    val expect000 = packRgb(0xDE, 0x78, 0x9A)
    assert(paletteAt(127) == expect127,
      f"Case 5 entry 127: expected 0x$expect127%06X got 0x${paletteAt(127)}%06X")
    assert(paletteAt(0) == expect000,
      f"Case 5 entry 0 post-wrap: expected 0x$expect000%06X got 0x${paletteAt(0)}%06X")
    println("[sim] Case 5 pointer wrap at 0xFF → 0x00 — OK")

    println("[sim] PaletteRamSim: PASS")
  }
}
