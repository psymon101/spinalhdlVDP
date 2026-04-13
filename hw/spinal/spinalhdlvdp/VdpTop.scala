package spinalhdlvdp

import spinal.core._

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
    val lsWriteAddr   = in UInt(log2Up(480) bits)
    val lsWriteData   = in Bits(12 bits)
    val lsWriteEnable = in Bool()

    // Task 15 Layer-0 SDRAM source interface.
    //   - layer0UseSdram routes the external SDRAM-backed pixel into L0
    //     instead of the on-chip BasicPatternSource (for the switchable
    //     comparison path).
    //   - layer0SdramPixel comes from SdramTileFetch.io.pixelIndex.
    //   - layer0Fetch* are outputs that drive the external fetch engine. The
    //     raster owner decides the scroll/line/pixelAddr so the fetch contract
    //     stays at the VdpTop boundary.
    val layer0UseSdram        = in Bool()
    val layer0SdramPixel      = in Bits(3 bits)
    val layer0FetchStart      = out Bool()
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
  linestate.io.writeAddr := io.lsWriteAddr
  linestate.io.writeData := io.lsWriteData
  linestate.io.writeEnable := io.lsWriteEnable

  // Layer 0 (lower priority background).
  val layer0 = BasicPatternSource()
  layer0.io.x := hCounter.resize(10)
  layer0.io.y := fillLine
  layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX
  layer0.io.scrollY := io.layer0ScrollY

  // Task 15 fetch-control outputs. Atomic CDC pattern per 6626/6628:
  //   1) Pulse-harden fetchStart: widen to 4 pixel cycles so the SDRAM-side
  //      BufferCC (2-stage synchronizer) reliably samples it despite routing
  //      delay and phase alignment with the 64.8 MHz SDRAM clock.
  //   2) Atomic latch: capture fetchLine/scrolls into registers ONCE on the
  //      line-boundary strobe so the multi-bit CDC sees stable values between
  //      pulses. Sampling `(vCounter+3)` combinationally through BufferCC would
  //      let bits transition asynchronously during the sync, risking a "torn"
  //      scanline index on specific raster positions.
  val fetchStartStrobe = hCounter === hTotal - 1

  val fetchStartCount = Reg(UInt(3 bits)) init 0
  when(fetchStartStrobe) {
    fetchStartCount := 4
  }.elsewhen(fetchStartCount =/= 0) {
    fetchStartCount := fetchStartCount - 1
  }

  val fetchLineReg    = RegNextWhen((vCounter + 3).resize(10),
                                    fetchStartStrobe) init 0
  val fetchScrollXReg = RegNextWhen(io.layer0ScrollX + linestate.io.layer0ScrollX,
                                    fetchStartStrobe) init 0
  val fetchScrollYReg = RegNextWhen(io.layer0ScrollY, fetchStartStrobe) init 0

  io.layer0FetchStart     := fetchStartCount =/= 0
  io.layer0FetchLine      := fetchLineReg
  io.layer0FetchScrollX   := fetchScrollXReg
  io.layer0FetchScrollY   := fetchScrollYReg
  io.layer0FetchPixelAddr := hCounter.resize(10)

  // Layer 1 (higher priority background).
  val layer1 = BasicPatternSource()
  layer1.io.x := hCounter.resize(10)
  layer1.io.y := fillLine
  layer1.io.scrollX := io.layer1ScrollX
  layer1.io.scrollY := io.layer1ScrollY

  // Task 15: runtime Layer-0 source mux. When layer0UseSdram is high, the
  // SDRAM-backed pixel from the external fetch engine feeds L0. The on-chip
  // BasicPatternSource is kept instantiated and reading as the comparison
  // baseline so A/B can happen on the same hardware image.
  val layer0Source = Mux(io.layer0UseSdram, io.layer0SdramPixel, layer0.io.pixelIndex)

  // Multi-layer composition with linestate enables.
  val layer0Pixel = Mux(linestate.io.layer0Enable, layer0Source.resize(4), B(0, 4 bits))
  val layer1Pixel = Mux(linestate.io.layer1Enable, layer1.io.pixelIndex.resize(4), B(0, 4 bits))
  val composedBg = Mux(layer1Pixel =/= B(0, 4 bits), layer1Pixel, layer0Pixel)

  // R2: two-pass sprite evaluator over 4 descriptors, 2 visible per line.
  // Descriptors come directly from the top-level sprite* inputs. Evaluator
  // latches the active list at the line boundary strobe; pass 2 reads it.
  val spriteEval = SpriteEvaluator(descCount = 4, visiblePerLine = 2, patternSelBits = 1)
  spriteEval.io.descX(0)          := io.sprite0X
  spriteEval.io.descY(0)          := io.sprite0Y
  spriteEval.io.descEnabled(0)    := io.sprite0Enabled
  spriteEval.io.descPatternIdx(0) := io.sprite0PatternIdx
  spriteEval.io.descX(1)          := io.sprite1X
  spriteEval.io.descY(1)          := io.sprite1Y
  spriteEval.io.descEnabled(1)    := io.sprite1Enabled
  spriteEval.io.descPatternIdx(1) := io.sprite1PatternIdx
  spriteEval.io.descX(2)          := io.sprite2X
  spriteEval.io.descY(2)          := io.sprite2Y
  spriteEval.io.descEnabled(2)    := io.sprite2Enabled
  spriteEval.io.descPatternIdx(2) := io.sprite2PatternIdx
  spriteEval.io.descX(3)          := io.sprite3X
  spriteEval.io.descY(3)          := io.sprite3Y
  spriteEval.io.descEnabled(3)    := io.sprite3Enabled
  spriteEval.io.descPatternIdx(3) := io.sprite3PatternIdx
  // At end of line M (hCounter==hTotal-1), fillLine is M+1 for vCounter=M.
  // The latched slot data is consumed during line M+1, where fillPixel is
  // being computed for the next LineBuffer write (line M+2). So evalLine
  // must be `fillLine + 1` = M+2 to match what layer0/1 sample during the
  // next line's fill. Without this +1 the sprite y-offsets are off by one
  // line relative to the composition, and sprites at their declared Y do
  // not render.
  spriteEval.io.evalLine  := (fillLine + 1).resize(10)
  spriteEval.io.evalStart := hCounter === hTotal - 1
  io.spriteOverflow := spriteEval.io.overflowFlag

  // Sprite pattern memories: 256 × 4-bit, power-of-two (GT-022 safe).
  val sprite0Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite0PatternInit)
  val sprite1Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite1PatternInit)

  val fillX = hCounter.resize(10)

  // Per active-slot pixel resolution. Each slot picks its pattern Mem via
  // activePatternIdx(s). Pixel is non-transparent where both the X-range
  // covers fillX and the pattern pixel is non-zero.
  val slotVisible = Vec(Bool(), 2)
  val slotPixel   = Vec(Bits(4 bits), 2)
  for (s <- 0 until 2) {
    val x       = spriteEval.io.activeX(s)
    val row     = spriteEval.io.activeRow(s)
    val valid   = spriteEval.io.activeValid(s)
    val patIdx  = spriteEval.io.activePatternIdx(s)
    val col     = (fillX - x).resize(10)
    val onPixel = fillX >= x && fillX < (x + 16)
    val active  = valid && onPixel
    val addr    = (row(3 downto 0) ## col(3 downto 0)).asUInt
    val p0      = sprite0Pattern.readAsync(addr)
    val p1      = sprite1Pattern.readAsync(addr)
    val pixel   = Mux(patIdx === U(0, 1 bit), p0, p1)
    slotPixel(s)   := pixel
    slotVisible(s) := active && pixel =/= B(0, 4 bits)
  }

  // Priority: slot 1 over slot 0 (i.e., evaluator's "second-lowest" wins in
  // front of "lowest" — matches the previous s1>s0 demo). This can flip once
  // a later adapter defines a different priority rule; for R2's bounded scope
  // the important property is that the order is DETERMINISTIC AND STABLE.
  val fillPixel = Bits(4 bits)
  when(slotVisible(1)) {
    fillPixel := slotPixel(1)
  }.elsewhen(slotVisible(0)) {
    fillPixel := slotPixel(0)
  }.otherwise {
    fillPixel := composedBg
  }

  // Double-buffered scanline buffer: 4-bit padded pixel index, 640 pixels wide.
  // Uses readSync for BSRAM inference — address presented 1 cycle before data needed.
  val lineBuf = LineBuffer(pixelWidth = 4, lineWidth = hActive)
  lineBuf.io.writeEnable := hCounter < hActive
  lineBuf.io.writeAddr := hCounter.resized
  lineBuf.io.writeData := fillPixel
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

  // Drain: readSync output is the 4-bit padded index, available 1 cycle after address.
  val pixelIndex = lineBuf.io.readData.asUInt

  // Palette: 16-entry × 24-bit RGB lookup, initialized to match previous switch-case.
  val palette = Mem(Bits(24 bits), initialContent = VdpTop.paletteInit)
  val paletteRgb = palette.readAsync(pixelIndex)

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

  io.hsync := !(hCounter >= hSyncStart && hCounter < hSyncEnd)
  io.vsync := !(vCounter >= vSyncStart && vCounter < vSyncEnd)
  io.de := activeVideo
  io.red := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue := B(0, 8 bits)
  when(activeVideo && primed) {
    val redRaw = paletteRgb(23 downto 16)
    io.red   := Mux(rasterTrigger.io.pending, ~redRaw, redRaw)
    io.green := paletteRgb(15 downto 8)
    io.blue  := paletteRgb(7 downto 0)
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
