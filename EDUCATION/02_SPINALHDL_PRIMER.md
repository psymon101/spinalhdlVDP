# SpinalHDL Primer: Language Constructs and Hardware Mapping

This document provides a primer on **SpinalHDL**, the hardware description language (HDL) used to implement the Video Display Processor (VDP). It explains the core syntax constructs, their semantic meaning, and how they map to physical FPGA fabric.

---

## 1. What is SpinalHDL?

SpinalHDL is **not** a high-level synthesis (HLS) language that compiles sequential software (like C or Java) into gates. Instead, it is a **hardware description DSL** (Domain-Specific Language) embedded inside **Scala**. 

When you write SpinalHDL code, you are executing a Scala program that builds an Abstract Syntax Tree (AST) representing digital circuits. The SpinalHDL compiler then traverses this AST to generate clean, syntax-compliant **Verilog** or **VHDL** netlists.

### The Scala Embed-and-Generate Loop
```
[Scala Source] ──(Execute Program)──→ [Abstract Syntax Tree (AST)] ──(Spinal Compiler)──→ [Verilog File]
```

---

## 2. Core Language Constructs

### Component (Modules)
In Verilog, the fundamental block is a `module`. In SpinalHDL, it is a `Component`. A `Component` defines a hardware unit with inputs, outputs, and internal logic.

```scala
import spinal.core._

class SimpleAdder extends Component {
  val io = new Bundle {
    val a   = in UInt(8 bits)
    val b   = in UInt(8 bits)
    val sum = out UInt(8 bits)
  }
  
  io.sum := io.a + io.b
}
```
* **Verilog Mapping**: Synthesizes to a Verilog `module SimpleAdder`.
* **Hardware Mapping**: Generates an 8-bit ripple-carry or carry-lookahead adder using FPGA Look-Up Tables (LUTs).

### Bundle (Interfaces)
A `Bundle` is a user-defined record that groups signals. Ports inside a `Component` are declared inside an `io` Bundle.

```scala
case class VideoBus() extends Bundle {
  val de    = Bool()
  val hsync = Bool()
  val vsync = Bool()
  val color = Bits(12 bits)
}
```

---

## 3. Registers vs. Combinational Logic

SpinalHDL distinguishes clearly between combinational paths and sequential (registered) storage.

### Combinational Assignment (`:=`)
Combinational logic has no memory; its output updates immediately when its inputs change.

```scala
val a, b = Bool()
val c = Bool()
c := a && !b  // c updates instantly
```
* **Verilog Mapping**: `assign c = a && (! b);`
* **Hardware Mapping**: A single LUT input gate.

### Sequential Storage (`Reg` and `RegNext`)
A register stores a state and updates its output only on a clock edge. In SpinalHDL, clocks and resets are handled implicitly by the surrounding `ClockDomain`.

```scala
val toggle = Reg(Bool()) init False
toggle := !toggle
```
* **Verilog Mapping**:
  ```verilog
  reg toggle;
  always @(posedge clk or posedge reset) begin
    if (reset) begin
      toggle <= 1'b0;
    end else begin
      toggle <= !toggle;
    end
  end
  ```
* **Hardware Mapping**: A D-type Flip-Flop (DFF) with a synchronous or asynchronous reset input.

#### Conditional Registration (`RegNextWhen`)
```scala
val lineIndex = RegNextWhen(fillLine, hCounter === 799) init 0
```
* **Hardware Mapping**: A DFF equipped with a clock-enable (CE) pin. The register is only updated when `hCounter === 799` is True; otherwise, it retains its value.

---

## 4. Memories (`Mem`)

On-chip FPGA memory blocks (such as block RAMs or SRAMs) are defined using the `Mem` construct.

```scala
// A 128-word, 10-bit wide RAM
val scrollTable = Mem(UInt(10 bits), wordCount = 128)
```

