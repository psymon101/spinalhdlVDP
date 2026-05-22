package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

object QspiBarebonesSim extends App {
  Config.sim.compile(QspiBarebones()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    
    dut.io.cs_n #= true
    dut.io.sck  #= false
    dut.io.mosi #= false
    dut.clockDomain.waitSampling(20)

    val writes = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.regWr.toBoolean) {
          writes += ((dut.io.regAddr.toInt, dut.io.regData.toInt))
        }
      }
    }

    def sendBit(bit: Boolean): Unit = {
      dut.io.mosi #= bit
      dut.clockDomain.waitSampling(5)
      dut.io.sck #= true
      dut.clockDomain.waitSampling(10)
      dut.io.sck #= false
      dut.clockDomain.waitSampling(5)
    }

    def sendByte(byte: Int): Unit = {
      for (i <- 7 downto 0) {
        sendBit(((byte >> i) & 1) != 0)
      }
    }

    def sendFrame(cmd: Int, addr: Int, data: Int): Unit = {
      dut.io.cs_n #= false
      dut.clockDomain.waitSampling(20)
      
      sendByte(cmd)
      sendByte(addr >> 8)
      sendByte(addr & 0xFF)
      sendByte(data >> 8)
      sendByte(data & 0xFF)
      
      dut.clockDomain.waitSampling(20)
      dut.io.cs_n #= true
      dut.clockDomain.waitSampling(50)
    }

    println("Case 1: REG_WRITE ADDR=0x0000 DATA=0x0123")
    writes.clear()
    sendFrame(0x01, 0x0000, 0x0123)
    assert(writes.size == 1, s"Expected 1 write, got ${writes.size}")
    assert(writes(0)._1 == 0x0000, f"Expected addr 0x0000, got 0x${writes(0)._1}%04X")
    assert(writes(0)._2 == 0x0123, f"Expected data 0x0123, got 0x${writes(0)._2}%04X")
    println("Case 1 PASS")

    println("Case 2: REG_WRITE ADDR=0x0001 DATA=0x03FF")
    writes.clear()
    sendFrame(0x01, 0x0001, 0x03FF)
    assert(writes.size == 1)
    assert(writes(0)._1 == 0x0001)
    assert(writes(0)._2 == 0x03FF)
    println("Case 2 PASS")

    println("Case 3: Invalid CMD=0x02")
    writes.clear()
    sendFrame(0x02, 0x0000, 0x5555)
    assert(writes.size == 0, "Should have ignored invalid CMD")
    println("Case 3 PASS")

    println("Case 4: Incomplete frame (39 bits)")
    writes.clear()
    dut.io.cs_n #= false
    dut.clockDomain.waitSampling(20)
    for (_ <- 0 until 39) sendBit(true)
    dut.io.cs_n #= true
    dut.clockDomain.waitSampling(50)
    assert(writes.size == 0, "Should have ignored incomplete frame")
    println("Case 4 PASS")

    println("QspiBarebonesSim: ALL CASES PASS")
  }
}
