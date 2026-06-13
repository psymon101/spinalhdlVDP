package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** BITMAP-PLUMB-129 (#12169/#12205) — proves BitmapRowFetch consumes the new
  * host-programmable bitmap/attr base, stride, and height inputs.
  *
  * The direct-color SDRAM fetch address is:
  *     addr = base + (lineReg * stride) + byteIdx
  * where lineReg = fetchLine >> 1 (each source row shown on two HDMI lines).
  *
  * Two configs are checked against captured `io.sdramAddr` (sampled on each
  * rising edge of `io.sdramRd`):
  *   - DEFAULTS (base 0x3000/0x4000, stride 512): must reproduce the former
  *     hardcoded `<<9` behavior byte-for-byte at a non-zero line.
  *   - REPROGRAMMED (base 0x100000/0x200000, stride 1024): the captured
  *     addresses must follow the new base/stride, proving the registers are
  *     live and not the old constants.
  *
  * skipSdramInit=true so bootDone is immediate (no procedural init phase).
  */
object BitmapBaseRegSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(84000000 Hz))
    BitmapRowFetch(sdramCd, skipSdramInit = true)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.sdramDout      #= 0
    dut.io.sdramDataReady #= false
    dut.io.sdramBusy      #= false
    dut.io.fetchGrant     #= false
    dut.io.fetchLine      #= 0
    dut.io.col            #= 0
    dut.io.enable         #= false
    dut.io.directColor    #= false
    dut.io.tileBootDone   #= false
    dut.io.sdramDout32    #= 0
    dut.io.bitmapBase     #= 0x3000
    dut.io.attrBase       #= 0x4000
    dut.io.bitmapStride   #= 512
    dut.io.attrStride     #= 512
    dut.io.bitmapHeight   #= 240

    // Reactive SDRAM read model: 5-cycle latency, then BURST out `sdramBurstLen`
    // consecutive 32-bit words (one data_ready pulse/cycle) so the burst FSM keeps
    // advancing through the row. dout32 word k = bytes (addr+k*4+0..3)&0xFF.
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong
          val n    = math.max(1, dut.io.sdramBurstLen.toInt)
          dut.sdramCd.waitSampling(5)
          for (k <- 0 until n) {
            val wa = addr + k * 4
            dut.io.sdramDout #= (wa & 0xFF).toInt
            dut.io.sdramDout32 #= (wa & 0xFF) | (((wa + 1) & 0xFF) << 8) |
                                  (((wa + 2) & 0xFF) << 16) | (((wa + 3) & 0xFF) << 24)
            dut.io.sdramDataReady #= true
            dut.sdramCd.waitSampling()
          }
          dut.io.sdramDataReady #= false
        } else {
          dut.sdramCd.waitSampling()
        }
      }
    }

    // Capture the address on every rising edge of sdramRd (one entry per read).
    val reads = ArrayBuffer[Long]()
    fork {
      var prev = false
      while (true) {
        val rd = dut.io.sdramRd.toBoolean
        if (rd && !prev) reads += dut.io.sdramAddr.toLong
        prev = rd
        dut.sdramCd.waitSampling()
      }
    }

    dut.sdramCd.waitSampling(10)
    dut.clockDomain.waitSampling(10)

    dut.io.enable       #= true
    dut.io.directColor  #= true
    dut.io.tileBootDone #= true

    var timeout = 2000
    while (!dut.io.bootDone.toBoolean && timeout > 0) { dut.sdramCd.waitSampling(); timeout -= 1 }
    assert(timeout > 0, "Timed out waiting for bootDone (skipSdramInit)")
    println("[sim] bootDone reached")

    /** Drive a fetch for `fetchLine`, return (firstBitmapAddr, firstAttrAddr). */
    def runFetch(fetchLine: Int, attrBaseVal: Long): (Long, Long) = {
      reads.clear()
      dut.io.fetchLine #= fetchLine
      dut.io.fetchGrant #= true
      dut.clockDomain.waitSampling(4)
      dut.io.fetchGrant #= false
      // Let the FSM walk bitmap (320) + attr (320) reads.
      dut.sdramCd.waitSampling(60000)
      val captured = reads.toList
      assert(captured.nonEmpty, s"no reads captured for line $fetchLine")
      val firstBitmap = captured.head
      val firstAttr   = captured.find(_ >= attrBaseVal).getOrElse(
        sys.error(s"no attr-region read (>= 0x${attrBaseVal.toHexString}) captured for line $fetchLine"))
      (firstBitmap, firstAttr)
    }

    // --- Config 1: DEFAULTS, non-zero line — must match legacy <<9 math ---
    // fetchLine=4 -> lineReg=2 -> rowOffset = 2*512 = 1024 (== 2<<9, legacy).
    val (bm1, at1) = runFetch(fetchLine = 4, attrBaseVal = 0x4000)
    val expBm1 = 0x3000L + 2L * 512L
    val expAt1 = 0x4000L + 2L * 512L
    assert(bm1 == expBm1, f"DEFAULT bitmap addr got 0x$bm1%X exp 0x$expBm1%X")
    assert(at1 == expAt1, f"DEFAULT attr addr got 0x$at1%X exp 0x$expAt1%X")
    println(f"[sim] defaults line4: bitmap 0x$bm1%X / attr 0x$at1%X — matches legacy <<9 — OK")

    // --- Config 2: REPROGRAMMED base+stride — addresses must follow regs ---
    dut.io.bitmapBase   #= 0x100000
    dut.io.attrBase     #= 0x200000
    dut.io.bitmapStride #= 1024
    dut.io.attrStride   #= 1024
    // Allow the quasi-static BufferCC to settle into sdramCd.
    dut.sdramCd.waitSampling(20)
    // fetchLine=6 -> lineReg=3 -> rowOffset = 3*1024 = 3072.
    val (bm2, at2) = runFetch(fetchLine = 6, attrBaseVal = 0x200000)
    val expBm2 = 0x100000L + 3L * 1024L
    val expAt2 = 0x200000L + 3L * 1024L
    assert(bm2 == expBm2, f"REPROG bitmap addr got 0x$bm2%X exp 0x$expBm2%X")
    assert(at2 == expAt2, f"REPROG attr addr got 0x$at2%X exp 0x$expAt2%X")
    println(f"[sim] reprog line6: bitmap 0x$bm2%X / attr 0x$at2%X — follows base+stride regs — OK")

    println("[sim] BitmapBaseRegSim: PASS")
  }
}
