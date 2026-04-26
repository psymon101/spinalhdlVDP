package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R6 Task 20 + Color/Window Hardening CW-4: ColorMath sim.
  *
  * Per task artifact validation plan:
  *   Shadow mode (op=01):    0xFF0000 → 0x7F0000
  *   Highlight   (op=10):    0x404040 → 0x808080;  0x808080 → 0xFFFFFF (clamp)
  *   Add-constant(op=11):    0x102030 + 0x20 → 0x304050
  *   Clamp                :  0xE0E0E0 + 0x40 → 0xFFFFFF
  *
  * Plus default-passthrough check (op=00 / enable=false) to guarantee no
  * regression in the existing output path before any control register is
  * programmed.
  */
object ColorMathSim extends App {
  Config.sim.compile(ColorMath()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.rgbIn    #= 0
    dut.io.op       #= 0
    dut.io.constant #= 0
    dut.io.enable   #= false
    dut.clockDomain.waitSampling(2)

    def run(rgbIn: Long, op: Int, constant: Int, enable: Boolean): Long = {
      dut.io.rgbIn    #= rgbIn
      dut.io.op       #= op
      dut.io.constant #= constant
      dut.io.enable   #= enable
      sleep(1)
      dut.io.rgbOut.toLong & 0xFFFFFFL
    }

    // ---- Case 1: passthrough (enable=false) regardless of op ----------------
    val pass = Seq(
      0x000000L, 0xFFFFFFL, 0x123456L, 0x808080L
    )
    for (rgb <- pass) {
      val out = run(rgb, op = 1, constant = 0x40, enable = false)
      assert(out == rgb,
        f"case1 passthrough enable=false: in=0x$rgb%06X got=0x$out%06X exp=0x$rgb%06X")
    }
    println("[sim] case1 enable=false passthrough — OK")

    // op=00 with enable=true also passthrough
    for (rgb <- pass) {
      val out = run(rgb, op = 0, constant = 0x40, enable = true)
      assert(out == rgb,
        f"case1b op=00 passthrough: in=0x$rgb%06X got=0x$out%06X exp=0x$rgb%06X")
    }
    println("[sim] case1b op=00 enable=true passthrough — OK")

    // ---- Case 2: shadow mode (op=01) halves each channel --------------------
    val shadowVecs = Seq(
      (0xFF0000L, 0x7F0000L),
      (0x00FF00L, 0x007F00L),
      (0x0000FFL, 0x00007FL),
      (0xFFFFFFL, 0x7F7F7FL),
      (0x808080L, 0x404040L),
      (0x010101L, 0x000000L),    // floor on (1>>1)
      (0x102030L, 0x081018L),
    )
    for ((rgb, exp) <- shadowVecs) {
      val out = run(rgb, op = 1, constant = 0, enable = true)
      assert(out == exp,
        f"case2 shadow: in=0x$rgb%06X got=0x$out%06X exp=0x$exp%06X")
    }
    println("[sim] case2 shadow op=01 (>>1 per channel) — OK")

    // ---- Case 3: highlight (op=10) << 1 per channel with 0xFF clamp --------
    val highlightVecs = Seq(
      (0x000000L, 0x000000L),        // zero stays zero
      (0x404040L, 0x808080L),        // doubles cleanly
      (0x010203L, 0x020406L),
      (0x808080L, 0xFFFFFFL),        // MSB set → clamp
      (0xFF0000L, 0xFF0000L),        // single channel saturates independently
      (0x7F7F7FL, 0xFEFEFEL),        // top valid pre-clamp value
    )
    for ((rgb, exp) <- highlightVecs) {
      val out = run(rgb, op = 2, constant = 0, enable = true)
      assert(out == exp,
        f"case3 highlight: in=0x$rgb%06X got=0x$out%06X exp=0x$exp%06X")
    }
    println("[sim] case3 highlight op=10 (<<1 per channel, clamp 0xFF) — OK")

    // ---- Case 4: add-constant (op=11) per channel with clamp ---------------
    val addVecs = Seq(
      (0x102030L, 0x20, 0x304050L),    // artifact spec
      (0x000000L, 0x40, 0x404040L),
      (0xE0E0E0L, 0x40, 0xFFFFFFL),    // artifact spec — clamp
      (0xFFFFFFL, 0x01, 0xFFFFFFL),    // saturated
      (0x80808FL, 0x10, 0x90909FL),
      (0xC0C0C0L, 0xFF, 0xFFFFFFL),    // each channel saturates independently
    )
    for ((rgb, k, exp) <- addVecs) {
      val out = run(rgb, op = 3, constant = k, enable = true)
      assert(out == exp,
        f"case4 add(0x$k%02X): in=0x$rgb%06X got=0x$out%06X exp=0x$exp%06X")
    }
    println("[sim] case4 add-constant op=11 (per-channel clamp at 0xFF) — OK")

    println("[sim] ColorMathSim: PASS")
  }
}
