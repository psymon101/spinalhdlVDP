# Tech Spec: VDP Host Interface & Copper Coprocessor

> [!WARNING]
> **Implementation Note (2026-06-13)**: The register address map in Section 3.4 is an architectural summary and is **STALE**. Refer to [`MODE0_REGISTER_BUS_SPEC.md`](MODE0_REGISTER_BUS_SPEC.md) for the authoritative hardware-proven address map. Section 3.2 and 3.3 describe the current i80 protocol and readback semantics.

**Version:** 0.1  
**Date:** 2026-04-13  
**Author:** CoralReef  
**Status:** DONE — Host interface and Copper architecture implemented, audited, and integrated (R5, Tasks 24–25, 33)

---

## 1. Purpose

This document translates findings from an open-source FPGA GPU/graphics survey (RasterIX, icestation-32, Gameduino, Project F, f32c) into concrete architecture decisions for `spinalhdlVDP`. It covers two major areas:

1. **Host Interface Architecture** — how the external host (i80 / QSPI / MCU) communicates with the VDP
2. **Copper Coprocessor** — a minimal mid-frame register-write engine for raster effects

A third section defines a **Working-Set Caching Policy** for upcoming planar/shuffled fetch modes.

---

## 2. Background & Motivation

Our current VDP (`VdpTop`) exposes raw linestate registers directly. When the host control surface was first implemented (Task 24, originally QSPI), a naive memory-mapped approach created several risks:

- **Host can corrupt state mid-line** — no timing isolation
- **Host must stall for VDP timing** — every write is synchronous
- **No burst efficiency** — host must arbitrate per register

The surveyed projects solve this with an **indirect register access model** (icestation-32, Gameduino) or a **command FIFO** (RasterIX). Both approaches decouple the host from the pixel pipeline. The current canonical Tang Nano 20K implementation uses the i80 8-bit parallel bus with the same indirect register model.

Additionally, every advanced 2D VDP in the survey (icestation-32, Gameduino, Sega Genesis) includes a **copper** or coprocessor for mid-frame register updates. Our R1 Raster Trigger Unit is the seed of this capability. Formalizing it now prevents a later rewrite when raster effects expand beyond simple linestate commits.

---

## 3. Host Interface Architecture

### 3.1 Design Principle

> The host **writes** VDP state through an 8-bit parallel i80 bus. Reads are limited to loopback of the last-written register value and a small set of debug/status words. The VDP applies writes at safe boundaries (line start or vblank), never mid-line.

The canonical implementation uses an ESP32-S3 driving the i80 bus. The legacy QSPI path (Pico 2, older ESP32/ESP8266) is retired from active development but remains documented in [`archive/QSPI_HOST_CONTROL_PLAN.md`](archive/QSPI_HOST_CONTROL_PLAN.md).

### 3.2 i80 Protocol

The host interface is an Intel-8080-style parallel bus: 8 data lines (`D0..D7`), chip-select (`CS#`), read strobe (`RD#`), write strobe (`WR#`), and data/command select (`DC#`). `DC#` is low during opcode/address phases and high during data phases.

