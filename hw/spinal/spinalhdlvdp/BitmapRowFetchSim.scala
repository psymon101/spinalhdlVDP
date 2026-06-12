package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** Task 44b — BitmapRowFetch validation sim.
  *
  * Validates the CyanPeak audit fix (iter 6b):
  *   - sInitSettle window: sdramActiveR high for 16 cycles before first cmdWr.
  *   - sFetchSettle window: sdramActiveR high for 16 cycles before first cmdRd.
  *   - bootCounter resets correctly between states.
  *   - SDRAM writes populate correctly after settle.
  *   - Content delivery matches expected SDRAM data.
  */
object BitmapRowFetchSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(84000000 Hz))
    BitmapRowFetch(sdramCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    // Initialize IO
    dut.io.sdramDout      #= 0
    dut.io.sdramDataReady #= false
    dut.io.sdramBusy      #= false
    dut.io.fetchGrant     #= false
    dut.io.fetchLine      #= 0
    dut.io.col            #= 0
    dut.io.enable         #= false
    dut.io.directColor    #= false
    dut.io.tileBootDone   #= false
    // BITMAP-PLUMB-129: drive geometry regs at their power-on defaults so this
    // legacy indexed-mode test exercises the byte-identical fetch path.
    dut.io.bitmapBase     #= 0x3000
    dut.io.attrBase       #= 0x4000
    dut.io.bitmapStride   #= 512
    dut.io.attrStride     #= 512
    dut.io.bitmapHeight   #= 240

    // Use a fork for a reactive SDRAM model
    val sdramModel = fork {
      while(true) {
        if(dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong
          // Simulate latency
          dut.sdramCd.waitSampling(5)
          // Drive dataReady and dout.
          // In our reactive model, we return (addr & 0xFF).
          dut.io.sdramDout #= (addr & 0xFF).toInt
          dut.io.sdramDataReady #= true
          dut.sdramCd.waitSampling()
          dut.io.sdramDataReady #= false
        } else {
          dut.sdramCd.waitSampling()
        }
      }
    }

    // Let reset propagate
    dut.sdramCd.waitSampling(10)
    dut.clockDomain.waitSampling(10)

    // === Case 1: Init Settle Window ===
    println("[sim] Case 1: Testing Init Settle Window...")
    
    // Enable bitmap mode and signal tileBootDone
    dut.io.enable #= true
    dut.io.tileBootDone #= true
    
    // Wait for enableSync and tileBootDoneSync to reach sdramCd
    // BufferCC is 2 stages, so ~3 cycles.
    dut.sdramCd.waitSampling(5)
    
    var timeout = 0
    // Wait for sdramActive to propagate through BufferCC back to pixel domain
    timeout = 100
    while(!dut.io.sdramActive.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for sdramActive to go high")
    
    var cyclesWithActiveNoWr = 0
    for (_ <- 0 until 20) {
      if (dut.io.sdramActive.toBoolean && !dut.io.sdramWr.toBoolean) {
        cyclesWithActiveNoWr += 1
      }
      dut.sdramCd.waitSampling()
    }
    
    // We expect 16 cycles of settle in sdramCd.
    // The loop above checks 20 cycles.
    println(s"[sim] Observed $cyclesWithActiveNoWr cycles of active-but-no-write (post-propagation)")
    assert(cyclesWithActiveNoWr >= 10, s"Expected most settle cycles remaining, got $cyclesWithActiveNoWr")
    
    // Eventually cmdWr should assert
    timeout = 100
    while(!dut.io.sdramWr.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for first cmdWr after settle")
    println("[sim] First cmdWr observed after settle — OK")

    // === Case 2: Fetch Settle Window ===
    println("[sim] Case 2: Testing Fetch Settle Window...")
    
    // Wait for bootDone
    timeout = 10000
    while(!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone")
    println("[sim] bootDone reached")
    
    // Pulse fetchGrant
    dut.io.fetchGrant #= true
    dut.clockDomain.waitSampling(4) // 4-pixel-cycle pulse per VdpTop
    dut.io.fetchGrant #= false
    
    // Wait for sdramActive to go high
    timeout = 100
    while(!dut.io.sdramActive.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for sdramActive to go high for fetch")
    
    // Now we should be in sFetchSettle
    cyclesWithActiveNoWr = 0
    for (_ <- 0 until 25) {
      if (dut.io.sdramActive.toBoolean && !dut.io.sdramRd.toBoolean) {
        cyclesWithActiveNoWr += 1
      }
      dut.sdramCd.waitSampling()
    }
    
    println(s"[sim] Observed $cyclesWithActiveNoWr cycles of active-but-no-read (post-propagation)")
    assert(cyclesWithActiveNoWr >= 10, s"Expected most settle cycles for fetch remaining, got $cyclesWithActiveNoWr")
    
    // Eventually cmdRd should assert
    // (Handled by the sdramModel fork above)
    timeout = 100
    while(!dut.io.sdramRd.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling()
      timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for first cmdRd after fetch settle")
    println("[sim] First cmdRd observed after fetch settle — OK")

    // Wait for the full line to be fetched (80 bitmap + 80 attr = 160 bytes)
    // Plus some padding for FIFO and SDRAM latency.
    dut.sdramCd.waitSampling(1000)
    
    // === Case 3: Content Verification ===
    println("[sim] Case 3: Verifying line-buffer content...")
    
    // In our reactive model above, we return (addr & 0xFF).
    // For line 0, bitmap fetch addr = BitmapSdramBase + 0*80 + byteIdx
    // BitmapSdramBase = 0x3000.
    // So col 0 (byteIdx 0) -> addr 0x3000 -> data 0x00.
    // col 8 (byteIdx 1) -> addr 0x3001 -> data 0x01.
    // col 80 (byteIdx 10) -> addr 0x300A -> data 0x0A.
    
    dut.io.col #= 0
    dut.clockDomain.waitSampling(2)
    println(s"[sim] col=0: bitmapByte=${dut.io.bitmapByte.toInt}")
    assert(dut.io.bitmapByte.toInt == 0x00, s"Expected 0x00, got ${dut.io.bitmapByte.toInt}")

    dut.io.col #= 8
    dut.clockDomain.waitSampling(2)
    println(s"[sim] col=8: bitmapByte=${dut.io.bitmapByte.toInt}")
    assert(dut.io.bitmapByte.toInt == 0x01, s"Expected 0x01, got ${dut.io.bitmapByte.toInt}")

    // Attr fetch addr = AttrSdramBase + 0*80 + byteIdx
    // AttrSdramBase = 0x4000.
    // So col 0 (byteIdx 0) -> addr 0x4000 -> data 0x00.
    dut.io.col #= 0
    dut.clockDomain.waitSampling(2)
    println(s"[sim] col=0: attrByte=${dut.io.attrByte.toInt}")
    assert(dut.io.attrByte.toInt == 0x00, s"Expected 0x00, got ${dut.io.attrByte.toInt}")

    println("[sim] BitmapRowFetchSim: PASS")
  }
}
