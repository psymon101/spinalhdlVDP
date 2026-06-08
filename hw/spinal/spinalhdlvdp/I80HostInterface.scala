package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** I80HostInterface — Intel 8080-style parallel host front-end (lane P21, #12010/
  * #12017). SKELETON (Checkpoint B prep): the register read/write path + async
  * strobe CDC are implemented; the SDRAM block-write path is a defined interface
  * with a TODO drain into the existing StreamFifoCC upload bridge.
  *
  * Contract (TopazCliff #12010, approved #12017):
  *   - CS  active-low: frames a transaction; D hi-Z when CS high.
  *   - WR  active-low: D is LATCHED on WR's RISING edge (with CS low).
  *   - RD  active-low: FPGA DRIVES D while (CS low && RD low); hi-Z otherwise.
  *   - DC  : 0 = command/address byte, 1 = data byte.
  *   - Reg write : addr-lo, addr-hi (DC=0) then data-lo, data-hi (DC=1) -> one
  *                 regBus write (15-bit addr + 16-bit data) — SAME downstream
  *                 contract as QspiDecoder, so VdpTop is unchanged below the mux.
  *   - Reg read  : addr (DC=0, 2 bytes) then two RD strobes (DC=1) return data
  *                 lo/hi from a pre-latched read register (combinational on D).
  *   - Block wr  : cmd 0x02 (DC=0), addr(3B)+len(2B) (DC=0), payload (DC=1) ->
  *                 StreamFifoCC into the SDRAM domain (reuse upload bridge).
  *
  * Runs in the host/pixel(regBus) clock domain. WR/RD/CS/DC are asynchronous to
  * it, so they are 2-FF synchronized and edge-detected here. dataWidth defaults
  * to 8 (i80-8); the data path is parameterized so D0-D15 is a one-line widen
  * later (TopazCliff's 16-bit-expansion note) without touching the FSM.
  */
case class I80HostInterface(dataWidth: Int = 8) extends Component {
  require(dataWidth == 8 || dataWidth == 16, "i80 data bus is 8 or 16 bits")
  val bytesPerBeat = dataWidth / 8

  val io = new Bundle {
    // ---- i80 pads (split tri-state: top-level wires dOut/dOutEn/dIn to an InOut buffer) ----
    val dIn    = in  Bits(dataWidth bits)
    val dOut   = out Bits(dataWidth bits)
    val dOutEn = out Bool()                 // drive D when high
    val cs     = in  Bool()                 // active low
    val wr     = in  Bool()                 // active low
    val rd     = in  Bool()                 // active low
    val dc     = in  Bool()                 // 0=cmd/addr, 1=data

    // ---- downstream: identical to QspiDecoder's output ----
    val regBus   = out(Mode0RegBus())
    val readData = in  Bits(16 bits)        // pre-latched value to return on reads
    val readAddr = out UInt(15 bits)        // reg-read target (opcode 0x01)
    val readReq  = out Bool()               // pulse when a read addr is latched

    // ---- SDRAM block-write payload (TODO: drain to StreamFifoCC) ----
    val blockWr       = master Stream(Bits(8 bits))
    val blockAddr     = out UInt(23 bits)
    val blockActive   = out Bool()
    val blockOverflow = out Bool()          // sticky: a payload beat dropped (FIFO !ready)
  }

  // ---------------------------------------------------------------------------
  // Async strobe CDC: 2-FF synchronize then edge-detect.
  // ---------------------------------------------------------------------------
  val csS = BufferCC(io.cs, True)
  val wrS = BufferCC(io.wr, True)
  val rdS = BufferCC(io.rd, True)
  val dcS = BufferCC(io.dc, False)
  val dInS = BufferCC(io.dIn, B(0, dataWidth bits))  // data settles long before the strobe edge

  val wrRise = wrS && !RegNext(wrS).init(True)        // WR deasserted -> latch write byte
  val rdRise = rdS && !RegNext(rdS).init(True)         // RD deasserted -> advance to next read byte
  val csActive = !csS

  // ---------------------------------------------------------------------------
  // Write-cycle accumulator (8-bit beats; 16-bit mode latches a full beat).
  // First DC=0 byte == 0x02 enters block-write mode; otherwise it is reg addr-lo.
  // (Cmd-vs-addr disambiguation is the one open contract item — see report.)
  // ---------------------------------------------------------------------------
  val addrReg  = Reg(UInt(15 bits)) init 0
  val dataReg  = Reg(Bits(16 bits)) init 0
  val regWrR   = Reg(Bool()) init False
  val readReqR = Reg(Bool()) init False
  val isRead   = Reg(Bool()) init False
  regWrR   := False
  readReqR := False

  // Block-write (opcode 0x02): 23-bit SDRAM byte address + 16-bit length captured
  // on DC=0 beats, then `length` payload bytes on DC=1 streamed into io.blockWr.
  val blkAddr     = Reg(UInt(23 bits)) init 0
  val blkLen      = Reg(UInt(16 bits)) init 0     // payload bytes remaining
  val blkWrValidR = Reg(Bool())  init False
  val blkWrDataR  = Reg(Bits(8 bits)) init 0
  val blkOverflow = Reg(Bool())  init False        // sticky: a payload beat dropped (FIFO !ready)
  blkWrValidR := False
  when(blkWrValidR && !io.blockWr.ready) { blkOverflow := True }

  // Opcode (TopazCliff #12022 / BronzeGate #12023): the FIRST DC=0 byte of EVERY
  // transaction is an opcode — 0x00=reg write, 0x01=reg read, 0x02=block write.
  // Eliminates the first-byte addr-vs-command collision; control pins stay at 4.
  val fsm = new StateMachine {
    val sOpcode = new State with EntryPoint
    val sAddrLo = new State
    val sAddrHi = new State
    val sDataLo = new State
    val sDataHi = new State
    val sBlkA0  = new State    // block addr byte 0 (LSB), DC=0
    val sBlkA1  = new State    // block addr byte 1
    val sBlkA2  = new State    // block addr byte 2 (7 bits -> 23-bit addr)
    val sBlkL0  = new State    // block length byte 0 (LSB), DC=0
    val sBlkL1  = new State    // block length byte 1
    val sBlkDat = new State    // payload bytes, DC=1

    always { when(!csActive) { goto(sOpcode) } }    // CS deassert ends/aborts txn

    sOpcode.whenIsActive {
      when(csActive && wrRise && !dcS) {
        switch(dInS(7 downto 0)) {
          is(0x00) { isRead := False; goto(sAddrLo) }   // register write
          is(0x01) { isRead := True;  goto(sAddrLo) }   // register read
          is(0x02) { goto(sBlkA0) }                      // SDRAM block write
          default  { }                                   // unknown opcode -> ignore
        }
      }
    }
    sAddrLo.whenIsActive {
      when(csActive && wrRise && !dcS) { addrReg(7 downto 0) := dInS(7 downto 0).asUInt; goto(sAddrHi) }
    }
    sAddrHi.whenIsActive {
      when(csActive && wrRise && !dcS) {
        addrReg(14 downto 8) := dInS(6 downto 0).asUInt
        when(isRead) { readReqR := True; goto(sOpcode) } // read: addr latched; RD strobes return data
          .otherwise { goto(sDataLo) }
      }
    }
    sDataLo.whenIsActive {
      when(csActive && wrRise && dcS) { dataReg(7 downto 0) := dInS(7 downto 0); goto(sDataHi) }
    }
    sDataHi.whenIsActive {
      when(csActive && wrRise && dcS) { dataReg(15 downto 8) := dInS(7 downto 0); regWrR := True; goto(sOpcode) }
    }
    // ---- block-write address (3B) + length (2B), all on DC=0 beats ----
    sBlkA0.whenIsActive { when(csActive && wrRise && !dcS) { blkAddr( 7 downto  0) := dInS(7 downto 0).asUInt; goto(sBlkA1) } }
    sBlkA1.whenIsActive { when(csActive && wrRise && !dcS) { blkAddr(15 downto  8) := dInS(7 downto 0).asUInt; goto(sBlkA2) } }
    sBlkA2.whenIsActive { when(csActive && wrRise && !dcS) { blkAddr(22 downto 16) := dInS(6 downto 0).asUInt; goto(sBlkL0) } }
    sBlkL0.whenIsActive { when(csActive && wrRise && !dcS) { blkLen ( 7 downto  0) := dInS(7 downto 0).asUInt; goto(sBlkL1) } }
    sBlkL1.whenIsActive {
      when(csActive && wrRise && !dcS) {
        val fullLen = (dInS(7 downto 0).asUInt ## blkLen(7 downto 0)).asUInt
        blkLen := fullLen
        when(fullLen === 0) { goto(sOpcode) }.otherwise { goto(sBlkDat) }   // zero-length -> no payload
      }
    }
    // ---- payload: each DC=1 WR beat pushes one byte into io.blockWr ----
    sBlkDat.whenIsActive {
      when(csActive && wrRise && dcS) {
        blkWrValidR := True
        blkWrDataR  := dInS(7 downto 0)
        blkLen      := blkLen - 1
        when(blkLen === 1) { goto(sOpcode) }   // last byte just consumed
      }
    }
  }

  io.regBus.addr   := addrReg
  io.regBus.data   := dataReg
  io.regBus.enable := regWrR
  io.readAddr      := addrReg
  io.readReq       := readReqR

  // ---------------------------------------------------------------------------
  // Read path: two RD strobes return readData lo then hi; D driven only while
  // (CS low && RD low && DC=data). Combinational from the pre-latched register.
  // ---------------------------------------------------------------------------
  val readHi = Reg(Bool()) init False
  when(!csActive) { readHi := False }
    .elsewhen(rdRise && dcS) { readHi := !readHi }   // first RD-low shows lo, then advance
  io.dOut   := (readHi ? io.readData(15 downto 8) | io.readData(7 downto 0)).resize(dataWidth)
  io.dOutEn := csActive && !rdS && dcS

  // ---- block-write payload stream (CP-B) ----
  io.blockWr.valid   := blkWrValidR
  io.blockWr.payload := blkWrDataR
  io.blockAddr       := blkAddr
  io.blockActive     := fsm.isActive(fsm.sBlkDat)
  io.blockOverflow   := blkOverflow
}
