package spinalhdlvdp

import spinal.core._

/** Task 26 wire-test — reverse-direction variant.
  *
  * Per user request: FPGA drives one of four pins HIGH at a time (the other
  * three held LOW so nothing floats) while the Pico reads GP8..GP11 as
  * inputs and reports via USB serial.  This makes the whole test machine-
  * observable on the host side (no need for a human to watch LEDs).
  *
  * One-hot schedule (1 s per step, cycles forever):
  *   step 0  (all low)  — sanity baseline
  *   step 1  SCK=1      Pico should see GP8  high, GP9/GP10/GP11 low
  *   step 2  CS =1      Pico should see GP9  high, others low
  *   step 3  IO0=1      Pico should see GP10 high, others low
  *   step 4  IO1=1      Pico should see GP11 high, others low
  *
  * LEDs also mirror the currently-driven pin so a human can cross-check.
  *
  * The 4 pins physically LOC to the same Tang pads as the QSPI slave
  * (41/42/48/49) — see `fpga/tang20k/tang20k_wire_rev.cst`.  This is a
  * throwaway diagnostic top; not part of Task 26's production build.
  */
case class TopTang20kWireRev() extends Component {
  setDefinitionName("top_tang20k")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits (6 bits)

  // QSPI pads driven as OUTPUTS for this diagnostic.
  val O_qspi_sck = out Bool()
  val O_qspi_cs  = out Bool()
  val O_qspi_io0 = out Bool()
  val O_qspi_io1 = out Bool()

  // -------- PLL / clock ---------------------------------------------------
  val pll = GowinRpll()
  pll.CLKIN := I_clk
  pll.CLKFB := False
  pll.FBDSEL := B(0, 6 bits)
  pll.IDSEL  := B(0, 6 bits)
  pll.ODSEL  := B(0, 6 bits)
  pll.DUTYDA := B(0, 4 bits)
  pll.PSDA   := B(0, 4 bits)
  pll.FDLY   := B(0, 4 bits)
  pll.RESET   := False
  pll.RESET_P := False

  val clkdiv = GowinClkdiv()
  clkdiv.HCLKIN := pll.CLKOUT
  clkdiv.CALIB  := True
  clkdiv.RESETN := pll.LOCK

  val pixelReset = !pll.LOCK
  val pixelClockDomain = ClockDomain(
    clock  = clkdiv.CLKOUT,
    reset  = pixelReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  // -------- One-hot driver ------------------------------------------------
  val pixelArea = new ClockingArea(pixelClockDomain) {
    // At ~25 MHz pixel clock, 1 s = 25M cycles. Use a 25-bit counter to
    // sweep each step roughly once a second, then advance the state.
    val tick       = Reg(UInt(25 bits)) init 0
    val tickRoll   = tick === U(25_000_000 - 1, 25 bits)
    tick := Mux(tickRoll, U(0, 25 bits), tick + 1)

    val step = Reg(UInt(3 bits)) init 0   // 0..4, wraps to 0
    when(tickRoll) {
      step := Mux(step === U(4, 3 bits), U(0, 3 bits), step + 1)
    }

    // One-hot decode: exactly one pin is HIGH per non-zero step; all pins
    // are LOW in step 0 so we also exercise a clean "off" baseline.
    val sck = (step === U(1, 3 bits))
    val cs  = (step === U(2, 3 bits))
    val io0 = (step === U(3, 3 bits))
    val io1 = (step === U(4, 3 bits))
  }

  O_qspi_sck := pixelArea.sck
  O_qspi_cs  := pixelArea.cs
  O_qspi_io0 := pixelArea.io0
  O_qspi_io1 := pixelArea.io1

  // LEDs mirror (active-low so high = lit); LED4 lit when PLL locked, LED5
  // lit when clkdiv ready — keep-alive signals for a human watching the
  // board.
  O_led := B"6'b111111"
  O_led(0) := !pixelArea.sck
  O_led(1) := !pixelArea.cs
  O_led(2) := !pixelArea.io0
  O_led(3) := !pixelArea.io1
  O_led(4) := !pll.LOCK
  O_led(5) := !clkdiv.CALIB   // always lit (CALIB tied True) — alive indicator
}

object TopTang20kWireRevVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kWireRev())
}
