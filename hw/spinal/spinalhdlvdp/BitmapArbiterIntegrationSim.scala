package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** RGB565-FULLFRAME-132 B.2 — TopTang-level concurrent cosim (Option B, #12314).
  *
  * Uses the REAL sdram.v controller + behavioral SDRAM chip (SdramWithModel
  * BlackBox, defined in SdramCoSimProbe.scala) instead of a hand-written model, so
  * read latency, single-outstanding busy, refresh, and the dual-clock handshake are
  * the production controller — no testbench-model ambiguity. The bitmap client's
  * reads reach the controller ONLY through the real SdramArbiter + the activeBit ->
  * grantClientId override wired exactly as TopTang20kHdmi. A FetchSlotScheduler is
  * present (with VdpTop's L0 slot map) but the bitmap client has NO slot — it relies
  * solely on the override, which is the question under test.
  *
  * Phase 1 (uploadMode=1): the testbench writes a per-byte signature pattern into
  * SDRAM through the real controller for the bitmap + attr regions of N lines.
  * Phase 2 (uploadMode=0): enable bitmap directcolor, run N display lines with
  * prefetch-at-hActive, sample bitmapByte/attrByte during the active region and
  * compare to each line's source. PASS = zero mismatches => the activeBit override
  * gives the bitmap enough bus through the real controller; no dedicated slot needed.
  */
case class BitmapCosimDut(sdramCd: ClockDomain) extends Component {
  val io = new Bundle {
    // Pixel-domain bitmap stimulus.
    val col          = in  UInt(10 bits)
    val fetchLine    = in  UInt(10 bits)
    val fetchGrant   = in  Bool()
    val enable       = in  Bool()
    val directColor  = in  Bool()
    val tileBootDone = in  Bool()
    val bitmapBase   = in  UInt(23 bits)
    val attrBase     = in  UInt(23 bits)
    val bitmapStride = in  UInt(16 bits)
    val attrStride   = in  UInt(16 bits)
    val bitmapHeight = in  UInt(10 bits)
    val bitmapByte   = out Bits(8 bits)
    val attrByte     = out Bits(8 bits)
    val bootDone     = out Bool()
    // SDRAM controller reset + upload path (driven in the SDRAM clock domain).
    val resetn       = in  Bool()
    val uploadMode   = in  Bool()
    val uplWr        = in  Bool()
    val uplRd        = in  Bool()
    val uplAddr      = in  UInt(23 bits)
    val uplDin       = in  Bits(8 bits)
    val ctrlBusy     = out Bool()
    val ctrlDout32   = out Bits(32 bits)
    val ctrlDataReady = out Bool()
    val l0Rd         = in  Bool()
    val refreshEn    = in  Bool()   // gate AUTO_REFRESH (debug: isolate data path vs throughput)
    // Debug.
    val grantClientId = out UInt(3 bits)
    val activeBitDbg  = out Bool()
  }

  // Real sdram.v + chip model. FREQ small so the 200us init counter is short.
  val bb = SdramWithModel(freq = 1000000)
  bb.io.clk       := sdramCd.readClockWire
  bb.io.clk_sdram := !sdramCd.readClockWire   // 180-deg companion
  bb.io.resetn    := io.resetn

  val bitmapRowFetch = BitmapRowFetch(sdramCd, skipSdramInit = true)
  bitmapRowFetch.io.fetchGrant   := io.fetchGrant
  bitmapRowFetch.io.fetchLine    := io.fetchLine
  bitmapRowFetch.io.col          := io.col
  bitmapRowFetch.io.enable       := io.enable
  bitmapRowFetch.io.directColor  := io.directColor
  bitmapRowFetch.io.tileBootDone := io.tileBootDone
  bitmapRowFetch.io.bitmapBase   := io.bitmapBase
  bitmapRowFetch.io.attrBase     := io.attrBase
  bitmapRowFetch.io.bitmapStride := io.bitmapStride
  bitmapRowFetch.io.attrStride   := io.attrStride
  bitmapRowFetch.io.bitmapHeight := io.bitmapHeight
  bitmapRowFetch.io.sdramDout      := bb.io.dout
  bitmapRowFetch.io.sdramDout32    := bb.io.dout32
  bitmapRowFetch.io.sdramDataReady := bb.io.data_ready
  bitmapRowFetch.io.sdramBusy      := bb.io.busy
  io.bitmapByte := bitmapRowFetch.io.bitmapByte
  io.attrByte   := bitmapRowFetch.io.attrByte
  io.bootDone   := bitmapRowFetch.io.bootDone
  io.ctrlBusy     := bb.io.busy
  io.ctrlDout32   := bb.io.dout32
  io.ctrlDataReady := bb.io.data_ready

  // Real scheduler (pixel domain) with VdpTop's L0 slot map; bitmap (client 1) has
  // NO slot — it relies on the activeBit override.
  val hTotal = 800
  val scheduler = FetchSlotScheduler(slotCount = 8)
  scheduler.io.hCounter  := io.col
  scheduler.io.lineStart := io.col === 0
  def slot(i: Int, en: Bool, cid: Int, s: Int, e: Int): Unit = {
    scheduler.io.schedule(i).enabled  := en
    scheduler.io.schedule(i).clientId := U(cid, 2 bits)
    scheduler.io.schedule(i).startH   := U(s, 10 bits)
    scheduler.io.schedule(i).endH     := U(e, 10 bits)
  }
  // Realistic default slot map: L0 start (hTotal-1) + L0 burst [0,399], L1 DISABLED
  // — i.e. the [400,639] slotValid gap CyanPeak flagged (#12321). The bitmap (client
  // 1) has no slot; it relies on the activeBit override. (Separately verified: with
  // ALL slots disabled — slotValid never asserted — the bitmap still renders 0
  // mismatches, proving the override is independent of any qualifying slotValid.)
  slot(0, True, 0, hTotal - 1, hTotal - 1)
  slot(1, True, 0, 0, 399)
  for (i <- 2 until 8) slot(i, False, 0, 0, 0)

  // Arbiter + activeBit override in the SDRAM clock domain (mirrors sdramArbArea).
  val sArea = new ClockingArea(sdramCd) {
    val arbiter = SdramArbiter(clientCount = 6, addrWidth = 23, dataWidth = 8, burstRefresh = false)
    val activeBit = BufferCC(bitmapRowFetch.io.sdramActive, False)
    val grantBundle = BufferCC(
      scheduler.io.grantClientId.asBits ## scheduler.io.slotValid.asBits ## scheduler.io.grant.asBits,
      B(0, 4 bits))
    val grantIdSync = grantBundle(3 downto 2).asUInt
    val baseGrantId = Mux(activeBit, U(1, 3 bits), grantIdSync.resize(3))
    arbiter.io.slotValid     := grantBundle(1)
    arbiter.io.grant         := grantBundle(0)
    arbiter.io.grantClientId := baseGrantId
    arbiter.io.vblankActive  := False
    arbiter.io.clientRd(0)   := io.l0Rd
    arbiter.io.clientWr(0)   := False
    arbiter.io.clientAddr(0) := U(0, 23 bits)
    arbiter.io.clientDin(0)  := B(0, 8 bits)
    arbiter.io.clientRd(1)   := bitmapRowFetch.io.sdramRd
    arbiter.io.clientWr(1)   := bitmapRowFetch.io.sdramWr
    arbiter.io.clientAddr(1) := bitmapRowFetch.io.sdramAddr
    arbiter.io.clientDin(1)  := bitmapRowFetch.io.sdramDin
    // RGB565-FULLFRAME-132 Phase 0: bitmap client (1) requests bursts; all others single.
    arbiter.io.clientBurstLen(1) := bitmapRowFetch.io.sdramBurstLen
    for (c <- Seq(0, 2, 3, 4, 5)) arbiter.io.clientBurstLen(c) := U(1, 4 bits)
    for (c <- 2 until 6) {
      arbiter.io.clientRd(c)   := False
      arbiter.io.clientWr(c)   := False
      arbiter.io.clientAddr(c) := U(0, 23 bits)
      arbiter.io.clientDin(c)  := B(0, 8 bits)
    }
  }

  // Controller request mux: testbench-driven writes during upload, arbiter during fetch.
  bb.io.rd      := Mux(io.uploadMode, io.uplRd,     sArea.arbiter.io.sdramRd)
  bb.io.wr      := Mux(io.uploadMode, io.uplWr,     sArea.arbiter.io.sdramWr)
  bb.io.addr    := Mux(io.uploadMode, io.uplAddr,   sArea.arbiter.io.sdramAddr)
  bb.io.din     := Mux(io.uploadMode, io.uplDin,    sArea.arbiter.io.sdramDin)
  // RGB565-FULLFRAME-132 Phase 0: upload writes are single transactions; fetch reads
  // carry the arbitrated client burst length (bitmap = 8). The controller latches it
  // at the rd pulse, so the upload-mode value is irrelevant during writes.
  bb.io.burstLen := Mux(io.uploadMode, U(1, 4 bits), sArea.arbiter.io.sdramBurstLen)
  // Refresh: the arbiter owns the cadence (refreshDue); model the real-HW behaviour
  // where AUTO_REFRESH is inserted at a SAFE boundary. RGB565-FULLFRAME-132 Phase 0:
  // refresh is held pending and issued only while the bitmap fetch FSM is IDLE between
  // source rows (sdramActiveR low) — never mid-row. This matches both the real system
  // (refresh is sequenced by the tile engine's FSM + arbiter serialization, NOT a
  // free-running !busy strobe that could steal the registered-cmdRd cycle the bitmap
  // FSM is about to drive) and CyanPeak's #12354 guidance (insert at burst/row
  // boundaries). With the burst design the inter-row idle is ~1340 of every ~1600
  // pixel-clocks — far more than the ~593-cycle refresh cadence needs — so refresh is
  // always serviced on time while contending for the same bus the fetch uses. (Gating
  // on a combinational !busy strobe instead races the FSM's 1-cycle-delayed cmdRd and
  // silently drops a read — the artifact that made the pre-burst refresh-ON cosim fail.)
  val refreshArea = new ClockingArea(sdramCd) {
    val pending = RegInit(False)
    when(sArea.arbiter.io.refreshDue) { pending := True }
    val doRefresh = pending && !bb.io.busy && !io.uploadMode && io.refreshEn &&
                    !bitmapRowFetch.io.sdramActiveRaw
    when(doRefresh) { pending := False }
  }
  bb.io.refresh := refreshArea.doRefresh

  io.grantClientId := sArea.baseGrantId
  io.activeBitDbg  := sArea.activeBit
}

object BitmapArbiterIntegrationSim extends App {
  val hActive = 640; val hTotal = 800
  val stride  = 512
  val base    = 0x100000
  val attrBaseV = 0x200000
  val nLines  = 13  // source rows uploaded (covers the 2-row-ahead lookahead + warmup)
  def sig(a: Int): Int = ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
  def srcByte(line: Int, pixel: Int): Int = sig(base + line * stride + pixel)
  def attrOf(line: Int, pixel: Int): Int  = sig(attrBaseV + line * stride + pixel)

  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile {
      val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
      BitmapCosimDut(sdramCd)
    }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 16)   // 25.2 MHz pixel
    dut.sdramCd.forkStimulus(period = 10)       // 40.5 MHz sdram (real PLL: 27*3/2; SDC clk_sdram period 24.691)

    dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
    dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
    dut.io.bitmapBase #= base; dut.io.attrBase #= attrBaseV
    dut.io.bitmapStride #= stride; dut.io.attrStride #= stride; dut.io.bitmapHeight #= 240
    dut.io.resetn #= false
    dut.io.uploadMode #= true; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
    dut.io.l0Rd #= false
    val refreshEnabled = sys.env.getOrElse("REFRESH", "1") != "0"
    dut.io.refreshEn #= refreshEnabled
    println(s"[sim] refreshEnabled=$refreshEnabled")

    dut.sdramCd.waitSampling(4)
    dut.io.resetn #= true
    // Wait for controller init (busy clears).
    var i = 0
    while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }
    assert(!dut.io.ctrlBusy.toBoolean, "controller never left init")
    println(s"[sim] controller init done after $i sdram cycles")

    // Phase 1: upload the signature pattern (bitmap + attr) for nLines lines.
    def wrByte(addr: Int, data: Int): Unit = {
      while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      dut.io.uplAddr #= addr; dut.io.uplDin #= data; dut.io.uplWr #= true
      // Hold wr until the controller accepts it (busy rises), else a write issued
      // one cycle before the controller is ready is silently dropped.
      var g = 20
      while (!dut.io.ctrlBusy.toBoolean && g > 0) { dut.sdramCd.waitSampling(); g -= 1 }
      dut.io.uplWr #= false
      while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
    }
    // Two passes (bitmap bank0, then attr bank1) — alternating banks per write was
    // losing the bank-1 writes.
    for (line <- 0 until nLines; p <- 0 until 320) {
      val ba = base + line * stride + p; wrByte(ba, sig(ba))
    }
    for (line <- 0 until nLines; p <- 0 until 320) {
      val aa = attrBaseV + line * stride + p; wrByte(aa, sig(aa))
    }
    println(s"[sim] uploaded ${nLines * 320 * 2} bytes")

    // Read-back verification through the real controller (still upload mode).
    def rdWord(addr: Int): Long = {
      while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      dut.io.uplAddr #= addr; dut.io.uplRd #= true
      var g = 20
      while (!dut.io.ctrlBusy.toBoolean && g > 0) { dut.sdramCd.waitSampling(); g -= 1 }
      dut.io.uplRd #= false
      var h = 30; var v = 0L
      while (h > 0) { if (dut.io.ctrlDataReady.toBoolean) v = dut.io.ctrlDout32.toLong & 0xFFFFFFFFL; dut.sdramCd.waitSampling(); h -= 1 }
      while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      v
    }
    for (a <- Seq(base, base + 512, attrBaseV, attrBaseV + 512)) {
      val w = rdWord(a)
      val expW = (sig(a) | (sig(a+1) << 8) | (sig(a+2) << 16) | (sig(a+3) << 24)).toLong & 0xFFFFFFFFL
      println(f"[rb] addr=0x$a%06X dout32=0x$w%08X expect=0x$expW%08X ${if (w == expW) "OK" else "<<< WRONG"}")
    }

    // Phase 2: switch to fetch, enable bitmap directcolor.
    dut.io.uploadMode #= false
    dut.sdramCd.waitSampling(4); dut.clockDomain.waitSampling(4)
    dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
    var t = 8000
    while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
    assert(t > 0, "bootDone timeout")

    var mismatches = 0; var checks = 0; var attrMismatches = 0
    val firstMismatch = scala.collection.mutable.ArrayBuffer[String]()
    val firstAttr = scala.collection.mutable.ArrayBuffer[String]()
    // OPTION B (#12346): grant + bank-advance ONCE PER SOURCE ROW (every 2 output
    // lines), TRIPLE buffer. Each row is fetched TWO rows ahead (fetchLine =
    // screenLine+5 → row R+2), so the fetch has up to ~2 source-row windows
    // (~3200 pixel-clocks) to complete — ample slack for AUTO_REFRESH contention.
    // Grant fires at hTotal-1 of the ODD output line (row boundary). The first
    // `warmup` screen lines fill the deeper pipeline and are not checked.
    val warmup  = 8
    val nScreen = warmup + 8
    dut.io.fetchGrant #= false; dut.io.fetchLine #= 0

    for (screenLine <- 0 until nScreen) {
      val srcRow = screenLine >> 1
      for (h <- 0 until hTotal) {
        dut.io.col #= h
        if (h == 4) dut.io.fetchGrant #= false
        if (h == hTotal - 1 && (screenLine % 2 == 1)) {
          dut.io.fetchLine #= (screenLine + 5); dut.io.fetchGrant #= true
        }
        dut.clockDomain.waitSampling()
        if (screenLine >= warmup && h < hActive && (h % 2 == 0)) {
          sleep(1)
          val got = dut.io.bitmapByte.toInt; val gotA = dut.io.attrByte.toInt
          val pixel = h / 2
          val exp = srcByte(srcRow, pixel); val expA = attrOf(srcRow, pixel)
          checks += 1
          if (got != exp) {
            mismatches += 1
            if (firstMismatch.size < 8)
              firstMismatch += f"BMP screen=$screenLine row=$srcRow px=$pixel: got=0x$got%02X exp=0x$exp%02X"
          }
          if (gotA != expA) {
            attrMismatches += 1
            if (firstAttr.size < 10)
              firstAttr += f"ATTR screen=$screenLine row=$srcRow px=$pixel: got=0x$gotA%02X exp=0x$expA%02X"
          }
        }
      }
    }
    println(f"[sim] cosim checks=$checks bitmapMismatches=$mismatches attrMismatches=$attrMismatches grantOverflow=${dut.bitmapRowFetch.sd.grantOverflow.toInt}")
    firstMismatch.foreach(m => println(s"[sim]   $m"))
    firstAttr.foreach(m => println(s"[sim]   $m"))
    if (mismatches == 0 && attrMismatches == 0)
      println("[sim] BitmapArbiterIntegrationSim: PASS — real sdram.v + arbiter; activeBit override sufficient, no dedicated slot needed")
    else
      println("[sim] BitmapArbiterIntegrationSim: FAIL — render wrong through real controller/arbiter")
  }
}
