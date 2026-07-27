package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** ScaleCoordGen unit-level golden-vector sim (external-review-scaler-rewrite P0
  * Checkpoint B). Pokes ScaleCoordGen directly and verifies the physical->logical
  * coordinate generation:
  *   1. 1x identity horizontal: sourceX == physical active column.
  *   2. 2x horizontal: sourceX = col/2 (P0 P0 P1 P1 ... — the source-coord fix).
  *   3. 3x horizontal: sourceX = col/3.
  *   4. Vertical 1x identity + 2x repeat (sourceY advances once per line / two lines).
  *   5. Auto-center borders match (hActive - scale*logicW)/2.
  *   6. Silent clamp: scaleX*logicW > hActive walks scaleXEff down.
  *   7. sourceValid: false in the auto-center bezel, true inside the scaled rect.
  *
  * Run: sbt "runMain spinalhdlvdp.ScaleCoordGenSim"
  */
object ScaleCoordGenSim extends App {
  Config.sim.compile(ScaleCoordGen()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def setConfig(sx: Int, sy: Int, ac: Boolean, lw: Int, lh: Int): Unit = {
      dut.io.scaleXReg #= sx
      dut.io.scaleYReg #= sy
      dut.io.autoCenter #= ac
      dut.io.logicWidth #= lw
      dut.io.logicHeight #= lh
    }
    def quiesce(): Unit = {
      dut.io.hCounter #= 0
      dut.io.vCounter #= 0
      dut.io.hActive #= 640
      dut.io.vActive #= 480
    }
    // Sweep one physical line's hCounter 0..nCols-1 at a fixed vCounter; return sourceX per column.
    // sourceX/sourceY are REGISTERED outputs (P4 timing-closure) — +1 cycle from the inputs — so
    // hold each poked value across 2 samplings before reading so the register reflects it.
    def sweepX(vc: Int, nCols: Int): Seq[Int] = {
      val out = ArrayBuffer[Int]()
      for (hc <- 0 until nCols) {
        dut.io.vCounter #= vc
        dut.io.hCounter #= hc
        dut.clockDomain.waitSampling(2)
        out += dut.io.sourceX.toInt
      }
      out.toSeq
    }
    // Drive nLines physical lines (lineStart pulse hc=0 then body hc=1) with vCounter=line;
    // return sourceY sampled mid-line.
    def sweepY(nLines: Int): Seq[Int] = {
      val out = ArrayBuffer[Int]()
      dut.io.hCounter #= 1   // fixed active column; sourceY tracks vCounter (registered output)
      for (ln <- 0 until nLines) {
        dut.io.vCounter #= ln
        dut.clockDomain.waitSampling(2)
        out += dut.io.sourceY.toInt
      }
      out.toSeq
    }

    quiesce(); setConfig(1, 1, false, 640, 480); dut.clockDomain.waitSampling(4)

    // --- Case 1: 1x identity horizontal ---
    val h1 = sweepX(vc = 1, nCols = 12)
    for (hc <- 0 until 12) assert(h1(hc) == hc, s"Case 1 1x: col $hc sourceX=${h1(hc)} expected $hc; seq=${h1.mkString(",")}")
    println(s"[sim] Case 1 1x identity sourceX = ${h1.mkString(",")} — OK")

    // --- Case 2: 2x horizontal (source-coord fix: col/2, no skip) ---
    quiesce(); setConfig(2, 1, false, 320, 480); dut.clockDomain.waitSampling(4)
    val h2 = sweepX(vc = 1, nCols = 12)
    val exp2 = (0 until 12).map(_ / 2)
    assert(h2 == exp2, s"Case 2 2x: sourceX=${h2.mkString(",")} expected ${exp2.mkString(",")}")
    println(s"[sim] Case 2 2x sourceX = ${h2.mkString(",")} (P0 P0 P1 P1 ...) — OK")

    // --- Case 3: 3x horizontal ---
    quiesce(); setConfig(3, 1, false, 200, 480); dut.clockDomain.waitSampling(4)
    val h3 = sweepX(vc = 1, nCols = 12)
    val exp3 = (0 until 12).map(_ / 3)
    assert(h3 == exp3, s"Case 3 3x: sourceX=${h3.mkString(",")} expected ${exp3.mkString(",")}")
    println(s"[sim] Case 3 3x sourceX = ${h3.mkString(",")} — OK")

    // --- Case 4a: vertical 1x identity ---
    quiesce(); setConfig(1, 1, false, 640, 480); dut.clockDomain.waitSampling(4)
    val v1 = sweepY(nLines = 8)
    for (ln <- 0 until 8) assert(v1(ln) == ln, s"Case 4a 1x vert: line $ln sourceY=${v1(ln)} expected $ln; seq=${v1.mkString(",")}")
    println(s"[sim] Case 4a 1x identity sourceY = ${v1.mkString(",")} — OK")

    // --- Case 4b: vertical 2x repeat (line/2) ---
    quiesce(); setConfig(1, 2, false, 640, 240); dut.clockDomain.waitSampling(4)
    val v2 = sweepY(nLines = 8)
    val expV2 = (0 until 8).map(_ / 2)
    assert(v2 == expV2, s"Case 4b 2x vert: sourceY=${v2.mkString(",")} expected ${expV2.mkString(",")}")
    println(s"[sim] Case 4b 2x sourceY = ${v2.mkString(",")} (line/2) — OK")

    // --- Case 5: auto-center borders ---
    quiesce(); setConfig(2, 2, true, 200, 150); dut.clockDomain.waitSampling(4)
    val bx0 = dut.io.borderX0.toInt; val bx1 = dut.io.borderX1.toInt
    val by0 = dut.io.borderY0.toInt; val by1 = dut.io.borderY1.toInt
    assert(bx0 == 120, s"Case 5 borderX0 expected 120 got $bx0")       // (640-200*2)/2
    assert(bx1 == 520, s"Case 5 borderX1 expected 520 got $bx1")       // 120+400
    assert(by0 == 90,  s"Case 5 borderY0 expected 90 got $by0")        // (480-150*2)/2
    assert(by1 == 390, s"Case 5 borderY1 expected 390 got $by1")       // 90+300
    println(s"[sim] Case 5 auto-center: X=[$bx0..$bx1) Y=[$by0..$by1) — OK")

    // --- Case 6: silent clamp (3x*300=900 > 640 -> scaleXEff walks to 2) ---
    quiesce(); setConfig(3, 1, true, 300, 480); dut.clockDomain.waitSampling(4)
    val sxe = dut.io.scaleXEffOut.toInt
    val cbx0 = dut.io.borderX0.toInt
    assert(sxe == 2, s"Case 6 silent clamp: scaleXEffOut expected 2 (3->2) got $sxe")
    assert(cbx0 == 20, s"Case 6 silent clamp: borderX0 expected 20 (visibleW=600) got $cbx0")
    println(s"[sim] Case 6 silent clamp: scaleXEff=$sxe borderX0=$cbx0 — OK")

    // --- Case 7: sourceValid in the auto-center bezel vs inside ---
    quiesce(); setConfig(2, 1, true, 200, 480); dut.clockDomain.waitSampling(4)
    // scaleX=2, logicW=200 -> visibleW=400, offX=120, active X=[120..520). scaleY clamps? 1*480=480<=480 ok.
    // Sample a column INSIDE the left bezel (hc=50) -> invalid; and INSIDE the rect (hc=200) -> valid.
    dut.io.vCounter #= 1
    dut.io.hCounter #= 0;   dut.clockDomain.waitSampling()      // lineStart reset
    dut.io.hCounter #= 50;  dut.clockDomain.waitSampling(); dut.clockDomain.waitSampling()
    val validBezel = dut.io.sourceValid.toBoolean
    dut.io.hCounter #= 200; dut.clockDomain.waitSampling(); dut.clockDomain.waitSampling()
    val validInside = dut.io.sourceValid.toBoolean
    assert(!validBezel, s"Case 7: sourceValid should be FALSE in the left bezel (hc=50 < offX=120), got $validBezel")
    assert(validInside, s"Case 7: sourceValid should be TRUE inside the rect (hc=200 in [120,520)), got $validInside")
    println(s"[sim] Case 7 sourceValid: bezel=$validBezel inside=$validInside — OK")

    println("[sim] ScaleCoordGenSim: PASS")
  }
}
