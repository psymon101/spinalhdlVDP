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
case class QspiSdramBridge(stallTimeout: Int = 65536) extends Component {
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
    // CP-A1 (Phase A #11411): sticky watchdog-abort flag. Set when a transaction
    // is aborted because it could not make progress within `stallTimeout` cycles
    // (wrCmd.ready stalled, or fewer payload bytes than LEN arrived). The top
    // routes this to a host-visible status bit so the host can detect + resync.
    val uploadError = out Bool()
    // CP-A4 (Phase A #11443, CyanPeak): sticky ingress-FIFO overflow flag. Set when a
    // payload byte is pushed while byteFifo is full (push.valid && !push.ready) — i.e.
    // the host out-paces the arbiter's drain (the F3/F5 transport-ceiling symptom).
    // Routed to READ_STATUS sel=6 bit3 for host diagnosis. byteValid has no flow
    // control, so this is the only signal that a byte was dropped at ingress.
    val fifoOverflow = out Bool()
    // DIAG #14260: temporary debug taps for sim integration diagnosis.
    val dbgHdrPushed  = out Bool()
    val dbgBytePushed = out Bool()
    val dbgFsmState   = out Bits(3 bits)
  }

  val addrReg   = Reg(UInt(23 bits)) init 0
  val bytesLeft = Reg(UInt(17 bits)) init 0

  // Task 3 (CoralReef #9360): FIFO absorbs the per-active-line backlog so no byte is
  // dropped while the drain is gated. Originally 16 (sized for ESP8266 ~500 kHz,
  // ~13 bytes/line). #11246 F5b (CyanPeak #11266 step 3): UploadSeamSim swept depth
  // at the 4 MHz host cap with realistic per-line fetch read bursts — 16 dropped
  // ~320/512, 64 dropped 13, depth 128 -> ZERO drops (postFix). A full line-fetch
  // read burst starves the drain longer than 64 can hold, so 128 is required to
  // absorb the worst-case backlog and fully drain in the inter-burst idle. Power-of-
  // two (GT-022). NOTE: backpressure on byteValid is still ignored (the QSPI decoder
  // has no flow-control input), so the host MUST stay <= 4 MHz (F3); 128 covers a
  // bounded upload at that rate, not an unbounded stream during continuous rendering.
  val byteFifo = StreamFifo(Bits(8 bits), depth = 128)
  byteFifo.io.push.valid   := io.byteValid
  byteFifo.io.push.payload := io.byteIn

  // CP-A4 (#11443): sticky ingress-overflow detector. A byte pushed while the FIFO is
  // full is silently lost (byteValid has no flow control); latch it so the host can
  // read sel=6 bit3 and know the transport out-paced the drain.
  val fifoOverflow = Reg(Bool()) init False
  when(byteFifo.io.push.valid && !byteFifo.io.push.ready) { fifoOverflow := True }
  // CP-A5 (#11470): also latch downstream (uploadCc) backpressure — a wrCmd that can't
  // push because the CDC FIFO is full is the tile[31] CC-overflow signature. Makes a
  // CC-full event diagnosable on sel=6 bit3 (was invisible: only byteFifo was watched).
  when(io.wrCmd.valid && !io.wrCmd.ready) { fifoOverflow := True }

  // #11321 (BronzeGate Finding 1 / TopazCliff): a NEW SDRAM_WRITE header arriving
  // while the bridge was still draining the previous transaction got DROPPED
  // (sIdle-only acceptance), so the next transaction's bytes continued at the OLD
  // addrReg -> back-to-back uploads corrupted (isolated writes passed). Queue headers
  // in a small FIFO so every transaction re-anchors addrReg/bytesLeft at its own
  // boundary. The byte stream stays consistent because the decoder now forwards
  // exactly LEN bytes per header (#11308). payload = addrInit(23) ## lenBytes(17).
  val hdrFifo = StreamFifo(Bits(40 bits), depth = 8)
  hdrFifo.io.push.valid   := io.headerValid
  hdrFifo.io.push.payload := io.addrInit.asBits ## io.lenBytes.asBits
  hdrFifo.io.pop.ready    := False

  val donePulse = Reg(Bool()) init False
  donePulse := False

  // CP-A1 watchdog state. `stallCnt` counts cycles in sActive WITHOUT a committed
  // byte (reset on every wrCmd.fire); a transaction making progress never trips it.
  // Only a genuine wedge (ready stuck low, or fewer bytes than LEN ever arriving)
  // lets it reach `stallTimeout` -> ABORT to sFlush. `uploadError` is sticky until
  // the host clears it (or POR) so a transient wedge is observable after recovery.
  val stallCnt    = Reg(UInt(log2Up(stallTimeout) bits)) init 0
  val uploadError = Reg(Bool()) init False

  val fsm = new StateMachine {
    val sIdle   = new State with EntryPoint
    val sActive = new State
    val sFlush  = new State
    val sDone   = new State

    sIdle.whenIsActive {
      stallCnt := 0
      // Pop the next queued header (back-to-back safe) and re-anchor this
      // transaction's address + byte budget.
      when(hdrFifo.io.pop.valid) {
        addrReg   := hdrFifo.io.pop.payload(39 downto 17).asUInt
        bytesLeft := hdrFifo.io.pop.payload(16 downto 0).asUInt
        hdrFifo.io.pop.ready := True
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
        stallCnt  := 0                    // progress — reset the watchdog
        when(bytesLeft === U(1, 17 bits)) {
          goto(sDone)
        }
      } otherwise {
        // No byte committed this cycle — advance the stall watchdog. If it
        // saturates, the transaction is wedged: abort it instead of hanging
        // uploadBusy forever (QspiWriteStatusReproSim CASE B/B2).
        stallCnt := stallCnt + 1
        when(stallCnt === U(stallTimeout - 1, log2Up(stallTimeout) bits)) {
          uploadError := True
          goto(sFlush)
        }
      }
    }

    sFlush.whenIsActive {
      // Drop the orphaned transaction: drain whatever bytes remain buffered so a
      // partial/wedged frame cannot mis-pair into the next transaction, then
      // return to idle. Host sees uploadError on the next status poll and resyncs.
      when(!byteFifo.io.pop.valid) {
        goto(sIdle)
      }
    }

    sDone.whenIsActive {
      donePulse := True
      goto(sIdle)
    }
  }

  // wrCmd source: valid while emitting and a byte is available and gate open.
  // pop the byte FIFO when the command is accepted (fire) OR when flushing an
  // aborted transaction (drain to clear, no write emitted).
  io.wrCmd.valid   := fsm.isActive(fsm.sActive) && byteFifo.io.pop.valid && io.allowUpload
  io.wrCmd.payload := addrReg.asBits ## byteFifo.io.pop.payload
  byteFifo.io.pop.ready := io.wrCmd.fire || fsm.isActive(fsm.sFlush)

  io.uploadBusy := !fsm.isActive(fsm.sIdle) || hdrFifo.io.pop.valid  // active OR headers queued
  io.uploadDone := donePulse
  io.uploadError := uploadError
  io.fifoOverflow := fifoOverflow

  io.dbgHdrPushed  := RegNext(io.headerValid && hdrFifo.io.push.ready, False)
  io.dbgBytePushed := RegNext(io.byteValid && byteFifo.io.push.ready, False)
  io.dbgFsmState   := Cat(
    fsm.isActive(fsm.sIdle),
    fsm.isActive(fsm.sActive),
    fsm.isActive(fsm.sDone)
  )
}
