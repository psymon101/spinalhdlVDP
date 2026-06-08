package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** I80HostInterfaceSim — Checkpoint B start (lane P21). Drives the i80 strobes
  * for a 4-byte register write and a register read, verifying the FSM emits the
  * correct regBus write and drives D with the pre-latched read data. */
object I80HostInterfaceSim extends App {
  Config.sim.compile(I80HostInterface(8)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.cs #= true; dut.io.wr #= true; dut.io.rd #= true; dut.io.dc #= false
    dut.io.dIn #= 0; dut.io.readData #= 0; dut.io.blockWr.ready #= true
    dut.clockDomain.waitSampling(5)

    val captured = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    dut.clockDomain.onSamplings {
      if (dut.io.regBus.enable.toBoolean) captured += ((dut.io.regBus.addr.toInt, dut.io.regBus.data.toInt))
    }

    def wrByte(dcv: Boolean, b: Int): Unit = {
      dut.io.cs #= false; dut.io.dc #= dcv; dut.io.dIn #= b
      dut.io.wr #= false; dut.clockDomain.waitSampling(4)   // WR asserted (active low)
      dut.io.wr #= true;  dut.clockDomain.waitSampling(4)   // WR rising edge -> latch
    }

    // --- register write: opcode 0x00, addr 0x0347 (BORDER_CTRL), data 0x1234 ---
    wrByte(false, 0x00)                        // opcode: reg write (DC=0)
    wrByte(false, 0x47); wrByte(false, 0x03)   // addr lo, hi  (DC=0)
    wrByte(true,  0x34); wrByte(true,  0x12)   // data lo, hi  (DC=1)
    dut.clockDomain.waitSampling(8)
    dut.io.cs #= true; dut.clockDomain.waitSampling(5)

    println(s"[sim] regBus writes: ${captured.map { case (a, d) => f"0x$a%04X=0x$d%04X" }.mkString(", ")}")
    assert(captured.size == 1 && captured.head == (0x347, 0x1234), s"reg-write FSM wrong: $captured")

    // --- register read: opcode 0x01 + addr, then readData=0xBEEF via two RD strobes ---
    dut.io.readData #= 0xBEEF
    wrByte(false, 0x01)                        // opcode: reg read (DC=0)
    wrByte(false, 0x47); wrByte(false, 0x03)   // read addr lo, hi (DC=0) -> readReq pulse
    dut.io.cs #= false; dut.io.dc #= true
    def rdByte(): Int = {
      dut.io.rd #= false; dut.clockDomain.waitSampling(4)
      val v = dut.io.dOut.toInt & 0xFF
      dut.io.rd #= true; dut.clockDomain.waitSampling(4); v
    }
    val lo = rdByte(); val hi = rdByte()
    dut.io.cs #= true; dut.clockDomain.waitSampling(3)
    println(f"[sim] read returned lo=0x$lo%02X hi=0x$hi%02X (expect EF, BE)")
    assert(lo == 0xEF && hi == 0xBE, s"reg-read wrong: lo=$lo hi=$hi")

    println("I80HostInterfaceSim: PASS — i80 reg write -> regBus(0x0347,0x1234); reg read -> 0xBEEF")
  }
}
