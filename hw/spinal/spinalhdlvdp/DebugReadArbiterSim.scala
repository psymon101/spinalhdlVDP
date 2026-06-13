package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

/** CP-A2b (Phase A #11429/#11432) — debug-read-as-arbiter-client-5 proof.
  *
  * The #10928 hazard: when the debug read was a side-channel (ctrl.rd OR + ctrl.addr
  * Mux), a fetch read issued through the arbiter while the debug read was in flight,
  * and the dataCaptured snoop (`inFlight && data_ready`) latched the FETCH transaction's
  * dout — so the readback returned data unrelated to the debug address. CP-A2b promotes
  * the debug read to arbiter client 5 (lowest priority) whose read makes the controller
  * busy, blocking fetch issue for the read's duration, and keeps the inFlight guard.
  *
  * This harness mirrors the TopTang20kHdmi CP-A2b glue (arbiter clientCount=6, client 0
  * = fetch reads, client 5 = debug read, grant override, ctrl.rd inFlight guard) against
  * a behavioral sdram model. It interleaves fetch reads at addr FETCH_A (data FETCH_D)
  * while arming a debug read at DBG_A (data DBG_D) and asserts the captured dataReg ==
  * DBG_D — i.e. the snoop returns the DEBUG address's committed data, never the fetch's.
  */
object DebugReadArbiterSim extends App {
  val FETCH_A = 0x200; val FETCH_D = 0xAA
  val DBG_A   = 0x500; val DBG_D   = 0x55

  class Harness extends Component {
    val io = new Bundle {
      val fetchRd   = in  Bool()              // pulse a fetch read (client 0) at FETCH_A
      val dbgArm    = in  Bool()              // arm a debug read at DBG_A
      val ctrlBusy      = in  Bool()
      val ctrlDout32    = in  Bits(32 bits)
      val ctrlDataReady = in  Bool()
      val ctrlRd    = out Bool()
      val ctrlAddr  = out UInt(23 bits)
      val dataReg   = out Bits(32 bits)       // captured debug-read result
      val dbgInFlight = out Bool()
    }
    val arbiter = SdramArbiter(clientCount = 6, addrWidth = 23, dataWidth = 8)
    for (i <- 0 until 6) arbiter.io.clientBurstLen(i) := U(1, 4 bits)  // single-read sim
    arbiter.io.slotValid := True
    arbiter.io.grant     := False
    // Client 0 = fetch read at FETCH_A while io.fetchRd held.
    arbiter.io.clientRd(0)   := io.fetchRd
    arbiter.io.clientWr(0)   := False
    arbiter.io.clientAddr(0) := U(FETCH_A, 23 bits)
    arbiter.io.clientDin(0)  := 0
    for (i <- 1 until 5) {
      arbiter.io.clientRd(i) := False; arbiter.io.clientWr(i) := False
      arbiter.io.clientAddr(i) := U(0, 23 bits); arbiter.io.clientDin(i) := B(0, 8 bits)
    }

    // --- mirrored dbgReadArea snoop FSM (CP-A2b) ---
    val pending  = Reg(Bool()) init False
    val inFlight = Reg(Bool()) init False
    val rdPulse  = Reg(Bool()) init False
    val rdAddr   = Reg(UInt(23 bits)) init 0
    val dataReg  = Reg(Bits(32 bits)) init 0
    rdPulse := False
    val armPrev = RegNext(io.dbgArm) init False
    when(io.dbgArm && !armPrev) { pending := True }
    // sdramIdle: no fetch read being granted + controller not busy.
    val sdramIdle = !arbiter.io.sdramRd && !io.ctrlBusy
    when(pending && !inFlight && sdramIdle) {
      rdPulse := True; rdAddr := U(DBG_A, 23 bits); inFlight := True; pending := False
    }
    when(inFlight && io.ctrlDataReady) { dataReg := io.ctrlDout32; inFlight := False }

