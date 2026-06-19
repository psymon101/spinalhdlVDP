package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** SDRAM-BANDWIDTH-146 Checkpoint A.2 — CONCURRENT upload+read co-sim at the REAL
  * 40.5 MHz SDRAM clock.
  *
  * Reproduces test07's lower-frame raggedness digitally: the real BitmapRowFetch
  * (display read, client 1) runs a full frame through the REAL SdramArbiter + REAL
  * sdram.v controller while a host upload write generator (client 4, gated exactly
  * like TopTang20kHdmi's uploadPopArea: pops only when !ctrl.busy && !bitmap-busy,
  * plus the optional UPLOAD_MIN_GAP rate cap) saturates the bus with back-buffer
  * writes. Measures, per display row, the SDRAM cycles the row's fetch takes
  * (fetchActive high→low) under contention, vs the per-line budget. A row whose
  * fetch exceeds the budget falls behind → lower-frame starvation.
  *
  * REAL clock: clk_sdram = 40.5 MHz (tang20k_hdmi.sdc; lowered 64.8→40.5 under
  * #11197), clk_pixel = 25.2 MHz. Line = 800 px = 800*(40.5/25.2) ≈ 1286 SDRAM cyc.
  *
  * A/B: UPLOAD_MIN_GAP = 0 (no cap, current main behaviour for the contention) vs
  * 12 (the fix). Expect cap-off → fetch duration >> budget (starved) + high
  * writes/row; cap-on → fetch fits + fewer writes/row (host throttled).
  */
case class BitmapBwDut(sdramCd: ClockDomain, uploadMinGap: Int) extends Component {
  val io = new Bundle {
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
    val bootDone     = out Bool()
    val resetn       = in  Bool()
    val uploadActive = in  Bool()      // enable the concurrent host-write generator
    val ctrlBusy     = out Bool()
    val fetchActive  = out Bool()      // bitmap row fetch busy (sdram domain)
    val uploadFire   = out Bool()      // an upload write was issued this cycle
    // Preload path (testbench writes/reads the SDRAM directly) for the content check.
    val uploadMode   = in  Bool()
    val uplWr        = in  Bool()
    val uplRd        = in  Bool()
    val uplAddr      = in  UInt(23 bits)
    val uplDin       = in  Bits(8 bits)
    val ctrlDout32   = out Bits(32 bits)
    val ctrlDataReady = out Bool()
    val bitmapByte   = out Bits(8 bits)
    val attrByte     = out Bits(8 bits)
  }

  val bb = SdramWithModel(freq = 1000000)
  bb.io.clk       := sdramCd.readClockWire
  bb.io.clk_sdram := !sdramCd.readClockWire
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
  io.bootDone     := bitmapRowFetch.io.bootDone
  io.ctrlBusy     := bb.io.busy
  io.fetchActive  := bitmapRowFetch.io.sdramActive
  io.ctrlDout32   := bb.io.dout32
  io.ctrlDataReady := bb.io.data_ready
  io.bitmapByte   := bitmapRowFetch.io.bitmapByte
  io.attrByte     := bitmapRowFetch.io.attrByte

  // Scheduler (pixel domain), VdpTop L0 slot map; bitmap relies on activeBit override.
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
  slot(0, True, 0, hTotal - 1, hTotal - 1)
  slot(1, True, 0, 0, 399)
  for (i <- 2 until 8) slot(i, False, 0, 0, 0)

  val sArea = new ClockingArea(sdramCd) {
    val arbiter = SdramArbiter(clientCount = 6, addrWidth = 23, dataWidth = 8, burstRefresh = false)
    val activeBit = BufferCC(bitmapRowFetch.io.sdramActive, False)
    val grantBundle = BufferCC(
      scheduler.io.grantClientId.asBits ## scheduler.io.slotValid.asBits ## scheduler.io.grant.asBits,
      B(0, 4 bits))
    val grantIdSync = grantBundle(3 downto 2).asUInt
    val baseGrantId = Mux(activeBit, U(1, 3 bits), grantIdSync.resize(3))

    // --- Concurrent host upload generator (client 4), mirroring uploadPopArea ---
    // bitmap-busy look-ahead defer (same as TopTang: current + next-cycle request).
    val bitmapBusy = bitmapRowFetch.io.sdramRd || bitmapRowFetch.io.sdramRdNext ||
                     bitmapRowFetch.io.sdramWr || bitmapRowFetch.io.sdramWrNext
    val cooldown = Reg(UInt(log2Up(uploadMinGap + 2) bits)) init 0
    when(cooldown =/= 0) { cooldown := cooldown - 1 }
    val gapOk = if (uploadMinGap > 0) cooldown === 0 else True
    // Host saturating: a write is always pending; it fires whenever the gate allows.
    // Suppressed during preload (uploadMode) — testbench owns the controller then.
    val uploadFire = io.uploadActive && !io.uploadMode && !bb.io.busy && !bitmapBusy && gapOk
    val backAddr = Reg(UInt(23 bits)) init 0x300000
    when(uploadFire) {
      backAddr := backAddr + 1
      if (uploadMinGap > 0) cooldown := U(uploadMinGap, cooldown.getWidth bits)
    }

    arbiter.io.slotValid     := grantBundle(1)
    arbiter.io.grant         := grantBundle(0)
    // Upload override: grant client 4 when an upload write fires (only when
    // !bitmapBusy, so it never steals a fetch grant) — exactly as TopTang.
    arbiter.io.grantClientId := Mux(uploadFire, U(4, 3 bits), baseGrantId)
    arbiter.io.vblankActive  := False
    arbiter.io.clientRd(1)   := bitmapRowFetch.io.sdramRd
    arbiter.io.clientWr(1)   := bitmapRowFetch.io.sdramWr
    arbiter.io.clientAddr(1) := bitmapRowFetch.io.sdramAddr
    arbiter.io.clientDin(1)  := bitmapRowFetch.io.sdramDin
    arbiter.io.clientBurstLen(1) := bitmapRowFetch.io.sdramBurstLen
    arbiter.io.clientRd(4)   := False
    arbiter.io.clientWr(4)   := uploadFire
    arbiter.io.clientAddr(4) := backAddr
    arbiter.io.clientDin(4)  := B(0xA5, 8 bits)
    for (c <- Seq(0, 2, 3, 5)) {
      arbiter.io.clientRd(c)   := False
      arbiter.io.clientWr(c)   := False
      arbiter.io.clientAddr(c) := U(0, 23 bits)
      arbiter.io.clientDin(c)  := B(0, 8 bits)
    }
    for (c <- Seq(0, 2, 3, 4, 5)) arbiter.io.clientBurstLen(c) := U(1, 4 bits)
  }

  // Controller mux: testbench preload writes/reads during uploadMode, arbiter otherwise.
  bb.io.rd      := Mux(io.uploadMode, io.uplRd,  sArea.arbiter.io.sdramRd)
  bb.io.wr      := Mux(io.uploadMode, io.uplWr,  sArea.arbiter.io.sdramWr)
  bb.io.addr    := Mux(io.uploadMode, io.uplAddr, sArea.arbiter.io.sdramAddr)
  bb.io.din     := Mux(io.uploadMode, io.uplDin, sArea.arbiter.io.sdramDin)
  bb.io.burstLen := Mux(io.uploadMode, U(1, 4 bits), sArea.arbiter.io.sdramBurstLen)

  val refreshArea = new ClockingArea(sdramCd) {
    val pending = RegInit(False)
    when(sArea.arbiter.io.refreshDue) { pending := True }
    val doRefresh = pending && !bb.io.busy && !io.uploadMode && !bitmapRowFetch.io.sdramActiveRaw
    when(doRefresh) { pending := False }
  }
  bb.io.refresh := refreshArea.doRefresh

  io.uploadFire := sArea.uploadFire
}

object BitmapConcurrentBwCosim extends App {
  val hActive = 640; val hTotal = 800
  val stride  = 512
  val base    = 0x100000
  val attrBaseV = 0x200000
  // Real timing: line = 800 px * (40.5/25.2) SDRAM cyc.
  val lineBudgetSdram = scala.math.round(800.0 * 40.5 / 25.2).toInt   // ≈ 1286

  /** Run N display rows with the concurrent upload generator on/off-capped; return
    * (avg fetch sdram-cyc/row over steady tail, lateRows, avg writes/row). */
  def run(uploadMinGap: Int, uploadActive: Boolean, label: String): Unit = {
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, uploadMinGap)
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)   // 25.2 MHz pixel
      dut.sdramCd.forkStimulus(period = 10)       // 40.5 MHz sdram
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= base; dut.io.attrBase #= attrBaseV
      dut.io.bitmapStride #= stride; dut.io.attrStride #= stride; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= false; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }

      dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      assert(t > 0, s"$label: bootDone timeout")

      // Count upload-write fires across the whole run (sdram domain).
      var totalWrites = 0L
      fork { while (true) { if (dut.io.uploadFire.toBoolean) totalWrites += 1; dut.sdramCd.waitSampling() } }

      dut.io.uploadActive #= uploadActive

      val nRows = 80
      val warmup = 16
      var sumDur = 0L; var lateRows = 0; var measured = 0
      var firstLate = -1
      // One source row per display line; prefetch 2 rows ahead. Grant at end of line.
      for (row <- 0 until nRows) {
        // walk one display line of hTotal pixel clocks; grant at hTotal-1.
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1) { dut.io.fetchLine #= (row + 2); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
        }
        // After the grant, measure how many SDRAM cycles the row fetch stays active.
        // (fetchActive rises shortly after grant, falls when the row's 160 words land.)
        var guard = 0; var dur = 0L; var seenActive = false
        while (guard < 12000) {
          val act = dut.io.fetchActive.toBoolean
          if (act) { seenActive = true; dur += 1 }
          else if (seenActive) { guard = 99999 } // fell -> row done
          dut.sdramCd.waitSampling(); guard += 1
        }
        if (row >= warmup) {
          sumDur += dur; measured += 1
          if (dur > lineBudgetSdram) { lateRows += 1; if (firstLate < 0) firstLate = row }
        }
      }
      val avgDur = sumDur.toDouble / measured
      val writesPerRow = totalWrites.toDouble / nRows
      val util = 100.0 * avgDur / lineBudgetSdram
      val onset = if (firstLate < 0) "none" else s"row $firstLate"
      println(f"[sim] $label%-26s avgFetch=$avgDur%6.0f cyc/row  (budget=$lineBudgetSdram, ${util}%.0f%%)  lateRows=$lateRows%2d/$measured%d onset=$onset  writes/row≈$writesPerRow%.0f")
    }
  }

  // CONTENT check: pre-load the FRONT buffer with a signature, then display it
  // (per-pixel compare) WHILE the host writes the BACK buffer concurrently. This
  // settles capture-chain-vs-RTL bench-independently: 0 mismatches => the RTL
  // produces pixel-perfect output under the test07 workload => the bench artifact
  // is the capture chain, not the RTL.
  def sig(a: Int): Int = ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
  def runContent(): Unit = {
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, 0)   // no cap — worst case for contention
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)
      dut.sdramCd.forkStimulus(period = 10)
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= base; dut.io.attrBase #= attrBaseV
      dut.io.bitmapStride #= stride; dut.io.attrStride #= stride; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= true; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }

      val nLines = 16
      def wrByte(addr: Int, data: Int): Unit = {
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
        dut.io.uplAddr #= addr; dut.io.uplDin #= data; dut.io.uplWr #= true
        var g = 20; while (!dut.io.ctrlBusy.toBoolean && g > 0) { dut.sdramCd.waitSampling(); g -= 1 }
        dut.io.uplWr #= false
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      }
      for (line <- 0 until nLines; p <- 0 until 320) { val a = base + line * stride + p; wrByte(a, sig(a)) }
      for (line <- 0 until nLines; p <- 0 until 320) { val a = attrBaseV + line * stride + p; wrByte(a, sig(a)) }

      // Switch to fetch; start the concurrent BACK-buffer write generator.
      dut.io.uploadMode #= false
      dut.sdramCd.waitSampling(4); dut.clockDomain.waitSampling(4)
      dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      dut.io.uploadActive #= true   // host hammers the back buffer during display

      var mism = 0; var attrMism = 0; var checks = 0
      val firsts = scala.collection.mutable.ArrayBuffer[String]()
      val warmup = 8; val nScreen = warmup + 6
      for (screenLine <- 0 until nScreen) {
        val srcRow = screenLine >> 1
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1 && (screenLine % 2 == 1)) { dut.io.fetchLine #= (screenLine + 5); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
          if (screenLine >= warmup && h < hActive && (h % 2 == 0)) {
            sleep(1)
            val pixel = h / 2
            val got = dut.io.bitmapByte.toInt; val gotA = dut.io.attrByte.toInt
            val exp = sig(base + srcRow * stride + pixel); val expA = sig(attrBaseV + srcRow * stride + pixel)
            checks += 1
            if (got != exp) { mism += 1; if (firsts.size < 8) firsts += f"BMP scr=$screenLine row=$srcRow px=$pixel got=0x$got%02X exp=0x$exp%02X" }
            if (gotA != expA) attrMism += 1
          }
        }
      }
      println(f"[sim] CONTENT-under-concurrent-write: checks=$checks bitmapMismatch=$mism attrMismatch=$attrMism")
      firsts.foreach(m => println(s"[sim]   $m"))
      if (mism == 0 && attrMism == 0)
        println("[sim] CONTENT PASS — RTL renders pixel-perfect WHILE the back buffer is hammered => artifact is NOT the RTL (capture chain).")
      else
        println("[sim] CONTENT FAIL — concurrent writes corrupt displayed pixels => real RTL issue.")
    }
  }

  println(f"[sim] REAL clock 40.5 MHz; line budget = $lineBudgetSdram%d SDRAM cyc/row")
  run(0,  false, "baseline read-only")
  run(0,  true,  "concurrent, NO cap")
  run(12, true,  "concurrent, cap gap=12")
  runContent()
  println("[sim] BitmapConcurrentBwCosim: done.")
}
