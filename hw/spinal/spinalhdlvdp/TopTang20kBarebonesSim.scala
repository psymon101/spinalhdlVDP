package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Integration sim for TopTang20kBarebones.
  *
  * This sim operates at the pin level to verify that the SPI receiver
  * correctly drives the register bus and moves the rendered background.
  */
object TopTang20kBarebonesSim extends App {
  // Verilator needs the source for internal modules (rPLL, CLKDIV, etc)
  // We'll use a simulation model that avoids the Gowin primitives.
  
  class BarebonesHarness extends Component {
    val io = new Bundle {
      val cs_n = in Bool()
      val sck  = in Bool()
      val mosi = in Bool()
      
      val hsync = out Bool()
      val vsync = out Bool()
      val de    = out Bool()
      val red   = out Bits(8 bits)
      val green = out Bits(8 bits)
      val blue  = out Bits(8 bits)
      
      // Internal probes
      val scrollX = out UInt(10 bits)
      val scrollY = out UInt(10 bits)
      val x = out UInt(10 bits)
      val y = out UInt(10 bits)
    }
    
    // Instead of using the top-level PLL/HDMI-TX (which are Gowin/Verilog specific),
    // we instantiate the inner logic area.
    
    // PM #10034 stage-2 + PM #10051 stage-4: QSPI receive + 4-register file.
    val qspi = QspiBarebones()
    qspi.io.cs_n := io.cs_n
    qspi.io.sck  := io.sck
    qspi.io.mosi := io.mosi
    
