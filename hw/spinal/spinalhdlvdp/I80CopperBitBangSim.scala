package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** WHOLE-VDP-134 — cycle-accurate ASYNC bit-bang i80 host model for the
  * copper-over-i80 failure (TopazCliff #12468).
  *
  * Earlier i80 sims drove cs/wr/rd/dc/D clock-ALIGNED (waitSampling), so they
  * could not expose an async edge-detect drop / multi-bit-CDC skew. This sim
  * drives the pads from sim TIME (sleep), NOT phase-locked to the FPGA clock,
  * with WR pulses spanning several clocks (like the real ~µs ESP32 bit-bang) and
  * a programmable phase offset swept across the clock period. For each phase it
  * runs the exact 3-word copper diagnostic and checks borderCtrlReg, and counts
  * host regBus.enable pulses vs writes issued (no drop / no double).
  */
object I80CopperBitBangSim extends App {
  val PERIOD = 40                       // FPGA clock period (units); 1/4 period = 10
  Config.sim.compile {
    val d = CopperI80Dut()
    d.i80.io.regBus.enable.simPublic()
    d.i80.io.regBus.addr.simPublic()
    d.i80.io.regBus.data.simPublic()
    d
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = PERIOD)
    dut.io.cs #= true; dut.io.wr #= true; dut.io.rd #= true; dut.io.dc #= false; dut.io.dIn #= 0
    sleep(PERIOD * 5)

    // Capture host regBus write pulses (rising-edge) as (addr,data); diff vs issued.
    val caps   = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    val issued = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var prevEn = false
    dut.clockDomain.onSamplings {
      val en = dut.i80.io.regBus.enable.toBoolean
      if (en && !prevEn) caps += ((dut.i80.io.regBus.addr.toInt, dut.i80.io.regBus.data.toInt))
      prevEn = en
    }

    // --- async bit-bang primitives (sim-time, not clock-aligned) ---
    // WR pulse spans ~3.25 clocks; gaps non-integer-multiples → broad phase drift.
    val WR_LO = 130; val WR_HI = 130; val GAP = 90; val CS_SET = 210
    def wrByteBB(dcv: Boolean, b: Int): Unit = {
      dut.io.dc #= dcv; dut.io.dIn #= b   // data set + held across the whole WR pulse
      sleep(GAP)
      dut.io.wr #= false; sleep(WR_LO)
      dut.io.wr #= true;  sleep(WR_HI)    // WR rising edge latches (2-FF synced)
    }
    def regWriteBB(addr: Int, data: Int): Unit = {
      issued += ((addr & 0x7FFF, data & 0xFFFF))
      dut.io.cs #= false; sleep(CS_SET)
      wrByteBB(false, 0x00)
      wrByteBB(false, addr & 0xFF); wrByteBB(false, (addr >> 8) & 0xFF)
      wrByteBB(true,  data & 0xFF); wrByteBB(true,  (data >> 8) & 0xFF)
      dut.io.cs #= true; sleep(GAP)
    }

    def runFramesCycles(n: Int): Unit = dut.clockDomain.waitSampling(n)

    // Warmup writes to flush any capture/startup transient, then reset accounting
    // so the integrity diff reflects only the steady-state phase sweep.
    regWriteBB(0x0334, 0x1111); regWriteBB(0x0334, 0x2222)
    dut.clockDomain.waitSampling(40)
    caps.clear(); issued.clear()

    val phases = Seq(0, 10, 20, 30)     // 0, 1/4, 1/2, 3/4 of the clock period
    var anyFail = false
    for (ph <- phases) {
      sleep(ph)                          // shift host phase vs FPGA clock
      // reset border directly (unique data per phase to pinpoint any drop)
      regWriteBB(0x0347, 0x0000 | (ph << 8))  // direct: border off (phase-tagged)
      regWriteBB(0x0310, 0x0000)         // disable copper (upload→active bank)
      runFramesCycles(2000)
      regWriteBB(0x0400, 0x4347)         // WRITE BORDER_CTRL
      regWriteBB(0x0401, 0x0201)         // data: palette idx 2 + enable
      regWriteBB(0x0402, 0xC000)         // JUMP(0)
      regWriteBB(0x0310, 0x0001)         // enable copper
      runFramesCycles(800 * 525 * 2)     // ~2 frames for the copper to drain
      val br = dut.borderCtrlReg.toInt
      val ok = br == 0x0201
      if (!ok) anyFail = true
      println(f"[sim] phase=$ph%2d (offset ${ph * 100 / PERIOD}%% of clk): borderCtrlReg=0x$br%04X ${if (ok) "PASS" else "*** FAIL — reproduced ***"}")
      regWriteBB(0x0310, 0x0000)         // disable for next phase
    }

    dut.clockDomain.waitSampling(20)   // settle so the last write's pulse is sampled

    // Integrity: every issued write should appear once with correct data.
    println(f"[sim] issued=${issued.size} captured regBus pulses=${caps.size}")
    val cm = caps.clone()
    val dropped = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    for (w <- issued) { val i = cm.indexOf(w); if (i >= 0) cm.remove(i) else dropped += w }
    if (dropped.nonEmpty) println("[sim] DROPPED/CORRUPTED writes: " + dropped.map { case (a, d) => f"0x$a%04X=0x$d%04X" }.mkString(", "))
    if (cm.nonEmpty)      println("[sim] EXTRA/UNMATCHED pulses: " + cm.map { case (a, d) => f"0x$a%04X=0x$d%04X" }.mkString(", "))
    val integrity = dropped.isEmpty && cm.isEmpty && caps.size == issued.size

    if (anyFail) println("[sim] I80CopperBitBangSim: FAIL — copper-over-i80 reproduced under async bit-bang at one or more phases")
    else if (!integrity) println("[sim] I80CopperBitBangSim: border PASS but WRITE-INTEGRITY FAIL — a host write dropped/corrupted under async i80 (the copper-upload hazard)")
    else println("[sim] I80CopperBitBangSim: PASS — copper writes land + every host write intact at all swept phases")
  }
}
