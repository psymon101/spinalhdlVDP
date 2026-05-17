package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Per-scanline N-bitplane SDRAM fetcher (Mode0 Hardening — H-2,
  * domain-migrated per BronzeGate #9366 / Path A).
  *
  * Architecture: the read-issue FSM lives in `sdramCd` and consumes
  * `data_ready` / `dout32` natively in the SDRAM clock domain. Each
  * captured byte (planeIdx, readIdx, dout32) is pushed across a single
  * `StreamFifoCC` into the pixel domain, which pops and writes the
  * `planeMems`. The pixel-domain `slotWord` read port stays a fully
  * combinational `readAsync` from those Mems.
  *
  * This replaces the prior pixel-domain FSM that depended on a
  * toggle-based per-read CDC stack (#9344 / #9351 / #9362 / #9366) and
  * was bandwidth-limited / boundary-sensitive on hardware.
  *
  * @param sdramCd      SDRAM-side clock domain — same one used by the
  *                     SDRAM controller and the existing `BitmapRowFetch`
  *                     / `SdramTileAttributeFetch`.
  * @param planeCount   number of bitplanes to fetch (1..8).
  * @param planePixels  pixels per plane per row. Multiple of 32.
  * @param addrWidth    SDRAM address width.
  */
case class BitplaneRowFetch(
    sdramCd:      ClockDomain,
    planeCount:   Int = 5,
    planePixels:  Int = 320,
    addrWidth:    Int = 23
) extends Component {
  require(planeCount >= 1 && planeCount <= 8,
    s"planeCount must be 1..8, got $planeCount")
  require(planePixels >= 32 && planePixels % 32 == 0,
    s"planePixels must be a positive multiple of 32, got $planePixels")

  val readsPerPlane = planePixels / 32
  val planeIdxBits  = if (planeCount > 1) log2Up(planeCount) else 1
  val readIdxBits   = if (readsPerPlane > 1) log2Up(readsPerPlane) else 1
  val totalReads    = planeCount * readsPerPlane

  val io = new Bundle {
    val start          = in  Bool()                                // 1-cycle pulse, pixel domain
    val planeBaseAddr  = in  Vec(UInt(addrWidth bits), planeCount) // pixel domain (sampled at start)

    // SDRAM master interface — sdram clock domain (caller wires natively).
    val sdramRd        = out Bool()
    val sdramAddr      = out UInt(addrWidth bits)
    val sdramBusy      = in  Bool()
    val sdramDataReady = in  Bool()
    val sdramDout32    = in  Bits(32 bits)

    // Slot read port (pixel domain). The compositor drives `slotIdx`
    // (0..readsPerPlane-1) and consumes the per-plane 32-bit word at
    // that slot via `slotWord(p)` — a single `readAsync` per plane on
    // `planeMems(p)`. Keeping this as the sole readAsync port on each
    // planeMems is a synthesis-fragility invariant: per
    // PROJECT_PLAN/MODE0_T20_STRIP_ANALYSIS_CORALREEF.md and the
    // SpriteEvaluator/activeListMem fix (commit 40c0384), multiple
    // readAsync ports on a small Gowin-inferred Mem can flip the
    // inference from SSRAM/LUTRAM to distributed DFFs (Mem→FF
    // promotion). For BitplaneRowFetchSim the wide-row probe at
    // line 104 was migrated to step `slotIdx` and read `slotWord(p)`.
    val slotIdx        = in  UInt(readIdxBits bits)
    val slotWord       = out Vec(Bits(32 bits), planeCount)

    val rowReady       = out Bool()
    val busy           = out Bool()
  }

  // ---- Pixel-domain plane row storage ----
  // Task 57: SpinalHDL auto-generates ram_style="distributed" for small
  // Mems; this is sufficient for Gowin LUTRAM/SSRAM inference. Manual
  // syn_ramstyle causes EX0200 "Property set invalid" and is stripped.
  val planeMems = Seq.fill(planeCount)(Mem(Bits(32 bits), readsPerPlane))
  for (p <- 0 until planeCount) {
    io.slotWord(p) := planeMems(p).readAsync(io.slotIdx)
  }

  // ---- Pixel→sdram start handoff (toggle + edge in sdram domain) ----
  val startToggle = Reg(Bool()) init False
  when(io.start) { startToggle := !startToggle }

  // ---- CDC FIFO carrying captured 32-bit words (sdram → pixel) ----
  // Payload: planeIdx ## readIdx ## dout32. Depth covers a full row
  // worth of reads with margin.
  val fifoPayloadBits = planeIdxBits + readIdxBits + 32
  val fifoDepth       = {
    var d = 8
    while (d < totalReads) d *= 2
    d * 2
  }
  val rowFifo = StreamFifoCC(
    dataType  = Bits(fifoPayloadBits bits),
    depth     = fifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current
  )

  // ---- SDRAM-domain FSM ----
  val sdramArea = new ClockingArea(sdramCd) {
    val startToggleSync = BufferCC(startToggle, False)
    val startTogglePrev = RegNext(startToggleSync) init False
    val startEdge       = startToggleSync =/= startTogglePrev

    // Multi-bit base addresses are only consumed after startEdge fires;
    // BufferCC of each gives the FSM stable values by the time it
    // begins issuing reads (FSM takes >=1 sdramCd cycle to leave Idle).
    val planeBaseAddrSync = Vec.tabulate(planeCount) { p =>
      BufferCC(io.planeBaseAddr(p), U(0, addrWidth bits))
    }

    val planeIdx = Reg(UInt(planeIdxBits bits)) init 0
    val readIdx  = Reg(UInt(readIdxBits bits))  init 0

    object State extends SpinalEnum {
      val Idle, Issue, WaitData, Done = newElement()
    }
    val state = Reg(State()) init State.Idle

    val rdReg     = Reg(Bool())                 init False
    val addrReg   = Reg(UInt(addrWidth bits))   init 0
    val rowDoneToggle = Reg(Bool())             init False
    rdReg := False

    val pushPayload = Reg(Bits(fifoPayloadBits bits)) init 0
    val pushValid   = Reg(Bool())                     init False
    pushValid := False

    switch(state) {
      is(State.Idle) {
        when(startEdge) {
          planeIdx := 0
          readIdx  := 0
          state    := State.Issue
        }
      }
      is(State.Issue) {
        when(!io.sdramBusy) {
          rdReg   := True
          addrReg := planeBaseAddrSync(planeIdx) +
                     (readIdx.resize(addrWidth) |<< 2)
          state   := State.WaitData
        }
      }
      is(State.WaitData) {
        when(io.sdramDataReady) {
          // Push captured word into the CDC FIFO.
          pushPayload := planeIdx.asBits ## readIdx.asBits ## io.sdramDout32
          pushValid   := True

          when(readIdx === U(readsPerPlane - 1, readIdxBits bits)) {
            readIdx := 0
            when(planeIdx === U(planeCount - 1, planeIdxBits bits)) {
              planeIdx := 0
              state    := State.Done
            } otherwise {
              planeIdx := planeIdx + 1
              state    := State.Issue
            }
          } otherwise {
            readIdx := readIdx + 1
            state   := State.Issue
          }
        }
      }
      is(State.Done) {
        rowDoneToggle := !rowDoneToggle
        state         := State.Idle
      }
    }

    val busyLevel = state =/= State.Idle
  }

  // FIFO push from sdram-domain captures.
  rowFifo.io.push.valid   := sdramArea.pushValid
  rowFifo.io.push.payload := sdramArea.pushPayload
  // (push.ready ignored — depth >= totalReads ensures no backpressure)

  // SDRAM master IO is sdram-domain.
  io.sdramRd   := sdramArea.rdReg
  io.sdramAddr := sdramArea.addrReg

  // ---- Pixel-domain FIFO drain → planeMems writes ----
  val popPayload  = rowFifo.io.pop.payload
  val popPlaneIdx = popPayload(fifoPayloadBits - 1 downto fifoPayloadBits - planeIdxBits).asUInt
  val popReadIdx  = popPayload(31 + readIdxBits downto 32).asUInt
  val popDout32   = popPayload(31 downto 0)
  rowFifo.io.pop.ready := True
  val popFire = rowFifo.io.pop.fire

  for (p <- 0 until planeCount) {
    planeMems(p).write(
      address = popReadIdx,
      data    = popDout32,
      enable  = popFire && (popPlaneIdx === U(p, planeIdxBits bits))
    )
  }

  // ---- Row-ready: count drains to totalReads after each start pulse ----
  val popCounter = Reg(UInt(log2Up(totalReads + 1) bits)) init 0
  val rowReadyR  = Reg(Bool()) init False
  rowReadyR := False
  when(io.start) {
    popCounter := 0
  }
  when(popFire) {
    when(popCounter === U(totalReads - 1, popCounter.getWidth bits)) {
      popCounter := 0
      rowReadyR  := True
    } otherwise {
      popCounter := popCounter + 1
    }
  }
  io.rowReady := rowReadyR

  // ---- Busy: pixel-side level mirror of sdram FSM busy ----
  // Includes pop-drain phase: high while either FSM busy or FIFO not empty.
  val busySdramSync = BufferCC(sdramArea.busyLevel, False)
  io.busy := busySdramSync || rowFifo.io.pop.valid || (popCounter =/= 0)
}
