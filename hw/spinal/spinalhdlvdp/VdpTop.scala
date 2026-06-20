package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib.BufferCC

case class VdpTop(sdramCd: ClockDomain = null, enableL1Fetch: Boolean = true, withExtraRasterTriggers: Boolean = false, enableL2L3: Boolean = false,
                  scaleCtrlInit:   Int = 0,
                  logicWidthInit:  Int = 640,
                  logicHeightInit: Int = 480,
                  borderCtrlInit:  Int = 0,
                  // HAM-DECODER-171 CP-D: shared bitmap write-pipeline alignment. MEASURED
                  // = 1 column (CyanPeak #12998: with the odd-column HAM step, the combinational
                  // rgb888 for source k is ready at cols 2k+1,2k+2 → 1-col write delay lands it
                  // at dcLineBuf[2k,2k+1]). Fixes HAM AND the latent RGB565 directcolor 1-col
                  // shift (shared dcLineBuf carrier). 0 = pre-fix legacy. Default now 1 (aligned).
                  bitmapWritePipelineDelay: Int = 1,
                  // HAM-DECODER-171 CP-D Option-1 sweep: first display column at which the HAM
                  // decoder begins stepping (once per source pixel, every 2 cols thereafter).
                  // The decoder must NOT step until bmByteSel holds the first VALID source byte
                  // (col/2 read + readSync + rdLaneD latency); stepping earlier consumes stale
                  // bytes and corrupts the per-line hold (worst for modify-led rows). =1 is the
                  // prior (broken) behavior. Swept in HamIntegrationSim to find the real latency.
                  hamStepStart: Int = 1) extends Component {
  // BronzeGate #9366 Path A: PlanarLineFetch's row-fetch FSM is migrated
  // into the SDRAM clock domain. When `sdramCd` is null (sim-default),
  // use the current pixel ClockDomain so single-clock sims keep working;
  // top-level integrations (TopTang20kHdmi, Hdmi720pMode0ProofTop) pass
  // the real `sdramClockDomain` so the FSM runs natively on the SDRAM
  // side.
  private val effectiveSdramCd: ClockDomain =
    if (sdramCd != null) sdramCd else ClockDomain.current
  val io = new Bundle {
    val hsync   = out Bool()
    val vsync   = out Bool()
    val de      = out Bool()
    val red     = out Bits(8 bits)
    val green   = out Bits(8 bits)
    val blue    = out Bits(8 bits)
    val x       = out UInt(10 bits)
    val y       = out UInt(10 bits)
    val layer0ScrollX = in UInt(10 bits)
    val layer0ScrollY = in UInt(10 bits)
    val layer1ScrollX = in UInt(10 bits)
    val layer1ScrollY = in UInt(10 bits)
    // Task 48 — Four-Layer Compositor Expansion: L2/L3 are simple
    // BasicPatternSource layers with global-only scroll (no per-column
    // scroll tables, no LinestateStore widening for L2/L3).
    val layer2ScrollX = in UInt(10 bits)
    val layer2ScrollY = in UInt(10 bits)
    val layer3ScrollX = in UInt(10 bits)
    val layer3ScrollY = in UInt(10 bits)
    // R2 sprite descriptors. Four descriptors total; SpriteEvaluator selects up
    // to two visible per line via priority-on-index. `patternIdx` picks pattern
    // 0 (sprite0Pattern) or 1 (sprite1Pattern).
    val sprite0X = in UInt(10 bits)
    val sprite0Y = in UInt(10 bits)
    val sprite0Enabled = in Bool()
    val sprite0PatternIdx = in UInt(1 bit)
    val sprite1X = in UInt(10 bits)
    val sprite1Y = in UInt(10 bits)
    val sprite1Enabled = in Bool()
    val sprite1PatternIdx = in UInt(1 bit)
    val sprite2X = in UInt(10 bits)
    val sprite2Y = in UInt(10 bits)
    val sprite2Enabled = in Bool()
    val sprite2PatternIdx = in UInt(1 bit)
    val sprite3X = in UInt(10 bits)
    val sprite3Y = in UInt(10 bits)
    val sprite3Enabled = in Bool()
    val sprite3PatternIdx = in UInt(1 bit)

    // R2 diagnostic: sprite-per-line overflow flag (sticky within line).
    val spriteOverflow = out Bool()
    // VDP-SOFT-RESET-135: live SOFT_RESET_BUSY status for the i80 0x0310
    // readback. High from the cycle a soft reset is accepted until the
    // bounded reset sequence completes (host polls this; i80 register reads
    // are otherwise last-write loopback). See the soft-reset controller below.
    val softResetBusy = out Bool()
    // VDP-SOFT-RESET-135 #3: SDRAM zero-fill stage handshake to TopTang. After
    // the on-chip Mem clear sweep, the controller raises `sdramFillStart` (level)
    // and holds busy until TopTang's sdram-domain fill FSM returns
    // `sdramFillDone` (both crossed by BufferCC in TopTang). On single-clock
    // sims with no fill engine, tie sdramFillDone high so the stage passes through.
    val sdramFillStart = out Bool()
    val sdramFillDone  = in  Bool() default True
    // R5: unified register-write bus. Replaces the raw lsWrite* ports.
    //   0x0000-0x01DF  linestate prepare (addr low 9 bits = line; data low 12 bits = {l0en, l1en, l0scrollX[9:0]})
    //   0x0300         LAYER_ENABLE (data[0]=L0, data[1]=L1, data[2]=sprite) — global override
    //   0x0400-0x05FF  copper program RAM (host uploads program here)
    //   (other ranges reserved for stages 5+)
    // Task 32b: unified register bus — replaces the prior ad-hoc
    // regWriteAddr/Data/Enable inputs with the Mode0RegBus bundle.
    val regBus = in (Mode0RegBus())

    // R4.1b stage 3 / R4.1d Checkpoint A: tile decode mode select out to the
    // SDRAM fetch engine. 2-bit field encoding:
    //   0x00 = packed 4bpp (R4 baseline)
    //   0x01 = NES-style 2bpp planar (R4.1b)
    //   0x02 = Amiga-style shuffled/bitplane (R4.1d)
    //   0x03 = reserved
    // The latched register is inside VdpTop and safe-boundary-committed to
    // hCounter===0. TopTang routes this to SdramTileAttributeFetch.tileDecodeMode.
    val layer0TileDecodeMode = out Bits(2 bits)

    // R4.1c: attribute-pack mode select (VDP_ATTR_MODE @ 0x0312).
    //   bit 0: 0 = linear 1:1 (R4), 1 = NES-style 2×2 packing
    // Safe-boundary-committed to hCounter===0. Routed to
    // SdramTileAttributeFetch.attributeMode.
    val layer0AttributeMode  = out Bits(1 bits)

    // Task 15 Layer-0 SDRAM source interface.
    //   - layer0UseSdram routes the external SDRAM-backed pixel into L0
    //     instead of the on-chip BasicPatternSource (for the switchable
    //     comparison path).
    //   - layer0SdramPixel comes from SdramTileFetch.io.pixelIndex.
    //   - layer0Fetch* are outputs that drive the external fetch engine. The
    //     raster owner decides the scroll/line/pixelAddr so the fetch contract
    //     stays at the VdpTop boundary.
    val layer0UseSdram        = in Bool()
    // R4: widened SDRAM-backed L0 interface.
    //   - pixel index widens from 3bpp (Task-15) to 4bpp
    //   - paletteBank[3] picks one of 8 palette banks (drives top bits of
    //     palette address)
    //   - priority=1 means this L0 pixel wins over L1 (priority-aware composite)
    val layer0SdramPixel      = in Bits(4 bits)
    val layer0SdramBank       = in UInt(3 bits)
    val layer0SdramPriority   = in Bool()
    // Test-pattern override for hardware validation (bypasses both SDRAM and
    // on-chip BasicPatternSource so standard validation patterns are always
    // available regardless of fetch-engine state).
    val layer0TestPatternSelect = in UInt(3 bits)
    val layer0TestPatternEnable = in Bool()

    // Task 56 Checkpoint A — L1 SDRAM source interface (mirrors L0).
    //
    // Coding authorized per CyanPeak audit PASS #9683 on artifact #9678.
    // CyanPeak's correction: L1 fetch engine uses sdramArbiter clientId=3
    // (clientId=1 is occupied by Task 44b bitmapRowFetch).
    //
    // For Checkpoint A only the *plumbing* lands: VdpTop accepts the
    // L1 SDRAM inputs and muxes them into the compositor in place of
    // (or alongside) the existing on-chip BasicPatternSource L1 path.
    // Top-level ties these inputs to default-off until Checkpoint B
    // instantiates the second SdramTileAttributeFetch engine.
    val layer1UseSdram          = in Bool()
    val layer1SdramPixel        = in Bits(4 bits)
    val layer1SdramBank         = in UInt(3 bits)
    val layer1SdramPriority     = in Bool()
    // R4: scheduler outputs exposed so the top-level can wire them into the
    // new SdramTileAttributeFetch engine (which accepts grant / slotValid /
    // preAnnounce instead of the legacy level-based fetchStart).
    val layer0FetchStart      = out Bool()
    val layer0FetchGrant      = out Bool()
    val layer0FetchSlotValid  = out Bool()
    val layer0FetchPreAnnounce = out Bool()
    // Task 30: scheduler grantClientId exposed so the top-level SDRAM
    // arbiter can mux between fetch clients.
    val layer0FetchGrantClientId = out UInt(2 bits)
    val layer0FetchLine       = out UInt(10 bits)
    val layer0FetchScrollX    = out UInt(10 bits)
    val layer0FetchScrollY    = out UInt(10 bits)
    val layer0FetchPixelAddr  = out UInt(10 bits)

    // Task 56 Checkpoint B (#9678 / #9693): L1 fetch scheduler outputs.
    // Mirror the L0 surface so a second SdramTileAttributeFetch engine
    // (clientId=3) can consume scheduler grants from slots 3/4. Driven
    // off scheduler.io.grant gated on hCounter==hTotal-1 for the grant
    // edge, and the layer1 scroll latches mirror the L0 earlyLatchStrobe
    // pattern with `layer1Scroll*` substituted for `layer0Scroll*`.
    // `layer1FetchEnable` follows `layer1UseSdram` (gates scheduler
    // slots 3/4 and ANDed with the grant pulse so the FSM never starts
    // when the engine is inactive).
    val layer1FetchGrant        = out Bool()
    val layer1FetchSlotValid    = out Bool()
    val layer1FetchPreAnnounce  = out Bool()
    val layer1FetchGrantClientId = out UInt(2 bits)
    val layer1FetchLine         = out UInt(10 bits)
    val layer1FetchScrollX      = out UInt(10 bits)
    val layer1FetchScrollY      = out UInt(10 bits)
    val layer1FetchPixelAddr    = out UInt(10 bits)

    // Task 44b — bitmap SDRAM-fetch coupling. When `bitmapEnable=1`,
    // BitmapFetch's `bitmapByte` / `attrByte` inputs are sourced from
    // these incoming ports instead of the Task 44 CP-B deterministic
    // test generator. The top-level wires these to a `BitmapRowFetch`
    // instance whose SDRAM bus runs through arbiter client 1.
    val bitmapSdramCol        = out UInt(10 bits)
    val bitmapSdramFetchLine  = out UInt(10 bits)
    val bitmapSdramFetchGrant = out Bool()
    val bitmapSdramByte       = in  Bits(8 bits)
    val bitmapSdramAttrByte   = in  Bits(8 bits)
    val bitmapModeActive      = out Bool()   // Task 44b: BITMAP_CTRL[0]
    val bitmapDirectColor     = out Bool()   // CP-1c: BITMAP_CTRL enable & bpp=0b10 (RGB565)
    // BITMAP-PLUMB-129 (#12169/#12205): host-programmable bitmap/attr fetch
    // geometry. Decoded from 0x0351..0x0357 with the standard safe-boundary
    // shadow/pend/commit pattern below; the top level routes these into
    // BitmapRowFetch (replacing its formerly hardcoded 0x3000/0x4000/512/240).
    val bitmapBase            = out UInt(23 bits)  // 0x0351 LO + 0x0352 HI
    val attrBase              = out UInt(23 bits)  // 0x0353 LO + 0x0354 HI
    val bitmapStride          = out UInt(16 bits)  // 0x0355 (direct-color bytes/row)
    val attrStride            = out UInt(16 bits)  // 0x0356 (direct-color bytes/row)
    val bitmapHeight          = out UInt(10 bits)  // 0x0357 (source rows)

    // R1 Raster Trigger Unit control/status. Stable naming so a later Mode0
    // register bus can adopt these without behavior change.
    val rasterTriggerLine      = in UInt(10 bits)
    val rasterTriggerPixel     = in UInt(10 bits)
    val rasterTriggerPxEnable  = in Bool()
    val rasterTriggerEnable    = in Bool()
    val rasterTriggerClear     = in Bool()
    val rasterTriggerPulse     = out Bool()
    val rasterTriggerPending   = out Bool()

    // Task 35 — Host-facing status surface.
    // External event inputs (pixel clock domain, 1-cycle pulses or level):
    val statusEvQspiReady  = in Bool()  // pulses on QSPI cmd_valid
    val statusEvQspiError  = in Bool()  // level-high when QspiDecoder.last_error != 0
    // Sticky register output for QSPI READ_STATUS sel=5 readback:
    val statusSticky       = out Bits(16 bits)
    // Host-visible IRQ line — asserted while any enabled sticky bit is set:
    val irq                = out Bool()
    // Task 54 — sprite-sprite collision per-descriptor mask, addr 0x0322.
    // Width deliberately held at 8 bits per BronzeGate #10363 even though
    // descCount is now 32: each bit set indicates the corresponding
    // descriptor participated in at least one sprite-sprite overlap since
    // the last write-1-to-clear. With descCount=32 the hit-descriptor
    // index is truncated to 3 bits, so descriptors 8/16/24 alias onto
    // bit 0, 9/17/25 onto bit 1, etc. Widening to 32 bits is parked
    // until a concrete product need for per-descriptor collision
    // resolution above descriptor 7 is shown (#10363).
    val spriteCollMask     = out Bits(8 bits)

    // MODE_SELECT live-mode field (4-bit) — exported for host READ_STATUS
    // LIVE_MODE observability. 0x0 = native Mode0; non-zero values are
    // reserved for runtime adapter selection driven by libvdp.
    val modeSelect         = out UInt(4 bits)

    // I80-FRAME-ATOMIC-SWAP-145: host-readable swap-ctrl status for 0x035C
    // readback. b0 = swapRequest (armed, self-clears at the vblank commit),
    // b1 = swapCommitted (sticky until host W1C). Wired into the i80 read mux
    // in TopTang20kHdmi so firmware can poll real commit completion instead of
    // a fixed open-loop delay.
    val swapStatus         = out Bits(16 bits)

    // Task 3 — Planar Fetch Hardening: SDRAM master interface for
    // PlanarLineFetch. The instance lives inside VdpTop; its SDRAM
    // master ports route up to TopTang20kHdmi for arbitration as
    // sdramArbiter client 2 alongside tile fetch (client 0) and
    // bitmap row fetch (client 1).
    val planarSdramRd        = out Bool()
    val planarSdramAddr      = out UInt(23 bits)
    val planarSdramBusy      = in  Bool()
    val planarSdramDataReady = in  Bool()
    val planarSdramDout32    = in  Bits(32 bits)
    // VDP-SOFT-RESET-135 #3 part 2c: expose planar plane bases + active gate so
    // TopTang's zero-fill can clear the occupied planar regions. Each plane's
    // SDRAM footprint is PLANE_PIXELS/8 = 40 bytes (BitplaneRowFetch reads
    // planeBase + readIdx*4, readsPerPlane = planePixels/32; no line offset).
    val planeBaseAddr        = out Vec(UInt(23 bits), 5)   // = PLANE_COUNT
    val planarFillActive     = out Bool()
  }

  // 640x480@60 timing uses a 25.2 MHz pixel clock.
  // The Tang20K wrapper supplies that from a 27 MHz input and a PLL/CLKDIV chain.
  val hActive = 640
  val hFront = 16
  val hSync = 96
  val hBack = 48
  val hTotal = hActive + hFront + hSync + hBack

  val vActive = 480
  val vFront = 10
  val vSync = 2
  val vBack = 33
  val vTotal = vActive + vFront + vSync + vBack

  val hCounter = (Reg(UInt(log2Up(hTotal) bits)) init 0).simPublic()   // simPublic: in-phase display counter for sims (SIM-TEST-DEBT-138)
  val vCounter = (Reg(UInt(log2Up(vTotal) bits)) init 0).simPublic()   // simPublic: vblank detection for the atomic-swap sim (I80-FRAME-ATOMIC-SWAP-145)

  // Raster counters walk the full timing envelope, not just the visible area.
  when(hCounter === hTotal - 1) {
    hCounter := 0
    when(vCounter === vTotal - 1) {
      vCounter := 0
    } otherwise {
      vCounter := vCounter + 1
    }
  } otherwise {
    hCounter := hCounter + 1
  }

  val activeVideo = hCounter < hActive && vCounter < vActive
  val hSyncStart = hActive + hFront
  val hSyncEnd = hActive + hFront + hSync
  val vSyncStart = vActive + vFront
  val vSyncEnd = vActive + vFront + vSync

  // Deterministic startup: output black until first vblank primes the buffer.
  val primed = Reg(Bool()) init False
  when(hCounter === hTotal - 1 && vCounter === vTotal - 1) {
    primed := True
  }

  // Fill line: during visible line N, fill the buffer with line N+1.
  // During vblank or the last visible line, fill with line 0 to prime next frame.
  val fillLine = UInt(10 bits)
  when(vCounter < vActive - 1) {
    fillLine := (vCounter + 1).resize(10)
  } otherwise {
    fillLine := U(0, 10 bits)
  }

  // Linestate: double-buffered per-scanline control store.
  // Prepare side is writable; commit side is read by render pipeline.
  // Commit at line boundary: at the start of each line, the prepare entry for
  // the current fillLine is copied to the commit side.
  val linestate = LinestateStore(lineCount = vActive)

  // VDP-SOFT-RESET-135: soft-reset controller state. Declared here (before the
  // linestate/scroll/palette/pattern write ports that the clear-sweep muxes
  // reference) — the request-latch decode + FSM logic follow further below.
  // #2a/#2b: on-chip memory clear sweep over host-writable Mems. Single shared
  // address counter; each Mem's EXISTING single write port is MUXED to the
  // sweep when active (NOT a second write port — that broke Gowin BSRAM
  // inference before). Sized to the largest swept Mem (sprite pattern RAM =
  // 16384 entries = 14b). affineTexture excluded (no write port; immutable POR).
  val softResetRequest  = Reg(Bool()) init False
  val softResetBusy     = Reg(Bool()) init False
  val softResetMemClear = Reg(Bool()) init False
  val softResetMemAddr  = Reg(UInt(14 bits)) init 0
  // #3: SDRAM zero-fill stage — high after the on-chip Mem clear, while the
  // controller waits for TopTang's sdram-domain fill FSM (sdramFillDone).
  val softResetFillStage = Reg(Bool()) init False
  // #4: core register reset stage — high while config registers are forced to
  // `init` (Option B surgical reset; the reset block keys off this). LIVE reg
  // (not itself reset) so it survives the reset it drives.
  val softResetCoreActive = Reg(Bool()) init False
  // #2b: linestate clears BOTH prepare+commit in one pass — a same-cycle
  // prepare-write + commit at the same address hits the BH-6 collision path,
  // which writes the (zero) writeData into commit. Active for addr < lineCount.
  val lsSweepWr = softResetMemClear && (softResetMemAddr < U(vActive, 14 bits))

  linestate.io.readAddr := fillLine.resized
  linestate.io.commitLine   := Mux(lsSweepWr, softResetMemAddr.resize(log2Up(vActive)), fillLine.resized)
  linestate.io.commitStrobe := Mux(lsSweepWr, True, hCounter === hTotal - 1)
  // Prepare-side write interface exposed for simulation testing.
  // R5 Copper coprocessor, fed by the regWrite bus for program uploads and by
  // `copperCtrlReg(0)` (VDP_CTRL @ 0x0310) for run control — R5.3 unifies the
  // previously-standalone `io.copperEnable` port with the register bus.
  val copperCtrlReg     = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPend    = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPendHit = Reg(Bool()) init False
  // R5.4: Copper double-buffered live-update. Host writes VDP_CTRL bit[1]=1
  // to request an atomic bank swap; HW commits the swap at vSyncStart && hCounter==0
  // (frame-atomic, matches MODE_SELECT cadence) and auto-clears the pending bit.
  // Requests while copper is disabled are dropped (a swap can only happen while
  // copper is running). Disable also clears any in-flight pending request.
  val copperSwapPending = Reg(Bool()) init False
  val copper = Copper()
  copper.io.hCounter := hCounter.resize(10)
  copper.io.vCounter := vCounter.resize(10)
  copper.io.enabled  := copperCtrlReg(0)
  val copperSwapNowPulse = copperSwapPending && copperCtrlReg(0) &&
    (vCounter === U(vSyncStart, log2Up(vTotal) bits)) &&
    (hCounter === U(0, log2Up(hTotal) bits))
  copper.io.bankSwapNow := copperSwapNowPulse
  // BH-2: feed Copper's SKIP comparator from the legacy TR0 raster
  // trigger config so SKIP shares the same (line, pixel) targets the
  // IRQ subsystem already exposes. Wired below the rasterTrigger
  // declaration; TR0 inputs are the top-level rasterTrigger* IO.
  copper.io.triggerLine0  := io.rasterTriggerLine
  copper.io.triggerPixel0 := io.rasterTriggerPixel
  val copperProgRangeHit = io.regBus.enable &&
    (io.regBus.addr >= U(0x0400, 15 bits)) &&
    (io.regBus.addr <  U(0x0600, 15 bits))
  copper.io.progAddr := io.regBus.addr(8 downto 0)
  copper.io.progData := io.regBus.data
  copper.io.progWr   := copperProgRangeHit
  // VDP-SOFT-RESET-135 #2c: drive the copper clear sweep from the shared counter.
  copper.io.softClear     := softResetMemClear
  copper.io.softClearAddr := softResetMemAddr

  // Task 33 — HDMA host-control sub-block @ 0x0380..0x03C9.
  // Decoded from the EFFECTIVE merged bus (effAddr/effWrite) so configuration
  // writes originating from the copper script also reach the HDMA engine —
  // not just host (QSPI/bootstrap) writes on io.regBus.
  // (effAddr/effWrite are defined further below; SpinalHDL resolves via
  //  concurrent-assignment, so the forward reference is fine.)

  // R5.2 (#7082 target 100%): copper writes now flow through a small drain
  // FIFO and are released only on the safe boundary (`hCounter === 0`).
  // Previously the combinational merge let copper regWrite pulses reach the
  // RegisterMap mid-line, producing the ~6 residual scroll skips and
  // red-flash artifacts the R5.1 partial fix couldn't fully eliminate.
  // Task 33: depth widened from 4 → 32 so a copper bootstrap script can fire
  // a burst of writes (e.g. HDMA config is 11 back-to-back writes) without
  // FIFO-full drops. Drain is still 1/line at hCounter===0.
  // Task 50 v3.2: depth widened 32 -> 64 to hold the 54-write per-frame burst
  // for the ZX Spectrum scene (palette load + border/bitmap control).
  val copperFifo = spinal.lib.StreamFifo(dataType = Bits(31 bits), depth = 64)
  copperFifo.io.push.valid   := copper.io.regWr
  copperFifo.io.push.payload := (copper.io.regAddr.asBits ## copper.io.regData).asBits.resize(31)
  val extHit     = io.regBus.enable
  val safeNow    = hCounter === U(0, log2Up(hTotal) bits)
  val copperDrain = safeNow && !extHit
  copperFifo.io.pop.ready := copperDrain
  val copperPopped = copperFifo.io.pop.fire

  // Task 47 — DMA-style block transfer primitive. Merges into effWrite with
  // lower priority than ext/copper; when a higher-priority master is
  // driving effWrite, the DMA pauses and resumes on the next free cycle.
  // Task 49 — Blitter engine added at the *lowest* priority (below DMA).
  // Fixed co-arbitration: dmaWr > blitWr (preserves Task 47 latency). Both
  // engines hold their counters when blocked.
  val dmaEngine     = DmaEngine()
  val blitterEngine = BlitterEngine()
  val dmaWr  = dmaEngine.io.dmaWr
  val blitWr = blitterEngine.io.blitWr
  val effWrite = (extHit || copperPopped || dmaWr || blitWr).simPublic()
  val effAddr  = Mux(extHit,      io.regBus.addr,
                 Mux(copperPopped, copperFifo.io.pop.payload(30 downto 16).asUInt,
                 Mux(dmaWr,        dmaEngine.io.dmaAddr,
                                   blitterEngine.io.blitAddr))).simPublic()
  val effData  = Mux(extHit,      io.regBus.data,
                 Mux(copperPopped, copperFifo.io.pop.payload(15 downto 0),
                 Mux(dmaWr,        dmaEngine.io.dmaData,
                                   blitterEngine.io.blitData))).simPublic()

  // DMA bus-write decode — only control registers (0x0B00..0x0B03) and the
  // staging buffer (0x0B10..0x0B4F) are consumed by DmaEngine. Writes from
  // ext/copper in this range program the DMA; writes from DMA itself always
  // target other ranges, so no self-recursion.
  val dmaRangeHit = (effAddr >= U(0x0B00, 15 bits)) && (effAddr < U(0x0B50, 15 bits))
  dmaEngine.io.busAddr := effAddr
  dmaEngine.io.busData := effData
  dmaEngine.io.busWr   := effWrite && dmaRangeHit
  // VDP-SOFT-RESET-135 #2d: drive the DMA staging clear from the shared sweep.
  dmaEngine.io.softClear     := softResetMemClear
  dmaEngine.io.softClearAddr := softResetMemAddr
  dmaEngine.io.busBusy := extHit || copperPopped

  // Task 49 — Blitter bus-write decode. Control registers at 0x0C00..0x0C07
  // and the 512-word source RAM at 0x0C10..0x0D0F are consumed by the
  // BlitterEngine. The blitter itself writes only to its programmed
  // destination address (15-bit), so self-recursion is precluded as long
  // as the host does not program dst into the blitter's own range.
  val blitRangeHit = (effAddr >= U(0x0C00, 15 bits)) && (effAddr < U(0x0D10, 15 bits))
  blitterEngine.io.busAddr := effAddr
  blitterEngine.io.busData := effData
  blitterEngine.io.busWr   := effWrite && blitRangeHit
  blitterEngine.io.busBusy := extHit || copperPopped || dmaWr
  // VDP-SOFT-RESET-135 #2d: drive the blitter srcRam clear from the shared sweep.
  blitterEngine.io.softClear     := softResetMemClear
  blitterEngine.io.softClearAddr := softResetMemAddr

  // Task 33 HDMA control decode (see forward-declared comment above).
  val copperHdmaRangeHit = effWrite &&
    (effAddr >= U(0x0380, 15 bits)) &&
    (effAddr <  U(0x0400, 15 bits))
  copper.io.hdmaCtrlAddr := effAddr(6 downto 0)
  copper.io.hdmaData     := effData
  copper.io.hdmaWr       := copperHdmaRangeHit

  // R5 RegisterMap decode off the merged bus. Writes to the linestate range
  // take the low 9 bits of effAddr as line index and the low 12 bits of
  // effData as the packed record. LAYER_ENABLE latches at 0x0300.
  val lsRangeHit = effWrite && (effAddr < U(480, 15 bits))
  // VDP-SOFT-RESET-135 #2b: prepare-side write muxed between host and the
  // zero-sweep. writeAddr/writeData here pair with the commitLine/commitStrobe
  // override above so each swept line zeroes prepare AND commit (BH-6 collision).
  linestate.io.writeAddr   := Mux(lsSweepWr, softResetMemAddr.resize(log2Up(480)), effAddr(log2Up(480) - 1 downto 0))
  linestate.io.writeData   := Mux(lsSweepWr, B(0, 12 bits), effData(11 downto 0))
  linestate.io.writeEnable := Mux(lsSweepWr, True, lsRangeHit)

  // R5.1 stutter fix (#7080): latch pending LAYER_ENABLE write into a shadow
  // register and apply it to `layerEnableReg` only at `hCounter === 0`.
  // Without this gate, the copper's combinational write arrives mid-line,
  // shifts the compositor's effective enable mask mid-scanline, and shows
  // up as 1-frame scroll skips + wrong-bank pixel flashes on hardware.
  // 5-bit layout: {L3[4], L2[3], sprite[2], L1[1], L0[0]}.
  // Reset default = all-off (lane #10567 agnosticism). The host owns layer
  // activation via libvdp.
  val layerEnableReg    = (Reg(Bits(5 bits)) init B"00000").simPublic()
  val layerEnablePend   = Reg(Bits(5 bits)) init B"00000"
  val layerEnablePendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0300, 15 bits)) {
    layerEnablePend    := effData(4 downto 0)
    layerEnablePendHit := True
  }
  // Register-programmability #3/#4 (TopazCliff #12578/#12649). Direct config regs
  // (host sets at setup, not mid-frame); reset to init by the #4 soft-reset block.
  // #3: per-layer transparency key — the palette index treated as transparent for
  // each layer (replaces the hardcoded index-0). Default 0 ⇒ bit-identical.
  val l0TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l1TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l2TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l3TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  when(effWrite && effAddr === U(0x0314, 15 bits)) { l0TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0315, 15 bits)) { l1TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0316, 15 bits)) { l2TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0317, 15 bits)) { l3TransKeyReg := effData(3 downto 0) }
  // #4: planar clip width — replaces the fixed PLANE_PIXELS clip. Default 320 ⇒
  // bit-identical; values >320 wrap (planar source native width is 320).
  val planarWidthReg = (Reg(UInt(10 bits)) init 320).simPublic()
  when(effWrite && effAddr === U(0x0D4B, 15 bits)) { planarWidthReg := effData(9 downto 0).asUInt }
  // R4.1b stage 3 / R4.1d Checkpoint A: VDP_TILE_MODE @ 0x0311 follows the
  // same safe-boundary pattern as layerEnable — pending shadow + commit at
  // hCounter===0. Widened from 1→2 bits to encode shuffled mode (0x02)
  // alongside packed (0x00) and planar (0x01). See layer0TileDecodeMode.
  val tileDecodeModeReg     = Reg(Bits(2 bits)) init B(0, 2 bits)
  val tileDecodeModePend    = Reg(Bits(2 bits)) init B(0, 2 bits)
  val tileDecodeModePendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0311, 15 bits)) {
    tileDecodeModePend    := effData(1 downto 0)
    tileDecodeModePendHit := True
  }
  // R4.1c: VDP_ATTR_MODE @ 0x0312, same safe-boundary pattern.
  val attributeModeReg      = Reg(Bits(1 bits)) init B(0, 1 bits)
  val attributeModePend     = Reg(Bits(1 bits)) init B(0, 1 bits)
  val attributeModePendHit  = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0312, 15 bits)) {
    attributeModePend    := effData(0 downto 0)
    attributeModePendHit := True
  }
  // BACKDROP_INDEX @ 0x0348 — host-writable 7-bit absolute palette index used
  // by the compositor `.otherwise` fallthrough as the displayed pixel when no
  // layer is opaque. Decouples the backdrop color from layer0Bank (which is
  // SDRAM-sourced and non-deterministic across reboots). POR=0 → palette[0].
  // Standard safe-boundary shadow+commit pattern.
  val backdropIndexReg     = (Reg(UInt(7 bits)) init U(0, 7 bits)).simPublic()
  val backdropIndexPend    = Reg(UInt(7 bits)) init U(0, 7 bits)
  val backdropIndexPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0348, 15 bits)) {
    backdropIndexPend    := effData(6 downto 0).asUInt
    backdropIndexPendHit := True
  }

  // PixelRepeatScaler register block (lane #10590 Path B).
  //   0x0349 SCALE_CTRL    : [2:0]=scaleX (0/1 = 1x, 2..6 = 2x..6x; ≥7 clamps)
  //                          [6:4]=scaleY (same encoding)
  //                          [7]  = autoCenter
  //   0x034A LOGIC_WIDTH   : 11-bit logical canvas width  (1..640)
  //   0x034B LOGIC_HEIGHT  : 11-bit logical canvas height (1..480)
  // Hardware silently clamps scale*logic to active dimensions (CyanPeak #10596).
  // Safe-boundary commit at hCounter===0 like the rest of the register file.
  val scaleCtrlReg     = (Reg(Bits(8 bits)) init B(scaleCtrlInit, 8 bits)).simPublic()
  val scaleCtrlPend    = Reg(Bits(8 bits)) init B(0, 8 bits)
  val scaleCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0349, 15 bits)) {
    scaleCtrlPend    := effData(7 downto 0)
    scaleCtrlPendHit := True
  }
  val logicWidthReg     = (Reg(UInt(11 bits)) init U(logicWidthInit, 11 bits)).simPublic()
  val logicWidthPend    = Reg(UInt(11 bits)) init U(640, 11 bits)
  val logicWidthPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034A, 15 bits)) {
    logicWidthPend    := effData(10 downto 0).asUInt
    logicWidthPendHit := True
  }
  val logicHeightReg     = (Reg(UInt(11 bits)) init U(logicHeightInit, 11 bits)).simPublic()
  val logicHeightPend    = Reg(UInt(11 bits)) init U(480, 11 bits)
  val logicHeightPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034B, 15 bits)) {
    logicHeightPend    := effData(10 downto 0).asUInt
    logicHeightPendHit := True
  }

  // Inner-border registers (0x034C..0x034F): border thickness in LOGICAL pixels.
  // Hardware auto-computes the physical BORDER_X0/Y0/X1/Y1 from these values
  // plus scale + logic dims, so the host need not do the math.
  //   0x034C INNER_BORDER_L  (10 bits)
  //   0x034D INNER_BORDER_R  (10 bits)
  //   0x034E INNER_BORDER_T  (10 bits)
  //   0x034F INNER_BORDER_B  (10 bits)
  //   0x0347 BORDER_CTRL     bit[1] = innerBorderEnable (in addition to bit[0]=enable)
  val innerBorderLReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderLPend    = Reg(UInt(10 bits)) init 0
  val innerBorderLPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034C, 15 bits)) {
    innerBorderLPend    := effData(9 downto 0).asUInt
    innerBorderLPendHit := True
  }
  val innerBorderRReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderRPend    = Reg(UInt(10 bits)) init 0
  val innerBorderRPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034D, 15 bits)) {
    innerBorderRPend    := effData(9 downto 0).asUInt
    innerBorderRPendHit := True
  }
  val innerBorderTReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderTPend    = Reg(UInt(10 bits)) init 0
  val innerBorderTPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034E, 15 bits)) {
    innerBorderTPend    := effData(9 downto 0).asUInt
    innerBorderTPendHit := True
  }
  val innerBorderBReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderBPend    = Reg(UInt(10 bits)) init 0
  val innerBorderBPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034F, 15 bits)) {
    innerBorderBPend    := effData(9 downto 0).asUInt
    innerBorderBPendHit := True
  }

  // R5.3: VDP_CTRL @ 0x0310, safe-boundary shadow + commit for copper enable.
  // R5.4: bit[1] = COPPER_SWAP_REQUEST (latch-on-write). HW auto-clears at
  // commit. Last-write-wins precedence below: swap-commit and disable-clear
  // both override the host set, so a request that lands the same cycle as
  // disable or the commit pulse resolves cleanly.
  when(effWrite && effAddr === U(0x0310, 15 bits)) {
    copperCtrlPend    := effData(0 downto 0)
    copperCtrlPendHit := True
    when(effData(1)) { copperSwapPending := True }
    // VDP-SOFT-RESET-135: bit[2] = SOFT_RESET_REQUEST (latch-on-write, like the
    // copper-swap bit[1]). Honored by the soft-reset controller below.
    when(effData(2)) { softResetRequest := True }
  }
  // R5.4: auto-clear on commit, and clear if copper is disabled (pending
  // swap is dropped because requests are only honored while enabled).
  when(copperSwapNowPulse)   { copperSwapPending := False }
  when(!copperCtrlReg(0))    { copperSwapPending := False }

  // ===== VDP-SOFT-RESET-135: host-triggered soft-reset controller =====
  // Host writes VDP_CTRL @ 0x0310 bit[2]=1 to request a POR-equivalent soft
  // reset; HW runs a bounded, deadlock-free sequence and AUTO-CLEARS the
  // request + drops `softResetBusy` when complete. The host polls completion
  // by reading 0x0310 (i80 readback returns bit2=SOFT_RESET_BUSY; see TopTang).
  //
  // INCREMENTAL BUILD (lane VDP-SOFT-RESET-135): this increment (#1) wires the
  // request/busy/auto-clear handshake + the i80 status readback only. The
  // reset *actions* land in later increments, each slotting into the staged
  // sequence below WITHOUT changing this host-facing contract:
  //   [#2] on-chip MEM clear sweep (copper RAM, palette, sprite pattern/desc,
  //        linestate, scroll tables, affine texture) — zero per TopazCliff Q1.
  //   [#3] SDRAM zero-fill engine (TopTang arbiter client) — all of SDRAM (Q2).
  //   [#4] core register reset (ClockDomain soft-reset partition) — regs->init.
  // These controller regs live in the NORMAL clock domain (NOT the future
  // core-reset partition) so the controller survives the reset it drives and
  // can hold/clear the request + drive the busy status throughout.
  //
  // Sequence: stage 1 = on-chip Mem clear sweep (#2a-#2e, done); stage 2 = SDRAM
  // zero-fill via TopTang's fill FSM (#3); stage 3 = core register reset (#4,
  // pending). Busy is held across all stages; the request auto-clears at the end.
  // (softResetRequest/softResetBusy/softResetMemClear/softResetMemAddr/
  //  softResetFillStage declared above the 0x0310 decode.)
  // Sweep covers addr [0, 16383] (full 14-bit sprite-pattern depth). palette
  // (PaletteDepth=128) clears only while addr < PaletteDepth; pattern RAM clears
  // across the whole sweep. The per-Mem write muxes live at each Mem below.
  val softResetSweepLast = U((1 << 14) - 1, 14 bits)
  when(softResetRequest && !softResetBusy) {
    softResetBusy      := True           // accept the request; begin the sequence
    softResetMemClear  := True           // stage 1: on-chip memory clear sweep
    softResetMemAddr   := 0
    softResetFillStage := False
  }
  when(softResetBusy && softResetMemClear) {
    when(softResetMemAddr === softResetSweepLast) {
      softResetMemClear  := False
      // stage 2: SDRAM zero-fill. Raise the fill request and hold busy until
      // TopTang's sdram-domain fill FSM reports done (BufferCC-crossed). #4
      // (core register reset) will chain after the fill stage when it lands.
      softResetFillStage := True
    } otherwise {
      softResetMemAddr := softResetMemAddr + 1
    }
  }
  when(softResetBusy && softResetFillStage) {
    when(io.sdramFillDone) {             // SDRAM zero-fill complete ...
      softResetFillStage  := False
      softResetCoreActive := True        // ... enter stage 3: core register reset
    }
  }
  // Stage 3 (#4): hold the config registers at their `init` (the reset block
  // below keys off softResetCoreActive), then RELEASE synchronously at a clean
  // line boundary (hCounter==0) so the video datapath sees no glitched pulse
  // (CyanPeak #12589/#12609 safety rule). Config regs are stable at init through
  // the stage; releasing at hCounter==0 starts the next line cleanly.
  when(softResetBusy && softResetCoreActive) {
    when(hCounter === U(0, log2Up(hTotal) bits)) {
      softResetCoreActive := False
      softResetBusy       := False        // sequence complete: drop busy ...
      softResetRequest    := False        // ... and auto-clear the request bit
    }
  }
  // SDRAM-fill request to TopTang (level; CDC'd in TopTang to sdramClockDomain).
  io.sdramFillStart := softResetFillStage
  io.softResetBusy := softResetBusy

  // MODE_SELECT @ 0x0313: 16-bit register — [3:0] = MODE_SELECT,
  // [7:4] = reserved, [15:8] = MODE_FLAGS. Host/QSPI-write only.
  // Frame-atomic commit at V=0 (vsync start) — NOT the per-line hCounter===0
  // boundary used by other safe-boundary regs, since mode switch must not
  // produce split-frame artifacts.
  val modeSelectPend     = Reg(UInt(4 bits))  init U(0, 4 bits)
  val modeSelectFlagsPend = Reg(Bits(8 bits))  init B(0, 8 bits)
  val modeSelectPendHit  = Reg(Bool())        init False
  val modeSelectReg      = Reg(UInt(4 bits))  init U(0, 4 bits)
  val modeSelectFlagsReg  = Reg(Bits(8 bits))  init B(0, 8 bits)
  when(effWrite && effAddr === U(0x0313, 15 bits)) {
    modeSelectPend      := effData(3 downto 0).asUInt
    modeSelectFlagsPend := effData(15 downto 8)
    modeSelectPendHit   := True
  }
  io.modeSelect := modeSelectReg
  // R6 Task 20: Color Math + Window registers (0x0330..0x0334), same
  // safe-boundary shadow+commit pattern. Defaults are all-zero so the stage
  // is passthrough at power-on (no output regression).
  val winX0Reg     = Reg(UInt(10 bits)) init 0
  val winX0Pend    = Reg(UInt(10 bits)) init 0
  val winX0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0330, 15 bits)) {
    winX0Pend    := effData(9 downto 0).asUInt
    winX0PendHit := True
  }
  val winX1Reg     = Reg(UInt(10 bits)) init 0
  val winX1Pend    = Reg(UInt(10 bits)) init 0
  val winX1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0331, 15 bits)) {
    winX1Pend    := effData(9 downto 0).asUInt
    winX1PendHit := True
  }
  val winY0Reg     = Reg(UInt(10 bits)) init 0
  val winY0Pend    = Reg(UInt(10 bits)) init 0
  val winY0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0332, 15 bits)) {
    winY0Pend    := effData(9 downto 0).asUInt
    winY0PendHit := True
  }
  val winY1Reg     = Reg(UInt(10 bits)) init 0
  val winY1Pend    = Reg(UInt(10 bits)) init 0
  val winY1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0333, 15 bits)) {
    winY1Pend    := effData(9 downto 0).asUInt
    winY1PendHit := True
  }
  val colorMathReg     = Reg(Bits(16 bits)) init 0
  val colorMathPend    = Reg(Bits(16 bits)) init 0
  val colorMathPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0334, 15 bits)) {
    colorMathPend    := effData
    colorMathPendHit := True
  }
  // CW-5: Window 2 + combination logic registers.
  //   0x0335 win2X0 (inclusive)
  //   0x0336 win2X1 (exclusive)
  //   0x0337 win2Y0 (inclusive)
  //   0x0338 win2Y1 (exclusive)
  //   0x0339 win2Ctrl  bit[0] = invert2
  //   0x033A winCombMode bits[2:0]
  //                       000 = window1 only (legacy default)
  //                       001 = AND (e1 && e2)
  //                       010 = OR  (e1 || e2)
  //                       011 = XOR (e1 ^^ e2)
  //                       100 = INV_AND (!(e1 && e2))
  //                       101 = INV_OR  (!(e1 || e2))
  //                       11x = reserved (treated as window1 only)
  // All defaults are zero so existing scenes are bit-identical: with
  // win2 X/Y all zero and invert2=0 → effect2 = False; combMode=0 → use
  // effect1 unchanged.
  val win2X0Reg     = Reg(UInt(10 bits)) init 0
  val win2X0Pend    = Reg(UInt(10 bits)) init 0
  val win2X0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0335, 15 bits)) {
    win2X0Pend    := effData(9 downto 0).asUInt
    win2X0PendHit := True
  }
  val win2X1Reg     = Reg(UInt(10 bits)) init 0
  val win2X1Pend    = Reg(UInt(10 bits)) init 0
  val win2X1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0336, 15 bits)) {
    win2X1Pend    := effData(9 downto 0).asUInt
    win2X1PendHit := True
  }
  val win2Y0Reg     = Reg(UInt(10 bits)) init 0
  val win2Y0Pend    = Reg(UInt(10 bits)) init 0
  val win2Y0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0337, 15 bits)) {
    win2Y0Pend    := effData(9 downto 0).asUInt
    win2Y0PendHit := True
  }
  val win2Y1Reg     = Reg(UInt(10 bits)) init 0
  val win2Y1Pend    = Reg(UInt(10 bits)) init 0
  val win2Y1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0338, 15 bits)) {
    win2Y1Pend    := effData(9 downto 0).asUInt
    win2Y1PendHit := True
  }
  val win2CtrlReg     = Reg(Bits(16 bits)) init 0
  val win2CtrlPend    = Reg(Bits(16 bits)) init 0
  val win2CtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0339, 15 bits)) {
    win2CtrlPend    := effData
    win2CtrlPendHit := True
  }
  val winCombReg     = Reg(Bits(16 bits)) init 0
  val winCombPend    = Reg(Bits(16 bits)) init 0
  val winCombPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033A, 15 bits)) {
    winCombPend    := effData
    winCombPendHit := True
  }
  // CW-6: Per-layer window mask enable. When a layer's bit is set AND the
  // combined window effect is active for the current pixel, that layer's
  // contribution is masked at display time (forced to black). Bit layout
  // matches PixelMetadata.SourceXxx encoding so `layerMaskReg(source)`
  // selects the correct mask:
  //   bit[0] = mask SourceBG0
  //   bit[1] = mask SourceBG1
  //   bit[2] = mask SourceBG2
  //   bit[3] = mask SourceBG3
  //   bit[4] = mask SourceSprite
  //   bits[7:5] = reserved
  // Default 0 → no masking (legacy behavior).
  val layerMaskReg     = Reg(Bits(16 bits)) init 0
  val layerMaskPend    = Reg(Bits(16 bits)) init 0
  val layerMaskPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033B, 15 bits)) {
    layerMaskPend    := effData
    layerMaskPendHit := True
  }
  // Task 50 v3 — Visible-border-via-window registers.
  //
  // Defines a dedicated rectangular window at display coordinates. When
  // BORDER_CTRL[0] is set, pixels OUTSIDE the rectangle are replaced at
  // the final display stage with palette[BORDER_CTRL[12:8]]. The rectangle
  // is independent from the CW-5 WIN1/WIN2 windows so existing scenes using
  // those for ColorMath effects are unaffected. Defaults are all-zero so
  // v3-OFF scenes continue to render bit-identically.
  //
  //   0x033C BORDER_X0   (10 bits, inclusive)
  //   0x033D BORDER_X1   (10 bits, exclusive)
  //   0x033E BORDER_Y0   (10 bits, inclusive)
  //   0x033F BORDER_Y1   (10 bits, exclusive)
  //   0x0347 BORDER_CTRL bit[0]    = enable
  //                       bit[1]     = innerBorderEnable (auto-compute
  //                                    physical borders from INNER_BORDER_*)
  //                       bits[12:8] = palette index (0..31) for the
  //                                    border source pixel
  val borderX0Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderX0Pend    = Reg(UInt(10 bits)) init 0
  val borderX0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033C, 15 bits)) {
    borderX0Pend    := effData(9 downto 0).asUInt
    borderX0PendHit := True
  }
  val borderX1Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderX1Pend    = Reg(UInt(10 bits)) init 0
  val borderX1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033D, 15 bits)) {
    borderX1Pend    := effData(9 downto 0).asUInt
    borderX1PendHit := True
  }
  val borderY0Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderY0Pend    = Reg(UInt(10 bits)) init 0
  val borderY0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033E, 15 bits)) {
    borderY0Pend    := effData(9 downto 0).asUInt
    borderY0PendHit := True
  }
  val borderY1Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderY1Pend    = Reg(UInt(10 bits)) init 0
  val borderY1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033F, 15 bits)) {
    borderY1Pend    := effData(9 downto 0).asUInt
    borderY1PendHit := True
  }
  val borderCtrlReg     = (Reg(Bits(16 bits)) init B(borderCtrlInit, 16 bits)).simPublic()
  val borderCtrlPend    = Reg(Bits(16 bits)) init 0
  val borderCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0347, 15 bits)) {
    borderCtrlPend    := effData
    borderCtrlPendHit := True
  }
  // Task 19 Checkpoint A: Affine Layer matrix + control registers.
  // Addresses 0x0340..0x0346, same safe-boundary shadow + commit pattern.
  //   0x0340 AFFINE_A    16b  signed 8.8 fixed point
  //   0x0341 AFFINE_B    16b  signed 8.8
  //   0x0342 AFFINE_C    16b  signed 8.8
  //   0x0343 AFFINE_D    16b  signed 8.8
  //   0x0344 AFFINE_X    16b  signed 10.6 translation
  //   0x0345 AFFINE_Y    16b  signed 10.6 translation
  //   0x0346 AFFINE_CTRL 16b  bit 0 = affineEnable, others reserved
  // Defaults are all-zero so AFFINE_CTRL[0]=0 at power-on — the L0 source mux
  // (landed in Checkpoint B) keeps the existing SDRAM/on-chip path unchanged.
  val affineAReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineAPend    = Reg(Bits(16 bits)) init 0
  val affineAPendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0340, 15 bits)) {
    affineAPend    := effData
    affineAPendHit := True
  }
  val affineBReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineBPend    = Reg(Bits(16 bits)) init 0
  val affineBPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0341, 15 bits)) {
    affineBPend    := effData
    affineBPendHit := True
  }
  val affineCReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineCPend    = Reg(Bits(16 bits)) init 0
  val affineCPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0342, 15 bits)) {
    affineCPend    := effData
    affineCPendHit := True
  }
  val affineDReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineDPend    = Reg(Bits(16 bits)) init 0
  val affineDPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0343, 15 bits)) {
    affineDPend    := effData
    affineDPendHit := True
  }
  val affineXReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineXPend    = Reg(Bits(16 bits)) init 0
  val affineXPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0344, 15 bits)) {
    affineXPend    := effData
    affineXPendHit := True
  }
  val affineYReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineYPend    = Reg(Bits(16 bits)) init 0
  val affineYPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0345, 15 bits)) {
    affineYPend    := effData
    affineYPendHit := True
  }
  val affineCtrlReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineCtrlPend    = Reg(Bits(16 bits)) init 0
  val affineCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0346, 15 bits)) {
    affineCtrlPend    := effData
    affineCtrlPendHit := True
  }
  val affineEnable = affineCtrlReg(0)

  // Task 44 — raw bitmap + attribute fetch register block (0x0350..0x0356).
  //   0x0350 BITMAP_CTRL       bit[0] enable, bits[2:1] bpp, bits[6:3] cellWidth log2
  //   0x0351 BITMAP_BASE_LO    low 16 bits of bitmap SDRAM base
  //   0x0352 BITMAP_BASE_HI    high 7 bits of bitmap SDRAM base
  //   0x0353 ATTR_BASE_LO      low 16 bits of attribute SDRAM base
  //   0x0354 ATTR_BASE_HI      high 7 bits of attribute SDRAM base
  //   0x0355 BITMAP_STRIDE     bytes per bitmap row
  //   0x0356 ATTR_STRIDE       bytes per attribute row
  // All registers use the established safe-boundary {shadow, pend, commit
  // at hCounter===0} pattern. Defaults are zero → BITMAP_CTRL[0]=0 at
  // power-on, so the L0 source mux below keeps the existing tile path
  // (no regression for legacy scenarios).
  val bitmapCtrlReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val bitmapCtrlPend    = Reg(Bits(16 bits)) init 0
  val bitmapCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0350, 15 bits)) {
    bitmapCtrlPend    := effData
    bitmapCtrlPendHit := True
  }
  val bitmapEnable    = bitmapCtrlReg(0)
  val bitmapBpp       = bitmapCtrlReg(2 downto 1).asUInt

  // BITMAP-PLUMB-129 (#12169/#12205) — bitmap/attr fetch geometry registers.
  //   0x0351 BITMAP_BASE_LO   low 16 bits of bitmap SDRAM base
  //   0x0352 BITMAP_BASE_HI   high 7 bits  (base = HI##LO, 23-bit byte addr)
  //   0x0353 ATTR_BASE_LO     low 16 bits of attribute SDRAM base
  //   0x0354 ATTR_BASE_HI     high 7 bits
  //   0x0355 BITMAP_STRIDE    direct-color bytes per bitmap row
  //   0x0356 ATTR_STRIDE      direct-color bytes per attribute row
  //   0x0357 BITMAP_HEIGHT    source image height in rows (NEW)
  // Same safe-boundary {shadow, pend, commit at hCounter===0} pattern as
  // BITMAP_CTRL. Power-on defaults reproduce BitmapRowFetch's former hardcoded
  // constants (base 0x3000/0x4000, stride 512, height 240) so existing demos
  // do not regress.
  val bitmapBaseLoReg  = Reg(UInt(16 bits)) init 0x3000
  val bitmapBaseHiReg  = Reg(UInt(7 bits))  init 0
  val attrBaseLoReg    = Reg(UInt(16 bits)) init 0x4000
  val attrBaseHiReg    = Reg(UInt(7 bits))  init 0
  val bitmapStrideReg  = Reg(UInt(16 bits)) init 512
  val attrStrideReg    = Reg(UInt(16 bits)) init 512
  val bitmapHeightReg  = Reg(UInt(10 bits)) init 240
  val bitmapBaseLoPend = Reg(UInt(16 bits)) init 0x3000
  val bitmapBaseHiPend = Reg(UInt(7 bits))  init 0
  val attrBaseLoPend   = Reg(UInt(16 bits)) init 0x4000
  val attrBaseHiPend   = Reg(UInt(7 bits))  init 0
  val bitmapStridePend = Reg(UInt(16 bits)) init 512
  val attrStridePend   = Reg(UInt(16 bits)) init 512
  val bitmapHeightPend = Reg(UInt(10 bits)) init 240
  val bitmapBaseLoPendHit = Reg(Bool()) init False
  val bitmapBaseHiPendHit = Reg(Bool()) init False
  val attrBaseLoPendHit   = Reg(Bool()) init False
  val attrBaseHiPendHit   = Reg(Bool()) init False
  val bitmapStridePendHit = Reg(Bool()) init False
  val attrStridePendHit   = Reg(Bool()) init False
  val bitmapHeightPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0351, 15 bits)) { bitmapBaseLoPend := effData(15 downto 0).asUInt; bitmapBaseLoPendHit := True }
  when(effWrite && effAddr === U(0x0352, 15 bits)) { bitmapBaseHiPend := effData(6 downto 0).asUInt;  bitmapBaseHiPendHit := True }
  when(effWrite && effAddr === U(0x0353, 15 bits)) { attrBaseLoPend   := effData(15 downto 0).asUInt; attrBaseLoPendHit   := True }
  when(effWrite && effAddr === U(0x0354, 15 bits)) { attrBaseHiPend   := effData(6 downto 0).asUInt;  attrBaseHiPendHit   := True }
  when(effWrite && effAddr === U(0x0355, 15 bits)) { bitmapStridePend := effData(15 downto 0).asUInt; bitmapStridePendHit := True }
  when(effWrite && effAddr === U(0x0356, 15 bits)) { attrStridePend   := effData(15 downto 0).asUInt; attrStridePendHit   := True }
  when(effWrite && effAddr === U(0x0357, 15 bits)) { bitmapHeightPend := effData(9 downto 0).asUInt;  bitmapHeightPendHit := True }

  // I80-FRAME-ATOMIC-SWAP-145: dedicated double-buffer staging for the bitmap
  // and attribute base pointers. The host stages all four words (0x0358-0x035B)
  // then arms the swap (0x035C b0); RTL copies them to the live bitmapBase/
  // attrBase regs in ONE cycle at the start of vblank (see the commit block).
  // This is additive to the legacy 0x0351-0x0354 path (which keeps its
  // commit-at-hCounter0 semantics) so the fetcher never observes a mixed
  // old-LO/new-HI or old-plane/new-plane base => test07 tearing fix.
  //   0x0358 BITMAP_BASE_PENDING_LO   0x0359 BITMAP_BASE_PENDING_HI
  //   0x035A ATTR_BASE_PENDING_LO     0x035B ATTR_BASE_PENDING_HI
  //   0x035C BITMAP_SWAP_CTRL: b0 = arm request (host sets, RTL auto-clears at
  //          commit); b1 = committed (sticky, host write-1-to-clear acks it).
  val bitmapBaseSwapLo = (Reg(UInt(16 bits)) init 0x3000).simPublic()
  val bitmapBaseSwapHi = (Reg(UInt(7 bits))  init 0).simPublic()
  val attrBaseSwapLo   = (Reg(UInt(16 bits)) init 0x4000).simPublic()
  val attrBaseSwapHi   = (Reg(UInt(7 bits))  init 0).simPublic()
  val swapRequest      = (Reg(Bool()) init False).simPublic()
  val swapCommitted    = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0358, 15 bits)) { bitmapBaseSwapLo := effData(15 downto 0).asUInt }
  when(effWrite && effAddr === U(0x0359, 15 bits)) { bitmapBaseSwapHi := effData(6 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035A, 15 bits)) { attrBaseSwapLo   := effData(15 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035B, 15 bits)) { attrBaseSwapHi   := effData(6 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035C, 15 bits)) {
    when(effData(0)) { swapRequest   := True }   // arm
    when(effData(1)) { swapCommitted := False }  // W1C ack of committed flag
  }
  // Host-readable swap status: b0 = swapRequest, b1 = swapCommitted.
  io.swapStatus := (B(0, 14 bits) ## swapCommitted ## swapRequest)

  when(hCounter === U(0, log2Up(hTotal) bits)) {
    when(layerEnablePendHit) {
      layerEnableReg     := layerEnablePend
      layerEnablePendHit := False
    }
    when(tileDecodeModePendHit) {
      tileDecodeModeReg     := tileDecodeModePend
      tileDecodeModePendHit := False
    }
    when(attributeModePendHit) {
      attributeModeReg     := attributeModePend
      attributeModePendHit := False
    }
    when(backdropIndexPendHit) {
      backdropIndexReg     := backdropIndexPend
      backdropIndexPendHit := False
    }
    when(scaleCtrlPendHit) {
      scaleCtrlReg     := scaleCtrlPend
      scaleCtrlPendHit := False
    }
    when(logicWidthPendHit) {
      logicWidthReg     := logicWidthPend
      logicWidthPendHit := False
    }
    when(logicHeightPendHit) {
      logicHeightReg     := logicHeightPend
      logicHeightPendHit := False
    }
    when(innerBorderLPendHit) {
      innerBorderLReg     := innerBorderLPend
      innerBorderLPendHit := False
    }
    when(innerBorderRPendHit) {
      innerBorderRReg     := innerBorderRPend
      innerBorderRPendHit := False
    }
    when(innerBorderTPendHit) {
      innerBorderTReg     := innerBorderTPend
      innerBorderTPendHit := False
    }
    when(innerBorderBPendHit) {
      innerBorderBReg     := innerBorderBPend
      innerBorderBPendHit := False
    }
    when(copperCtrlPendHit) {
      copperCtrlReg     := copperCtrlPend
      copperCtrlPendHit := False
    }
    // Task 1 (#9154) — V=0 commit pulse drives modeSelect commit + side
    // effects below (out of this hCounter===0 block since the V=0 gate
    // is once per frame). See modeCommitPulse.
    when(winX0PendHit)     { winX0Reg     := winX0Pend;     winX0PendHit     := False }
    when(winX1PendHit)     { winX1Reg     := winX1Pend;     winX1PendHit     := False }
    when(winY0PendHit)     { winY0Reg     := winY0Pend;     winY0PendHit     := False }
    when(winY1PendHit)     { winY1Reg     := winY1Pend;     winY1PendHit     := False }
    when(colorMathPendHit) { colorMathReg := colorMathPend; colorMathPendHit := False }
    when(win2X0PendHit)    { win2X0Reg    := win2X0Pend;    win2X0PendHit    := False }
    when(win2X1PendHit)    { win2X1Reg    := win2X1Pend;    win2X1PendHit    := False }
    when(win2Y0PendHit)    { win2Y0Reg    := win2Y0Pend;    win2Y0PendHit    := False }
    when(win2Y1PendHit)    { win2Y1Reg    := win2Y1Pend;    win2Y1PendHit    := False }
    when(win2CtrlPendHit)  { win2CtrlReg  := win2CtrlPend;  win2CtrlPendHit  := False }
    when(winCombPendHit)   { winCombReg   := winCombPend;   winCombPendHit   := False }
    when(layerMaskPendHit) { layerMaskReg := layerMaskPend; layerMaskPendHit := False }
    // Task 50 v3 — visible-border window safe-boundary commits.
    when(borderX0PendHit)   { borderX0Reg   := borderX0Pend;   borderX0PendHit   := False }
    when(borderX1PendHit)   { borderX1Reg   := borderX1Pend;   borderX1PendHit   := False }
    when(borderY0PendHit)   { borderY0Reg   := borderY0Pend;   borderY0PendHit   := False }
    when(borderY1PendHit)   { borderY1Reg   := borderY1Pend;   borderY1PendHit   := False }
    when(borderCtrlPendHit) { borderCtrlReg := borderCtrlPend; borderCtrlPendHit := False }
    // Task 19 affine registers (safe-boundary commit).
    when(affineAPendHit)    { affineAReg    := affineAPend;    affineAPendHit    := False }
    when(affineBPendHit)    { affineBReg    := affineBPend;    affineBPendHit    := False }
    when(affineCPendHit)    { affineCReg    := affineCPend;    affineCPendHit    := False }
    when(affineDPendHit)    { affineDReg    := affineDPend;    affineDPendHit    := False }
    when(affineXPendHit)    { affineXReg    := affineXPend;    affineXPendHit    := False }
    when(affineYPendHit)    { affineYReg    := affineYPend;    affineYPendHit    := False }
    when(affineCtrlPendHit) { affineCtrlReg := affineCtrlPend; affineCtrlPendHit := False }
    // Task 44 bitmap-fetch register commits.
    when(bitmapCtrlPendHit)    { bitmapCtrlReg    := bitmapCtrlPend;    bitmapCtrlPendHit    := False }
    // BITMAP-PLUMB-129 bitmap/attr base/stride/height commits.
    when(bitmapBaseLoPendHit)  { bitmapBaseLoReg  := bitmapBaseLoPend;  bitmapBaseLoPendHit  := False }
    when(bitmapBaseHiPendHit)  { bitmapBaseHiReg  := bitmapBaseHiPend;  bitmapBaseHiPendHit  := False }
    when(attrBaseLoPendHit)    { attrBaseLoReg    := attrBaseLoPend;    attrBaseLoPendHit    := False }
    when(attrBaseHiPendHit)    { attrBaseHiReg    := attrBaseHiPend;    attrBaseHiPendHit    := False }
    when(bitmapStridePendHit)  { bitmapStrideReg  := bitmapStridePend;  bitmapStridePendHit  := False }
    when(attrStridePendHit)    { attrStrideReg    := attrStridePend;    attrStridePendHit    := False }
    when(bitmapHeightPendHit)  { bitmapHeightReg  := bitmapHeightPend;  bitmapHeightPendHit  := False }
  }

  // I80-FRAME-ATOMIC-SWAP-145: vblank-atomic base swap. At the first cycle of
  // vblank (vCounter===vActive, hCounter===0) copy all four staged base words
  // to the live regs in ONE cycle, so the fetcher sees either all-old or
  // all-new bases (never a torn mix). Placed AFTER the per-register hCounter0
  // commit above, so on the rare cycle a legacy 0x0351-0x0354 write commits at
  // the same vblank edge, the atomic swap value wins (staged is authoritative).
  // Auto-clears the request and raises the sticky committed flag for the host.
  when(hCounter === U(0, log2Up(hTotal) bits) &&
       vCounter === U(vActive, log2Up(vTotal) bits) &&
       swapRequest) {
    bitmapBaseLoReg := bitmapBaseSwapLo
    bitmapBaseHiReg := bitmapBaseSwapHi
    attrBaseLoReg   := attrBaseSwapLo
    attrBaseHiReg   := attrBaseSwapHi
    swapRequest     := False
    swapCommitted   := True
  }
  io.layer0TileDecodeMode := tileDecodeModeReg
  io.layer0AttributeMode  := attributeModeReg

  // Task 31 — per-layer scroll tables. 128 entries × 10 bits indexed by
  // hCounter(9 downto 3) (one band per 8 pixels, covering 640-pixel
  // active area with 80 in-frame entries + off-edge). Bus decode:
  //   0x0900..0x097F = layer 0 table (subAddr bit 7 = 0)
  //   0x0980..0x09FF = layer 1 table (subAddr bit 7 = 1)
  val scrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
  val scrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
  val scrollTableRangeHit = effWrite &&
    (effAddr >= U(0x0900, 15 bits)) &&
    (effAddr <  U(0x0A00, 15 bits))
  val scrollTableSub  = (effAddr - U(0x0900, 15 bits))(7 downto 0)
  val scrollTableEntry = scrollTableSub(6 downto 0)    // 7 bits
  val scrollTableLayer = scrollTableSub(7)             // 0 = L0, 1 = L1
  // VDP-SOFT-RESET-135 #2b: scroll tables (128 entries each) zeroed by the sweep
  // for addr < 128 — both H-scroll and V-scroll tables share this gate below.
  val scrollSweepWr = softResetMemClear && (softResetMemAddr < U(128, 14 bits))
  scrollTable0.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), scrollTableEntry)
  scrollTable0.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  scrollTable0.io.wr     := Mux(scrollSweepWr, True, scrollTableRangeHit && !scrollTableLayer)
  scrollTable1.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), scrollTableEntry)
  scrollTable1.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  scrollTable1.io.wr     := Mux(scrollSweepWr, True, scrollTableRangeHit && scrollTableLayer)

  val scrollTable0Addr = hCounter(9 downto 3).resize(7)
  val scrollTable1Addr = hCounter(9 downto 3).resize(7)
  scrollTable0.io.rdAddr := scrollTable0Addr
  scrollTable1.io.rdAddr := scrollTable1Addr
  val scrollTable0Offset = scrollTable0.io.rdData
  val scrollTable1Offset = scrollTable1.io.rdData

  // Task 46 — per-layer V-scroll tables. Structurally identical to the Task 31
  // H-scroll tables: 128 entries × 10 bits indexed by hCounter(9 downto 3);
  // each vertical band (~5 px wide across the 640-pixel active area) gets
  // its own Y offset added to `scrollY`. Default init-to-zero keeps existing
  // scenes bit-identical until host programs the table. Bus decode:
  //   0x0A00..0x0A7F = layer 0 V-scroll table (subAddr bit 7 = 0)
  //   0x0A80..0x0AFF = layer 1 V-scroll table (subAddr bit 7 = 1)
  val vScrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
  val vScrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
  val vScrollTableRangeHit = effWrite &&
    (effAddr >= U(0x0A00, 15 bits)) &&
    (effAddr <  U(0x0B00, 15 bits))
  val vScrollTableSub   = (effAddr - U(0x0A00, 15 bits))(7 downto 0)
  val vScrollTableEntry = vScrollTableSub(6 downto 0)    // 7 bits = 128 entries
  val vScrollTableLayer = vScrollTableSub(7)             // 0 = L0, 1 = L1
  // VDP-SOFT-RESET-135 #2b: V-scroll tables zeroed by the sweep (shared gate).
  vScrollTable0.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), vScrollTableEntry)
  vScrollTable0.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  vScrollTable0.io.wr     := Mux(scrollSweepWr, True, vScrollTableRangeHit && !vScrollTableLayer)
  vScrollTable1.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), vScrollTableEntry)
  vScrollTable1.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  vScrollTable1.io.wr     := Mux(scrollSweepWr, True, vScrollTableRangeHit && vScrollTableLayer)

  val vScrollTable0Addr = hCounter(9 downto 3).resize(7)
  val vScrollTable1Addr = hCounter(9 downto 3).resize(7)
  vScrollTable0.io.rdAddr := vScrollTable0Addr
  vScrollTable1.io.rdAddr := vScrollTable1Addr
  val vScrollTable0Offset = vScrollTable0.io.rdData
  val vScrollTable1Offset = vScrollTable1.io.rdData

  // Layer 0 (lower priority background).
  val layer0 = BasicPatternSource()
  layer0.io.x := hCounter.resize(10)
  layer0.io.y := fillLine
  layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset
  layer0.io.scrollY := io.layer0ScrollY + vScrollTable0Offset

  // Test pattern source: combinational standard patterns for task validation.
  val testPattern = TestPatternSource()
  testPattern.io.x := hCounter.resize(10)
  testPattern.io.y := fillLine
  testPattern.io.patternSelect := io.layer0TestPatternSelect

  // === Task 3 — Planar Fetch Hardening (Checkpoint C, audit PASS #9313) ===
  // Multi-plane bitplane fetch path for Mode0 L0. PlanarLineFetch
  // combines BitplaneRowFetch (sdram dout32 reader) + BitplaneReconstruct
  // (per-pixel bit assembly). When planarFetchEnable is set via
  // PLANAR_CTRL @ 0x0D4A, slot 2 of the scheduler grants this client
  // its SDRAM bandwidth (clientId=2 on sdramArbiter, wired in
  // TopTang20kHdmi). 5 planes × 320 pixels = 50 dout32 reads/line.
  // planeBaseAddr[0..4] register-bus addresses at 0x0D40..0x0D49.
  // Task 3 risk #2 mitigation: planeCount=4 (Atari ST low-res — 16
  // colors, 4 bitplanes). 5-plane build hit CLS placement wall
  // (797 unplaced REGs from BitplaneRowFetch.planeWords storage =
  // 5×10×32 = 1,600 FFs exceeding CLS density). 4-plane saves
  // 1×10×32 = 320 FFs and lands within budget. Per artifact scope-
  // guard, this provisional drop preserves Task 3's "integration lane"
  // intent without reopening the standalone PlanarLineFetch primitive.
  // 5/6-plane Amiga OCS / EHB coverage deferred to a follow-on lane
  // that refactors planeWords to Mem-backed storage.
  val PLANE_COUNT = 5
  val PLANE_PIXELS = 320
  val planarLineFetch = PlanarLineFetch(sdramCd = effectiveSdramCd, planeCount = PLANE_COUNT, planePixels = PLANE_PIXELS, addrWidth = 23)
  val planarCtrlReg     = Reg(Bits(16 bits)) init 0
  val planeBaseAddrReg  = Vec.fill(PLANE_COUNT)(Reg(UInt(23 bits)) init 0)
  val planarFetchEnable = planarCtrlReg(0)
  // simPublic taps for PlanarIntegrationSim probes
  planarCtrlReg.simPublic()
  for (p <- 0 until PLANE_COUNT) planeBaseAddrReg(p).simPublic()

  // Register-bus decode for plane base addresses (PLANE_COUNT planes × 2 words each, lo/hi).
  val planarPlaneRangeHit = effWrite &&
    (effAddr >= U(0x0D40, 15 bits)) && (effAddr < U(0x0D40 + 2 * PLANE_COUNT, 15 bits))
  val planarCtrlWriteHit  = effWrite && (effAddr === U(0x0D4A, 15 bits))
  val planarSubAddr = (effAddr - U(0x0D40, 15 bits))(3 downto 0)   // 0..9
  val planarPlaneIdx = planarSubAddr(3 downto 1)                   // 0..4
  val planarHiSel    = planarSubAddr(0)                            // 0=lo, 1=hi
  when(planarPlaneRangeHit) {
    switch(planarPlaneIdx) {
      for (p <- 0 until PLANE_COUNT) {
        is(U(p, 3 bits)) {
          when(!planarHiSel) {
            planeBaseAddrReg(p)(15 downto 0)  := effData.asUInt
          } otherwise {
            planeBaseAddrReg(p)(22 downto 16) := effData(6 downto 0).asUInt
          }
        }
      }
    }
  }
  when(planarCtrlWriteHit) {
    planarCtrlReg := effData
  }

  planarLineFetch.io.planeBaseAddr  := planeBaseAddrReg
  // #3 part 2c: surface planar bases + active gate for the soft-reset zero-fill.
  io.planeBaseAddr    := planeBaseAddrReg
  io.planarFillActive := planarFetchEnable
  // Trigger row fetch one cycle into the active region — the FSM has
  // until next-line's display reaches pixelIdx N to land word N
  // (lead-time ≈ 160 cycles even for the first dout32 word).
  // Task 3 #9351 fix: align FSM start with slot 2's widened window so the
  // FSM transitions to State.Issue at the same cycle the slot opens
  // (hTotal-160) rather than 80 cycles before — the prior `hCounter ===
  // hActive` (= hTotal-160 only when hTotal=800 and hActive=640, which
  // matches by coincidence) is preserved as-is for now since hActive
  // happens to equal hTotal-160 with the widened slot. Documented for
  // future-proofing if either constant changes.
  planarLineFetch.io.start          := planarFetchEnable && (hCounter === U(hTotal - 160, log2Up(hTotal) bits))
  planarLineFetch.io.pixelIdx       := (hCounter % U(PLANE_PIXELS)).resize(log2Up(PLANE_PIXELS))
  // BronzeGate #9366 Path A: PlanarLineFetch's row-fetch FSM lives in
  // `effectiveSdramCd` and consumes data_ready/dout32/busy natively in
  // that domain. The top-level wires `io.planarSdram*` directly with
  // sdram-domain signals (no BufferCC stack here). On single-clock sims
  // (effectiveSdramCd == pixel CD), the wiring degenerates trivially.
  planarLineFetch.io.sdramBusy      := io.planarSdramBusy
  planarLineFetch.io.sdramDataReady := io.planarSdramDataReady
  planarLineFetch.io.sdramDout32    := io.planarSdramDout32
  io.planarSdramRd   := planarLineFetch.io.sdramRd
  io.planarSdramAddr := planarLineFetch.io.sdramAddr

  // Task 15 fetch-control outputs. Atomic CDC pattern per 6626/6628:
  //   1) Pulse-harden fetchStart: widen to 4 pixel cycles so the SDRAM-side
  //      BufferCC (2-stage synchronizer) reliably samples it despite routing
  //      delay and phase alignment with the 40.5 MHz SDRAM clock.
  //   2) Atomic latch: capture fetchLine/scrolls into registers ONCE on the
  //      line-boundary strobe so the multi-bit CDC sees stable values between
  //      pulses. Sampling `(vCounter+3)` combinationally through BufferCC would
  //      let bits transition asynchronously during the sync, risking a "torn"
  //      scanline index on specific raster positions.
  // R3: Static fetch-slot scheduler replaces the reactive end-of-line strobe.
  // Reading-B scope: a single tile-client slot at hCounter==hTotal-1 preserves
  // the pre-R3 strobe timing bit-for-bit, so the existing Task-15 fetch path
  // is unchanged from a behavioral standpoint. Extra slots are wired disabled
  // and remain available for future clients (e.g. sprite-to-SDRAM) without
  // further structural change.
  val scheduler = FetchSlotScheduler(slotCount = 8)
  // Task 56 Checkpoint C: simPublic mirror so MultiLayerSdramFetchSim
  // Cases 4-5 can observe per-line slot-grant counts (proves L1 slot 3
  // fires after the CP-C scheduler retime and planar slot 2 coexists).
  val schedulerLineGrantCount = CombInit(scheduler.io.lineGrantCount).simPublic()
  val schedulerGrantClientId  = CombInit(scheduler.io.grantClientId).simPublic()
  scheduler.io.hCounter  := hCounter.resize(10)
  scheduler.io.lineStart := hCounter === 0
  // R4.1: multi-slot schedule for clientId=0 (tile+attribute fetch). Three
  // non-contiguous windows prove pause/resume across slot gaps:
  //   slot 0: grant at hblank-end strobe (hTotal-1), window covers hblank
  //           into the start of the next line — starts a fresh fetch cycle
  //   slot 1: mid-line burst for additional SDRAM bandwidth
  //   slot 2: late-line burst for cleanup reads
  // Grant fires at startH of each slot; only slot 0's grant is consumed by
  // the fetch FSM's sIdle transition, the others simply widen slotValid.
  scheduler.io.schedule(0).enabled  := True
  scheduler.io.schedule(0).clientId := U(0, 2 bits)
  scheduler.io.schedule(0).startH   := U(hTotal - 1, 10 bits)
  scheduler.io.schedule(0).endH     := U(hTotal - 1, 10 bits)
  // Per CyanPeak #6804: slot 1 widened to cover the full line so the fetch
  // engine has continuous SDRAM bandwidth. slotValid gating still exercises
  // the pause/resume path at the line/domain boundary (grant → slot 0 pulse,
  // slotValid open for the whole line). Slot 2 disabled — the "thin lines"
  // artifact was caused by the prior h=[320,399] bandwidth gap.
  scheduler.io.schedule(1).enabled  := True
  scheduler.io.schedule(1).clientId := U(0, 2 bits)
  scheduler.io.schedule(1).startH   := U(0, 10 bits)
  // Task 56 Checkpoint C (#9678 §1 Resolution): narrow L0 burst window from
  // [0, hTotal-1] to [0, 399] so L1 burst slot 4 [400, hTotal-1] is exclusive
  // for clientId=3. L0 needs ~656 SDRAM cycles for 41 tiles ≈ 164 pixel cycles,
  // so 400 pixel cycles still gives ~2.4× margin (per artifact bandwidth table).
  scheduler.io.schedule(1).endH     := U(399, 10 bits)
  // Task 3 (Checkpoint A #9313): slot 2 dedicated to PlanarLineFetch
  // (clientId=2), gated on planarFetchEnable. Window covers H-blank
  // adjacent so 50 × dout32 reads for 5-plane × 320-pixel rows can be
  // granted without colliding with tile fetch's slot 0 (hTotal-1) or
  // slot 1 (full active line). FSM start is independent (mid-line)
  // per design packet §1.
  scheduler.io.schedule(2).enabled  := planarFetchEnable
  scheduler.io.schedule(2).clientId := U(2, 2 bits)
  // Task 3 #9351 fix (CoralReef bandwidth diagnosis): widen slot 2 from
  // 80 cycles (hTotal-80..hTotal-1) to 160 cycles (hTotal-160..hTotal-1).
  // 50 dout32 reads × 5 SDRAM cycles each = ~97 pixel-domain cycles
  // minimum; the 80-cycle window was below that floor. 160 cycles gives
  // headroom for FSM/CDC overhead and avoids deadlock when reads in
  // flight straddle the slot boundary.
  scheduler.io.schedule(2).startH   := U(hTotal - 160, 10 bits)
  scheduler.io.schedule(2).endH     := U(hTotal - 1,   10 bits)
  // Task 56 Checkpoint A — L1 fetch slots reserved on the scheduler
  // (clientId=3, per CyanPeak audit #9683 correction). Bandwidth plan
  // per artifact #9678:
  //   slot 3 (start):  hTotal-1   (single-cycle grant edge to start FSM)
  //   slot 4 (burst):  [400, hTotal-1]   (continuous bandwidth window)
  // Slots 0/1 still cover [hTotal-1] start and [0..hTotal-1] burst for
  // L0; FetchSlotScheduler resolves overlap by lowest-slot-index wins,
  // which gives Planar (slot 2) > L0 (slots 0/1) > L1 (slots 3/4) —
  // matches the audit-confirmed priority ranking. L1 FSM stalls in Rq
  // states during higher-priority overlap and resumes when slot 4 is
  // again exclusive.
  //
  // `enabled` is held False until the L1 SdramTileAttributeFetch
  // instance lands in Checkpoint B; at that point a new
  // `layer1FetchEnable` signal will gate this the same way Task 3
  // gates planar slot 2 on `planarFetchEnable`.
  // Task 56 Checkpoint B (#9678 / #9693): scheduler L1 slots enabled when
  // the top-level wires up an SDRAM-backed Layer 1 (clientId=3). When the
  // host runs an L1-disabled scene, `layer1UseSdram` is False so slots 3/4
  // stay gated off and the engine port stays inert at the arbiter.
  // NOTE on bandwidth: CP-A reserved slot 3 at hTotal-1 (collides with L0
  // slot 0) and L0 slot 1 still spans [0, hTotal-1] (full line). Under the
  // current scheduler, L1's grant edge is always shadowed by L0 → L1 FSM
  // stays in sIdle in practice. Checkpoint C will narrow L0 slot 1 to
  // [0, 399] and move L1 start slot to h=400 per artifact #9678 §1
  // "Resolution" plan. CP-B only proves the integration plumbing.
  val layer1FetchEnable = io.layer1UseSdram
  // PM #9907 Step 2: compile-time gate on L1 scaffolding. When
  // enableL1Fetch=false, the scheduler slot 3/4 entries collapse to disabled
  // tie-offs and the L1 fetch IO/registers below are likewise gated to
  // constant ties. This is a fit-stabilization probe — the L1 architectural
  // path stays available; only the surviving scaffolding is exercised.
  if (enableL1Fetch) {
    scheduler.io.schedule(3).enabled  := layer1FetchEnable
    scheduler.io.schedule(3).clientId := U(3, 2 bits)
    scheduler.io.schedule(3).startH   := U(400, 10 bits)
    scheduler.io.schedule(3).endH     := U(400, 10 bits)
    scheduler.io.schedule(4).enabled  := layer1FetchEnable
    scheduler.io.schedule(4).clientId := U(3, 2 bits)
    scheduler.io.schedule(4).startH   := U(400,        10 bits)
    scheduler.io.schedule(4).endH     := U(hTotal - 1, 10 bits)
    for (i <- 5 until 8) {
      scheduler.io.schedule(i).enabled  := False
      scheduler.io.schedule(i).clientId := U(0, 2 bits)
      scheduler.io.schedule(i).startH   := U(0, 10 bits)
      scheduler.io.schedule(i).endH     := U(0, 10 bits)
    }
  } else {
    for (i <- 3 until 8) {
      scheduler.io.schedule(i).enabled  := False
      scheduler.io.schedule(i).clientId := U(0, 2 bits)
      scheduler.io.schedule(i).startH   := U(0, 10 bits)
      scheduler.io.schedule(i).endH     := U(0, 10 bits)
    }
  }

  val fetchStartStrobe = scheduler.io.grant

  val fetchStartCount = Reg(UInt(3 bits)) init 0
  when(fetchStartStrobe) {
    fetchStartCount := 4
  }.elsewhen(fetchStartCount =/= 0) {
    fetchStartCount := fetchStartCount - 1
  }

  // R4.2-redo Early Latch fix (#7120 / #7121): latch fetch data ONE pixel-
  // cycle BEFORE the grant pulse so the multi-bit BufferCC synchronizers on
  // the SDRAM side see fully-stable operands when fetchGrantEdge fires.
  // Previously, reg update and grant coincided at hCounter=hTotal-1,
  // producing a classic source-domain race that manifested as systematic
  // wrong-bank scanlines at tile-row boundaries.
  val earlyLatchStrobe = hCounter === U(hTotal - 2, log2Up(hTotal) bits)
  val fetchLineReg    = RegNextWhen((vCounter + 3).resize(10),
                                    earlyLatchStrobe) init 0
  // Task 31: include scroll-table offset in the SDRAM-fetch scroll
  // snapshot. At `earlyLatchStrobe` hCounter is known, so the table
  // read produces a deterministic per-line offset. Full per-column
  // behaviour is visible only in the on-chip BasicPatternSource path;
  // the SDRAM fetch sees one offset per line.
  val fetchScrollXReg = RegNextWhen(
    (io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset).resize(10),
    earlyLatchStrobe) init 0
  val fetchScrollYReg = RegNextWhen(io.layer0ScrollY, earlyLatchStrobe) init 0

  io.layer0FetchStart       := fetchStartCount =/= 0
  // R4.1: only the "start-of-fetch-cycle" slot (slot 0 at hTotal-1) produces
  // the grant edge that transitions the fetch FSM from sIdle. The scheduler's
  // raw `grant` fires at every slot's startH, but secondary grants during a
  // line would reset the fetch mid-flight. Gate grant to the start strobe
  // only; let slotValid stay as the raw OR of all slot windows so reads can
  // span all three slots.
  // R4.2-redo Stage 2 (CyanPeak #7130): widen the grant pulse to 4 pixel
  // cycles so the SDRAM-side BufferCC reliably samples it after the bundled
  // fetch-data synchronizer has settled. Narrow 1-cycle pulses combined with
  // the bundled BufferCC's 2-cycle settling window gave the grant edge too
  // little margin on real silicon.
  val grantRaw  = scheduler.io.grant && (hCounter === hTotal - 1)
  val grantHold = Reg(UInt(3 bits)) init 0
  when(grantRaw) {
    grantHold := 4
  }.elsewhen(grantHold =/= 0) {
    grantHold := grantHold - 1
  }
  io.layer0FetchGrant       := grantHold =/= 0
  io.layer0FetchSlotValid   := scheduler.io.slotValid
  io.layer0FetchPreAnnounce := scheduler.io.preAnnounce
  io.layer0FetchGrantClientId := scheduler.io.grantClientId
  io.layer0FetchLine        := fetchLineReg
  io.layer0FetchScrollX     := fetchScrollXReg
  io.layer0FetchScrollY     := fetchScrollYReg
  /* Pre-advance pixelAddr by 1 cycle to compensate for the
   * SdramTileAttributeFetch / SdramTileFetch line-buffer `readSync`
   * latency. Without this, the leftmost active pixel of every scanline
   * paints with the previous clock's stale `readWord` (1-pixel bank-0
   * transient on the left edge — #10542/#10546).
   *
   * Mirrors the existing drainAddr pattern at line ~1610 (CyanPeak audit
   * #8760, sprite-pattern lane); explicit wrap at hTotal-1 → 0 so the
   * last active pixel (hCounter == hActive-1) reads mem[hActive-1] then
   * resets to 0 for the next line — without the conditional, the +1
   * would index past the line buffer's hActive-deep range. */
  val layer0FetchPixelAddrReg = UInt(10 bits)
  when(hCounter === hTotal - 1) {
    layer0FetchPixelAddrReg := U(0, 10 bits)
  }.elsewhen(hCounter < hActive - 1) {
    layer0FetchPixelAddrReg := (hCounter + 1).resize(10)
  }.otherwise {
    layer0FetchPixelAddrReg := U(0, 10 bits)
  }
  io.layer0FetchPixelAddr := layer0FetchPixelAddrReg

  // Task 56 Checkpoint B (#9678 / #9693): L1 fetch scheduler outputs.
  // Latch registers mirror the L0 earlyLatchStrobe pattern with `layer1*`
  // scroll inputs substituted. Grant pulse is gated on
  // `grantClientId === 3` so only L1's slot entries propagate to the L1
  // fetch engine; slotValid/preAnnounce are similarly client-id filtered
  // so the L1 FSM never sees an L0/Planar window as its own.
  if (enableL1Fetch) {
    val layer1FetchLineReg    = RegNextWhen((vCounter + 3).resize(10),
                                            earlyLatchStrobe) init 0
    val layer1FetchScrollXReg = RegNextWhen(
      (io.layer1ScrollX + scrollTable1Offset).resize(10),
      earlyLatchStrobe) init 0
    val layer1FetchScrollYReg = RegNextWhen(io.layer1ScrollY, earlyLatchStrobe) init 0

    val layer1GrantRaw  = scheduler.io.grant &&
                          (scheduler.io.grantClientId === U(3, 2 bits))
    val layer1GrantHold = Reg(UInt(3 bits)) init 0
    when(layer1GrantRaw) {
      layer1GrantHold := 4
    }.elsewhen(layer1GrantHold =/= 0) {
      layer1GrantHold := layer1GrantHold - 1
    }
    io.layer1FetchGrant         := layer1GrantHold =/= 0
    io.layer1FetchSlotValid     := scheduler.io.slotValid &&
                                   (scheduler.io.grantClientId === U(3, 2 bits))
    io.layer1FetchPreAnnounce   := scheduler.io.preAnnounce &&
                                   (scheduler.io.grantClientId === U(3, 2 bits))
    io.layer1FetchGrantClientId := scheduler.io.grantClientId
    io.layer1FetchLine          := layer1FetchLineReg
    io.layer1FetchScrollX       := layer1FetchScrollXReg
    io.layer1FetchScrollY       := layer1FetchScrollYReg
    io.layer1FetchPixelAddr     := hCounter.resize(10)
  } else {
    io.layer1FetchGrant         := False
    io.layer1FetchSlotValid     := False
    io.layer1FetchPreAnnounce   := False
    io.layer1FetchGrantClientId := U(0, 2 bits)
    io.layer1FetchLine          := U(0, 10 bits)
    io.layer1FetchScrollX       := U(0, 10 bits)
    io.layer1FetchScrollY       := U(0, 10 bits)
    io.layer1FetchPixelAddr     := U(0, 10 bits)
  }

  // Layer 1 (higher priority background).
  val layer1 = BasicPatternSource()
  layer1.io.x := hCounter.resize(10)
  layer1.io.y := fillLine
  layer1.io.scrollX := io.layer1ScrollX + scrollTable1Offset
  layer1.io.scrollY := io.layer1ScrollY + vScrollTable1Offset

  // Task 48 — Layer 2 and Layer 3. Simple BasicPatternSource layers with
  // global-only scroll (no per-column scroll tables or per-line enable —
  // those remain deferred). Compositor priority: L3 > L2 > L1 > L0 when
  // no L0 forcedPriority override is active; sprite slots still win via
  // the existing back-to-front iteration.
  //
  // Gate #2 (`enableL2L3`, default false): drop the L2/L3 BasicPatternSource
  // instances entirely from the default build. The `layer2/3ScrollX/Y` IO
  // ports remain declared on the bundle (zero hardware cost; they get
  // pruned at elaboration when nothing reads them) so TopTang20kHdmi can
  // wire them unconditionally. Downstream pixel/opaque signals are tied
  // off below to keep the compositor chain bit-identical to pre-Task-48
  // 2-layer behavior when the gate is off.
  val (layer2PixelRaw, layer3PixelRaw) = if (enableL2L3) {
    val layer2 = BasicPatternSource()
    layer2.io.x := hCounter.resize(10)
    layer2.io.y := fillLine
    layer2.io.scrollX := io.layer2ScrollX
    layer2.io.scrollY := io.layer2ScrollY

    val layer3 = BasicPatternSource()
    layer3.io.x := hCounter.resize(10)
    layer3.io.y := fillLine
    layer3.io.scrollX := io.layer3ScrollX
    layer3.io.scrollY := io.layer3ScrollY

    (layer2.io.pixelIndex, layer3.io.pixelIndex)
  } else {
    (B(0, 3 bits), B(0, 3 bits))
  }

  // Task 19 Checkpoint B: affine coordinate generator + texture BRAM. The
  // stepper runs combinationally against the current (hCounter, fillLine) so
  // its output is available in the same cycle as the existing layer0/layer1
  // sources. The texture is a 128×128 ROM-initialised Mem with async read.
  val affineStepper = AffineStepper()
  affineStepper.io.x := hCounter.resize(10)
  affineStepper.io.y := fillLine
  affineStepper.io.matrixA := affineAReg
  affineStepper.io.matrixB := affineBReg
  affineStepper.io.matrixC := affineCReg
  affineStepper.io.matrixD := affineDReg
  affineStepper.io.transX  := affineXReg
  affineStepper.io.transY  := affineYReg

  val affineTexture = Mem(Bits(8 bits), AffineAssets.Width * AffineAssets.Height)
    .init(AffineAssets.textureInit)
  val affineAddr  = (affineStepper.io.vInt ## affineStepper.io.uInt).asUInt
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — affine texture sample read
  // combinationally per pixel from the affine UV stepper; consumer is the
  // affineIndex/affineBank/affinePrio decomposition below feeding the L0 mux.
  // Candidate for readSync conversion + 1-cycle pipeline on the stepper output.
  val affinePixel = affineTexture.readAsync(affineAddr)
  val affineIndex = affinePixel(3 downto 0)
  val affineBank  = affinePixel(6 downto 4).asUInt
  val affinePrio  = affinePixel(7)

  // Task 15: runtime Layer-0 source mux. When layer0UseSdram is high, the
  // SDRAM-backed pixel from the external fetch engine feeds L0. The on-chip
  // BasicPatternSource is kept instantiated and reading as the comparison
  // baseline so A/B can happen on the same hardware image.
  // R4: L0 carries {index[4], bank[3], priority[1]} when driven by the R4
  // fetch engine; when fed by the on-chip 3bpp source we zero-extend the index
  // and force bank=0 / priority=0 to keep the legacy-path rendering identical.
  // Test-pattern override: when enabled, forces standard validation pattern
  // regardless of SDRAM or on-chip path state.
  val onChipIdx4   = layer0.io.pixelIndex.resize(4)
  // Task 44 — bitmap fetch pixel decoder.
  //
  val bitmapFetch = BitmapFetch()
  // Bitmap + attribute byte are sourced directly from the SDRAM-backed
  // BitmapRowFetch line buffers via the top-level wiring.
  val bmByteSel = io.bitmapSdramByte
  val bmAttrSel = io.bitmapSdramAttrByte
  bitmapFetch.io.bitmapByte      := bmByteSel
  bitmapFetch.io.attrByte        := bmAttrSel
  bitmapFetch.io.pixelWithinByte := hCounter(2 downto 0)
  bitmapFetch.io.bpp             := bitmapBpp
  // RGB565 directcolor (bpp=10): the 16-bit directcolor pixel is the two
  // fetched bytes packed {hi=attr, lo=bitmap}. CP-1b reuses the existing
  // bitmap+attr fetch as the lo/hi byte pair; CP-1c will widen
  // BitmapRowFetch to per-pixel (2-byte) addressing so each column has a
  // distinct RGB565 value (today they repeat across the fetcher's
  // 8-column byte span). The decoder raises directColorActive only for
  // bpp=0b10, so indexed bitmap modes are bit-unaffected.
  bitmapFetch.io.directPixel     := bmAttrSel ## bmByteSel

  // Export coupling signals to BitmapRowFetch at top level.
  io.bitmapSdramCol        := hCounter.resize(10)
  io.bitmapSdramFetchLine  := fillLine.resize(10)
  // RGB565-FULLFRAME-132 B.2 (CoralReef #12355 cond.4): grant ONCE PER SOURCE ROW,
  // not once per output line. Each source row is displayed on two output lines
  // (line-doubling: fillLine = vCounter+1, lineReg = pendingLine>>1), so the bank
  // rotation + fill-ahead geometry is only correct when the grant advances every
  // SECOND output line. Fire at hCounter==hTotal-1 (end of line, so the freshly
  // filled bank lands for the next line's pixel 0) gated on odd output lines
  // (vCounter(0)) and only within the active region (vCounter < vActive). The old
  // once-per-line hActive grant double-counted rows and broke the cadence.
  io.bitmapSdramFetchGrant := (hCounter === U(hTotal - 1, log2Up(hTotal) bits)) &&
                              (vCounter(0) === True) &&
                              (vCounter < U(vActive, log2Up(vTotal) bits))
  io.bitmapModeActive      := bitmapEnable
  // CP-1c: tell BitmapRowFetch to use the RGB565 directcolor fetch
  // schedule (2 bytes/pixel, 320 px/row) when bpp=0b10 is selected.
  // bpp=0b10 RGB565 directcolor and bpp=0b11 HAM (HAM-DECODER-171) both use the
  // directcolor fetch schedule (col/2, 320 source px/row, burst). HAM carries its
  // 6-bit code in the low byte of each source entry; the decode happens in the fill.
  io.bitmapDirectColor     := bitmapEnable && (bitmapBpp === U(2, 2 bits) || bitmapBpp === U(3, 2 bits))
  // BITMAP-PLUMB-129: assemble the 23-bit bases (HI##LO) and drive the
  // geometry outputs to BitmapRowFetch via the top level.
  io.bitmapBase            := (bitmapBaseHiReg ## bitmapBaseLoReg).asUInt
  io.attrBase              := (attrBaseHiReg   ## attrBaseLoReg).asUInt
  io.bitmapStride          := bitmapStrideReg
  io.attrStride            := attrStrideReg
  io.bitmapHeight          := bitmapHeightReg

  // Task 19: when affineEnable is high, the affine-texture lookup wins over
  // every other L0 source (test-pattern / SDRAM / on-chip). Task 44
  // inserts the bitmap-fetch path between affine and SDRAM; when
  // bitmapEnable=0 (default) the ordering and values are unchanged.
  // Task 3 — planar fetch is an additional L0 source. When
  // planarFetchEnable is set, the planar pixel (5 bits) projects to the
  // 4-bit L0 idx + 1-bit bank-select for Amiga OCS 32-color coverage:
  //   idx[3:0] := planarPixel[3:0]
  //   bank[0]  := planarPixel[4]   (other bank bits = 0 → palette banks 0/1)
  //   prio     := False (priority handled by adapter-local future work)
  // 5-plane pixel = 4-bit palette idx + 1-bit bank-select for Amiga OCS
  // 32-color coverage (idx[3:0] in palette banks 0/1).
  val planarPixel = planarLineFetch.io.pixel
  val planarIdx4  = planarPixel(3 downto 0)
  val planarBank3 = (B"00" ## planarPixel(4)).asUInt
  // 320-pixel planar clipping mask (PM #9736, MODE0_PLANNING.md §6 rank 3).
  // The planar source's native width is PLANE_PIXELS=320; `planarLineFetch
  // .io.pixelIdx` is driven `hCounter % 320`, which means planar output
  // wraps and repeats for hCounter in [320, 639]. Suppress the planar
  // contribution to L0 outside the [0, 320) window so the existing L0
  // source chain (affine → test pattern → bitmap → SDRAM → on-chip
  // BasicPatternSource with layer0ScrollX/Y) is preserved bit-identically
  // there. Consumer-side gate only — no planar fetch rewrite, no
  // scheduler change, no scroll-latch change.
  // #4: clip width is now the PLANAR_WIDTH register (default PLANE_PIXELS=320).
  val planarClipActive          = (hCounter < planarWidthReg.resize(log2Up(hTotal))).simPublic()
  val planarFetchEnableClipped  = (planarFetchEnable && planarClipActive).simPublic()
  val layer0Index = (Mux(planarFetchEnableClipped, planarIdx4,
                         Mux(affineEnable, affineIndex,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.pixelIndex,
                             Mux(bitmapEnable, bitmapFetch.io.pixelIndex.asBits,
                                 Mux(io.layer0UseSdram, io.layer0SdramPixel, onChipIdx4)))))).simPublic()
  val layer0Bank  = (Mux(planarFetchEnableClipped, planarBank3,
                         Mux(affineEnable, affineBank,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.paletteBank,
                             Mux(bitmapEnable, bitmapFetch.io.paletteBank,
                                 Mux(io.layer0UseSdram, io.layer0SdramBank,  U(0, 3 bits))))))).simPublic()
  val layer0Prio  = (Mux(planarFetchEnableClipped, False,
                         Mux(affineEnable, affinePrio,
                         Mux(io.layer0TestPatternEnable,
                             False,
                             Mux(bitmapEnable, False,
                                 Mux(io.layer0UseSdram, io.layer0SdramPriority, False)))))).simPublic()

  // R5: fold global LAYER_ENABLE register into the per-line linestate enable.
  // Task 48: L2/L3 use global enable only (bits 3/4) — LinestateStore is
  // NOT widened per artifact §3.5. bit 2 is sprite enable; unchanged.
  val effectiveL0Enable = linestate.io.layer0Enable && layerEnableReg(0)
  val effectiveL1Enable = linestate.io.layer1Enable && layerEnableReg(1)
  val effectiveL2Enable = layerEnableReg(3)
  val effectiveL3Enable = layerEnableReg(4)
  val layer0Pixel = Mux(effectiveL0Enable, layer0Index, B(0, 4 bits))
  val layer0PrioGated = effectiveL0Enable && layer0Prio
  // Task 56 Checkpoint A — L1 source mux. When `layer1UseSdram` is
  // asserted (driven by the L1 fetch engine in Checkpoint B), L1 takes
  // its pixel/bank/priority from the SDRAM-backed inputs; otherwise the
  // existing on-chip BasicPatternSource L1 path is preserved
  // bit-identically.  Compositor priority logic (L3 > L2 > L1 > L0) is
  // unchanged per artifact #9678 / audit #9683.
  val layer1Index = Mux(io.layer1UseSdram, io.layer1SdramPixel, layer1.io.pixelIndex.resize(4)).simPublic()
  val layer1Bank  = Mux(io.layer1UseSdram, io.layer1SdramBank,  U(0, 3 bits)).simPublic()
  val layer1Prio  = Mux(io.layer1UseSdram, io.layer1SdramPriority, False)

  val layer1Pixel = Mux(effectiveL1Enable, layer1Index, B(0, 4 bits))
  // Gate #2: when `enableL2L3=false`, `layer2PixelRaw`/`layer3PixelRaw`
  // are constant B(0,3 bits) (see L2/L3 instantiation block above) so
  // these Muxes degenerate to constant 0 → both opaque flags below stay
  // False → compositor reverts to the pre-Task-48 2-layer behavior.
  val layer2Pixel = Mux(effectiveL2Enable, layer2PixelRaw.resize(4), B(0, 4 bits))
  val layer3Pixel = Mux(effectiveL3Enable, layer3PixelRaw.resize(4), B(0, 4 bits))

  // Four-layer priority-aware composition. L0 forcedPriority override wins
  // over ALL layers (preserved from the 2-layer era). Otherwise, the
  // highest-index opaque layer wins (L3 > L2 > L1 > L0). When the only
  // visible layer is L0 (or nothing), L0 paints. This is bit-identical to
  // the pre-Task-48 2-layer compositor whenever L2/L3 are disabled (zero
  // pixel, not opaque).
  // #3: a layer pixel is opaque when its index differs from that layer's
  // transparency key (default key 0 ⇒ index-0-transparent, bit-identical).
  val layer0Opaque = layer0Pixel =/= l0TransKeyReg
  val layer1Opaque = layer1Pixel =/= l1TransKeyReg
  val layer2Opaque = layer2Pixel =/= l2TransKeyReg
  val layer3Opaque = layer3Pixel =/= l3TransKeyReg
  // Task 56 Checkpoint C: simPublic so MultiLayerSdramFetchSim Cases 3-5
  // can observe the compositor's actual mux output (proves L1>L0 opaque
  // priority and bank propagation under both-active workload).
  val composedBgIdx    = Bits(4 bits).simPublic()
  val composedBgBank   = UInt(3 bits).simPublic()
  val composedBgSource = UInt(3 bits)   // feeds fillMeta.layerSource
  when(layer0PrioGated && layer0Opaque) {
    composedBgIdx    := layer0Pixel
    composedBgBank   := layer0Bank
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }.elsewhen(layer3Opaque) {
    composedBgIdx    := layer3Pixel
    composedBgBank   := U(0, 3 bits)  // L3 uses legacy bank 0 like L1/L2
    composedBgSource := U(PixelMetadata.SourceBG3, 3 bits)
  }.elsewhen(layer2Opaque) {
    composedBgIdx    := layer2Pixel
    composedBgBank   := U(0, 3 bits)
    composedBgSource := U(PixelMetadata.SourceBG2, 3 bits)
  }.elsewhen(layer1Opaque) {
    composedBgIdx    := layer1Pixel
    // Task 56 — when L1 is fed by SDRAM the bank can be non-zero (4×16
    // colour banks of L0 mirror); falls back to bank 0 for the existing
    // on-chip BasicPatternSource path (bit-identical pre-Task-56).
    composedBgBank   := layer1Bank
    composedBgSource := U(PixelMetadata.SourceBG1, 3 bits)
  }.elsewhen(layer0Opaque) {
    // #11867 (CoralReef) ROOT-CAUSE FIX: the normal (non-priority) L0 paint path
    // was missing — only layer0PrioGated had a branch (the first `when`). A
    // non-priority opaque L0 (e.g. planar, whose layer0Prio is hardwired False at
    // :1376) fell through to .otherwise -> backdrop, so it never displayed. This
    // restores the compositor's own documented contract: "When the only visible
    // layer is L0 (or nothing), L0 paints." Opacity convention (index-0 transparent,
    // bank-ignored) is unchanged — see layer0Opaque @1416 / drainBgOpaque @1738.
    composedBgIdx    := layer0Pixel
    composedBgBank   := layer0Bank
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }.otherwise {
    // Backdrop: no layer is opaque (or all layers disabled). Display the
    // host-programmed BACKDROP_INDEX as an absolute 7-bit palette index.
    // Splitting it into bank[6:4] + idx[3:0] makes the downstream
    // `palette[bank*16+idx]` lookup map to palette[BACKDROP_INDEX] linearly.
    composedBgIdx    := backdropIndexReg(3 downto 0).asBits
    composedBgBank   := backdropIndexReg(6 downto 4)
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }
  val composedBg = composedBgIdx

  // Task 28: two-pass sprite evaluator over 32 descriptors, 8 visible per
  // line. Slots 0..3 come from the top-level sprite* inputs (backwards-
  // compat with TopTang20kHdmi scenarios + existing sims); slots 4..31
  // are Reg-backed and bus-programmable via the Mode0 register block at
  // 0x0800..0x083F. See SpriteEvaluator.scala for the slot layout and
  // the word-0 / word-1 packing.
  // Task 45 (BronzeGate #8189): restore sprite evaluator to full parametric
  // form. SpriteEvaluator case-class defaults are descCount=64, visiblePerLine=32.
  // Live instantiation is descCount=8, visiblePerLine=8 per Task 57 Path 5A.
  // 4 legacy IO slots + 4 bus-programmable extended slots.
  val spriteEval = SpriteEvaluator(
    // descCount=32 landed per BronzeGate #10363 (2026-05-19 lane).
    // The earlier descCount=16 PnR failure (`PR0003`, 7539 unplaced REGs)
    // and the 51 k-logic blowup at 32 were both artefacts of the old
    // readAsync descriptor-Mem substrate, which Gowin promoted to DFFs.
    // The storage-move redesign (#10357: descriptor Mems readAsync →
    // readSync/BSRAM) removed that promotion; the descCount=16/32
    // feasibility proof (#10360) showed both place, route, and meet
    // timing on Tang Nano with near-flat scaling. #10363 authorises
    // landing descCount=32 with visiblePerLine held at 8.
    descCount      = 32,
    visiblePerLine = 8,   // #10363: held at 8 (visible-per-line unchanged)
    patternSelBits = SpriteEvaluator.PatIdxWidth,   // Task 53 (#9419): 6 bits
    legacyIoCount  = 4)
  spriteEval.io.descX(0)          := io.sprite0X
  spriteEval.io.descY(0)          := io.sprite0Y
  spriteEval.io.descEnabled(0)    := io.sprite0Enabled
  spriteEval.io.descPatternIdx(0) := io.sprite0PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(1)          := io.sprite1X
  spriteEval.io.descY(1)          := io.sprite1Y
  spriteEval.io.descEnabled(1)    := io.sprite1Enabled
  spriteEval.io.descPatternIdx(1) := io.sprite1PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(2)          := io.sprite2X
  spriteEval.io.descY(2)          := io.sprite2Y
  spriteEval.io.descEnabled(2)    := io.sprite2Enabled
  spriteEval.io.descPatternIdx(2) := io.sprite2PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(3)          := io.sprite3X
  spriteEval.io.descY(3)          := io.sprite3Y
  spriteEval.io.descEnabled(3)    := io.sprite3Enabled
  spriteEval.io.descPatternIdx(3) := io.sprite3PatternIdx.resize(SpriteEvaluator.PatIdxWidth)

  // Mode0RegBus decode for 0x0800..0x08FF → evaluator bus-write port.
  // Task 37 extended layout: 8 words per slot (word 0..7 = enable/pat/aff/y,
  // x, matA, matB, matC, matD, transX, transY). Task 45 restores full scale:
  // 32 slots × 8 words = 256 addresses (0x0800..0x08FF). slot = subAddr[7:3]
  // (5 bits → 32 slots), word = subAddr[2:0] (unchanged).
  // Kept bit-identical for scenario 28 and any host firmware that hardcodes
  // `slot*8 + word`.
  val spriteBusRangeHit = effWrite &&
    (effAddr >= U(0x0800, 15 bits)) &&
    (effAddr <  U(0x0900, 15 bits))
  val spriteBusSub    = (effAddr - U(0x0800, 15 bits))(7 downto 0)
  val spriteBusSlot8  = spriteBusSub(7 downto 3).resize(spriteEval.descIdxBits)
  val spriteBusWord8  = spriteBusSub(2 downto 0).resize(spriteEval.busWordBits)

  // Sprite Envelope Hardening (CyanPeak #8577): word 8 lives in a
  // separate bus block so the legacy 8-words-per-slot map above stays
  // intact. 0x0D20..0x0D3F = 32 slots × 1 word (word 8 only).
  // slot = subAddr[4:0]. busWord forced to 8.
  // (Phase 2 fix: original 0x0900..0x091F conflicted with L0 scroll
  // table; 0x0C00..0x0C1F conflicted with the Blitter control range
  // 0x0C00..0x0D0F; 0x0D20 is in the free post-Blitter region.)
  val spriteExtBusRangeHit = effWrite &&
    (effAddr >= U(0x0D20, 15 bits)) &&
    (effAddr <  U(0x0D40, 15 bits))
  val spriteExtBusSlot = (effAddr - U(0x0D20, 15 bits))(4 downto 0)
    .resize(spriteEval.descIdxBits)

  spriteEval.io.busSlot := Mux(spriteExtBusRangeHit, spriteExtBusSlot, spriteBusSlot8)
  spriteEval.io.busWord := Mux(spriteExtBusRangeHit, U(8, spriteEval.busWordBits bits),
                                                     spriteBusWord8)
  spriteEval.io.busData := effData
  spriteEval.io.busWr   := spriteBusRangeHit || spriteExtBusRangeHit
  // VDP-SOFT-RESET-135 #2e: drive the sprite ext-descriptor clear from the sweep.
  spriteEval.io.softClear     := softResetMemClear
  spriteEval.io.softClearAddr := softResetMemAddr

  // Pass 1 strobe at end of line — evaluator takes descCount cycles to
  // complete (well under hBlank = 160 cycles at 640×480@60).
  // Shift strobe earlier by descCount cycles so the scan completes before
  // the next line begins drawing.
  spriteEval.io.evalLine  := (fillLine + 1).resize(10)
  // Scan start shifted earlier by descCount+margin so the sequential
  // Pass-1 FSM completes before the line-fill swap. Task 45 descCount=32
  // needs ~32 cycles; hTotal-45 gives a 13-cycle completion margin before
  // the swap at hTotal-1 (476 ns at 25.2 MHz, well within hBlank=160).
  spriteEval.io.evalStart := hCounter === U(hTotal - 77, log2Up(hTotal) bits)
  io.spriteOverflow := spriteEval.io.overflowFlag

  // Sprite Pattern Memory Foundation (CyanPeak #8596): BSRAM-backed
  // pattern RAM, replicated **per slot** so each Mem has exactly one read
  // port (writeFirst SDP) and infers cleanly to a Gowin BSRAM tile.
  // A single shared 4096×4-bit Mem with NUM_SLOTS read ports could not be
  // inferred — Gowin fell back to 16,384 DFFs and exceeded the chip
  // budget. Per-slot replication uses NUM_SLOTS BSRAM tiles (each
  // 16 kbit) but stays within the `MODE0_STOPLINES.md` BSRAM ceiling of
  // 23/46 with the current 7-tile baseline.
  //
  // All NUM_SLOTS Mems share identical contents at all times — bus writes
  // are broadcast to every Mem so the host sees one logical pattern table.
  // Address layout per Mem: {patternIndex[3:0], row[3:0], col[3:0]} =
  // 12 bits → 16 unique 16×16 patterns. Slots 0/1 pre-initialise with the
  // legacy diamond / cross so any existing scenario that selects
  // patternIndex 0 or 1 sees bit-identical pixels.
  // Per-slot Mems use **readSync** (not readAsync) so Gowin can infer them
  // as BSRAM tiles. readAsync on 4096-entry Mems forced 16,384-DFF
  // distributed-RAM synthesis which exceeded the chip's 15,915-DFF budget.
  // The cost of readSync is one extra clock of latency — `pixel` now
  // arrives one cycle after `ramAddr` is presented, which is compensated
  // for by registering the slot-visible flag and slot pixel below.
  // Task 2a Checkpoint 2 Step 2: trimmed from 8 per-slot Mems to 1 shared
  // Mem. The sequential rasterizer (single read port) replaces the parallel
  // per-slot for-loop's NUM_SLOTS read ports.
  val spritePatternRams = (0 until 1).map { _ =>
    Mem(Bits(4 bits), initialContent = VdpTop.spritePatternRamInit)
  }
  spritePatternRams.head.simPublic()    // mem visible for sim probes

  // Bus interface for runtime pattern RAM writes.
  //   0x0B00 (single word): pointer write — sets `patternRamPtr[11:0]` to
  //                         data[11:0]. Use this before a streaming load.
  //   0x0A00 (single word): data write — writes data[3:0] as the next 4-bit
  //                         pixel at the current pointer, then increments
  //                         the pointer (wraps mod 4096). Stream out a
  //                         16×16 pattern with one pointer-set + 256
  //                         data writes.
  // Bus addresses relocated to 0x0D10/0x0D11 — Phase 1's original
  // 0x0A00/0x0B00 collided with V-scroll-table (0x0A00..0x0AFF) and
  // the DMA control range (0x0B00..0x0B4F). 0x0D10 is free per the
  // Blitter range ending at 0x0D0F.
  val patternRamPtrWriteHit  = effWrite && (effAddr === U(0x0D11, 15 bits))
  val patternRamDataWriteHit = effWrite && (effAddr === U(0x0D10, 15 bits))
  // Task 53 (#9419): pointer widened 12→14 to address the new
  // 16384-entry pattern RAM (64 unique 16×16 tiles, Option A).
  val patternRamPtr = Reg(UInt(14 bits)) init 0
  when(patternRamPtrWriteHit) {
    patternRamPtr := effData(13 downto 0).asUInt
  }.elsewhen(patternRamDataWriteHit) {
    patternRamPtr := patternRamPtr + 1
  }
  // Broadcast write — every per-slot Mem must observe the same write so the
  // logical pattern table stays consistent across slots.
  // VDP-SOFT-RESET-135 #2a: pattern RAM write port muxed between host streaming
  // writes and the soft-reset zero-sweep (full 16384-entry clear).
  for (mem <- spritePatternRams) {
    mem.write(
      address = Mux(softResetMemClear, softResetMemAddr, patternRamPtr),
      data    = Mux(softResetMemClear, B(0, 4 bits), effData(3 downto 0)),
      enable  = softResetMemClear || patternRamDataWriteHit
    )
  }

  val fillX = hCounter.resize(10)

  // Sprite Phase 2 — P2-1 (CyanPeak #8614): the 1-cycle latency from
  // `readSync` on the per-slot pattern Mems would otherwise shift sprite
  // output right by 1 pixel relative to the line-buffer write address.
  // Pre-advance the address-gen / hitbox `fillX` by 1 so the pixel that
  // arrives at cycle T+1 corresponds to lineBuf write position T+1
  // (rather than T+1's read of T-cycle content). Pixel-accurate vs.
  // pre-Pattern-Memory baseline.
  val fillXAhead = (fillX + 1).resize(10)

  // Per active-slot pixel resolution (Task 28 — widened 2 → 8 slots).
  // patternIndex is now 4 bits; the low bit selects pattern Mem 0 vs 1 for
  // this task. Wider pattern-Mem banks land in a future sprite-attribute
  // extension task (Task 37), so bits [3:1] are ignored here.
  val NUM_SLOTS = 8  // Task 57 Path 5A (CyanPeak #9605): match evaluator visiblePerLine=8

  // === Task 2a Checkpoint 2 — Step 1 (PM #9244): SpriteRasterizer wired in
  // parallel to the existing per-slot pipeline. The rasterizer's drain
  // output is captured for inspection (simPublic) but NOT yet consumed by
  // the lineBuf write. Step 2 (next commit) cuts over and removes the
  // parallel for-loop + tree merge below.
  // ============================================================
  val spriteRasterizer = SpriteRasterizer(
    visiblePerLine = NUM_SLOTS,
    patternSelBits = SpriteEvaluator.PatIdxWidth,   // Task 53 (#9419): 6 bits
    hActive = hActive,
    cycleBudget = 798
  )
  // Task 2c Checkpoint E: narrow Evaluator → Rasterizer link via the
  // packed active-list RAM read port. Replaces 16 wide active* Vec
  // wires (~250 wires for V=8, ~4,500 for V=32) with a 3-wire bundle
  // (addr → eval, data ← eval, count ← eval).
  spriteEval.io.activeReadAddr := spriteRasterizer.io.activeReadAddr
  spriteRasterizer.io.activeReadData := spriteEval.io.activeReadData
  spriteRasterizer.io.activeCount    := spriteEval.io.activeCountOut
  spriteRasterizer.io.firstMaskSlot  := spriteEval.io.firstMaskSlot   // Task 55
  // Pattern Mem read interface — share with spritePatternRams(0). Adds a
  // second readSync port; Gowin will handle inference (LUTRAM fallback or
  // dual-port BSRAM split). Step 2 trims spritePatternRams to a single
  // shared instance.
  spriteRasterizer.io.patternRamData := spritePatternRams(0).readSync(spriteRasterizer.io.patternRamAddr)
  // Per-line trigger: fire at hCounter=hTotal-12, just after SpriteEvaluator
  // scan completes (evalStart at hTotal-45 + descCount=32 → done at
  // hTotal-13). active* are stable from hTotal-12 onward for the line-N+2
  // (= fillLine+1) target.
  spriteRasterizer.io.lineRenderStart := hCounter === U(hTotal - 12, log2Up(hTotal) bits)
  spriteRasterizer.io.fillLineY       := fillLine.resize(10)
  // Buffer swap aligned with the existing lineBuf swap.
  spriteRasterizer.io.bufferSwap      := hCounter === U(hTotal - 1, log2Up(hTotal) bits)
  // Drain addr — for Step 1, just feed hCounter (rasterizer drain is not
  // yet consumed downstream; this exists so the drain mux/registers
  // toggle and the module elaborates cleanly).
  // drainAddr is forward-declared; assigned in the bg-only compositor block below.
  val drainAddr = UInt(log2Up(hActive) bits)
  spriteRasterizer.io.drainAddr       := drainAddr
  // Expose drain outputs for sim inspection.
  spriteRasterizer.io.drainPixel.simPublic()
  spriteRasterizer.io.drainPaletteBank.simPublic()
  spriteRasterizer.io.drainPriority.simPublic()
  spriteRasterizer.io.drainSlot0.simPublic()
  spriteRasterizer.io.cycleOverflow.simPublic()
  // Task 54 — collision write-time pulse + participating descriptor IDs.
  spriteRasterizer.io.spriteSpriteHit.simPublic()
  spriteRasterizer.io.spriteSpriteHitDescA.simPublic()
  spriteRasterizer.io.spriteSpriteHitDescB.simPublic()
  spriteRasterizer.io.drainDescIdx.simPublic()
  // ============================================================

  // === Task 2a Checkpoint 2 Step 2 cutover (PM #9244): bg-only fillPacked ===
  // The parallel per-slot for-loop and Checkpoint 1 tree merge are replaced
  // by the SpriteRasterizer (instantiated above) producing the sprite drain.
  // The lineBuf now holds bg-only content; bg + sprite are composited at
  // drain time below.

  // bg-only compositor (single-cycle combinational; no merge pipeline).
  val bgPriorityHigh = layer0PrioGated && layer0Opaque &&
                       !layer1Opaque && !layer2Opaque && !layer3Opaque
  val fillIdx    = composedBgIdx
  val fillBank   = composedBgBank
  val fillSource = composedBgSource
  val fillPrio   = bgPriorityHigh
  val fillPixel  = (fillPrio ## fillBank.asBits ## fillIdx).asBits

  val fillMeta = PixelMetadata()
  fillMeta.mathEnable     := False
  fillMeta.forcedPriority := False
  fillMeta.layerSource    := fillSource
  val fillPacked = (fillMeta.toBits ## fillPixel).asBits

  val lineBuf = LineBuffer(pixelWidth = 8 + PixelMetadata.Width, lineWidth = hActive)
  lineBuf.io.writeEnable := hCounter < hActive
  lineBuf.io.writeAddr   := hCounter.resize(log2Up(hActive))
  lineBuf.io.writeData   := fillPacked
  lineBuf.io.swap        := hCounter === hTotal - 1

  // RGB565 directcolor (CP-1b): a parallel line buffer carrying the
  // 24-bit directcolor RGB plus its active flag {active, rgb[23:0]}.
  // Wired write/read/swap identically to `lineBuf` so it inherits the
  // same double-buffering and the same fill→drain line latency — the
  // drained directcolor pixel lands in the same cycle as `paletteRgb`.
  // The fill-side value is the 565→888-expanded pixel from BitmapFetch,
  // gated by bitmapEnable so non-bitmap scenes never see directcolor.
  // HAM-DECODER-171: HAM SET base colours mirror palette[0..15] truncated to 4:4:4
  // (distributed regs → 0 BSRAM; avoids a 2nd palette read port). The mirror-write
  // lives with the palette commit logic below. Host must load palette[0..15] for HAM.
  val hamBase = Vec(Reg(Bits(12 bits)) init 0, 16)

  // HAM-DECODER-171: Amiga HAM6 decode shares the directcolor carrier (dcLineBuf +
  // bypass mux). HAM (bpp=0b11) reuses the directcolor fetch (col/2, 320 source px
  // ×2-stretched); the 6-bit HAM code = low 6 bits of the fetched bitmap byte. The
  // decoder advances every fill column — the ×2 source repeat is harmless because
  // re-applying the same HAM code is idempotent (SET reloads base; modify rewrites a
  // channel to the same value → fixed point), so both columns of a pair agree.
  val hamMode    = bitmapEnable && (bitmapBpp === U(3, 2 bits))
  val hamCode    = bmByteSel(5 downto 0)
  val hamDecoder = HamDecoder()
  hamDecoder.io.lineStart := hCounter === hTotal - 1   // reset hold one cycle before col 0
  // HAM-DECODER-171 CP-D (CyanPeak #12998 / PM #12999): the directColor read path
  // (col/2 + readSync) presents each source byte on a PAIR of display columns. HAM's
  // accumulator is STATEFUL, so stepping every display column applies each code twice
  // (and steps at col 0 on stale data) → modify-chain desync (~0.69 match). Step ONCE
  // per source pixel by gating on ODD columns (hCounter(0)); this also avoids stepping
  // at col 0 (even) without an extra guard. (Stateless RGB565 is unaffected by the
  // doubling, so this gate is HAM-only and lives here, not in the shared fetch.)
  // Step once per source pixel, starting at column `hamStepStart` and every 2 cols after
  // (parity = parity of hamStepStart). lineStart already reset hold at hTotal-1; the no-step
  // idle from col 0..hamStepStart-1 holds the seed, so the first step consumes the first
  // VALID source byte rather than stale data.
  hamDecoder.io.step := (hCounter >= hamStepStart) && (hCounter < hActive) &&
                        (hCounter(0) === Bool((hamStepStart & 1) == 1))
  hamDecoder.io.code      := hamCode
  hamDecoder.io.baseColor := hamBase(hamCode(3 downto 0).asUInt)
  hamDecoder.io.seedColor := hamBase(0)

  // RGB565 directcolor (CP-1b) + HAM share this parallel line buffer carrying the
  // 24-bit RGB plus its active flag {active, rgb[23:0]}, drained co-timed with
  // `paletteRgb` and bypass-muxed at output.
  val dcFillActive = (bitmapEnable && (bitmapFetch.io.directColorActive || hamMode)).simPublic()
  val dcFillRgb    = Mux(hamMode, hamDecoder.io.rgb888, bitmapFetch.io.directRgb)
  val dcLineBuf = LineBuffer(pixelWidth = 25, lineWidth = hActive)
  // HAM-DECODER-171 CP-D (TopazCliff #12987 / CyanPeak #12986): shared bitmap write-
  // pipeline alignment. The fetch→select→decode path delivers `dcFillRgb` for source
  // column k some cycles AFTER hCounter==k (BitmapRowFetch readSync +1, registered
  // hCounter, etc.), so the dcLineBuf write address lagged its data → +N-column display
  // shift for BOTH HAM (bpp=0b11) and RGB565 directcolor (bpp=0b10), which share this
  // carrier. Delay the write addr/enable by `bitmapWritePipelineDelay` columns so that
  // the value computed for source k lands at dcLineBuf[k]. Compile-time param:
  //   0 = legacy (pre-fix, write addr == hCounter) — exact prior behavior.
  //   3 = measured-aligned (verified to 100% match by HamIntegrationSim).
  // Bounds stay Scala-Int constants (hActive/hTotal are Ints) so hCounter is compared
  // against literals — no width extension. writeEnable gates the underflow window when
  // hCounter < delay, so the wrapped writeAddr there is never committed.
  dcLineBuf.io.writeEnable := (hCounter >= bitmapWritePipelineDelay) && (hCounter < hActive + bitmapWritePipelineDelay)
  dcLineBuf.io.writeAddr   := (hCounter - bitmapWritePipelineDelay).resize(log2Up(hActive))
  dcLineBuf.io.writeData   := dcFillActive ## dcFillRgb
  dcLineBuf.io.swap        := hCounter === hTotal - 1

  // drainAddr was forward-declared above (for SpriteRasterizer). Assign here.
  // Present 1 cycle early for readSync alignment.
  when(hCounter === hTotal - 1) {
    drainAddr := U(0, log2Up(hActive) bits)
  }.elsewhen(hCounter < hActive - 1) {
    drainAddr := (hCounter + 1).resized
  }.otherwise {
    drainAddr := U(0, log2Up(hActive) bits)
  }
  lineBuf.io.readAddr := drainAddr
  // RGB565 directcolor: drain the parallel buffer on the same address as
  // `lineBuf` so the directcolor pixel and `paletteRgb` are co-timed.
  dcLineBuf.io.readAddr := drainAddr

  // Drain — combine bg (lineBuf) + sprite (rasterizer) at output time.
  // drainWord@T = bg pixel for hCounter@T (modulo wrap).
  // spriteRasterizer.io.drain*@T = sprite pixel for hCounter@T (same drainAddr).
  val drainWord = lineBuf.io.readData
  val drainMeta = PixelMetadata.fromBits(drainWord(8 + PixelMetadata.Width - 1 downto 8)).setName("drainMeta")
  drainMeta.mathEnable.simPublic()
  drainMeta.forcedPriority.simPublic()
  drainMeta.layerSource.simPublic()
  val drainBgIdx    = drainWord(3 downto 0).asUInt
  val drainBgBank   = drainWord(6 downto 4).asUInt
  val drainBgPrio   = drainWord(7)
  val drainBgOpaque = drainBgIdx =/= U(0, 4 bits)

  val drainSpriteIdx     = spriteRasterizer.io.drainPixel.asUInt
  val drainSpriteBank    = spriteRasterizer.io.drainPaletteBank
  val drainSpritePrio    = spriteRasterizer.io.drainPriority
  val drainSpriteIsSlot0 = spriteRasterizer.io.drainSlot0
  val drainSpriteOpaque  = drainSpriteIdx =/= U(0, 4 bits)

  // Sprite-wins predicate at drain time. Mirrors the prior `spriteWinsAt`
  // 4-tier rule (Phase 2-bis), evaluated against drained bg state.
  val drainSpriteTier     = drainSpritePrio
  val drainSpriteAbove    = drainSpriteTier(1)              // tier 2 or 3 → always above
  val drainSpriteMediumOk = drainSpriteTier === U(1, 2 bits) && (!drainBgOpaque || !drainBgPrio)
  val drainSpriteLowOk    = drainSpriteTier === U(0, 2 bits) && !drainBgOpaque
  val drainSpriteWins     = drainSpriteOpaque &&
                            (drainSpriteAbove || drainSpriteMediumOk || drainSpriteLowOk)

  val drainIdx    = Mux(drainSpriteWins, drainSpriteIdx, drainBgIdx)
  val drainBank   = Mux(drainSpriteWins, drainSpriteBank, drainBgBank)

  // Drain-time collision pulses (replaces the prior fill-time slotVisible-
  // based versions; PM #9244 (ii) preserves slot-0 specificity via the
  // rasterizer's drainSlot0 metadata bit).
  val sprite0HitPulse  = drainSpriteIsSlot0 && drainSpriteOpaque && drainBgOpaque
  val spriteBgHitPulse = drainSpriteOpaque && drainBgOpaque
  val anySlotVisible   = drainSpriteOpaque   // backward-compat alias

  // ============================================================
  // Below this point: legacy per-slot for-loop body has been removed.
  // The original block (val NUM_SLOTS=8 ... val fillPixel = ...) is
  // replaced by the SpriteRasterizer + drain compositor above.
  // ============================================================
  val paletteAddr = (drainBank @@ drainIdx).resize(log2Up(TileAttributeAssets.PaletteDepth))

  // Palette: 128-entry × 24-bit banked RGB lookup from TileAttributeAssets.
  // Bank 0 reproduces the pre-R4 16-color palette so the legacy L1 path and
  // sprite rendering are unchanged. Color/Window Hardening (#8629) makes the
  // RAM runtime-writable while preserving the legacy init content.
  //
  // Bus protocol (mirrors the sprite pattern RAM scheme at 0x0D10/0x0D11):
  //   0x0601 PALETTE_PTR  : sets paletteWritePtr[7:0] (entry × 2 + half)
  //   0x0600 PALETTE_DATA : auto-incrementing two-write entry commit
  //                          half=0 (even ptr): low 16 bits = G[7:0]:B[7:0]
  //                          half=1 (odd  ptr): low 8 bits  = R[7:0],
  //                                            commits {R,G,B} into entry
  // Two writes per entry; pointer wraps modulo 256. Hosts should sequence
  // bulk palette uploads inside vblank to avoid mid-frame visible flicker
  // (the readSync pixel path sees the new entry one pixel-clock later
  // than the second write completes — still visible on the next pixel
  // for vblank-paced uploads).
  val paletteWritePtr  = Reg(UInt(8 bits)) init 0
  val paletteWriteAcc  = Reg(Bits(16 bits)) init 0
  val palettePtrHit    = effWrite && (effAddr === U(0x0601, 15 bits))
  val paletteDataHit   = effWrite && (effAddr === U(0x0600, 15 bits))
  val paletteHalfHi    = paletteWritePtr(0)
  val paletteEntryIdx  = paletteWritePtr(7 downto 1)
  val paletteCommitNow = paletteDataHit && paletteHalfHi
  val paletteCommitData = effData(7 downto 0) ## paletteWriteAcc
  when(palettePtrHit) {
    paletteWritePtr := effData(7 downto 0).asUInt
  }.elsewhen(paletteDataHit) {
    when(!paletteHalfHi) {
      paletteWriteAcc := effData
    }
    paletteWritePtr := paletteWritePtr + 1
  }

  // HAM-DECODER-171: mirror palette[0..15] (8:8:8 → 4:4:4 truncation) into hamBase
  // on commit, so HAM SET codes index the base palette without a 2nd palette read
  // port (hamBase is distributed regs → 0 BSRAM). paletteCommitData = R##G##B (888).
  // CyanPeak #12958: also clear hamBase[0..15] during the soft-reset memory sweep
  // (matches the palette clear) so HAM SET base does not go stale after a soft reset.
  when(softResetMemClear) {
    when(softResetMemAddr < U(16, 14 bits)) {
      hamBase(softResetMemAddr(3 downto 0)) := B(0, 12 bits)
    }
  }.elsewhen(paletteCommitNow && (paletteEntryIdx < U(16, 7 bits))) {
    hamBase(paletteEntryIdx(3 downto 0)) :=
      paletteCommitData(23 downto 20) ## paletteCommitData(15 downto 12) ## paletteCommitData(7 downto 4)
  }

  val palette = Mem(Bits(24 bits), initialContent = TileAttributeAssets.paletteInit)
  // Lane #10686: force BSRAM inference (no LUT-RAM / distributed SSRAM).
  // The readAsync→readSync conversion below plus this attribute eliminates
  // the placement-sensitive prop-delay path that drove Gowin synthesis
  // non-determinism (4 distinct bitstream sha1s from identical source,
  // mail #10683 / #10652).
  palette.addAttribute("ram_style", "block")
  palette.simPublic()

  // Task 50 v3.3 — Palette mirror registers for the first 32 entries.
  // Mirroring the most-frequently-updated / low-index palette slots in
  // registers allows a zero-latency / async-free lookup for the border
  // display mux without adding a second read port to the palette Mem.
  // Adding a second readAsync port broke Gowin BSRAM inference in v3.0,
  // causing black-screen failure on hardware.
  val paletteMirror = Vec.fill(32)(Reg(Bits(24 bits)))
  for (i <- 0 until 32) {
    paletteMirror(i).init(TileAttributeAssets.paletteInit(i))
  }
  when(paletteCommitNow && paletteEntryIdx < 32) {
    paletteMirror(paletteEntryIdx.resize(5)) := paletteCommitData
  }
  // VDP-SOFT-RESET-135 #2a: zero the low-32 palette mirror regs during the sweep
  // (overrides the host commit above — host is mid-reset, polling completion).
  when(softResetMemClear && softResetMemAddr < U(32, 14 bits)) {
    paletteMirror(softResetMemAddr.resize(5)) := B(0, 24 bits)
  }

  // VDP-SOFT-RESET-135 #2a: palette write port muxed between host commit and the
  // soft-reset zero-sweep (single write port preserved for BSRAM inference).
  val paletteSweepWr = softResetMemClear && (softResetMemAddr < U(TileAttributeAssets.PaletteDepth, 14 bits))
  palette.write(
    address = Mux(softResetMemClear,
                  softResetMemAddr.resize(log2Up(TileAttributeAssets.PaletteDepth)),
                  paletteEntryIdx.resize(log2Up(TileAttributeAssets.PaletteDepth))),
    data    = Mux(softResetMemClear, B(0, 24 bits), paletteCommitData),
    enable  = Mux(softResetMemClear, paletteSweepWr, paletteCommitNow)
  )
  val paletteRgb = palette.readSync(paletteAddr)

  // R1 Raster Trigger Unit. Pending status is used below as a visible split
  // indicator (inverts the red channel after the trigger fires), which is the
  // mandated hardware proof signature from TASK_R1_RASTER_TRIGGER_UNIT.md.
  //
  // Beam Hardening BH-5 (#8656) extends this to 4 independent triggers.
  // TR0 keeps the existing top-level IO surface for backward compat with
  // sc0 / RasterTriggerUnitSim / VdpTopSim. TR1..TR3 are bus-addressable:
  //
  //   0x0360  TRIGGER1_LINE   (10 bits)
  //   0x0361  TRIGGER1_PIXEL  (10 bits)
  //   0x0362  TRIGGER1_CTRL   (bit[0]=enable, bit[1]=pixelCmpEnable,
  //                            bit[2]=clear-pending pulse)
  //   0x0364..0x0366  TRIGGER2_*
  //   0x0368..0x036A  TRIGGER3_*
  //   (offset 3 in each block reserved)
  //
  // All four trigger pulses are OR'd into evRasterMatch so the host sees
  // a single sticky bit (RASTER_MATCH) regardless of which trigger fired.
  // Per-trigger granularity is observable via rasterPendingMask (4 bits)
  // — wired to the existing top-level rasterTriggerPending IO output as
  // its OR for backward compat, and exposed individually as a 4-bit
  // bundle for downstream consumers.
  val rasterTrigger = RasterTriggerUnit()
  rasterTrigger.io.vCounter       := vCounter.resize(10)
  rasterTrigger.io.hCounter       := hCounter.resize(10)
  rasterTrigger.io.triggerLine    := io.rasterTriggerLine
  rasterTrigger.io.triggerPixel   := io.rasterTriggerPixel
  rasterTrigger.io.pixelCmpEnable := io.rasterTriggerPxEnable
  rasterTrigger.io.enable         := io.rasterTriggerEnable
  rasterTrigger.io.clear          := io.rasterTriggerClear
  io.rasterTriggerPulse           := rasterTrigger.io.triggerPulse

  // BH-5 extras (TR1..TR3) live behind `withExtraRasterTriggers`. Default
  // build (`false`) drops the per-trigger Regs, address-decode block, and
  // three additional RasterTriggerUnit instances. TR0 is unaffected.
  // The downstream-visible signals keep their shape so the IO contract
  // (`io.rasterTriggerPending`) and the `rasterPendingMask` simPublic tap
  // stay bit-stable for sims that don't toggle the extras.
  val extraTrigPending = Vec.fill(3)(Bool())
  val extraTrigPulse   = Vec.fill(3)(Bool())

  if (withExtraRasterTriggers) {
    // Per-trigger control register banks for TR1..TR3. Direct (non-shadow)
    // commits — the trigger compare is purely combinational on the
    // registers, so a host write that lands mid-frame just changes the
    // next-match condition without corrupting prior state.
    val tr1LineReg     = Reg(UInt(10 bits)) init 0
    val tr1PixelReg    = Reg(UInt(10 bits)) init 0
    val tr1CtrlReg     = Reg(Bits(3 bits))  init 0
    val tr2LineReg     = Reg(UInt(10 bits)) init 0
    val tr2PixelReg    = Reg(UInt(10 bits)) init 0
    val tr2CtrlReg     = Reg(Bits(3 bits))  init 0
    val tr3LineReg     = Reg(UInt(10 bits)) init 0
    val tr3PixelReg    = Reg(UInt(10 bits)) init 0
    val tr3CtrlReg     = Reg(Bits(3 bits))  init 0
    // Clear bits are pulse-style: they assert for one cycle when the host
    // writes a `1` to bit[2]. The Reg holds the rest of CTRL persistently;
    // the clear bit auto-deasserts the next cycle.
    val tr1Clear       = Bool()
    val tr2Clear       = Bool()
    val tr3Clear       = Bool()
    tr1Clear := False
    tr2Clear := False
    tr3Clear := False
    when(effWrite && effAddr === U(0x0360, 15 bits)) { tr1LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0361, 15 bits)) { tr1PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0362, 15 bits)) {
      tr1CtrlReg := effData(2 downto 0)
      tr1Clear   := effData(2)
    }
    when(effWrite && effAddr === U(0x0364, 15 bits)) { tr2LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0365, 15 bits)) { tr2PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0366, 15 bits)) {
      tr2CtrlReg := effData(2 downto 0)
      tr2Clear   := effData(2)
    }
    when(effWrite && effAddr === U(0x0368, 15 bits)) { tr3LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0369, 15 bits)) { tr3PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x036A, 15 bits)) {
      tr3CtrlReg := effData(2 downto 0)
      tr3Clear   := effData(2)
    }

    val rasterTrigger1 = RasterTriggerUnit()
    rasterTrigger1.io.vCounter       := vCounter.resize(10)
    rasterTrigger1.io.hCounter       := hCounter.resize(10)
    rasterTrigger1.io.triggerLine    := tr1LineReg
    rasterTrigger1.io.triggerPixel   := tr1PixelReg
    rasterTrigger1.io.pixelCmpEnable := tr1CtrlReg(1)
    rasterTrigger1.io.enable         := tr1CtrlReg(0)
    rasterTrigger1.io.clear          := tr1Clear

    val rasterTrigger2 = RasterTriggerUnit()
    rasterTrigger2.io.vCounter       := vCounter.resize(10)
    rasterTrigger2.io.hCounter       := hCounter.resize(10)
    rasterTrigger2.io.triggerLine    := tr2LineReg
    rasterTrigger2.io.triggerPixel   := tr2PixelReg
    rasterTrigger2.io.pixelCmpEnable := tr2CtrlReg(1)
    rasterTrigger2.io.enable         := tr2CtrlReg(0)
    rasterTrigger2.io.clear          := tr2Clear

    val rasterTrigger3 = RasterTriggerUnit()
    rasterTrigger3.io.vCounter       := vCounter.resize(10)
    rasterTrigger3.io.hCounter       := hCounter.resize(10)
    rasterTrigger3.io.triggerLine    := tr3LineReg
    rasterTrigger3.io.triggerPixel   := tr3PixelReg
    rasterTrigger3.io.pixelCmpEnable := tr3CtrlReg(1)
    rasterTrigger3.io.enable         := tr3CtrlReg(0)
    rasterTrigger3.io.clear          := tr3Clear

    extraTrigPending(0) := rasterTrigger1.io.pending
    extraTrigPending(1) := rasterTrigger2.io.pending
    extraTrigPending(2) := rasterTrigger3.io.pending
    extraTrigPulse(0)   := rasterTrigger1.io.triggerPulse
    extraTrigPulse(1)   := rasterTrigger2.io.triggerPulse
    extraTrigPulse(2)   := rasterTrigger3.io.triggerPulse
  } else {
    extraTrigPending.foreach(_ := False)
    extraTrigPulse.foreach(_ := False)
  }

  // Aggregate pending across all four — top-level pending output is OR
  // of the four for backward compat with the existing IO surface. When
  // the gate is off, bits[3..1] are tied False so the 4-bit shape and
  // simPublic tap stay stable for downstream consumers.
  val rasterPendingMask = (extraTrigPending(2) ##
                           extraTrigPending(1) ##
                           extraTrigPending(0) ##
                           rasterTrigger.io.pending).asBits
  rasterPendingMask.simPublic()
  io.rasterTriggerPending := rasterPendingMask.orR

  // -------------------------------------------------------------------
  // Task 35 — Host-Facing IRQ + Sticky Status Register Bank.
  //
  // Address map (within the 0x0320..0x032F reserved block per
  // MODE0_REGISTER_BUS_SPEC.md §3):
  //   0x0320  STATUS_STICKY  — read via QSPI sel=5; writes write-1-to-clear
  //   0x0321  STATUS_ENABLE  — IRQ mask (1 = bit contributes to irq)
  //
  // Sticky bit mapping (low byte, upper bits reserved for future events):
  //   bit 0 : RASTER_MATCH         — rasterTriggerPulse rising edge
  //   bit 1 : SPRITE_OVERFLOW      — spriteEval.overflowFlag pulse
  //   bit 2 : QSPI_READY           — QSPI cmd_valid pulse (command accepted)
  //   bit 3 : QSPI_ERROR           — QspiDecoder.last_error non-zero (level)
  //   bit 11: MODE_SELECT_CHANGED  — V=0 commit of MODE_SELECT @ 0x0313 (Task 1 #9154)
  //
  // Semantics:
  //   - Sticky bits SET on event pulse, PERSIST until write-1-to-clear.
  //   - QSPI_ERROR is level-triggered; sticky bit 3 follows the latched
  //     error state until host clears it AND the upstream error condition
  //     has also cleared (otherwise the bit re-asserts on the next cycle).
  //   - irq = (sticky & enable).orR — asserted while any enabled sticky
  //     bit is set; deasserts when host clears or disables the bit.
  //   - Safe-boundary commit: STATUS_ENABLE writes commit at hCounter===0
  //     per spec §4.1. Sticky bit sets propagate immediately (events are
  //     cycle-accurate and would be lost by a safe-boundary shadow).
  //   - Write-1-to-clear semantics for STATUS_STICKY: for each bit of the
  //     write data that is 1, the corresponding sticky bit clears. Bits
  //     written as 0 are preserved.
  // -------------------------------------------------------------------
  val statusStickyReg  = Reg(Bits(16 bits)) init 0
  val statusEnableReg  = Reg(Bits(16 bits)) init 0
  val statusEnablePend    = Reg(Bits(16 bits)) init 0
  val statusEnablePendHit = Reg(Bool()) init False

  // Event sources (low byte).
  // BH-5: any of the four triggers firing sets the sticky RASTER_MATCH bit.
  // When `withExtraRasterTriggers=false`, `extraTrigPulse` is tied False so
  // this collapses to TR0-only.
  val evRasterMatch    = rasterTrigger.io.triggerPulse ||
                         extraTrigPulse(0) ||
                         extraTrigPulse(1) ||
                         extraTrigPulse(2)
  val evSpriteOverflow = spriteEval.io.overflowFlag
  val evQspiReady      = io.statusEvQspiReady
  val evQspiError      = io.statusEvQspiError
  // Task 29 — extend event bus with sprite collision bits:
  //   bit 4: SPRITE_0_HIT   (sprite 0 non-transparent over non-transparent BG)
  //   bit 5: SPRITE_BG_HIT  (any sprite non-transparent over non-transparent BG)
  // Task 47 — DMA_DONE at bit 8 of the sticky word.
  // Task 49 — BLIT_DONE at bit 9 of the sticky word. Bit 10 (BLIT_BUSY) is
  // a live read-only signal (blitterEngine.io.busy) and does not flow into
  // the sticky pipeline; hosts that need the live state read it via a
  // future status-word read implementation.
  // Task 1 (#9154) — V=0 frame-atomic commit pulse for MODE_SELECT.
  // Fires for one cycle at the start of vsync (the unambiguous frame
  // boundary), per MODE_SELECT_ARCHITECTURE.md v1.1 §4.2 commit-boundary
  // rule. NOT the per-line hCounter===0 gate other safe-boundary regs
  // use, because mode switch must be frame-atomic to avoid split-frame
  // adapter-quiescence races.
  val modeCommitPulse = (vCounter === vSyncStart) && (hCounter === U(0, log2Up(hTotal) bits))
  when(modeCommitPulse && modeSelectPendHit) {
    modeSelectReg      := modeSelectPend
    modeSelectFlagsReg := modeSelectFlagsPend
    modeSelectPendHit  := False
    // §4.6.4 — Copper auto-disable on mode switch: stop the old program
    // immediately so the new mode starts with a clean copper state. The
    // host must upload a new copper program and re-enable.
    copperCtrlReg      := B(0, 1 bit)
    copperCtrlPendHit  := False
    // §4.6.5 — Optional MODE_FLAGS[0] auto-reset: clear LAYER_ENABLE so
    // the new mode starts with a clean visual slate.
    when(modeSelectFlagsPend(0)) {
      layerEnableReg    := B(0, layerEnableReg.getWidth bits)
      layerEnablePendHit := False
    }
  }
  // §4.2 — MODE_SELECT_CHANGED sticky event: one-cycle pulse at the V=0
  // commit if a pending mode write actually committed. Lets the host
  // poll for commit completion before issuing platform-specific traffic.
  // Sticky bit 11 (next free slot above blitterEngine.io.done at bit 9).
  val evModeSelectChanged = modeCommitPulse && modeSelectPendHit

  // Task 54 — SPRITE_SPRITE_HIT rollup pulse at bit 6 of STATUS_STICKY.
  // OR-reduction of the rasterizer's per-cycle collision pulse: any
  // sprite-sprite overlap pixel during the line sets the sticky bit;
  // host clears via W1C @ 0x0320 like the other sticky events.
  val evSpriteSpriteHit = spriteRasterizer.io.spriteSpriteHit

  val evBus = (B(0, 4 bits) ## evModeSelectChanged ## B(0, 1 bit) ##
               blitterEngine.io.done ## dmaEngine.io.done ##
               B(0, 1 bit) ## evSpriteSpriteHit ##
               spriteBgHitPulse ## sprite0HitPulse ##
               evQspiError ## evQspiReady ## evSpriteOverflow ## evRasterMatch).asBits

  // STATUS_ENABLE write (safe-boundary commit).
  when(effWrite && effAddr === U(0x0321, 15 bits)) {
    statusEnablePend    := effData
    statusEnablePendHit := True
  }

  // STATUS_STICKY write = write-1-to-clear. No shadow needed; clear is
  // an immediate action and cannot cause mid-line artifacts (it only
  // deasserts irq, it doesn't change visible pixel state).
  val statusClearMask = Bits(16 bits)
  statusClearMask := B(0, 16 bits)
  when(effWrite && effAddr === U(0x0320, 15 bits)) {
    statusClearMask := effData
  }

  // Sticky update: clear the host-requested bits FIRST, then set on any event
  // this cycle. If an event AND a clear both target the same bit in the same
  // cycle, the event WINS (new state takes precedence over the stale clear) —
  // matching the documented contract. (Bug 5, external review #13008: the prior
  // `(sticky | ev) & ~clear` form let clear win, dropping a same-cycle event.)
  // QSPI_ERROR uses the level directly so it re-asserts until the source clears.
  statusStickyReg := (statusStickyReg & (~statusClearMask)) | evBus

  // Safe-boundary commit of enable mask at hCounter===0.
  when(hCounter === U(0, log2Up(hTotal) bits)) {
    when(statusEnablePendHit) {
      statusEnableReg     := statusEnablePend
      statusEnablePendHit := False
    }
  }

  io.statusSticky := statusStickyReg
  io.irq          := (statusStickyReg & statusEnableReg).orR

  // -------------------------------------------------------------------
  // Task 54 — Sprite-Sprite Collision per-Descriptor Mask Register.
  //
  // Address map (within the 0x0320..0x032F STATUS block):
  //   0x0322  SPRITE_COLL_MASK — 8-bit per-descriptor sticky mask;
  //                              write-1-to-clear, read via io.spriteCollMask.
  //
  // Set semantics:
  //   - On every cycle the rasterizer asserts `spriteSpriteHit`, both
  //     `spriteSpriteHitDescA` (incoming sprite) and
  //     `spriteSpriteHitDescB` (existing sprite) bits are set in the
  //     mask. Reverse-iter draw order makes this OR-accumulation
  //     produce the canonical "every participating sprite has its bit
  //     set" semantic.
  //
  // Clear semantics:
  //   - Same write-1-to-clear pattern as STATUS_STICKY @ 0x0320: bits
  //     written as 1 clear; bits written as 0 are preserved. Sets and
  //     clears in the same cycle: set wins (event takes precedence).
  //
  // Rollup into STATUS_STICKY bit 6 (SPRITE_SPRITE_HIT) is wired below
  // by adding `spriteSpriteHit` into the evBus packing.
  // -------------------------------------------------------------------
  // Held at 8 bits per BronzeGate #10363 — NOT widened to descCount=32.
  // Hit-descriptor indices ≥8 alias into the low 3 bits (see io.spriteCollMask
  // comment). Widening is parked until a concrete product need is shown.
  val SpriteCollWidth = 8
  val spriteCollMaskReg = Reg(Bits(SpriteCollWidth bits)) init 0

  val spriteSpriteHit       = spriteRasterizer.io.spriteSpriteHit
  val spriteSpriteHitDescA  = spriteRasterizer.io.spriteSpriteHitDescA
  val spriteSpriteHitDescB  = spriteRasterizer.io.spriteSpriteHitDescB

  val collSetA = (B(1, SpriteCollWidth bits) |<<
                  spriteSpriteHitDescA.resize(log2Up(SpriteCollWidth)))
  val collSetB = (B(1, SpriteCollWidth bits) |<<
                  spriteSpriteHitDescB.resize(log2Up(SpriteCollWidth)))
  val collSetMask = Mux(spriteSpriteHit,
                        (collSetA | collSetB).resize(SpriteCollWidth),
                        B(0, SpriteCollWidth bits))

  val collClearMask = Bits(SpriteCollWidth bits)
  collClearMask := B(0, SpriteCollWidth bits)
  when(effWrite && effAddr === U(0x0322, 15 bits)) {
    collClearMask := effData(SpriteCollWidth - 1 downto 0)
  }

  // Bug 5 (external review #13008): clear FIRST then set, so a same-cycle
  // set wins (event takes precedence) — matches the documented contract above.
  spriteCollMaskReg := (spriteCollMaskReg & (~collClearMask)) | collSetMask
  io.spriteCollMask := spriteCollMaskReg

  // ===== VDP-SOFT-RESET-135 #4: core register reset (Stage 3 of the sequence) =====
  // Option B (surgical) per TopazCliff #12608 / CyanPeak #12609: while
  // `softResetCoreActive`, force every host-writable config register back to its
  // SpinalHDL `init` and clear its pend/commit hit so a mid-flight (uncommitted)
  // host write cannot land after the reset. Placed after ALL normal register
  // commit logic so it wins on the reset cycle (last-assignment-wins). The
  // soft-reset controller regs + i80/0x0310 status path are deliberately NOT
  // here — they stay LIVE to run the reset and keep the host poll alive.
  // Internal pipeline/counter regs (hCounter/vCounter/fillLine, copper pc, etc.)
  // are not reset; they re-settle within a frame (POR-equivalent; the sim proves
  // no visible artifact). Also clears STATUS_STICKY / STATUS_ENABLE (IRQ mask) /
  // sprite-collision mask so a stale flag or pending IRQ can't fire post-reset
  // (CyanPeak #12609).
  when(softResetCoreActive) {
    copperCtrlReg     := B(0, 1 bits);  copperCtrlPendHit     := False
    layerEnableReg    := B"00000";       layerEnablePendHit    := False
    tileDecodeModeReg := B(0, 2 bits);  tileDecodeModePendHit := False
    attributeModeReg  := B(0, 1 bits);  attributeModePendHit  := False
    backdropIndexReg  := U(0, 7 bits);  backdropIndexPendHit  := False
    scaleCtrlReg      := B(scaleCtrlInit, 8 bits);   scaleCtrlPendHit   := False
    logicWidthReg     := U(logicWidthInit, 11 bits); logicWidthPendHit  := False
    logicHeightReg    := U(logicHeightInit, 11 bits);logicHeightPendHit := False
    innerBorderLReg   := U(0, 10 bits); innerBorderLPendHit := False
    innerBorderRReg   := U(0, 10 bits); innerBorderRPendHit := False
    innerBorderTReg   := U(0, 10 bits); innerBorderTPendHit := False
    innerBorderBReg   := U(0, 10 bits); innerBorderBPendHit := False
    modeSelectReg     := U(0, 4 bits);  modeSelectFlagsReg := B(0, 8 bits); modeSelectPendHit := False
    winX0Reg := U(0, 10 bits); winX0PendHit := False
    winX1Reg := U(0, 10 bits); winX1PendHit := False
    winY0Reg := U(0, 10 bits); winY0PendHit := False
    winY1Reg := U(0, 10 bits); winY1PendHit := False
    colorMathReg := B(0, 16 bits); colorMathPendHit := False
    win2X0Reg := U(0, 10 bits); win2X0PendHit := False
    win2X1Reg := U(0, 10 bits); win2X1PendHit := False
    win2Y0Reg := U(0, 10 bits); win2Y0PendHit := False
    win2Y1Reg := U(0, 10 bits); win2Y1PendHit := False
    win2CtrlReg := B(0, 16 bits); win2CtrlPendHit := False
    winCombReg  := B(0, 16 bits); winCombPendHit  := False
    layerMaskReg := B(0, 16 bits); layerMaskPendHit := False
    borderX0Reg := U(0, 10 bits); borderX0PendHit := False
    borderX1Reg := U(0, 10 bits); borderX1PendHit := False
    borderY0Reg := U(0, 10 bits); borderY0PendHit := False
    borderY1Reg := U(0, 10 bits); borderY1PendHit := False
    borderCtrlReg := B(borderCtrlInit, 16 bits); borderCtrlPendHit := False
    affineAReg := B(0, 16 bits); affineAPendHit := False
    affineBReg := B(0, 16 bits); affineBPendHit := False
    affineCReg := B(0, 16 bits); affineCPendHit := False
    affineDReg := B(0, 16 bits); affineDPendHit := False
    affineXReg := B(0, 16 bits); affineXPendHit := False
    affineYReg := B(0, 16 bits); affineYPendHit := False
    affineCtrlReg := B(0, 16 bits); affineCtrlPendHit := False
    bitmapCtrlReg := B(0, 16 bits); bitmapCtrlPendHit := False
    bitmapBaseLoReg := U(0x3000, 16 bits); bitmapBaseLoPendHit := False
    bitmapBaseHiReg := U(0, 7 bits);       bitmapBaseHiPendHit := False
    attrBaseLoReg   := U(0x4000, 16 bits); attrBaseLoPendHit   := False
    attrBaseHiReg   := U(0, 7 bits);       attrBaseHiPendHit   := False
    bitmapStrideReg := U(512, 16 bits);    bitmapStridePendHit := False
    attrStrideReg   := U(512, 16 bits);    attrStridePendHit   := False
    bitmapHeightReg := U(240, 10 bits);    bitmapHeightPendHit := False
    // I80-FRAME-ATOMIC-SWAP-145: clear staged base double-buffer + swap flags.
    bitmapBaseSwapLo := U(0x3000, 16 bits)
    bitmapBaseSwapHi := U(0, 7 bits)
    attrBaseSwapLo   := U(0x4000, 16 bits)
    attrBaseSwapHi   := U(0, 7 bits)
    swapRequest      := False
    swapCommitted    := False
    planarCtrlReg := B(0, 16 bits)
    for (p <- 0 until PLANE_COUNT) planeBaseAddrReg(p) := U(0, 23 bits)
    // NOTE: extra raster triggers TR1-3 are conditionally instantiated
    // (withExtraRasterTriggers, off in the active i80/HW builds) and scoped inside
    // their own block — not resettable from here. If ever enabled, add their reset
    // inside that block keyed off softResetCoreActive.
    // CyanPeak #12609: clear sticky status / IRQ mask / collision mask so no
    // stale flag or pending interrupt survives the reset.
    statusStickyReg   := B(0, 16 bits)
    statusEnableReg   := B(0, 16 bits); statusEnablePendHit := False
    spriteCollMaskReg := B(0, spriteCollMaskReg.getWidth bits)
    // #3/#4 registers → init (transparency keys 0, planar width 320).
    l0TransKeyReg := B(0, 4 bits); l1TransKeyReg := B(0, 4 bits)
    l2TransKeyReg := B(0, 4 bits); l3TransKeyReg := B(0, 4 bits)
    planarWidthReg := U(320, 10 bits)
  }

  // R6 Task 20: post-palette color-math + window stage. Mux on `paletteRgb`
  // controlled by the window comparator and the colorMath op/constant fields.
  val windowUnit = WindowUnit()
  windowUnit.io.hCounter := hCounter.resize(10)
  windowUnit.io.vCounter := vCounter.resize(10)
  windowUnit.io.winX0    := winX0Reg
  windowUnit.io.winX1    := winX1Reg
  windowUnit.io.winY0    := winY0Reg
  windowUnit.io.winY1    := winY1Reg
  windowUnit.io.invert   := colorMathReg(13)

  // CW-5: second window comparator + combination logic. Defaults reduce
  // to legacy single-window behavior (combMode=0 → use window1 effect).
  val windowUnit2 = WindowUnit()
  windowUnit2.io.hCounter := hCounter.resize(10)
  windowUnit2.io.vCounter := vCounter.resize(10)
  windowUnit2.io.winX0    := win2X0Reg
  windowUnit2.io.winX1    := win2X1Reg
  windowUnit2.io.winY0    := win2Y0Reg
  windowUnit2.io.winY1    := win2Y1Reg
  windowUnit2.io.invert   := win2CtrlReg(0)

  val combMode = winCombReg(2 downto 0).asUInt
  val effect1  = windowUnit.io.effect
  val effect2  = windowUnit2.io.effect
  val combinedWindowEffect = combMode.mux(
    U(0, 3 bits) -> effect1,
    U(1, 3 bits) -> (effect1 && effect2),
    U(2, 3 bits) -> (effect1 || effect2),
    U(3, 3 bits) -> (effect1 ^ effect2),
    U(4, 3 bits) -> !(effect1 && effect2),
    U(5, 3 bits) -> !(effect1 || effect2),
    default      -> effect1
  )

  // CW-6: per-layer window mask. drainMeta.layerSource carries the
  // winning source ID (BG0..BG3=0..3, Sprite=4) selected at compose
  // time; if that layer's mask bit is set AND the combined window
  // effect is active here, the pixel is forced to black before
  // ColorMath. Default layerMaskReg=0 means no masking.
  val layerMaskBit    = layerMaskReg(drainMeta.layerSource(2 downto 0))
  val layerMaskActive = layerMaskBit && combinedWindowEffect

  // RGB565 directcolor bypass mux (CP-1b). The drained directcolor pixel
  // is co-timed with `paletteRgb`. When directcolor is active for this
  // pixel AND no sprite wins here, the 24-bit directcolor RGB replaces
  // the palette lookup — the bitmap layer is the background, sprites
  // still composite on top via the unchanged `drainSpriteWins` rule
  // (in directcolor mode the indexed bg reads as idx 0 / transparent,
  // so opaque sprites win naturally). Indexed modes: dcActive=0 → no-op.
  val dcDrained       = dcLineBuf.io.readData
  val dcActiveDrained = dcDrained(24).simPublic()
  val dcRgbDrained    = dcDrained(23 downto 0).simPublic()
  // Lane #10686 palette readSync compensation. paletteRgb is now +1 cycle
  // (readSync semantics). Delay every other input to this mux by 1 cycle
  // so all four inputs represent the same drain cycle. Pre-#10686 these
  // were combinationally co-timed with the old readAsync paletteRgb.
  // simPublic: these registered (2-cycle) outputs are co-timed with io.x/io.y and the
  // bypass mux below — co-sims MUST sample these, NOT the 1-cycle dcActiveDrained/
  // dcRgbDrained (which lead io.x by 1 col → false -1 column shift). CyanPeak #13009.
  val dcActiveDrainedR  = RegNext(dcActiveDrained)  init False        ; dcActiveDrainedR.simPublic()
  val dcRgbDrainedR     = RegNext(dcRgbDrained)     init B(0, 24 bits) ; dcRgbDrainedR.simPublic()
  val drainSpriteWinsR  = RegNext(drainSpriteWins)  init False
  val layerMaskActiveR  = RegNext(layerMaskActive)  init False
  val bgOrDirectRgb   = Mux(dcActiveDrainedR && !drainSpriteWinsR, dcRgbDrainedR, paletteRgb).simPublic()
  val maskedRgb       = Mux(layerMaskActiveR, B(0, 24 bits), bgOrDirectRgb)

  // CW Option 1 pipeline (CyanPeak #8649): register the new dual-window
  // / layer-mask combinational outputs before they enter ColorMath, so
  // the post-palette stage's combinational depth no longer pushes legacy
  // BG-layer paths over the line. Mirrors the P2-3a `slotPaletteBank`
  // pipeline that recovered Phase 2 timing. The 1-cycle latency at
  // ColorMath's input is matched by a 1-cycle shift on the display-side
  // sync/de/primed/raster-pending signals so the displayed pixel and
  // its sync envelope stay aligned.
  val combinedWindowEffectR = RegNext(combinedWindowEffect) init False
  val maskedRgbR            = RegNext(maskedRgb)            init B(0, 24 bits)
  val drainMetaMathEnR      = RegNext(drainMeta.mathEnable) init False
  val colorMathOpR          = RegNext(colorMathReg(15 downto 14).asUInt) init U(0, 2 bits)
  val colorMathConstR       = RegNext(colorMathReg(7 downto 0).asUInt)   init U(0, 8 bits)

  val colorMath = ColorMath()
  colorMath.io.rgbIn    := maskedRgbR
  colorMath.io.op       := colorMathOpR
  colorMath.io.constant := colorMathConstR
  // CW-3: per-pixel mathEnable metadata OR'd with the (possibly combined)
  // window effect, so individual line-buffer pixels can opt into color
  // math independent of the rectangular windows. Defaults all-zero
  // (line buffer drives False, combMode=0), so existing scenes are
  // unaffected.
  colorMath.io.enable   := combinedWindowEffectR || drainMetaMathEnR
  val mathRgb = colorMath.io.rgbOut

  // Task 50 v3 Slice 2 — visible-border window display mux.
  //
  // When BORDER_CTRL[0] is set, pixels OUTSIDE the rectangle
  // [borderX0, borderX1) × [borderY0, borderY1) are replaced by a
  // dedicated palette lookup. The border palette index is BORDER_CTRL
  // bits[12:8]; canonical assignment is slot 24 (written by the ZX
  // Spectrum adapter's border emitter). The replacement happens at
  // the same 1-cycle pipeline depth as the rest of the display
  // outputs (mathRgb / hsyncR / deR) — combinatorial border-active
  // and palette read are computed at cycle T from current
  // h/v/borderReg state, then registered to align with mathRgb at
  // cycle T+1.
  val borderEnable = borderCtrlReg(0)
  val borderIdx    = borderCtrlReg(12 downto 8).asUInt
  val innerBorderEnable = borderCtrlReg(1)
  // PixelRepeatScaler instantiation (lane #10590-reland, PM #10701).
  // Re-landed on top of the palette readSync fix (main @ 661907d) which
  // removed the Gowin placement-sensitivity that caused the original
  // intermittent black-HDMI. lineBuf write OOB-guard added per
  // BronzeGate #10697. POR scaleCtrlReg=0 yields 1x bypass (scaleX=1,
  // scaleY=1, autoCenter=0). Counters reset on the first cycle of
  // hsync/vsync (when hCounter/vCounter enter their respective sync
  // regions); we detect those edges combinationally here.
  val hsyncActive    = hCounter >= hSyncStart && hCounter < hSyncEnd
  val vsyncActive    = vCounter >= vSyncStart && vCounter < vSyncEnd
  val hsyncActivePrv = RegNext(hsyncActive) init False
  val vsyncActivePrv = RegNext(vsyncActive) init False
  val hsyncEdge      = hsyncActive && !hsyncActivePrv
  val vsyncEdge      = vsyncActive && !vsyncActivePrv
  val scaler = PixelRepeatScaler()
  scaler.io.hCounter     := hCounter.resize(10)
  scaler.io.vCounter     := vCounter.resize(10)
  scaler.io.hsyncRising  := hsyncEdge
  scaler.io.vsyncRising  := vsyncEdge
  scaler.io.hActive      := U(hActive, 11 bits)
  scaler.io.vActive      := U(vActive, 11 bits)
  scaler.io.scaleXReg    := scaleCtrlReg(2 downto 0).asUInt
  scaler.io.scaleYReg    := scaleCtrlReg(6 downto 4).asUInt
  scaler.io.autoCenter   := scaleCtrlReg(7)
  scaler.io.logicWidth   := logicWidthReg
  scaler.io.logicHeight  := logicHeightReg

  // Auto-center override of the host BORDER_X/Y0/1. Host BORDER_CTRL[12:8]
  // still picks the bezel palette slot. SCALE_CTRL[7] arms the override.
  //
  // INNER BORDER mode (BORDER_CTRL[1]): when set, the physical border
  // rectangle is auto-computed from INNER_BORDER_L/R/T/B (in logical pixels)
  // plus the scaler's effective scale factors. This lets the host set a
  // logical canvas resolution and inner border thickness without doing the
  // multiply-by-scale math in firmware. Inner border uses the same palette
  // index as the outer border (BORDER_CTRL[12:8]).
  val acActive    = scaleCtrlReg(7)
  val ibScaleX    = scaler.io.scaleXEffOut
  val ibScaleY    = scaler.io.scaleYEffOut
  val ibOffX      = scaler.io.acBorderX0
  val ibOffY      = scaler.io.acBorderY0

  // Defensive clamp: inner border thickness cannot exceed the logical canvas
  // on its own axis, and L+R (or T+B) cannot exceed the dimension. This
  // prevents silent unsigned-wrap misbehavior when the host writes out-of-range
  // values (BrightForge #11915 finding 1 / BronzeGate #11916 finding 1).
  val ibL = Mux(innerBorderLReg.resize(11) > logicWidthReg,  logicWidthReg,  innerBorderLReg.resize(11))
  val ibR = Mux(innerBorderRReg.resize(11) > logicWidthReg,  logicWidthReg,  innerBorderRReg.resize(11))
  val ibT = Mux(innerBorderTReg.resize(11) > logicHeightReg, logicHeightReg, innerBorderTReg.resize(11))
  val ibB = Mux(innerBorderBReg.resize(11) > logicHeightReg, logicHeightReg, innerBorderBReg.resize(11))
  val ibRSafe = Mux((ibL + ibR) > logicWidthReg,  logicWidthReg  - ibL, ibR)
  val ibBSafe = Mux((ibT + ibB) > logicHeightReg, logicHeightReg - ibT, ibB)

  val effBorderX0 = Mux(innerBorderEnable,
                        (ibOffX + (ibL * ibScaleX).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderX0, borderX0Reg)).simPublic()
  val effBorderX1 = Mux(innerBorderEnable,
                        (ibOffX + ((logicWidthReg  - ibRSafe) * ibScaleX).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderX1, borderX1Reg)).simPublic()
  val effBorderY0 = Mux(innerBorderEnable,
                        (ibOffY + (ibT * ibScaleY).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderY0, borderY0Reg)).simPublic()
  val effBorderY1 = Mux(innerBorderEnable,
                        (ibOffY + ((logicHeightReg - ibBSafe) * ibScaleY).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderY1, borderY1Reg)).simPublic()
  val effBorderEnable = borderEnable || acActive || innerBorderEnable
  val insideBorder = (hCounter >= effBorderX0.resize(log2Up(hTotal))) &&
                     (hCounter <  effBorderX1.resize(log2Up(hTotal))) &&
                     (vCounter >= effBorderY0.resize(log2Up(vTotal))) &&
                     (vCounter <  effBorderY1.resize(log2Up(vTotal)))
  val borderActive = effBorderEnable && !insideBorder
  // Task 50 v3.3: Use a combinational lookup from the palette mirror
  // registers to fetch the border color. This removes the second async
  // read port on the palette Mem which broke BSRAM inference in v3.0.
  val borderRgb = paletteMirror(borderIdx)
  val borderActiveR = RegNext(borderActive) init False
  val borderRgbR    = RegNext(borderRgb)    init B(0, 24 bits)

  // Display-side sync / DE / gating signals first stage (+1) — tracks the
  // ColorMath input pipeline. hsync/vsync are active-low so reset value
  // is True (inactive). The scaler re-land below adds a second RegNext
  // (RR) to match the scaler's +1 output latency; total display depth
  // becomes +2. Lane #10686's palette readSync is absorbed inside the
  // post-palette stage via the dcSide RegNexts at the bgOrDirectRgb
  // mux input, so it does NOT contribute to display-side depth here.
  val hsyncR         = RegNext(!(hCounter >= hSyncStart && hCounter < hSyncEnd)) init True
  val vsyncR         = RegNext(!(vCounter >= vSyncStart && vCounter < vSyncEnd)) init True
  val deR            = RegNext(activeVideo)           init False
  val primedR        = RegNext(primed)                init False
  val rasterPendingR = RegNext(rasterTrigger.io.pending) init False

  // Border bypasses ColorMath — when borderActiveR is set, displayRgb
  // is the border palette entry directly; otherwise the post-ColorMath
  // pixel.
  val displayRgb = Mux(borderActiveR, borderRgbR, mathRgb)

  // Wire displayRgb into the scaler. Scaler is +1 latency uniformly
  // across bypass (1x) and scaled paths — outRgb is registered.
  scaler.io.inRgb      := displayRgb
  val displayRgbScaled = scaler.io.outRgb

  // Display-side second-stage RegNext (+2 total) to align with the
  // scaler's +1 output latency. Matches the dc1fba8-pre-disconnect
  // depth, minus the third stage that was overcounted there (the
  // third stage was matched to a post-palette compensation that the
  // dcSide RegNexts now absorb upstream of maskedRgbR).
  val hsyncRR         = RegNext(hsyncR)          init True
  val vsyncRR         = RegNext(vsyncR)          init True
  val deRR            = RegNext(deR)             init False
  val primedRR        = RegNext(primedR)         init False
  val rasterPendingRR = RegNext(rasterPendingR)  init False

  io.hsync := hsyncRR
  io.vsync := vsyncRR
  io.de    := deRR
  io.red   := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue  := B(0, 8 bits)
  when(deRR && primedRR) {
    val redRaw = displayRgbScaled(23 downto 16)
    io.red   := Mux(rasterPendingRR, ~redRaw, redRaw)
    io.green := displayRgbScaled(15 downto 8)
    io.blue  := displayRgbScaled(7 downto 0)
  }
  // io.x/y track the same +2 cycle pipeline as the RGB output.
  val hCounterR = RegNext(hCounter.resize(10)) init 0
  val vCounterR = RegNext(vCounter.resize(10)) init 0
  io.x := RegNext(hCounterR) init 0
  io.y := RegNext(vCounterR) init 0
}

