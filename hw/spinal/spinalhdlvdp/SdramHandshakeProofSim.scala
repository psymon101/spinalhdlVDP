package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

/** CP-A4 (Phase A #11444) — the "Handshake Proof" gate-opener.
  *
  * Integration sim on the REAL sdram.v controller (via SdramWithModel: real sdram.v +
  * behavioral sdram_model, tristate hidden internally) wired to the REAL SpinalHDL
  * datapath: QspiSdramBridge -> uploadCc -> SdramArbiter (6 clients) with the CP-A2/A2b
  * grant-override glue + CP-A3 central refresh. Single clock domain for the sim
  * (clk_sdram driven 180-deg as the chip sample clock).
  *
  * PROVES (TopazCliff #11444): a DEBUG READ (client 5) returns the exact data an
  * UPLOAD (client 4) wrote, WHILE a video FETCH (client 0) is concurrently reading and
  * central refresh is running — end to end through the real controller. This is the
  * final correctness proof of the CP-A1..A3 structural rework before the flash gate.
  *
  * Client 0 (fetch) is modelled as a representative read-contender (pulsing reads at a
  * distinct address); the real fetch engine's arbiter behaviour IS "a client issuing
  * reads", and the engine itself is proven by TileAttributeFetchSim/PlanarRefreshStallSim.
  */
object SdramHandshakeProofSim extends App {
  val FETCH_A = 0x000400          // fetch read address (distinct from upload)
  val DBG_A   = 0x00A000          // upload + debug-read address
  // Upload payload bytes (LE) -> dout32 word.
  val B0 = 0x12; val B1 = 0x34; val B2 = 0x56; val B3 = 0x78
  val EXP_WORD = (B3 << 24) | (B2 << 16) | (B1 << 8) | B0   // 0x78563412

  class Harness extends Component {
    val io = new Bundle {
      val clkSdram   = in  Bool()
      // bridge upload drive
      val headerValid = in Bool()
      val addrInit    = in UInt(23 bits)
      val lenBytes    = in UInt(17 bits)
      val byteIn      = in Bits(8 bits)
      val byteValid   = in Bool()
      // synthetic fetch read contender (client 0)
      val fetchRd     = in Bool()
      // debug read arm
      val dbgArm      = in Bool()
      // observability
      val uploadBusy  = out Bool()
      val dbgInFlight = out Bool()
      val dataReg     = out Bits(32 bits)
      val initBusy    = out Bool()
    }

    val sdram = SdramWithModel()
    sdram.io.clk       := ClockDomain.current.readClockWire
    sdram.io.clk_sdram := io.clkSdram
    sdram.io.resetn    := ClockDomain.current.isResetActive ? False | True
    io.initBusy := sdram.io.busy

    val arbiter = SdramArbiter(clientCount = 6, addrWidth = 23, dataWidth = 8, refreshPeriodCycles = 64)
    for (i <- 0 until 6) arbiter.io.clientBurstLen(i) := U(1, 4 bits)  // single-read sim

    // ---- upload bridge + CC FIFO (single clock here) ----
    val bridge = QspiSdramBridge()
    bridge.io.headerValid := io.headerValid
    bridge.io.addrInit    := io.addrInit
    bridge.io.lenBytes    := io.lenBytes
    bridge.io.byteIn      := io.byteIn
    bridge.io.byteValid   := io.byteValid
    bridge.io.allowUpload := True
    io.uploadBusy := bridge.io.uploadBusy
    val uploadCc = StreamFifo(Bits(31 bits), 128)
    uploadCc.io.push << bridge.io.wrCmd

    // ---- debug-read snoop FSM (mirrors TopTang20kHdmi dbgReadArea) ----
    val pending  = Reg(Bool()) init False
    val inFlight = Reg(Bool()) init False
    val rdPulse  = Reg(Bool()) init False
    val rdAddr   = Reg(UInt(23 bits)) init 0
    val dataReg  = Reg(Bits(32 bits)) init 0
    rdPulse := False
    val armPrev = RegNext(io.dbgArm) init False
    when(io.dbgArm && !armPrev) { pending := True }

    // ---- upload pop gate (mirrors uploadPopArea: drain on idle cycles) ----
    val fetchActive = io.fetchRd
    val canAccept = !sdram.io.busy && !fetchActive && !rdPulse && !inFlight
    uploadCc.io.pop.ready := canAccept
    val uploadDrive = uploadCc.io.pop.fire
    val upAddr = uploadCc.io.pop.payload(30 downto 8).asUInt
    val upDin  = uploadCc.io.pop.payload(7 downto 0)

    // ---- central refresh (CP-A3): arbiter owns the timer; issue at an idle safe point ----
    val refreshPending = Reg(Bool()) init False
    when(arbiter.io.refreshDue) { refreshPending := True }
    val doRefresh = refreshPending && !sdram.io.busy && !fetchActive && !uploadDrive && !rdPulse && !inFlight
    when(doRefresh) { refreshPending := False }

