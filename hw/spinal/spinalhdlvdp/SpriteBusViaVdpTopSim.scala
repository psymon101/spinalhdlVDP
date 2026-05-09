package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 28 CP-C Option 1 (per BronzeGate #7867) — VdpTop-scoped sim that
  * drives the Mode0 register bus with writes to `0x0808..0x0811` and
  * checks whether the SpriteEvaluator's extended Reg-Vec actually latches
  * the programmed values.
  *
  * SpriteEvaluatorSim Case 7 already proves the evaluator works when its
  * bus port is driven directly. This sim proves (or disproves) the full
  * VdpTop integration path: host → `io.regBus` → `extHit/effAddr/effWrite`
  * → VdpTop's `spriteBusRangeHit` decode → `spriteEval.io.busSlot/busWord/
  * busData/busWr` port → Reg-Vec latching.
  *
  * If this sim passes, the hardware failure observed in Sc28 captures
  * (#7862 / #7866) narrows to a Gowin/SpinalHDL synthesis discrepancy.
  * If this sim fails, the exact broken signal is reproducible.
  */
object SpriteBusViaVdpTopSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init (matches VdpTopSim defaults).
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false
    dut.io.layer0UseSdram #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.layer0SdramPixel #= 0; dut.io.layer0SdramBank #= 0; dut.io.layer0SdramPriority #= false
    dut.io.rasterTriggerLine #= 0; dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false; dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.statusEvQspiReady #= false; dut.io.statusEvQspiError #= false

    dut.clockDomain.waitSampling(10)

    /** Drive one single-cycle pulse on io.regBus matching what the arbiter
      * would produce for a bootstrap/QSPI/animator register write. */
    def busPulse(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
      dut.clockDomain.waitSampling()
    }

    // Program 4 extended slots (4..7) at y=250, x spread, alternating patIdx.
    // Task 28 CP-C Option A scope (descCount=8, extCount=4).
    // Task 37 bus layout: 8 words per slot. slot N word W = 0x0800+N*8+W.
    val progSeq = Seq(
      (0x0820, 0x8000 | 250, 0),    // slot 4 word 0
      (0x0821, 60,            0),   // slot 4 word 1: x=60
      (0x0828, 0x8000 | (1 << 11) | 250, 0),   // slot 5 word 0
      (0x0829, 140,           0),
      (0x0830, 0x8000 | 250,  0),
      (0x0831, 220,           0),
      (0x0838, 0x8000 | (1 << 11) | 250, 0),
      (0x0839, 300,           0)
    )

    println("-- phase 1: issue 10 bus pulses via io.regBus --")
    for (((addr, data, _), i) <- progSeq.zipWithIndex) {
      busPulse(addr, data)
      println(f"  pulse[$i] addr=0x$addr%04X data=0x$data%04X")
    }

    // Give the evaluator a few cycles in case of latch delay.
    dut.clockDomain.waitSampling(20)

    println("-- phase 2: peek evaluator extended Reg-Vec (slots 4..8 => rel 0..4) --")
    val expected = Seq(
      (true,  60,  250, 0),
      (true,  140, 250, 1),
      (true,  220, 250, 0),
      (true,  300, 250, 1)
    )

    var allPass = true
    // Task 57 Slice 3: per-slot fields moved to infoMem* — unpack via
    // Mem.getBigInt. Layout per SpriteEvaluator.scala:
    //   infoMemW0 (15 bits): {y[14:5], patIdxLow[4:1], enabled[0]}
    //   infoMemW1 (10 bits): {x[9:0]}
    //   infoMemW8 (14 bits): {patIdxHigh[13:12] (if PatIdxWidth>4), mask[11], bppSel[10:9],
    //                          flipV[8], flipH[7], priority[6:5], paletteBank[4:2], sizeSel[1:0]}
    for (i <- 0 until 4) {
      val w0 = dut.spriteEval.infoMemW0.getBigInt(i)
      val w1 = dut.spriteEval.infoMemW1.getBigInt(i)
      val w8 = dut.spriteEval.infoMemW8.getBigInt(i)
      val en = ((w0 & 1) == 1)
      val x  = (w1 & 0x3FF).toInt
      val y  = ((w0 >> 5) & 0x3FF).toInt
      val patLow  = ((w0 >> 1) & 0xF).toInt
      val patHigh = ((w8 >> 12) & 0x3).toInt
      val p = (patHigh << 4) | patLow
      val (expEn, expX, expY, expP) = expected(i)
      val slotOk = en == expEn && x == expX && y == expY && p == expP
      val status = if (slotOk) "PASS" else "FAIL"
      println(f"  slot ${i + 4}%d (rel $i) $status en=$en (exp $expEn) x=$x%3d (exp $expX%3d) y=$y%3d (exp $expY%3d) patIdx=$p (exp $expP)")
      if (!slotOk) allPass = false
    }

    if (allPass) {
      println("SpriteBusViaVdpTopSim: ALL 4 SLOTS LATCHED CORRECTLY — VdpTop integration path works in sim")
      println("  Conclusion: hardware symptom (#7866) is a synthesis discrepancy, not a functional RTL bug")
    } else {
      println("SpriteBusViaVdpTopSim: LATCH FAILED — reproduces hardware symptom in sim")
      println("  Conclusion: exact broken boundary captured; next pass can bisect the failing signal")
      sys.exit(1)
    }
  }
}