object VdpTop {
  // Palette entries: index -> RGB (8-bit per channel, packed as R[23:16] G[15:8] B[7:0]).
  // Entries 0-7 reproduce the previous switch-case colors exactly.
  // Entries 8-15 default to black.
  val paletteColors: Seq[Int] = Seq(
    0x000000, // 0: black
    0xFFFFFF, // 1: white
    0xFF0000, // 2: red
    0x00FF00, // 3: green
    0x0000FF, // 4: blue
    0xFFFF00, // 5: yellow
    0x00FFFF, // 6: cyan
    0xFF00FF, // 7: magenta
    0x000000, // 8-15: black (unused)
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000
  )

  def paletteInit: Seq[Bits] = paletteColors.map(c => B(c, 24 bits))

  // Sprite pattern: 16x16 pixels, 4-bit palette index. Arrow/diamond shape using palette colors.
  val spritePatternData: Seq[Seq[Int]] = Seq(
    Seq(0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0),
    Seq(0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,0),
    Seq(0,0,0,0,1,2,2,5,5,2,2,1,0,0,0,0),
    Seq(0,0,0,1,2,2,5,5,5,5,2,2,1,0,0,0),
    Seq(0,0,1,2,2,5,5,5,5,5,5,2,2,1,0,0),
    Seq(0,1,2,2,5,5,5,1,1,5,5,5,2,2,1,0),
    Seq(1,2,2,5,5,5,1,1,1,1,5,5,5,2,2,1),
    Seq(1,2,2,5,5,5,1,1,1,1,5,5,5,2,2,1),
    Seq(0,1,2,2,5,5,5,1,1,5,5,5,2,2,1,0),
    Seq(0,0,1,2,2,5,5,5,5,5,5,2,2,1,0,0),
    Seq(0,0,0,1,2,2,5,5,5,5,2,2,1,0,0,0),
    Seq(0,0,0,0,1,2,2,5,5,2,2,1,0,0,0,0),
    Seq(0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,0),
    Seq(0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0)
  )