### Read Asynchronous (`readAsync`)
Asynchronous reads output the memory contents immediately based on the address inputs, bypassing clock edges.
```scala
val data = scrollTable.readAsync(address)
```
* **Hardware Mapping**: Synthesizes as **Distributed RAM** (using LUTs as registers/ROMs). It is fast but consumes precious logic resources rather than dedicated memory blocks.

### Read Synchronous (`readSync`)
Synchronous reads require a clock edge to present data on the output port.
```scala
val data = scrollTable.readSync(address)
```
* **Hardware Mapping**: Maps directly to physical **Block RAM (BRAM)**. This is extremely resource-efficient but introduces a **1-cycle read latency** which downstream pipeline decoders must align to.

---

## 5. Clock Domains and Domain Crossing (CDC)

Every register in SpinalHDL exists inside an active `ClockDomain`. If signals cross between asynchronous clock domains (e.g., from the 25.2 MHz pixel clock to the 40.5 MHz SDRAM clock), they must pass through proper synchronizers to prevent metastability.

### 2-Stage Synchronizer (`BufferCC`)
For single-bit signals (like an enable strobe or reset indicator):
```scala
val enableSync = BufferCC(io.enable, False)
```
* **Hardware Mapping**: Two cascaded DFFs clocked by the destination clock. The first register samples the asynchronous input (which may violate setup/hold and go metastable); the second register samples the stabilized output of the first after one clock cycle.

### Multi-bit Synchronizer (`StreamFifoCC`)
For buses (data arrays, address vectors), a simple `BufferCC` can cause bit tearing (skew). We use a dual-clock asynchronous FIFO:
```scala
val cdcFifo = StreamFifoCC(
  dataType = Bits(8 bits),
  depth    = 16,
  pushClk  = qspiClockDomain,
  popClk   = sdramClockDomain
)
```
* **Hardware Mapping**: A dual-port Block RAM memory block with asynchronous grey-coded address counters crossing between read and write clock ports.

---

## 6. Finite State Machines (`FSM`)

SpinalHDL includes a dedicated FSM library that simplifies writing control logic compared to raw Verilog case statements.

```scala
import spinal.lib.fsm._

val fsm = new StateMachine {
  val sIdle, sFetch, sWait = new State
  setEntrypoint(sIdle)
  
  sIdle.whenIsActive {
    when(io.start) {
      goto(sFetch)
    }
  }
  
  sFetch.whenIsActive {
    io.readStrobe := True
    goto(sWait)
  }
  
  sWait.whenIsActive {
    when(io.dataReady) {
      goto(sIdle)
    }
  }
}
```
* **Verilog Mapping**: SpinalHDL automatically extracts a state register, assigns binary or Gray-code states, and generates next-state combinational decoders.
* **Hardware Mapping**: A state register (DFFs) and lookup tables (LUTs) implementing state transition transitions.

---

## Summary of Syntax to Silicon Mapping

| SpinalHDL Construct | Verilog Equivalent | FPGA Hardware Primitive |
|---------------------|--------------------|-------------------------|
| `Bool() / Bits() / UInt()` | `wire` | Interconnect trace / routing resource |
| `+ / - / ===` | `+ / - / ==` | Carry-chains / LUT logic functions |
| `Reg(...)` | `reg` + `always @(posedge clk)` | DFF (D-Type Flip-Flop) |
| `RegNextWhen(d, cond)` | `always @(posedge clk)` + `if (cond)` | DFF with Clock Enable (CE) |
| `Mem(..., readAsync)` | `reg [W-1:0] mem [N-1:0]` (combinational read) | LUT-based Distributed RAM |
| `Mem(..., readSync)` | `reg [W-1:0] mem [N-1:0]` (registered read) | BSRAM (Block SRAM) primitive |
| `BufferCC(...)` | 2-stage synchronizer register pipeline | Dual cascaded D-Type Flip-Flops |
| `StateMachine` | State register + `always @(*)` case block | State registers + transition LUT logic |
