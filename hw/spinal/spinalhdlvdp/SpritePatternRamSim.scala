package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VdpTop-scoped unit sim for the Sprite Pattern Memory Foundation
  * primitive (CyanPeak #8596).
  *
  * Cases:
  *   1. Reset state — slot 0 (entries 0..255) holds the legacy diamond
  *      pattern, slot 1 (entries 256..511) holds the legacy cross,
  *      slots 2..15 are zero (transparent default).
  *   2. Pointer write at 0x0D11 sets the next data-write address.
  *   3. Streaming data writes at 0x0D10 fill consecutive RAM entries
  *      and increment the pointer (16-pixel run, slot 5).
  *   4. Slot 5 contents read back exactly the streamed pattern; slots
  *      0/1 unchanged (no cross-slot writes).
  *   5. Pointer wrap: writing data with ptr=0xFFF advances to 0x000.
  */
object SpritePatternRamSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init mirroring VdpTopSim/SpriteBusViaVdpTopSim.
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

    // Per-slot replication keeps every Mem identical; sim probes slot 0.
    def ramAt(idx: Int): Int = dut.spritePatternRams(0).getBigInt(idx).toInt

    // --- Case 1: reset state — slots 0/1 = diamond/cross, 2..15 = 0 ---
    val expectedSlot0 = VdpTop.spritePatternData.flatten
    val expectedSlot1 = VdpTop.sprite1PatternData.flatten
    for (i <- 0 until 256) {
      val got0 = ramAt(i)
      assert(got0 == expectedSlot0(i),
        s"Case 1 slot0 entry $i: expected ${expectedSlot0(i)} got $got0")
    }
    for (i <- 0 until 256) {
      val got1 = ramAt(256 + i)
      assert(got1 == expectedSlot1(i),
        s"Case 1 slot1 entry $i: expected ${expectedSlot1(i)} got $got1")
    }
    for (slot <- 2 until 16) {
      val base = slot * 256
      for (off <- 0 until 256) {
        val got = ramAt(base + off)
        assert(got == 0, s"Case 1 slot$slot entry $off: expected 0, got $got")
      }
    }
    println("[sim] Case 1 reset state — diamond at slot 0, cross at slot 1, 14 zero slots — OK")

    // --- Case 2: pointer write sets address ---
    busPulse(0x0D11, 5 * 256)   // point at slot 5 base
    // No way to read back patternRamPtr directly without simPublic, but we
    // verify behaviour via Case 3's data writes.

    // --- Case 3: stream a known pattern into slot 5 ---
    // Pattern: 16 unique values 0..15 repeated 16 times = 256 entries.
    val streamed = (0 until 256).map(i => i & 0xF)
    for (px <- streamed) busPulse(0x0D10, px)

    // --- Case 4: slot 5 read-back; slots 0/1 unchanged ---
    for (i <- 0 until 256) {
      val got = ramAt(5 * 256 + i)
      assert(got == streamed(i),
        s"Case 4 slot5 entry $i: expected ${streamed(i)} got $got")
    }
    for (i <- 0 until 256) {
      assert(ramAt(i)         == expectedSlot0(i), s"Case 4 slot0 corrupted at $i")
      assert(ramAt(256 + i)   == expectedSlot1(i), s"Case 4 slot1 corrupted at $i")
    }
    println("[sim] Case 3+4 streaming write to slot 5 readback correct — OK")

    // --- Case 5: pointer wrap at the top of the 14-bit pointer range
    // (Task 53 #9419: depth grew 4096 → 16384). Write a sentinel after
    // wrap and verify it lands at entry 0 (overwriting diamond[0]). ---
    busPulse(0x0D11, 0x3FFF)
    busPulse(0x0D10, 0xA)        // writes RAM[0x3FFF] = 0xA, ptr → 0x0000
    busPulse(0x0D10, 0xB)        // writes RAM[0x0000] = 0xB, ptr → 0x0001
    assert(ramAt(0x3FFF) == 0xA, s"Case 5: wrap-write at 0x3FFF: expected 0xA, got ${ramAt(0x3FFF)}")
    assert(ramAt(0x0000) == 0xB, s"Case 5: post-wrap write at 0x0000: expected 0xB, got ${ramAt(0x0000)}")
    println("[sim] Case 5 pointer wrap 0x3FFF → 0x0000 — OK")

    println("[sim] SpritePatternRamSim: PASS")
  }
}