  // Sprite 0: diamond shape (white/red/yellow)
  def sprite0PatternInit: Seq[Bits] = spritePatternData.flatten.map(v => B(v, 4 bits))

  // Sprite 1: cross shape (cyan/magenta) — visually distinct from sprite 0.
  val sprite1PatternData: Seq[Seq[Int]] = Seq(
    Seq(0,0,0,0,0,0,6,6,6,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(6,6,6,6,6,6,6,7,7,6,6,6,6,6,6,6),
    Seq(6,7,7,7,7,7,7,7,7,7,7,7,7,7,7,6),
    Seq(6,7,7,7,7,7,7,7,7,7,7,7,7,7,7,6),
    Seq(6,6,6,6,6,6,6,7,7,6,6,6,6,6,6,6),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,6,6,6,0,0,0,0,0,0)
  )

  def sprite1PatternInit: Seq[Bits] = sprite1PatternData.flatten.map(v => B(v, 4 bits))

  /** Sprite Pattern Memory Foundation (CyanPeak #8596) — single 4096×4-bit
    * BSRAM-backed pattern RAM. Slot 0 holds the legacy diamond pattern,
    * slot 1 holds the legacy cross, slots 2..15 are zero (transparent)
    * until bus writes program them. Address layout per slot is
    * 256 entries (16×16 4-bit pixels). */
  def spritePatternRamInit: Seq[Bits] = {
    val slot0 = spritePatternData.flatten          // 256 nibbles
    val slot1 = sprite1PatternData.flatten         // 256 nibbles
    // Task 53 (#9419): RAM depth 4096 → 16384 (64 slots × 256 entries).
    val zeros = Seq.fill(16384 - 2 * 256)(0)       // slots 2..63
    (slot0 ++ slot1 ++ zeros).map(v => B(v, 4 bits))
  }

  def paletteRgb(index: Int): (Int, Int, Int) = {
    val c = paletteColors(index & 0xF)
    ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)
  }

  def sprite0PixelAt(row: Int, col: Int): Int = {
    if (row >= 0 && row < 16 && col >= 0 && col < 16)
      spritePatternData(row)(col)
    else 0
  }

  def sprite1PixelAt(row: Int, col: Int): Int = {
    if (row >= 0 && row < 16 && col >= 0 && col < 16)
      sprite1PatternData(row)(col)
    else 0
  }
}

object VdpTopVerilog extends App {
  Config.spinal.generateVerilog(VdpTop())
}

object VdpTopVhdl extends App {
  Config.spinal.generateVhdl(VdpTop())
}
