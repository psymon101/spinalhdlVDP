package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 40 — C64 demo animator for Scenario 20 (two-bar raster split +
  * bouncing sprites).
  *
  * Wraps `C64Adapter` with a small bootstrap+per-frame state machine that
  * emits VIC-II-style register writes. The animator is the adapter's
  * only "host": no QSPI or Copper drives the C64 side.
  *
  * Demo program:
  *   - On boot and on every vsync, drive one bootstrap write per cycle:
  *     $D012 = 240       (raster trigger line, mid-screen)
  *     $D01A = 0x01      (IRQ mask bit 0 → rasterTriggerEnable)
  *     $D015 = 0x03      (sprite 0/1 enable)
  *     $D011 = 0x10      (DEN=1 → LAYER_ENABLE bit 0 set via bus)
  *     $D000 = sprite0X  (updated per vsync for bounce)
  *     $D001 = sprite0Y
  *     $D002 = sprite1X
  *     $D003 = sprite1Y
  *   - On `rasterTriggerPulse` rising, write $D011 = 0x00 (DEN=0) —
  *     turns L0 dark for the lower bar of the screen, producing a
  *     visible two-bar split. $D019 ack is pulsed immediately after.
  *   - The animator never touches anything the substrate does not already
  *     expose (no new primitives).
  *
  * Direct outputs (raster trigger, sprite 0/1 legacy IO) pass through
  * from `C64Adapter`. The bus output is the adapter's bus output.
  */
