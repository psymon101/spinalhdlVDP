package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

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
  val copperFifo = spinal.lib.StreamFifo(dataType = Bits(31 bits), depth = 32)
  copperFifo.io.push.valid   := copper.io.regWr
  copperFifo.io.push.payload := (copper.io.regAddr.asBits ## copper.io.regData).asBits.resize(31)
  val extHit     = io.regBus.enable
  val safeNow    = hCounter === U(0, log2Up(hTotal) bits)
  val copperDrain = safeNow && !extHit
  copperFifo.io.pop.ready := copperDrain
  val copperPopped = copperFifo.io.pop.fire
  val effWrite = (extHit || copperPopped).simPublic()
  val effAddr  = Mux(extHit, io.regBus.addr, copperFifo.io.pop.payload(30 downto 16).asUInt).simPublic()
  val effData  = Mux(extHit, io.regBus.data, copperFifo.io.pop.payload(15 downto 0)).simPublic()

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
  val layerEnableReg    = (Reg(Bits(3 bits)) init B"111").simPublic()
  val layerEnablePend   = Reg(Bits(3 bits)) init B"111"
  val layerEnablePendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0300, 15 bits)) {
    layerEnablePend    := effData(2 downto 0)
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
  val bitmapEnable = bitmapCtrlReg(0)
  val bitmapBpp    = bitmapCtrlReg(2 downto 1).asUInt

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
    when(winX0PendHit)     { winX0Reg     := winX0Pend;     winX0PendHit     := False }
    when(winX1PendHit)     { winX1Reg     := winX1Pend;     winX1PendHit     := False }
    when(winY0PendHit)     { winY0Reg     := winY0Pend;     winY0PendHit     := False }
    when(winY1PendHit)     { winY1Reg     := winY1Pend;     winY1PendHit     := False }
    when(colorMathPendHit) { colorMathReg := colorMathPend; colorMathPendHit := False }
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

  // Layer 0 (lower priority background).
  val layer0 = BasicPatternSource()
  layer0.io.x := hCounter.resize(10)
  layer0.io.y := fillLine
  layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset
  layer0.io.scrollY := io.layer0ScrollY

  // Test pattern source: combinational standard patterns for task validation.
  val testPattern = TestPatternSource()
  testPattern.io.x := hCounter.resize(10)
  testPattern.io.y := fillLine
  testPattern.io.patternSelect := io.layer0TestPatternSelect

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
  scheduler.io.schedule(2).enabled  := False
  scheduler.io.schedule(2).clientId := U(0, 2 bits)
  scheduler.io.schedule(2).startH   := U(0, 10 bits)
  scheduler.io.schedule(2).endH     := U(0, 10 bits)
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
  layer1.io.scrollY := io.layer1ScrollY

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
  // Task 44 — bitmap fetch pixel decoder. The SDRAM row/attr row
  // delivery path is CP-B hardware work; for CP-A we instantiate the
  // decoder with stubbed byte inputs so the register/mux path is
  // wired end-to-end and does not drift.
  val bitmapFetch = BitmapFetch()
  bitmapFetch.io.bitmapByte      := B(0, 8 bits)  // CP-B: wire from SDRAM row buffer
  bitmapFetch.io.attrByte        := B(0, 8 bits)
  bitmapFetch.io.pixelWithinByte := hCounter(2 downto 0)
  bitmapFetch.io.bpp             := bitmapBpp

  // Task 19: when affineEnable is high, the affine-texture lookup wins over
  // every other L0 source (test-pattern / SDRAM / on-chip). Task 44
  // inserts the bitmap-fetch path between affine and SDRAM; when
  // bitmapEnable=0 (default) the ordering and values are unchanged.
  val layer0Index = (Mux(affineEnable, affineIndex,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.pixelIndex,
                             Mux(bitmapEnable, bitmapFetch.io.pixelIndex.asBits,
                                 Mux(io.layer0UseSdram, io.layer0SdramPixel, onChipIdx4))))).simPublic()
  val layer0Bank  = (Mux(affineEnable, affineBank,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.paletteBank,
                             Mux(bitmapEnable, bitmapFetch.io.paletteBank,
                                 Mux(io.layer0UseSdram, io.layer0SdramBank,  U(0, 3 bits)))))).simPublic()
  val layer0Prio  = (Mux(affineEnable, affinePrio,
                         Mux(io.layer0TestPatternEnable,
                             False,
                             Mux(bitmapEnable, False,
                                 Mux(io.layer0UseSdram, io.layer0SdramPriority, False))))).simPublic()

  // R5: fold global LAYER_ENABLE register into the per-line linestate enable.
  val effectiveL0Enable = linestate.io.layer0Enable && layerEnableReg(0)
  val effectiveL1Enable = linestate.io.layer1Enable && layerEnableReg(1)
  val layer0Pixel = Mux(effectiveL0Enable, layer0Index, B(0, 4 bits))
  val layer0PrioGated = effectiveL0Enable && layer0Prio
  val layer1Pixel = Mux(effectiveL1Enable, layer1.io.pixelIndex.resize(4), B(0, 4 bits))

  // Priority-aware L0/L1 composition. Previous behavior: L1 wins over L0
  // whenever L1 is non-transparent. With R4 per-tile priority, L0 additionally
  // wins when its priority bit is set and the L0 pixel is non-transparent —
  // this is what lets a foreground tile poke through L1.
  val layer0Opaque = layer0Pixel =/= B(0, 4 bits)
  val layer1Opaque = layer1Pixel =/= B(0, 4 bits)
  val composedBgIdx = Bits(4 bits)
  val composedBgBank = UInt(3 bits)
  when(layer0PrioGated && layer0Opaque) {
    composedBgIdx  := layer0Pixel
    composedBgBank := layer0Bank
  }.elsewhen(layer1Opaque) {
    composedBgIdx  := layer1Pixel
    composedBgBank := U(0, 3 bits)  // L1 is legacy-bank-0 only
  }.otherwise {
    composedBgIdx  := layer0Pixel
    composedBgBank := layer0Bank
  }
  val composedBg = composedBgIdx

  // Task 28: two-pass sprite evaluator over 32 descriptors, 8 visible per
  // line. Slots 0..3 come from the top-level sprite* inputs (backwards-
  // compat with TopTang20kHdmi scenarios + existing sims); slots 4..31
  // are Reg-backed and bus-programmable via the Mode0 register block at
  // 0x0800..0x083F. See SpriteEvaluator.scala for the slot layout and
  // the word-0 / word-1 packing.
  // Task 28 CP-C Option A (BronzeGate #7883): reduce descCount 32 → 8
  // temporarily as a scale-related discriminator on Gowin. 4 legacy IO
  // slots + 4 bus-programmable extended slots.
  val spriteEval = SpriteEvaluator(
    descCount      = 8,
    visiblePerLine = 4,
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

  // Mode0RegBus decode for 0x0800..0x083F → evaluator bus-write port.
  // Task 37 extended layout: 8 words per slot (word 0..7 = enable/pat/aff/y,
  // x, matA, matB, matC, matD, transX, transY). 8 slots × 8 words = 64
  // addresses (0x0800..0x083F). slot = subAddr[5:3], word = subAddr[2:0].
  val spriteBusRangeHit = effWrite &&
    (effAddr >= U(0x0800, 15 bits)) &&
    (effAddr <  U(0x0840, 15 bits))
  val spriteBusSub = (effAddr - U(0x0800, 15 bits))(5 downto 0)
  spriteEval.io.busSlot := spriteBusSub(5 downto 3).resize(spriteEval.descIdxBits)
  spriteEval.io.busWord := spriteBusSub(2 downto 0).resize(spriteEval.busWordBits)
  spriteEval.io.busData := effData
  spriteEval.io.busWr   := spriteBusRangeHit

  // Pass 1 strobe at end of line — evaluator takes descCount cycles to
  // complete (well under hBlank = 160 cycles at 640×480@60).
  // Shift strobe earlier by descCount cycles so the scan completes before
  // the next line begins drawing.
  spriteEval.io.evalLine  := (fillLine + 1).resize(10)
  // Scan start shifted earlier by descCount+margin so the sequential
  // Pass-1 FSM completes before the line-fill swap. descCount=8 needs
  // ~8 cycles; hTotal-33 is a comfortable margin.
  spriteEval.io.evalStart := hCounter === U(hTotal - 33, log2Up(hTotal) bits)
  io.spriteOverflow := spriteEval.io.overflowFlag

  // Sprite pattern memories: 256 × 4-bit, power-of-two (GT-022 safe).
  val sprite0Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite0PatternInit)
  val sprite1Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite1PatternInit)

  val fillX = hCounter.resize(10)

  // Per active-slot pixel resolution (Task 28 — widened 2 → 8 slots).
  // patternIndex is now 4 bits; the low bit selects pattern Mem 0 vs 1 for
  // this task. Wider pattern-Mem banks land in a future sprite-attribute
  // extension task (Task 37), so bits [3:1] are ignored here.
  val NUM_SLOTS = 4   // Task 28 CP-C Option A: match reduced visiblePerLine
  val slotVisible = Vec(Bool(), NUM_SLOTS)
  val slotPixel   = Vec(Bits(4 bits), NUM_SLOTS)
  for (s <- 0 until NUM_SLOTS) {
    val x       = spriteEval.io.activeX(s)
    val row     = spriteEval.io.activeRow(s)
    val valid   = spriteEval.io.activeValid(s)
    val patIdx  = spriteEval.io.activePatternIdx(s)
    val affEn   = spriteEval.io.activeAffineEnable(s)

    // Flat path (Task 28 baseline).
    val col      = (fillX - x).resize(10)
    val flatOn   = fillX >= x && fillX < (x + 16)
    val flatAddr = (row(3 downto 0) ## col(3 downto 0)).asUInt

    // Task 37 affine path: per-slot AffineStepper (replication, per
    // CyanPeak #7904 §8). Host pre-computes transX/Y so that hotspot
    // (sprite center) maps to texture (8, 8). Out-of-bounds (u,v) outside
    // [0..15] → clamp to transparent.
    val stepper = AffineStepper()
    stepper.io.x       := fillX
    stepper.io.y       := fillLine.resize(10)
    stepper.io.matrixA := spriteEval.io.activeMatrixA(s)
    stepper.io.matrixB := spriteEval.io.activeMatrixB(s)
    stepper.io.matrixC := spriteEval.io.activeMatrixC(s)
    stepper.io.matrixD := spriteEval.io.activeMatrixD(s)
    stepper.io.transX  := spriteEval.io.activeTransX(s)
    stepper.io.transY  := spriteEval.io.activeTransY(s)
    val uIntFull = stepper.io.uFrac(31 downto 8)
    val vIntFull = stepper.io.vFrac(31 downto 8)
    val uOk      = uIntFull >= S(0, 24 bits) && uIntFull < S(16, 24 bits)
    val vOk      = vIntFull >= S(0, 24 bits) && vIntFull < S(16, 24 bits)
    val affOn    = uOk && vOk
    val affAddr  = (vIntFull(3 downto 0).asBits ## uIntFull(3 downto 0).asBits).asUInt

    val onPixel = Mux(affEn, affOn, flatOn)
    val addr    = Mux(affEn, affAddr, flatAddr)
    val active  = valid && onPixel
    val p0      = sprite0Pattern.readAsync(addr)
    val p1      = sprite1Pattern.readAsync(addr)
    val pixel   = Mux(patIdx(0), p1, p0)
    slotPixel(s)   := pixel
    slotVisible(s) := active && pixel =/= B(0, 4 bits)
  }

  // Task 28 priority: back-to-front (slot 0 = lowest descriptor index,
  // rendered first; higher-index slots overwrite lower-index ones on
  // overlap). CyanPeak #7838 §8.3 approved back-to-front with descriptor
  // index as the deterministic tie-breaker. R2's legacy 2-slot pattern
  // (slot1 > slot0) generalises to NUM_SLOTS-1 > NUM_SLOTS-2 > … > 0.
  //
  // R4: line buffer carries {metadata, priority, bank, index}. Sprites
  // render from legacy bank 0 with priority=0 to stay bit-compatible
  // with R2.
  val anySlotVisible = slotVisible.reduce(_ || _)

  // Task 29 — sprite/background collision detection. Purely
  // observational: no pixel-path change. `slotVisible(s)` already
  // includes `pixel =/= 0`, so overlap with non-transparent background
  // is simply the AND with `bgOpaque`. Sticky bits fold into `evBus`
  // below and clear via the existing write-1-to-clear path on 0x0320.
  val bgOpaque           = composedBgIdx =/= B(0, 4 bits)
  val sprite0HitPulse    = slotVisible(0) && bgOpaque
  val spriteBgHitPulse   = anySlotVisible && bgOpaque

  val fillIdx  = Bits(4 bits)
  val fillBank = UInt(3 bits)
  fillIdx  := composedBgIdx
  fillBank := composedBgBank
  // Iterate slots low→high with last-hit-wins. In SpinalHDL's
  // sequential-assignment semantics the highest-index visible slot
  // overrides lower ones.
  for (s <- 0 until NUM_SLOTS) {
    when(slotVisible(s)) {
      fillIdx  := slotPixel(s)
      fillBank := U(0, 3 bits)
    }
  }
  val fillPrio = Bool()
  when(anySlotVisible) {
    fillPrio := False
  }.otherwise {
    fillPrio := layer0PrioGated && layer0Opaque && !layer1Opaque
  }
  val fillPixel = (fillPrio ## fillBank.asBits ## fillIdx).asBits  // 8 bits

  // Task 41 — per-pixel metadata stub. All fetch-engine sources currently
  // drive the structural default (no math, normal priority, BG0 source),
  // so existing scenarios remain bit-for-bit identical. Downstream
  // consumers (sprite evaluator, platform overlays) will drive the flags
  // from their own fetch engines without touching this file.
  val fillMeta    = PixelMetadata.default()
  val fillPacked  = (fillMeta.toBits ## fillPixel).asBits   // 12 bits total

  // Double-buffered scanline buffer — widened from 8 → 12 bits to carry
  // `{metadata[3:0], priority, bank[2:0], idx[3:0]}`. Per CyanPeak #7820
  // guidance, 12-bit width fits standard Gowin BSRAM port aspect ratios.
  val lineBuf = LineBuffer(pixelWidth = 8 + PixelMetadata.Width, lineWidth = hActive)
  lineBuf.io.writeEnable := hCounter < hActive
  lineBuf.io.writeAddr := hCounter.resized
  lineBuf.io.writeData := fillPacked
  lineBuf.io.swap := hCounter === hTotal - 1

  // Drain address: present 1 cycle early for readSync pipeline.
  // At hCounter=hTotal-1, present addr 0 (data appears at hCounter=0).
  // At hCounter=N (N < hActive-1), present addr N+1 (data appears at hCounter=N+1).
  val drainAddr = UInt(log2Up(hActive) bits)
  when(hCounter === hTotal - 1) {
    drainAddr := U(0, log2Up(hActive) bits)
  }.elsewhen(hCounter < hActive - 1) {
    drainAddr := (hCounter + 1).resized
  }.otherwise {
    drainAddr := U(0, log2Up(hActive) bits)
  }
  lineBuf.io.readAddr := drainAddr

  // Drain: readSync output is the 12-bit packed
  // `{metadata[3:0], priority, bank[2:0], idx[3:0]}` (Task 41),
  // available 1 cycle after address. The palette is addressed by the full
  // {bank[3], idx[4]} = 7 bits; the stored priority bit is carried for future
  // consumers but not used for palette selection. The 4-bit metadata tail
  // is unpacked and exposed for downstream compositor / color-math consumers
  // but — by scope — is not yet gating the live ColorMath enable expression;
  // fetch-engine stubs drive the default (all-zeros) so existing scenarios
  // remain bit-for-bit identical.
  val drainWord   = lineBuf.io.readData
  val drainMeta   = PixelMetadata.fromBits(drainWord(11 downto 8)).setName("drainMeta")
  drainMeta.mathEnable.simPublic()
  drainMeta.forcedPriority.simPublic()
  drainMeta.layerSource.simPublic()
  val drainIdx    = drainWord(3 downto 0).asUInt
  val drainBank   = drainWord(6 downto 4).asUInt
  val paletteAddr = (drainBank @@ drainIdx).resize(log2Up(TileAttributeAssets.PaletteDepth))

  // Palette: 128-entry × 24-bit banked RGB lookup from TileAttributeAssets.
  // Bank 0 reproduces the pre-R4 16-color palette so the legacy L1 path and
  // sprite rendering are unchanged.
  val palette = Mem(Bits(24 bits), initialContent = TileAttributeAssets.paletteInit)
  val paletteRgb = palette.readAsync(paletteAddr)

  // R1 Raster Trigger Unit. Pending status is used below as a visible split
  // indicator (inverts the red channel after the trigger fires), which is the
  // mandated hardware proof signature from TASK_R1_RASTER_TRIGGER_UNIT.md.
  val rasterTrigger = RasterTriggerUnit()
  rasterTrigger.io.vCounter       := vCounter.resize(10)
  rasterTrigger.io.hCounter       := hCounter.resize(10)
  rasterTrigger.io.triggerLine    := io.rasterTriggerLine
  rasterTrigger.io.triggerPixel   := io.rasterTriggerPixel
  rasterTrigger.io.pixelCmpEnable := io.rasterTriggerPxEnable
  rasterTrigger.io.enable         := io.rasterTriggerEnable
  rasterTrigger.io.clear          := io.rasterTriggerClear
  io.rasterTriggerPulse           := rasterTrigger.io.triggerPulse
  io.rasterTriggerPending         := rasterTrigger.io.pending

  // -------------------------------------------------------------------
  // Task 35 — Host-Facing IRQ + Sticky Status Register Bank.
  //
  // Address map (within the 0x0320..0x032F reserved block per
  // MODE0_REGISTER_BUS_SPEC.md §3):
  //   0x0320  STATUS_STICKY  — read via QSPI sel=5; writes write-1-to-clear
  //   0x0321  STATUS_ENABLE  — IRQ mask (1 = bit contributes to irq)
  //
  // Sticky bit mapping (low byte, upper bits reserved for future events):
  //   bit 0 : RASTER_MATCH    — rasterTriggerPulse rising edge
  //   bit 1 : SPRITE_OVERFLOW — spriteEval.overflowFlag pulse
  //   bit 2 : QSPI_READY      — QSPI cmd_valid pulse (command accepted)
  //   bit 3 : QSPI_ERROR      — QspiDecoder.last_error non-zero (level)
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
  val evRasterMatch    = rasterTrigger.io.triggerPulse
  val evSpriteOverflow = spriteEval.io.overflowFlag
  val evQspiReady      = io.statusEvQspiReady
  val evQspiError      = io.statusEvQspiError
  // Task 29 — extend event bus with sprite collision bits:
  //   bit 4: SPRITE_0_HIT   (sprite 0 non-transparent over non-transparent BG)
  //   bit 5: SPRITE_BG_HIT  (any sprite non-transparent over non-transparent BG)
  val evBus = (B(0, 10 bits) ## spriteBgHitPulse ## sprite0HitPulse ##
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

  val colorMath = ColorMath()
  colorMath.io.rgbIn    := paletteRgb
  colorMath.io.op       := colorMathReg(15 downto 14).asUInt
  colorMath.io.constant := colorMathReg(7  downto 0).asUInt
  colorMath.io.enable   := windowUnit.io.effect
  val mathRgb = colorMath.io.rgbOut

  io.hsync := !(hCounter >= hSyncStart && hCounter < hSyncEnd)
  io.vsync := !(vCounter >= vSyncStart && vCounter < vSyncEnd)
  io.de := activeVideo
  io.red := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue := B(0, 8 bits)
  when(activeVideo && primed) {
    val redRaw = mathRgb(23 downto 16)
    io.red   := Mux(rasterTrigger.io.pending, ~redRaw, redRaw)
    io.green := mathRgb(15 downto 8)
    io.blue  := mathRgb(7 downto 0)
  }
  io.x := hCounter.resize(10)
  io.y := vCounter.resize(10)
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
