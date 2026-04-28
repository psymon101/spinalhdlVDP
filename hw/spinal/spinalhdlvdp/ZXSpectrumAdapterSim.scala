package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 50 — ZXSpectrumAdapter unit sim.
  *
  * Covers the artifact §11.1 validation matrix:
  *
  *   1. ZX_BORDER write → borderColor output reflects the new value
  *      (and only the low 3 bits land — high bits are ignored).
  *   2. ZX_FLASH_CTRL / ZX_FLASH_RATE writes shadow correctly and
  *      surface on flashEnable / flashRate.
  *   3. ZX_CTRL[0] rising edge emits a one-cycle bus write of
  *      LAYER_ENABLE = 0x0001 (L0 only) at addr 0x0300. Falling
  *      edge does NOT re-emit. Sustained high holds emit deasserted.
  *   4. Out-of-range register writes (regAddr >= shadowDepth) leave
  *      shadow state unchanged.
  *   5. ZX_PAL_LOAD slot is shadowable but produces no bus emission
  *      in v1 (reserved-for-future-use slot, per artifact §6).
  */
object ZXSpectrumAdapterSim extends App {
  Config.sim.compile(ZXSpectrumAdapter()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.regAddr #= 0
    dut.io.regData #= 0
    dut.io.regWr   #= false
    dut.clockDomain.waitSampling(3)

    def zxWrite(addr: Int, data: Int): Unit = {
      dut.io.regAddr #= addr & 0xFF
      dut.io.regData #= data & 0xFF
      dut.io.regWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.regWr   #= false
      dut.io.regAddr #= 0
      dut.io.regData #= 0
      dut.clockDomain.waitSampling()
    }

    // ---- Case 1: ZX_BORDER write -------------------------------------
    zxWrite(0x00, 0x05)
    assert(dut.io.borderColor.toInt == 5,
      s"Case 1a: expected borderColor=5, got ${dut.io.borderColor.toInt}")
    // High bits beyond [2:0] should be discarded.
    zxWrite(0x00, 0xF3)         // 0xF3 & 0x07 = 0x03
    assert(dut.io.borderColor.toInt == 3,
      s"Case 1b: expected borderColor=3 (low 3 bits of 0xF3), got ${dut.io.borderColor.toInt}")
    println(s"[sim] Case 1 ZX_BORDER shadow + low-bit slice — OK")

    // ---- Case 2: ZX_FLASH_CTRL / ZX_FLASH_RATE -----------------------
    zxWrite(0x01, 0x01)
    assert(dut.io.flashEnable.toBoolean,
      "Case 2a: expected flashEnable=true after ZX_FLASH_CTRL=1")
    zxWrite(0x02, 32)
    assert(dut.io.flashRate.toInt == 32,
      s"Case 2b: expected flashRate=32, got ${dut.io.flashRate.toInt}")
    zxWrite(0x01, 0x00)
    assert(!dut.io.flashEnable.toBoolean,
      "Case 2c: expected flashEnable=false after ZX_FLASH_CTRL=0")
    println(s"[sim] Case 2 ZX_FLASH_CTRL / ZX_FLASH_RATE shadow — OK")

    // ---- Case 3: ZX_CTRL[0] rising edge → LAYER_ENABLE bus emit ------
    // First drain any v2 border emits queued by Case 1 (3 cycles per emit
    // × 2 border writes = up to ~10 cycles to fully serialise).
    dut.clockDomain.waitSampling(20)

    // Set up a sampler that watches busWr and captures any pulses.
    val emitted = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    val watcher = fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.busWr.toBoolean) {
          emitted.append((dut.io.busAddr.toInt, dut.io.busData.toInt))
        }
      }
    }

    zxWrite(0x03, 0x01)               // ZX_CTRL = 1 — rising edge
    dut.clockDomain.waitSampling(3)   // give the emitter time to fire
    val rising = emitted.toList
    assert(rising.size == 1,
      s"Case 3a: expected exactly 1 bus emit on rising edge, got ${rising.size} -> $rising")
    val (addr, data) = rising.head
    assert(addr == 0x0300,
      f"Case 3a: expected bus addr=0x0300, got 0x$addr%04X")
    assert(data == 0x0001,
      f"Case 3a: expected LAYER_ENABLE data=0x0001 (L0 only), got 0x$data%04X")
    println(s"[sim] Case 3a ZX_CTRL rising edge → LAYER_ENABLE=0x0001 at 0x0300 — OK")

    // Sustained high: a re-write of ZX_CTRL=1 should NOT re-emit.
    emitted.clear()
    zxWrite(0x03, 0x01)
    dut.clockDomain.waitSampling(3)
    assert(emitted.isEmpty,
      s"Case 3b: sustained-high re-write must not re-emit, got $emitted")
    println(s"[sim] Case 3b sustained-high suppresses re-emit — OK")

    // Falling edge then rising edge → emit again exactly once.
    emitted.clear()
    zxWrite(0x03, 0x00)               // falling edge — no emit expected
    dut.clockDomain.waitSampling(3)
    assert(emitted.isEmpty,
      s"Case 3c: falling edge must not emit, got $emitted")
    zxWrite(0x03, 0x01)               // rising edge again
    dut.clockDomain.waitSampling(3)
    assert(emitted.size == 1,
      s"Case 3d: re-rise should emit exactly once, got $emitted")
    println(s"[sim] Case 3c/d falling + re-rising edge handled — OK")

    // ---- Case 4: out-of-range write ignored --------------------------
    val borderBefore = dut.io.borderColor.toInt
    zxWrite(0x80, 0x07)               // 0x80 > shadowDepth=0x11
    assert(dut.io.borderColor.toInt == borderBefore,
      s"Case 4: out-of-range write must not corrupt border (was $borderBefore, now ${dut.io.borderColor.toInt})")
    println(s"[sim] Case 4 out-of-range write rejected — OK")

    // ---- Case 5: ZX_PAL_LOAD shadow + no bus emit --------------------
    emitted.clear()
    zxWrite(0x10, 0x55)
    dut.clockDomain.waitSampling(3)
    assert(emitted.isEmpty,
      s"Case 5: ZX_PAL_LOAD must not emit bus writes in v1, got $emitted")
    println(s"[sim] Case 5 ZX_PAL_LOAD shadowable, no bus emit — OK")

    // ---- Case 6 (v2): ZX_BORDER → 3-write palette emit sequence ------
    // Writing ZX_BORDER=4 (green) must produce exactly:
    //   addr=0x0601 data=48 (entry borderSlot=24 × 2, low half pointer)
    //   addr=0x0600 data=0xCD00 (G:B = 0xCD:0x00 — green low half)
    //   addr=0x0600 data=0x0000 (R = 0x00 — green high half, commits)
    emitted.clear()
    zxWrite(0x00, 0x04)               // ZX_BORDER = 4 (green)
    dut.clockDomain.waitSampling(10)  // give the FSM time to emit all 3 words
    assert(emitted.size == 3,
      s"Case 6a: expected 3 bus emits for green border, got ${emitted.size} -> $emitted")
    val (a0, d0) = emitted(0)
    val (a1, d1) = emitted(1)
    val (a2, d2) = emitted(2)
    assert(a0 == 0x0601 && d0 == 24 * 2,
      f"Case 6a step 1: expected (0x0601, ${24*2}), got (0x$a0%04X, 0x$d0%04X)")
    assert(a1 == 0x0600 && d1 == 0xCD00,
      f"Case 6a step 2: expected (0x0600, 0xCD00) green G:B, got (0x$a1%04X, 0x$d1%04X)")
    assert(a2 == 0x0600 && d2 == 0x0000,
      f"Case 6a step 3: expected (0x0600, 0x0000) green R, got (0x$a2%04X, 0x$d2%04X)")
    println(f"[sim] Case 6a ZX_BORDER=4 (green) → 3-write palette sequence — OK")

    // Different border code → different RGB. Test code 2 (red) and 5 (cyan).
    emitted.clear()
    zxWrite(0x00, 0x02)               // red
    dut.clockDomain.waitSampling(10)
    assert(emitted.size == 3, s"Case 6b red: expected 3 emits, got ${emitted.size}")
    assert(emitted(0) == (0x0601, 48), s"Case 6b ptr: ${emitted(0)}")
    assert(emitted(1) == (0x0600, 0x0000), s"Case 6b G:B (red is G=0,B=0): ${emitted(1)}")
    assert(emitted(2) == (0x0600, 0x00CD), s"Case 6b R (red is 0xCD): ${emitted(2)}")
    println(f"[sim] Case 6b ZX_BORDER=2 (red) → 0xCD0000 emitted — OK")

    emitted.clear()
    zxWrite(0x00, 0x05)               // cyan
    dut.clockDomain.waitSampling(10)
    assert(emitted.size == 3, s"Case 6c cyan: expected 3 emits, got ${emitted.size}")
    assert(emitted(1) == (0x0600, 0xCDCD), s"Case 6c G:B (cyan G=0xCD,B=0xCD): ${emitted(1)}")
    assert(emitted(2) == (0x0600, 0x0000), s"Case 6c R (cyan R=0): ${emitted(2)}")
    println(f"[sim] Case 6c ZX_BORDER=5 (cyan) → 0x00CDCD emitted — OK")

    // ---- Case 7 (v2): mixed ordering — border + ctrl rise serialised --
    // If both events queue simultaneously, adapterRise gets priority,
    // followed by the 3-word border emit.
    emitted.clear()
    // First reset ZX_CTRL to 0 so we can re-rise it.
    zxWrite(0x03, 0x00)
    dut.clockDomain.waitSampling(2)
    emitted.clear()
    // Set border AND ctrl rise in quick succession.
    zxWrite(0x00, 0x06)               // border = yellow
    zxWrite(0x03, 0x01)               // ctrl rises to 1
    dut.clockDomain.waitSampling(15)
    // Expect 4 emits total: 3 border + 1 LAYER_ENABLE in some order.
    // The serialiser prioritises adapterRise over pendingBorder when
    // both surface at sIdle, but the border write happened first so
    // its FSM sequence may already be in flight when the rise fires.
    assert(emitted.size == 4,
      s"Case 7: expected 4 emits (3 border + 1 LAYER_ENABLE), got ${emitted.size} -> $emitted")
    val layerEmits = emitted.count(_._1 == 0x0300)
    assert(layerEmits == 1,
      s"Case 7: expected exactly 1 LAYER_ENABLE emit at 0x0300, got $layerEmits in $emitted")
    val borderPtrEmits  = emitted.count(e => e._1 == 0x0601)
    val borderDataEmits = emitted.count(e => e._1 == 0x0600)
    assert(borderPtrEmits == 1 && borderDataEmits == 2,
      s"Case 7: expected 1 ptr + 2 data emits, got ptr=$borderPtrEmits data=$borderDataEmits in $emitted")
    println(f"[sim] Case 7 mixed ordering: 3 border + 1 LAYER_ENABLE serialised — OK (sequence=$emitted)")

    println("[sim] ZXSpectrumAdapterSim: PASS")
  }
}
