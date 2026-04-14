package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** R5 host interface — indirect register access + 16-entry dual-clock FIFO
  * with safe-boundary application at `hCounter === 0` (or during vblank).
  *
  * Host side (runs in `hostCd`):
  *   - Five shadow registers selected by 3-bit `hostAddr`:
  *       0 VDP_ADDR   (16-bit, bit 15 reserved)
  *       1 VDP_DATA   (write triggers FIFO enqueue + auto-increment VDP_ADDR)
  *       2 VDP_INC    (8-bit, default = 1)
  *       3 VDP_STATUS (read-only: {fifo_full, fifo_empty, vblank, line[9:2]})
  *       4 VDP_CTRL   ({irq_enable, copper_enable, flush_fifo})
  *
  * Pixel side (current clock domain):
  *   - CommandParser drains the FIFO one entry per cycle while the safe
  *     boundary is open (`hCounter === 0` OR `vCounter >= vActive`).
  *   - Each drained entry is emitted as a single-cycle pulse on
  *     `regAddr`/`regData`/`regWr`.
  *
  * Every existing project test-harness defaults `hostWr=False` so the unit
  * is quiet when not exercised. This keeps R5 additive on top of R4.1.
  */
case class HostInterface(hostCd: ClockDomain) extends Component {
  val io = new Bundle {
    // Host clock domain
    val hostAddr  = in  UInt(3 bits)
    val hostData  = in  Bits(16 bits)
    val hostWr    = in  Bool()
    val hostRd    = in  Bool()
    val hostRdata = out Bits(16 bits)

    // Pixel clock domain
    val hCounter  = in  UInt(10 bits)
    val vCounter  = in  UInt(10 bits)
    val vActive   = in  UInt(10 bits)

    // Unified register-write output (pixel domain)
    val regAddr   = out UInt(15 bits)
    val regData   = out Bits(16 bits)
    val regWr     = out Bool()

    // Observability (pixel domain)
    val copperEnable = out Bool()
  }

  // --------------------------------------------------------------------------
  // Dual-clock FIFO (payload = {addr[14:0], data[15:0]} = 31 bits)
  // --------------------------------------------------------------------------
  val fifo = StreamFifoCC(
    dataType  = Bits(31 bits),
    depth     = 16,
    pushClock = hostCd,
    popClock  = ClockDomain.current
  )

  // --------------------------------------------------------------------------
  // Pixel-domain status signals (CDC'd into host side)
  // --------------------------------------------------------------------------
  val inVblankPx   = io.vCounter >= io.vActive
  val linePxBits   = io.vCounter(9 downto 2).asBits  // 8 bits
  val ctrlFromHost = Bits(8 bits)  // filled below via BufferCC

  // --------------------------------------------------------------------------
  // Host-domain logic
  // --------------------------------------------------------------------------
  val hostArea = new ClockingArea(hostCd) {
    val vdpAddrReg = Reg(UInt(16 bits)) init 0
    val vdpIncReg  = Reg(UInt(8 bits))  init 1
    val vdpCtrlReg = Reg(Bits(8 bits))  init 0

    val hostAddrD = io.hostAddr
    val hostWrD   = io.hostWr

    // VDP_ADDR write
    when(hostWrD && hostAddrD === U(0, 3 bits)) {
      vdpAddrReg := io.hostData.asUInt
    }
    // VDP_INC write
    when(hostWrD && hostAddrD === U(2, 3 bits)) {
      vdpIncReg := io.hostData(7 downto 0).asUInt
    }
    // VDP_CTRL write
    when(hostWrD && hostAddrD === U(4, 3 bits)) {
      vdpCtrlReg := io.hostData(7 downto 0)
    }

    // VDP_DATA write → FIFO push + auto-increment VDP_ADDR
    val dataWrite = hostWrD && hostAddrD === U(1, 3 bits) && fifo.io.push.ready
    val pushValid   = RegNext(dataWrite) init False
    val pushPayload = RegNext((vdpAddrReg(14 downto 0) ## io.hostData).asBits) init 0
    fifo.io.push.valid   := pushValid
    fifo.io.push.payload := pushPayload
    when(dataWrite) {
      vdpAddrReg := (vdpAddrReg + vdpIncReg.resize(16)).resize(16)
    }

    // VDP_STATUS (CDC from pixel side)
    val fifoFullHost  = BufferCC(!fifo.io.push.ready, init = False)
    val fifoEmptyHost = BufferCC(!fifo.io.pop.valid,  init = True)
    val vblankHost    = BufferCC(inVblankPx, init = False)
    val lineHost      = BufferCC(linePxBits, init = B(0, 8 bits))
    val vdpStatusReg  = (lineHost(5 downto 0) ## vblankHost ## fifoEmptyHost ## fifoFullHost)
      .resize(16).asBits
      // Layout chosen so low bits are the most volatile (fifo flags), upper
      // bits are line scan position. Host observes these live via hostRd.

    // Host read mux
    val rdata = Bits(16 bits)
    rdata := B(0, 16 bits)
    switch(hostAddrD) {
      is(U(0, 3 bits)) { rdata := vdpAddrReg.asBits }
      is(U(2, 3 bits)) { rdata := vdpIncReg.resize(16).asBits }
      is(U(3, 3 bits)) { rdata := vdpStatusReg }
      is(U(4, 3 bits)) { rdata := vdpCtrlReg.resize(16) }
      default          { rdata := B(0, 16 bits) }
    }
    io.hostRdata := rdata
  }

  // Bring VDP_CTRL into the pixel domain for copperEnable observability
  ctrlFromHost := BufferCC(hostArea.vdpCtrlReg, init = B(0, 8 bits))
  io.copperEnable := ctrlFromHost(1)  // bit 1 = copper_enable

  // --------------------------------------------------------------------------
  // Pixel-domain CommandParser
  // --------------------------------------------------------------------------
  val atLineStart = io.hCounter === U(0, 10 bits)
  val drainOpen   = atLineStart || inVblankPx

  fifo.io.pop.ready := drainOpen
  io.regWr   := fifo.io.pop.fire
  io.regAddr := fifo.io.pop.payload(30 downto 16).asUInt
  io.regData := fifo.io.pop.payload(15 downto 0)
}