    val scrollXReg  = Reg(UInt(10 bits)) init 0
    val scrollYReg  = Reg(UInt(10 bits)) init 0
    val scrollX1Reg = Reg(UInt(10 bits)) init 0
    val scrollY1Reg = Reg(UInt(10 bits)) init 0
    val spriteXReg  = Reg(UInt(10 bits)) init 0
    val spriteYReg  = Reg(UInt(10 bits)) init 0
    when(qspi.io.regWr) {
      switch(qspi.io.regAddr) {
        is(U(0x0000, 16 bits)) { scrollXReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0001, 16 bits)) { scrollYReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0002, 16 bits)) { scrollX1Reg := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0003, 16 bits)) { scrollY1Reg := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0004, 16 bits)) { spriteXReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0005, 16 bits)) { spriteYReg  := qspi.io.regData(9 downto 0).asUInt }
      }
    }
    
    // Horizontal and vertical counters (VGA 640x480 defaults)
    val hTotal = 800; val hActive = 640; val hSyncStart = 656; val hSyncEnd = 752
    val vTotal = 525; val vActive = 480; val vSyncStart = 490; val vSyncEnd = 492
    
    val hCounter = Reg(UInt(10 bits)) init 0
    val vCounter = Reg(UInt(10 bits)) init 0
    
    hCounter := hCounter + 1
    when(hCounter === hTotal - 1) {
      hCounter := 0
      vCounter := vCounter + 1
      when(vCounter === vTotal - 1) {
        vCounter := 0
      }
    }
    
    val de = (hCounter < hActive) && (vCounter < vActive)
    
    val layer0 = BasicPatternSource()
    layer0.io.x := hCounter
    layer0.io.y := vCounter
    layer0.io.scrollX := scrollXReg
    layer0.io.scrollY := scrollYReg

    val layer1 = BasicPatternSource()
    layer1.io.x := hCounter
    layer1.io.y := vCounter
    layer1.io.scrollX := scrollX1Reg
    layer1.io.scrollY := scrollY1Reg
    
    val paletteRom = Vec(
      B"24'h000000", B"24'hFFFFFF", B"24'hFF0000", B"24'h00FF00",
      B"24'h0000FF", B"24'hFFFF00", B"24'h00FFFF", B"24'hFF00FF"
    )
    val paletteL1Rom = Vec(
      B"24'h000000", B"24'h804000", B"24'h00FFFF", B"24'hFF00FF",
      B"24'hFFFF00", B"24'h0000FF", B"24'hFF0000", B"24'h00FF00"
    )

    val layer0Idx = layer0.io.pixelIndex.asUInt
    val layer1Idx = layer1.io.pixelIndex.asUInt
    val layer0Rgb = paletteRom  (layer0Idx)
    val layer1Rgb = paletteL1Rom(layer1Idx)

    val l1Opaque = layer1Idx =/= U(0, 3 bits)
    val bgRgb = Mux(l1Opaque, layer1Rgb, layer0Rgb)
    val sprHit = (hCounter >= spriteXReg) &&
                 (hCounter < (spriteXReg + U(16, 11 bits)).resize(hCounter.getWidth)) &&
                 (vCounter >= spriteYReg) &&
                 (vCounter < (spriteYReg + U(16, 11 bits)).resize(vCounter.getWidth)) &&
                 de
    val rgb = Mux(sprHit, B"24'hFFFFFF", bgRgb)

    io.hsync := !(hCounter >= hSyncStart && hCounter < hSyncEnd)
    io.vsync := !(vCounter >= vSyncStart && vCounter < vSyncEnd)
    io.de    := de
    io.red   := Mux(de, rgb(23 downto 16), B(0, 8 bits))
    io.green := Mux(de, rgb(15 downto  8), B(0, 8 bits))
    io.blue  := Mux(de, rgb( 7 downto  0), B(0, 8 bits))
    
    io.scrollX := scrollXReg
    io.scrollY := scrollYReg
    io.x := hCounter
    io.y := vCounter
  }

  Config.sim.compile(new BarebonesHarness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    
    dut.io.cs_n #= true
    dut.io.sck  #= false
    dut.io.mosi #= false
    dut.clockDomain.waitSampling(100)

    def sendBit(bit: Boolean): Unit = {
      dut.io.mosi #= bit
      dut.clockDomain.waitSampling(5); dut.io.sck #= true
      dut.clockDomain.waitSampling(10); dut.io.sck #= false
      dut.clockDomain.waitSampling(5)
    }

    def sendByte(byte: Int): Unit = {
      for (i <- 7 downto 0) sendBit(((byte >> i) & 1) != 0)
    }

    def vdpWrite(addr: Int, data: Int): Unit = {
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(20)
      sendByte(0x01)
      sendByte(addr >> 8); sendByte(addr & 0xFF)
      sendByte(data >> 8); sendByte(data & 0xFF)
      dut.clockDomain.waitSampling(20); dut.io.cs_n #= true
      dut.clockDomain.waitSampling(100)
    }

    def getPixel(): Int = {
      val r = dut.io.red.toInt; val g = dut.io.green.toInt; val b = dut.io.blue.toInt
      if (r == 255 && g == 0 && b == 0) 2 // red
      else if (r == 0 && g == 0 && b == 0) 0 // black
      else if (r == 0x80 && g == 0x40 && b == 0) 10 // brown (L1 white replacement)
      else if (r == 0 && g == 255 && b == 255) 6 // cyan (L1 red replacement)
      else -1
    }

    def waitForPixel(tx: Int, ty: Int): Unit = {
      var found = false
      var timeout = 0
      while(!found && timeout < 1000000) {
        dut.clockDomain.waitSampling()
        timeout += 1
        if (dut.io.x.toInt == tx && dut.io.y.toInt == ty) found = true
      }
    }

    // Park the sprite off-screen so the existing scroll tests sample
    // background colour unobscured by the white sprite (sprite defaults
    // to (0,0) which would otherwise mask the (0,0) cyan check below).
    vdpWrite(0x0004, 1000); vdpWrite(0x0005, 1000)

    // Step 1: Initial state check
    waitForPixel(0, 0)
    val p0 = getPixel()
    println(s"Pixel at (0,0) with scroll(0,0): $p0")
    // In L1 over L0 compositor, if L1 is opaque it wins.
    // L1 at (0,0) is tile0, pixel (0,0) is colorRed (index 2).
    // So (0,0) should be p6 (cyan) because L1 tile0 at (0,0) is red -> cyan in paletteL1Rom.
    assert(p0 == 6, s"Expected CYAN at (0,0) from L1, got $p0")

    // Step 2: Write L0 ScrollX = 4 (should be hidden by L1)
    println("Setting L0 ScrollX to 4...")
    vdpWrite(0x0000, 4)
    waitForPixel(0, 0)
    val p1 = getPixel()
    println(s"Pixel at (0,0) with L0 scroll(4,0): $p1")
    assert(p1 == 6, s"Expected CYAN at (0,0) (L1 still hiding L0), got $p1")

    // Step 3: Write L1 ScrollX = 4
    // L1 tile0 at (4,0) is BLACK (transparent idx 0).
    // So L0 should show through. L0 scroll is 0, so L0 at (0,0) is colorRed (index 2).
    // Index 2 in paletteRom is RED.
    println("Setting L0 ScrollX to 0, L1 ScrollX to 4...")
    vdpWrite(0x0000, 0)
    vdpWrite(0x0002, 4)
    waitForPixel(0, 0)
    val p2 = getPixel()
    println(s"Pixel at (0,0) with L1 scroll(4,0): $p2")
    assert(p2 == 2, s"Expected RED at (0,0) (L0 showing through L1 hole), got $p2")

    // PM #10080 sprite slice tests --------------------------------------
    // Sample current (r, g, b) directly without index translation.
    def sampleRgb(): (Int, Int, Int) = {
      (dut.io.red.toInt, dut.io.green.toInt, dut.io.blue.toInt)
    }

    // Wait until (hCounter, vCounter) reaches (tx, ty), then sample RGB.
    def rgbAt(tx: Int, ty: Int): (Int, Int, Int) = {
      waitForPixel(tx, ty)
      sampleRgb()
    }

    // The sprite render is registered downstream by one pixel-clock in the
    // top, but the harness drives io.red/green/blue combinationally from rgb,
    // so the sample lands on the exact (h,v) coordinate.
    def isWhite(rgb: (Int, Int, Int)): Boolean = rgb == (255, 255, 255)

    // Test 1: spriteAtTest — place sprite at (200, 100), confirm centre
    // pixel is white and a pixel outside the bounding box is NOT white.
    println("\n[sprite] Test 1: spriteAtTest")
    vdpWrite(0x0000, 0); vdpWrite(0x0001, 0)   // park both scrolls
    vdpWrite(0x0002, 0); vdpWrite(0x0003, 0)
    vdpWrite(0x0004, 200); vdpWrite(0x0005, 100)
    val sprIn  = rgbAt(208, 108)                 // inside 16x16 box
    val sprOut = rgbAt(180, 108)                 // 20 px left of box -> background
    println(s"[sprite] inside (208,108) = $sprIn  outside (180,108) = $sprOut")
    assert(isWhite(sprIn),   s"Expected WHITE at sprite-inside (208,108), got $sprIn")
    assert(!isWhite(sprOut), s"Expected non-WHITE at sprite-outside (180,108), got $sprOut")

    // Test 2: spritePositionUpdateTest — move sprite to (300, 240) and
    // confirm new position is white AND old position has reverted to bg.
    println("\n[sprite] Test 2: spritePositionUpdateTest")
    vdpWrite(0x0004, 300); vdpWrite(0x0005, 240)
    val oldPos = rgbAt(208, 108)                 // previous sprite location
    val newPos = rgbAt(308, 248)                 // new sprite location
    println(s"[sprite] old (208,108) = $oldPos  new (308,248) = $newPos")
    assert(!isWhite(oldPos), s"Expected non-WHITE at old sprite pos (208,108), got $oldPos")
    assert(isWhite(newPos),  s"Expected WHITE at new sprite pos (308,248), got $newPos")

    println("\nTopTang20kBarebonesSim: ALL CASES PASS (scroll + sprite slice).")

  }
}
