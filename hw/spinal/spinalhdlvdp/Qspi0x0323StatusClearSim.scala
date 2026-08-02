package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Status-contract cleanup (#14638) — verifies the re-added QspiSdramBridge write-1-to-clear
  * inputs (`uploadErrorClear` / `fifoOverflowClear`) that the centralized 0x0323 W1C decode in
  * VdpTop strobes. Proves:
  *   - `fifoOverflow` sets on a downstream (wrCmd) stall and clears on a W1C strobe.
  *   - `uploadError` sets on the CP-A1 watchdog abort and clears on a W1C strobe.
  *   - SET-WINS-ON-TIE: a clear strobe pulsed WHILE the set condition is active must NOT drop
  *     the live event (the clear `when` is sequenced before the set `when`, so the set wins).
  *
  * Small `stallTimeout` keeps the watchdog test short. The VdpTop 0x0323 decode, QSPI sel=5/6
  * responder, and i80 0x0320/0x0323 read mux are combinational wiring validated by dual-top
  * elaboration; this sim targets the only new SEQUENTIAL logic — the bridge W1C set/clear race.
  *
  * Run: sbt "runMain spinalhdlvdp.Qspi0x0323StatusClearSim"
  */
object Qspi0x0323StatusClearSim extends App {

  var pass = true

  Config.sim.compile(QspiSdramBridge(stallTimeout = 64)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def quiescent(): Unit = {
      dut.io.headerValid #= false
      dut.io.addrInit    #= 0
      dut.io.lenBytes    #= 0
      dut.io.byteIn      #= 0
      dut.io.byteValid   #= false
      dut.io.allowUpload #= true
      dut.io.wrCmd.ready #= true
      dut.io.uploadErrorClear  #= false
      dut.io.fifoOverflowClear #= false
    }
    quiescent()
    dut.clockDomain.waitSampling(5)

    def check(cond: Boolean, msg: String): Unit = {
      if (!cond) { println(s"  [FAIL] $msg"); pass = false } else println(s"  [ok]   $msg")
    }

    // Fire a header + one payload byte with the downstream stalled (wrCmd.ready=false) so the
    // bridge enters sActive and tries to emit -> wrCmd.valid && !ready sets fifoOverflow.
    def armStall(addr: Int, data: Int): Unit = {
      dut.io.wrCmd.ready #= false
      dut.io.addrInit    #= addr
      dut.io.lenBytes    #= 4
      dut.io.headerValid #= true;  dut.clockDomain.waitSampling(); dut.io.headerValid #= false
      dut.io.byteIn      #= data
      dut.io.byteValid   #= true;  dut.clockDomain.waitSampling(); dut.io.byteValid #= false
    }
    // Drop the stall and let the bridge unwind back to idle (does NOT touch the stickies).
    def relaxAndSettle(): Unit = {
      dut.io.wrCmd.ready #= true
      dut.clockDomain.waitSampling(200)   // drain / flush / return to sIdle
    }

    println("=== Qspi0x0323StatusClearSim: bridge W1C set/clear + set-wins-on-tie ===")

    // --- Test 1: fifoOverflow set (downstream stall) then W1C clear ---
    armStall(0x1000, 0xAB)
    dut.clockDomain.waitSampling(12)
    check(dut.io.fifoOverflow.toBoolean, "fifoOverflow SET on wrCmd downstream stall")
    relaxAndSettle()                                   // remove the set condition first
    dut.io.fifoOverflowClear #= true; dut.clockDomain.waitSampling(); dut.io.fifoOverflowClear #= false
    dut.clockDomain.waitSampling(3)
    check(!dut.io.fifoOverflow.toBoolean, "fifoOverflow CLEARED by W1C strobe")

    // --- Test 2: uploadError (CP-A1 watchdog abort) then W1C clear ---
    armStall(0x2000, 0xCD)
    dut.clockDomain.waitSampling(120)                  // > stallTimeout(64) -> watchdog abort
    check(dut.io.uploadError.toBoolean, "uploadError SET on watchdog stall abort")
    relaxAndSettle()
    dut.io.uploadErrorClear #= true; dut.clockDomain.waitSampling(); dut.io.uploadErrorClear #= false
    dut.clockDomain.waitSampling(3)
    check(!dut.io.uploadError.toBoolean, "uploadError CLEARED by W1C strobe")
    // clear fifoOverflow too (the stall in Test 2 also set it) so Test 3 starts clean
    dut.io.fifoOverflowClear #= true; dut.clockDomain.waitSampling(); dut.io.fifoOverflowClear #= false
    dut.clockDomain.waitSampling(3)
    check(!dut.io.fifoOverflow.toBoolean, "fifoOverflow also cleared before tie test")

    // --- Test 3: SET-WINS-ON-TIE — pulse the clear WHILE the set condition is active ---
    armStall(0x3000, 0xEF)
    dut.clockDomain.waitSampling(8)                    // still within the pre-abort window
    check(dut.io.fifoOverflow.toBoolean, "fifoOverflow SET (tie setup)")
    // wrCmd.ready is still false here, so wrCmd.valid && !ready re-fires this same cycle:
    dut.io.fifoOverflowClear #= true; dut.clockDomain.waitSampling(); dut.io.fifoOverflowClear #= false
    dut.clockDomain.waitSampling(2)
    check(dut.io.fifoOverflow.toBoolean, "SET-WINS-ON-TIE: fifoOverflow stays SET (clear pulsed while set active)")
    relaxAndSettle()

    println(if (pass) "Qspi0x0323StatusClearSim: PASS" else "Qspi0x0323StatusClearSim: FAIL")
  }
  if (!pass) { println("Qspi0x0323StatusClearSim FAILED"); sys.exit(1) }
}
