package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 49 — Blitter-Class Block Transfer Engine.
  *
  * Rectangular block-transfer engine for the Mode0 register bus. Extends the
  * Task 47 linear DMA into 2-D geometry: width, height, and independent
  * source/destination strides let a single setup + go transaction clear a
  * tilemap region, copy a rectangular sprite pattern, or update a
  * scroll-table band.
  *
  * Modes (CTRL bit[2:1]):
  *   - 0 = RECT_FILL : constant `BLIT_FILL_VAL` written to width*height cells
  *                     stepping dstStride per row.
  *   - 1 = RECT_COPY : read source RAM at (srcBase + row*srcStride + col) →
  *                     write destination (dstBase + row*dstStride + col).
  *   - 2 = LINE_FILL : convenience linear fill (HEIGHT is ignored internally;
  *                     behaves as RECT_FILL with HEIGHT=0).
  *
  * Integrates with VdpTop's effWrite path at the **lowest priority** (below
  * external > copper > DMA). When any higher-priority master drives the bus,
  * the blitter holds its counters and emits blitWr=0. Stride math uses
  * accumulators — no runtime multiplier — per CyanPeak #8238.
  *
  * Control register map (bus-decoded in VdpTop):
  *   0x0C00  BLIT_CTRL       {_, done_ack[3], mode[2:1], go[0]}
  *                            go/done_ack are self-clearing.
  *   0x0C01  BLIT_WIDTH      (10-bit width  minus 1 → cols = WIDTH  + 1)
  *   0x0C02  BLIT_HEIGHT     (10-bit height minus 1 → rows = HEIGHT + 1)
  *   0x0C03  BLIT_DST_ADDR   (15-bit destination start address)
  *   0x0C04  BLIT_DST_STRIDE (15-bit destination row increment, words)
  *   0x0C05  BLIT_SRC_ADDR   ( 9-bit source RAM start offset, COPY only)
  *   0x0C06  BLIT_SRC_STRIDE ( 9-bit source RAM row increment, COPY only)
  *   0x0C07  BLIT_FILL_VAL   (16-bit fill constant, FILL modes only)
  *   0x0C10..0x0D0F          512 × 16-bit source/store RAM
  */
