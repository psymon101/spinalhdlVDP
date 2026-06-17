package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** I80-FRAME-ATOMIC-SWAP-145 (#12755) — proves the vblank-atomic bitmap/attr
  * base double-buffer added to VdpTop fixes the test07 tearing.
  *
  * Mechanism under test (regs 0x0358-0x035C):
  *   - Host stages four base words into dedicated staging regs:
  *       0x0358 BITMAP_BASE_PENDING_LO  0x0359 BITMAP_BASE_PENDING_HI
  *       0x035A ATTR_BASE_PENDING_LO    0x035B ATTR_BASE_PENDING_HI
  *   - Staging is BUFFERED: the live io.bitmapBase/io.attrBase do NOT move.
  *   - Host arms the swap (0x035C b0). At the first cycle of vblank
  *     (vCounter===vActive(480), hCounter===0) the RTL copies all four staged
  *     words to the live regs in ONE cycle, auto-clears the request (b0), and
  *     raises the sticky committed flag (b1, host write-1-to-clear acks it).
  *
  * Assertions:
  *   1. Staging four words leaves the live bases unchanged (double-buffered).
  *   2. After arming, BOTH planes flip together to the staged targets in the
  *      SAME cycle (atomic — never a torn old-LO/new-HI or old/new-plane mix).
  *   3. The flip lands exactly at the vblank edge (the triggering cycle had
  *      hCounter==0 && vCounter==vActive).
  *   4. swapRequest auto-clears and swapCommitted sets at the swap.
  *   5. The bases hold for a full additional frame — the swap happens exactly
  *      once, not every line / not mid-frame.
  *   6. swapCommitted is host-clearable via W1C @ 0x035C b1.
  *   7. A second staged swap repeats correctly (request gating works).
  *
  * VdpTop's video counters free-run on the clock alone (cf. PlanarClipSim),
  * so no DUT inputs beyond the register bus need driving.
  */
object VdpFrameAtomicSwapSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    SimTimeout(60000000L) // ~6M cycles; a frame is 800*525 = 420000 cycles

    val vActive = 480
    val frame   = 800 * 525

    dut.io.regBus.addr   #= 0
    dut.io.regBus.data   #= 0
    dut.io.regBus.enable #= false
    dut.clockDomain.waitSampling(5)

    def writeReg(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.clockDomain.waitSampling()
    }

    def bmBase: Long = dut.io.bitmapBase.toLong
    def atBase: Long = dut.io.attrBase.toLong

    val defBm = bmBase
    val defAt = atBase
    println(f"[sim] defaults: bitmapBase=0x$defBm%X attrBase=0x$defAt%X")
    assert(defBm == 0x3000L, f"default bitmapBase 0x$defBm%X exp 0x3000")
    assert(defAt == 0x4000L, f"default attrBase 0x$defAt%X exp 0x4000")

    /** Stage `bmTarget`/`atTarget`, arm, and verify atomic vblank-aligned,
      * once-only swap. */
    def doSwap(bmTarget: Long, atTarget: Long): Unit = {
      val bmLo  = (bmTarget & 0xFFFF).toInt
      val bmHi  = ((bmTarget >> 16) & 0x7F).toInt
      val atLo  = (atTarget & 0xFFFF).toInt
      val atHi  = ((atTarget >> 16) & 0x7F).toInt
      val preBm = bmBase
      val preAt = atBase

      // (1) Stage all four words — swapRequest is False, so no commit can fire
      // regardless of where the video counters are; the live bases must hold.
      writeReg(0x0358, bmLo)
      writeReg(0x0359, bmHi)
      writeReg(0x035A, atLo)
      writeReg(0x035B, atHi)
      assert(bmBase == preBm, f"bitmapBase moved during staging: 0x${bmBase}%X (double-buffer broken)")
      assert(atBase == preAt, f"attrBase moved during staging: 0x${atBase}%X (double-buffer broken)")
      assert(!dut.swapCommitted.toBoolean, "swapCommitted set before swap")

      // Arm.
      writeReg(0x035C, 0x1)
      assert(dut.swapRequest.toBoolean, "swapRequest not set after arm")
      // Readback (0x035C): b0=request set, b1=committed clear after arm.
      assert((dut.io.swapStatus.toLong & 0x1) != 0, "swapStatus b0 (request) not set after arm")
      assert((dut.io.swapStatus.toLong & 0x2) == 0, "swapStatus b1 (committed) unexpectedly set after arm")

      // (2,3) Spin until the live base changes. Track the PREVIOUS cycle's
      // (h,v): the regs load on the edge where hCounter==0 && vCounter==vActive,
      // observed one cycle later.
      var prevBm = bmBase
      var prevAt = atBase
      var prevH  = dut.hCounter.toLong.toInt
      var prevV  = dut.vCounter.toLong.toInt
      var found  = false
      var guard  = 0
      val cap    = 3 * frame
      while (!found && guard < cap) {
        dut.clockDomain.waitSampling()
        val nb = bmBase
        val na = atBase
        if (nb != prevBm || na != prevAt) {
          assert(nb == bmTarget, f"bitmapBase flipped to 0x$nb%X exp 0x$bmTarget%X")
          assert(na == atTarget, f"attrBase flipped to 0x$na%X exp 0x$atTarget%X — NOT atomic with bitmap plane!")
          assert(prevH == 0,       s"swap loaded at hCounter=$prevH (exp 0) — not at a line start")
          assert(prevV == vActive, s"swap loaded at vCounter=$prevV (exp $vActive) — not at the vblank edge")
          found = true
        }
        prevBm = nb; prevAt = na
        prevH = dut.hCounter.toLong.toInt
        prevV = dut.vCounter.toLong.toInt
        guard += 1
      }
      assert(found, s"swap never observed within $cap cycles")
      // (4)
      assert(!dut.swapRequest.toBoolean, "swapRequest not auto-cleared after swap")
      assert(dut.swapCommitted.toBoolean, "swapCommitted not set after swap")
      // Readback: b1=committed set, b0=request cleared after the vblank commit.
      assert((dut.io.swapStatus.toLong & 0x2) != 0, "swapStatus b1 (committed) not set after swap")
      assert((dut.io.swapStatus.toLong & 0x1) == 0, "swapStatus b0 (request) not cleared after swap")
      println(f"[sim] swap OK: bitmapBase->0x$bmTarget%X attrBase->0x$atTarget%X atomically at vblank edge")

      // (5) Hold a full frame: bases stable, no spurious re-arm/re-swap.
      var held = 0
      while (held < frame + 2000) {
        dut.clockDomain.waitSampling()
        assert(bmBase == bmTarget, f"bitmapBase drifted post-swap: 0x${bmBase}%X")
        assert(atBase == atTarget, f"attrBase drifted post-swap: 0x${atBase}%X")
        assert(!dut.swapRequest.toBoolean, "swapRequest spuriously re-armed within the frame")
        held += 1
      }
      println("[sim] held one full frame — swap occurred exactly once")
    }

    // Test 1.
    doSwap(0x100000L, 0x200000L)

    // (6) W1C the committed flag.
    writeReg(0x035C, 0x2)
    assert(!dut.swapCommitted.toBoolean, "swapCommitted not cleared by W1C @0x035C b1")
    assert((dut.io.swapStatus.toLong & 0x2) == 0, "swapStatus b1 not cleared by W1C readback")
    println("[sim] swapCommitted W1C-cleared (incl. 0x035C readback) — OK")

    // (7) Repeat with a different target.
    doSwap(0x040000L, 0x050000L)

    println("[sim] VdpFrameAtomicSwapSim: PASS")
  }
}
