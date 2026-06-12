package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** RGB565 directcolor lane CP-1c — BitmapRowFetch directcolor fetch sim.
  *
  * Validates the CP-1c per-pixel fetch path:
  *   - directColor mode fetches 320 bytes/row into each line buffer
  *     (vs the indexed 80), so columns 0..639 cover 320 distinct
  *     source pixels.
  *   - the read address is col/2 in directcolor mode, so each pair of
  *     HDMI columns maps to one distinct buffer entry (CP-1b read
  *     col/8, repeating every value across an 8-column span).
  *
  * Uses skipSdramInit=true (host owns the framebuffer) so there is no
  * procedural-init phase. The reactive SDRAM model returns addr&0xFF,
  * so buffer entry k holds (base+k)&0xFF; both region bases end in
  * 0x00, hence entry k = k&0xFF.
  */
object BitmapRowFetchDirectColorSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(84000000 Hz))
    BitmapRowFetch(sdramCd, skipSdramInit = true)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.sdramDout      #= 0
    dut.io.sdramDout32    #= 0
    dut.io.sdramDataReady #= false
    dut.io.sdramBusy      #= false
    dut.io.fetchGrant     #= false
    dut.io.fetchLine      #= 0
    dut.io.col            #= 0
    dut.io.enable         #= false
    dut.io.directColor    #= false
    dut.io.tileBootDone   #= false
    // BITMAP-PLUMB-129: defaults — stride 512 reproduces the legacy <<9
    // direct-color row stride so the expected addresses are byte-identical.
    dut.io.bitmapBase     #= 0x3000
    dut.io.attrBase       #= 0x4000
    dut.io.bitmapStride   #= 512
    dut.io.attrStride     #= 512
    dut.io.bitmapHeight   #= 240

    // Reactive SDRAM model: return (addr & 0xFF) with a few cycles latency.
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong
          dut.sdramCd.waitSampling(5)
          dut.io.sdramDout #= (addr & 0xFF).toInt
          // dout32 = 4 LE bytes (addr+0..3)&0xFF so the pop-side deinterleave
          // reproduces the same per-byte addr&0xFF contract the test asserts.
          val w = (addr & 0xFF) | (((addr + 1) & 0xFF) << 8) |
                  (((addr + 2) & 0xFF) << 16) | (((addr + 3) & 0xFF) << 24)
          dut.io.sdramDout32 #= w
          dut.io.sdramDataReady #= true
          dut.sdramCd.waitSampling()
          dut.io.sdramDataReady #= false
        } else {
          dut.sdramCd.waitSampling()
        }
      }
    }

    dut.sdramCd.waitSampling(10)
    dut.clockDomain.waitSampling(10)

    // Enter directcolor mode and release the FSM (skipSdramInit -> sIdle).
    dut.io.enable       #= true
    dut.io.directColor  #= true
    dut.io.tileBootDone #= true

    // Wait for bootDone (immediate under skipSdramInit, plus BufferCC).
    var timeout = 2000
    while (!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.sdramCd.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone (skipSdramInit)")
    println("[sim] bootDone reached (directcolor, skipSdramInit)")

    // Pulse fetchGrant for source line 0.
    dut.io.fetchLine #= 0
    dut.io.fetchGrant #= true
    dut.clockDomain.waitSampling(4)
    dut.io.fetchGrant #= false

    // Let the FSM fetch 320 bitmap + 320 attr bytes and the FIFO drain.
    dut.sdramCd.waitSampling(40000)
    dut.clockDomain.waitSampling(2000)

    // Read back: directcolor read address is col/2, so io.bitmapByte at
    // column `col` must equal buffer entry (col/2), i.e. (col/2)&0xFF.
    def readAt(col: Int): (Int, Int) = {
      dut.io.col #= col
      dut.clockDomain.waitSampling(); sleep(1)
      (dut.io.bitmapByte.toInt, dut.io.attrByte.toInt)
    }

    val probes = Seq(0, 2, 8, 100, 200, 400, 638)
    for (col <- probes) {
      val (bm, at) = readAt(col)
      val exp = (col / 2) & 0xFF
      assert(bm == exp, f"directcolor col=$col bitmapByte got 0x$bm%02X exp 0x$exp%02X (per-pixel addressing)")
      assert(at == exp, f"directcolor col=$col attrByte got 0x$at%02X exp 0x$exp%02X")
      println(f"[sim] directcolor col=$col%3d → buffer entry ${col / 2}%3d (byte 0x$bm%02X) — OK")
    }

    // Key contrast vs CP-1b: adjacent column pairs are now DISTINCT.
    val (b0, _) = readAt(0)
    val (b2, _) = readAt(2)
    assert(b0 != b2, "directcolor columns 0 and 2 must map to distinct buffer entries (per-pixel, not /8)")
    println("[sim] directcolor adjacent columns are per-pixel distinct — OK")

    println("[sim] BitmapRowFetchDirectColorSim: PASS")
  }
}
