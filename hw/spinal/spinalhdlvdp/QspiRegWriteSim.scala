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
      val statusStickyIn = in Bits (16 bits)
      // Expose the decoded register-write pulses for sim inspection.
      val regWriteAddr   = out UInt (15 bits)   // kept for test assertions
      val regWriteData   = out Bits (16 bits)
      val regWriteEnable = out Bool()            // Task 32b: hoisted from dec.io.regBus
      // Task 38b: expose spi_io_out / spi_io_oe so response bytes are
      // observable at nibble granularity during sim.
      val spi_io_out     = out Bits (4 bits)
      val spi_io_oe      = out Bool()
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
    dec.io.status_sticky := io.statusStickyIn  // Task 35: driven by tb
    dec.io.live_mode := 0
    // Task 34: tie bridge status inputs off in this harness; the decoder
    // drives bridge outputs, which the TB does not consume (no bridge
    // instantiated here). A SdramUploadSim covers the bridge path.
    dec.io.upload_busy := False
    dec.io.upload_done := False
    dec.io.upload_error := False
    dec.io.upload_overflow := False
    // Word-drain byte/word egress inputs (added by transport-core cherry-pick a4dcdf4);
    // this legacy REG_WRITE sim exercises the payload_byte path, so tie them inactive.
    dec.io.payload_word       := 0
    dec.io.payload_word_valid := False
    // #11308: tie off the sel=8 DIAG readback input (added later for #10908; this
    // harness never drove it -> a NO-DRIVER elaboration failure that had silently
    // broken QspiRegWriteSim). Tying it off restores the REG_WRITE regression.
    dec.io.debug_sdram_data := 0
    io.regWriteAddr   := dec.io.regBus.addr
    io.regWriteData   := dec.io.regBus.data
    io.regWriteEnable := dec.io.regBus.enable
    io.spi_io_out     := slave.io.spi_io_out
    io.spi_io_oe      := slave.io.spi_io_oe
  }

  Config.sim.compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.spi_cs_n     #= true
    dut.io.spi_sck      #= false
    dut.io.spi_io_in    #= 0
    dut.io.statusStickyIn #= 0
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

    println(f"Case 3.5 PASS: 4-bit fidelity complete")

    // ---- Case 4: Task 38b — READ_STATUS sel=0..4 response coverage ----
    // After the prior REG_WRITE cases, the decoder's diagnostic state is:
    //   rx_cmd_cnt = 7  (3 regs plus 5 fidelity cases — but each header
    //                    counts once, so total cmd_valid pulses = 7)
    //   last_addr  = 0x0300  (from Case 3.5's 0x00F3 write to 0x0300)
    //   last_data  = 0x00F3
    //   last_error = 0x00    (no unknown opcodes seen so far)
    //
    // For each sel, we issue a READ_STATUS and capture the 4 response
    // bytes at the nibble-level (spi_io_out during spi_io_oe high).
    def captureResponse(sel: Int): Seq[Int] = {
      val bytes = scala.collection.mutable.ArrayBuffer[Int]()
      var nibbleAccum = 0
      var nibbleHaveHigh = false
      val respWatcher = fork {
        var lastOe = false
        var lastSck = false
        var ticks = 0
        while (ticks < 80000 && bytes.length < 4) {
          dut.clockDomain.waitSampling(); ticks += 1
          val oeNow  = dut.io.spi_io_oe.toBoolean
          val sckNow = dut.io.spi_sck.toBoolean
          // Sample on SCK rising while oe is high (slave drives).
          if (oeNow && sckNow && !lastSck) {
            val nibble = dut.io.spi_io_out.toInt & 0xF
            if (!nibbleHaveHigh) {
              nibbleAccum = nibble << 4
              nibbleHaveHigh = true
            } else {
              bytes += (nibbleAccum | nibble)
              nibbleHaveHigh = false
            }
          }
          lastOe = oeNow; lastSck = sckNow
        }
      }
      // Drive the transaction. READ_STATUS = CMD 0x04, addr low byte = sel.
      dut.io.spi_cs_n #= false
      dut.clockDomain.waitSampling(5)
      Seq(0x04, sel & 0xFF, 0x00, 0x00, 0x00, 0x00).foreach(sendByte)
      // Provide 2 turnaround + 8 response SCK edges worth of stimulus.
      for (_ <- 0 until (2 + 8)) {
        dut.io.spi_io_in #= 0
        dut.clockDomain.waitSampling(H); dut.io.spi_sck #= true
        dut.clockDomain.waitSampling(H); dut.io.spi_sck #= false
      }
      dut.clockDomain.waitSampling(H * 4)
      dut.io.spi_cs_n #= true
      dut.clockDomain.waitSampling(80)
      respWatcher.join()
      bytes.toSeq
    }

    // sel=0: magic 0x51560002 → host sees bytes 0x02, 0x00, 0x56, 0x51
    println("Case 4.0: READ_STATUS sel=0 — magic 0x51560002")
    val r0 = captureResponse(0)
    assert(r0 == Seq(0x02, 0x00, 0x56, 0x51), f"Case 4.0: got ${r0.map("0x%02X".format(_)).mkString(",")}")
    println(f"Case 4.0 PASS: bytes=${r0.map("0x%02X".format(_)).mkString(",")} — magic retained")

    // sel=1..3 are intentionally zero in the current decoder contract.
    // They were retired from the live status surface to keep the fit clean.
    println("Case 4.1: READ_STATUS sel=1 — retired selector should read zero")
    val r1 = captureResponse(1)
    assert(r1.length == 4, s"Case 4.1 expected 4 bytes, got ${r1.length}")
    assert(r1 == Seq(0x00, 0x00, 0x00, 0x00), f"Case 4.1: got ${r1.map("0x%02X".format(_)).mkString(",")}, want 0x00,0x00,0x00,0x00")
    println(f"Case 4.1 PASS: retired selector returned zeros")

    // sel=2 is also retired and should stay zero.
    println("Case 4.2: READ_STATUS sel=2 — retired selector should read zero")
    val r2 = captureResponse(2)
    assert(r2 == Seq(0x00, 0x00, 0x00, 0x00), f"Case 4.2: got ${r2.map("0x%02X".format(_)).mkString(",")}, want zeros")
    println(f"Case 4.2 PASS: retired selector returned zeros")

    // sel=3 is also retired and should stay zero.
    println("Case 4.3: READ_STATUS sel=3 — retired selector should read zero")
    val r3 = captureResponse(3)
    assert(r3 == Seq(0x00, 0x00, 0x00, 0x00), f"Case 4.3: got ${r3.map("0x%02X".format(_)).mkString(",")}, want zeros")
    println(f"Case 4.3 PASS: retired selector returned zeros")

    // sel=4: last_error = 0x00 (no unknown opcodes in this sim) → 0,0,0,0
    println("Case 4.4: READ_STATUS sel=4 — last_error")
    val r4 = captureResponse(4)
    assert(r4 == Seq(0x00, 0x00, 0x00, 0x00), f"Case 4.4: got ${r4.map("0x%02X".format(_)).mkString(",")}")
    println(f"Case 4.4 PASS: last_error=0x00 observed as zeros")

    // ---- Case 5: snapshot behavior — internal state change mid-response
    // must not corrupt the in-flight READ_STATUS output. Use sel=5 because
    // it remains in the live surface and is easy to observe.
    println("Case 5: snapshot behavior — state change mid-response does not corrupt output")
    dut.io.statusStickyIn #= 0x1234
    val snapBytes = scala.collection.mutable.ArrayBuffer[Int]()
    val snapWatcher = fork {
      var nibbleAccum = 0
      var nibbleHaveHigh = false
      var ticks = 0
      var lastOe = false
      var lastSck = false
      while (ticks < 80000 && snapBytes.length < 4) {
        dut.clockDomain.waitSampling(); ticks += 1
        val oeNow  = dut.io.spi_io_oe.toBoolean
        val sckNow = dut.io.spi_sck.toBoolean
        if (oeNow && sckNow && !lastSck) {
          val nibble = dut.io.spi_io_out.toInt & 0xF
          if (!nibbleHaveHigh) {
            nibbleAccum = nibble << 4
            nibbleHaveHigh = true
          } else {
            snapBytes += (nibbleAccum | nibble)
            nibbleHaveHigh = false
          }
        }
        lastOe = oeNow; lastSck = sckNow
      }
    }
    val stateChanger = fork {
      var spins = 0
      while (snapBytes.length == 0 && spins < 80000) {
        dut.clockDomain.waitSampling()
        spins += 1
      }
      dut.clockDomain.waitSampling(20)
      dut.io.statusStickyIn #= 0xABCD
    }
    dut.io.spi_cs_n #= false
    dut.clockDomain.waitSampling(5)
    Seq(0x04, 0x05, 0x00, 0x00, 0x00, 0x00).foreach(sendByte)
    for (_ <- 0 until (2 + 8)) {
      dut.io.spi_io_in #= 0
      dut.clockDomain.waitSampling(H); dut.io.spi_sck #= true
      dut.clockDomain.waitSampling(H); dut.io.spi_sck #= false
    }
    dut.clockDomain.waitSampling(H * 4)
    dut.io.spi_cs_n #= true
    dut.clockDomain.waitSampling(80)
    stateChanger.join()
    snapWatcher.join()
    assert(snapBytes == Seq(0x34, 0x12, 0x00, 0x00), f"Case 5: snapshot corrupted, got ${snapBytes.map("0x%02X".format(_)).mkString(",")}")
    println(f"Case 5 PASS: sel=5 snapshot stayed at 0x1234 despite mid-response state change")

    // ---- Case 6: Task 35 — sel=5 STATUS_STICKY readback ----
    // Drive a pattern on statusStickyIn and verify the response reflects
    // the low-16-bits-in-bytes-0-and-1 mapping. This is a pure pipe test
    // of the decoder's sel=5 case; the full sticky-bank behaviour is
    // covered by StatusRegSim against VdpTop.
    def testSticky(label: String, sticky: Int, expected: Seq[Int]): Unit = {
      println(label)
      dut.io.statusStickyIn #= sticky
      dut.clockDomain.waitSampling(2)
      val resp = captureResponse(5)
      assert(resp == expected, f"$label got ${resp.map("0x%02X".format(_)).mkString(",")}, want ${expected.map("0x%02X".format(_)).mkString(",")}")
      println(f"$label PASS: sticky=0x${sticky}%04X -> bytes=${resp.map("0x%02X".format(_)).mkString(",")}")
    }
    testSticky("Case 6.0: sel=5 sticky=0x0000", 0x0000, Seq(0x00, 0x00, 0x00, 0x00))
    testSticky("Case 6.1: sel=5 sticky=0x0001 (RASTER_MATCH alone)",      0x0001, Seq(0x01, 0x00, 0x00, 0x00))
    testSticky("Case 6.2: sel=5 sticky=0x000F (all four events)",         0x000F, Seq(0x0F, 0x00, 0x00, 0x00))
    testSticky("Case 6.3: sel=5 sticky=0xABCD (arbitrary 16-bit pattern)", 0xABCD, Seq(0xCD, 0xAB, 0x00, 0x00))

    println("QspiRegWriteSim: all cases PASS — QspiSlave -> QspiDecoder -> regWrite* + READ_STATUS sel=0..5 verified")
  }
}
