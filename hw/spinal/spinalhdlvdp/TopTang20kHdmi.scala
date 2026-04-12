package spinalhdlvdp

import spinal.core._

case class TopTang20kHdmi() extends Component {
  setDefinitionName("top_tang20k")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // The Tang Nano 20K provides a 27 MHz reference clock.
  // This PLL instance multiplies it to the TMDS serializer clock.
  val pll = GowinRpll()
  pll.CLKIN := I_clk
  pll.CLKFB := False
  pll.FBDSEL := B(0, 6 bits)
  pll.IDSEL := B(0, 6 bits)
  pll.ODSEL := B(0, 6 bits)
  pll.DUTYDA := B(0, 4 bits)
  pll.PSDA := B(0, 4 bits)
  pll.FDLY := B(0, 4 bits)
  pll.RESET := False
  pll.RESET_P := False

  // Divide the serializer clock by 5 to get the pixel clock used by the video core.
  val clkdiv = GowinClkdiv()
  clkdiv.HCLKIN := pll.CLKOUT
  clkdiv.CALIB := True
  clkdiv.RESETN := pll.LOCK

  val pixelReset = !pll.LOCK
  val pixelClockDomain = ClockDomain(
    clock = clkdiv.CLKOUT,
    reset = pixelReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  val pixelArea = new ClockingArea(pixelClockDomain) {
    val video = VdpTop()

    // Frame counter drives scroll offsets for visible motion proof.
    val vsyncPrev = RegNext(video.io.vsync) init True
    val vsyncRising = video.io.vsync && !vsyncPrev
    val frameCounter = Reg(UInt(10 bits)) init 0
    when(vsyncRising) {
      frameCounter := frameCounter + 1
    }
    // Dynamic background scroll to prevent constant-folding of the layer pipeline.
    video.io.layer0ScrollX := (frameCounter >> 1).resized
    video.io.layer0ScrollY := U(0, 10 bits)
    video.io.layer1ScrollX := frameCounter
    video.io.layer1ScrollY := U(0, 10 bits)

    // Linestate prepare-side write: tied off (initialized contents only).
    video.io.lsWriteAddr := U(0, log2Up(480) bits)
    video.io.lsWriteData := B(0, 12 bits)
    video.io.lsWriteEnable := False

    // Sprite 0: bounces diagonally at 1px/frame.
    val s0X = Reg(UInt(10 bits)) init 100
    val s0Y = Reg(UInt(10 bits)) init 200
    val s0DirX = Reg(Bool()) init False
    val s0DirY = Reg(Bool()) init False
    when(vsyncRising) {
      when(s0DirX) { when(s0X <= 1) { s0DirX := False } otherwise { s0X := s0X - 1 } }
        .otherwise { when(s0X >= 623) { s0DirX := True } otherwise { s0X := s0X + 1 } }
      when(s0DirY) { when(s0Y <= 1) { s0DirY := False } otherwise { s0Y := s0Y - 1 } }
        .otherwise { when(s0Y >= 463) { s0DirY := True } otherwise { s0Y := s0Y + 1 } }
    }
    video.io.sprite0X := s0X
    video.io.sprite0Y := s0Y
    video.io.sprite0Enabled := True

    // Sprite 1: bounces at 2px/frame, different starting position.
    val s1X = Reg(UInt(10 bits)) init 300
    val s1Y = Reg(UInt(10 bits)) init 150
    val s1DirX = Reg(Bool()) init True
    val s1DirY = Reg(Bool()) init False
    when(vsyncRising) {
      when(s1DirX) { when(s1X <= 2) { s1DirX := False } otherwise { s1X := s1X - 2 } }
        .otherwise { when(s1X >= 622) { s1DirX := True } otherwise { s1X := s1X + 2 } }
      when(s1DirY) { when(s1Y <= 2) { s1DirY := False } otherwise { s1Y := s1Y - 2 } }
        .otherwise { when(s1Y >= 462) { s1DirY := True } otherwise { s1Y := s1Y + 2 } }
    }
    video.io.sprite1X := s1X
    video.io.sprite1Y := s1Y
    video.io.sprite1Enabled := True

    // Keep the raster generator in SpinalHDL and hand off only TMDS encoding
    // and serialization to the board-specific transport wrapper.
    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel := clkdiv.CLKOUT
    hdmiTx.clk_pixel_x5 := pll.CLKOUT
    hdmiTx.reset := pixelReset
    hdmiTx.hsync := video.io.hsync
    hdmiTx.vsync := video.io.vsync
    hdmiTx.de := video.io.de
    hdmiTx.red := video.io.red
    hdmiTx.green := video.io.green
    hdmiTx.blue := video.io.blue

    O_tmds_clk_p := hdmiTx.tmds_clk_p
    O_tmds_clk_n := hdmiTx.tmds_clk_n
    O_tmds_data_p := hdmiTx.tmds_data_p
    O_tmds_data_n := hdmiTx.tmds_data_n

    // LEDs provide basic board-side bring-up visibility.
    O_led := B"6'b111111"
    O_led(0) := !pll.LOCK
    O_led(1) := !video.io.de
    O_led(2) := !video.io.vsync
    O_led(3) := !video.io.hsync
    O_led(4) := pll.LOCK
    O_led(5) := video.io.de
  }
}

object TopTang20kHdmiVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi())
}
