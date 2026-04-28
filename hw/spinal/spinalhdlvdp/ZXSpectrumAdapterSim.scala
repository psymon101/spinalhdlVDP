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

    println("[sim] ZXSpectrumAdapterSim: PASS")
  }
}
