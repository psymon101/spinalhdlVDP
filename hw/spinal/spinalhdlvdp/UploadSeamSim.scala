package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable

/** #11246 / #11262 — end-to-end upload->SDRAM SEAM proof.
  *
  * Instantiates the REAL upload path (QspiSdramBridge + StreamFifoCC) + the REAL
  * SdramArbiter + the REAL fetch engine (SdramTileAttributeFetch, client 0, whose
  * registered cmdRd is the source of the GT-17 lag) + the top-level upload glue
  * (canAccept + ctrl mux), driving a faithful behavioral sdram.v model
  * (rd>wr priority + per-op busy timing, matching fpga/.../sdram.v).
  *
  * `preFix` A/Bs exactly the glue that changed in the fix bundle:
  *   - preFix=true  : OLD gating — bridge allowUpload=!de (byteFifo starves during
  *                    active video = F5) + canAccept on CURRENT arbiter rd/wr only
  *                    (no look-ahead = F2). Expected: byte DROPS.
  *   - preFix=false : NEW gating — allowUpload=True (continuous drain) + canAccept
  *                    look-ahead on each client's current AND next-cycle request.
  *                    Expected: ZERO drops.
  *
  * The top itself can't be simulated (rPLL blackboxes), so this harness mirrors
  * TopTang20kHdmi's upload glue verbatim (cross-referenced in comments). Drop count
  * = upload bytes pushed minus bytes that actually landed in the sdram model.
  */
case class UploadSeamHarness(preFix: Boolean) extends Component {
  val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
  val io = new Bundle {
    // Bridge inputs (pixel/default domain)
    val headerValid = in Bool()
    val addrInit    = in UInt(23 bits)
    val lenBytes    = in UInt(17 bits)
    val byteIn      = in Bits(8 bits)
    val byteValid   = in Bool()
    val de          = in Bool()           // active-video gate driver (preFix only)
    // Fetch engine drive (pixel domain)
    val fetchGrant  = in Bool()
    val fetchLine   = in UInt(10 bits)
    // Controller interface to the TB sdram model (sdram domain)
    val ctrlBusy      = in  Bool()
    val ctrlDout      = in  Bits(8 bits)
    val ctrlDout32    = in  Bits(32 bits)
    val ctrlDataReady = in  Bool()
    val ctrlRd        = out Bool()
    val ctrlWr        = out Bool()
    val ctrlRefresh   = out Bool()
    val ctrlAddr      = out UInt(23 bits)
    val ctrlDin       = out Bits(8 bits)
    // Observability
    val uploadFire  = out Bool()
    val uploadBusy  = out Bool()
    val bootDone    = out Bool()
  }

  // ---- Real upload bridge (pixel) ----
  val bridge = QspiSdramBridge()
  bridge.io.headerValid := io.headerValid
  bridge.io.addrInit    := io.addrInit
  bridge.io.lenBytes    := io.lenBytes
  bridge.io.byteIn      := io.byteIn
  bridge.io.byteValid   := io.byteValid
  // F5: preFix gates emission on blanking (the bug); postFix drains continuously.
  bridge.io.allowUpload := (if (preFix) !io.de else True)

  // ---- Real CDC FIFO (pixel -> sdram) ----
  val uploadCc = StreamFifoCC(Bits(31 bits), 128, ClockDomain.current, sdramCd)
  uploadCc.io.push << bridge.io.wrCmd

  // ---- Real fetch engine (client 0). FAITHFUL boot (no skipSdramInit) so the
  // refreshTimer initializes correctly and fires at ~593-cycle cadence, not the
  // skipSdramInit rapid-wrap storm (CyanPeak #11266). runMemtest off to trim time. ----
  val fetch = SdramTileAttributeFetch(sdramCd, runMemtest = false)
  fetch.io.fetchGrant       := io.fetchGrant
  fetch.io.fetchSlotValid   := True
  fetch.io.fetchPreAnnounce := False
  fetch.io.fetchLine        := io.fetchLine
  fetch.io.fetchScrollX     := 0
  fetch.io.fetchScrollY     := 0
  fetch.io.pixelAddr        := 0
  fetch.io.tileDecodeMode   := 0
  fetch.io.attributeMode    := 0
  io.bootDone   := fetch.io.bootDone
  io.uploadBusy := bridge.io.uploadBusy

  // ---- sdram-domain: arbiter + upload glue (mirrors TopTang20kHdmi) ----
  val sdramArea = new ClockingArea(sdramCd) {
    val arbiter = SdramArbiter(clientCount = 4, addrWidth = 23, dataWidth = 8)
    arbiter.io.grantClientId := U(0, 2 bits)
    arbiter.io.slotValid     := True
    arbiter.io.grant         := BufferCC(io.fetchGrant, False)
    arbiter.io.clientRd(0)   := fetch.io.sdramRd
    arbiter.io.clientWr(0)   := fetch.io.sdramWr
    arbiter.io.clientAddr(0) := fetch.io.sdramAddr
    arbiter.io.clientDin(0)  := fetch.io.sdramDin
    for (i <- 1 until 4) {
      arbiter.io.clientRd(i)   := False
      arbiter.io.clientWr(i)   := False
      arbiter.io.clientAddr(i) := U(0, 23 bits)
      arbiter.io.clientDin(i)  := B(0, 8 bits)
    }

    val deSync = BufferCC(io.de, False)
    val canAccept = if (preFix) {
      // OLD (TopTang20kHdmi pre-5ceecb3): current arbiter rd/wr + stale de gate.
      !io.ctrlBusy && !deSync && !arbiter.io.sdramRd && !arbiter.io.sdramWr &&
        !fetch.io.sdramRefresh
    } else {
      // NEW (5ceecb3): per-client current AND next-cycle look-ahead, no de gate.
      val fetchBusy = fetch.io.sdramRd || fetch.io.sdramWr ||
        fetch.io.sdramRdNext || fetch.io.sdramWrNext ||
        fetch.io.sdramRefresh || fetch.io.sdramRefreshNext
      !io.ctrlBusy && !fetchBusy
    }
    uploadCc.io.pop.ready := canAccept
    val fire   = uploadCc.io.pop.fire
    val upAddr = uploadCc.io.pop.payload(30 downto 8).asUInt
    val upDin  = uploadCc.io.pop.payload(7 downto 0)

    io.ctrlRd      := arbiter.io.sdramRd
    io.ctrlWr      := arbiter.io.sdramWr || fire
    io.ctrlRefresh := fetch.io.sdramRefresh
    io.ctrlAddr := Mux(fire, upAddr, arbiter.io.sdramAddr)
    io.ctrlDin  := Mux(fire, upDin,  arbiter.io.sdramDin)
    io.uploadFire := fire
  }

  fetch.io.sdramBusy      := io.ctrlBusy
  fetch.io.sdramDout      := io.ctrlDout
  fetch.io.sdramDout32    := io.ctrlDout32
  fetch.io.sdramDataReady := io.ctrlDataReady
}

