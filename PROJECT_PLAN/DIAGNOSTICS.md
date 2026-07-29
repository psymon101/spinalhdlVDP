# VDP Standalone Diagnostics Guide

This document describes the build and run procedures for compiling a standalone diagnostic VDP image for the Tang Nano 20K. 

## Overview

By default, the production VDP image operates in host-initialized mode (`useHostInit = true`). In this mode, the VDP remains blank at power-up and waits for the host microcontroller (e.g., ESP32-P4) to initialize registers and upload assets over the QSPI bus.

For standalone bring-up, debugging, or hardware timing verification without a host microcontroller attached, the VDP can be compiled in standalone diagnostic mode (`useHostInit = false`). This activates an on-chip bootstrap FSM that writes a pre-compiled checkerboard pattern, copper program, and window configuration into the VDP registers immediately at power-up.

---

## Build Procedure

To build a standalone diagnostic VDP image:

1. **Enable Standalone Mode**:
   Open [TopTang20kHdmi.scala](file:///home/itadmin/github/spinalhdlVDP/hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala) and change `useHostInit` to `false`:
   ```scala
   // Locate line 18 in TopTang20kHdmi.scala
   private val useHostInit: Boolean = false
   ```

2. **Generate Verilog**:
   Run the SpinalHDL generator using the project runbook command:
   ```bash
   sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"
   ```
   This will regenerate the Verilog source at `hw/gen/top_tang20k.v`.

3. **Synthesize and Place & Route**:
   Open the Gowin project in the IDE or run the command-line build using the Gowin toolchain to produce the physical bitstream `.fs` file:
   ```bash
   # Execute synthesis and PnR (GW2AR-LV18QN88C8/I7)
   # The output will be impl/pnr/project.fs
   ```

---

## Run Procedure

1. **Connect Tang Nano 20K**:
   Connect the Tang Nano 20K board to the development host via USB and connect its HDMI port directly to a monitor or capture card.

2. **Program the FPGA**:
   Use `openFPGALoader` to program the generated bitstream to the FPGA SRAM or Flash:
   ```bash
   # Load to SRAM for diagnostic smoke-testing
   openFPGALoader -b tangnano20k impl/pnr/project.fs
   ```

---

## Expected Observable Output

Upon successful programming:

* **HDMI Sync Lock**: The connected monitor or capture device must report an HDMI signal lock at $640 \times 480$ @ 60 Hz.
* **Test Pattern**: 
  * A centered $320 \times 240$ active area window (columns 160 to 480, rows 120 to 360) will be displayed.
  * Inside the window, a color-math shadow operation (`op = 01`, $RGB \gg 1$) will be active, creating a visible intensity difference across the window boundary.
  * On-chip diagnostic bands alternating Layer 0 (L0) and Layer 1 (L1) will alternate down the screen.
* **No Host Interaction Required**: The VDP generates this pattern immediately on reset without receiving QSPI configuration packets.

---

## Reverting to Production Mode

To restore the production configuration:

1. Revert `useHostInit` to `true` in [TopTang20kHdmi.scala](file:///home/itadmin/github/spinalhdlVDP/hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala):
   ```scala
   private val useHostInit: Boolean = true
   ```
2. Regenerate Verilog:
   ```bash
   sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"
   ```
3. Recompile the project to produce a standard host-initialized bitstream.
