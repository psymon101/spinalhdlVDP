# Tasks 8-10 Walkthrough: From Seamless Scrolling to Programmable Palettes

**Document Purpose**: Explain the next phase of the spinalhdlVDP project covering wraparound/scrolling, line buffering, and palette-based color lookup.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Task 8: Wraparound / Seam Correctness](#task-8-wraparound--seam-correctness)
3. [Task 9: Line Buffer Implementation](#task-9-line-buffer-implementation)
4. [Task 10: Palette Path](#task-10-palette-path)
5. [System Architecture Evolution](#system-architecture-evolution)
6. [What's Next](#whats-next)

---

## Executive Summary

### The Journey So Far (Recap)

**Tasks 1-7** gave us:
- ✅ 640×480 @ 60Hz video output
- ✅ Tile-based graphics (8 patterns, 40×30 tile map)
- ✅ Hardware scrolling with X/Y offsets

### What's Coming in Tasks 8-10

| Task | Feature | Purpose |
|------|---------|---------|
| Task 8 | Wraparound / Seam | Smooth infinite scrolling without visual glitches |
| Task 9 | Line Buffer | Decouple fetch from display for SDRAM compatibility |
| Task 10 | Palette | Programmable colors without changing tile data |

---

## Task 8: Wraparound / Seam Correctness

### The Problem

With Task 7 scrolling, when you scroll past the edge of the tile map, you hit a boundary. The naive implementation shows garbage or creates a visual "seam" — a discontinuity where the edge doesn't connect smoothly.

### Visual Example

```
Without wraparound (BAD):
                    
┌─────────────────┬─┐
│                 │░│  ← seam: abrupt cutoff
│   Visible area  │░│     then garbage
│                 │░│
└─────────────────┴─┘
        ↑
   Scroll boundary

With wraparound (GOOD):

┌─────────────────┬─┐
│                 │ │  ← seamless wrap to
│   Visible area  │ │     other side of map
│                 │ │
└─────────────────┴─┘
        ↑
   Scroll boundary (invisible)
```

### The Concept: Modular Arithmetic

The key insight: **tile coordinates should wrap around** using modular arithmetic.

```scala
// Without wrap (Task 7):
tileX = scrolledX / 16  // Can exceed 39 (tile map width)

// With wrap (Task 8):
tileX = (scrolledX / 16) % 40  // Always 0-39
```

### Address Calculation with Wrap

```scala
// Current implementation (Task 7):
val tileX = scrolledX(9 downto 4)  // Just take upper bits
val tileY = scrolledY(8 downto 4)

// Problem: When scrolledX = 640, tileX = 40 (out of bounds!)

// Task 8 fix:
val tileX = (scrolledX / 16) % MapTilesX
val tileY = (scrolledY / 16) % MapTilesY
```

### Hardware Implementation

Modulo by power-of-2 is free in hardware (just take bits):
```scala
// % 40 is NOT power-of-2 (requires division)
// Options:
// 1. Use actual division (expensive in LUTs)
// 2. Use power-of-2 tile map size (64×32 = 2K entries)
// 3. Use conditional subtraction (cheaper)
```

**Likely Task 8 approach:** Use conditional subtraction or accept power-of-2 map size.

### Validation Strategy

1. **Simulation**: Scroll to boundary-1, boundary, boundary+1
2. **Visual check**: No discontinuity at wrap point
3. **Hardware test**: Continuous scroll shows smooth infinite world

---

## Task 9: Line Buffer Implementation

### The Problem

Task 6-8 read tile data **on-demand** during display time:
```
Pixel 0: Read tile map → Read tile data → Output
Pixel 1: Read tile map → Read tile data → Output
...
```

This works for fast BRAM but **fails for SDRAM** which has:
- Higher latency (multiple clock cycles)
- Burst access patterns
- Refresh cycles

### The Solution: Line Buffer

**Concept**: Decouple fetching from display using **double buffering**.

```
┌─────────────────────────────────────────────────────────────┐
│                    DOUBLE BUFFER ARCHITECTURE                 │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────┐      ┌───────────────┐                   │
│  │   Buffer A    │      │   Buffer B    │                   │
│  │  (640 × 4b)   │      │  (640 × 4b)   │                   │
│  │               │      │               │                   │
│  │  Being filled │←────→│ Being drained │                   │
│  │  from tiles   │ Swap │  to display   │                   │
│  └───────────────┘      └───────────────┘                   │
│         ↑                      ↑                             │
│    Fill during           Drain during                       │
│    horizontal blank      active video                       │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### The Two-Phase Operation

**Phase 1: Horizontal Blanking (Line N-1 ending)**
- Display just finished showing Line N-1 from Buffer B
- Tile fetcher fills Buffer A with Line N data
- Takes ~800 pixel clocks (full line time)

**Phase 2: Active Video (Line N displaying)**
- Buffer A is now full
- Display drains Buffer A to screen, pixel by pixel
- Meanwhile, tile fetcher can start filling Buffer B with Line N+1

**At Line End**: Swap buffers

### Why 4-Bit Storage?

Current pixel index is 3 bits. Why store 4 bits?

```
3 bits = 8 colors (current)
4 bits = 16 colors (future expansion)

Memory cost per buffer:
  640 pixels × 4 bits = 2,560 bits = 320 bytes
  
Two buffers: 640 bytes total (easily fits in BRAM)
```

The extra bit provides:
- Future expansion (16 colors without hardware change)
- Cleaner memory alignment (4 bits vs 3 bits)
- Compatibility with palette system (Task 10)

### Data Flow (Task 9)

```
┌─────────────────────────────────────────────────────────────┐
│ TASK 9 DATA FLOW                                            │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Tile Engine                    Line Buffer    Display       │
│  ───────────                    ───────────    ───────       │
│     │                               │            │           │
│     │ (during blanking)             │            │           │
│     ├───────────────────────────────→│ Fill       │           │
│     │ pixel index (4b)              │            │           │
│     │                               │            │           │
│     │                          (during active)   │           │
│     │                               ├────────────→│ Drain    │
│     │                               │ pixel index  │         │
│     │                               │            │           │
│     │                               ↓            ↓           │
│     │                            [SWAP at line boundary]     │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### Memory Implementation Options

**Option 1: True Dual-Port BRAM**
- Port A: Write (fill)
- Port B: Read (drain)
- Both ports independent

**Option 2: Simple Dual-Port BRAM**
- One write port, one read port
- Simpler, sufficient for line buffer

**Option 3: Two Single-Port BRAMs (Ping-Pong)**
- Buffer A: Port for both read and write
- Buffer B: Port for both read and write
- Physically swap which is A and B

**Gowin FPGA likely implements as**: Simple dual-port BRAM or distributed RAM.

### Validation Requirements

1. **Simulation**: Verify buffer swap timing
2. **Visual**: Output matches Task 6 baseline exactly
3. **Timing**: No glitches at line boundaries
4. **Resource**: Confirm synthesis uses intended memory type

---

## Task 10: Palette Path

### The Problem

Tasks 6-9 used **fixed RGB decode**:
```scala
pixelIndex match {
  case 0 => RGB(0, 0, 0)      // Always black
  case 1 => RGB(255, 255, 255) // Always white
  case 2 => RGB(255, 0, 0)     // Always red
  ...
}
```

To change colors, you must change **tile data**. This is inflexible.

### The Solution: Palette Lookup

**Concept**: Add an indirection layer between pixel index and RGB output.

```
Before (Task 9):                    After (Task 10):
                                    
pixelIndex → RGB                   pixelIndex → Palette → RGB
                                    
2 → Red(255,0,0)                   2 → palette[2] → Any RGB
                                    
Fixed forever                      Programmable!
```

### The 16-Entry Palette

```scala
val palette = Mem(RGB(24 bits), 16)  // 16 entries × 24 bits

// Entry mapping:
// 0-7: Current colors (backward compatible)
// 8-15: Additional colors / black (expansion)
```

**Entry 0**: Background/black (special meaning)
**Entries 1-7**: Original 7 colors
**Entries 8-15**: Expansion (initialized to black)

### Palette Initialization

```scala
palette(0) := RGB(0, 0, 0)        // Black
palette(1) := RGB(255, 255, 255)  // White
palette(2) := RGB(255, 0, 0)      // Red
palette(3) := RGB(0, 255, 0)      // Green
palette(4) := RGB(0, 0, 255)      // Blue
palette(5) := RGB(255, 255, 0)    // Yellow
palette(6) := RGB(0, 255, 255)    // Cyan
palette(7) := RGB(255, 0, 255)    // Magenta
palette(8) := RGB(0, 0, 0)        // Black (expansion)
...                                // More black entries
```

### Pipeline Placement

```
Line Buffer (4-bit pixel index)
    ↓
[Palette Lookup: readAsync]
    ↓
24-bit RGB (8R, 8G, 8B)
    ↓
TMDS Encoding
    ↓
HDMI Output
```

**Why `readAsync`?**
- Same 1-cycle latency as fixed decode
- No pipeline stall needed
- Natural for small memories (16 entries)

### Benefits of Palette System

1. **Color Animation**: Change palette entries, not tile data
   ```scala
   // Every frame, cycle palette entries
   palette(2) := nextColorInSequence
   ```

2. **Multiple Color Schemes**: Same tiles, different palettes
   - Day mode: Bright colors
   - Night mode: Dark colors
   - Same tile data, different palette!

3. **Flash Effects**: Palette manipulation for effects
   ```scala
   // Flash screen white
   for (i <- 0 to 15) palette(i) := RGB(255, 255, 255)
   ```

4. **Transparency**: Index 0 as transparent (for sprites later)

### Hardware Requirements

```
16 entries × 24 bits = 384 bits = 48 bytes

Gowin will likely infer:
- Distributed RAM (SSRAM/RAM16) - small, fast
- Or small BRAM slice

Cost: Negligible (~50 LUTs if distributed, or <1 BRAM)
```

### Validation Requirements

1. **Visual equivalence**: Output matches Task 9 baseline
2. **Timing**: No additional latency
3. **Startup**: Palette initialized at reset
4. **Flexibility**: Can modify palette at runtime

---

## System Architecture Evolution

### The Complete Data Flow (Tasks 6-10)

```
┌─────────────────────────────────────────────────────────────────┐
│ COMPLETE SYSTEM ARCHITECTURE (Tasks 6-10)                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Phase 1: TILE FETCH (during horizontal blanking)                │
│  ─────────────────────────────────────────────────────────────── │
│                                                                   │
│  Tile Map (BRAM)                                                  │
│  ┌─────────────────┐                                              │
│  │ (x,y) → tileIdx │──┐                                          │
│  └─────────────────┘  │                                          │
│                       ↓                                          │
│  Tile Data (BRAM)     ┌──────────────────┐                       │
│  ┌─────────────────┐  │ tileIdx + pixY   │                       │
│  │ → rowData       │←─┤ → row of pixels  │                       │
│  └─────────────────┘  └──────────────────┘                       │
│                       ↓                                          │
│                       pixelIndex (3b)                             │
│                       ↓                                          │
│  Phase 2: LINE BUFFER FILL                                       │
│  ─────────────────────────────────────────────────────────────── │
│                       ↓                                          │
│                       ┌─────────────────┐                        │
│                       │  Line Buffer A  │←── Fill (4-bit index)  │
│                       │  (640 × 4b)     │                        │
│                       └─────────────────┘                        │
│                                                                   │
│  Phase 3: DISPLAY (during active video)                          │
│  ─────────────────────────────────────────────────────────────── │
│  Line Buffer B                                                    │
│  ┌─────────────────┐                                              │
│  │ → pixelIndex    │──┐                                          │
│  │   (4-bit)       │  │                                          │
│  └─────────────────┘  │                                          │
│                       ↓                                          │
│  Palette (16-entry)   ┌──────────────────┐                       │
│  ┌─────────────────┐  │ lookup[pixIdx]   │                       │
│  │ → RGB (24b)     │←─┤ → RGB value      │                       │
│  └─────────────────┘  └──────────────────┘                       │
│                       ↓                                          │
│  TMDS Encoder         ┌──────────────────┐                       │
│  ┌─────────────────┐  │ 8b→10b encode    │                       │
│  │ → HDMI signals  │←─┤ → serial output  │                       │
│  └─────────────────┘  └──────────────────┘                       │
│                       ↓                                          │
│                    HDMI TX                                       │
│                                                                   │
│  [At line end: Swap Buffer A ↔ B]                                │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### What Each Component Does

| Component | Function | Memory |
|-----------|----------|--------|
| Tile Map | Screen layout | 1,200 × 3 bits = 450 bytes |
| Tile Data | Pixel patterns | 128 × 48 bits = 768 bytes |
| Line Buffer A | Current line storage | 640 × 4 bits = 320 bytes |
| Line Buffer B | Next line storage | 640 × 4 bits = 320 bytes |
| Palette | Color lookup | 16 × 24 bits = 48 bytes |
| **Total** | | **~1.9 KB** |

**Compare to framebuffer**: 640 × 480 × 24 bits = 900 KB!

---

## What's Next

### Task 11: Sprite Pipeline

**Concept**: Overlay moving objects on the background.

```
Background (tiles)  ← Current system
     ↓
Sprite overlay      ← New: Draw sprites on top
     ↓
Final pixel output
```

**Challenge**: Multiple sprites per line, priority, transparency.

### Task 12: Sprite Priority / Transparency

Handle sprite-to-sprite and sprite-to-background ordering.

### Task 13: Multi-Layer Composition

Combine multiple background layers (parallax scrolling).

### Task 14+: SDRAM Integration

Move tile data to external memory for larger worlds.

---

## Summary

### What You Have After Tasks 8-10

1. **Task 8**: Infinite scrolling world without seams
2. **Task 9**: Timing-decoupled display (SDRAM-ready)
3. **Task 10**: Programmable colors (animation-ready)

### The Architecture is Now:
- **Memory-efficient**: Tiles + palette instead of framebuffer
- **Flexible**: Colors programmable without tile changes
- **Scalable**: Line buffer enables external memory
- **Game-console-classic**: Same architecture as NES/SNES/Genesis

---

**Document Version**: 1.0  
**Author**: CoralReef (Teach-back lane)  
**Last Updated**: 2026-04-11
