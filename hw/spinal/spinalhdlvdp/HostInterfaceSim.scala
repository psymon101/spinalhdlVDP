package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** R5 Stage 1 sim for `HostInterface`.
  *
  * Runs host and pixel on the same clock for simplicity (the StreamFifoCC
  * still exercises the CDC path). Coverage:
  *   1. Burst writes + auto-increment: 8 writes to sequential addresses
  *   2. Custom VDP_INC (increment = 4)
  *   3. Safe-boundary buffering: entries appear only at hCounter==0 or vblank
  */
object HostInterfaceSim extends App {
  Config.sim.compile {
    val hostCd = ClockDomain.external("host", frequency = FixedFrequency(25200000 Hz))
    HostInterface(hostCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.hostCd.forkStimulus(period = 10)

    def step(n: Int = 1): Unit = for (_ <- 0 until n) dut.clockDomain.waitSampling()

    // Sampler for regWrite pulses at the pixel domain.
    val captured = mutable.ArrayBuffer[(Int, Int)]()
    dut.clockDomain.onSamplings {
      if (dut.io.regWr.toBoolean) {
        captured += ((dut.io.regAddr.toInt, dut.io.regData.toInt))
      }
    }

    // Defaults
    dut.io.hostAddr #= 0
    dut.io.hostData #= 0
    dut.io.hostWr   #= false
    dut.io.hostRd   #= false
    dut.io.hCounter #= 100     // mid-line, so drainOpen is false
    dut.io.vCounter #= 50      // visible
    dut.io.vActive  #= 480
    step(10)

    def hostWrite(addr: Int, data: Int): Unit = {
      dut.io.hostAddr #= addr
      dut.io.hostData #= data
      dut.io.hostWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.hostWr   #= false
      dut.clockDomain.waitSampling()
    }

    def openBoundary(): Unit = {
      // Sit at hCounter=0 for enough cycles to drain everything in the FIFO.
      dut.io.hCounter #= 0
      for (_ <- 0 until 50) dut.clockDomain.waitSampling()
      dut.io.hCounter #= 100
      dut.clockDomain.waitSampling()
    }

    // -------- Case 1: burst 8 writes, default increment = 1 --------
    captured.clear()
    hostWrite(0, 0x0100)        // VDP_ADDR = 0x0100
    for (i <- 0 until 8) {
      hostWrite(1, 0xA000 + i)  // VDP_DATA = 0xA000+i → enqueue, auto-inc
    }
    // While still mid-line, nothing should have drained yet.
    val midLineCount = captured.size
    assert(midLineCount == 0, s"case1: expected 0 drained mid-line, got $midLineCount")

    openBoundary()
    val expected1 = (0 until 8).map(i => (0x0100 + i, 0xA000 + i))
    assert(captured.toList == expected1,
      s"case1: captured=${captured.toList} expected=$expected1")
    println("[sim] case1 burst 8 + auto-inc=1 — OK")

    // -------- Case 2: custom VDP_INC = 4 --------
    captured.clear()
    hostWrite(0, 0x0200)
    hostWrite(2, 4)
    for (i <- 0 until 4) {
      hostWrite(1, 0xB000 + i)
    }
    openBoundary()
    val expected2 = (0 until 4).map(i => (0x0200 + i * 4, 0xB000 + i))
    assert(captured.toList == expected2,
      s"case2: captured=${captured.toList} expected=$expected2")
    println("[sim] case2 custom VDP_INC=4 — OK")

    // -------- Case 3: vblank releases buffered entries --------
    captured.clear()
    hostWrite(0, 0x0300)
    hostWrite(2, 1)
    for (i <- 0 until 3) {
      hostWrite(1, 0xC000 + i)
    }
    // Not at h=0 and not in vblank → nothing drains
    dut.io.hCounter #= 200
    dut.io.vCounter #= 100
    step(20)
    assert(captured.isEmpty, s"case3: drained before boundary (${captured.size})")
    // Enter vblank
    dut.io.vCounter #= 490
    step(50)
    val expected3 = (0 until 3).map(i => (0x0300 + i, 0xC000 + i))
    assert(captured.toList == expected3,
      s"case3: vblank drain=${captured.toList} expected=$expected3")
    println("[sim] case3 vblank release — OK")

    println("[sim] HostInterfaceSim: PASS")
  }
}
