package spinalhdlvdp

import spinal.core._
import spinal.lib._
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
  // Task 3 host-upload repair (CoralReef #9360, CyanPeak audit PASS #9362):
  // replace the prior single-byte latch (`latchedByte` + `hasByte`) with a
  // 16-byte StreamFifo. The single-byte latch silently dropped bytes any
  // time `allowUpload` was low (active video) — at 500 kHz QSPI, ~13 bytes
  // arrived per active line and only 1 could be held. The 16-byte FIFO
  // absorbs the per-line backlog with margin; H-blank drains it faster
  // than active video can fill it.
  val byteFifo = StreamFifo(Bits(8 bits), depth = 16)

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

  // Push every incoming byte into the FIFO. Backpressure is intentionally
  // ignored on `byteValid` (the QSPI decoder source has no flow-control
  // input); depth=16 absorbs the worst-case ~13 bytes/active-line backlog
  // with margin. If a sustained-overflow scenario is ever introduced,
  // expose `byteFifo.io.push.ready` here.
  byteFifo.io.push.valid   := io.byteValid
  byteFifo.io.push.payload := io.byteIn

  // Pop one byte per cycle when all gates open. canWrite is the unified
  // pop+ready predicate: data available, blanking window open, controller
  // not busy. The FSM observes `canWrite` to advance its byte counter.
  val canWrite = byteFifo.io.pop.valid && io.allowUpload && !io.sdramBusy
  byteFifo.io.pop.ready := canWrite

  val fsm = new StateMachine {
    val sIdle     = new State with EntryPoint
    val sActive   = new State
    val sDone     = new State

    sIdle.whenIsActive {
      when(io.headerValid) {
        addrReg   := io.addrInit
        bytesLeft := io.lenBytes
        goto(sActive)
      }
    }

    sActive.whenIsActive {
      // Write one byte per cycle when all gates open. Capture the address
      // and data INTO the write-side regs and schedule the pulse; addrReg
      // advances in parallel so the following write targets the next byte.
      when(canWrite) {
        wrAddrReg := addrReg
        wrDinReg  := byteFifo.io.pop.payload
        wrPulse   := True
        wrToggleReg := !wrToggleReg       // flip on each write — CDC source
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