case class BlitterEngine() extends Component {
  val ctrlBaseAddr   = 0x0C00
  val srcRamBaseAddr = 0x0C10
  val srcRamWords    = 512
  val srcRamAddrBits = log2Up(srcRamWords)  // 9

  val io = new Bundle {
    // Bus write port — fed by VdpTop's effWrite mux. Covers BOTH control
    // registers and the source RAM. BlitterEngine decodes internally.
    val busAddr = in  UInt(15 bits)
    val busData = in  Bits(16 bits)
    val busWr   = in  Bool()

    // True when any higher-priority master (external / copper / DMA) is
    // driving effWrite this cycle. Blitter holds its counters and emits
    // blitWr=0 while this is high.
    val busBusy = in  Bool()

    // Blitter-generated write port (merged into effWrite at lowest prio).
    val blitAddr = out UInt(15 bits)
    val blitData = out Bits(16 bits)
    val blitWr   = out Bool()

    // Status.
    val busy = out Bool()    // live — non-sticky
    val done = out Bool()    // one-cycle pulse on transfer complete

    // VDP-SOFT-RESET-135 #2d: soft-reset clear of the blitter source RAM.
    // Defaulted so other instantiations are unaffected; driven by VdpTop sweep.
    val softClear     = in Bool() default False
    val softClearAddr = in UInt(14 bits) default U(0, 14 bits)
  }

  // Mode encoding.
  val MODE_RECT_FILL = B"00"
  val MODE_RECT_COPY = B"01"
  val MODE_LINE_FILL = B"10"

  // Control registers.
  val widthReg     = Reg(UInt(10 bits)) init 0
  val heightReg    = Reg(UInt(10 bits)) init 0
  val dstAddrReg   = Reg(UInt(15 bits)) init 0
  val dstStrideReg = Reg(UInt(15 bits)) init 0
  val srcAddrReg   = Reg(UInt(srcRamAddrBits bits)) init 0
  val srcStrideReg = Reg(UInt(srcRamAddrBits bits)) init 0
  val fillReg      = Reg(Bits(16 bits)) init 0
  val goReg        = Reg(Bool())        init False
  val modeReg      = Reg(Bits(2 bits))  init B"00"

  // Source/store RAM: 512 × 16, host-writable via bus, blitter-readable.
  // CLS optimization (BronzeGate #10445): readSync + ram_style="block" so
  // this 8 Kbit RAM infers a Gowin BSRAM block instead of CLS-resident
  // distributed RAM (SSRAM). The blitter read is lookahead-addressed in
  // the FSM below to hide the 1-cycle readSync latency, preserving the
  // 1 word/cycle COPY throughput.
  val srcRam = Mem(Bits(16 bits), srcRamWords)
  srcRam.addAttribute("ram_style", "block")

  // ------------------------------------------------------------------
  // Bus decode: control-register writes vs source-RAM writes.
  // ------------------------------------------------------------------
  val ctrlHit = io.busWr &&
    (io.busAddr >= U(ctrlBaseAddr, 15 bits)) &&
    (io.busAddr <  U(ctrlBaseAddr + 8, 15 bits))
  val srcRamHit = io.busWr &&
    (io.busAddr >= U(srcRamBaseAddr, 15 bits)) &&
    (io.busAddr <  U(srcRamBaseAddr + srcRamWords, 15 bits))

  val ctrlIdx = (io.busAddr - U(ctrlBaseAddr, 15 bits))(2 downto 0)
  when(ctrlHit) {
    switch(ctrlIdx) {
      is(U(0, 3 bits)) {
        goReg   := io.busData(0)
        modeReg := io.busData(2 downto 1)
        // bit 3 (done_ack) is reserved / self-clearing; kept for host polling.
      }
      is(U(1, 3 bits)) { widthReg     := io.busData(9 downto 0).asUInt }
      is(U(2, 3 bits)) { heightReg    := io.busData(9 downto 0).asUInt }
      is(U(3, 3 bits)) { dstAddrReg   := io.busData(14 downto 0).asUInt }
      is(U(4, 3 bits)) { dstStrideReg := io.busData(14 downto 0).asUInt }
      is(U(5, 3 bits)) { srcAddrReg   := io.busData(srcRamAddrBits - 1 downto 0).asUInt }
      is(U(6, 3 bits)) { srcStrideReg := io.busData(srcRamAddrBits - 1 downto 0).asUInt }
      is(U(7, 3 bits)) { fillReg      := io.busData }
    }
  }

  // Source-RAM write port — driven by host bus writes to 0x0C10..0x0D0F.
  val srcRamWrAddr = (io.busAddr - U(srcRamBaseAddr, 15 bits))(srcRamAddrBits - 1 downto 0)
  // VDP-SOFT-RESET-135 #2d: source-RAM write muxed to the zero-sweep.
  val srcRamClearWr = io.softClear && (io.softClearAddr < U(srcRamWords, 14 bits))
  srcRam.write(
    address = Mux(io.softClear, io.softClearAddr.resize(srcRamAddrBits), srcRamWrAddr),
    data    = Mux(io.softClear, B(0, 16 bits), io.busData),
    enable  = srcRamHit || srcRamClearWr
  )

  // ------------------------------------------------------------------
  // FSM: idle → run → done → idle.
  //
  // Accumulator addressing: dstRowBase / srcRowBase hold the per-row
  // base address. colCounter adds onto that for the active word. On row
  // advance, the bases += stride. No runtime multiplier.
  // ------------------------------------------------------------------
  val fsm = new Area {
    object State extends SpinalEnum {
      val IDLE, RUN, DONE = newElement()
    }
    import State._

    val state      = Reg(State()) init IDLE
    val colCounter = Reg(UInt(10 bits)) init 0
    val rowCounter = Reg(UInt(10 bits)) init 0
    val dstRowBase = Reg(UInt(15 bits)) init 0
    val srcRowBase = Reg(UInt(srcRamAddrBits bits)) init 0

    // LINE_FILL forces height=0 semantics: only one row is emitted. We do
    // this by capturing an effective height at kickoff.
    val effHeight = Reg(UInt(10 bits)) init 0
    val isCopy    = Reg(Bool()) init False  // MODE_RECT_COPY -> srcRam read

    // Current addresses. Adder only — no multiplier.
    val dstCur = dstRowBase + colCounter.resize(15)
    val srcCur = srcRowBase + colCounter.resize(srcRamAddrBits)

    // Source RAM read (COPY mode). srcRam is readSync (BSRAM-mapped), so
    // its output is registered — 1 cycle after the address. To keep the
    // RUN loop at 1 word/cycle, present the address the FSM will consume
    // NEXT cycle (lookahead): then the registered `srcRead` lines up with
    // the FSM's current column. Default holds the current address, which
    // covers the busBusy stall, IDLE, and DONE.
    val srcReadAddr = UInt(srcRamAddrBits bits)
    srcReadAddr := srcCur
    val srcRead = srcRam.readSync(srcReadAddr)

    // Defaults.
    io.blitWr   := False
    io.blitAddr := dstCur
    io.blitData := Mux(isCopy, srcRead, fillReg)
    io.busy     := state === RUN
    io.done     := False

    switch(state) {
      is(IDLE) {
        colCounter := 0
        rowCounter := 0
        when(goReg) {
          goReg      := False                 // self-clearing
          state      := RUN
          dstRowBase := dstAddrReg
          srcRowBase := srcAddrReg
          // Lookahead: prime the readSync with the first column's address
          // (col 0 of row 0) so srcRead is valid on the first RUN cycle.
          srcReadAddr := srcAddrReg
          // LINE_FILL = mode 2: treat HEIGHT as 0 so we run exactly one row.
          effHeight  := (modeReg === MODE_LINE_FILL) ? U(0, 10 bits) | heightReg
          isCopy     := (modeReg === MODE_RECT_COPY)
        }
      }
      is(RUN) {
        when(!io.busBusy) {
          io.blitWr := True
          when(colCounter === widthReg) {
            // End of row.
            colCounter := 0
            when(rowCounter === effHeight) {
              state := DONE
            } otherwise {
              rowCounter := rowCounter + 1
              dstRowBase := dstRowBase + dstStrideReg
              srcRowBase := srcRowBase + srcStrideReg
              // Lookahead: next column is col 0 of the next row.
              srcReadAddr := srcRowBase + srcStrideReg
            }
          } otherwise {
            colCounter := colCounter + 1
            // Lookahead: next column is colCounter + 1 of the same row.
            srcReadAddr := srcRowBase + (colCounter + 1).resize(srcRamAddrBits)
          }
        }
      }
      is(DONE) {
        io.done := True                       // one-cycle event pulse
        state   := IDLE
      }
    }
  }
}
