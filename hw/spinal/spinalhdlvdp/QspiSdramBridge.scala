package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Task 34 — Bridge between `QspiDecoder`'s SDRAM_WRITE payload stream and
  * the SDRAM controller's 8-bit write port.
  *
  * Contract (pixel clock domain):
  *   - `headerValid` pulses when QspiDecoder receives a valid SDRAM_WRITE
  *     header.  `addrInit` and `lenBytes` are sampled on that pulse.
  *   - `byteValid` pulses each time a payload byte arrives from the decoder.
  *     Bytes are buffered in a small FIFO and emitted as a sequence of
  *     per-byte write commands at `addrInit + n`.
  *   - `allowUpload` gates emission. The top wires this to `!activeVideo`
  *     so upload writes never collide with fetch reads.
  *
  * #11123 FIX 1 (CDC repair, BronzeGate #11120 Finding 1):
  *   The previous design crossed the write into the SDRAM clock domain as a
  *   toggle pulse PLUS quasi-static `addr`/`din` buses, and gated emission on
  *   a RAW cross-domain `busy`. That is not lossless: the regenerated pulse
  *   could sample stale/younger addr-data, two toggle flips could collapse,
  *   and using async `busy` as the acceptance handshake dropped writes. That
  *   produced partial bytes and writes landing at the wrong address.
  *
  *   This bridge now exposes a proper `wrCmd` Stream carrying `{addr,din}`
  *   ATOMICALLY in one payload. The top crosses it into the SDRAM domain via a
  *   `StreamFifoCC` (lossless, addr+data inseparable) and the SDRAM side pops
  *   one entry only when the controller can accept it. No toggle, no
  *   quasi-static buses, no raw cross-domain `busy` handshake.
  *
  * Status outputs:
  *   - `uploadBusy` high while a transaction is in flight.
  *   - `uploadDone` pulses one cycle after the last byte is emitted.
  *
  * Address wrap (CyanPeak #7680): `addr` is 23 bits and wraps at the 8 MB
  * boundary; the bridge does not bounds-check (host's responsibility).
  */
case class QspiSdramBridge() extends Component {
  val io = new Bundle {
    // From QspiDecoder
    val headerValid = in Bool()
    val addrInit    = in UInt(23 bits)
    val lenBytes    = in UInt(17 bits)   // 2 * lenWords, up to 128 KB
    val byteIn      = in Bits(8 bits)
    val byteValid   = in Bool()

    // Arbitration gate (high = upload allowed to emit)
    val allowUpload = in Bool()

    // To SDRAM domain (via StreamFifoCC at the top): one write command per
    // byte. payload = addr(23) ## din(8) = 31 bits, atomic.
    val wrCmd       = master Stream (Bits(31 bits))

    // Host-visible status
    val uploadBusy  = out Bool()
    val uploadDone  = out Bool()
  }

  val addrReg   = Reg(UInt(23 bits)) init 0
  val bytesLeft = Reg(UInt(17 bits)) init 0

  // Task 3 (CoralReef #9360): 16-byte FIFO absorbs the per-active-line backlog
  // (~13 bytes/line at 500 kHz QSPI) so no byte is dropped while allowUpload
  // is low; H-blank drains it faster than active video fills it.
  val byteFifo = StreamFifo(Bits(8 bits), depth = 16)
  byteFifo.io.push.valid   := io.byteValid
  byteFifo.io.push.payload := io.byteIn
  // Backpressure on byteValid is intentionally ignored (the decoder source
  // has no flow-control input); depth 16 covers the worst-case line backlog.

  val donePulse = Reg(Bool()) init False
  donePulse := False

  val fsm = new StateMachine {
    val sIdle   = new State with EntryPoint
    val sActive = new State
    val sDone   = new State

    sIdle.whenIsActive {
      when(io.headerValid) {
        addrReg   := io.addrInit
        bytesLeft := io.lenBytes
        goto(sActive)
      }
    }

    sActive.whenIsActive {
      // One write command per byte fires when the downstream CC FIFO has
      // space (wrCmd.ready) AND the blanking gate is open AND a byte is
      // buffered. addr+data leave together in one payload — cannot mis-pair.
      when(io.wrCmd.fire) {
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

  // wrCmd source: valid while emitting and a byte is available and gate open.
  // pop the byte FIFO exactly when the command is accepted (fire).
  io.wrCmd.valid   := fsm.isActive(fsm.sActive) && byteFifo.io.pop.valid && io.allowUpload
  io.wrCmd.payload := addrReg.asBits ## byteFifo.io.pop.payload
  byteFifo.io.pop.ready := io.wrCmd.fire

  io.uploadBusy := !fsm.isActive(fsm.sIdle)
  io.uploadDone := donePulse
}
