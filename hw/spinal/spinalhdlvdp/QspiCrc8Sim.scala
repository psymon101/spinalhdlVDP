package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** QSPI-CRC8-185 (#14274) — proof of per-write-transaction CRC8 detect-and-flag.
  *
  * Drives the REAL QspiTransportCore over bit-level SCLK with SDRAM_WRITE frames that append a
  * trailing CRC-8-CCITT byte (poly 0x07, init 0x00, over CMD+ADDR+LEN+payload), then reads
  * READ_STATUS sel=11 to observe {crcErrSticky, crcErrCount}.
  *
  * SELF-VALIDATING:
  *  - sel=0 magic 0x51560002 (validates the read-sampling helper).
  *  - Test A (correct CRC): sel=11 sticky=0, count=0; exactly LEN*2 payload bytes forwarded on
  *    sdramByteOut (the CRC byte is NOT delivered to the decoder).
  *  - Test B (corrupted CRC): sel=11 sticky=1, count=1.
  *
  * Run: sbt "runMain spinalhdlvdp.QspiCrc8Sim"
  */
object QspiCrc8Sim extends App {
  // Scala reference CRC-8-CCITT — MUST match QspiSlaveSync.crc8Byte and the host implementation.
  def crc8(bytes: Seq[Int]): Int = {
    var c = 0x00
    for (b <- bytes) {
      c ^= (b & 0xFF)
      for (_ <- 0 until 8) c = if ((c & 0x80) != 0) ((c << 1) ^ 0x07) & 0xFF else (c << 1) & 0xFF
    }
    c
  }

  Config.sim.compile(QspiTransportCore(fifoDepth = 512, dummyCycles = 2)).doSim { dut =>
    val sysPeriod = 37
    val sclkPeriod = 40

    dut.io.clk #= false
    dut.io.sclk #= false; dut.io.csn #= true; dut.io.ioIn #= 0
    dut.io.debug_sdram_data #= 0

    fork { while (true) { dut.io.clk #= true; sleep(sysPeriod/2); dut.io.clk #= false; sleep(sysPeriod - sysPeriod/2) } }

    // clk_sys monitor: collect SDRAM payload bytes forwarded to the bridge egress.
    val sdramBytes = ArrayBuffer[Int]()
    var pClk = false
    fork {
      while (true) {
        sleep(1)
        val c = dut.io.clk.toBoolean
        if (c && !pClk && dut.io.sdramByteValid.toBoolean) sdramBytes += (dut.io.sdramByteOut.toInt & 0xFF)
        pClk = c
      }
    }

    // ---- SCLK bit-bang ----
    def clkRise(): Unit = { dut.io.sclk #= true; sleep(sclkPeriod/2) }
    def clkFall(): Unit = { dut.io.sclk #= false; sleep(sclkPeriod - sclkPeriod/2) }
    def sendSingle(v: BigInt, bits: Int): Unit =
      for (i <- (bits-1) to 0 by -1) { dut.io.ioIn #= (((v>>i)&1).toInt); sleep(sclkPeriod/2); clkRise(); clkFall() }
    def sendQuad(bytes: Seq[Int]): Unit =
      for (b <- bytes) { dut.io.ioIn #= ((b>>4)&0xF); sleep(sclkPeriod/2); clkRise(); clkFall()
                         dut.io.ioIn #= (b&0xF);   sleep(sclkPeriod/2); clkRise(); clkFall() }
    def startTxn(): Unit = { dut.io.csn #= false; sleep(2*sclkPeriod) }
    // Hold CS# low for a while after the last byte so the clk_sys domain samples the crcBad
    // level (the CRC byte has no trailing SCLK edge). Real hosts hold CS# far longer.
    def endTxn():   Unit = { dut.io.sclk #= false; sleep(12*sysPeriod); dut.io.csn #= true; sleep(8*sclkPeriod) }

    /** SDRAM_WRITE(0x02) of `payload` bytes at `addr`, appending `crcByte`. Frame:
      * CMD(8) ADDR(24) single-line, then LEN(2) + payload + CRC(1) quad. */
    def writeFrame(addr: Int, payload: Seq[Int], crcByte: Int): Unit = {
      val nWords = payload.size / 2
      startTxn()
      sendSingle(0x02, 8); sendSingle(addr, 24)
      val len = Seq(nWords & 0xFF, (nWords >> 8) & 0xFF)
      sendQuad(len ++ payload ++ Seq(crcByte & 0xFF))
      endTxn()
    }
    def frameCrc(addr: Int, payload: Seq[Int]): Int = {
      val nWords = payload.size / 2
      crc8(Seq(0x02, (addr>>16)&0xFF, (addr>>8)&0xFF, addr&0xFF, nWords&0xFF, (nWords>>8)&0xFF) ++ payload)
    }

    /** READ_STATUS(sel): CMD 0x04 + 24-bit ADDR (low byte=sel), dummy turnaround, 8 nibbles.
      * (Sampling: FPGA launches the first RDATA nibble one edge into the dummy window.) */
    def readStatus(sel: Int): BigInt = {
      startTxn(); sendSingle(0x04, 8); sendSingle(sel & 0xFF, 24)
      for (_ <- 0 until 1) { clkRise(); clkFall() }
      val nibs = ArrayBuffer[Int]()
      for (_ <- 0 until 8) { clkRise(); clkFall(); nibs += (if (dut.io.ioOe.toBoolean) dut.io.ioOut.toInt & 0xF else -1) }
      endTxn()
      var w = BigInt(0)
      for (byteIdx <- 0 until 4) { val b = ((nibs(byteIdx*2)&0xF)<<4)|(nibs(byteIdx*2+1)&0xF); w |= BigInt(b) << (byteIdx*8) }
      w
    }

    sleep(20*sclkPeriod)
    println("=== QspiCrc8Sim (per-write CRC8 detect-and-flag) ===")
    var failures = 0
    def check(c: Boolean, m: String): Unit = { if (!c) { failures += 1; println(s"  [FAIL] $m") } else println(s"  [PASS] $m") }

    // Control: magic read validates the sampling helper.
    val magic = readStatus(0)
    check(magic == BigInt("51560002",16), f"sel=0 magic 0x51560002 (got 0x$magic%08X) — read helper validated")

    // Test A: correct CRC → no error, LEN*2 payload bytes forwarded (CRC byte not forwarded).
    val payloadA = (0 until 128).map(i => (i * 7 + 3) & 0xFF)   // 64 words = 128 bytes
    sdramBytes.clear()
    writeFrame(0x1000, payloadA, frameCrc(0x1000, payloadA))
    sleep(50000)
    val s11a = readStatus(11)
    val stickyA = (s11a >> 16) & 1; val countA = s11a & 0xFFFF
    check(stickyA == 0, f"Test A correct CRC: sel=11 crcErrSticky=0 (got 0x$s11a%08X)")
    check(countA == 0, f"Test A: crcErrCount=0 (got $countA)")
    check(sdramBytes.size == payloadA.size, s"Test A: exactly ${payloadA.size} payload bytes forwarded, CRC byte dropped (got ${sdramBytes.size})")
    check(sdramBytes.toSeq == payloadA, "Test A: forwarded payload byte-exact")

    // Test B: corrupt the CRC byte (flip low nibble) → sticky=1, count=1.
    val payloadB = (0 until 128).map(i => (i * 11 + 5) & 0xFF)
    sdramBytes.clear()
    writeFrame(0x2000, payloadB, frameCrc(0x2000, payloadB) ^ 0x0F)   // wrong CRC
    sleep(50000)
    val s11b = readStatus(11)
    val stickyB = (s11b >> 16) & 1; val countB = s11b & 0xFFFF
    check(stickyB == 1, f"Test B corrupted CRC: sel=11 crcErrSticky=1 (got 0x$s11b%08X)")
    check(countB == 1, f"Test B: crcErrCount=1 (got $countB)")
    check(sdramBytes.size == payloadB.size, s"Test B: ${payloadB.size} payload bytes still forwarded (detect-and-flag) (got ${sdramBytes.size})")

    // Test C: a second correct-CRC write does not increment the count beyond Test B's 1.
    sdramBytes.clear()
    writeFrame(0x3000, payloadA, frameCrc(0x3000, payloadA))
    sleep(50000)
    val s11c = readStatus(11)
    check(((s11c >> 16) & 1) == 1, "Test C: sticky stays 1 (set-and-hold)")
    check((s11c & 0xFFFF) == 1, f"Test C: count stays 1 after a good write (got ${s11c & 0xFFFF})")

    println(if (failures == 0) "=== QspiCrc8Sim: ALL PASS — CRC8 detect-and-flag on sel=11 verified ==="
            else s"=== QspiCrc8Sim: $failures FAIL ===")
    assert(failures == 0, s"QspiCrc8Sim: $failures checks failed")
  }
}