**Transaction opcodes** (sent on D0..D7 while DC#=0, WR# pulsed):

| Opcode | Name | Direction | Payload phases | Description |
|--------|------|-----------|----------------|-------------|
| `0x00` | `REG_WRITE` | Host → VDP | addr[15:0] (DC=0), data[15:0] (DC=1) | Write one 16-bit word to the 15-bit VDP register bus. |
| `0x01` | `REG_READ`  | Host ← VDP | addr[15:0] (DC=0), data[15:0] (DC=1) | Read back data for the given address. |
| `0x02` | `SDRAM_WRITE` | Host → VDP | addr[15:0], len[15:0], then `len+1` data words | Block write to SDRAM via the upload path. |

A register write is therefore: `opcode` → `addr_lo` → `addr_hi` → `data_lo` → `data_hi`. The library facade in `firmware/libvdp/vdp_i80.h` hides this byte-level framing.

### 3.3 Readback Semantics

The i80 read path is **loopback-oriented**, not a full register-file readback:

- **Most register addresses** return the **last value written to that register** (latched `regBus.data`). This is sufficient for host shadow verification.
- **Address-independent readback is expected** for many registers; do not compare POR defaults across unrelated addresses.
- **`0x0328` / `0x0329`** return armed SDRAM debug data (read-only debug aperture).
- **Status readback** on legacy QSPI builds is performed through the `READ_STATUS` response surface (selector-based), not through the register bus. See `MODE0_REGISTER_BUS_SPEC.md` for the status selector mapping. **Note:** the i80 RTL path does not currently decode the `READ_STATUS` opcode (`0x04`); i80 hosts must poll status through normal register reads where those registers exist (e.g., `0x0320` for sticky status).

> **Note:** Because reads return the last-written value, a write followed immediately by a read to the **same** address should return the value just written. This is the criterion used by `firmware/esp32s3_i80_smoke`.

### 3.4 VDP Address Space

> [!WARNING]
> **Section 3.3 address map is an architectural proposal and is STALE.** Refer to [`MODE0_REGISTER_BUS_SPEC.md`](MODE0_REGISTER_BUS_SPEC.md) for the authoritative hardware-proven address map.

The authoritative register map is canonically defined in `firmware/libvdp/mode0_regs.json` and rendered into `MODE0_REGISTER_BUS_SPEC.md` §3.1. Key regions include:

| Range | Resource |
|-------|----------|
| `0x0300..0x031F` | Global control (`LAYER_ENABLE`, `VDP_CTRL`, `STATUS_STICKY`, ...) |
| `0x0320..0x032F` | Status / sticky / upload status |
| `0x0330..0x034F` | Window / color / affine |
| `0x0350..0x037F` | Bitmap / fetch / raster config |
| `0x0380..0x0AFF` | Automation / tables (Copper, HDMA, scroll, sprite tables) |
| `0x0B00..0x0DFF` | DMA / Blitter |
| `0x1000..0x17FF` | Palette RAM |

### 3.5 Command FIFO

A small CDC FIFO sits between the i80 host clock domain and the VDP pixel clock domain. This allows the host to issue multiple register writes in quick succession without stalling.

```
Host (i80 clock) → i80 adapter → Command FIFO → VDP CommandParser (pixel clock)
```

FIFO entry format: `{addr[14:0], data[15:0]}` = 31 bits.

**FIFO / CommandParser Contract:**

1. **Queue contents**: The FIFO holds only **register-write queue entries** (`{addr[14:0], data[15:0]}`), not a generic command stream.
2. **Ordering**: Host writes preserve strict FIFO order.
3. **Drain rate**: The pixel-domain `CommandParser` emits **one write per cycle** while the drain window is open (`hCounter === 0` or during vblank).
4. **Vblank behavior**: During vertical blanking, writes may drain continuously without buffering.

### 3.6 Safe-Boundary Application

A `CommandParser` module in the pixel clock domain consumes the FIFO:

- During **active video**: entries are buffered but NOT applied
- At **`hCounter === 0`** (start of visible line, immediately after the line-buffer swap): all buffered entries are applied atomically
- During **vblank**: entries are applied continuously

This guarantees that no host write can change linestate mid-line. The boundary was moved from `hTotal-1` to `hCounter === 0` to avoid a race with the R4.1 fetch-slot scheduler grant, which fires at `hTotal-1`.

### 3.7 Benefits

| Concern | How this solves it |
|---------|-------------------|
| Mid-line corruption | Writes applied only at line boundary |
| Host stalling | FIFO absorbs burst writes |
| CDC safety | FIFO is the sole crossing point |
| Code simplicity | Host uploads data with simple loops; VDP owns timing |
| Throughput | 8-bit parallel i80 is faster than the retired QSPI nibble path for bulk uploads |

### 3.8 Retired QSPI Path

The original host interface was a 4-wire QSPI bus with a 6-byte header `[CMD:1][ADDR:3][LEN:2]`. It was proven on Pico 2, ESP32-S3, and ESP8266, but was retired as the canonical path when i80/ESP32-S3 became the baseline. Detailed QSPI history is preserved in [`archive/QSPI_HOST_CONTROL_PLAN.md`](archive/QSPI_HOST_CONTROL_PLAN.md).

---

## 4. Copper Coprocessor

### 4.1 Design Principle

> A minimal 4-instruction coprocessor executes from its own program RAM and writes VDP registers at exact raster positions. It runs in the pixel clock domain and shares the same register-write path as the host CommandParser.

### 4.2 Instruction Set

Copper instructions are 16-bit words. The current implementation (see `Copper.scala`) supports WAIT, WRITE, WRITE_SEQ, JUMP, and SKIP.

| Op | Enc | Description | Bit Layout | Cycles | Typical Use |
|----|-----|-------------|------------|--------|-------------|
| `WAIT` legacy | `00` | Stall until `vCounter==Y && hCounter==0` | `00 0 000 Y[9:0]` | 1 | Line-accurate beam sync |
| `WAIT` extended | `00` | Stall until `vCounter==Y && hCounter==X` | `00 1 00 X[9:0]` then `000000 Y[9:0]` | 2 | Pixel-precise beam sync |
| `WRITE` | `01` | Write one 16-bit value to a VDP register | `01 addr[13:0]` then `data[15:0]` | 2 | Single register poke |
| `WRITE_SEQ` | `10` | Write `N` consecutive 16-bit values | `10 count_m1[2:0] addr[10:0]` then `N` data words | 1 + N | Palette/bar bursts |
| `JUMP` | `11` | Unconditional jump | `11 0 000 targetPC[8:0]` | 1 | Loop restart |
| `SKIP` | `11` | Conditional skip | `11 1 xxxxx cond[2:0] offset[4:0]` | 1 | Branchless raster logic |

**Field definitions:**
- `Y` / `X` / `TGT`: 10-bit raster coordinate.
- `addr`: register bus address (14 bits for `WRITE`, 11 bits for `WRITE_SEQ`).
- `data`: 16-bit register value.
- `count_m1`: burst length minus one (`0..7`), so `N = count_m1 + 1` (`1..8` words).
- `targetPC`: 9-bit program word address for `JUMP`.
- `cond`: 3-bit condition code for `SKIP` (uses the `TR0` raster-trigger registers as compare inputs).
- `offset`: 5-bit skip offset counted in **program words**.

> [!NOTE]
> A `WRITE` consumes **two program words**: the header word encodes the register address, and the following word is the 16-bit data value. A `WRITE_SEQ` of one value (`count_m1 = 0`) is the canonical way to emit a single 16-bit write.

### 4.3 Copper Program RAM

- Size: **1 KiB** — two banks of **512** × 16-bit words each.
- Bank A/B are mapped to host address space at **0x0400–0x05FF**.
- Host writes always use the `0x0400..0x05FF` aperture; the hardware routes them based on the copper enable state:
  - **Copper disabled:** writes land in the **active** bank (the bank that will execute when enabled).
  - **Copper enabled:** writes land in the **inactive** bank (the bank that will execute after the next swap).
- Copper fetches from the active bank when `copper_enable = 1`.
- Maximum visible program is 512 words; use `JUMP` loops for longer effects.

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

### 6.1 Files Created / Implemented
- `I80HostInterface.scala` / `I80Pads.scala` — i80 adapter + Command FIFO + CommandParser
- `Copper.scala` — copper core + program RAM interface
- `TopTang20kI80.scala` — i80 top-level instantiation
- `firmware/libvdp/vdp_i80.h`, `vdp_i80.c` — ESP32-S3 i80 transport facade

### 6.2 Files Modified
- `TopTang20kHdmi.scala` / `TopTang20kI80.scala` — instantiate host interface and Copper, wire to VDP
- `VdpTop.scala` — unified register-write bus from CommandParser/Copper
- `FetchSlotScheduler.scala` — widened slot windows for cache-burst loads as needed

### 6.3 Backward Compatibility

- The i80 and legacy QSPI host interfaces share the same internal register-write path.
- The existing `layer0TestPatternEnable` / `layer0TestPatternSelect` interface remains unchanged.
- All existing simulations continue to pass.

---

## 7. Open Questions

1. **i80 clock rate**: The ESP32-S3 GPIO bit-bang backend is validated at the cadence used by `vdp_i80.h`. A hardware LCD_CAM i80 backend may allow higher throughput but is not yet the documented baseline.
2. **Palette width**: Should we upgrade the palette from 24-bit RGB to 16-bit A1R4G4B4 (matching icestation-32) to save BRAM? Or keep 24-bit for color fidelity?
3. **Copper RAM size**: Is 1KB (512 instructions) sufficient, or should we allocate 2KB?
4. **Task ordering**: Copper and the host interface share the same safe-boundary register-write path and were implemented together.

---

## 8. Recommendations

### Immediate (current state)
- The i80 host interface + Copper are implemented and proven. Use `firmware/libvdp/vdp_i80.h` for new ESP32-S3 firmware.
- Use the 8-entry Command FIFO with safe-boundary application.

### Near-term
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
