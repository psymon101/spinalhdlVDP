package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** VDP-SOFT-RESET-135 Stage 3 — occupied-region SDRAM zero-fill engine.
  *
  * Runs in `sdramClockDomain`. On `start` (a level, CDC-synced upstream in
  * TopTang) it walks the `regions` list and zeroes each ENABLED region
  * `[base, base + rowBytes·rowCount)` using row-accumulator addressing (no
  * runtime multiplier, per CyanPeak): outer loop over rows, inner loop writes
  * `rowBytes` zero bytes, `rowBase += rowBytes` each row. Disabled or empty
  * (rowCount==0 / rowBytes==0) regions are skipped.
  *
  * Refresh: a lightweight interleaved auto-refresh is MANDATORY (CyanPeak
  * #12589 / TopazCliff #12587) — a large host framebuffer (640×480 RGB565 dual
  * plane ≈ 1.2 MB) can exceed the 64 ms retention window, so cells written
  * early would decay before the sweep ends. `refreshInterval` cycles of active
  * fill between refresh commands (~1 per 15 µs at 40.5 MHz ⇒ ~600).
  *
  * Handshake: `done` is a level — asserted when every enabled region is
  * cleared, held until `start` deasserts (req/ack; TopTang's BufferCC crosses
  * it back to the pixel-domain controller). `active` is high from start-accept
  * through done-ack and drives TopTang's arbiter-bypass command mux.
  *
  * All writes are byte-wide `din=0`, issued only when the controller is ready
  * (`ctrlBusy`=0). Single write port — no controller change (write-burst stays
  * deferred).
  */
case class SdramFillRegion(addrWidth: Int) extends Bundle {
  val base     = UInt(addrWidth bits)
  val rowBytes = UInt(16 bits)   // stride: bytes per row
  val rowCount = UInt(11 bits)   // rows (covers 480 / future 720)
  val enable   = Bool()
}

case class SdramZeroFill(addrWidth: Int = 23, regionCount: Int = 5, refreshInterval: Int = 600)
    extends Component {
  require(regionCount >= 1)
  val io = new Bundle {
    val start    = in  Bool()                              // level (CDC-synced)
    val done     = out Bool()                              // level
    val regions  = in  Vec(SdramFillRegion(addrWidth), regionCount)
    val ctrlBusy = in  Bool()                              // 0 = controller ready
    val wr       = out Bool()
    val refresh  = out Bool()
    val addr     = out UInt(addrWidth bits)
    val din      = out Bits(8 bits)
    val active   = out Bool()                              // drives TopTang ctrl mux
  }

  val idxBits  = log2Up(regionCount)
  val regIdx   = Reg(UInt(log2Up(regionCount + 1) bits)) init 0
  val rowBase  = Reg(UInt(addrWidth bits)) init 0
  val col      = Reg(UInt(16 bits)) init 0
  val rowsLeft = Reg(UInt(11 bits)) init 0
  val doneReg  = Reg(Bool()) init False

  // Real-time refresh pacing: count every active cycle (not just writes) so the
  // ~15 µs cadence holds regardless of controller stalls.
  val refreshCnt = Reg(UInt(log2Up(refreshInterval + 1) bits)) init 0
  val refreshDue = refreshCnt === U(refreshInterval, refreshCnt.getWidth bits)

  io.wr      := False
  io.refresh := False
  io.addr    := (rowBase + col.resize(addrWidth))
  io.din     := 0

  val fsm = new StateMachine {
    val sIdle       = new State with EntryPoint
    val sNextRegion = new State
    val sRow        = new State
    val sDone       = new State

    // active whenever the engine owns the bus (start-accept → done-ack).
    io.active := !isActive(sIdle)

    sIdle.whenIsActive {
      doneReg    := False
      refreshCnt := 0
      when(io.start) {
        regIdx := 0
        goto(sNextRegion)
      }
    }

    sNextRegion.whenIsActive {
      when(regIdx === U(regionCount, regIdx.getWidth bits)) {
        goto(sDone)
      } otherwise {
        val r = io.regions(regIdx.resize(idxBits))
        when(r.enable && (r.rowCount =/= 0) && (r.rowBytes =/= 0)) {
          rowBase  := r.base
          rowsLeft := r.rowCount
          col      := 0
          goto(sRow)
        } otherwise {
          regIdx := regIdx + 1               // skip disabled / empty region
        }
      }
    }

    sRow.whenIsActive {
      val r = io.regions(regIdx.resize(idxBits))
      // count active cycles toward the refresh cadence
      when(!refreshDue) { refreshCnt := refreshCnt + 1 }
      when(!io.ctrlBusy) {
        when(refreshDue) {
          io.refresh := True                 // interleaved auto-refresh ...
          refreshCnt := 0                     // ... write deferred one cycle
        } otherwise {
          io.wr := True                       // zero-write at rowBase+col
          when(col === (r.rowBytes - 1)) {
            col := 0
            when(rowsLeft === 1) {
              regIdx := regIdx + 1            // region complete → next
              goto(sNextRegion)
            } otherwise {
              rowsLeft := rowsLeft - 1
              rowBase  := rowBase + r.rowBytes.resize(addrWidth)
            }
          } otherwise {
            col := col + 1
          }
        }
      }
    }

    sDone.whenIsActive {
      doneReg := True
      when(!io.start) { goto(sIdle) }         // req/ack: release on start deassert
    }
  }

  io.done := doneReg
}
