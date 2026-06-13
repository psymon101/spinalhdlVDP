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

    // Reactive SDRAM model: 5-cycle latency, then BURST out `sdramBurstLen` consecutive
    // 32-bit words (one data_ready pulse/cycle), word k = bytes (addr+k*4+0..3)&0xFF —
    // matching the real sdram.v manual-burst path. RGB565-FULLFRAME-132: direct-color
    // requests burstLen=8, so a single-word response would stall the burst FSM.
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong
          val n    = math.max(1, dut.io.sdramBurstLen.toInt)
          dut.sdramCd.waitSampling(5)
          for (k <- 0 until n) {
            val wa = addr + k * 4
            val w = (wa & 0xFF) | (((wa + 1) & 0xFF) << 8) |
                    (((wa + 2) & 0xFF) << 16) | (((wa + 3) & 0xFF) << 24)
            dut.io.sdramDout32 #= w
            dut.io.sdramDout   #= (wa & 0xFF).toInt
            dut.io.sdramDataReady #= true
            dut.sdramCd.waitSampling()
          }
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

    // Drive several per-SOURCE-ROW grants so all three line-buffer banks fill and the
    // displayed bank (dispBank advances once per grant) holds a completed row. Every
    // row has the SAME low-byte content here — entry k = (base + byteIdx)&0xFF = k&0xFF,
    // since base 0x3000 and stride 512 are both 256-aligned — so any filled bank yields
    // the per-pixel-addressing contract this test checks. (One lone grant would leave
    // dispBank pointing at a not-yet-filled bank under the triple-buffer rotation.)
    for (row <- 0 until 6) {
      dut.io.fetchLine #= row * 2
      dut.io.fetchGrant #= true
      dut.clockDomain.waitSampling(4)
      dut.io.fetchGrant #= false
      dut.sdramCd.waitSampling(6000)     // let the burst row fetch complete
      dut.clockDomain.waitSampling(300)  // and the FIFO drain into the line buffer
    }

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
