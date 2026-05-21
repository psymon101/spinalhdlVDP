package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** RGB565 directcolor lane CP-1b — VdpTop integration sim.
  *
  * Proves the directcolor datapath end-to-end through the real VdpTop:
  *   BITMAP_CTRL bpp=0b10  ->  BitmapFetch 565->888 decode  ->  parallel
  *   directcolor LineBuffer (drain-aligned)  ->  maskedRgb bypass mux  ->
  *   io.red/green/blue.
  *
  * BITMAP_CTRL @ 0x0350: bit0=enable, bits[2:1]=bpp, bit7=useSdram.
  *   directcolor enable value = enable | (bpp=10 << 1) | useSdram
  *                            = 0x01 | 0x04 | 0x80 = 0x85
  *
  * The 16-bit RGB565 pixel is {attrByte, bitmapByte} (CP-1b reuses the
  * existing bitmap+attr fetch as the lo/hi byte pair). Sprites are
  * disabled, so the directcolor pixel reaches the display directly.
  */
object BitmapDirectColorSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init — mirrors BorderRegSim.
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

    dut.clockDomain.waitSampling(20)

    def busPulse(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
    }

    // hTotal*vTotal = 800*525 = 420000 cycles per 640x480@60 frame.
    /** Run a full frame — needed for `primed` to assert (display output is
      * gated by primedR until the first complete frame ends). */
    def settleFrame(): Unit = dut.clockDomain.waitSampling(420000)
    /** Settle the double-buffered line buffers + a safe-boundary commit
      * (a handful of lines); `primed` is sticky so it stays set. */
    def settleLines(): Unit = dut.clockDomain.waitSampling(4000)

    /** Sample (red,green,blue) ~64 pixels into a fresh active line. */
    def sampleActive(): (Int, Int, Int) = {
      // Drop into blanking, then catch the next active-video rising edge.
      dut.clockDomain.waitSamplingWhere(!dut.io.de.toBoolean)
      dut.clockDomain.waitSamplingWhere(dut.io.de.toBoolean)
      dut.clockDomain.waitSampling(64)
      (dut.io.red.toInt, dut.io.green.toInt, dut.io.blue.toInt)
    }

    // directPixel = {attrByte, bitmapByte}. Set both, then settle.
    def setDirectPixel(rgb565: Int): Unit = {
      dut.io.bitmapSdramAttrByte #= (rgb565 >> 8) & 0xFF
      dut.io.bitmapSdramByte     #= rgb565 & 0xFF
    }

    // --- enable RGB565 directcolor: BITMAP_CTRL = 0x85 ---
    busPulse(0x0350, 0x85)
    // Two full frames so `primed` asserts (display output is gated until
    // the first complete frame ends).
    settleFrame(); settleFrame()

    // 565→888 reference expansion (bit-replication), same as BitmapFetch.
    def exp565(rgb: Int): (Int, Int, Int) = {
      val r5 = (rgb >> 11) & 0x1F
      val g6 = (rgb >> 5) & 0x3F
      val b5 = rgb & 0x1F
      val r8 = (r5 << 3) | (r5 >> 2)
      val g8 = (g6 << 2) | (g6 >> 4)
      val b8 = (b5 << 3) | (b5 >> 2)
      (r8, g8, b8)
    }

    val cases = Seq(
      ("red",   0xF800),
      ("green", 0x07E0),
      ("blue",  0x001F),
      ("white", 0xFFFF),
      ("grey",  0x8410)
    )
    for ((name, rgb565) <- cases) {
      setDirectPixel(rgb565)
      settleLines()
      val (r, g, b) = sampleActive()
      val (er, eg, eb) = exp565(rgb565)
      assert(r == er && g == eg && b == eb,
        f"directcolor $name (0x$rgb565%04X): displayRgb got ($r%02X,$g%02X,$b%02X) exp ($er%02X,$eg%02X,$eb%02X)")
      println(f"[sim] directcolor $name 0x$rgb565%04X → display ($r%02X,$g%02X,$b%02X) — OK")
    }

    // --- regression: bpp=00 (1bpp indexed) must NOT route directPixel ---
    // BITMAP_CTRL = enable | bpp=00 | useSdram = 0x81. directPixel bytes
    // still carry 0x07E0; in indexed mode the decoder leaves
    // directColorActive low so the display must NOT be pure green.
    setDirectPixel(0x07E0)
    busPulse(0x0350, 0x81)
    settleLines()
    val (ri, gi, bi) = sampleActive()
    assert(!(ri == 0x00 && gi == 0xFF && bi == 0x00),
      f"regression: 1bpp indexed mode leaked directPixel — display ($ri%02X,$gi%02X,$bi%02X) is pure green")
    println(f"[sim] regression 1bpp indexed mode does not route directPixel — OK")

    println("[sim] BitmapDirectColorSim: PASS")
  }
}