case class C64DemoAnimator() extends Component {
  val io = new Bundle {
    val vsyncRising        = in  Bool()
    val rasterTriggerPulse = in  Bool()

    // Mode0 register bus (feeds a RegBusArbiter master slot).
    val busAddr = out UInt(15 bits)
    val busData = out Bits(16 bits)
    val busWr   = out Bool()

    // Direct outputs from the adapter (drive VdpTop IO).
    val rasterTriggerLine   = out UInt(10 bits)
    val rasterTriggerEnable = out Bool()
    val rasterTriggerClear  = out Bool()
    val sprite0X            = out UInt(10 bits)
    val sprite0Y            = out UInt(10 bits)
    val sprite0Enabled      = out Bool()
    val sprite1X            = out UInt(10 bits)
    val sprite1Y            = out UInt(10 bits)
    val sprite1Enabled      = out Bool()
  }

  val adapter = C64Adapter()
  io.busAddr             := adapter.io.busAddr
  io.busData             := adapter.io.busData
  io.busWr               := adapter.io.busWr
  io.rasterTriggerLine   := adapter.io.rasterTriggerLine
  io.rasterTriggerEnable := adapter.io.rasterTriggerEnable
  io.rasterTriggerClear  := adapter.io.rasterTriggerClear
  io.sprite0X            := adapter.io.sprite0X
  io.sprite0Y            := adapter.io.sprite0Y
  io.sprite0Enabled      := adapter.io.sprite0Enabled
  io.sprite1X            := adapter.io.sprite1X
  io.sprite1Y            := adapter.io.sprite1Y
  io.sprite1Enabled      := adapter.io.sprite1Enabled

  // -------------------------------------------------------------
  // Sprite bounce registers. Updated once per vsync.
  // -------------------------------------------------------------
  val s0x   = Reg(UInt(10 bits)) init 120
  val s0y   = Reg(UInt(10 bits)) init 120
  val s1x   = Reg(UInt(10 bits)) init 400
  val s1y   = Reg(UInt(10 bits)) init 300
  val s0dir = Reg(Bool()) init False          // false = +2, true = -2
  val s1dir = Reg(Bool()) init True
  when(io.vsyncRising) {
    // Sprite 0 bounces in X between 32..576 at ±2 px/frame.
    when(s0dir) {
      when(s0x <= U(34, 10 bits))  { s0dir := False; s0x := U(32,  10 bits) }
        .otherwise                  { s0x := s0x - 2 }
    } otherwise {
      when(s0x >= U(574, 10 bits)) { s0dir := True;  s0x := U(576, 10 bits) }
        .otherwise                  { s0x := s0x + 2 }
    }
    // Sprite 1 bounces opposite phase.
    when(s1dir) {
      when(s1x <= U(34, 10 bits))  { s1dir := False; s1x := U(32,  10 bits) }
        .otherwise                  { s1x := s1x - 3 }
    } otherwise {
      when(s1x >= U(574, 10 bits)) { s1dir := True;  s1x := U(576, 10 bits) }
        .otherwise                  { s1x := s1x + 3 }
    }
  }

  // -------------------------------------------------------------
  // Write generator. A small FSM issues the bootstrap write sequence
  // starting on vsync. Each step drives (regAddr, regData, regWr).
  // -------------------------------------------------------------
  // Step encoding (16 steps):
  //  0: $D012=240      8: $D001=s0y lo
  //  1: $D01A=0x01     9: $D002=s1x lo
  //  2: $D015=0x03    10: $D003=s1y lo
  //  3: $D011=0x10    11: $D010=s0/s1 x MSB
  //  4: $D000=s0x lo  12: <idle>
  //  5..7 reserved    13..15 idle
  // plus two async triggers:
  //   - on rasterTriggerPulse rising: write $D011=0x00 then $D019=0x01
  //     (ack) — handled by a 2-step sub-FSM with priority over the
  //     vsync bootstrap.

  val ackStep  = Reg(UInt(2 bits)) init 3    // 3 = idle, 0..1 = writing
  val rasterPrev = RegNext(io.rasterTriggerPulse) init False
  val rasterRising = io.rasterTriggerPulse && !rasterPrev
  when(rasterRising && ackStep === U(3, 2 bits)) {
    ackStep := 0
  }
  when(ackStep < U(2, 2 bits)) { ackStep := ackStep + 1 }
    .elsewhen(ackStep === U(2, 2 bits)) { ackStep := 3 }

  val bootStep = Reg(UInt(4 bits)) init 15   // 15 = idle
  when(io.vsyncRising) {
    bootStep := 0
  } elsewhen(bootStep < U(12, 4 bits)) {
    bootStep := bootStep + 1
  }

  // Drive adapter inputs. Ack sequence takes priority over vsync bootstrap.
  val regA = UInt(8 bits); regA := U(0, 8 bits)
  val regD = Bits(8 bits); regD := B(0, 8 bits)
  val regW = Bool();       regW := False

  when(ackStep < U(2, 2 bits)) {
    regW := True
    switch(ackStep) {
      is(U(0, 2 bits)) { regA := U(0x11, 8 bits); regD := B(0x00, 8 bits) }   // DEN=0
      is(U(1, 2 bits)) { regA := U(0x19, 8 bits); regD := B(0x01, 8 bits) }   // IRQ ack
    }
  } elsewhen(bootStep < U(12, 4 bits)) {
    regW := True
    switch(bootStep) {
      is(U(0,  4 bits)) { regA := U(0x12, 8 bits); regD := B(240,             8 bits) }
      is(U(1,  4 bits)) { regA := U(0x1A, 8 bits); regD := B(0x01,            8 bits) }
      is(U(2,  4 bits)) { regA := U(0x15, 8 bits); regD := B(0x03,            8 bits) }
      is(U(3,  4 bits)) { regA := U(0x11, 8 bits); regD := B(0x10,            8 bits) }
      is(U(4,  4 bits)) { regA := U(0x00, 8 bits); regD := s0x(7 downto 0).asBits }
      is(U(5,  4 bits)) { regA := U(0x01, 8 bits); regD := s0y(7 downto 0).asBits }
      is(U(6,  4 bits)) { regA := U(0x02, 8 bits); regD := s1x(7 downto 0).asBits }
      is(U(7,  4 bits)) { regA := U(0x03, 8 bits); regD := s1y(7 downto 0).asBits }
      is(U(8,  4 bits)) {
        regA := U(0x10, 8 bits)
        regD := (B(0, 6 bits) ## s1x(8) ## s0x(8))
      }
      default { regW := False }
    }
  }

  adapter.io.regAddr := regA
  adapter.io.regData := regD
  adapter.io.regWr   := regW
}
