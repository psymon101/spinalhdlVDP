# Tasks 1-7 Complete Walkthrough: From Blank Screen to Scrolling Tiles

**Document Purpose**: Comprehensive explanation of how the spinalhdlVDP project went from nothing to a working tile-based video system with scrolling.

> **Note (2026-05-24):** This walkthrough describes the historical development of the VDP. As of the **RTL Platform-Agnosticism Purge (#10567)**, all platform-specific code and adapters mentioned in later tasks have been removed from the RTL. The VDP is now a purely generic graphics IP.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Phase 1: Output Bring-Up (Tasks 1-5)](#phase-1-output-bring-up-tasks-1-5)
3. [Phase 2: Core Mode0 Pixel Control (Tasks 6-7)](#phase-2-core-mode0-pixel-control-tasks-6-7)
4. [Code Deep Dives](#code-deep-dives)
5. [Validation and Testing](#validation-and-testing)
6. [What Comes Next](#what-comes-next)

---

## Executive Summary

### What Was Built

A fully functional **Video Display Processor (VDP)** that generates 640×480 @ 60Hz HDMI video output on the Tang Nano 20K FPGA. The system uses a **tile-based graphics architecture** (similar to NES, SNES, Sega Genesis) and supports smooth hardware scrolling.

### Key Achievements

| Metric | Value |
|--------|-------|
| Resolution | 640×480 pixels |
| Refresh Rate | 60 Hz |
| Color Depth | 8 colors (3-bit index, expandable to 256 with palette) |
| Tile Size | 16×16 pixels |
| Screen Tiles | 40×30 (1,200 tiles) |
| Pattern Memory | 8 unique tile patterns |
| Scroll Range | Full 10-bit X (0-1023), 9-bit Y (0-511) |

---

## Phase 1: Output Bring-Up (Tasks 1-5)

### Task 1: Project Skeleton

#### Purpose
Establish the build infrastructure and project organization before writing any hardware logic.

#### What Was Created

**Build System (build.sbt)**
```scala
// Scala-based build configuration for SpinalHDL
scalaVersion := "2.13.12"
libraryDependencies += "com.github.spinalhdl" %% "spinalhdl-core" % "1.12.3"
```

SpinalHDL is a **hardware description DSL** (Domain-Specific Language) embedded in Scala.

#### Build Flow
```
SpinalHDL (Scala) → SpinalHDL Compiler → Verilog → Gowin Synthesis → Bitstream → FPGA
```

---

### Task 2: Clocking and Reset

#### The Problem
The Tang Nano 20K provides a **27 MHz crystal oscillator**. But we need:
- **25.2 MHz** pixel clock for 640×480 @ 60Hz video
- **126 MHz** serializer clock for TMDS (HDMI encoding runs at 5× pixel rate)

#### The Solution: PLL + Clock Divider

**PLL (Phase-Locked Loop)**
```
27 MHz Input → PLL Multiplier (×4.666...) → 126 MHz Output
```

**Clock Divider**
```scala
// 126 MHz ÷ 5 = 25.2 MHz pixel clock
val clkdiv = GowinClkdiv()
```

---

### Task 3: Video Timing Generator

#### Understanding Video Timing

A CRT/LCD monitor expects a very specific sequence of signals. This is called **raster scanning**.

#### The 640×480 @ 60Hz Standard (VESA)

**Horizontal Timing (per line)**
```
┌─────────────────────────────────────────────────────────────┐
│  Active Video  │  Front Porch  │  Sync  │  Back Porch  │AV│
│    640 pix     │    16 pix     │ 96 pix │   48 pix     │→ │
└─────────────────────────────────────────────────────────────┘
Total: 800 pixels per line
```

**Vertical Timing (per frame)**
```
Active: 480 lines
Front porch: 10 lines
Sync: 2 lines  
Back porch: 33 lines
Total: 525 lines
```

#### Implementation in `VdpTop.scala`

```scala
val hCounter = Reg(UInt(log2Up(800) bits)) init 0  // 0-799
val vCounter = Reg(UInt(log2Up(525) bits)) init 0  // 0-524
```

---

### Task 4: TMDS/HDMI Output Path

#### Why TMDS?

HDMI uses **TMDS encoding** for:
1. **DC balance**: Equal number of 0s and 1s
2. **Clock recovery**: Receiver extracts clock from data
3. **Reduced EMI**: Minimizes electromagnetic interference

#### TMDS Encoding

For each 8-bit color value, TMDS produces a **10-bit symbol**:
```
8-bit input → TMDS Encoder → 10-bit output
```

---

### Task 5: Test Pattern Output

#### The Pattern

A simple quadrant layout with a grid overlay:
```
┌─────────────────┬─────────────────┐
│      RED        │     GREEN       │
│   (checker)     │   (stripes)     │
├─────────────────┼─────────────────┤
│      BLUE       │     YELLOW      │
│   (border)      │   (cross)       │
└─────────────────┴─────────────────┘
```

---

## Phase 2: Core Mode0 Pixel Control (Tasks 6-7)

### Task 6: Basic Pattern Source - The Tile Engine

#### The Problem with Framebuffers

A 640×480 display with 8-bit color needs:
```
640 × 480 × 1 byte = 307,200 bytes (~300 KB)
```

The Tang Nano 20K FPGA has limited BRAM (~100 KB). A full framebuffer wouldn't fit!

#### The Solution: Tiles

Classic game consoles solved this with **tile-based graphics**:

**Concept**
- Screen is divided into small squares called **tiles** (16×16 pixels)
- Store a small set of **tile patterns** (8 unique tiles)
- Store a **tile map** saying "put tile 3 here, tile 5 there"

**Memory Math**
```
Tile data:      8 tiles × 16×16 pixels × 3 bits = 6,144 bits (768 bytes)
Tile map:       40×30 tiles × 3 bits           = 3,600 bits (450 bytes)
Total:                                          ≈ 1.2 KB
```

**That's 250× less memory than a framebuffer!**

#### The Two-Memory Architecture

**1. Tile Data Memory (`tileRows`)**

Stores the actual pixel patterns for all 8 tiles.

```scala
val tileRows = Mem(Bits(48 bits), initialContent = tileRowInit)
```

- **48 bits per row**: 16 pixels × 3 bits per pixel
- **16 rows per tile**: 16 pixels tall
- **8 tiles total**: 128 rows

**2. Tile Map Memory (`tileMap`)**

Stores the screen layout - which tile goes where.

```scala
val tileMap = Mem(Bits(3 bits), initialContent = tileMapInit)
```

- **40 columns × 30 rows** = 1,200 entries
- **3 bits per entry**: Can reference tiles 0-7

#### Address Generation Pipeline

For every pixel clock, we fetch the right pixel:

**Step 1: Calculate Tile Position**
```scala
val tileX = x(9 downto 4)     // x / 16
val tileY = y(8 downto 4)     // y / 16
```

**Step 2: Look Up Tile Index**
```scala
val tileAddress = (tileY × 40) + tileX
val tileIndex = tileMap.readAsync(tileAddress)
```

**Step 3: Calculate Pixel Position Within Tile**
```scala
val pixelX = x(3 downto 0)    // x % 16
val pixelY = y(3 downto 0)    // y % 16
```

**Step 4: Look Up Row Data**
```scala
val rowAddress = (tileIndex × 16) + pixelY
val rowData = tileRows.readAsync(rowAddress)
```

**Step 5: Extract Specific Pixel**
```scala
io.pixelIndex := rowData.subdivideIn(3 bits)(pixelX)
```

#### The 8 Tile Patterns

| Tile | Description |
|------|-------------|
| 0 | Red/black checkerboard |
| 1 | Green/white vertical stripes |
| 2 | Blue/white horizontal stripes |
| 3 | Yellow/black diagonal pattern |
| 4 | White border with cyan center |
| 5 | Magenta X pattern |
| 6 | White cross on blue |
| 7 | Concentric circles (yellow, red, black) |

---

### Task 7: Scroll Path - Adding Camera Movement

#### The Concept

Scrolling lets you display a **larger virtual screen** than the physical display. By changing which part of the tile map is visible, you create the illusion of camera movement.

```
Without scroll:                 With scroll:
┌──────────┐                   ╔══════════╗
│┌────┬────┐│                   ║....┌────┤
││ 0  │ 1  ││                   ║....│ 2  │
│├────┼────┤│      Scroll X=8   ║....├────┤
││ 2  │ 3  ││    ─────────────→ ║....│ 3  │
│└────┴────┘│                   ║....└────┘
└──────────┘                   ╚══════════╝
```

#### Implementation

**Adding Scroll Inputs**

In `BasicPatternSource`:
```scala
val io = new Bundle {
  val x = in UInt(10 bits)        // Screen X position
  val y = in UInt(10 bits)        // Screen Y position
  val scrollX = in UInt(10 bits)  // Horizontal scroll offset
  val scrollY = in UInt(9 bits)   // Vertical scroll offset
  val pixelIndex = out Bits(3 bits)
}
```

**Applying Scroll Offsets**
```scala
// Add scroll to screen position
val scrolledX = (io.x + io.scrollX).resize(10 bits)
val scrolledY = (io.y + io.scrollY).resize(9 bits)

// Use scrolled position for tile lookup
val tileX = scrolledX(9 downto 4)
val tileY = scrolledY(8 downto 4)
```

**Key Insight**: Scroll happens **before** tile lookup. We're not moving the display window - we're shifting which part of the world we look at for each screen pixel.

#### Hardware Scroll Demo

In `TopTang20kHdmi.scala`:
```scala
// Detect frame boundaries
val vsyncPrev = RegNext(video.io.vsync) init True
val vsyncRising = video.io.vsync && !vsyncPrev

// Increment frame counter every frame (60 Hz)
val frameCounter = Reg(UInt(10 bits)) init 0
when(vsyncRising) {
  frameCounter := frameCounter + 1
}

// Auto-scroll for demo
video.io.scrollX := frameCounter                    // +1 pixel/frame
video.io.scrollY := (frameCounter >> 1).resized     // +1 pixel/2 frames
```

**Result**: On hardware, the tile pattern visibly drifts diagonally!

---

## Code Deep Dives

### File: `VdpTop.scala`

**Purpose**: Main video controller - generates timing and produces pixels

**Key Components**:

| Component | Type | Purpose |
|-----------|------|---------|
| hCounter | Register | Horizontal position (0-799) |
| vCounter | Register | Vertical position (0-524) |
| patternSource | Instance | Tile graphics generator |
| pixelIndex | Wire | 3-bit color index from tile system |

**Critical Path**:
```
Counter increment → Address generation → Memory read → Color lookup → Output
```

All happens in **one 25.2 MHz clock cycle** (39.7 nanoseconds)!

### File: `BasicPatternSource.scala`

**Purpose**: Tile-based graphics engine

**Memory Organization**:

```
tileRows Memory Layout (128 rows × 48 bits):
┌─────────────┬──────────────────────────────────────────────┐
│ Row Address │ Contents                                     │
├─────────────┼──────────────────────────────────────────────┤
│ 0-15        │ Tile 0: Red/black checkerboard              │
│ 16-31       │ Tile 1: Green/white stripes                 │
│ 32-47       │ Tile 2: Blue/white stripes                  │
│ 48-63       │ Tile 3: Yellow/black diagonal               │
│ 64-79       │ Tile 4: White border + cyan center          │
│ 80-95       │ Tile 5: Magenta X pattern                   │
│ 96-111      │ Tile 6: White cross on blue                 │
│ 112-127     │ Tile 7: Concentric circles                  │
└─────────────┴──────────────────────────────────────────────┘

tileMap Memory Layout (1200 entries × 3 bits):
┌─────────────────────────────────────────────────────────────┐
│ Entry 0    │ Tile at screen position (0,0)  → stores 0-7  │
│ Entry 1    │ Tile at screen position (1,0)  → stores 0-7  │
│ ...        │ ...                                           │
│ Entry 1199 │ Tile at screen position (39,29)→ stores 0-7  │
└─────────────────────────────────────────────────────────────┘
```

### File: `TopTang20kHdmi.scala`

**Purpose**: Board-specific wrapper - connects VDP to Tang Nano 20K hardware

**Clock Domains**:
```
┌──────────────────────────────────────────────────────────────┐
│ Input: 27 MHz from board oscillator                          │
│           ↓                                                  │
│ PLL: Multiplies to ~126 MHz (TMDS clock)                     │
│           ↓                                                  │
│ Clock Divider: Divides by 5 → 25.2 MHz (pixel clock)         │
│           ↓                                                  │
│ pixelClockDomain: All video logic runs here                  │
└──────────────────────────────────────────────────────────────┘
```

---

## Validation and Testing

### Simulation (`VdpTopSim.scala`)

**What It Tests**:

1. **Baseline (scroll = 0,0)**: Verifies un-scrolled behavior
2. **Horizontal scroll (8,0)**: Tests X offset by half a tile
3. **Vertical scroll (0,16)**: Tests Y offset by one full tile
4. **Diagonal scroll (24,32)**: Tests combined X+Y offset

### Hardware Validation Checklist

- ✅ Simulation passes
- ✅ Synthesis succeeds
- ✅ Hardware flash succeeds
- ✅ HDMI output works on physical monitor
- ✅ Scrolling visible and smooth

---

## What Comes Next

### Task 8 (Wraparound/Seam)
Smooth, artifact-free wraparound when scrolling past screen edges.

### Task 9 (Line Buffer)
Prefetch an entire line into a buffer. Allows fetching from SDRAM.

### Task 10 (Palette)
Programmable color lookup. 256-color display from 3-bit tile data.

---

## Glossary

| Term | Definition |
|------|------------|
| **BRAM** | Block RAM - dedicated memory blocks in FPGA |
| **Clock Domain** | A set of logic running on a specific clock |
| **LUT** | Look-Up Table - FPGA element that implements logic functions |
| **PLL** | Phase-Locked Loop - circuit for frequency multiplication |
| **Raster** | The scanning pattern of a display |
| **TMDS** | Transition Minimized Differential Signaling - HDMI encoding |
| **Tile** | Reusable graphics pattern (16×16 pixels) |
| **Tile Map** | Screen layout describing which tile goes where |

---

**Document Version**: 1.0  
**Author**: CoralReef (Teach-back lane)  
**Last Updated**: 2026-04-11
