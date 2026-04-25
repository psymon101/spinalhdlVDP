package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Unit sim for `BitplaneRowFetch`.
  *
  * Drives a synthetic SDRAM model that mimics the nand2mario controller's
  * 5-cycles-per-op, byte-(here dword-)addressable shape:
  *   - on `rd=1` while not busy, latch the address, set busy=1
  *   - 4 cycles later, present `dout32 = sdramMem[addr / 4]` and pulse
  *     `data_ready` for one cycle, then drop busy
  *
  * Memory contents are a deterministic checker pattern: 32-bit word at
  * dword index `i` holds the value `0x10000000 + i`. After fetch, the
  * sim verifies that:
  *   1. Each plane's row is assembled in the correct MSB-first slot order
  *      (slot 0 = words from base, slot N-1 = words at base + 4·(N-1)).
  *   2. Plane 0's row begins at planeBase(0), plane 4's row begins at
  *      planeBase(4), etc. (no cross-plane pollution).
  *   3. `rowReady` pulses exactly once and `busy` returns to False.
  */
object BitplaneRowFetchSim extends App {

  val planeCount  = 5
  val planePixels = 320     // 10 dout32 reads per plane
  val readsPerPlane = planePixels / 32

  Config.sim.compile(BitplaneRowFetch(
    planeCount  = planeCount,
    planePixels = planePixels
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Plane bases at 0x10000, 0x11000, 0x12000, 0x13000, 0x14000.
    val planeBases = (0 until planeCount).map(p => 0x10000 + p * 0x1000)
    for (p <- 0 until planeCount) dut.io.planeBaseAddr(p) #= planeBases(p)

    // ---- Synthetic SDRAM model ----
    // dout32 at byte address A returns value 0x10000000 + (A/4) - i.e. the
    // dword index, biased so every value is non-zero and easy to read.
    def memAt(byteAddr: BigInt): BigInt = BigInt("10000000", 16) + (byteAddr >> 2)

    dut.io.sdramBusy      #= false
    dut.io.sdramDataReady #= false
    dut.io.sdramDout32    #= 0
    dut.io.start          #= false

    // Drive the SDRAM model from a parallel forked thread.
    var pendingAddr: BigInt = 0
    var pendingTicks = 0
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (pendingTicks > 0) {
          pendingTicks -= 1
          if (pendingTicks == 0) {
            dut.io.sdramDout32    #= memAt(pendingAddr)
            dut.io.sdramDataReady #= true
            dut.io.sdramBusy      #= false
          } else {
            dut.io.sdramDataReady #= false
          }
        } else if (dut.io.sdramRd.toBoolean && !dut.io.sdramBusy.toBoolean) {
          pendingAddr  = BigInt(dut.io.sdramAddr.toLong)
          pendingTicks = 4   // data_ready 4 cycles after rd
          dut.io.sdramBusy #= true
        } else {
          dut.io.sdramDataReady #= false
        }
      }
    }

    // Reset settles.
    dut.clockDomain.waitSampling(2)

    // Pulse start.
    dut.io.start #= true
    dut.clockDomain.waitSampling()
    dut.io.start #= false

    // Wait for rowReady (cap at 5000 cycles for safety; expected ~5*5 cycles
    // per read × 50 reads = 1250 cycles).
    var elapsed = 0
    while (!dut.io.rowReady.toBoolean && elapsed < 5000) {
      dut.clockDomain.waitSampling()
      elapsed += 1
    }
    assert(dut.io.rowReady.toBoolean, s"rowReady never asserted within 5000 cycles")
    println(s"[sim] rowReady asserted after $elapsed cycles (expected ~250-1250)")

    dut.clockDomain.waitSampling()
    assert(!dut.io.rowReady.toBoolean, "rowReady must drop after 1 cycle")
    assert(!dut.io.busy.toBoolean,     "busy must return to False after Done")

    // Verify each plane row's assembled bits.
    for (p <- 0 until planeCount) {
      val rowBig = dut.io.planeRows(p).toBigInt
      for (r <- 0 until readsPerPlane) {
        val expectedWord = memAt(BigInt(planeBases(p)) + r * 4)
        val msb = planePixels - 1 - r * 32
        val lsb = planePixels - (r + 1) * 32
        val mask = (BigInt(1) << 32) - 1
        val gotWord = (rowBig >> lsb) & mask
        assert(gotWord == expectedWord,
          f"plane $p slot $r (bits $msb..$lsb): expected 0x$expectedWord%X got 0x$gotWord%X")
      }
    }
    println(s"[sim] all $planeCount × $readsPerPlane = ${planeCount * readsPerPlane} dout32 slots correct")

    // Verify the FSM rests in Idle (no spurious re-fire).
    for (_ <- 0 until 50) {
      dut.clockDomain.waitSampling()
      assert(!dut.io.rowReady.toBoolean, "rowReady must not re-fire while idle")
      assert(!dut.io.busy.toBoolean,     "busy must stay False while idle")
    }
    println("[sim] FSM idle and stable — OK")

    println("[sim] BitplaneRowFetchSim: PASS")
  }
}
