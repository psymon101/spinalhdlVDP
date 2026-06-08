package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** I80BridgeCoSim — CP-B2 proof (lane P21). Wires I80HostInterface -> QspiSdramBridge
  * exactly as TopTang20kHdmi(hostI80=true) does, drives an i80 block-write transaction,
  * and checks the bridge emits the correct per-byte SDRAM write commands (addr++ from
  * the block base). Proves the block-write FSM reaches the real SDRAM upload datapath. */
class I80BridgeDut extends Component {
  val io = new Bundle {
    val cs = in Bool(); val wr = in Bool(); val rd = in Bool(); val dc = in Bool()
    val dIn = in Bits(8 bits)
    val wrCmd = master Stream (Bits(31 bits))
    val fifoOverflow = out Bool()
  }
  val i80 = I80HostInterface(8)
  i80.io.cs := io.cs; i80.io.wr := io.wr; i80.io.rd := io.rd; i80.io.dc := io.dc
  i80.io.dIn := io.dIn
  i80.io.readData      := 0
  i80.io.blockWr.ready := True

  val bridge = QspiSdramBridge()
  bridge.io.headerValid := i80.io.blockHeaderValid
  bridge.io.addrInit    := i80.io.blockAddr
  bridge.io.lenBytes    := i80.io.blockLen.resize(17)
  bridge.io.byteIn      := i80.io.blockWr.payload
  bridge.io.byteValid   := i80.io.blockWr.valid
  bridge.io.allowUpload := True
  io.wrCmd <> bridge.io.wrCmd
  io.fifoOverflow := bridge.io.fifoOverflow
}

object I80BridgeCoSim extends App {
  Config.sim.compile(new I80BridgeDut).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.cs #= true; dut.io.wr #= true; dut.io.rd #= true; dut.io.dc #= false
    dut.io.dIn #= 0; dut.io.wrCmd.ready #= true
    dut.clockDomain.waitSampling(5)

    val cmds = scala.collection.mutable.ArrayBuffer[(Long, Int)]()
    dut.clockDomain.onSamplings {
      if (dut.io.wrCmd.valid.toBoolean && dut.io.wrCmd.ready.toBoolean) {
        val p = dut.io.wrCmd.payload.toLong
        cmds += (((p >> 8) & 0x7FFFFFL, (p & 0xFF).toInt))
      }
    }

    def wrByte(dcv: Boolean, b: Int): Unit = {
      dut.io.cs #= false; dut.io.dc #= dcv; dut.io.dIn #= b
      dut.io.wr #= false; dut.clockDomain.waitSampling(4)
      dut.io.wr #= true;  dut.clockDomain.waitSampling(4)
    }

    // block write: opcode 0x02, addr 0x012345, len 4, payload DE AD BE EF
    wrByte(false, 0x02)
    wrByte(false, 0x45); wrByte(false, 0x23); wrByte(false, 0x01)   // addr lo/mid/hi
    wrByte(false, 0x04); wrByte(false, 0x00)                         // len = 4
    wrByte(true, 0xDE); wrByte(true, 0xAD); wrByte(true, 0xBE); wrByte(true, 0xEF)
    dut.clockDomain.waitSampling(60)
    dut.io.cs #= true; dut.clockDomain.waitSampling(10)

    println(s"[sim] bridge wrCmd: ${cmds.map { case (a, d) => f"0x$a%06X<-0x$d%02X" }.mkString(", ")}")
    val exp = Seq((0x012345L, 0xDE), (0x012346L, 0xAD), (0x012347L, 0xBE), (0x012348L, 0xEF))
    assert(cmds.toSeq == exp, s"CP-B2 bridge writes wrong: $cmds")
    assert(!dut.io.fifoOverflow.toBoolean, "unexpected fifoOverflow")
    println("I80BridgeCoSim: PASS — i80 block-write 0x012345 len4 -> bridge wrCmd DE AD BE EF at 0x012345..0x012348")
  }
}
