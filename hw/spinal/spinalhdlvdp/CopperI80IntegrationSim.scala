package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** WHOLE-VDP-134 scenario #5 — copper-OVER-i80 integration sim.
  *
  * CopperBorderIntegrationSim proves copper→border works when the program +
  * enable are written via a DIRECT regBus. The board fails when the SAME
  * sequence is driven through the i80 host. This sim closes that gap: it wires
  * I80HostInterface → RegBusArbiter(3) master(2) → VdpTop.io.regBus (exactly as
  * top_tang20k_i80 does) and drives the i80 PADS through a copper upload +
  * enable, then checks the same borderCtrlReg the passing sim checks.
  *
  *   Reproduces FAIL  → the i80→arbiter→copper path is the bug → fix RTL.
  *   PASS             → RTL good, issue is silicon timing / firmware → waveform.
  */
case class CopperI80Dut() extends Component {
  val vdp = VdpTop()
  val arb = RegBusArbiter(3)
  val i80 = I80HostInterface(8)

  val io = new Bundle {
    // i80 pads (mirrors I80HostInterface)
    val cs = in Bool(); val wr = in Bool(); val rd = in Bool(); val dc = in Bool()
    val dIn = in Bits(8 bits)
  }
  i80.io.cs := io.cs; i80.io.wr := io.wr; i80.io.rd := io.rd; i80.io.dc := io.dc
  i80.io.dIn := io.dIn
  i80.io.readData := 0
  i80.io.blockWr.ready := True

  // Arbiter: master(2) = i80 (as in TopTang); masters 0/1 idle.
  for (m <- 0 until 2) {
    arb.io.masters(m).addr   := 0
    arb.io.masters(m).data   := 0
    arb.io.masters(m).enable := False
  }
  arb.io.masters(2).addr   := i80.io.regBus.addr
  arb.io.masters(2).data   := i80.io.regBus.data
  arb.io.masters(2).enable := i80.io.regBus.enable
  vdp.io.regBus <> arb.io.mixed

  // Tie off all other VdpTop inputs (quiescent — copper/border is what we test).
  vdp.io.layer0ScrollX := 0; vdp.io.layer0ScrollY := 0
  vdp.io.layer1ScrollX := 0; vdp.io.layer1ScrollY := 0
  vdp.io.layer2ScrollX := 0; vdp.io.layer2ScrollY := 0
  vdp.io.layer3ScrollX := 0; vdp.io.layer3ScrollY := 0
  vdp.io.sprite0X := 1023; vdp.io.sprite0Y := 1023; vdp.io.sprite0Enabled := False; vdp.io.sprite0PatternIdx := 0
  vdp.io.sprite1X := 1023; vdp.io.sprite1Y := 1023; vdp.io.sprite1Enabled := False; vdp.io.sprite1PatternIdx := 0
  vdp.io.sprite2X := 1023; vdp.io.sprite2Y := 1023; vdp.io.sprite2Enabled := False; vdp.io.sprite2PatternIdx := 0
  vdp.io.sprite3X := 1023; vdp.io.sprite3Y := 1023; vdp.io.sprite3Enabled := False; vdp.io.sprite3PatternIdx := 0
  vdp.io.layer0UseSdram := False; vdp.io.layer0SdramPixel := 0; vdp.io.layer0SdramBank := 0; vdp.io.layer0SdramPriority := False
  vdp.io.layer0TestPatternSelect := 0; vdp.io.layer0TestPatternEnable := False
  vdp.io.layer1UseSdram := False; vdp.io.layer1SdramPixel := 0; vdp.io.layer1SdramBank := 0; vdp.io.layer1SdramPriority := False
  vdp.io.bitmapSdramByte := 0; vdp.io.bitmapSdramAttrByte := 0
  vdp.io.rasterTriggerLine := 0; vdp.io.rasterTriggerPixel := 0; vdp.io.rasterTriggerPxEnable := False
  vdp.io.rasterTriggerEnable := False; vdp.io.rasterTriggerClear := False
  vdp.io.statusEvQspiReady := False; vdp.io.statusEvQspiError := False
  vdp.io.planarSdramBusy := False; vdp.io.planarSdramDataReady := False; vdp.io.planarSdramDout32 := 0

  // expose borderCtrlReg for the check
  val borderCtrlReg = vdp.borderCtrlReg.simPublic()
}

object CopperI80IntegrationSim extends App {
  Config.sim.compile(CopperI80Dut()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.cs #= true; dut.io.wr #= true; dut.io.rd #= true; dut.io.dc #= false; dut.io.dIn #= 0
    dut.clockDomain.waitSampling(10)

    // i80 register write: opcode 0x00, addr lo/hi (DC=0), data lo/hi (DC=1).
    def wrByte(dcv: Boolean, b: Int): Unit = {
      dut.io.cs #= false; dut.io.dc #= dcv; dut.io.dIn #= b
      dut.io.wr #= false; dut.clockDomain.waitSampling(4)
      dut.io.wr #= true;  dut.clockDomain.waitSampling(4)
    }
    def regWrite(addr: Int, data: Int): Unit = {
      wrByte(false, 0x00)
      wrByte(false, addr & 0xFF); wrByte(false, (addr >> 8) & 0xFF)
      wrByte(true,  data & 0xFF); wrByte(true,  (data >> 8) & 0xFF)
      dut.io.cs #= true; dut.clockDomain.waitSampling(3)
    }

    // Same program the passing direct-regBus sim uses, driven via i80:
    //   WAIT(10) ; WRITE BORDER_CTRL=0x1801 ; JUMP(0)
    regWrite(0x0310, 0x0000)               // disable copper (upload to active bank)
    dut.clockDomain.waitSampling(2000)     // let disable commit at hCounter==0
    regWrite(0x0400, 0x000A)               // WAIT(10)
    regWrite(0x0401, 0x4347)               // WRITE header, addr=0x0347
    regWrite(0x0402, 0x1801)               // data: palette 24 + enable
    regWrite(0x0403, 0xC000)               // JUMP(0)
    regWrite(0x0310, 0x0001)               // enable copper

    // Let it run several frames (hTotal*vTotal ~ 800*525).
    for (_ <- 0 until 6) dut.clockDomain.waitSampling(800 * 525)

    val br = dut.borderCtrlReg.toInt
    println(f"[sim] copper-over-i80: borderCtrlReg=0x$br%04X (expect 0x1801)")
    if (br == 0x1801) println("[sim] CopperI80IntegrationSim: PASS — copper writes land over the i80 path")
    else println(f"[sim] CopperI80IntegrationSim: FAIL — copper write did NOT land over i80 (got 0x$br%04X) — reproduces the board")
  }
}
