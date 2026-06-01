package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** #11228 discriminator #1 (BrightForge) — does the QspiSdramBridge byteFifo
  * (depth 16, push backpressure IGNORED) DROP payload bytes at the PRODUCTION
  * QSPI write rate under active-video gating?
  *
  * The pre-existing QspiSdramBridgeSim drives one byte every ~32 pixel cycles
  * (ESP8266 ~500 kHz QSPI) and PASSES — that rate lets the FIFO drain faster
  * than it fills. But the production host is the ESP32-S3 at 60 MHz writes
  * ([[reference_esp32s3_host_config]]): a payload byte arrives roughly every
  * pixel cycle. During active video (allowUpload = !de is LOW) the bridge does
  * NOT drain at all, so a burst longer than the 16-deep FIFO overflows and
  * silently drops bytes — a clock-INDEPENDENT loss that explains why lowering
  * the SDRAM clock (Option A) did not help (HW fails #11224/#11226).
  *
  * This sim drives a tile-sized burst (256 bytes, the HW gate's tile upload
  * size class) at a parameterized inter-byte gap under the real 4:1
  * active:blank gate, and counts bytes EMITTED on wrCmd vs bytes PUSHED. A
  * drop (emitted < pushed) localizes the fault to the bridge byteFifo.
  *
  * Pass/fail here is DIAGNOSTIC, not a regression gate: it prints the drop
  * count at each rate so the seam fault is reproduced in sim before any RTL
  * change (PM #11228: no fix until repro confirms location).
  */
object UploadByteRateSim extends App {

  // (label, inter-byte gap in pixel cycles). gap=0 -> a byte every cycle
  // (≈ ESP32-S3 60 MHz). gap=31 -> the legacy ESP8266 ~500 kHz design point.
  val rates = Seq(("60MHz-ish (gap=0)", 0), ("~3MHz (gap=8)", 8), ("~500kHz (gap=31)", 31))

  Config.sim.compile(QspiSdramBridge()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.headerValid #= false
    dut.io.addrInit    #= 0
    dut.io.lenBytes    #= 0
    dut.io.byteIn      #= 0
    dut.io.byteValid   #= false
    dut.io.allowUpload #= true
    dut.io.wrCmd.ready #= true     // downstream CC FIFO modeled always-ready:
                                   // isolates the byteFifo PUSH-side drop. The
                                   // real drain stalls EARLIER (allowUpload),
                                   // so this is the optimistic case — any drop
                                   // here is a floor, HW can only be worse.
    dut.clockDomain.waitSampling(5)

    // Real 4:1 active:blank gate: allowUpload LOW 80 cyc (active video),
    // HIGH 20 cyc (blanking). Same ratio the pre-existing sim uses.
    var gateRun = true
    val gate = fork {
      while (gateRun) {
        dut.io.allowUpload #= false
        dut.clockDomain.waitSampling(80)
        dut.io.allowUpload #= true
        dut.clockDomain.waitSampling(20)
      }
    }

    def runBurst(label: String, gap: Int): Unit = {
      val addrInit = 0xA000
      val nBytes   = 256

      // Count committed writes for THIS burst.
      var emitted = 0
      var stop = false
      val cap = fork {
        while (!stop) {
          dut.clockDomain.waitSampling()
          if (dut.io.wrCmd.valid.toBoolean && dut.io.wrCmd.ready.toBoolean) emitted += 1
        }
      }

      dut.io.addrInit    #= addrInit
      dut.io.lenBytes    #= nBytes
      dut.io.headerValid #= true
      dut.clockDomain.waitSampling()
      dut.io.headerValid #= false

      for (i <- 0 until nBytes) {
        dut.io.byteIn    #= (0xA0 + (i & 0xFF)) & 0xFF
        dut.io.byteValid #= true
        dut.clockDomain.waitSampling()
        dut.io.byteValid #= false
        if (gap > 0) dut.clockDomain.waitSampling(gap)
      }

      // Drain.
      dut.clockDomain.waitSampling(6000)
      stop = true
      cap.join()

      val dropped = nBytes - emitted
      println(f"[rate $label%-20s] pushed=$nBytes emitted=$emitted DROPPED=$dropped")
    }

    for ((label, gap) <- rates) runBurst(label, gap)

    gateRun = false
    gate.join()
    println("UploadByteRateSim: done (diagnostic — see DROPPED counts above)")
  }
}
