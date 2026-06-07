# Tech Spec: VDP Host Interface & Copper Coprocessor

**Version:** 0.1  
**Date:** 2026-04-13  
**Author:** CoralReef  
**Status:** DONE — Host interface and Copper architecture implemented, audited, and integrated (R5, Tasks 24–25, 33)

---

## 1. Purpose

This document translates findings from an open-source FPGA GPU/graphics survey (RasterIX, icestation-32, Gameduino, Project F, f32c) into concrete architecture decisions for `spinalhdlVDP`. It covers two major areas:

1. **Host Interface Architecture** — how the external host (QSPI / MCU) communicates with the VDP
2. **Copper Coprocessor** — a minimal mid-frame register-write engine for raster effects

A third section defines a **Working-Set Caching Policy** for upcoming planar/shuffled fetch modes.

---

## 2. Background & Motivation

Our current VDP (`VdpTop`) exposes raw linestate registers directly. When Task 24 (QSPI Control Surface) is implemented, a naive memory-mapped approach creates several risks:

- **Host can corrupt state mid-line** — no timing isolation
- **Host must stall for VDP timing** — every write is synchronous
- **No burst efficiency** — host must arbitrate per register

The surveyed projects solve this with an **indirect register access model** (icestation-32, Gameduino) or a **command FIFO** (RasterIX). Both approaches decouple the host from the pixel pipeline.

Additionally, every advanced 2D VDP in the survey (icestation-32, Gameduino, Sega Genesis) includes a **copper** or coprocessor for mid-frame register updates. Our R1 Raster Trigger Unit is the seed of this capability. Formalizing it now prevents a later rewrite when raster effects expand beyond simple linestate commits.

---

## 3. Host Interface Architecture

### 3.1 Design Principle

> The host **writes** VDP state through an indirect (address + data + auto-increment) interface. Reads are limited to a small status register set. The VDP applies writes at safe boundaries (line start or vblank), never mid-line.

### 3.2 Register Interface

The QSPI adapter presents the following MMIO registers to the host:

| Offset | Name | Width | Access | Description |
|--------|------|-------|--------|-------------|
| 0x00 | `VDP_ADDR` | 16 | RW | Target address in VDP address space |
| 0x02 | `VDP_DATA` | 16 | W | Write data; triggers FIFO enqueue |
| 0x04 | `VDP_INC` | 8 | RW | Auto-increment value after each write (default = 1) |
| 0x06 | `VDP_STATUS` | 8 | R | `{fifo_full, fifo_empty, vblank, line[9:2]}` |
| 0x08 | `HOST_CTRL` (`VDP_CTRL` in current code) | 8 | RW | `{irq_enable, copper_enable, flush_fifo}` — host-side control shadow register |

> **Layering clarification:** `hostAddr` selects **host-side shadow/status registers** (`VDP_ADDR`, `VDP_DATA`, `VDP_INC`, `VDP_STATUS`, `HOST_CTRL`). Only writes to `VDP_DATA` enqueue entries into the internal VDP register-space FIFO. The target address for those queued writes is the value held in the host-side `VDP_ADDR` shadow register. The internal VDP register space (accessed by the pixel-domain `CommandParser`) is a separate 15-bit address map; `0x0310` inside that map is the VDP register-space control register consumed by `VdpTop`.

### 3.3 VDP Address Space

Addresses 0x0000–0x7FFF map into the VDP's internal register space (the destination of queued FIFO writes):

| Range | Resource |
|-------|----------|
| 0x0000–0x0FFF | Linestate table (480 lines × 16-bit words) |
| 0x1000–0x17FF | Palette RAM (128 entries × 16-bit, A1R4G4B4 or R4G4B4) |
| 0x2000–0x27FF | Scroll table / raster config |
| 0x3000–0x3FFF | Copper program RAM |
| 0x4000–0x4FFF | Sprite attribute RAM |
| 0x5000–0x5FFF | Tile map / VRAM window |
| 0x6000–0x7FFF | Reserved |

### 3.4 Command FIFO

A small FIFO (8–16 entries) sits between the QSPI clock domain and the VDP pixel clock domain. This allows the host to burst multiple `(addr, data)` pairs without stalling.

```
Host (QSPI clock) → QSPI adapter → Command FIFO → VDP parser (pixel clock)
```

FIFO entry format: `{addr[14:0], data[15:0]}` = 31 bits.

**FIFO / CommandParser Contract:**

1. **Queue contents**: The FIFO holds only **register-write queue entries** (`{addr[14:0], data[15:0]}`), not a generic command stream.
2. **Ordering**: Host writes preserve strict FIFO order. A `VDP_DATA` write enqueues the current `VDP_ADDR` value paired with the data word.
3. **Drain rate**: The pixel-domain `CommandParser` emits **one write per cycle** while the drain window is open (`hCounter === 0` or during vblank).
4. **Vblank behavior**: During vertical blanking, writes may drain continuously without buffering.
5. **`flush_fifo`**: The bit is named in the `HOST_CTRL` register map but is **not yet implemented** in the current codebase. When implemented, the intended semantics are host-domain FIFO reset (discard all queued entries). Until then, hosts must avoid overflow by monitoring `fifo_full`.

