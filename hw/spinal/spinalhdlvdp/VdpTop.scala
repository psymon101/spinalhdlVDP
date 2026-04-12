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
    val sprite0X = in UInt(10 bits)
    val sprite0Y = in UInt(10 bits)
    val sprite0Enabled = in Bool()
    val sprite1X = in UInt(10 bits)
    val sprite1Y = in UInt(10 bits)
    val sprite1Enabled = in Bool()
    val lsWriteAddr   = in UInt(log2Up(480) bits)
    val lsWriteData   = in Bits(12 bits)
    val lsWriteEnable = in Bool()
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

  // Layer 1 (higher priority background).
  val layer1 = BasicPatternSource()
  layer1.io.x := hCounter.resize(10)
  layer1.io.y := fillLine
  layer1.io.scrollX := io.layer1ScrollX
  layer1.io.scrollY := io.layer1ScrollY

  // Multi-layer composition with linestate enables.
  val layer0Pixel = Mux(linestate.io.layer0Enable, layer0.io.pixelIndex.resize(4), B(0, 4 bits))
  val layer1Pixel = Mux(linestate.io.layer1Enable, layer1.io.pixelIndex.resize(4), B(0, 4 bits))
  val composedBg = Mux(layer1Pixel =/= B(0, 4 bits), layer1Pixel, layer0Pixel)

  // Sprite attribute stores.
  val spriteAttrStrobe = hCounter === hTotal - 1 && vCounter === vSyncStart
  val sprite0Attr = SpriteAttributes()
  sprite0Attr.io.writeX := io.sprite0X
  sprite0Attr.io.writeY := io.sprite0Y
  sprite0Attr.io.writeEnabled := io.sprite0Enabled
  sprite0Attr.io.writeStrobe := spriteAttrStrobe

  val sprite1Attr = SpriteAttributes()
  sprite1Attr.io.writeX := io.sprite1X
  sprite1Attr.io.writeY := io.sprite1Y
  sprite1Attr.io.writeEnabled := io.sprite1Enabled
  sprite1Attr.io.writeStrobe := spriteAttrStrobe

  // Sprite patterns: sprite 0 uses diamond pattern, sprite 1 uses cross pattern.
  val sprite0Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite0PatternInit)
  val sprite1Pattern = Mem(Bits(4 bits), initialContent = VdpTop.sprite1PatternInit)

  val fillX = hCounter.resize(10)

  // Sprite 0 evaluation.
  val s0Row = (fillLine - sprite0Attr.io.y).resize(10)
  val s0Col = (fillX - sprite0Attr.io.x).resize(10)
  val s0OnLine = sprite0Attr.io.enabled && fillLine >= sprite0Attr.io.y && fillLine < (sprite0Attr.io.y + 16)
  val s0OnPixel = fillX >= sprite0Attr.io.x && fillX < (sprite0Attr.io.x + 16)
  val s0Active = s0OnLine && s0OnPixel
  val s0Pixel = sprite0Pattern.readAsync((s0Row(3 downto 0) ## s0Col(3 downto 0)).asUInt)
  val s0Visible = s0Active && s0Pixel =/= B(0, 4 bits)

  // Sprite 1 evaluation (higher priority).
  val s1Row = (fillLine - sprite1Attr.io.y).resize(10)
  val s1Col = (fillX - sprite1Attr.io.x).resize(10)
  val s1OnLine = sprite1Attr.io.enabled && fillLine >= sprite1Attr.io.y && fillLine < (sprite1Attr.io.y + 16)
  val s1OnPixel = fillX >= sprite1Attr.io.x && fillX < (sprite1Attr.io.x + 16)
  val s1Active = s1OnLine && s1OnPixel
  val s1Pixel = sprite1Pattern.readAsync((s1Row(3 downto 0) ## s1Col(3 downto 0)).asUInt)
  val s1Visible = s1Active && s1Pixel =/= B(0, 4 bits)

  // Priority chain: Sprite 1 > Sprite 0 > Layer 1 > Layer 0.
  val fillPixel = Bits(4 bits)
  when(s1Visible) {
    fillPixel := s1Pixel
  }.elsewhen(s0Visible) {
    fillPixel := s0Pixel
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

  io.hsync := !(hCounter >= hSyncStart && hCounter < hSyncEnd)
  io.vsync := !(vCounter >= vSyncStart && vCounter < vSyncEnd)
  io.de := activeVideo
  io.red := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue := B(0, 8 bits)
  when(activeVideo && primed) {
    io.red := paletteRgb(23 downto 16)
    io.green := paletteRgb(15 downto 8)
    io.blue := paletteRgb(7 downto 0)
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

  val paletteInit: Seq[Bits] = paletteColors.map(c => B(c, 24 bits))

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
  val sprite0PatternInit: Seq[Bits] = spritePatternData.flatten.map(v => B(v, 4 bits))

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

  val sprite1PatternInit: Seq[Bits] = sprite1PatternData.flatten.map(v => B(v, 4 bits))

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
