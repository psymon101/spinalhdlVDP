package spinalhdlvdp

import spinal.core._

/** TopTang20kI80Continuity — throwaway pin-continuity exerciser for the i80 harness
  * (lane P21 side-lane, TopazCliff #12039). NOT a VDP build: no PLL, no SDRAM, no
  * video. Drives D0-D7 as a walking-1 and mirrors the four control inputs onto LEDs
  * so the ESP32-S3 side can verify every wire before protocol bring-up.
  *
  *   - IO_i80_d[7:0] : OUTPUT walking-1 (0x01,0x02,0x04,...0x80), ~19 ms/step so a
  *                     logic analyzer or a polling MCU both catch it.
  *   - I_i80_cs/wr/rd/dc -> O_led[0..3] : LED follows the pin level (Tang LEDs are
  *                     active-low, so driving a control pin LOW lights its LED).
  *   - O_led[4]/[5]  : slow heartbeat so a dead clock is obvious.
  *
  * Pins are the locked i80 map (tang20k_i80.cst): D0-7 = 25/26/27/28/29/30/31/41,
  * CS/WR/RD/DC = 76/77/80/85. Reuses those constraints via tang20k_i80_continuity.cst.
  */
case class TopTang20kI80Continuity(stepShift: Int = 19) extends Component {
  // stepShift sets the walking-1 step rate (cnt bit the step index starts at). 19 =
  // ~19 ms/step at 27 MHz for the bench bitstream; sim overrides it small so the full
  // 8-step walk is observable in a few dozen cycles.
  setDefinitionName("top_tang20k_i80_cont")
  noIoPrefix()

  val I_clk    = in  Bool()
  val O_led    = out Bits(6 bits)
  val IO_i80_d = out Bits(8 bits)
  val I_i80_cs = in  Bool()
  val I_i80_wr = in  Bool()
  val I_i80_rd = in  Bool()
  val I_i80_dc = in  Bool()

  // Free-running counter clocked directly off the 27 MHz crystal (no PLL needed).
  val core = new ClockingArea(ClockDomain(clock = I_clk,
      config = ClockDomainConfig(resetKind = BOOT))) {   // GSR power-up init, no reset pin
    val cnt  = Reg(UInt(27 bits)) init 0
    cnt := cnt + 1
    val step = cnt(stepShift + 2 downto stepShift)   // 0..7, ~19 ms/step at 27 MHz (bench)
    val walk = (B(1, 8 bits) |<< step)        // walking single 1 across D0..D7
  }

  IO_i80_d  := core.walk
  O_led(0)  := I_i80_cs                       // lit (LED low) when CS# driven low
  O_led(1)  := I_i80_wr
  O_led(2)  := I_i80_rd
  O_led(3)  := I_i80_dc
  O_led(4)  := core.step(0)                   // toggles ~every step -> visible heartbeat
  O_led(5)  := core.cnt(26)                   // ~0.2 Hz blink -> clock-alive indicator
}

object TopTang20kI80ContinuityVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kI80Continuity())
}
