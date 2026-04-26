package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** CW-6 unit sim: per-layer window masking.
  *
  * Mirrors VdpTop's layer-mask gate with a tiny synthesized harness. The
  * gate selects bit `layerSource` of the 8-bit mask register; when that
  * bit AND `windowEffect` are both true the input RGB is forced to black
  * before the ColorMath stage.
  *
  * Layer source IDs match `PixelMetadata.SourceXxx`:
  *   0..3 = BG0..BG3
  *   4    = Sprite
  *   5..7 = unused (treated as never-masked since reserved bits default 0)
  */
case class LayerMaskProbe() extends Component {
  val io = new Bundle {
    val rgbIn        = in  Bits(24 bits)
    val mask         = in  Bits(8 bits)
    val layerSource  = in  UInt(3 bits)
    val windowEffect = in  Bool()
    val rgbOut       = out Bits(24 bits)
  }
  val maskBit       = io.mask(io.layerSource)
  val maskActive    = maskBit && io.windowEffect
  io.rgbOut := Mux(maskActive, B(0, 24 bits), io.rgbIn)
}

object LayerMaskSim extends App {
  Config.sim.compile(LayerMaskProbe()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.rgbIn        #= 0
    dut.io.mask         #= 0
    dut.io.layerSource  #= 0
    dut.io.windowEffect #= false
    dut.clockDomain.waitSampling(2)

    def run(rgb: Long, mask: Int, source: Int, win: Boolean): Long = {
      dut.io.rgbIn        #= rgb
      dut.io.mask         #= mask
      dut.io.layerSource  #= source
      dut.io.windowEffect #= win
      sleep(1)
      dut.io.rgbOut.toLong & 0xFFFFFFL
    }

    // ---- Case 1: mask=0 → never gates, regardless of source/window ---------
    val sample = 0xABCDEFL
    for (src <- 0 until 8; win <- Seq(false, true)) {
      val out = run(sample, mask = 0, source = src, win = win)
      assert(out == sample,
        f"case1 mask=0 src=$src win=$win: got=0x$out%06X expected 0x$sample%06X (passthrough)")
    }
    println("[sim] case1 mask=0 passthrough — OK")

    // ---- Case 2: mask set, window off → never gates ------------------------
    for (src <- 0 until 8) {
      val out = run(sample, mask = 0xFF, source = src, win = false)
      assert(out == sample,
        f"case2 mask=0xFF src=$src win=off: got=0x$out%06X expected 0x$sample%06X (window inactive)")
    }
    println("[sim] case2 window=off passthrough even with mask=0xFF — OK")

    // ---- Case 3: per-source mask isolation ---------------------------------
    // For each source S, set ONLY bit S in the mask. With window on, source S
    // must be masked (→ 0); all other sources must passthrough.
    for (maskedSrc <- 0 until 5) {                  // BG0..BG3 + Sprite
      val mask = 1 << maskedSrc
      for (src <- 0 until 8) {
        val out = run(sample, mask = mask, source = src, win = true)
        val expected = if (src == maskedSrc) 0L else sample
        assert(out == expected,
          f"case3 maskBit=$maskedSrc src=$src: got=0x$out%06X expected 0x$expected%06X")
      }
    }
    println("[sim] case3 per-source isolation (each of 5 layer bits) — OK")

    // ---- Case 4: mask=0xFF window=on → all 8 sources masked to 0 ----------
    for (src <- 0 until 8) {
      val out = run(sample, mask = 0xFF, source = src, win = true)
      assert(out == 0L,
        f"case4 mask=0xFF src=$src win=on: got=0x$out%06X expected 0x000000")
    }
    println("[sim] case4 mask=0xFF window=on masks all sources — OK")

    // ---- Case 5: reserved bits 5..7 act as normal mask bits ----------------
    // (No special handling — `mask(source)` is straight bit indexing.)
    val out5 = run(sample, mask = 1 << 7, source = 7, win = true)
    assert(out5 == 0L, f"case5 mask bit 7 with src=7: got=0x$out5%06X expected 0x000000")
    val out5b = run(sample, mask = 1 << 7, source = 4, win = true)
    assert(out5b == sample, f"case5b mask bit 7 with src=4: got=0x$out5b%06X expected 0x$sample%06X")
    println("[sim] case5 reserved bits index normally — OK")

    println("[sim] LayerMaskSim: PASS")
  }
}
