package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import java.nio.file.{Files, Paths}
import java.security.MessageDigest

/** HAM-DECODER-171 CP-D byte-exact proof.
  *
  * Drives BronzeGate's locked HAM6 fixture (`ham6_320x240_codes.raw`) through the
  * real `HamDecoder` RTL exactly as VdpTop's bitmap fill drives it — display-rate
  * stepping (`code = source[col/2]`, `step` every one of the 640 columns, which
  * exercises the idempotent ×2 source repeat), accumulator seeded to palette[0] and
  * reset at the start of each source row — then ×2 line-doubles vertically and
  * converts 4:4:4 → RGB565 with BronzeGate's exact convention. The rendered
  * 640×480 RGB565 frame is SHA-256 compared to `ham6_640x480_expected_rgb565_le.raw`.
  *
  * (dcLineBuf double-buffer + drain + the direct-color bypass mux are colour-
  * preserving — verified by CyanPeak code-to-spec #12958 and BitmapDirectColorSim —
  * so the rendered colour equals the HamDecoder output captured here.)
  *
  * Run: sbt "runMain spinalhdlvdp.HamFrameCoSim"
  */
object HamFrameCoSim {
  // Asset lives (untracked) in the main checkout; absolute path until BronzeGate commits it.
  val AssetDir = "/home/itadmin/github/spinalhdlVDP/firmware/assets/ham_decoder_171"
  val SrcW = 320; val SrcH = 240; val DispW = 640; val DispH = 480

  // HAM base palette (R4:G4:B4 packed 12-bit), matching the fixture's PALETTE_R4G4B4.
  val pal = Array(
    0x000, 0xFFF, 0xF00, 0x0F0, 0x00F, 0xFF0, 0xF0F, 0x0FF,
    0x840, 0x480, 0x048, 0x804, 0xC62, 0x2C6, 0x62C, 0x444)

  def to565(c12: Int): Int = {
    val r4 = (c12 >> 8) & 0xF; val g4 = (c12 >> 4) & 0xF; val b4 = c12 & 0xF
    val r8 = (r4 << 4) | r4; val g8 = (g4 << 4) | g4; val b8 = (b4 << 4) | b4
    ((r8 & 0xF8) << 8) | ((g8 & 0xFC) << 3) | (b8 >> 3)
  }
  def sha256(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xFF}%02x").mkString

  def main(args: Array[String]): Unit = {
    val ham = Files.readAllBytes(Paths.get(s"$AssetDir/ham6_320x240_codes.raw"))
    val expected = Files.readAllBytes(Paths.get(s"$AssetDir/ham6_640x480_expected_rgb565_le.raw"))
    require(ham.length == SrcW * SrcH, s"ham size ${ham.length}")
    require(expected.length == DispW * DispH * 2, s"ref size ${expected.length}")

    val out = new Array[Byte](DispW * DispH * 2)

    Config.sim.compile(HamDecoder()).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.seedColor #= pal(0)
      dut.io.lineStart #= false; dut.io.step #= false; dut.io.code #= 0; dut.io.baseColor #= pal(0)
      dut.clockDomain.waitSampling()

      for (y <- 0 until SrcH) {
        // Reset hold to palette[0] at the start of each source row (one cycle before col 0).
        dut.io.lineStart #= true; dut.io.step #= false
        dut.clockDomain.waitSampling()
        dut.io.lineStart #= false

        val row565 = new Array[Int](DispW)
        for (col <- 0 until DispW) {
          val sx  = col / 2
          val cde = ham(y * SrcW + sx) & 0x3F
          dut.io.code      #= cde
          dut.io.baseColor #= pal(cde & 0xF)
          dut.io.step      #= true
          sleep(1)                                   // settle combinational rgb444
          row565(col) = to565(dut.io.rgb444.toInt)
          dut.clockDomain.waitSampling()             // latch acc := this pixel's colour
        }
        // Vertical ×2 line-double.
        for (dy <- Seq(2 * y, 2 * y + 1)) {
          var col = 0
          while (col < DispW) {
            val p = row565(col); val idx = (dy * DispW + col) * 2
            out(idx) = (p & 0xFF).toByte; out(idx + 1) = ((p >> 8) & 0xFF).toByte
            col += 1
          }
        }
      }
    }

    val gotSha = sha256(out)
    val expSha = sha256(expected)
    // First mismatch (if any) for diagnosis.
    var firstDiff = -1
    var i = 0
    while (i < out.length && firstDiff < 0) { if (out(i) != expected(i)) firstDiff = i; i += 1 }

    println(s"[sim] HamFrameCoSim rendered SHA-256 = $gotSha")
    println(s"[sim] HamFrameCoSim expected SHA-256 = $expSha")
    if (gotSha == expSha) {
      println("[sim] HamFrameCoSim: PASS — rendered 640x480 RGB565 frame byte-exact vs fixture")
      simSuccess()
    } else {
      val px = (firstDiff / 2); val dy = px / DispW; val dx = px % DispW
      println(f"[sim] HamFrameCoSim: FAIL — first byte diff at $firstDiff (pixel x=$dx y=$dy): " +
              f"got=0x${out(firstDiff) & 0xFF}%02x exp=0x${expected(firstDiff) & 0xFF}%02x")
      simFailure("HamFrameCoSim frame mismatch")
    }
  }
}