    // Client 5 = debug read.
    arbiter.io.clientRd(5)   := rdPulse
    arbiter.io.clientWr(5)   := False
    arbiter.io.clientAddr(5) := rdAddr
    arbiter.io.clientDin(5)  := 0
    // Grant: debug(5) when rdPulse else fetch(0). (No upload in this harness.)
    arbiter.io.grantClientId := Mux(rdPulse, U(5, arbiter.idBits bits), U(0, arbiter.idBits bits))

    // ctrl.rd with the CP-A2b inFlight guard (fetch blocked while debug in flight).
    io.ctrlRd := arbiter.io.sdramRd &&
                 (!inFlight || arbiter.io.grantClientId === U(5, arbiter.idBits bits))
    io.ctrlAddr := arbiter.io.sdramAddr
    io.dataReg     := dataReg
    io.dbgInFlight := inFlight
  }

  SimConfig.compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.fetchRd #= false
    dut.io.dbgArm  #= false
    dut.io.ctrlBusy #= false
    dut.io.ctrlDout32 #= 0
    dut.io.ctrlDataReady #= false
    val mem = mutable.HashMap(FETCH_A -> FETCH_D, DBG_A -> DBG_D)
    dut.clockDomain.waitSampling(5)

    // behavioral sdram model: rd -> busy(timer=4) -> dout32 = mem[addr] (byte0), data_ready.
    fork {
      var state = "idle"; var timer = 0; var a = 0
      while (true) {
        dut.clockDomain.waitSampling()
        dut.io.ctrlDataReady #= false
        state match {
          case "idle" =>
            if (dut.io.ctrlRd.toBoolean) { a = dut.io.ctrlAddr.toInt; dut.io.ctrlBusy #= true; state = "wait"; timer = 4 }
          case "wait" =>
            timer -= 1
            if (timer == 0) {
              dut.io.ctrlDout32 #= BigInt(mem.getOrElse(a & 0x7fffff, 0) & 0xFF)
              dut.io.ctrlDataReady #= true; state = "done"
            }
          case "done" => dut.io.ctrlBusy #= false; state = "idle"
        }
      }
    }

    // Deterministic arm+capture using waitSamplingWhere (handshake-safe, per the
    // SpinalSim lesson: don't poll bare waitSampling against a model that drives busy
    // a cycle late). Returns the captured byte.
    def armAndCapture(): Int = {
      dut.io.dbgArm #= true; dut.clockDomain.waitSampling(2); dut.io.dbgArm #= false
      dut.clockDomain.waitSamplingWhere(dut.io.dbgInFlight.toBoolean)   // read issued
      dut.clockDomain.waitSamplingWhere(!dut.io.dbgInFlight.toBoolean)  // data captured
      dut.clockDomain.waitSampling(2)
      dut.io.dataReg.toInt & 0xFF
    }

    // Phase A — clean idle: debug read must capture its own address's data.
    dut.clockDomain.waitSampling(20)
    val gotA = armAndCapture()
    val okA = gotA == DBG_D
    println(f"[dbgrd] PhaseA idle:    captured 0x$gotA%02X expect 0x$DBG_D%02X  ${if (okA) "PASS" else "*** FAIL ***"}")

    // Phase B — under fetch-read contention at FETCH_A (data 0xAA): the debug read
    // must STILL capture DBG_D (0x55), never the fetch transaction's data.
    var fetchRun = true
    val fk = fork {
      while (fetchRun) {
        dut.io.fetchRd #= true;  dut.clockDomain.waitSampling(6)
        dut.io.fetchRd #= false; dut.clockDomain.waitSampling(10)   // gaps leave idle windows
      }
    }
    dut.clockDomain.waitSampling(30)
    val gotB = armAndCapture()
    fetchRun = false; fk.join()
    val okB = gotB == DBG_D
    println(f"[dbgrd] PhaseB contend: captured 0x$gotB%02X expect 0x$DBG_D%02X (fetch=0x$FETCH_D%02X)  ${if (okB) "PASS" else "*** FAIL — snoop latched fetch data ***"}")

    assert(okA && okB, s"CP-A2b: debug read must capture 0x$DBG_D%02X in both phases (got A=0x$gotA B=0x$gotB)")
    println("DebugReadArbiterSim: PASS")
  }
}
