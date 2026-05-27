package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 47 — DMA-Style Transfer Primitive.
  *
  * Autonomous block-write engine for the Mode0 register bus. Lets the
  * host/Copper trigger up to 1024 consecutive writes with a single control
  * setup instead of N individual 16-bit bus transactions.
  *
  * Two modes:
  *   - FILL: constant `fillReg` value written to `dstReg..dstReg+lenReg`.
  *   - COPY: reads from a 64-word staging buffer (written via bus writes
  *     to `0x0B10..0x0B4F` ahead of time) and streams the first `lenReg+1`
  *     entries into `dstReg..dstReg+lenReg`.
  *
  * The engine runs in the pixel clock domain. Integrates with the VdpTop
  * effWrite path at **lowest priority**: if external (QSPI) or Copper
  * writes are active on a given cycle, the DMA pauses (counter holds,
  * `dmaWr` stays low) and resumes on the next free cycle.
  *
  * Completion signaling is a one-cycle `done` pulse suitable for OR-ing
  * into the Task 35 `evBus` so the `DMA_DONE` sticky bit latches via the
  * existing status/IRQ pipeline. A live `busy` output is exposed for
  * non-sticky read-back.
  *
  * Control register map (bus-decoded in VdpTop):
  *   0x0B00  DMA_DST   (15-bit destination start address)
  *   0x0B01  DMA_LEN   (10-bit length MINUS 1 → transfers = LEN+1)
  *   0x0B02  DMA_FILL  (16-bit fill value; FILL mode only)
  *   0x0B03  DMA_CTRL  bit0=go (self-clearing), bit1=mode (0=FILL, 1=COPY),
  *                     bit2=done_ack (self-clearing; clears busy-edge gating)
  *   0x0B10..0x0B4F   64 × 16-bit staging buffer (COPY-mode source)
  */
case class DmaEngine() extends Component {
  val ctrlBaseAddr    = 0x0B00
  val stagingBaseAddr = 0x0B10
  val stagingWords    = 64
  val stagingAddrBits = log2Up(stagingWords)  // 6

  val io = new Bundle {
    // Bus write port — fed by VdpTop's effWrite mux. Covers BOTH control
    // registers and the staging buffer. DmaEngine decodes internally.
    val busAddr = in  UInt(15 bits)
    val busData = in  Bits(16 bits)
    val busWr   = in  Bool()

    // True when any higher-priority master (external QSPI or Copper) is
    // driving effWrite this cycle. DMA holds its counter and emits
    // dmaWr=0 while this is high.
    val busBusy = in  Bool()

    // DMA-generated write port (merged into effWrite with lowest prio).
    val dmaAddr = out UInt(15 bits)
    val dmaData = out Bits(16 bits)
    val dmaWr   = out Bool()

    // Status.
    val busy = out Bool()    // live — non-sticky
    val done = out Bool()    // one-cycle pulse on transfer complete; OR into evBus
  }

  // Control registers.
  val dstReg  = Reg(UInt(15 bits)) init 0
  val lenReg  = Reg(UInt(10 bits)) init 0
  val fillReg = Reg(Bits(16 bits)) init 0
  val goReg   = Reg(Bool())        init False
  val modeReg = Reg(Bool())        init False   // 0 = FILL, 1 = COPY

  // Staging buffer: 64 × 16, LUT-RAM-inferred (<2 Kbit; Gowin keeps these
  // out of BSRAM when readAsync).
  val staging = Mem(Bits(16 bits), stagingWords)

  // ------------------------------------------------------------------
  // Bus decode: control-register writes vs staging-buffer writes.
  // ------------------------------------------------------------------
  val ctrlHit = io.busWr &&
    (io.busAddr >= U(ctrlBaseAddr, 15 bits)) &&
    (io.busAddr <  U(ctrlBaseAddr + 4, 15 bits))
  val stagingHit = io.busWr &&
    (io.busAddr >= U(stagingBaseAddr, 15 bits)) &&
    (io.busAddr <  U(stagingBaseAddr + stagingWords, 15 bits))

  val ctrlIdx = (io.busAddr - U(ctrlBaseAddr, 15 bits))(1 downto 0)
  when(ctrlHit) {
    switch(ctrlIdx) {
      is(U(0, 2 bits)) { dstReg  := io.busData(14 downto 0).asUInt }
      is(U(1, 2 bits)) { lenReg  := io.busData( 9 downto 0).asUInt }
      is(U(2, 2 bits)) { fillReg := io.busData }
      is(U(3, 2 bits)) {
        goReg   := io.busData(0)
        modeReg := io.busData(1)
        // bit 2 (done_ack) is reserved for future use; kept self-clearing.
      }
    }
  }

  // Staging buffer write port — drives Mem directly from bus.
  val stagingWrAddr = (io.busAddr - U(stagingBaseAddr, 15 bits))(stagingAddrBits - 1 downto 0)
  staging.write(address = stagingWrAddr, data = io.busData, enable = stagingHit)

  // ------------------------------------------------------------------
  // FSM: idle → running → (done-pulse) → idle.
  // ------------------------------------------------------------------
  val fsm = new Area {
    object State extends SpinalEnum {
      val IDLE, RUN, DONE = newElement()
    }
    import State._

    val state   = Reg(State()) init IDLE
    val counter = Reg(UInt(10 bits)) init 0

    // Read the staging buffer combinationally at the current counter so
    // the value is available the same cycle we emit dmaWr. Since
    // staging.readAsync returns the old value of the entry written this
    // cycle (Mem readAsync default), streaming consecutive entries is fine
    // — the host must have filled staging before triggering the DMA.
    // readAsync — AUDIT #10772: Class 3 (mid-frame, same-cycle FSM) — see
    // prior author comment above: stagingRead feeds io.dmaData on the same
    // cycle as io.dmaWr emission. readSync would mis-align the streamed
    // entry by 1 (dmaWr fires with stale data). Conversion requires the
    // standard lookahead-address pattern (issue counter+1 to readSync,
    // gate dmaWr by 1-cycle pipelined state). Per-DMA-burst, not per-pixel.
    val stagingRead = staging.readAsync(counter(stagingAddrBits - 1 downto 0))

    // Defaults.
    io.dmaWr   := False
    io.dmaAddr := (dstReg + counter).resize(15)
    io.dmaData := Mux(modeReg, stagingRead, fillReg)
    io.busy    := state === RUN
    io.done    := False

    switch(state) {
      is(IDLE) {
        counter := 0
        when(goReg) {
          goReg := False             // self-clearing
          state := RUN
        }
      }
      is(RUN) {
        // Emit a write only when the higher-priority buses are idle.
        when(!io.busBusy) {
          io.dmaWr := True
          when(counter === lenReg) {
            state   := DONE
          } otherwise {
            counter := counter + 1
          }
        }
      }
      is(DONE) {
        io.done := True              // one-cycle event pulse
        state   := IDLE
      }
    }
  }
}
