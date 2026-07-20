package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** #11308 — QspiDecoder SDRAM_WRITE payload LEN-bound hardening proof.
  *
  * Root cause of the HW halfword/shift corruption (#11297/#11305): libvdp padded
  * QSPI transfers to a 4-byte boundary; the FPGA decoder forwarded those trailing
  * 0x00 bytes as payload (no LEN bound), desyncing the SDRAM address stream. The
  * host removed the padding; this sim proves the RTL is now ALSO robust — trailing
  * bytes beyond LEN are dropped, and a back-to-back second header is accepted with
  * its byte budget freshly reloaded (no ignored header / orphan payload).
  *
  * Drives QspiDecoder directly (cmd_* header + payload_* bytes), counts forwarded
  * sdramByteValid pulses + sdramHeaderValid pulses.
  */
object QspiDecoderSdramBoundSim extends App {
  Config.sim.compile(QspiDecoder()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.cmd_opcode #= 0; dut.io.cmd_addr #= 0; dut.io.cmd_len #= 0
    dut.io.cmd_valid #= false; dut.io.payload_byte #= 0; dut.io.payload_valid #= false
    dut.io.tx_byte_sent #= false; dut.io.active #= false; dut.io.status_sticky #= 0
    dut.io.live_mode #= 0; dut.io.debug_sdram_data #= 0
    dut.io.upload_busy #= false; dut.io.upload_done #= false; dut.io.upload_error #= false; dut.io.upload_overflow #= false
    // Word-drain word-egress inputs (added by transport-core cherry-pick a4dcdf4). This sim
    // exercises the legacy payload_byte SDRAM path; hold the word path inactive so it cannot
    // drain sdramBytesLeft (-2/word) and starve the byte forward under test.
    dut.io.payload_word #= 0; dut.io.payload_word_valid #= false
    dut.clockDomain.waitSampling(5)

    val fwd = mutable.ArrayBuffer[Int]()
    var hdrCount = 0
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.sdramByteValid.toBoolean)   fwd += (dut.io.sdramByteOut.toInt & 0xFF)
        if (dut.io.sdramHeaderValid.toBoolean)  hdrCount += 1
      }
    }

    def header(addr: Int, words: Int): Unit = {
      dut.io.cmd_opcode #= 0x02            // Op.SDRAM_WRITE
      dut.io.cmd_addr   #= addr
      dut.io.cmd_len    #= words
      dut.io.cmd_valid  #= true
      dut.clockDomain.waitSampling()
      dut.io.cmd_valid  #= false
      dut.clockDomain.waitSampling()
    }
    def payload(b: Int): Unit = {
      dut.io.payload_byte  #= b
      dut.io.payload_valid #= true
      dut.clockDomain.waitSampling()
      dut.io.payload_valid #= false
      dut.clockDomain.waitSampling()
    }

    // ---- Test 1: LEN=2 words (4 bytes) then 2 PADDING bytes (must be dropped) ----
    header(0xB000, 2)
    Seq(0x44, 0x33, 0x22, 0x11).foreach(payload)   // the 4 real bytes (LE 0x11223344)
    payload(0x00); payload(0x00)                   // host 4-byte padding — must be IGNORED
    dut.clockDomain.waitSampling(10)
    assert(fwd.size == 4, s"Test1: expected 4 forwarded bytes, got ${fwd.size}: ${fwd.map(_.toHexString)}")
    assert(fwd.toSeq == Seq(0x44, 0x33, 0x22, 0x11), s"Test1: wrong bytes: ${fwd.map(_.toHexString)}")
    println("[sim] Test1 LEN-bound: 4 real bytes forwarded, 2 padding bytes DROPPED — PASS")

    // ---- Test 2: back-to-back second header immediately (no drain) ----
    fwd.clear()
    header(0xC000, 1)                              // 1 word = 2 bytes
    Seq(0xAB, 0xCD).foreach(payload)
    payload(0x00); payload(0x00)                   // padding again
    dut.clockDomain.waitSampling(10)               // settle so the capture fork sees the header pulse
    assert(hdrCount == 2, s"Test2: back-to-back header IGNORED (total headers=$hdrCount, expected 2)")
    assert(fwd.size == 2, s"Test2: expected 2 bytes (no orphan), got ${fwd.size}: ${fwd.map(_.toHexString)}")
    assert(fwd.toSeq == Seq(0xAB, 0xCD), s"Test2: wrong bytes: ${fwd.map(_.toHexString)}")
    println("[sim] Test2 back-to-back header: accepted, byte budget reloaded, padding dropped — PASS")

    println("QspiDecoderSdramBoundSim: PASS")
  }
}
