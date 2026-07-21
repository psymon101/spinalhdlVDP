package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** HAM6-2bpp #14246 — proof that READ_STATUS sel=8 surfaces `debug_sdram_data` through the
  * word-drain `QspiTransportCore` SCLK read responder.
  *
  * Re-enables the SDRAM-content readback the host needs to split QSPI-upload corruption from
  * downstream defects on the 2bpp banding. The read PATH (dummy turnaround + falling-edge
  * nibble launch) is already hardware-proven by the working sel=0 magic and sel=10 health
  * reads; this sim adds the new sel=8 arm and proves it returns the driven word.
  *
  * SELF-VALIDATING: reads sel=0 (magic 0x51560002, known-good) with the SAME helper as sel=8.
  * If magic reconstructs correctly, the read helper is proven, so a correct sel=8 (0xDEADBEEF)
  * is airtight.
  *
  * Run: sbt "runMain spinalhdlvdp.Qspi2bppReadbackSim"
  */
object Qspi2bppReadbackSim extends App {
  Config.sim.compile(QspiTransportCore(fifoDepth = 512, dummyCycles = 2)).doSim { dut =>
    val sysPeriod = 37
    val sclkPeriod = 40

    dut.io.clk #= false
    dut.io.sclk #= false; dut.io.csn #= true; dut.io.ioIn #= 0
    dut.io.debug_sdram_data #= 0

    fork {
      while (true) {
        dut.io.clk #= true;  sleep(sysPeriod / 2)
        dut.io.clk #= false; sleep(sysPeriod - sysPeriod / 2)
      }
    }

    // --- SCLK bit-bang (mode 0): drive on the half-cycle before the rising edge. ---
    def clkRise(): Unit = { dut.io.sclk #= true; sleep(sclkPeriod / 2) }
    def clkFall(): Unit = { dut.io.sclk #= false; sleep(sclkPeriod - sclkPeriod / 2) }
    def sendSingle(v: BigInt, bits: Int): Unit =
      for (i <- (bits - 1) to 0 by -1) { dut.io.ioIn #= (((v >> i) & 1).toInt); sleep(sclkPeriod / 2); clkRise(); clkFall() }
    def startTxn(): Unit = { dut.io.csn #= false; sleep(2 * sclkPeriod) }
    def endTxn():   Unit = { dut.io.sclk #= false; dut.io.csn #= true; sleep(8 * sclkPeriod) }

    /** READ_STATUS(sel): CMD=0x04 (single, MSB-first), 24-bit ADDR whose low byte = sel,
      * dummyCycles turnaround, then 8 nibbles the FPGA launches on FALLING edges (byte0
      * first, high nibble first). Sample ioOut just after each falling edge (post-launch),
      * reconstruct the 32-bit word. */
    def readStatus(sel: Int): BigInt = {
      startTxn()
      sendSingle(0x04, 8)          // CMD
      sendSingle(sel & 0xFF, 24)   // ADDR (low byte = sel)
      // dummy turnaround: the FPGA launches the first RDATA nibble one edge into the
      // dummy window (empirically, sel=0 magic self-check), so pre-clock ONE edge then
      // sample 8 nibbles.
      for (_ <- 0 until 1) { clkRise(); clkFall() }
      val nibs = ArrayBuffer[Int]()
      for (_ <- 0 until 8) {
        clkRise()
        clkFall()
        // FPGA launched this nibble on the falling edge; sample now (stable for the
        // master's next rising edge). Only meaningful while ioOe is asserted.
        if (dut.io.ioOe.toBoolean) nibs += (dut.io.ioOut.toInt & 0xF) else nibs += -1
      }
      endTxn()
      // Reconstruct: nibble k -> byte(k/2), high nibble first. word byte order = LSB..MSB
      // (magic sel=0 is B"32'h51560002", byte0=0x02 emitted first).
      var w = BigInt(0)
      for (byteIdx <- 0 until 4) {
        val hi = nibs(byteIdx * 2); val lo = nibs(byteIdx * 2 + 1)
        val b = ((hi & 0xF) << 4) | (lo & 0xF)
        w = w | (BigInt(b) << (byteIdx * 8))
      }
      println(f"  sel=$sel%-2d nibbles=${nibs.map(n => if (n < 0) "z" else n.toHexString).mkString(",")}  word=0x$w%08X")
      w
    }

    sleep(20 * sclkPeriod)
    println("=== Qspi2bppReadbackSim (sel=8 SDRAM readback via word-drain responder) ===")

    var failures = 0
    def check(c: Boolean, m: String): Unit = { if (!c) { failures += 1; println(s"  [FAIL] $m") } else println(s"  [PASS] $m") }

    // Control: sel=0 magic with the SAME helper (validates the read sampling itself).
    val magic = readStatus(0)
    check(magic == BigInt("51560002", 16), f"sel=0 magic reads 0x51560002 (got 0x$magic%08X) — read helper validated")

    // Under test: drive a known SDRAM debug word, read it back via sel=8.
    val exp1 = BigInt("DEADBEEF", 16)
    dut.io.debug_sdram_data #= exp1
    sleep(20 * sysPeriod)   // let the 2FF BufferCC settle into the SCLK domain
    val got1 = readStatus(8)
    check(got1 == exp1, f"sel=8 returns driven debug_sdram_data 0x$exp1%08X (got 0x$got1%08X)")

    // Second value — proves it tracks the input, not a constant.
    val exp2 = BigInt("0BADF00D", 16)
    dut.io.debug_sdram_data #= exp2
    sleep(20 * sysPeriod)
    val got2 = readStatus(8)
    check(got2 == exp2, f"sel=8 tracks a second word 0x$exp2%08X (got 0x$got2%08X)")

    // Magic still correct after (no state corruption).
    val magic2 = readStatus(0)
    check(magic2 == BigInt("51560002", 16), f"sel=0 magic still 0x51560002 after sel=8 reads (got 0x$magic2%08X)")

    println(if (failures == 0) "=== Qspi2bppReadbackSim: ALL PASS — sel=8 SDRAM readback surfaced ==="
            else s"=== Qspi2bppReadbackSim: $failures FAIL ===")
    assert(failures == 0, s"Qspi2bppReadbackSim: $failures checks failed")
  }
}
