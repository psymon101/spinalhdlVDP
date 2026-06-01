package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

/** #11123 FIX 1 — two-clock proof of the repaired upload CDC crossing
  * (BronzeGate #11120 Finding 1).
  *
  * Harness: QspiSdramBridge (push = pixel domain) -> StreamFifoCC -> pop
  * (SDRAM domain), exactly as wired in TopTang20kHdmi. A byte sequence is
  * streamed across MISMATCHED pixel/SDRAM clocks; the test proves every byte
  * lands at addr+i with correct data and that address+data stay paired across
  * the boundary. The OLD design (toggle + quasi-static addr/data buses + raw
  * cross-domain busy) could mis-pair addr/data, collapse two toggle flips, or
  * drop writes under unfavorable phase — the cause of partial sentinel bytes
  * and writes landing at the wrong address. This is the regression test for
  * that class of bug.
  */
case class UploadBridgeCcHarness(popCd: ClockDomain) extends Component {
  val io = new Bundle {
    val headerValid = in  Bool()
    val addrInit    = in  UInt(23 bits)
    val lenBytes    = in  UInt(17 bits)
    val byteIn      = in  Bits(8 bits)
    val byteValid   = in  Bool()
    val allowUpload = in  Bool()
    val popReady    = in  Bool()
    val popValid    = out Bool()
    val popPayload  = out Bits(31 bits)
  }
  val bridge = QspiSdramBridge()
  bridge.io.headerValid := io.headerValid
  bridge.io.addrInit    := io.addrInit
  bridge.io.lenBytes    := io.lenBytes
  bridge.io.byteIn      := io.byteIn
  bridge.io.byteValid   := io.byteValid
  bridge.io.allowUpload := io.allowUpload

  val fifo = StreamFifoCC(Bits(31 bits), 16, ClockDomain.current, popCd)
  fifo.io.push << bridge.io.wrCmd
  val popArea = new ClockingArea(popCd) {
    fifo.io.pop.ready := io.popReady
    io.popValid   := fifo.io.pop.valid
    io.popPayload := fifo.io.pop.payload
  }
}

object SdramUploadCdcSim extends App {
  Config.sim.compile {
    val popCd = ClockDomain.external("pop", withReset = true, frequency = FixedFrequency(40500000 Hz))
    UploadBridgeCcHarness(popCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)   // push (pixel-ish) — FAST
    dut.popCd.forkStimulus(period = 17)          // pop (sdram-ish) — SLOWER, mismatched

    val mem = mutable.HashMap[Int, Int]()
    dut.io.popReady #= true
    // SDRAM-side sink: capture each popped write (addr,din) — addr+din arrive
    // together in one payload, so a mis-pair is impossible by construction.
    dut.popCd.onSamplings {
      if (dut.io.popValid.toBoolean && dut.io.popReady.toBoolean) {
        val p    = dut.io.popPayload.toBigInt
        val addr = ((p >> 8) & 0x7FFFFF).toInt
        val din  = (p & 0xFF).toInt
        mem(addr) = din
      }
    }

    dut.io.headerValid #= false
    dut.io.byteValid   #= false
    dut.io.allowUpload #= true
    dut.clockDomain.waitSampling(5)

    val base = 0x1000
    val n    = 64
    dut.io.addrInit    #= base
    dut.io.lenBytes    #= n
    dut.io.headerValid #= true
    dut.clockDomain.waitSampling()
    dut.io.headerValid #= false

    // Stream n bytes (value = low byte of addr). Paced so the bridge's 16-byte
    // byteFifo never overflows (byteValid backpressure is host-paced by design);
    // the mismatched clocks stress the CROSSING, which is what we are proving.
    for (i <- 0 until n) {
      dut.io.byteIn    #= ((base + i) & 0xFF)
      dut.io.byteValid #= true
      dut.clockDomain.waitSampling()
      dut.io.byteValid #= false
      dut.clockDomain.waitSampling(4)
    }
    dut.popCd.waitSampling(200)   // drain

    var bad = 0
    for (i <- 0 until n) {
      val exp = (base + i) & 0xFF
      val got = mem.getOrElse(base + i, -1)
      if (got != exp) { bad += 1; if (bad <= 8) println(f"  LOSS/mismatch @0x${base + i}%X got $got exp 0x$exp%02X") }
    }
    assert(mem.size == n, s"FAIL: expected $n distinct addresses written, got ${mem.size}")
    assert(bad == 0, s"FAIL: $bad byte mismatches across the CDC crossing")
    println(s"[sim] SdramUploadCdcSim: PASS — $n bytes crossed pixel->SDRAM losslessly across mismatched clocks; addr/data atomic, none dropped or mis-addressed")
    simSuccess()
  }
}
