package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Integration sim for the QSPI ingress chain — wires `QspiSlave` →
  * `QspiDecoder` inside a small test harness and verifies that QSPI
  * stimulus produces the correct `regWriteAddr/Data/Enable` pulses on
  * the VdpTop-facing bus.
  *
  * Test cases:
  *   1. REG_WRITE ADDR=0x0300 LEN=1 DATA=0x0005 → one pulse at addr=0x0300,
  *      data=0x0005.
  *   2. REG_WRITE ADDR=0x0340 LEN=3 (three-word burst) → three pulses at
  *      addresses 0x0340, 0x0341, 0x0342 with the expected data words.
  */
object QspiRegWriteSim extends App {
  class Harness extends Component {
    val io = new Bundle {
      val spi_cs_n  = in Bool()
      val spi_sck   = in Bool()
      val spi_io_in = in Bits (4 bits)
      // Expose the decoded register-write pulses for sim inspection.
      val regWriteAddr   = out UInt (15 bits)
      val regWriteData   = out Bits (16 bits)
      val regWriteEnable = out Bool()
    }
    val slave = QspiSlave()
    val dec   = QspiDecoder()
    slave.io.spi_cs_n  := io.spi_cs_n
    slave.io.spi_sck   := io.spi_sck
    slave.io.spi_io_in := io.spi_io_in
    dec.io.cmd_opcode    := slave.io.cmd_opcode
    dec.io.cmd_addr      := slave.io.cmd_addr
    dec.io.cmd_len       := slave.io.cmd_len
    dec.io.cmd_valid     := slave.io.cmd_valid
    dec.io.payload_byte  := slave.io.payload_byte
    dec.io.payload_valid := slave.io.payload_valid
    dec.io.tx_byte_sent  := slave.io.tx_byte_sent
    dec.io.active        := slave.io.active
    slave.io.tx_byte := dec.io.tx_byte
    slave.io.tx_load := dec.io.tx_load
    io.regWriteAddr   := dec.io.regWriteAddr
    io.regWriteData   := dec.io.regWriteData
    io.regWriteEnable := dec.io.regWriteEnable
  }

  Config.sim.compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.spi_cs_n  #= true
    dut.io.spi_sck   #= false
    dut.io.spi_io_in #= 0
    dut.clockDomain.waitSampling(20)

    val H = 20
    def sendNibble(n: Int): Unit = {
      dut.io.spi_io_in #= (n & 0xF)
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= true
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck #= false
    }
    def sendByte(b: Int): Unit = {
      sendNibble((b >> 4) & 0xF)
      sendNibble(b & 0xF)
    }

    val writes = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    val watcher = fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.regWriteEnable.toBoolean) {
          writes += ((dut.io.regWriteAddr.toInt, dut.io.regWriteData.toInt))
        }
      }
    }

    def doTxn(header: Seq[Int], payload: Seq[Int]): Unit = {
      dut.io.spi_cs_n #= false
      dut.clockDomain.waitSampling(5)
      header.foreach(sendByte)
      payload.foreach(sendByte)
      dut.clockDomain.waitSampling(H * 4)
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(40)
    }

    // ---- Case 1: single-word REG_WRITE to LAYER_ENABLE ----
    println("Case 1: REG_WRITE 0x0300 <- 0x0005")
    writes.clear()
    doTxn(Seq(0x01, 0x00, 0x03, 0x00, 0x01, 0x00), Seq(0x05, 0x00))
    assert(writes.size == 1,   s"Case 1: expected 1 regWrite pulse, got ${writes.size}: $writes")
    assert(writes(0)._1 == 0x0300, f"Case 1: addr=0x${writes(0)._1}%04X, want 0x0300")
    assert(writes(0)._2 == 0x0005, f"Case 1: data=0x${writes(0)._2}%04X, want 0x0005")
    println(f"Case 1 PASS: regWriteAddr=0x0300 regWriteData=0x0005 — single pulse")

    // ---- Case 2: 3-word burst at 0x0340, 0x0341, 0x0342 ----
    println("Case 2: REG_WRITE 0x0340 <- {0x0100, 0x00C0, 0xFF00}  (burst of 3 words)")
    writes.clear()
    val header2  = Seq(0x01, 0x40, 0x03, 0x00, 0x03, 0x00)
    val payload2 = Seq(0x00, 0x01, 0xC0, 0x00, 0x00, 0xFF)
    doTxn(header2, payload2)
    assert(writes.size == 3,   s"Case 2: expected 3 regWrite pulses, got ${writes.size}: $writes")
    val expected2 = Seq((0x0340, 0x0100), (0x0341, 0x00C0), (0x0342, 0xFF00))
    writes.zip(expected2).zipWithIndex.foreach { case (((a, d), (ea, ed)), i) =>
      assert(a == ea, f"Case 2[$i]: addr=0x$a%04X, want 0x$ea%04X")
      assert(d == ed, f"Case 2[$i]: data=0x$d%04X, want 0x$ed%04X")
    }
    println(f"Case 2 PASS: 3 pulses at 0x0340..0x0342 with expected data")

    // ---- Case 3: Task 27 hardening — 4-bit payload fidelity ----
    // Each test value is a 16-bit word whose nibbles exercise bits 2 and/or
    // 3 specifically (the ones that were lost on 2-wire hardware pre-hardening).
    // If any bit of any nibble is dropped silently, these cases fail. They
    // complement the Checkpoint A HDL/CST change proving {I_qspi_io3, io2,
    // io1, io0} reaches QspiSlave.spi_io_in correctly.
    def fidelityCase(caseId: Int, data: Int): Unit = {
      val lo = data & 0xFF
      val hi = (data >> 8) & 0xFF
      val label = f"Case 3.$caseId: REG_WRITE 0x0300 <- 0x$data%04X"
      println(label)
      writes.clear()
      doTxn(Seq(0x01, 0x00, 0x03, 0x00, 0x01, 0x00), Seq(lo, hi))
      assert(writes.size == 1, s"$label expected 1 pulse, got ${writes.size}: $writes")
      assert(writes(0)._1 == 0x0300, f"$label addr=0x${writes(0)._1}%04X, want 0x0300")
      assert(writes(0)._2 == data,   f"$label data=0x${writes(0)._2}%04X, want 0x$data%04X (bit-exact)")
      println(f"$label PASS: all 4 bits of each nibble round-tripped")
    }
    fidelityCase(1, 0xFFFF)  // every bit set — every nibble is 0xF
    fidelityCase(2, 0xAAAA)  // 0b1010 in each nibble — tests bits 1,3
    fidelityCase(3, 0x5555)  // 0b0101 in each nibble — tests bits 0,2
    fidelityCase(4, 0xBEEF)  // mixed nibbles spanning all bit positions
    fidelityCase(5, 0x00F3)  // pathological case — bit 2/3 only in low nibble; the
                             //                       exact value that broke on 2-wire

    println("QspiRegWriteSim: all cases PASS — QspiSlave -> QspiDecoder -> regWrite* chain verified with 4-bit fidelity")
  }
}
