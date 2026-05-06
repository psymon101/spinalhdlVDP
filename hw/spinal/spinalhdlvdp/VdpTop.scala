package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib.BufferCC

case class VdpTop() extends Component {
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

    // Task 1 (MODE_SELECT, #9154) — runtime adapter selection per
    // MODE_SELECT_ARCHITECTURE.md v1.1 §4.2. Live-mode 4-bit field exported
    // for adapters' output gating (§4.4) and for host READ_STATUS LIVE_MODE
    // observability (§4.2 / open-question Q6 ruling: place in READ_STATUS).
    //   0x0 = Native Mode0 (no adapter)
    //   0x1 = C64 adapter
    //   0x2 = ZX Spectrum adapter
    //   0x3..0xF reserved
    val modeSelect         = out UInt(4 bits)

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

  val hCounter = Reg(UInt(log2Up(hTotal) bits)) init 0
  val vCounter = Reg(UInt(log2Up(vTotal) bits)) init 0

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
  linestate.io.readAddr := fillLine.resized
  linestate.io.commitLine := fillLine.resized
  linestate.io.commitStrobe := hCounter === hTotal - 1
  // Prepare-side write interface exposed for simulation testing.
  // R5 Copper coprocessor, fed by the regWrite bus for program uploads and by
  // `copperCtrlReg(0)` (VDP_CTRL @ 0x0310) for run control — R5.3 unifies the
  // previously-standalone `io.copperEnable` port with the register bus.
  val copperCtrlReg     = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPend    = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPendHit = Reg(Bool()) init False
  val copper = Copper()
  copper.io.hCounter := hCounter.resize(10)
  copper.io.vCounter := vCounter.resize(10)
  copper.io.enabled  := copperCtrlReg(0)
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
  linestate.io.writeAddr := effAddr(log2Up(480) - 1 downto 0)
  linestate.io.writeData := effData(11 downto 0)
  linestate.io.writeEnable := lsRangeHit

  // R5.1 stutter fix (#7080): latch pending LAYER_ENABLE write into a shadow
  // register and apply it to `layerEnableReg` only at `hCounter === 0`.
  // Without this gate, the copper's combinational write arrives mid-line,
  // shifts the compositor's effective enable mask mid-scanline, and shows
  // up as 1-frame scroll skips + wrong-bank pixel flashes on hardware.
  // Task 48: expanded to 5 bits — {L3[4], L2[3], sprite[2], L1[1], L0[0]}.
  // Default 5'b00111 preserves the original 3-bit init for L0/L1/sprite
  // (all on at reset) and keeps L2/L3 OFF until a scenario's Copper or
  // host writes bits 4..3 explicitly. Matches CyanPeak #8221 audit note:
  // "The default-zero state of bits 4..3 ensures L2/L3 are inactive for
  // legacy builds."
  val layerEnableReg    = (Reg(Bits(5 bits)) init B"00111").simPublic()
  val layerEnablePend   = Reg(Bits(5 bits)) init B"00111"
  val layerEnablePendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0300, 15 bits)) {
    layerEnablePend    := effData(4 downto 0)
    layerEnablePendHit := True
  }
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
  // R5.3: VDP_CTRL @ 0x0310, safe-boundary shadow + commit for copper enable.
  when(effWrite && effAddr === U(0x0310, 15 bits)) {
    copperCtrlPend    := effData(0 downto 0)
    copperCtrlPendHit := True
  }

  // Task 1 (MODE_SELECT, #9154) per MODE_SELECT_ARCHITECTURE.md v1.1 §4.2:
  // 16-bit register at 0x0313 — [3:0] = MODE_SELECT, [7:4] = reserved,
  // [15:8] = MODE_FLAGS. Write authority: host/QSPI only for v1; the
  // AdapterRegRouter (Phase 4) silently drops Copper/HDMA writes to 0x0313.
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
  // the final display stage with palette[BORDER_CTRL[12:8]] (typically
  // slot 24, written by the ZX Spectrum adapter's border emitter — see
  // ZXSpectrumAdapter.scala). The rectangle is independent from the
  // CW-5 WIN1/WIN2 windows so existing scenarios using those for
  // ColorMath effects are unaffected. Defaults are all-zero so v3-OFF
  // scenarios continue to render bit-identically.
  //
  //   0x033C BORDER_X0   (10 bits, inclusive)
  //   0x033D BORDER_X1   (10 bits, exclusive)
  //   0x033E BORDER_Y0   (10 bits, inclusive)
  //   0x033F BORDER_Y1   (10 bits, exclusive)
  //   0x0347 BORDER_CTRL bit[0]    = enable
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
  val borderCtrlReg     = (Reg(Bits(16 bits)) init 0).simPublic()
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
  val bitmapBaseLoReg     = Reg(Bits(16 bits)) init 0
  val bitmapBaseLoPend    = Reg(Bits(16 bits)) init 0
  val bitmapBaseLoPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0351, 15 bits)) {
    bitmapBaseLoPend    := effData
    bitmapBaseLoPendHit := True
  }
  val bitmapBaseHiReg     = Reg(Bits(16 bits)) init 0
  val bitmapBaseHiPend    = Reg(Bits(16 bits)) init 0
  val bitmapBaseHiPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0352, 15 bits)) {
    bitmapBaseHiPend    := effData
    bitmapBaseHiPendHit := True
  }
  val attrBaseLoReg     = Reg(Bits(16 bits)) init 0
  val attrBaseLoPend    = Reg(Bits(16 bits)) init 0
  val attrBaseLoPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0353, 15 bits)) {
    attrBaseLoPend    := effData
    attrBaseLoPendHit := True
  }
  val attrBaseHiReg     = Reg(Bits(16 bits)) init 0
  val attrBaseHiPend    = Reg(Bits(16 bits)) init 0
  val attrBaseHiPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0354, 15 bits)) {
    attrBaseHiPend    := effData
    attrBaseHiPendHit := True
  }
  val bitmapStrideReg     = Reg(Bits(16 bits)) init 0
  val bitmapStridePend    = Reg(Bits(16 bits)) init 0
  val bitmapStridePendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0355, 15 bits)) {
    bitmapStridePend    := effData
    bitmapStridePendHit := True
  }
  val attrStrideReg     = Reg(Bits(16 bits)) init 0
  val attrStridePend    = Reg(Bits(16 bits)) init 0
  val attrStridePendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0356, 15 bits)) {
    attrStridePend    := effData
    attrStridePendHit := True
  }
  val bitmapEnable    = bitmapCtrlReg(0)
  val bitmapBpp       = bitmapCtrlReg(2 downto 1).asUInt
  // Task 44b: bit[7] selects SDRAM-backed source (1) vs CP-A
  // deterministic test generator (0). Sc44 (Task 44 CP-B) leaves
  // bit[7]=0; Sc44d (Task 44b CP-B) sets bit[7]=1.
  val bitmapUseSdram  = bitmapCtrlReg(7)

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
    when(bitmapBaseLoPendHit)  { bitmapBaseLoReg  := bitmapBaseLoPend;  bitmapBaseLoPendHit  := False }
    when(bitmapBaseHiPendHit)  { bitmapBaseHiReg  := bitmapBaseHiPend;  bitmapBaseHiPendHit  := False }
    when(attrBaseLoPendHit)    { attrBaseLoReg    := attrBaseLoPend;    attrBaseLoPendHit    := False }
    when(attrBaseHiPendHit)    { attrBaseHiReg    := attrBaseHiPend;    attrBaseHiPendHit    := False }
    when(bitmapStridePendHit)  { bitmapStrideReg  := bitmapStridePend;  bitmapStridePendHit  := False }
    when(attrStridePendHit)    { attrStrideReg    := attrStridePend;    attrStridePendHit    := False }
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
  scrollTable0.io.wrAddr := scrollTableEntry
  scrollTable0.io.wrData := effData(9 downto 0).asUInt
  scrollTable0.io.wr     := scrollTableRangeHit && !scrollTableLayer
  scrollTable1.io.wrAddr := scrollTableEntry
  scrollTable1.io.wrData := effData(9 downto 0).asUInt
  scrollTable1.io.wr     := scrollTableRangeHit && scrollTableLayer

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
  vScrollTable0.io.wrAddr := vScrollTableEntry
  vScrollTable0.io.wrData := effData(9 downto 0).asUInt
  vScrollTable0.io.wr     := vScrollTableRangeHit && !vScrollTableLayer
  vScrollTable1.io.wrAddr := vScrollTableEntry
  vScrollTable1.io.wrData := effData(9 downto 0).asUInt
  vScrollTable1.io.wr     := vScrollTableRangeHit && vScrollTableLayer

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
  val PLANE_COUNT = 3
  val PLANE_PIXELS = 256
  val planarLineFetch = PlanarLineFetch(planeCount = PLANE_COUNT, planePixels = PLANE_PIXELS, addrWidth = 23)
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
  // Trigger row fetch one cycle into the active region — the FSM has
  // until next-line's display reaches pixelIdx N to land word N
  // (lead-time ≈ 160 cycles even for the first dout32 word).
  planarLineFetch.io.start          := planarFetchEnable && (hCounter === U(hActive, log2Up(hTotal) bits))
  planarLineFetch.io.pixelIdx       := hCounter.resize(log2Up(PLANE_PIXELS))
  // CDC: io.planarSdram* arrive from the SDRAM clock domain (driven by
  // sdramArbiter in TopTang20kHdmi). BufferCC breaks the combinational
  // sdram↔pixel loop between sdramRd/clientGrant and sdramBusy/state.
  // Note: 32-bit dout32 BufferCC has the standard caveat (per-bit
  // synchronization, no gray code). Acceptable here because the
  // dataReady handshake gates consumption — PlanarLineFetch only
  // samples dout32 when dataReady is asserted.
  planarLineFetch.io.sdramBusy      := BufferCC(io.planarSdramBusy,      False)
  planarLineFetch.io.sdramDataReady := BufferCC(io.planarSdramDataReady, False)
  planarLineFetch.io.sdramDout32    := BufferCC(io.planarSdramDout32,    B(0, 32 bits))
  io.planarSdramRd   := planarLineFetch.io.sdramRd
  io.planarSdramAddr := planarLineFetch.io.sdramAddr

  // Task 15 fetch-control outputs. Atomic CDC pattern per 6626/6628:
  //   1) Pulse-harden fetchStart: widen to 4 pixel cycles so the SDRAM-side
  //      BufferCC (2-stage synchronizer) reliably samples it despite routing
  //      delay and phase alignment with the 64.8 MHz SDRAM clock.
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
  scheduler.io.schedule(1).endH     := U(hTotal - 1, 10 bits)
  // Task 3 (Checkpoint A #9313): slot 2 dedicated to PlanarLineFetch
  // (clientId=2), gated on planarFetchEnable. Window covers H-blank
  // adjacent so 50 × dout32 reads for 5-plane × 320-pixel rows can be
  // granted without colliding with tile fetch's slot 0 (hTotal-1) or
  // slot 1 (full active line). FSM start is independent (mid-line)
  // per design packet §1.
  scheduler.io.schedule(2).enabled  := planarFetchEnable
  scheduler.io.schedule(2).clientId := U(2, 2 bits)
  scheduler.io.schedule(2).startH   := U(hTotal - 80, 10 bits)
  scheduler.io.schedule(2).endH     := U(hTotal - 1,  10 bits)
  for (i <- 3 until 8) {
    scheduler.io.schedule(i).enabled  := False
    scheduler.io.schedule(i).clientId := U(0, 2 bits)
    scheduler.io.schedule(i).startH   := U(0, 10 bits)
    scheduler.io.schedule(i).endH     := U(0, 10 bits)
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
  io.layer0FetchPixelAddr   := hCounter.resize(10)

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
  // CP-B note: the full SDRAM-backed row-buffer fetch is a follow-on
  // lane. For initial hardware proof we feed the decoder with a
  // deterministic pixel-domain test-pattern generator so the decoder
  // and the mux path render visibly on silicon. Inputs are stable
  // across their respective byte windows (bitmapByte for 8 pixel
  // columns in 1bpp, attrByte for 8 pixel columns × 8 rows).
  val bitmapByteIdx = hCounter(9 downto 3)                 // 0..127
  val bitmapRowIdx  = fillLine(7 downto 0)                 // 0..255 mod
  val bitmapAttrIdx = hCounter(9 downto 6)                 // 8-pixel attr cells
  val bitmapAttrRow = fillLine(8 downto 3)                 // 8-pixel-row attr cells
  val bitmapFetch = BitmapFetch()
  // Task 44b: source from `io.bitmapSdram*` (SDRAM-backed). Falls back
  // to the Task 44 CP-B deterministic test pattern when the top-level
  // does not drive those ports (e.g. older scenarios).
  val testBitmapByte = (bitmapByteIdx.resize(8).asBits ^ bitmapRowIdx.asBits)
  val testAttrByte   = (B(0, 2 bits) ## bitmapAttrRow(2 downto 0).asBits ##
                        bitmapAttrIdx(2 downto 0).asBits)
  // Enable the SDRAM-backed path iff the top-level actually drives it —
  // a scenario wires bitmapSdram* to meaningful values; unwired inputs
  // stay at 0. Use `io.bitmapSdramByte =/= 0` as a crude OR presence
  // signal. For Sc44d the bootstrap forces bitmapEnable=1 and live SDRAM
  // data is present after boot; the Task 44 Sc44 scenario does not wire
  // BitmapRowFetch so inputs remain zero and the test path still lights.
  bitmapFetch.io.bitmapByte      := Mux(bitmapUseSdram, io.bitmapSdramByte,    testBitmapByte)
  bitmapFetch.io.attrByte        := Mux(bitmapUseSdram, io.bitmapSdramAttrByte, testAttrByte)
  bitmapFetch.io.pixelWithinByte := hCounter(2 downto 0)
  bitmapFetch.io.bpp             := bitmapBpp

  // Export coupling signals to BitmapRowFetch at top level.
  io.bitmapSdramCol        := hCounter.resize(10)
  io.bitmapSdramFetchLine  := fillLine.resize(10)
  // Task 44b iter 6f: move fetch-grant pulse to start of blanking (hActive) so
  // SDRAM switching noise lands at the start of hblank and has maximum time
  // to settle before the next active line begins.
  io.bitmapSdramFetchGrant := hCounter === U(hActive, log2Up(hTotal) bits)
  io.bitmapModeActive      := bitmapEnable

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
  // 4-plane pixel = 4-bit palette idx (16 colors). Bank stays at 0.
  // (When a future lane raises planeCount to 5 with Mem-backed planeWords,
  // re-introduce bit 4 → bank-bit 0 for 32-color Amiga OCS coverage.)
  val planarPixel = planarLineFetch.io.pixel
  val planarIdx4  = planarPixel.resize(4)
  val planarBank3 = U(0, 3 bits)
  val layer0Index = (Mux(planarFetchEnable, planarIdx4,
                         Mux(affineEnable, affineIndex,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.pixelIndex,
                             Mux(bitmapEnable, bitmapFetch.io.pixelIndex.asBits,
                                 Mux(io.layer0UseSdram, io.layer0SdramPixel, onChipIdx4)))))).simPublic()
  val layer0Bank  = (Mux(planarFetchEnable, planarBank3,
                         Mux(affineEnable, affineBank,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.paletteBank,
                             Mux(bitmapEnable, bitmapFetch.io.paletteBank,
                                 Mux(io.layer0UseSdram, io.layer0SdramBank,  U(0, 3 bits))))))).simPublic()
  val layer0Prio  = (Mux(planarFetchEnable, False,
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
  val layer1Pixel = Mux(effectiveL1Enable, layer1.io.pixelIndex.resize(4), B(0, 4 bits))
  val layer2Pixel = Mux(effectiveL2Enable, layer2.io.pixelIndex.resize(4), B(0, 4 bits))
  val layer3Pixel = Mux(effectiveL3Enable, layer3.io.pixelIndex.resize(4), B(0, 4 bits))

  // Four-layer priority-aware composition. L0 forcedPriority override wins
  // over ALL layers (preserved from the 2-layer era). Otherwise, the
  // highest-index opaque layer wins (L3 > L2 > L1 > L0). When the only
  // visible layer is L0 (or nothing), L0 paints. This is bit-identical to
  // the pre-Task-48 2-layer compositor whenever L2/L3 are disabled (zero
  // pixel, not opaque).
  val layer0Opaque = layer0Pixel =/= B(0, 4 bits)
  val layer1Opaque = layer1Pixel =/= B(0, 4 bits)
  val layer2Opaque = layer2Pixel =/= B(0, 4 bits)
  val layer3Opaque = layer3Pixel =/= B(0, 4 bits)
  val composedBgIdx    = Bits(4 bits)
  val composedBgBank   = UInt(3 bits)
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
    composedBgBank   := U(0, 3 bits)
    composedBgSource := U(PixelMetadata.SourceBG1, 3 bits)
  }.otherwise {
    composedBgIdx    := layer0Pixel
    composedBgBank   := layer0Bank
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
  // defaults descCount=32, visiblePerLine=8 now that Task 44b has cleared and
  // Gowin timing/resource reports show ample headroom. 4 legacy IO slots +
  // 28 bus-programmable extended slots.
  val spriteEval = SpriteEvaluator(
    descCount      = 64,
    // Sprite Envelope Hardening B-1 (CyanPeak #8577) — TIMING-BLOCKED
    // capacity bump. 32 blew the logic budget (51 k of 20.7 k); 16 ran
    // 1.06× over (21.9 k); 12 fit resource but missed timing by 2 ns
    // on vCounter→lineBuf paths (15 violations, TNS −25.9 ns). The 5
    // new descriptor fields land at the original 8/line capacity so
    // they can be used by adapters — capacity bump is parked for a
    // follow-on slice that pipelines the compositor merge or shares
    // the per-slot AffineStepper. Out of #8577 scope.
    //
    // Task 2 (#9204) attempt 2026-05-04: same 51,191-logic failure
    // mode reproduced when bumped to 64/32 directly. Blocker filed
    // — substrate redesign (shared pattern Mems / pipelined
    // compositor) required before capacity bump can land.
    visiblePerLine = 32,
    patternSelBits = 4,
    legacyIoCount  = 4)
  spriteEval.io.descX(0)          := io.sprite0X
  spriteEval.io.descY(0)          := io.sprite0Y
  spriteEval.io.descEnabled(0)    := io.sprite0Enabled
  spriteEval.io.descPatternIdx(0) := io.sprite0PatternIdx.resize(4)
  spriteEval.io.descX(1)          := io.sprite1X
  spriteEval.io.descY(1)          := io.sprite1Y
  spriteEval.io.descEnabled(1)    := io.sprite1Enabled
  spriteEval.io.descPatternIdx(1) := io.sprite1PatternIdx.resize(4)
  spriteEval.io.descX(2)          := io.sprite2X
  spriteEval.io.descY(2)          := io.sprite2Y
  spriteEval.io.descEnabled(2)    := io.sprite2Enabled
  spriteEval.io.descPatternIdx(2) := io.sprite2PatternIdx.resize(4)
  spriteEval.io.descX(3)          := io.sprite3X
  spriteEval.io.descY(3)          := io.sprite3Y
  spriteEval.io.descEnabled(3)    := io.sprite3Enabled
  spriteEval.io.descPatternIdx(3) := io.sprite3PatternIdx.resize(4)

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
  val patternRamPtr = Reg(UInt(12 bits)) init 0
  when(patternRamPtrWriteHit) {
    patternRamPtr := effData(11 downto 0).asUInt
  }.elsewhen(patternRamDataWriteHit) {
    patternRamPtr := patternRamPtr + 1
  }
  // Broadcast write — every per-slot Mem must observe the same write so the
  // logical pattern table stays consistent across slots.
  for (mem <- spritePatternRams) {
    mem.write(
      address = patternRamPtr,
      data    = effData(3 downto 0),
      enable  = patternRamDataWriteHit
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
  val NUM_SLOTS = 32  // Sprite Envelope Hardening B-1: capacity bump parked, fields-only landing

  // === Task 2a Checkpoint 2 — Step 1 (PM #9244): SpriteRasterizer wired in
  // parallel to the existing per-slot pipeline. The rasterizer's drain
  // output is captured for inspection (simPublic) but NOT yet consumed by
  // the lineBuf write. Step 2 (next commit) cuts over and removes the
  // parallel for-loop + tree merge below.
  // ============================================================
  val spriteRasterizer = SpriteRasterizer(
    visiblePerLine = NUM_SLOTS,
    patternSelBits = 4,
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
  // (the readAsync pixel path will see the new entry on the very next
  // pixel after the second write).
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

  val palette = Mem(Bits(24 bits), initialContent = TileAttributeAssets.paletteInit)
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

  palette.write(
    address = paletteEntryIdx.resize(log2Up(TileAttributeAssets.PaletteDepth)),
    data    = paletteCommitData,
    enable  = paletteCommitNow
  )
  val paletteRgb = palette.readAsync(paletteAddr)

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

  // BH-5: per-trigger control register banks for TR1..TR3. Direct
  // (non-shadow) commits — the trigger compare is purely combinational
  // on the registers, so a host write that lands mid-frame just changes
  // the next-match condition without corrupting prior state.
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

  // Aggregate pending across all four — top-level pending output is OR
  // of the four for backward compat with the existing IO surface.
  val rasterPendingMask = (rasterTrigger3.io.pending ##
                           rasterTrigger2.io.pending ##
                           rasterTrigger1.io.pending ##
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
  val evRasterMatch    = rasterTrigger.io.triggerPulse  ||
                         rasterTrigger1.io.triggerPulse ||
                         rasterTrigger2.io.triggerPulse ||
                         rasterTrigger3.io.triggerPulse
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

  val evBus = (B(0, 4 bits) ## evModeSelectChanged ## B(0, 1 bit) ##
               blitterEngine.io.done ## dmaEngine.io.done ## B(0, 2 bits) ##
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

  // Sticky update: set on any event this cycle, then clear bits the host
  // requested. If an event AND a clear both target the same bit in the
  // same cycle, the event wins (new state takes precedence over stale
  // clear). QSPI_ERROR uses the level directly so it re-asserts until the
  // source condition clears.
  statusStickyReg := (statusStickyReg | evBus) & (~statusClearMask)

  // Safe-boundary commit of enable mask at hCounter===0.
  when(hCounter === U(0, log2Up(hTotal) bits)) {
    when(statusEnablePendHit) {
      statusEnableReg     := statusEnablePend
      statusEnablePendHit := False
    }
  }

  io.statusSticky := statusStickyReg
  io.irq          := (statusStickyReg & statusEnableReg).orR

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
  val maskedRgb       = Mux(layerMaskActive, B(0, 24 bits), paletteRgb)

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
  val insideBorder = (hCounter >= borderX0Reg.resize(log2Up(hTotal))) &&
                     (hCounter <  borderX1Reg.resize(log2Up(hTotal))) &&
                     (vCounter >= borderY0Reg.resize(log2Up(vTotal))) &&
                     (vCounter <  borderY1Reg.resize(log2Up(vTotal)))
  val borderActive = borderEnable && !insideBorder
  // Task 50 v3.3: Use a combinational lookup from the palette mirror
  // registers to fetch the border color. This removes the second async
  // read port on the palette Mem which broke BSRAM inference in v3.0.
  val borderRgb = paletteMirror(borderIdx)
  val borderActiveR = RegNext(borderActive) init False
  val borderRgbR    = RegNext(borderRgb)    init B(0, 24 bits)

  // Display-side sync / DE / gating signals delayed 1 cycle to track
  // the ColorMath input pipeline. hsync/vsync are active-low so reset
  // value is True (inactive).
  val hsyncR         = RegNext(!(hCounter >= hSyncStart && hCounter < hSyncEnd)) init True
  val vsyncR         = RegNext(!(vCounter >= vSyncStart && vCounter < vSyncEnd)) init True
  val deR            = RegNext(activeVideo)           init False
  val primedR        = RegNext(primed)                init False
  val rasterPendingR = RegNext(rasterTrigger.io.pending) init False

  // Border bypasses ColorMath — when borderActiveR is set, displayRgb
  // is the border palette entry directly; otherwise the post-ColorMath
  // pixel.
  val displayRgb = Mux(borderActiveR, borderRgbR, mathRgb)

  io.hsync := hsyncR
  io.vsync := vsyncR
  io.de := deR
  io.red := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue := B(0, 8 bits)
  when(deR && primedR) {
    val redRaw = displayRgb(23 downto 16)
    io.red   := Mux(rasterPendingR, ~redRaw, redRaw)
    io.green := displayRgb(15 downto 8)
    io.blue  := displayRgb(7 downto 0)
  }
  // io.x/y are the displayed-pixel coordinates and must track the same
  // 1-cycle pipeline shift as io.de / io.red / io.green / io.blue (CW
  // Option 1 pipeline above).
  io.x := RegNext(hCounter.resize(10)) init 0
  io.y := RegNext(vCounter.resize(10)) init 0
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
    val zeros = Seq.fill(4096 - 2 * 256)(0)        // slots 2..15
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
