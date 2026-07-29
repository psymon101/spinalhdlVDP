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
    val blkBytes = scala.collection.mutable.ArrayBuffer[Int]()
    dut.io.blockWr.ready #= true
    dut.clockDomain.onSamplings {
      if (dut.io.regBus.enable.toBoolean) captured += ((dut.io.regBus.addr.toInt, dut.io.regBus.data.toInt))
      if (dut.io.blockWr.valid.toBoolean && dut.io.blockWr.ready.toBoolean) blkBytes += dut.io.blockWr.payload.toInt
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
    dut.io.cs #= true; dut.clockDomain.waitSampling(5)

    // --- block write: opcode 0x02, addr 0x012345, len 4, payload DE AD BE EF ---
    wrByte(false, 0x02)                                    // opcode: block write (DC=0)
    wrByte(false, 0x45); wrByte(false, 0x23); wrByte(false, 0x01)  // addr lo/mid/hi (3B, DC=0)
    wrByte(false, 0x04); wrByte(false, 0x00)              // length = 4 (lo/hi, DC=0)
    wrByte(true, 0xDE); wrByte(true, 0xAD); wrByte(true, 0xBE); wrByte(true, 0xEF)  // payload (DC=1)
    dut.clockDomain.waitSampling(8)
    val blkAddr = dut.io.blockAddr.toLong
    val overflow = dut.io.blockOverflow.toBoolean
    dut.io.cs #= true; dut.clockDomain.waitSampling(5)
    println(f"[sim] block: addr=0x$blkAddr%06X bytes=${blkBytes.map(b => f"$b%02X").mkString(" ")} overflow=$overflow")
    assert(blkAddr == 0x012345L, f"block addr wrong: 0x$blkAddr%06X")
    assert(blkBytes.toSeq == Seq(0xDE, 0xAD, 0xBE, 0xEF), s"block payload wrong: $blkBytes")
    assert(!overflow, "unexpected block overflow")

    // --- DC/WR race regression (HAM-DECODER-171 / CyanPeak #12977) ---
    // Reproduce the real failure: the host flips DC high to start the data phase on the
    // SAME edge it deasserts WR for the addr-hi byte, so the 2-FF-synchronized `dcS` is
    // already high by the time `wrRise` reaches the FSM. Pre-fix, `sAddrHi`'s `&& !dcS`
    // guard then evaluated false and the FSM stalled (the write was lost → black frame).
    // Post-fix, internal transitions advance on `csActive && wrRise` alone, so the write
    // still lands. Asserts the register write commits despite the racy DC edge.
    captured.clear()
    def wrByteDcFlipAtEdge(b: Int, dcAfter: Boolean): Unit = {
      dut.io.dIn #= b
      dut.io.wr #= false; dut.clockDomain.waitSampling(4)   // WR asserted (DC stable for this byte)
      dut.io.wr #= true;  dut.io.dc #= dcAfter              // WR rises AND DC flips on the same edge
      dut.clockDomain.waitSampling(2)
    }
    dut.io.cs #= false; dut.io.dc #= false
    wrByte(false, 0x00)                            // opcode reg-write (DC=0, stable)
    wrByte(false, 0x47)                            // addr lo (DC=0)
    wrByteDcFlipAtEdge(0x03, dcAfter = true)       // addr hi (DC=0 during byte) → DC→1 at the WR edge (RACE)
    wrByte(true, 0x34); wrByte(true, 0x12)         // data lo/hi (DC=1)
    dut.clockDomain.waitSampling(8)
    dut.io.cs #= true; dut.clockDomain.waitSampling(5)
    println(s"[sim] racy reg writes: ${captured.map { case (a, d) => f"0x$a%04X=0x$d%04X" }.mkString(", ")}")
    assert(captured.size == 1 && captured.head == (0x347, 0x1234),
           s"DC/WR race regression: addr-hi DC-flip-at-edge lost the write: $captured")

    println("I80HostInterfaceSim: PASS — reg write 0x0347=0x1234; reg read 0xBEEF; " +
            "block 0x012345 <- DE AD BE EF (no overflow); DC/WR-race reg write 0x0347=0x1234")
  }
}
