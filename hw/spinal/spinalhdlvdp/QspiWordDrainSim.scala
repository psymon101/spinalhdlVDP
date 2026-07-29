package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** QspiWordDrainSim — QSPI-OPTION-A-183 adaptation (byte egress).
  *
  * The barebones #13888 word-drain fed the decoder's WORD path (one 16-bit word per
  * clk_sys cycle) into a fictitious infinite sink, so an unbounded 80 MHz burst never
  * overflowed. Option A wires the core into the in-tree byte-granular, byte-addressed
  * QspiSdramBridge, so the core's pop stage now unpacks each popped word token into two
  * byte pulses on the decoder BYTE path (holding the FIFO token across both cycles =
  * real backpressure). The FIFO still carries WORD tokens (half-rate push preserved),
  * but the drain is now ~2 clk_sys cycles/word. Upload throughput is therefore bounded
  * by the SDRAM byte-write sink (~4 MHz), NOT the 80 MHz link (premise correction
  * #13976, accepted #13984) — an unbounded 80 MHz burst is EXPECTED to overflow and is
  * gated host-side, so this sim proves correctness at the real operating point instead.
  *
  * Test A — REG_WRITE byte-exact via the byte path, push <= drain: a 1024-word REG_WRITE
  *          at a push rate below the byte-path drain retires with zero overflow and every
  *          decoded word exact (proves the word-token -> byte-path -> reg assembly).
  * Test C — SDRAM_WRITE byte egress (the HAM upload path): a CMD=0x02 transfer surfaces
  *          the correct header (addrInit/lenBytes/headerValid) and the exact lo,hi byte
  *          sequence on sdramByteOut/sdramByteValid.
  * Test B — MALFORMED ODD-BYTE GUARD: a dangling half-word is DISCARDED + flagged and
  *          must not corrupt a following clean write (push-side logic, unchanged).
  * Test D — GRACEFUL OVERFLOW: an unbounded 80 MHz burst past the FIFO depth trips the
  *          `overflow` sticky (flagged, never silent corruption).
  */
object QspiWordDrainSim extends App {
  Config.sim.compile(QspiTransportCore(fifoDepth = 512, dummyCycles = 2)).doSim { dut =>
    val sysPeriod   = 37     // ns -> ~27 MHz clk_sys
    val slowSclk    = 37     // ns SCLK edge -> word push ~6.75 Mword/s < ~13.5 Mword/s drain
    val fastSclk    = 6      // ns SCLK edge -> word push ~27.8 Mword/s, well above the
                             // ~13.5 Mword/s byte-path drain, so a burst past the FIFO
                             // depth decisively over-runs it (Test D graceful-overflow).

    dut.io.clk #= false
    dut.io.sclk #= false; dut.io.csn #= true; dut.io.ioIn #= 0

    fork {
      while (true) {
        dut.io.clk #= true;  sleep(sysPeriod / 2)
        dut.io.clk #= false; sleep(sysPeriod - sysPeriod / 2)
      }
    }

    var failures = 0
    def check(c: Boolean, m: String): Unit = if (!c) { failures += 1; println(s"  [FAIL] $m") }

    // SCLK period is per-test (drain-bound vs overflow stress).
    var sclkPeriod = slowSclk
    def clk(): Unit = { dut.io.sclk #= true; sleep(sclkPeriod / 2); dut.io.sclk #= false; sleep(sclkPeriod - sclkPeriod / 2) }
    def sendSingle(v: BigInt, bits: Int): Unit = for (i <- (bits - 1) to 0 by -1) { dut.io.ioIn #= (((v >> i) & 1).toInt); sleep(sclkPeriod / 2); clk() }
    def sendQuad(bytes: Seq[Int]): Unit = for (b <- bytes) { dut.io.ioIn #= ((b >> 4) & 0xF); sleep(sclkPeriod / 2); clk(); dut.io.ioIn #= (b & 0xF); sleep(sclkPeriod / 2); clk() }
    def startTxn(): Unit = { dut.io.csn #= false; sleep(2 * sclkPeriod) }
    def endTxn():   Unit = { dut.io.sclk #= false; dut.io.csn #= true; sleep(6 * sclkPeriod) }
    def hdr(cmd: Int, addr: BigInt): Unit = { sendSingle(cmd, 8); sendSingle(addr, 24) }
    def lenBytes(words: Int): Seq[Int] = Seq(words & 0xFF, (words >> 8) & 0xFF)

    // clk_sys-domain monitors: sample regBus + sdram byte egress on EVERY clk_sys rising
    // edge (writes/bytes can land on back-to-back cycles, so enable-edge-detect undercounts).
    val regWrites  = ArrayBuffer[(Int, Int)]()
    val sdramBytes = ArrayBuffer[Int]()
    var sdramHdrSeen = false
    var sdramAddrInit = -1
    var sdramLenBytes = -1
    var pClk = false
    fork {
      while (true) {
        sleep(1)
        val c = dut.io.clk.toBoolean
        if (c && !pClk) {
          if (dut.io.regBus.enable.toBoolean)
            regWrites += ((dut.io.regBus.addr.toInt, dut.io.regBus.data.toInt))
          if (dut.io.sdramByteValid.toBoolean)
            sdramBytes += dut.io.sdramByteOut.toInt
          if (dut.io.sdramHeaderValid.toBoolean) {
            sdramHdrSeen = true
            sdramAddrInit = dut.io.sdramAddrInit.toInt
            sdramLenBytes = dut.io.sdramLenBytes.toInt
          }
        }
        pClk = c
      }
    }

    sleep(10 * sclkPeriod)
    println("=== QspiWordDrainSim (Option A byte-egress adaptation) ===")

    // ---- Test A: REG_WRITE byte-exact via byte path, push <= drain, no overflow ----
    sclkPeriod = slowSclk
    val N = 1024
    regWrites.clear()
    startTxn()
    hdr(0x01, 0)
    val dataBytes = (0 until N).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }   // word i encodes i
    sendQuad(lenBytes(N) ++ dataBytes)
    endTxn()
    sleep(300000)   // drain (2 clk_sys cycles/word) + let overflow cross to clk_sys
    val ovA = dut.io.overflow.toBoolean
    val mfA = dut.io.malformed.toBoolean
    val writesA = regWrites.toSeq
    val firstBadA = writesA.zipWithIndex.collectFirst {
      case ((addr, data), idx) if addr != idx || data != (idx & 0xFFFF) => idx
    }.getOrElse(-1)
    println(f"  Test A N=$N: writes=${writesA.size}%d overflow=$ovA malformed=$mfA firstBad=$firstBadA")
    check(!ovA,              s"Test A: overflow fired at push<=drain rate ($N-word burst)")
    check(writesA.size == N, s"Test A: got ${writesA.size} writes, expected $N")
    check(firstBadA == -1,   s"Test A: decoded words diverged at index $firstBadA (expected exact)")
    check(!mfA,              "Test A: malformed sticky set on a well-formed even burst")
    if (failures == 0) println(s"  Test A PASS — $N words byte-exact via byte path, zero overflow at push<=drain")

    // ---- Test C: SDRAM_WRITE (CMD=0x02) byte egress — the HAM upload path ----
    val failBeforeC = failures
    sclkPeriod = slowSclk
    val Nc = 64
    val baseAddr = 0x1000
    sdramBytes.clear(); sdramHdrSeen = false; sdramAddrInit = -1; sdramLenBytes = -1
    startTxn()
    hdr(0x02, baseAddr)                                     // SDRAM_WRITE header
    val scDataBytes = (0 until Nc).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }
    sendQuad(lenBytes(Nc) ++ scDataBytes)
    endTxn()
    sleep(120000)
    val expBytes = (0 until Nc).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }
    val gotBytes = sdramBytes.toSeq
    val firstBadC = gotBytes.zip(expBytes).indexWhere { case (g, e) => g != e }
    println(f"  Test C SDRAM_WRITE: hdrSeen=$sdramHdrSeen addrInit=0x$sdramAddrInit%X lenBytes=$sdramLenBytes bytes=${gotBytes.size}/${expBytes.size} firstBad=$firstBadC")
    check(sdramHdrSeen,                     "Test C: sdramHeaderValid never pulsed")
    check(sdramAddrInit == baseAddr,        s"Test C: addrInit=0x$sdramAddrInit%X expected 0x$baseAddr%X")
    check(sdramLenBytes == 2 * Nc,          s"Test C: lenBytes=$sdramLenBytes expected ${2 * Nc}")
    check(gotBytes.size == 2 * Nc,          s"Test C: got ${gotBytes.size} egress bytes, expected ${2 * Nc}")
    check(firstBadC == -1,                  s"Test C: SDRAM egress byte diverged at index $firstBadC")
    if (failures == failBeforeC) println(s"  Test C PASS — SDRAM_WRITE header + ${2 * Nc} lo/hi bytes byte-exact on sdramByteOut")

    // ---- Test B: odd-byte payload discarded + flagged, follow-up write clean ----
    val failBeforeB = failures
    sclkPeriod = slowSclk
    regWrites.clear()
    startTxn()
    hdr(0x01, 0x0100)
    sendQuad(lenBytes(1) ++ Seq(0xBE))                     // claim 1 word, send ONE byte
    endTxn()
    regWrites.clear()
    startTxn()
    hdr(0x01, 0x0200)
    sendQuad(lenBytes(1) ++ Seq(0x34, 0x12))               // word 0x1234 at 0x0200
    endTxn()
    sleep(20000)
    val ovB = dut.io.overflow.toBoolean
    val mfB = dut.io.malformed.toBoolean
    val writesB = regWrites.toSeq
    println(f"  Test B odd-byte: writes=${writesB.map{case(a,d)=>f"0x$a%04X<-0x$d%04X"}.mkString(",")} overflow=$ovB malformed=$mfB")
    check(mfB,               "Test B: malformed sticky did NOT set on an odd-byte payload")
    check(writesB == Seq((0x0200, 0x1234)),
                             s"Test B: follow-up write corrupted by leftover byte — got $writesB, expected [0x0200<-0x1234]")
    check(!ovB,              "Test B: overflow fired on a tiny malformed burst")
    if (failures == failBeforeB) println("  Test B PASS — dangling half-word discarded + flagged, follow-up write clean")

    // ---- Test D: graceful overflow — unbounded 80 MHz burst past FIFO depth trips sticky ----
    val failBeforeD = failures
    sclkPeriod = fastSclk
    regWrites.clear()
    val Nd = 2048            // > fifoDepth(512); at fast push the 2-cyc/word drain cannot keep up
    startTxn()
    hdr(0x01, 0)
    val dDataBytes = (0 until Nd).flatMap { i => Seq(i & 0xFF, (i >> 8) & 0xFF) }
    sendQuad(lenBytes(Nd) ++ dDataBytes)
    endTxn()
    sleep(400000)
    val ovD = dut.io.overflow.toBoolean
    println(f"  Test D N=$Nd @80MHz: overflow=$ovD (expected TRUE — flagged, not silent)")
    check(ovD, s"Test D: overflow did NOT fire on an unbounded $Nd-word 80 MHz burst (silent drop risk)")
    if (failures == failBeforeD) println("  Test D PASS — over-rate burst trips overflow sticky (graceful, flagged)")

    println(s"=== QspiWordDrainSim: ${if (failures == 0) "ALL PASS" else s"$failures FAIL"} ===")
    if (failures != 0) simFailure(s"$failures failed")
  }
}