object UploadSeamSim extends App {
  def run(preFix: Boolean): Int = {
    var dropped = -1
    SimConfig.compile(UploadSeamHarness(preFix)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.sdramCd.forkStimulus(period = 10)

      val mem = mutable.HashMap[Int, Int]()
      var nRd = 0; var nWr = 0; var nRf = 0; var nFire = 0
      fork { while (true) { dut.sdramCd.waitSampling(); if (dut.io.uploadFire.toBoolean) nFire += 1 } }

      dut.io.headerValid #= false
      dut.io.addrInit    #= 0
      dut.io.lenBytes    #= 0
      dut.io.byteIn      #= 0
      dut.io.byteValid   #= false
      dut.io.de          #= false
      dut.io.fetchGrant  #= false
      dut.io.fetchLine   #= 0
      dut.io.ctrlBusy      #= true
      dut.io.ctrlDout      #= 0
      dut.io.ctrlDout32    #= 0
      dut.io.ctrlDataReady #= false

      // ---- faithful sdram.v behavioral model (rd>wr priority + busy timing) ----
      fork {
        for (_ <- 0 until 30) dut.sdramCd.waitSampling()
        dut.io.ctrlBusy #= false
        var state = "idle"; var timer = 0; var op = ""; var a = 0; var d = 0
        while (true) {
          dut.sdramCd.waitSampling()
          dut.io.ctrlDataReady #= false
          state match {
            case "idle" =>
              if (dut.io.ctrlRd.toBoolean) {            // rd WINS over wr (sdram.v rd|wr)
                op = "rd"; a = dut.io.ctrlAddr.toInt; dut.io.ctrlBusy #= true
                state = "wait"; timer = 3; nRd += 1
              } else if (dut.io.ctrlWr.toBoolean) {
                op = "wr"; a = dut.io.ctrlAddr.toInt; d = dut.io.ctrlDin.toInt & 0xFF
                dut.io.ctrlBusy #= true; state = "wait"; timer = 5; nWr += 1
              } else if (dut.io.ctrlRefresh.toBoolean) {
                op = "rf"; dut.io.ctrlBusy #= true; state = "wait"; timer = 4; nRf += 1
              }
            case "wait" =>
              timer -= 1
              if (timer == 0) op match {
                case "rd" =>
                  dut.io.ctrlDout   #= BigInt(mem.getOrElse(a & 0x7fffff, 0) & 0xFF)
                  dut.io.ctrlDout32 #= BigInt(0)
                  dut.io.ctrlDataReady #= true; state = "rdDone"
                case "wr" =>
                  mem(a & 0x7fffff) = d; dut.io.ctrlBusy #= false; state = "idle"
                case "rf" =>
                  dut.io.ctrlBusy #= false; state = "idle"
              }
            case "rdDone" => dut.io.ctrlBusy #= false; state = "idle"
          }
        }
      }

      dut.clockDomain.waitSampling(50)
      var timeout = 400000   // faithful boot init walk is long (~61k+ cycles)
      while (!dut.io.bootDone.toBoolean && timeout > 0) { dut.clockDomain.waitSampling(); timeout -= 1 }
      assert(timeout > 0, "bootDone timeout")

      // ---- fetch-read contention: pulse grant every ~120 pixel cycles ----
      var contend = true
      val contender = fork {
        while (contend) {
          dut.io.fetchLine #= (dut.io.fetchLine.toInt + 1) & 0x3FF
          dut.io.fetchGrant #= true
          dut.clockDomain.waitSampling(4)
          dut.io.fetchGrant #= false
          dut.clockDomain.waitSampling(2000)   // one line-fetch per ~scanline (leaves idle SDRAM for uploads)
        }
      }

      // ---- active-video gate: realistic 640 active / 160 blank line schedule ----
      var gate = true
      val gater = fork {
        while (gate) {
          dut.io.de #= true;  dut.clockDomain.waitSampling(640)
          dut.io.de #= false; dut.clockDomain.waitSampling(160)
        }
      }

      // ---- #11330 repro: 32 back-to-back SDRAM_WRITE transactions (tile-matrix
      // pattern), gap=0 (max stress), through the bridge WHILE the real fetch engine
      // contends on the pop side (uploadCc gated by canAccept + refresh). Each txn is
      // a header + 4 bytes to base+t*4. Checks the bridge never DEADLOCKS (uploadBusy
      // stuck) and every byte lands. preFix=true = old gating; preFix=false = the
      // shipped header-FIFO bridge.
      val base = 0x100000
      val nTxn = 32
      for (t <- 0 until nTxn) {
        dut.io.addrInit    #= base + t * 4
        dut.io.lenBytes    #= 4
        dut.io.headerValid #= true
        dut.clockDomain.waitSampling()
        dut.io.headerValid #= false
        for (k <- 0 until 4) {
          dut.io.byteIn    #= (0x10 * (k + 1) + t) & 0xFF
          dut.io.byteValid #= true
          dut.clockDomain.waitSampling()
          dut.io.byteValid #= false
        }
      }
      dut.clockDomain.waitSampling(120000)  // drain
      val stuck = dut.io.uploadBusy.toBoolean
      contend = false; gate = false
      contender.join(); gater.join()

      var landed = 0
      for (t <- 0 until nTxn; k <- 0 until 4) {
        val exp = (0x10 * (k + 1) + t) & 0xFF
        if (mem.getOrElse((base + t * 4 + k) & 0x7fffff, -1) == exp) landed += 1
      }
      dropped = nTxn * 4 - landed
      println(f"[seam preFix=$preFix%-5s] 32-txn burst: landed=$landed/${nTxn * 4} DROPPED=$dropped uploadBusyStuck=$stuck | fires=$nFire rd=$nRd wr=$nWr rf=$nRf")
      if (stuck) println(f"[seam preFix=$preFix%-5s] *** DEADLOCK: uploadBusy stuck high after burst ***")
    }
    dropped
  }

  val before = run(true)
  val after  = run(false)
  println(s"[seam] SUMMARY: preFix(old gating) DROPPED=$before ; postFix(look-ahead) DROPPED=$after")
  if (before > 0 && after == 0)
    println("[seam] PROOF OK: reproduces drops before fix, ZERO drops after fix")
  else
    println(s"[seam] PROOF INCOMPLETE: before=$before after=$after (expected before>0, after=0)")
}