### 3.5 Safe-Boundary Application

A `CommandParser` module in the pixel clock domain consumes the FIFO:

- During **active video**: entries are buffered but NOT applied
- At **`hCounter === 0`** (start of visible line, immediately after the line-buffer swap): all buffered entries are applied atomically
- During **vblank**: entries are applied continuously

This guarantees that no host write can change linestate mid-line. The boundary was moved from `hTotal-1` to `hCounter === 0` to avoid a race with the R4.1 fetch-slot scheduler grant, which fires at `hTotal-1`.

### 3.6 Benefits

| Concern | How this solves it |
|---------|-------------------|
| Mid-line corruption | Writes applied only at line boundary |
| Host stalling | FIFO absorbs burst writes |
| CDC safety | FIFO is the sole crossing point |
| Code simplicity | Host uploads data with simple loops; VDP owns timing |

---

## 4. Copper Coprocessor

### 4.1 Design Principle

> A minimal 4-instruction coprocessor executes from its own program RAM and writes VDP registers at exact raster positions. It runs in the pixel clock domain and shares the same register-write path as the host CommandParser.

### 4.2 Instruction Set

Copper instructions are 16 bits.

| Op | Enc | Description | Bit Layout | Cycles | Typical Use |
|----|-----|-------------|------------|--------|-------------|
| `WAIT` | `00` | Block until raster matches target | `00 WT Y TGT[9:0]` | 1 | Beam-sync trigger |
| `WRITE` | `01` | Write 8-bit data to VDP register | `01 -- reg[5:0] data[7:0]` | 1 | Single register poke |
| `WRITE_SEQ` | `10` | Write `N+1` words from program RAM | `10 ITY BAS N reg[5:0]` | N+1 | Palette/bar updates |
| `JUMP` | `11` | Unconditional jump | `11 -- addr[10:0]` | 1 | Loop restart |

**Field definitions:**
- `WT`: 1 = block until match; 0 = set target, continue
- `Y`: 0 = target X, 1 = target Y
- `TGT`: 10-bit raster coordinate
- `reg`: 6-bit VDP register address
- `data`: 8-bit immediate
- `BAS`: increment mode (00 = same, 01 = +1, 10 = +2, 11 = +4)
- `N`: batch count minus one
- `ITY`: interlaced Y mode (auto-increment Y and WAIT between batches)
- `addr`: 11-bit program RAM address

**16-bit writes:** Use `WRITE_SEQ` with `N=0`.

### 4.3 Copper Program RAM

- Size: 2 kB (**1024** × 16-bit words)
- Mapped to host address space at 0x0400–0x07FF
- Host writes programs when `copper_enable = 0`
- Copper reads programs when `copper_enable = 1`

### 4.4 Execution Model

1. When `copper_enable` rises, PC resets to 0 and execution starts
2. The copper maintains a 10-bit target X and 10-bit target Y
3. `WAIT` compares `(hCounter, vCounter)` against the target
4. When matched, the copper advances to the next instruction
5. `WRITE` / `WRITE_SEQ` enqueue into the same **safe-boundary register-write path** used by the host CommandParser
6. `JUMP` restarts loops (e.g., for per-line palette bars)

### 4.5 Typical Effects

| Effect | Copper program |
|--------|---------------|
| Palette swap per scanline | `WAIT y=N; WRITE palette_addr, color; JUMP start` |
| Layer mode switch mid-frame | `WAIT y=240; WRITE layer_enable, 0x02` |
| Parallax scroll update | `WAIT y=N; WRITE_SEQ hscroll_0..hscroll_3, data...` |

### 4.6 Why This Scope

icestation-32's copper has exactly these 4 instructions and achieves:
- palette-bar demos
- affine/scroll layer switching mid-frame
- copper polygon effects

This is sufficient for Mode0 and avoids the complexity of a general-purpose CPU.

### 4.7 Prior-Art Comparison — Copper Capability

| Feature | Amiga Copper | Xosera | VERA | **Ours (Mode0)** |
|---------|-------------|--------|------|------------------|
| **Pixel-precise `WAIT(X,Y)`** | ✅ Yes | ❌ No | ❌ No | ✅ Yes |
| **Burst writes (`WRITE_SEQ`)** | ❌ No | ❌ No | ❌ No | ✅ Yes (8 words) |
| **HDMA integration** | ❌ No | ❌ No | ❌ No | ✅ Yes (4 channels) |
| **Program RAM size** | ~2 KB (Chip RAM) | 512 words | 512 words | **512 words × 2 banks** (double-buffered) |
| **Instruction width** | 32-bit (MOVE/WAIT) | 16-bit | 16-bit | **16-bit** |
| **Host upload while running** | No (list in Chip RAM) | No | No | **Yes** (routes to inactive bank) |
| **Atomic bank swap** | N/A | N/A | N/A | **Yes** (at `vSyncStart`) |

