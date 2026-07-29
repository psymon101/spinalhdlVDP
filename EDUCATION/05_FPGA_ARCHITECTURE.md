# FPGA Architecture and Resource Optimization

This document describes how the VDP SpinalHDL code is compiled, mapped to physical FPGA resources, and optimized to fit the constraints of the Gowin GW2AR-18 device.

---

## 1. Gowin GW2AR-18 Architecture Overview

The **Tang Nano 20K** board features a Gowin GW2AR-LV18QN88C8/I7 FPGA. This chip has the following logic capacity:

| Resource Type | Available Count | Description |
|---------------|-----------------|-------------|
| **LUT4** (Look-Up Table) | 20,736 | 4-input logic function generators |
| **DFF** (Flip-Flops) | 15,552 | 1-bit memory elements (registers) |
| **BSRAM** (Block SRAM) | 46 blocks | On-chip memory blocks (18 Kbits each) |
| **DSP Blocks** | 40 blocks | Dedicated multiplier / ALU blocks |

---

## 2. Synthesizing Logic to Gates

The Gowin synthesis engine (`yosys` or Gowin's proprietary toolchain) translates the generated Verilog netlist into physical hardware primitives.

### Look-Up Tables (LUTs)
Any combinational logic equation (such as comparators, multiplexers, adders) is broken down into a network of 4-input LUTs.
* **4-Input LUT**: Can implement **any** boolean function of up to 4 inputs.
* **Arithmetic Carry Chains**: Dedicated fast-carry routing channels are built next to the LUTs to implement fast adders without routing delays.

### Flip-Flops (DFFs)
Every `Reg` or `RegNext` in SpinalHDL maps to a physical D-type Flip-Flop register. These are distributed throughout the FPGA logic slices.

---

## 3. Block RAM (BSRAM) Mapping

Memory blocks declared in SpinalHDL using `Mem(..., readSync)` are mapped to Gowin's physical **BSRAM18K** blocks. Each block can hold 18,432 bits of data.

### Configuration Modes
A BSRAM block can be configured in various widths and depths (e.g., $16K \times 1$, $8K \times 2$, $4K \times 4$, $2K \times 8$, $1K \times 16$).
* **Dual-Port RAM**: Allows simultaneous reads and writes from two separate address ports.
* **VDP BSRAM Budget**: The VDP uses 42 of the available 46 BSRAM blocks. This includes:
  * **Line Buffers** (`LinestateStore`)
  * **Pattern Memory** (Tile Bitmaps)
  * **Palette LUT RAM**
  * **QSPI FIFO Buffers**

> [!WARNING]
> Because BSRAM resources are highly constrained (42/46), memories should never use asynchronous reads (`readAsync`) unless absolutely necessary, as `readAsync` synthesizes using LUTs, which rapidly depletes the FPGA's routing and logic resources.

---

## 4. Clock Routing and Timing Constraints (STA)

The VDP contains multiple asynchronous clock domains that require careful clock routing:

| Clock Domain | Frequency | Source / Purpose |
|--------------|-----------|------------------|
| `clk_pixel` | 25.2 MHz | Video output raster and compositor |
| `clk_sdram` | 40.5 MHz | SDRAM fetch FSM and memory controller |
| `clk_x5` | 126.0 MHz | TMDS HDMI transmitter serialization |
| `qspi_sck` | 40.0 MHz (max) | Host SPI bus transfer clock |

### STA (Static Timing Analysis)
The Gowin router must meet timing constraints for all clocks. Timing parameters are specified in the `.sdc` (Gowin Constraint) file:
* **Worst Setup Slack**: The margin of time by which signals arrive before the next clock edge. A positive slack (e.g., `+4.438 ns`) indicates timing closure is met.
* **TNS (Total Negative Slack)**: The sum of all negative slacks. A timing-clean design **must** have `TNS = 0`.
* **Clock Grouping**: Async clocks (e.g., `clk_pixel` and `clk_sdram`) must be configured as asynchronous clock groups so the timing analyzer does not evaluate false paths across domain crossings.
