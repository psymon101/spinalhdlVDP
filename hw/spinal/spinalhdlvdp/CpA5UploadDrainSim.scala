package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** CP-A5 (Phase A #11464/#11470) — tile[31] uploadCc-overflow reproduction + fix proof.
  *
  * Deterministic, FAITHFUL-CDC harness: the REAL QspiSdramBridge (pixel domain) + the
  * REAL uploadCc StreamFifoCC (depth 128, pixel->sdram, two clock domains, exactly as in
  * TopTang20kHdmi) + a directly-gated drain on the SDRAM side. No behavioral controller /
  * arbiter / fetch engine — those only modelled WHY the drain is gated; here the drain
  * gate is driven directly so the CC-fill is deterministic (no timing tuning, no single-
  * clock-collapse races).
  *
  * The HW discriminator does sentinel(4B) + 32 tiles(128B) = 132 bytes; uploadCc is 128
  * deep, so byte #129 (tile[31]) is the first that cannot enter the CC. With the drain
  * gated off longer than the bridge watchdog window, the bridge stall on that byte trips
  * the CP-A1 watchdog -> abort -> tile[31] dropped. That is the HW tile[31] failure.
  *
  * pollMode models the host's per-tile poll-clear:
  *   "bridge"  (today / TopTang:420 upload_busy = bridge only) -> host advances when the
  *             bridge hands bytes to uploadCc, NOT when they drain -> CC fills -> abort.
  *   "full"    (CP-A5 fix: upload_busy = bridge OR uploadCc-not-empty) -> host waits for
  *             the CC to drain -> CC never fills -> all 132 bytes commit.
  */
object CpA5UploadDrainSim extends App {
  class Harness(stallTimeout: Int = 256) extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    val io = new Bundle {
      val headerValid = in Bool()
      val addrInit    = in UInt(23 bits)
      val lenBytes    = in UInt(17 bits)
      val byteIn      = in Bits(8 bits)
      val byteValid   = in Bool()
      val drainGate   = in Bool()        // sdram-domain: 1 = uploadCc may drain this cycle
      // observability
      val uploadBusy     = out Bool()    // bridge-only (today's host poll signal)
      val uploadBusyFull = out Bool()    // CP-A5: bridge OR uploadCc-not-empty (full drain)
      val uploadError    = out Bool()    // CP-A1 watchdog sticky abort
      val uploadFires    = out UInt(16 bits)  // bytes actually drained (committed)
    }

    val bridge = QspiSdramBridge(stallTimeout = stallTimeout)
    bridge.io.headerValid := io.headerValid
    bridge.io.addrInit    := io.addrInit
    bridge.io.lenBytes    := io.lenBytes
    bridge.io.byteIn      := io.byteIn
    bridge.io.byteValid   := io.byteValid
    bridge.io.allowUpload := True
    io.uploadBusy  := bridge.io.uploadBusy
    io.uploadError := bridge.io.uploadError

    val uploadCc = StreamFifoCC(Bits(31 bits), 128, ClockDomain.current, sdramCd)
    uploadCc.io.push << bridge.io.wrCmd

    // SDRAM-side drain, gated directly (models fetch contention blocking canAccept).
    val sdramArea = new ClockingArea(sdramCd) {
      val fires = Reg(UInt(16 bits)) init 0
      uploadCc.io.pop.ready := io.drainGate
      when(uploadCc.io.pop.fire) { fires := fires + 1 }
    }
    io.uploadFires := sdramArea.fires
    // CP-A5 fix signal — EXACTLY the production wiring (TopTang20kHdmi): full-drain busy =
    // bridge active OR uploadCc.pushOccupancy != 0 (push-side, pixel-domain native, no CDC).
    io.uploadBusyFull := bridge.io.uploadBusy || (uploadCc.io.pushOccupancy =/= 0)
  }

  val compiled = SimConfig.compile(new Harness(stallTimeout = 256))

  // drive one SDRAM_WRITE transaction (header + payload bytes) into the bridge
  def txn(dut: Harness, addr: Int, bytes: Seq[Int]): Unit = {
    dut.io.addrInit #= addr; dut.io.lenBytes #= bytes.length; dut.io.headerValid #= true
    dut.clockDomain.waitSampling(); dut.io.headerValid #= false
    for (b <- bytes) {
      dut.io.byteIn #= b; dut.io.byteValid #= true
      dut.clockDomain.waitSampling(); dut.io.byteValid #= false
    }
  }

  def run(pollFull: Boolean): (Int, Boolean) = {
    var fires = -1; var err = false
    compiled.doSim(if (pollFull) "postFix" else "preFix") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.sdramCd.forkStimulus(period = 10)
      dut.io.headerValid #= false; dut.io.addrInit #= 0; dut.io.lenBytes #= 0
      dut.io.byteIn #= 0; dut.io.byteValid #= false; dut.io.drainGate #= false
      dut.clockDomain.waitSampling(10)

      // Drain gate: OFF for an initial 1500-cyc window (> the host's burst time to push
      // 132 bytes + stallTimeout=256), then pulse ON. With only 132 bytes total vs a
      // 128-deep CC, the CC only fills if the host BURSTS faster than the drain — which
      // is exactly what preFix (poll bridge-only) does (bridge clears as soon as bytes
      // reach the CC, so the host floods it during the OFF window -> fills -> byte #129
      // = tile[31] stalls -> watchdog abort). postFix (poll full-drain) WAITS during the
      // OFF window (CC not empty) and only advances once the gate pulses ON -> CC stays
      // bounded -> all 132 commit. Same gate, opposite outcome — purely the poll signal.
      var gateRun = true
      val gk = fork {
        dut.io.drainGate #= false
        dut.sdramCd.waitSampling(1500)
        while (gateRun) { dut.io.drainGate #= true;  dut.sdramCd.waitSampling(100)
                          dut.io.drainGate #= false; dut.sdramCd.waitSampling(40) }
      }

      val poll = () => if (pollFull) dut.io.uploadBusyFull.toBoolean else dut.io.uploadBusy.toBoolean
      def pace(): Unit = { var p = 0; while (poll() && p < 8000) { dut.clockDomain.waitSampling(); p += 1 } }

      // sentinel 0xB000 (4B) then 32 tiles 0xA000.. (4B each) = 132 bytes vs CC depth 128
      txn(dut, 0x00B000, Seq(0x11, 0x11, 0x22, 0x22)); pace()
      for (t <- 0 until 32) { txn(dut, 0x00A000 + t * 4, Seq(0xFF, 0xFF, 0x00, 0x00)); pace() }

      // let everything settle / drain
      dut.io.drainGate #= true
      dut.clockDomain.waitSampling(4000)
      gateRun = false; gk.join()
      fires = dut.io.uploadFires.toBigInt.toInt
      err   = dut.io.uploadError.toBoolean
    }
    (fires, err)
  }

  val exp = 4 + 32 * 4   // 132
  val (pf, pe) = run(pollFull = false)
  println(f"[cpa5 preFix  pollBridgeOnly] fires=$pf/$exp uploadError=$pe -> ${if (pe || pf < exp) "REPRODUCED (tile[31] CC-overflow abort/loss)" else "did not reproduce"}")
  val (qf, qe) = run(pollFull = true)
  println(f"[cpa5 postFix pollFullDrain ] fires=$qf/$exp uploadError=$qe -> ${if (!qe && qf == exp) "FIXED (all 132 bytes committed, no abort)" else "*** STILL FAILS ***"}")
  assert(pe || pf < exp, "CP-A5 repro: preFix must reproduce the tile[31] overflow/abort")
  assert(!qe && qf == exp, s"CP-A5 fix: postFix must commit all $exp bytes with no abort (got fires=$qf err=$qe)")
  println("CpA5UploadDrainSim: PASS (reproduced under bridge-only poll; fixed under full-drain poll)")
}
