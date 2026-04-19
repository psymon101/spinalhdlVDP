package spinalhdlvdp

import spinal.core._
import spinal.lib.fsm._

/** Task 34 — Bridge between `QspiDecoder`'s SDRAM_WRITE payload stream and
  * the `SdramController` 8-bit write port.
  *
  * Contract (pixel clock domain):
  *   - `headerValid` pulses when QspiDecoder receives a valid SDRAM_WRITE
  *     header.  `addrInit` and `lenBytes` are sampled on that pulse.
  *   - `byteValid` pulses each time a payload byte arrives from the
  *     decoder.  The bridge latches the byte and issues a per-byte SDRAM
  *     write at `addrInit + n`, where n counts from 0.
  *   - `allowUpload` gates writes to the controller.  Per artifact §4.4,
  *     Checkpoint C uses vblank-only gating; the top-level wires this to
  *     `!activeVideo` so upload writes never collide with fetch reads.
  *     When low, the bridge holds its output byte latched and waits; the
  *     host is expected to pace bursts so the small buffer doesn't
  *     overrun.
  *   - `sdramBusy` from the controller is honored: the bridge asserts
  *     `sdramWr` for one cycle when busy is low and allowUpload is high.
  *
  * Status outputs:
  *   - `uploadBusy` high while transaction is in flight (header latched,
  *     len bytes still pending).
  *   - `uploadDone` pulses one cycle when the last byte has been written.
  *
  * Address wrap (CyanPeak #7680 callout): `sdramAddr` is 23 bits, so the
  * addr + n calculation naturally wraps at 8 MB boundary. Bridge does
  * not reject out-of-range addresses — it is the host's responsibility
  * (per §6 risks table) to stay within allocated asset regions. Writes
  * beyond the writable region just corrupt reserved memory; the bridge
  * neither enforces bounds nor raises an error.
  */
case class QspiSdramBridge() extends Component {
  val io = new Bundle {
    // From QspiDecoder
    val headerValid = in Bool()
    val addrInit    = in UInt(23 bits)
    val lenBytes    = in UInt(17 bits)   // 2 * lenWords, up to 128 KB
    val byteIn      = in Bits(8 bits)
    val byteValid   = in Bool()

    // Arbitration gate (high = upload allowed to drive SDRAM)
    val allowUpload = in Bool()

    // To SDRAM controller
    val sdramWr     = out Bool()
    val sdramAddr   = out UInt(23 bits)
    val sdramDin    = out Bits(8 bits)
    val sdramBusy   = in  Bool()

    // Host-visible status
    val uploadBusy  = out Bool()
    val uploadDone  = out Bool()

    // Task 34 CDC fix (CyanPeak #7689, BronzeGate #7690 path β):
    // Toggle signal that flips on each successful sdramWr pulse. The
    // destination (sdram) clock domain BufferCC's this and edge-detects
    // to regenerate a one-cycle pulse in its own domain. Together with
    // the stable `sdramAddr` / `sdramDin` outputs (held unchanged between
    // writes, inherent to the FSM), this gives a safe pulse+data CDC
    // without losing writes due to unfavorable pixel↔sdram clock phase.
    val wrToggle = out Bool()
  }

  val addrReg    = Reg(UInt(23 bits)) init 0
  val bytesLeft  = Reg(UInt(17 bits)) init 0
  val latchedByte = Reg(Bits(8 bits)) init 0
  val hasByte    = Reg(Bool()) init False

  // The write address presented to the SDRAM controller lags addrReg by
  // one cycle so it carries the CURRENT byte's address on the cycle the
  // wr pulse asserts, while addrReg has already advanced to the next
  // byte ready for the following write.
  val wrAddrReg = Reg(UInt(23 bits)) init 0
  val wrDinReg  = Reg(Bits(8 bits)) init 0
  val wrPulse  = Reg(Bool()) init False
  val donePulse = Reg(Bool()) init False
  // Toggle reg: flips every time wrPulse asserts. Stable between flips,
  // which makes it safe to cross into sdramClockDomain via BufferCC.
  val wrToggleReg = Reg(Bool()) init False
  wrPulse   := False   // default — single-cycle
  donePulse := False

  // Capture incoming bytes into the single-entry holding register. If a
  // byte arrives while `hasByte` is still True (host overran the bridge),
  // the old byte is dropped. The host is responsible for pacing; per
  // artifact §6 any overrun manifests as a visible data corruption when
  // the asset is later read back. No silent error accounting in this
  // bounded Checkpoint B/C scope.
  when(io.byteValid) {
    latchedByte := io.byteIn
    hasByte     := True
  }

  val fsm = new StateMachine {
    val sIdle     = new State with EntryPoint
    val sActive   = new State
    val sDone     = new State

    sIdle.whenIsActive {
      when(io.headerValid) {
        addrReg   := io.addrInit
        bytesLeft := io.lenBytes
        hasByte   := False
        goto(sActive)
      }
    }

    sActive.whenIsActive {
      // Write one byte per cycle when all gates open. Capture the address
      // and data INTO the write-side regs and schedule the pulse; addrReg
      // advances in parallel so the following write targets the next byte.
      when(hasByte && io.allowUpload && !io.sdramBusy) {
        wrAddrReg := addrReg
        wrDinReg  := latchedByte
        wrPulse   := True
        wrToggleReg := !wrToggleReg       // flip on each write — CDC source
        hasByte   := False
        addrReg   := addrReg + 1          // wraps at 2^23 naturally
        bytesLeft := bytesLeft - 1
        when(bytesLeft === U(1, 17 bits)) {
          goto(sDone)
        }
      }
    }

    sDone.whenIsActive {
      donePulse := True
      goto(sIdle)
    }
  }

  io.sdramWr     := wrPulse
  io.sdramAddr   := wrAddrReg
  io.sdramDin    := wrDinReg
  io.uploadBusy  := !fsm.isActive(fsm.sIdle)
  io.uploadDone  := donePulse
  io.wrToggle    := wrToggleReg
}
