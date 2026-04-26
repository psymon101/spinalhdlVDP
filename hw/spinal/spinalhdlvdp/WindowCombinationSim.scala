package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** CW-5 unit sim: dual-window combination logic.
  *
  * Mirrors the combMode mux in VdpTop with a tiny synthesized harness so
  * the boolean truth tables can be exercised exhaustively without paying
  * the cost of a full VdpTop simulation.
  *
  * Modes (per VdpTop CW-5 wiring):
  *   000 window1 only (legacy default)
  *   001 AND
  *   010 OR
  *   011 XOR
  *   100 INV_AND
  *   101 INV_OR
  *   11x reserved (treated as window1 only)
  */
case class WindowCombinationProbe() extends Component {
  val io = new Bundle {
    val effect1  = in  Bool()
    val effect2  = in  Bool()
    val combMode = in  UInt(3 bits)
    val combined = out Bool()
  }
  io.combined := io.combMode.mux(
    U(0, 3 bits) -> io.effect1,
    U(1, 3 bits) -> (io.effect1 && io.effect2),
    U(2, 3 bits) -> (io.effect1 || io.effect2),
    U(3, 3 bits) -> (io.effect1 ^ io.effect2),
    U(4, 3 bits) -> !(io.effect1 && io.effect2),
    U(5, 3 bits) -> !(io.effect1 || io.effect2),
    default      -> io.effect1
  )
}

object WindowCombinationSim extends App {
  Config.sim.compile(WindowCombinationProbe()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.effect1  #= false
    dut.io.effect2  #= false
    dut.io.combMode #= 0
    dut.clockDomain.waitSampling(2)

    def expect(mode: Int, e1: Boolean, e2: Boolean): Boolean = mode match {
      case 0 => e1
      case 1 => e1 && e2
      case 2 => e1 || e2
      case 3 => e1 ^ e2
      case 4 => !(e1 && e2)
      case 5 => !(e1 || e2)
      case _ => e1            // 6, 7 reserved → window1 only
    }

    def run(mode: Int, e1: Boolean, e2: Boolean): Boolean = {
      dut.io.combMode #= mode
      dut.io.effect1  #= e1
      dut.io.effect2  #= e2
      sleep(1)
      dut.io.combined.toBoolean
    }

    val modeNames = Map(
      0 -> "WIN1",
      1 -> "AND",
      2 -> "OR",
      3 -> "XOR",
      4 -> "INV_AND",
      5 -> "INV_OR",
      6 -> "RES6",
      7 -> "RES7"
    )

    for (mode <- 0 until 8) {
      for (e1 <- Seq(false, true); e2 <- Seq(false, true)) {
        val got = run(mode, e1, e2)
        val want = expect(mode, e1, e2)
        assert(got == want,
          s"mode=${modeNames(mode)} e1=$e1 e2=$e2 → got=$got want=$want")
      }
      println(s"[sim] combMode=$mode (${modeNames(mode)}) full truth table — OK")
    }

    println("[sim] WindowCombinationSim: PASS")
  }
}
