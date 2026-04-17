package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Unit test for `QspiSlave`. Avoids async watcher threads; just sends
  * stimulus and samples the latched outputs after enough clocks pass.
  */
object QspiSlaveSim extends App {
  Config.sim.compile(QspiSlave()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Defaults.
    dut.io.spi_cs_n #= true
    dut.io.spi_sck  #= false
    dut.io.spi_io_in #= 0
    dut.io.tx_byte  #= 0
    dut.io.tx_load  #= false
    dut.clockDomain.waitSampling(20)

    // SCK half cycle — 20 pixel clocks gives the 2-stage sync plenty of
    // margin to latch the new IO and SCK values before the next edge.
    val H = 20

    def sendNibble(nibble: Int): Unit = {
      // Stable IO for half a cycle before SCK goes high.
      dut.io.spi_io_in #= (nibble & 0xF)
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck  #= true
      dut.clockDomain.waitSampling(H)
      dut.io.spi_sck  #= false
    }
    def sendByte(b: Int): Unit = {
      sendNibble((b >> 4) & 0xF)
      sendNibble(b & 0xF)
    }

    // Track cmd_valid + payload across the run so we can look at side-effects later.
    var cmdValidCount = 0
    val payloadBuf = scala.collection.mutable.ArrayBuffer[Int]()
    val watcher = fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.cmd_valid.toBoolean)     cmdValidCount += 1
        if (dut.io.payload_valid.toBoolean) payloadBuf += dut.io.payload_byte.toInt
      }
    }

    def doTxn(header: Seq[Int], payload: Seq[Int] = Seq.empty): Unit = {
      dut.io.spi_cs_n #= false
      dut.clockDomain.waitSampling(5)
      header.foreach(sendByte)
      payload.foreach(sendByte)
      dut.clockDomain.waitSampling(H * 4)   // let turnaround settle
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(40)
    }

    // ---- Case 1: REG_WRITE CMD=0x01 ADDR=0x000300 LEN=1 DATA=0x0007 ----
    println("Case 1: REG_WRITE ADDR=0x000300 LEN=1 DATA=0x0007")
    payloadBuf.clear()
    val before1 = cmdValidCount
    doTxn(Seq(0x01, 0x00, 0x03, 0x00, 0x01, 0x00), Seq(0x07, 0x00))
    val after1 = cmdValidCount
    assert(after1 - before1 == 1, s"Case 1: expected 1 cmd_valid pulse, saw ${after1 - before1}")
    val op1    = dut.io.cmd_opcode.toInt
    val addr1  = dut.io.cmd_addr.toLong
    val len1   = dut.io.cmd_len.toInt
    val bcnt1  = dut.io.byte_count.toInt
    assert(op1 == 0x01,       f"Case 1 op=0x$op1%02X, want 0x01")
    assert(addr1 == 0x000300L, f"Case 1 addr=0x$addr1%06X, want 0x000300")
    assert(len1 == 0x0001,     s"Case 1 len=$len1, want 1")
    assert(bcnt1 == 8,         s"Case 1 byte_count=$bcnt1, want 8 (6 header + 2 payload)")
    assert(payloadBuf == Seq(0x07, 0x00), s"Case 1 payload=$payloadBuf, want Seq(0x07, 0x00)")
    println(f"Case 1 PASS: op=0x$op1%02X addr=0x$addr1%06X len=$len1 payload=${payloadBuf.map(_.formatted("0x%02X")).mkString(",")}")

    // ---- Case 2: REG_WRITE 4-word burst, ADDR=0x000100 ----
    println("Case 2: REG_WRITE ADDR=0x000100 LEN=4 DATA=0x0A,0x0B,0x0C,0x0D")
    payloadBuf.clear()
    val before2 = cmdValidCount
    val payload2 = Seq(0x0A, 0x00, 0x0B, 0x00, 0x0C, 0x00, 0x0D, 0x00)
    doTxn(Seq(0x01, 0x00, 0x01, 0x00, 0x04, 0x00), payload2)
    val after2 = cmdValidCount
    assert(after2 - before2 == 1, s"Case 2: expected 1 cmd_valid pulse, saw ${after2 - before2}")
    val addr2 = dut.io.cmd_addr.toLong
    val len2  = dut.io.cmd_len.toInt
    assert(addr2 == 0x000100L, f"Case 2 addr=0x$addr2%06X, want 0x000100")
    assert(len2 == 4,          s"Case 2 len=$len2, want 4")
    assert(payloadBuf == payload2, s"Case 2 payload=$payloadBuf, want $payload2")
    println(f"Case 2 PASS: 4-word payload stream matches")

    // ---- Case 3: READ_STATUS CMD=0x04 LEN=0 ----
    println("Case 3: READ_STATUS CMD=0x04 LEN=0")
    payloadBuf.clear()
    val before3 = cmdValidCount
    doTxn(Seq(0x04, 0x00, 0x00, 0x00, 0x00, 0x00))
    val after3 = cmdValidCount
    assert(after3 - before3 == 1, s"Case 3: expected 1 cmd_valid pulse, saw ${after3 - before3}")
    val op3 = dut.io.cmd_opcode.toInt
    val len3 = dut.io.cmd_len.toInt
    assert(op3 == 0x04, f"Case 3 op=0x$op3%02X, want 0x04")
    assert(len3 == 0,   s"Case 3 len=$len3, want 0")
    assert(payloadBuf.isEmpty, s"Case 3: no payload expected, got $payloadBuf")
    println(f"Case 3 PASS: READ_STATUS header decoded (op=0x$op3%02X, len=$len3)")

    println("QspiSlaveSim: all 3 cases PASS")
  }
}
