package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** HAM-DECODER-171 CP-A proof: HamDecoder directed + randomized equivalence.
  * Run: sbt "runMain spinalhdlvdp.HamDecoderSim"
  */
object HamDecoderSim {
  def main(args: Array[String]): Unit = {
    Config.sim.compile(HamDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // 16-entry 4:4:4 base palette for SET codes (arbitrary distinct values).
      val basePal = (0 until 16).map(i => ((i * 0x111) & 0xFFF)).toArray

      def expand444(c: Int): Int = {
        val r = (c >> 8) & 0xF; val g = (c >> 4) & 0xF; val b = c & 0xF
        ((r << 4 | r) << 16) | ((g << 4 | g) << 8) | (b << 4 | b)
      }

      // Scala reference: returns this pixel's 4:4:4 colour given code + hold.
      def model(code: Int, hold: Int): Int = {
        val ctrl = (code >> 4) & 0x3
        val data = code & 0xF
        val r = (hold >> 8) & 0xF; val g = (hold >> 4) & 0xF; val b = hold & 0xF
        ctrl match {
          case 0 => basePal(data)                 // SET
          case 1 => (r << 8) | (g << 4) | data    // MODIFY BLUE
          case 2 => (data << 8) | (g << 4) | b    // MODIFY RED
          case 3 => (r << 8) | (data << 4) | b    // MODIFY GREEN
        }
      }

      var hold = 0
      def lineReset(seed: Int): Unit = {
        dut.io.lineStart #= true
        dut.io.step #= false
        dut.io.seedColor #= seed
        dut.clockDomain.waitSampling()            // acc := seed
        dut.io.lineStart #= false
        hold = seed
      }
      def pixel(code: Int): Unit = {
        val data = code & 0xF
        dut.io.code #= code
        dut.io.baseColor #= basePal(data)
        dut.io.step #= true
        dut.io.lineStart #= false
        sleep(1)                                  // settle combinational
        val exp444 = model(code, hold)
        val got444 = dut.io.rgb444.toInt
        assert(got444 == exp444,
          f"rgb444 mismatch code=0x$code%02x hold=0x$hold%03x got=0x$got444%03x exp=0x$exp444%03x")
        val got888 = dut.io.rgb888.toBigInt
        assert(got888 == BigInt(expand444(exp444)),
          f"rgb888 mismatch code=0x$code%02x got=0x$got888%06x exp=0x${expand444(exp444)}%06x")
        dut.clockDomain.waitSampling()            // latch acc := this pixel's colour
        hold = exp444
      }

      // --- Directed: classic HAM hold-and-modify chain ---
      lineReset(0x000)
      pixel(0x05)        // SET   data=5  -> basePal(5)=0x555
      pixel((1 << 4) | 0xA) // MODIFY BLUE  -> R5 G5 B A = 0x55A
      pixel((3 << 4) | 0x3) // MODIFY GREEN -> 0x53A
      pixel((2 << 4) | 0x1) // MODIFY RED   -> 0x13A
      pixel((1 << 4) | 0xF) // MODIFY BLUE  -> 0x13F
      pixel(0x02)        // SET   data=2  -> basePal(2)=0x222

      // --- Line reset clears the hold ---
      lineReset(0x000)
      pixel((1 << 4) | 0x7) // MODIFY BLUE from seed black -> 0x007 (proves hold reset)

      // --- Randomized cross-check vs reference, with periodic line resets ---
      val rnd = new scala.util.Random(0x4A3)
      for (line <- 0 until 8) {
        lineReset(rnd.nextInt(0x1000))
        for (_ <- 0 until 64) pixel(rnd.nextInt(64))
      }

      println("[sim] HamDecoderSim: PASS (directed HAM chain + line-reset + 8x64 random vs reference)")
      simSuccess()
    }
  }
}