    // debug read issues when idle + upload drained (mirrors sdramIdle + uploadDrained)
    val uploadDrained = !uploadCc.io.pop.valid && !bridge.io.uploadBusy
    val sdramIdle = !arbiter.io.sdramRd && !arbiter.io.sdramWr && !doRefresh && !sdram.io.busy
    when(pending && !inFlight && sdramIdle && uploadDrained) {
      rdPulse := True; rdAddr := U(DBG_A, 23 bits); inFlight := True; pending := False
    }
    when(inFlight && sdram.io.data_ready) { dataReg := sdram.io.dout32; inFlight := False }

    // ---- arbiter client wiring ----
    arbiter.io.slotValid := True
    arbiter.io.grant     := False
    arbiter.io.clientRd(0)   := io.fetchRd; arbiter.io.clientWr(0) := False
    arbiter.io.clientAddr(0) := U(FETCH_A, 23 bits); arbiter.io.clientDin(0) := 0
    for (i <- 1 until 4) {
      arbiter.io.clientRd(i) := False; arbiter.io.clientWr(i) := False
      arbiter.io.clientAddr(i) := U(0, 23 bits); arbiter.io.clientDin(i) := B(0, 8 bits)
    }
    arbiter.io.clientRd(4)   := False; arbiter.io.clientWr(4) := uploadDrive
    arbiter.io.clientAddr(4) := upAddr; arbiter.io.clientDin(4) := upDin
    arbiter.io.clientRd(5)   := rdPulse; arbiter.io.clientWr(5) := False
    arbiter.io.clientAddr(5) := rdAddr; arbiter.io.clientDin(5) := 0
    arbiter.io.grantClientId := Mux(uploadDrive, U(4, arbiter.idBits bits),
                                Mux(rdPulse,     U(5, arbiter.idBits bits),
                                    U(0, arbiter.idBits bits)))

    // ---- controller drive (mirrors TopTang20kHdmi CP-A2/A2b/A3) ----
    sdram.io.rd      := arbiter.io.sdramRd &&
                        (!inFlight || arbiter.io.grantClientId === U(5, arbiter.idBits bits))
    sdram.io.wr      := arbiter.io.sdramWr
    sdram.io.refresh := doRefresh
    sdram.io.burstLen := arbiter.io.sdramBurstLen   // RGB565-FULLFRAME-132: single reads here (all clients drive 1)
    sdram.io.addr    := arbiter.io.sdramAddr
    sdram.io.din     := arbiter.io.sdramDin

    io.dbgInFlight := inFlight
    io.dataReg     := dataReg
  }

  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 20)
    dut.io.clkSdram #= false
    fork { sleep(10); while (true) { dut.io.clkSdram #= !dut.io.clkSdram.toBoolean; sleep(10) } }
    dut.io.headerValid #= false; dut.io.addrInit #= 0; dut.io.lenBytes #= 0
    dut.io.byteIn #= 0; dut.io.byteValid #= false
    dut.io.fetchRd #= false; dut.io.dbgArm #= false
    dut.clockDomain.waitSampling(5)

    // wait init/config (busy clears)
    var i = 0
    while (dut.io.initBusy.toBoolean && i < 5000) { dut.clockDomain.waitSampling(); i += 1 }
    assert(!dut.io.initBusy.toBoolean, "init never completed")
    println(s"[handshake] init done after $i cycles")

    // UPLOAD: one SDRAM_WRITE of 2 words (4 bytes) to DBG_A via the bridge.
    dut.io.addrInit #= DBG_A; dut.io.lenBytes #= 4; dut.io.headerValid #= true
    dut.clockDomain.waitSampling(); dut.io.headerValid #= false
    for (b <- Seq(B0, B1, B2, B3)) {
      dut.io.byteIn #= b; dut.io.byteValid #= true
      dut.clockDomain.waitSampling(); dut.io.byteValid #= false
      dut.clockDomain.waitSampling(2)
    }
    // start fetch contention (client 0 hammering reads)
    var fetchRun = true
    val fk = fork { while (fetchRun) { dut.io.fetchRd #= true; dut.clockDomain.waitSampling(5)
                                        dut.io.fetchRd #= false; dut.clockDomain.waitSampling(9) } }
    // wait for the upload to fully drain (bridge idle + CC empty)
    var d = 0
    while (dut.io.uploadBusy.toBoolean && d < 20000) { dut.clockDomain.waitSampling(); d += 1 }
    dut.clockDomain.waitSampling(50)

    // DEBUG READ at DBG_A while fetch contends + refresh runs.
    dut.io.dbgArm #= true; dut.clockDomain.waitSampling(2); dut.io.dbgArm #= false
    dut.clockDomain.waitSamplingWhere(dut.io.dbgInFlight.toBoolean)
    dut.clockDomain.waitSamplingWhere(!dut.io.dbgInFlight.toBoolean)
    dut.clockDomain.waitSampling(2)
    fetchRun = false; fk.join()

    val got = dut.io.dataReg.toInt & 0xFFFFFFFFL.toInt
    val ok = got == EXP_WORD
    println(f"[handshake] debug read @0x$DBG_A%X = 0x$got%08X (expected upload word 0x$EXP_WORD%08X)  ${if (ok) "PASS" else "*** FAIL ***"}")
    assert(ok, f"CP-A4 handshake: debug read must return the uploaded word 0x$EXP_WORD%08X, got 0x$got%08X")
    println("SdramHandshakeProofSim: PASS")
  }
}
