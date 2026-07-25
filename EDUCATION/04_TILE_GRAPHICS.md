# Tile-Based Graphics Architecture

This document describes the design, address generation, and line-buffering pipeline of the VDP tile-graphics processor.

---

## 1. Why Tile-Based Graphics?

A full 640×480 screen at 8-bit color depth requires **307.2 KB** of video RAM (VRAM). This is too large to fit in the on-chip block RAMs of smaller FPGAs (like the Gowin GW2AR-18, which has only 46 Block RAMs total, about 90 KB).

Tile-based graphics solve this by decomposing the screen into a grid of reusable **Tiles** (patterns) referenced by a **Tile Map**:
* **Grid**: 40×30 cells of 16×16 pixel tiles.
* **Tile Map**: Stores a 1,200-byte array of indices (each byte points to one of 256 unique tile patterns).
* **Pattern Memory**: Stores the actual pixel bitmaps for the 256 patterns. An 8-pixel pattern only needs $16 \times 16 = 256$ bits (for 1bpp), keeping memory footprint tiny.

---

## 2. Address Generation and Scrolling

To scroll the screen, hardware adds X and Y scroll offsets (`scroll_x`, `scroll_y`) to the current display coordinate (`hCounter`, `vCounter`).

```scala
val scrolledX = (hCounter.resize(10) + scroll_x).resize(10)
val scrolledY = (vCounter.resize(9) + scroll_y).resize(9)
```

### Tile Coordinate Mapping
To find which tile index to read, we divide the scrolled coordinate by 16 (shift right by 4):
```scala
val tileX = scrolledX(9 downto 4) // X tile coordinate (0 to 39)
val tileY = scrolledY(8 downto 4) // Y tile coordinate (0 to 29)
```

### Wrapping (Modulo Arithmetic)
To ensure smooth infinite scrolling without drawing out-of-bounds, coordinates must wrap cleanly at screen boundaries. Modulo arithmetic ensures the scroll offsets wrap back to 0:
```scala
// Tile map dimensions: 40 tiles wide, 30 tiles high
val wrappedTileX = (tileX < 40) ? tileX | (tileX - 40)
```
In modern VDP layers, wrapping is integrated into SDRAM fetching, so the memory controller requests wrapped line segments automatically to avoid horizontal split-seam glitches.

---

## 3. The Video Pipeline

The VDP processes pixels in a pipeline to balance memory lookup latency:

```
[Raster Timers] ──→ [SDRAM Tile / Attr Fetch] ──→ [Pattern Memory ROM Lookup] ──→ [Palette Mapper] ──→ [HDMI Output]
```

1. **SDRAM Fetch**: The SDRAM controller fetches tile map indices and attribute data ahead of the active scanline.
2. **ROM Lookup**: The tile map index, combined with the current fine Y line offset (`scrolledY(3 downto 0)`), calculates the address to read the raw tile pattern bits.
3. **Palette Mapper**: The raw pattern index (e.g., 2bpp color bits) is mapped to one of the 4 programmable palette colors to produce a 12-bit (RGB444) or 16-bit (RGB565) color value.

---

## 4. Line Buffering (`LinestateStore`)

To prevent the video scanout from stalling while waiting for SDRAM memory bursts (which can suffer from arbitration delays and refresh cycles), the VDP uses a **double-buffered scanline buffer**.

```
           Scanline N-1 (Visible)                     Scanline N (Prefetch)
     ┌─────────────────────────────────┐        ┌─────────────────────────────────┐
     │      Line Buffer A (Read)       │        │      Line Buffer B (Write)      │
     │   Outputs pixels to compositor  │        │   Fills with next row data      │
     └─────────────────────────────────┘        └─────────────────────────────────┘
                      ▲                                          ▲
                      │                                          │
                  clk_pixel                                  clk_sdram
```

* **Ping-Pong Buffer**: While `Line Buffer A` is being read by the pixel scanner clock domain (`clk_pixel`) to output pixels to the display, `Line Buffer B` is being written to by the SDRAM controller clock domain (`clk_sdram`) with prefetched data for the next scanline.
* **Domain Isolation**: This architecture isolates the high-latency asynchronous SDRAM clock domain crossing from the critical real-time video timing domain, eliminating temporal shimmer and flicker.
* **Sync Registers**: The line buffers use dual-port block RAMs with synchronized read/write indicators to safely pass memory ownership back and forth at the horizontal blanking boundary.
