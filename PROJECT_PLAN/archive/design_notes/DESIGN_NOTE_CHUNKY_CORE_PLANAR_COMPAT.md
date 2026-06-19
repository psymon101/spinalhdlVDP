# Design Note: Chunky-Core VDP — Planar as Optional Compatibility Layer

**Status:** Design capture — NOT committed RTL  
**Date:** 2026-06-07  
**Author:** BrightForge (architectural exploration); CoralReef (doc capture)  
**Source:** Owner-directed handoff, thread #11946  
**Authority:** Captured architecture for roadmap visibility. Lane authorization required before any implementation.

---

## 1. Executive Summary

A bitplane image and a chunky image are two **encodings of the same per-pixel index grid**. Any planar picture = some chunky picture, pixel-identical. The choice is memory layout, not capability.

**Recommendation:** Keep the chunky core (tile/bitmap layers, sprites, palette, Copper, scaler) as the **documented public API**. Do NOT expose bitplanes in the contract. Planar becomes an **optional compatibility layer** (§6) rather than a first-class citizen.

On modern bandwidth-bound hardware, chunky is also **cheaper to fetch**: chunky tile ~10–15 SDRAM reads/line vs planar 5-plane ~50 reads/line (the bandwidth wall behind the per-line-fetch debate #11909). Planar's advantage was a 1980s DRAM-layout artifact; it is a liability here.

---

## 2. Atari ST Adapter Mapping

The ST video model (low 320×200/4-plane/16-col, med 640×200/2-plane/4-col, high 640×400/1-plane/mono) maps cleanly onto Mode0 primitives. This section documents the mapping for a future host-side `libvdp` adapter.

### 2.1 ST-Specific Behaviours to Translate

| ST-ism | Translation |
|--------|-------------|
| **Interleaved bitplanes** (word-interleaved per 16-px group: [p0][p1][p2][p3]…) | De-interleave, then feed chunky backend (§2.2) |
| **16-entry palette** (9-bit STF / 12-bit STE) | Convert via `PALETTE_PTR`/`PALETTE_DATA`; bit-replicate 3-bit→8-bit; handle STE rotated-LSB quirk |
| **Per-scanline palette swaps ("rasters")** | Copper program rewriting palette per line |

> **Note:** ST interleaving is DIFFERENT from Amiga's separate contiguous planes and from our planar path's separate plane bases.

### 2.2 Resolution / Aspect Preservation

ST low-res is 320 wide = our `PLANE_PIXELS=320` planar window, and ST 4 planes is a subset of our 5. The planar path that produces red stripes on 640-wide content is actually **shaped for ST content** (no clip-to-garbage at 320).

| Mode | Native | Scaled | Letterbox in 480 | Aspect preserved? |
|------|--------|--------|------------------|-------------------|
| Low | 320×200 | 2× → 640×400 | Yes | Yes (tall pixels) |
| Medium | 640×200 | 2× → 640×400 | Yes | Yes (tall pixels) |
| High | 640×400 | 1× → 640×400 | Native | Yes (square pixels) |

**Do not stretch-to-fill.** That is an accuracy bug. Use the scaler + inner-border (hardware-proven in #11939) to preserve the ST's non-square (tall) pixels.

### 2.3 Backend Choice

| Backend | Pros | Cons | Recommendation |
|---------|------|------|----------------|
| **A — Planar backend** | Zero host CPU cost for planar content | Bandwidth wall; left-half-only at 640 | ❌ Deprecated path |
| **B — Chunky backend** | Robust; no clip artifacts; matches public API | Host must de-interleave once | ✅ Preferred |

---

## 3. Per-Plane Tricks & Why Chunky Costs More

ST per-plane scroll (e.g. "scroll just plane 3") is software bit-shifting that plane's words: 68k `ROXL`/`ROXR` (rotate THROUGH eXtend, NOT plain `ROL`/`ROR` — you must thread the carry across the 16-px word boundaries), walking the interleave stride (plane 3 = every 4th word = stride 8 bytes in low-res). Used for the plane-as-overlay idiom (planes 0–2 = bg, plane 3 = independent overlay).

| Backend | Per-plane scroll cost |
|---------|----------------------|
| **Planar backend** | Cheap: shift/re-upload one plane, or a per-plane scroll-offset reg = free in RTL |
| **Chunky backend** | Expensive: plane bits are MERGED into packed indices, so reproducing plane-3-scroll = whole-frame read-modify-write + re-upload |

**Better-native answer:** The overlay idiom maps onto our real L0/L1 layers + hardware scroll regs (one register write), not plane partitioning. Dual-playfield, parallax, fades → use layers/ColorMath, not bitplane tricks.

---

## 4. Visual-Accuracy Priorities

Most of what makes content "look like an ST" is **post-fetch or timing**, i.e. backend-independent:

| Priority | Accuracy driver | Mode0 primitive | Effort |
|----------|----------------|-----------------|--------|
| 1 | Correct pixels/frame | Chunky = planar (no difference) | Zero |
| 2 | 16-col palette + per-scanline rasters | **Copper** (highest-leverage accuracy feature; this is where the "ST look" lives) | One Copper program |
| 3 | Resolution modes + non-square aspect + integer scale | Scaler + inner-border | Proven |
| 4 | Scroll cadence (50 Hz feel) | Display timing | Native |
| 5 | Content-agnostic per-plane tricks | — | ONLY forces planar |

> **Conclusion:** Accuracy investment should go into Copper rasters, aspect/timing, palette fidelity — NOT into resurrecting the on-chip planar fetch. Lead with chunky+layers+Copper; treat per-plane tricks as a documented optional mode.

---

## 5. Chunky Pixel Writes (Grounded in Registers)

Bitmap framebuffer lives in SDRAM.

| Register | Address | Purpose |
|----------|---------|---------|
| `BITMAP_CTRL` | `0x0350` | enable + `bpp_sel[2b]` + cell width |
| `BITMAP_BASE_LO` | `0x0351` | framebuffer base (low) |
| `BITMAP_BASE_HI` | `0x0352` | framebuffer base (high) |
| `BITMAP_STRIDE` | `0x0355` | bytes per scanline |

Setting a pixel = SDRAM write (via the `SDRAM_WRITE` transport), NOT a register write.

- **RGB565 directcolor** (2 B/px): write color directly (palette bypassed).
- **Indexed 4bpp**: `base + y*stride + (x>>1)`, set the nibble; color via palette.

### 5.1 The RMW Gotcha

SDRAM bus is 32-bit; a pixel is sub-word (RGB565 = half a word, 4bpp = 1/8). The transport's natural unit is a 32-bit word, so a single pixel needs **READ-MODIFY-WRITE** of its word (blind word write clobbers neighbors). Plus per-write QSPI framing/ACK overhead.

**→ single-pixel is the slowest pattern; write SPANS/ROWS.**

No `plot_pixel()` helper exists in `libvdp` today; a batching one would be a natural addition (BronzeGate). Byte/halfword write-enables in the SDRAM controller would make clean single-pixel writes possible (RTL candidate, BrightForge lane).

---

## 6. Planar → Chunky Compatibility Module

Owner's idea, and the right architecture: **stable core + optional compat layer**.

### 6.1 Concept

Presents a "virtual Shifter" (N planes, plane bases, optional plane scroll/mask, platform palette format) and emits chunky uploads to the core.

| Front-end | Platform |
|-----------|----------|
| ST interleaved | Atari ST |
| Separate planes + dual-playfield | Amiga |
| EGA planar | PC EGA |

### 6.2 Implementation Strategy

| Phase | Location | Risk | When |
|-------|----------|------|------|
| **1 — Host-side shim** (`libvdp`) | Software | Zero RTL risk, zero gate budget | First |
| **2 — RTL convert engine** (DMA-style) | FPGA | Requires lane + synthesis validation | Only if host CPU/upload proves too slow |

**Phase 1 (host-side):** Read planar SDRAM region, write chunky region when content changes. Display fetches chunky cheaply. Amortized, not per-frame.

**Phase 2 (RTL, if needed):** ONE-SHOT planar→chunky convert engine (DMA-style: read planar SDRAM region, write chunky region when content changes). This is **not** per-pixel-per-frame on-chip planar fetch — that is the bandwidth trap we are escaping.

### 6.3 Why This Architecture Wins

- **Sweet spot:** static screens, format conversion, region updates (dirty-span tracking)
- **Worst case:** full-screen per-frame plane animation (whole-frame recompute — intrinsic, set expectations)
- **BONUS:** Lets us **DEPRECATE the on-chip planar fetch path entirely** (the red-stripe / bandwidth-wall path) → fewer LUTs, fewer bugs, simpler RTL. The module isn't just additive; it makes the problem path removable.
- **ACCURACY WIN:** planar→chunky is a **PURE FUNCTION**, so it's golden-frame verifiable in sim (same discipline as the inner-border co-sim #11939). "People can rely on it" becomes a test, not a promise.

---

## 7. Blitter Reality (Task 49)

Read firsthand from `BlitterEngine.scala`, `VdpTop.scala`.

| Property | Value |
|----------|-------|
| Modes | 0 = RECT_FILL, 1 = RECT_COPY, 2 = LINE_FILL |
| Registers | `0x0C00..0x0C07` |
| srcRam | `0x0C10..0x0D0F` (512 words internal) |
| Write granularity | Whole 16-bit WORDS |
| Write mask | ❌ NO |
| RMW | ❌ NO |
| Transparency / ROP | ❌ NO |
| Target memory | 15-bit INTERNAL Mode0 space (tilemaps/pattern RAM/palette) |
| SDRAM bitmap | ❌ NO — blitter cannot address SDRAM |

### 7.1 Consequence: "Blit a Pixel"

| Target | Possible? | How |
|--------|-----------|-----|
| Bitmap framebuffer (SDRAM) | ❌ No | Use host `SDRAM_WRITE` (RMW) |
| Tile/pattern RAM (internal) | ⚠️ Yes | Word-granular + unmasked; sub-word pixels clobber word-mates. Pattern pixels are shared by every cell using that tile. |

### 7.2 Where It Shines

Bulk internal fills/copies: clear tilemap, stage in srcRam then RECT_COPY into pattern RAM — a tidy adapter path for converted tile cells.

### 7.3 Enhancement Candidates (Future Lanes)

| Enhancement | Class | Unlocks |
|-------------|-------|---------|
| (a) Write-mask / transparent-color skip | RTL | Masked sprite blits = Amiga/V9938-class |
| (b) SDRAM-targeting blit/DMA | RTL | On-chip fills/copies hit the framebuffer |

> **Lane authorization required.** Both are BrightForge lanes; PM decides if/when to open.

---

## 8. Prior-Art Comparison

Split: accurate reimplementations of planar/blitter machines vs modern from-scratch VDPs. We are the latter, and that camp has converged on our choices.

### 8.1 Comparable VDPs

| Property | VERA (CX16) | Gameduino / EVE | SNES / Genesis | MSX2 V9938 | Amiga (Minimig/MiSTer) | **Ours (Mode0)** |
|----------|-------------|-----------------|----------------|------------|------------------------|------------------|
| **Encoding** | Chunky | Chunky | Chunky tile VRAM | Chunky + planar modes | **Planar** | **Chunky core** + planar compat |
| **VRAM location** | 128 KB ON-CHIP | Block RAM | On-chip | 128 KB VRAM | Various (SDRAM + caches) | **SDRAM** |
| **Blitter** | ❌ No | Display-list coprocessor | ❌ No general blitter; DMA + HDMA | ✅ Real command engine (logic ops / line / fill / copy / search) | ✅ Full Blitter (minterms / mask / area-fill / line) | **DMA-class** (fill/copy/line) |
| **Pixel write** | Auto-increment data ports (+stride) | MCU streams over SPI | DMA/HDMA per-line reg reload | Command engine RMW | Blitter RMW | **Header+stream SDRAM_WRITE** |
| **Mid-frame effects** | Raster IRQ | — | HDMA (per-line reg reload) | — | **Copper** + Blitter | **Copper** + HDMA |
| **Host interface** | Register ports | SPI coprocessor | On-board 68k bus | Z80 I/O | 68k Chip RAM bus | **QSPI** |

### 8.2 Lessons

1. **Chunky won; planar survives only in accuracy cores** (= our optional-compat-module role). Validates §1.
2. **"Set a pixel" is near-universally an AUTO-INCREMENT streaming port**, never per-pixel re-addressing. Our header+stream `SDRAM_WRITE` is the same idea → "write spans not pixels" is correct.
3. **Blitters are two-tier:**
   - **DMA-class** (copy/fill, no mask): VERA, SNES, Genesis, us today
   - **Full blitter** (mask+ROP+RMW): Amiga, V9938
   - Our §7 enhancements = "move from DMA-class to V9938/Amiga-class."
4. **Mid-frame effects all = per-line register/palette reload** (Copper/HDMA/raster IRQ). We have the right primitive.

### 8.3 Root Cause of Our Pixel-Write Friction

Most peers keep video memory **ON-CHIP** (VERA 128 KB, Gameduino block RAM) = byte-addressable, single-cycle, no RMW pain. We chose commodity **SDRAM for CAPACITY**, paying burst/word granularity + 32-bit bus. The single-pixel awkwardness is an **SDRAM artifact, not a VDP law**. MiSTer also uses SDRAM but hides it behind chip-accurate caching/line-buffers. Ties into the existing SDRAM-controller survey (#11404 / #11409) in the repo.

---

## 9. Ownership & Roadmap Visibility

| Deliverable | Owner | Lane Status |
|-------------|-------|-------------|
| Host-side ST adapter + planar-compat shim + plot/span helpers | BronzeGate | Not yet opened |
| Masked/SDRAM blit, byte-enable writes, per-plane scroll reg, one-shot convert engine, planar-fetch deprecation | BrightForge | Not yet opened |
| Lane authorization / sequencing | TopazCliff | PM decides if/when |
| This design note + prior-art comparison | **CoralReef** | ✅ Captured |

---

## 10. References

- `BlitterEngine.scala` — current blitter implementation
- `VdpTop.scala` — compositor, effAddr path, register map wiring
- `MODE0_REGISTER_BUS_SPEC.md` — canonical register definitions
- `vdp_mode0.h` / `libvdp` — host-side API surface
- `PROJECT_PLAN/PLATFORM_ADAPTERS.md` — adapter honesty matrix
- `kb/AtariST/README.md` — Atari ST platform contract
- `kb/Amiga_OCS_ECS/README.md` — Amiga platform contract

---

*Captured by CoralReef from BrightForge #11946 owner-directed handoff.*
