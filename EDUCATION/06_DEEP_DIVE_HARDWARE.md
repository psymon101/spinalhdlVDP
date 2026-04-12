# Deep Dive: Behind the Scenes - From Scala to Silicon

**Purpose**: Understand exactly what hardware is created, how it operates cycle-by-cycle, and how SpinalHDL maps to actual FPGA primitives.

---

## Table of Contents

1. [SpinalHDL to Hardware Mapping](#spinalhdl-to-hardware-mapping)
2. [Clock Cycle-by-Cycle Execution](#clock-cycle-by-cycle-execution)
3. [FPGA Primitives Explained](#fpga-primitives-explained)
4. [Memory Internals](#memory-internals)
5. [Timing Analysis](#timing-analysis)
6. [Power and Resource Usage](#power-and-resource-usage)

---

## SpinalHDL to Hardware Mapping

### What SpinalHDL Actually Generates

When you write:
```scala
val hCounter = Reg(UInt(10 bits)) init 0
```

SpinalHDL generates this Verilog:
```verilog
reg [9:0] hCounter;
always @(posedge clk or posedge reset) begin
  if (reset) begin
    hCounter <= 10'd0;
  end else begin
    hCounter <= hCounter_next;
  end
end
```

Which maps to this hardware:
```
                    FPGA Fabric
┌─────────────────────────────────────────────────────┐
│  ┌─────────────┐      ┌─────────────┐              │
│  │  10 DFFs    │←─────│  10-to-10   │              │
│  │ (hCounter)  │      │   MUX       │              │
│  │             │      │ (next val)  │              │
│  │  Q  Q  Q  Q │      │   ↓         │              │
│  │  │  │  │  │ │      │ ┌─────────┐ │              │
│  │  │  │  │  │ └──────┤→│ +1 Adder│ │              │
│  │  │  │  │ └─────────┤ │         │ │              │
│  └──┴──┴──┴───────────┤ └────┬────┘ │              │
│                       └──────┼───────┘              │
│                              │                      │
│                         Compare to 799             │
└─────────────────────────────────────────────────────┘
```

**10 DFFs** = 10 D-type Flip-Flops, each storing 1 bit.

### Breaking Down `when/otherwise`

SpinalHDL:
```scala
when(hCounter === 800 - 1) {
  hCounter := 0
} otherwise {
  hCounter := hCounter + 1
}
```

Generated Verilog:
```verilog
wire [9:0] hCounter_next;
assign hCounter_next = (hCounter == 10'd799) ? 10'd0 : (hCounter + 10'd1);
```

Hardware implementation:
```
                    ┌─────────────┐
hCounter ────────┬──→│ 10-bit      │
                 │   │ Comparator  │──→ (hCounter == 799)
                 │   │  (== 799)   │
                 │   └─────────────┘
                 │           │
                 │   ┌───────┴───────┐
                 │   │   2:1 MUX     │
                 └──→│ 0  vs  h+1    │──→ hCounter_next
                     │   select:     │
                     │   (==799)     │
                     └───────────────┘
```

**The Comparator**: A tree of XNOR gates comparing each bit.

**The MUX**: 10 individual 2:1 multiplexers (one per bit), all controlled by the same select signal.

**The Adder**: A 10-bit ripple-carry adder (or faster carry-lookahead, depending on synthesis).

---

## Clock Cycle-by-Cycle Execution

### A Single Line (800 clock cycles)

Let's trace what happens during one horizontal line (800 pixel clocks):

```
Cycle 0: hCounter=0
  ├── Pixel (0, vCounter) displayed
  ├── tileX = 0/16 = 0
  ├── tileY = vCounter/16
  ├── tileMap[0*40 + tileY] → returns tile index (e.g., 3)
  ├── tileRows[3*16 + (vCounter%16)] → returns row data
  ├── Extract pixel (vCounter%16) from row
  └── Output RGB value

Cycle 1: hCounter=1
  ├── Same process, now x=1
  └── May be in same tile, or different tile if x crossed boundary

...

Cycle 639: hCounter=639 (last visible pixel)
  ├── Last active pixel of line
  └── de (Data Enable) still high

Cycle 640: hCounter=640
  ├── Front porch begins
  ├── de goes LOW
  └── RGB outputs don't matter (not displayed)

Cycle 656: hCounter=656
  ├── hsync goes LOW (active low sync pulse)
  └── Monitor detects horizontal sync

Cycle 752: hCounter=752  
  ├── hsync goes HIGH
  └── Back porch begins

Cycle 799: hCounter=799
  ├── Last cycle of line
  ├── Comparator detects (hCounter == 799)
  ├── Next cycle: hCounter becomes 0
  └── If vCounter == 524, it also resets to 0
```

### The Magic of Parallelism

While the above looks sequential, **everything happens in parallel** in hardware:

```
Same clock cycle, simultaneously:
┌──────────────────────────────────────────────────────────────┐
│ Counter → Tile Calc → Map Read → Row Read → Pixel Extract   │
│    ↓         ↓           ↓          ↓            ↓          │
│  hCount    tileAddr   tileIdx    rowData      pixelIdx      │
└──────────────────────────────────────────────────────────────┘
         ↑                        ↑                    ↑
         └──── Combinational ─────┘                    │
                                                       │
                                              Registered Output
```

All the "→" arrows are combinational logic (gates only, no clocks). Only the counters are registered.

### Critical Path Analysis

The longest path (slowest combinational chain) determines maximum clock speed:

```
Path: hCounter → tileAddress calc → tileMap read → rowAddress calc → rowData read → pixel extract → color lookup

Let's trace the delay:

1. hCounter Q output (DFF clk-to-Q):          ~0.5 ns
2. Tile address adder (tileY*40 + tileX):     ~2.0 ns
3. BRAM read access (tileMap):                ~3.0 ns
4. Row address concatenation:                 ~0.5 ns  
5. BRAM read access (tileRows):               ~3.0 ns
6. Subdivide/mux for pixel extraction:        ~1.0 ns
7. Color lookup switch statement:             ~1.5 ns
8. Setup time for output registers:           ~0.5 ns
                                              ─────────
Total critical path:                          ~12.0 ns

Max clock frequency: 1/12ns = 83 MHz

Our pixel clock is 25.2 MHz, so we have plenty of margin (3.3×)
```

---

## FPGA Primitives Explained

### Look-Up Tables (LUTs)

The Tang Nano 20K uses **LUT4** primitives (4-input Look-Up Tables).

**What is a LUT?**
A LUT is a small SRAM that implements any Boolean function of its inputs:

```
     Inputs
       ↓
┌─────────────┐
│    LUT4     │
│  ┌───────┐  │
│  │ 16×1  │  │  ← 16 entries (2^4), 1 bit each
│  │ SRAM  │  │
│  └───────┘  │
│      ↓      │
│   Output    │
└─────────────┘
```

**Example**: 2-input AND gate
```
Inputs: A, B
Truth table stored in LUT:
  A B | Out
  ────┼────
  0 0 │  0
  0 1 │  0
  1 0 │  0
  1 1 │  1

LUT contents: 4'b0001 (just the output column)
```

**Bigger functions need multiple LUTs**:
- 4-input function: 1 LUT
- 5-input function: 2 LUTs + 1 MUX
- 6-input function: 4 LUTs + tree of MUXes

### Our Design's LUT Usage

**hCounter comparison** (`hCounter == 799`):
```
799 in binary: 10'b1100011111

Comparison requires: hCounter[9] & hCounter[8] & ~hCounter[7] & 
                     ~hCounter[6] & hCounter[5] & hCounter[4] &
                     hCounter[3] & hCounter[2] & hCounter[1] & hCounter[0]

This is a 10-input AND with some inverted inputs.

LUTs required: ~3-4 LUTs (tree structure)
```

**Color lookup switch**:
```scala
switch(pixelIndex) {
  is(1) { io.red := 255 }
  is(2) { io.red := 255 }
  ...
}
```

Each RGB bit needs a 7-input mux (select from 8 values based on 3-bit index).

**Total estimated LUTs for VdpTop**: ~200-300 LUTs

### Flip-Flops (FFs)

Each bit of storage = 1 DFF:
- `hCounter`: 10 bits = 10 FFs
- `vCounter`: 10 bits = 10 FFs
- `frameCounter` (in TopTang20kHdmi): 10 bits = 10 FFs
- Various pipeline registers: ~20 FFs

**Total FFs**: ~50-100

### Block RAM (BRAM)

The Tang Nano 20K has **~100 Kbits** of BRAM.

**What is BRAM?**
Dedicated SRAM blocks, not built from LUTs. Much more efficient for memory.

```
BRAM Configuration Options:
┌─────────────────────────────────────────┐
│  16K × 1 bit  (deep and narrow)        │
│  8K × 2 bits                             │
│  4K × 4 bits                             │
│  2K × 8 bits                             │
│  1K × 16 bits  (shallow and wide)       │
└─────────────────────────────────────────┘
```

**Our Memory Usage**:
```
tileMap:   1200 entries × 3 bits = 3,600 bits → 1 BRAM (9K configuration)
tileRows:  128 entries × 48 bits = 6,144 bits → 1 BRAM (9K configuration)

Total: ~2 BRAMs used
```

**BRAM Internal Structure**:
```
┌─────────────────────────────────────────────┐
│              BRAM (9K bits)                 │
│  ┌─────────────────────────────────────┐   │
│  │  SRAM Array                         │   │
│  │  ┌─────┬─────┬─────┬─────┐         │   │
│  │  │Row 0│Row 1│ ... │Row N│         │   │
│  │  │ 48b │ 48b │     │ 48b │         │   │
│  │  └─────┴─────┴─────┴─────┘         │   │
│  └─────────────────────────────────────┘   │
│              ↓                              │
│  ┌─────────────────────────────────────┐   │
│  │  Output Register (optional)         │   │
│  │  (adds 1 cycle latency if used)     │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### DSP Blocks (Optional)

Modern FPGAs have DSP slices for arithmetic. Our simple adders don't need them - they're implemented in LUTs.

---

## Memory Internals

### Tile Map Memory Deep Dive

**Physical Organization**:
```
Address Space (11 bits for 1200 entries):
                    ┌─────────────────┐
Address 0x000 ─────→│  Tile at (0,0)  │→ "Put tile 3 here"
                    ├─────────────────┤
Address 0x001 ─────→│  Tile at (1,0)  │→ "Put tile 4 here"
                    ├─────────────────┤
Address 0x002 ─────→│  Tile at (2,0)  │
        ...         │      ...        │
Address 0x027 ─────→│  Tile at (39,0) │→ Row 0 complete
                    ├─────────────────┤
Address 0x028 ─────→│  Tile at (0,1)  │→ Row 1 starts
        ...         │      ...        │
Address 0x4AF ─────→│  Tile at (39,29)│→ Last entry
                    └─────────────────┘

Address calculation hardware:
tileAddress = (tileY × 40) + tileX
            = (tileY << 5) + (tileY << 3) + tileX  // ×40 = ×32 + ×8
```

The multiplication by 40 is done with shifts and adds (cheaper than a multiplier):
- `tileY × 32` = `tileY << 5` (5-bit left shift)
- `tileY × 8` = `tileY << 3` (3-bit left shift)
- Add results with tileX

### Tile Row Memory Deep Dive

**Address Calculation**:
```
rowAddress = (tileIndex × 16) + pixelY
           = (tileIndex << 4) + pixelY  // ×16 = left shift 4
```

**Data Layout** (48 bits per row):
```
Bit:  47  46  45 | 44  43  42 | ... | 2   1   0
      ├──────────┼───────────┼─────┼──────────┤
      │ Pixel 15 │ Pixel 14  │ ... │  Pixel 0 │
      │ (3 bits) │  (3 bits) │     │ (3 bits) │
      └──────────┴───────────┴─────┴──────────┘
```

**Extraction Hardware** (`subdivideIn`):
```scala
rowData.subdivideIn(3 bits)(pixelX)
```

Becomes:
```verilog
// 16:1 multiplexer selecting 3 bits from 48-bit word
assign pixelIndex = rowData[pixelX*3 +: 3];
//                          │      │  │
//                          │      │  └─ Width (3 bits)
//                          │      └──── Step (3 bits per pixel)
//                          └─────────── Start position
```

Hardware: 16-to-1 multiplexer tree, 3 bits wide.

---

## Timing Analysis

### Setup and Hold Times

For every flip-flop:
```
       ┌─────────────────────────────────────────┐
       │  DFF (Flip-Flop)                       │
       │                                         │
D ────→│  D ───┐                                │
       │       │   ┌─────┐                      │
Clk ───→│  CLK──┴──→│     │──→ Q                │
       │           │  ┌──┘                      │
       │  Setup↑   │  │                        │
       │  Hold ↑   └──┘                        │
       └─────────────────────────────────────────┘
```

**Setup Time (t_su)**: Data must be stable before clock edge (~0.5 ns)
**Hold Time (t_h)**: Data must be stable after clock edge (~0.2 ns)

**Clock-to-Q (t_cq)**: Time from clock edge to valid output (~0.5 ns)

### Our Timing Requirements

```
Period = 1/25.2 MHz = 39.68 ns

Required: t_cq + t_comb + t_su < 39.68 ns
Actual:   0.5   +  12.0   + 0.5  = 13.0 ns
Margin:   39.68 - 13.0 = 26.68 ns (67% margin!)
```

Very safe. We could probably overclock to 60+ MHz if needed.

### Clock Domain Crossing (CDC)

When signals cross between clock domains (25.2 MHz pixel and 126 MHz TMDS):

```
Pixel Domain (25.2 MHz)         TMDS Domain (126 MHz)
┌──────────────────┐           ┌──────────────────┐
│ RGB data valid   │           │ Serializer needs │
│ @ pixel clock    │──────────→│ data @ 5× rate  │
└──────────────────┘           └──────────────────┘
         │                              │
         │      ┌─────────────┐         │
         └──────→│ Async FIFO  │─────────┘
                │  (2+ stages)│
                └─────────────┘
                
The TMDS serializer has its own FIFO
that reads 5× for each pixel.
```

**Tang20kHdmiTx** handles this internally.

---

## Power and Resource Usage

### Resource Report (Estimated)

From Gowin synthesis, you'd see something like:
```
=== Resource Usage ===

LUTs:     350 / 20,736  (1.7%)
FFs:      85  / 20,736  (0.4%)
BRAMs:    2   / 46      (4.3%)
PLLs:     1   / 2       (50%)
IOs:      14  / 130     (10.8%)
```

We're using a tiny fraction of the FPGA!

### Power Estimation

**Dynamic Power** (switching):
```
P_dynamic = C × V² × f

Where:
  C = Capacitance switched
  V = 1.0V (core voltage)
  f = 25.2 MHz

Rough estimate: ~50 mW for the VDP core
```

**Static Power** (leakage):
```
Even when not switching, transistors leak current.
Estimate: ~20 mW for this design
```

**Total**: ~70 mW (very low!)

### Where Power is Consumed

1. **Clock distribution** (~30%): Every clocked element
2. **BRAM accesses** (~25%): Two BRAM reads per pixel
3. **Combinational logic** (~25%): Adders, comparators, muxes
4. **IO pins** (~15%): Driving HDMI signals
5. **PLL** (~5%): Clock generation

---

## Simulation vs. Hardware

### What Simulation Shows

Verilator simulation:
```
Cycle 0: hCounter=0, vCounter=0, hsync=1, vsync=1, de=1, R=255,G=0,B=0
Cycle 1: hCounter=1, vCounter=0, hsync=1, vsync=1, de=1, R=0,G=255,B=0
...
```

Cycle-accurate behavior, but idealized (no delays).

### What Hardware Actually Does

Real signals on an oscilloscope:
```
Ideal (Simulation):     Actual (Oscilloscope):
    ┌──┐                    ╱╲__
    │  │                   ╱    ╲
────┘  └───              ╱      ╲___
(Perfect square)       (Rounded edges,
                        slight overshoot)
                        
Reason: PCB traces have capacitance,
driver has limited slew rate
```

### Why Hardware Works Despite "Imperfection"

Digital logic has **noise margins**:
```
Voltage
  1.0V ─┬──┐      ┌──  "1" region (0.7V - 1.0V)
        │  │      │
  0.7V ─┤  │ Noise│ Margin (200mV)
        │  │ Margin│
  0.3V ─┤  └──────┘
        │         "0" region (0.0V - 0.3V)
  0.0V ─┴──────────
```

As long as signals stay in the valid regions, the design works.

---

## RTL Viewer Mental Model

If you opened this in an RTL viewer, you'd see:

```
TopTang20kHdmi
├── PLL (primitive)
├── Clock Divider (primitive)
├── pixelArea (ClockDomain)
│   ├── frameCounter (Register: 10 FF)
│   ├── vsyncPrev (Register: 1 FF)
│   ├── video (VdpTop instance)
│   │   ├── hCounter (Register: 10 FF)
│   │   ├── vCounter (Register: 10 FF)
│   │   ├── patternSource (BasicPatternSource instance)
│   │   │   ├── scrolledX (Wire: adder)
│   │   │   ├── scrolledY (Wire: adder)
│   │   │   ├── tileMap (Memory: BRAM)
│   │   │   ├── tileRows (Memory: BRAM)
│   │   │   └── pixelIndex (Wire: mux)
│   │   └── RGB logic (Combinational)
│   └── hdmiTx (Tang20kHdmiTx instance)
│       └── TMDS serializer (primitive)
└── LED assignments (Combinational)
```

---

## Questions to Test Understanding

1. **Why 10 bits for hCounter when we only go to 800?**
   - 10 bits = 0-1023 range. 9 bits would only give 0-511 (insufficient).
   - `log2Up(800)` = 10, meaning "smallest power of 2 that holds 800"

2. **What happens if BRAM read takes 2 cycles instead of 1?**
   - We'd need to pipeline: fetch tile index in cycle N, fetch pixel in cycle N+1
   - Would require pixel buffer or scanline buffer
   - This is exactly why Task 9 (Line Buffer) exists!

3. **Why does the simulation pass but hardware might fail?**
   - Timing violations (path too slow)
   - Clock domain crossing issues
   - Uninitialized registers (X propagation in sim vs. random in hardware)
   - Metastability across async boundaries

4. **How much could we overclock this design?**
   - Critical path is ~12 ns → max ~80 MHz
   - At 25.2 MHz, we have 3.3× margin
   - Could probably run at 50-60 MHz safely

---

**Next Document**: [07_PHYSICAL_LAYER.md](./07_PHYSICAL_LAYER.md) - PCB traces, signal integrity, and oscilloscope debugging

**Document Version**: 1.0  
**Author**: CoralReef  
**Last Updated**: 2026-04-11
