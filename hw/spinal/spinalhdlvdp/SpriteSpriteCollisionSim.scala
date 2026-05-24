package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 54 Checkpoint B — sprite-sprite collision proof.
  *
  * Verifies the set-on-collide, read-and-clear sprite-sprite collision surface:
  *   - 0x0322 SPRITE_COLL_MASK : 8-bit per-descriptor sticky mask, W1C
  *   - 0x0320 STATUS_STICKY    : new bit 6 SPRITE_SPRITE_HIT rollup, W1C
  *
  * Five cases (matches mail #9619 Checkpoint A contract):
  *   1. Two non-overlapping sprites → mask stays 0, bit 6 stays 0
  *   2. Two sprites with non-transparent pixel overlap →
  *      mask bits 0 & 1 set, bit 6 set
  *   3. Three sprites overlapping →
  *      mask bits 0, 1, 2 all set
  *   4. Write-1-to-clear → 0x0322 clears, sprites moved apart, then
  *      bit 6 of 0x0320 also clears via W1C
  *   5. One sprite alone → no sprite-sprite collision (a sprite cannot
  *      collide with itself)
  *
  * Detection mechanism (per CyanPeak audit #9620): the SpriteRasterizer
  * line-buffer entry now carries the writing sprite's descriptor index;
  * before each write the rasterizer async-reads the current entry, and
  * if both incoming and existing pixels are non-transparent it emits a
  * collision pulse with both descriptor IDs. VdpTop OR-sets the per-
  * descriptor mask. Reverse-iter draw order accumulates every
  * participating sprite's bit.
  */
object SpriteSpriteCollisionSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init.
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000
    dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000
    dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 0
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000
    dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000
    dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 0
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false
    dut.io.layer0UseSdram #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.statusEvQspiReady #= false
    dut.io.statusEvQspiError #= false

    val hTotal = 800; val vTotal = 525
    val oneFrame = hTotal * vTotal

    def busWrite(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
      dut.clockDomain.waitSampling()
    }

    def parkAllSprites(): Unit = {
      dut.io.sprite0Enabled #= false; dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000
      dut.io.sprite1Enabled #= false; dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000
      dut.io.sprite2Enabled #= false; dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000
      dut.io.sprite3Enabled #= false; dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000
    }

    def clearAllSticky(): Unit = {
      busWrite(0x0320, 0xFFFF)   // STATUS_STICKY W1C
      busWrite(0x0322, 0x00FF)   // SPRITE_COLL_MASK W1C (low byte = 8 desc)
    }

    dut.clockDomain.waitSampling(oneFrame + 100)

    // === Case 1: two non-overlapping sprites — no collision ===
    parkAllSprites()
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame)
    // sprite0 at (100,100) pattern 0; sprite1 at (300,200) pattern 0.
    // 16×16 sprites: bounding boxes don't share an X or Y range.
    dut.io.sprite0X #= 100; dut.io.sprite0Y #= 100
    dut.io.sprite0PatternIdx #= 0; dut.io.sprite0Enabled #= true
    dut.io.sprite1X #= 300; dut.io.sprite1Y #= 200
    dut.io.sprite1PatternIdx #= 0; dut.io.sprite1Enabled #= true
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st1   = dut.io.statusSticky.toInt
    val mask1 = dut.io.spriteCollMask.toInt
    val bit6_1 = (st1 >> 6) & 0x1
    println(f"[sim] case1 non-overlap sticky=0x$st1%04X bit6=$bit6_1 collMask=0x$mask1%02X")
    assert(bit6_1 == 0, s"case1: SPRITE_SPRITE_HIT (bit 6) should be 0 (got $bit6_1)")
    assert(mask1 == 0,  s"case1: SPRITE_COLL_MASK should be 0 (got 0x${mask1.toHexString})")
    println("[sim] case1 non-overlapping sprites — no collision — OK")

    // === Case 2: two overlapping sprites — bits {0,1} set ===
    parkAllSprites()
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame)
    // sprite0 and sprite1 at the SAME (100,100) with same pattern 0.
    // Identical 16×16 footprints → every non-transparent pixel of
    // pattern 0 is overlapped → collision pulses fire repeatedly.
    dut.io.sprite0X #= 100; dut.io.sprite0Y #= 100
    dut.io.sprite0PatternIdx #= 0; dut.io.sprite0Enabled #= true
    dut.io.sprite1X #= 100; dut.io.sprite1Y #= 100
    dut.io.sprite1PatternIdx #= 0; dut.io.sprite1Enabled #= true
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st2    = dut.io.statusSticky.toInt
    val mask2  = dut.io.spriteCollMask.toInt
    val bit6_2 = (st2 >> 6) & 0x1
    println(f"[sim] case2 two-overlap sticky=0x$st2%04X bit6=$bit6_2 collMask=0x$mask2%02X")
    assert(bit6_2 == 1, s"case2: SPRITE_SPRITE_HIT (bit 6) should be 1 (got $bit6_2)")
    assert((mask2 & 0x03) == 0x03,
      s"case2: mask bits 0 and 1 should be set (got 0x${mask2.toHexString})")
    println("[sim] case2 two overlapping sprites — bits {0,1} set — OK")

    // === Case 3: three overlapping sprites — bits {0,1,2} set ===
    parkAllSprites()
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame)
    dut.io.sprite0X #= 100; dut.io.sprite0Y #= 100
    dut.io.sprite0PatternIdx #= 0; dut.io.sprite0Enabled #= true
    dut.io.sprite1X #= 100; dut.io.sprite1Y #= 100
    dut.io.sprite1PatternIdx #= 0; dut.io.sprite1Enabled #= true
    dut.io.sprite2X #= 100; dut.io.sprite2Y #= 100
    dut.io.sprite2PatternIdx #= 0; dut.io.sprite2Enabled #= true
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st3    = dut.io.statusSticky.toInt
    val mask3  = dut.io.spriteCollMask.toInt
    val bit6_3 = (st3 >> 6) & 0x1
    println(f"[sim] case3 three-overlap sticky=0x$st3%04X bit6=$bit6_3 collMask=0x$mask3%02X")
    assert(bit6_3 == 1, s"case3: SPRITE_SPRITE_HIT (bit 6) should be 1 (got $bit6_3)")
    assert((mask3 & 0x07) == 0x07,
      s"case3: mask bits 0, 1, 2 should all be set (got 0x${mask3.toHexString})")
    println("[sim] case3 three overlapping sprites — bits {0,1,2} set — OK")

    // === Case 4: W1C clears the mask and the sticky bit ===
    // Start from case 3's loaded state; write 0x07 to 0x0322 to clear
    // bits {0,1,2} of the mask. Move sprites apart so no new collision
    // pulse re-asserts. Then W1C bit 6 of 0x0320.
    busWrite(0x0322, 0x07)        // clear mask bits 0,1,2
    parkAllSprites()
    dut.clockDomain.waitSampling(oneFrame + 100)
    busWrite(0x0320, 1 << 6)      // clear SPRITE_SPRITE_HIT
    dut.clockDomain.waitSampling(oneFrame + 100)
    val st4    = dut.io.statusSticky.toInt
    val mask4  = dut.io.spriteCollMask.toInt
    val bit6_4 = (st4 >> 6) & 0x1
    println(f"[sim] case4 post-W1C sticky=0x$st4%04X bit6=$bit6_4 collMask=0x$mask4%02X")
    assert(mask4 == 0,
      s"case4: SPRITE_COLL_MASK should clear after W1C (got 0x${mask4.toHexString})")
    assert(bit6_4 == 0,
      s"case4: SPRITE_SPRITE_HIT should clear after W1C (got $bit6_4)")
    println("[sim] case4 write-1-to-clear — OK")

    // === Case 5: one sprite alone — no sprite-sprite collision ===
    parkAllSprites()
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame)
    dut.io.sprite0X #= 150; dut.io.sprite0Y #= 150
    dut.io.sprite0PatternIdx #= 0; dut.io.sprite0Enabled #= true
    clearAllSticky()
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st5    = dut.io.statusSticky.toInt
    val mask5  = dut.io.spriteCollMask.toInt
    val bit6_5 = (st5 >> 6) & 0x1
    println(f"[sim] case5 lone-sprite sticky=0x$st5%04X bit6=$bit6_5 collMask=0x$mask5%02X")
    assert(bit6_5 == 0,
      s"case5: a lone sprite must not raise SPRITE_SPRITE_HIT (got $bit6_5)")
    assert(mask5 == 0,
      s"case5: a lone sprite must not set any mask bit (got 0x${mask5.toHexString})")
    println("[sim] case5 lone sprite — no sprite-sprite collision — OK")

    println("[sim] SpriteSpriteCollisionSim: PASS")
  }
}