> **Note:** This table captures the *Copper coprocessor* dimension only. It does not claim overall VDP superiority — each peer makes different trade-offs in memory model, blitter tier, and host interface (see `DESIGN_NOTE_CHUNKY_CORE_PLANAR_COMPAT.md` §8 for the full VDP-level prior-art comparison).

**Sources:**
- Amiga: HRM Copper chapter; MOVE + WAIT only, no burst, no HDMA
- Xosera: `xosera_pkg.sv` — copper has WAIT + MOVE, no WRITE_SEQ
- VERA: `vera_module.v` — raster IRQ only; no programmable copper
- Ours: `Copper.scala`, `vdp_copper.h` — 4-instruction set with WRITE_SEQ and double-buffer

---

## 5. Working-Set Caching Policy (Planar / Shuffled / Affine)

### 5.1 Design Principle

> Never stream individual pixels from SDRAM during active rasterization. Always burst a working set into on-chip memory during a blanking window, then render from the on-chip cache.

### 5.2 Justification

- **RasterIX**: Each TMU has a 128KB texture buffer. Texels are read from on-chip memory, not SDRAM.
- **icestation-32**: No external SDRAM at all; all graphics live in 64KB VDP RAM.
- **Gameduino**: Tile graphics are entirely in block RAM.

### 5.3 Proposed Cache Architecture

For modes that need irregular access (planar bitplanes, shuffled layouts, affine transformations):

```
SDRAM → Burst Loader → Tile Cache (BRAM) → Raster Pipeline
```

- **Tile Cache**: 8–32 tiles × 16×16 × 4bpp = 1KB–4KB
- **Burst Loader**: Runs during hblank or vblank, filling the cache with the tiles needed for the upcoming scanline
- **Raster Pipeline**: Reads tiles from BRAM with single-cycle latency

### 5.4 Cache Coherence Rule

The cache is **read-only** during the active line. It is only updated during:
- Horizontal blanking (for narrow working sets)
- Vertical blanking (for full frame cache refreshes)
- Linestate commit strobe (for scroll-triggered cache invalidation)

---

## 6. Impact on Existing Code

### 6.1 Files to Create
- `HostInterface.scala` — QSPI adapter + Command FIFO + CommandParser
- `Copper.scala` — copper core + program RAM interface
- `HostInterfaceSim.scala` / `CopperSim.scala` — simulation entries

### 6.2 Files to Modify
- `TopTang20kHdmi.scala` — instantiate `HostInterface` and `Copper`, wire to VDP
- `VdpTop.scala` — replace raw linestate write ports with unified register-write bus from CommandParser/Copper
- `FetchSlotScheduler.scala` — optionally widen slot windows for cache-burst loads

### 6.3 Backward Compatibility

- When `HostInterface` is disabled, VDP falls back to internal defaults (same behavior as today)
- The existing `layer0TestPatternEnable` / `layer0TestPatternSelect` interface remains unchanged
- All existing simulations continue to pass

---

## 7. Open Questions

1. **QSPI vs. SPI bitrate**: What is the expected host clock? If the host is much slower than 25.2 MHz, the FIFO can be smaller. If it is faster, we may need a 16- or 32-entry FIFO.
2. **Palette width**: Should we upgrade the palette from 24-bit RGB to 16-bit A1R4G4B4 (matching icestation-32) to save BRAM? Or keep 24-bit for color fidelity?
3. **Copper RAM size**: Is 1KB (512 instructions) sufficient, or should we allocate 2KB?
4. **Task ordering**: Should the copper be implemented **before** or **together with** the QSPI host interface? They share the same register-write path, so implementing them together is efficient.

---

## 8. Recommendations

### Immediate (next task)
- **Implement Host Interface + Copper as a single bounded task** (call it **R5** or fold into Task 24). They share the safe-boundary register-write path and should not be split.
- Adopt the indirect address+data+auto-increment register model for host access.
- Provide 8-entry Command FIFO with safe-boundary application.

### Near-term (following 1–2 tasks)
- Apply the Working-Set Caching Policy to planar/shuffled mode implementations.
- Begin unifying sprite and background tile memory formats.

### Avoid
- Direct memory-mapped linestate without a FIFO or safe-boundary parser
- Generic CPU/coprocessor inside the copper (4 instructions is sufficient)
- Per-pixel SDRAM fetches for any rasterized element

---

## 9. References

- **icestation-32** — `doc/platform.md` (copper, VDP register map, indirect access)
- **Gameduino** — `toivoh/gameduino-fpga-mods` (line-buffer pipeline, sprite FIFO, host memory map)
- **RasterIX** — `ToNi3141/RasterIX` (stream-centric command parser, texture buffers, deferred pipeline)
- **Project F** — `projf/display_controller` (resolution-agnostic timing, test patterns)
- **f32c** — `f32c/f32c` (multi-port SDRAM, async video clock domain)
