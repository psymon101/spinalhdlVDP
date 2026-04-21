package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 29 Checkpoint A — sprite collision + overflow status proof.
  *
  * Cases:
  *   1. Sprite 0 disabled → no collision; `STATUS_STICKY` bits 4 & 5 stay 0.
  *   2. Sprite 0 enabled at a position overlapping the default
  *      BasicPatternSource tile map → bits 4 (SPRITE_0_HIT) and 5
  *      (SPRITE_BG_HIT) both latch.
  *   3. Write-1-to-clear: host writes 0x30 to 0x0320 → bits 4 & 5 clear.
  *   4. Re-trigger: collision pulse re-sets bits once cleared.
  */
object SpriteCollisionSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init (shared with VdpTopSim).
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000
    dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000
    dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000
    dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000
    dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
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

    // Step enough cycles to clear startup + reach active video.
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

    dut.clockDomain.waitSampling(oneFrame + 100)

    // === Case 1: sprite disabled → no hit ===
    busWrite(0x0320, 0xFFFF)  // clear all sticky
    dut.clockDomain.waitSampling(oneFrame + 50)
    val st1 = dut.io.statusSticky.toInt
    val bits45_1 = (st1 >> 4) & 0x3
    println(f"[sim] case1 no-sprite sticky=0x$st1%04X  bits[5:4]=0x$bits45_1%X")
    assert(bits45_1 == 0, s"case1: collision bits set with sprite disabled (0x${bits45_1.toHexString})")
    println("[sim] case1 sprite disabled — no collision — OK")

    // === Case 2: sprite 0 over non-transparent BG ===
    // Enable L0 tile background — default scroll 0; tiles are the 40x30
    // BasicPatternSource map which has non-transparent pixels at most
    // positions. Enable sprite 0 at (100, 100) with pattern 0 (diamond
    // shape, non-transparent interior).
    dut.io.sprite0X #= 100
    dut.io.sprite0Y #= 100
    dut.io.sprite0Enabled #= true
    dut.io.sprite0PatternIdx #= 0
    // Clear sticky before observation window.
    busWrite(0x0320, 0xFFFF)
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st2 = dut.io.statusSticky.toInt
    val bit4_2 = (st2 >> 4) & 0x1
    val bit5_2 = (st2 >> 5) & 0x1
    println(f"[sim] case2 sprite-over-bg sticky=0x$st2%04X  bit4=$bit4_2  bit5=$bit5_2")
    assert(bit4_2 == 1, "case2: SPRITE_0_HIT (bit 4) should be set")
    assert(bit5_2 == 1, "case2: SPRITE_BG_HIT (bit 5) should be set")
    println("[sim] case2 sprite over BG — both bits set — OK")

    // === Case 3: write-1-to-clear ===
    busWrite(0x0320, 0x0030)  // clear bits 4 & 5
    dut.clockDomain.waitSampling(2)
    val st3 = dut.io.statusSticky.toInt
    // Bits 4 & 5 may immediately re-set if the hit is ongoing during this
    // very cycle; but the write should have taken effect at the clear
    // instant. Reset sprite off-screen before clear to guarantee no race.
    dut.io.sprite0Enabled #= false
    dut.io.sprite0X #= 1000
    dut.io.sprite0Y #= 1000
    dut.clockDomain.waitSampling(200)
    busWrite(0x0320, 0x0030)
    dut.clockDomain.waitSampling(200)
    val st3b = dut.io.statusSticky.toInt
    val bits45_3 = (st3b >> 4) & 0x3
    println(f"[sim] case3 post-clear sticky=0x$st3b%04X  bits[5:4]=0x$bits45_3%X")
    assert(bits45_3 == 0, s"case3: collision bits should clear when sprite off-screen (got 0x${bits45_3.toHexString})")
    println("[sim] case3 write-1-to-clear — OK")

    // === Case 4: re-trigger ===
    dut.io.sprite0Enabled #= true
    dut.io.sprite0X #= 150
    dut.io.sprite0Y #= 150
    dut.clockDomain.waitSampling(oneFrame + 200)
    val st4 = dut.io.statusSticky.toInt
    val bit4_4 = (st4 >> 4) & 0x1
    val bit5_4 = (st4 >> 5) & 0x1
    println(f"[sim] case4 re-trigger sticky=0x$st4%04X  bit4=$bit4_4  bit5=$bit5_4")
    assert(bit4_4 == 1 && bit5_4 == 1, "case4: re-trigger should set both bits again")
    println("[sim] case4 collision re-triggers after clear — OK")

    println("[sim] SpriteCollisionSim: PASS")
  }
}
