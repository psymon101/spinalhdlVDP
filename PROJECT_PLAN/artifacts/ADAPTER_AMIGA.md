# Amiga OCS/ECS Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** Commodore Amiga (OCS/ECS chipset)  
**Tier:** 4 (very high)  
**Mode ID (proposed):** `0xD`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 320×200 (NTSC lores) / 320×256 (PAL lores) / 640×200/256 (hires) |
| Color depth | 1–6 bitplanes (2–64 colors) or HAM (4096 colors) or EHB (64 colors) |
| Master palette | 4096 colors (12-bit RGB: 4 bits per channel) |
| Simultaneous colors | 2–64 (bitplane modes) / 4096 (HAM) / 64 (EHB) |
| Refresh | ~59.9 Hz (NTSC) / ~49.9 Hz (PAL) |
| Aspect | Non-square pixels; 320×200 is canonical game resolution |

The Amiga uses three custom chips: **Agnus** (DMA/blitter/Copper), **Denise** (video output), and **Paula** (sound). The video subsystem is uniquely flexible.

### 1.2 Layer model

**No fixed layers.** The Amiga uses **bitplanes** — up to 6 independent 1-bit layers that are combined to produce pixels:
- 1 bitplane = 2 colors
- 2 bitplanes = 4 colors
- 3 bitplanes = 8 colors
- 4 bitplanes = 16 colors
- 5 bitplanes = 32 colors
- 6 bitplanes = 64 colors (or EHB: 64 colors with half-brightness)

**HAM (Hold-And-Modify):** 6 bitplanes interpreted differently to allow 4096 colors on screen simultaneously.

**Sprites:** 8 hardware sprites ("hardware boobs"), 16 pixels wide, variable height, linked in pairs for 15-color sprites.

### 1.3 Bitplane organization

**Bitplanes:** Each bitplane is a 1-bit-per-pixel framebuffer.
- For 320×200: one bitplane = 8,000 bytes (40 bytes/line × 200 lines)
- Bitplanes can be interleaved in memory (standard) or non-interleaved
- Denise fetches one word (16 pixels) from each bitplane per fetch block

**Fetch mode:**
- Lores: 1 word fetch per bitplane per 16 pixels
- Hires: 2 word fetches per bitplane per 16 pixels

**Screen memory layout (interleaved, standard):**
- Word 0 of bitplane 0, word 0 of bitplane 1, ..., word 0 of bitplane N
- Word 1 of bitplane 0, word 1 of bitplane 1, ..., word 1 of bitplane N
- etc.

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 8 sprites |
| Width | 16 pixels (or 32 in ECS with sprite width doubling) |
| Height | Variable (2–?? lines, determined by sprite data termination) |
| Colors | 2 colors (1 sprite) or 15 colors (2 sprites linked) |
| DMA | Sprites are fetched automatically by Agnus DMA |

**Sprite DMA:** Agnus fetches sprite data from RAM automatically during the display frame. No CPU intervention needed after setup.

**Sprite linking:** Odd+even sprite pairs (0+1, 2+3, 4+5, 6+7) share a 15-color palette. Individual sprites use 2 colors + transparent.

### 1.5 Palette / color model

**Color registers:** 32 entries × 12-bit RGB (4 bits per channel).
- Entries 0–15: standard colors
- Entries 16–31: only used in EHB mode (half-brightness of 0–15)
- In HAM mode, color registers serve as the "base palette" for HAM operations

**HAM mode:** Each 6-bit pixel is interpreted as:
- `00xxxx` = load palette entry xxxx
- `01xxxx` = modify blue channel to xxxx
- `10xxxx` = modify red channel to xxxx
- `11xxxx` = modify green channel to xxxx

### 1.6 Scrolling model

**No hardware scroll registers.** The Amiga achieves scroll by:
- Changing bitplane pointers (`BPLxPTH/BPLxPTL`) to offset the start address
- Using the Copper to modify pointers mid-frame for split-screen effects
- Using the Blitter to shift bitplanes horizontally (expensive)

**DIWSTRT/DIWSTOP:** Define the display window start/stop positions, which can create partial-screen effects.

### 1.7 Raster / IRQ / beam-driven behavior

**Copper (co-processor):**
- Executes a small program of MOVE/WAIT/SKIP instructions
- Can modify any chipset register at any raster position
- Used for: color splits, scroll splits, mode changes, sprite repositioning

**VBlank interrupt:** Standard frame tick.

**Blitter interrupts:** Signal completion.

### 1.8 DMA / blitter / display-list behavior

**Blitter:**
- DMA-based block copy, fill, and line drawing
- Supports bitwise logic operations (minterms: 256 possible operations)
- Independent of CPU; interrupts on completion

**Copper:**
- Not a display-list processor per se, but acts as a beam-synchronous register update engine
- Programs are stored in RAM and executed by Agnus

**DMA channels:**
- Bitplane DMA (up to 6 channels)
- Sprite DMA (8 channels)
- Copper DMA (1 channel)
- Blitter DMA (1 channel, shares with CPU)

### 1.9 Windowing / masking / priority rules

**Display window:** `DIWSTRT` / `DIWSTOP` define the visible region. Outside this region shows the border color.

**Sprite vs bitplane priority:** Sprites can be placed in front of or behind bitplanes on a per-sprite basis via `SPRxPOS`/`SPRxCTL`.

**No color math.** No masking hardware beyond the display window.

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| Chip RAM | 512 KB–2 MB | Bitplanes, sprites, Copper lists, audio, disk buffers |
| Color registers | 32 × 12-bit | Palette (chipset registers, not RAM) |
| Chipset registers | ~256 | Control registers for Agnus/Denise/Paula |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Copper:** The Amiga's defining feature. Games and demos rely on Copper for color bars, splits, and effects. Missing Copper support breaks the Amiga visual identity.
2. **Bitplanes:** The flexible bitplane model is fundamentally different from tilemaps. Mode0 is tilemap-centric.
3. **HAM mode:** Iconic for still images and some games (e.g., `Shadow of the Beast`). Requires dedicated decode logic.
4. **EHB mode:** Simple 64-color mode using half-brightness. Easy to support if 6 bitplanes are available.
5. **Blitter minterms:** The 256-operation bitwise logic is powerful and used for masking, cookie-cut sprites, etc.

---

## 2. Pipeline Decomposition

| Stage | What Amiga does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | Agnus DMA fetches bitplane words from Chip RAM | `PlanarLineFetch` (R7.1) | Direct — bitplane fetch |
| **Decode** | Denise combines 1–6 bitplanes → pixel index (or HAM decode) | `BitplaneReconstruct` + palette / HAM decoder | Direct for standard; **HAM is a gap** |
| **Staging** | Internal latches | Line buffers | Direct |
| **Sprite evaluation** | 8 sprites, DMA-fetched | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc. **Amiga has only 8 sprites but with DMA fetch** |
| **Composition** | Bitplanes + sprites → priority | `FourLayerCompositor` | Approximate — bitplanes are not tiles; composition model differs |
| **Palette** | 32-entry × 12-bit color registers | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | Copper programs modify registers at any line | `RasterTriggerUnit` + `Copper` (R3) | Approximate — Copper is more general than raster trigger |
| **Host/control** | 68000 writes chipset registers / Copper lists | Adapter shadow + bus emitter | Medium — many registers to map |

---

## 3. Mode0 Mapping

### 3.1 Background (bitplanes)

| Amiga function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 1–6 bitplanes (2–64 colors) | `PlanarLineFetch` (1–6 planes) | Configure planar fetch with N planes | Direct |
| HAM mode (4096 colors) | N/A | **Gap:** Mode0 has no HAM decoder | **Large gap** — requires dedicated post-fetch logic |
| EHB mode (64 colors) | `PlanarLineFetch` (6 planes) + palette | Map bit 5 to half-brightness select | Minor — can be done in palette lookup |
| Interleaved bitplanes | `PlanarLineFetch` | Configure interleaved fetch | Direct |
| Hardware scroll (pointer change) | `BITMAP_BASE_LO/HI` | Map `BPLxPTH/BPLxPTL` to bitmap base | Minor — Amiga scrolls by pointer; Mode0 uses scroll regs |
| Display window (DIW) | `WindowUnit` | Map `DIWSTRT/DIWSTOP` to window bounds | Minor |

### 3.2 Sprite layer

| Amiga function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 8 sprites | `SpriteEvaluator` (64 desc) | Direct match — Amiga has fewer sprites | None |
| 16-pixel wide sprites | `SpriteEvaluator` descriptor | Set width = 16 | None |
| Variable height | `SpriteEvaluator` descriptor | Set height per descriptor | None |
| Sprite DMA | Host QSPI / bus writes | No DMA in Mode0; host writes descriptors | Minor — functional equivalent |
| Sprite linking (pairs) | `SpriteEvaluator` | Pair sprites for 15-color | Minor — Mode0 supports multi-color sprites natively |
| Sprite priority (front/back) | `PixelMetadata` priority bit | Map per-sprite priority | Direct |

### 3.3 Palette

| Amiga function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 32-entry 12-bit color registers | CW-1 palette RAM | Map color registers to palette entries 0..31 | Direct — Mode0 palette is superset |
| HAM base palette | CW-1 palette RAM | First 16 entries serve as HAM base | Minor |
| EHB half-brightness | Palette lookup | Entries 16..31 = half of 0..15 | Minor — can precompute in palette |

### 3.4 Copper / Blitter

| Amiga function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| Copper MOVE/WAIT/SKIP | `Copper` (R3) | Map Copper programs to Mode0 Copper | Minor — Mode0 Copper is similar but register set differs |
| Blitter copy/fill/line | `BlitterEngine` (Task 49) | Map blitter commands to Mode0 blitter | Minor — minterms may not map fully |
| Blitter minterms (256 ops) | `BlitterEngine` | Mode0 blitter may not support all 256 ops | **Medium gap** — cookie-cut sprites need specific minterms |
| Blitter interrupts | Adapter-local status | Map blitter done to status bit | Minor |

### 3.5 Raster / IRQ

| Amiga function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| Copper raster effects | `Copper` + `RasterTriggerUnit` | Copper handles all mid-frame register updates | Direct — Mode0 Copper is designed for this |
| VBlank interrupt | `RasterTriggerUnit` at last line | Direct map | Direct |
| Blitter done interrupt | Adapter-local | Map to status/IRQ | Minor |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `AMG_BPLCON0` | 16 bits | Bitplane control: HAM, EHB, bitplane count, hires |
| `0x01` | `AMG_BPLCON1` | 16 bits | Scroll delay (fine scroll) |
| `0x02` | `AMG_DIWSTRT` | 16 bits | Display window start (X, Y) |
| `0x03` | `AMG_DIWSTOP` | 16 bits | Display window stop (X, Y) |
| `0x04` | `AMG_DDFSTRT` | 16 bits | Display data fetch start |
| `0x05` | `AMG_DDFSTOP` | 16 bits | Display data fetch stop |
| `0x06` | `AMG_BPL1PTH` | 16 bits | Bitplane 1 pointer (high) |
| `0x07` | `AMG_BPL1PTL` | 16 bits | Bitplane 1 pointer (low) |
| `0x08` | `AMG_BPL2PTH` | 16 bits | Bitplane 2 pointer (high) |
| `0x09` | `AMG_BPL2PTL` | 16 bits | Bitplane 2 pointer (low) |
| `0x0A` | `AMG_BPL3PTH` | 16 bits | Bitplane 3 pointer (high) |
| `0x0B` | `AMG_BPL3PTL` | 16 bits | Bitplane 3 pointer (low) |
| `0x0C` | `AMG_BPL4PTH` | 16 bits | Bitplane 4 pointer (high) |
| `0x0D` | `AMG_BPL4PTL` | 16 bits | Bitplane 4 pointer (low) |
| `0x0E` | `AMG_BPL5PTH` | 16 bits | Bitplane 5 pointer (high) |
| `0x0F` | `AMG_BPL5PTL` | 16 bits | Bitplane 5 pointer (low) |
| `0x10` | `AMG_BPL6PTH` | 16 bits | Bitplane 6 pointer (high) |
| `0x11` | `AMG_BPL6PTL` | 16 bits | Bitplane 6 pointer (low) |
| `0x12` | `AMG_COLOR00` | 16 bits | Color register 0 (12-bit RGB) |
| `0x13` | `AMG_COLOR01` | 16 bits | Color register 1 |
| ... | ... | ... | ... |
| `0x21` | `AMG_COLOR15` | 16 bits | Color register 15 |
| `0x22` | `AMG_COP1LCH` | 16 bits | Copper 1 list pointer (high) |
| `0x23` | `AMG_COP1LCL` | 16 bits | Copper 1 list pointer (low) |
| `0x24` | `AMG_COPCON` | 16 bits | Copper control (enable/disable) |
| `0x25` | `AMG_DMACON` | 16 bits | DMA control (bitplane, sprite, Copper, blitter enable) |
| `0x26` | `AMG_INTENA` | 16 bits | Interrupt enable |
| `0x27` | `AMG_INTREQ` | 16 bits | Interrupt request / clear |

### 4.2 Initialization flow

1. Host selects mode `0xD` via `MODE_SELECT`
2. Host uploads bitplane data to SDRAM
3. Host writes color registers 0–15 (or 0–31 for EHB)
4. Host sets bitplane pointers (`AMG_BPLxPTH/BPLxPTL`)
5. Host sets display window (`AMG_DIWSTRT/DIWSTOP`)
6. Host writes `AMG_BPLCON0` to set bitplane count and mode (HAM/EHB/hires)
7. Host enables DMA via `AMG_DMACON`
8. (Optional) Host loads Copper list and enables Copper

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Bitplane data | 8–48 KB (1–6 planes × 320×200) | SDRAM | 1bpp planar, interleaved |
| Sprite data | Up to 8 KB | SDRAM | 2-color or 15-color sprite patterns |
| Copper list | Up to 4 KB | SDRAM / Adapter shadow | MOVE/WAIT/SKIP instructions |
| Color registers | 64 bytes | Adapter shadow / Mode0 palette | 32 × 12-bit entries |

### 4.4 Runtime control/update model

- **Bitplane pointer updates:** Write `AMG_BPLxPTH/BPLxPTL`. Adapter emits `BITMAP_BASE_LO/HI` bus writes.
- **Color updates:** Write `AMG_COLORxx`. Adapter emits palette bus writes.
- **Copper list:** Host uploads Copper program to SDRAM, sets pointer, enables Copper.
- **Blitter:** Host writes blitter registers (via separate blitter command interface) and triggers.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` at last line | `STATUS_STICKY` bit 0 |
| Blitter done | Adapter-local | `STATUS_STICKY` bit (custom) |
| Copper vertical blank | `RasterTriggerUnit` | Used to restart Copper list |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `PlanarLineFetch` plane count | Adapter-local `AMG_BPLCON0` | Adapter translates |
| `BITMAP_BASE_*` per plane | Adapter-local `AMG_BPLxPTH/BPLxPTL` | Adapter translates |
| Palette entries 0..31 | Adapter-local `AMG_COLORxx` | Adapter translates |
| Sprite descriptors | Adapter-local shadow | Adapter emits bus writes |
| Display window | Adapter-local `AMG_DIWSTRT/DIWSTOP` | Adapter translates to `WindowUnit` |
| Copper | Adapter-local `AMG_COP1LCH/COP1LCL` | Translates to Mode0 Copper program |
| Blitter | Adapter-local blitter regs | Translates to `BlitterEngine` |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Planar fetch (R7.1) — direct match for bitplanes
- Sprite evaluation (R2) — Mode0 has 64 desc; Amiga has only 8
- Palette RAM — superset
- Raster triggers (R1) — direct match
- Copper (R3) — direct match for beam-synchronous updates

### 5.2 What is approximate

- **Bitplane scroll:** Amiga scrolls by changing bitplane pointers. Mode0 uses `layer0ScrollX/Y` registers. The adapter can emulate pointer-based scroll by computing base addresses from scroll values, but this is not identical.
- **Copper:** Mode0's Copper is similar but the register set differs. Not all Amiga Copper tricks map cleanly.
- **Blitter minterms:** Mode0 `BlitterEngine` may not support all 256 bitwise operations.

### 5.3 What is missing entirely

- **HAM mode:** No Mode0 equivalent. Requires a dedicated 6-bit → 12-bit/24-bit decode stage that implements HAM semantics.
- **EHB mode:** Can be approximated with palette tricks but is not a native Mode0 feature.
- **Hires mode:** 640-pixel horizontal resolution may exceed Mode0's output capabilities.
- **Sprite DMA:** Mode0 has no DMA-based sprite fetch. Host must write descriptors.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Planar fetch (1–6 planes) | Shared | Already proven |
| Sprite evaluation | Shared | Mode0 has 64; Amiga only needs 8 |
| 12-bit palette | Adapter-local | Mode0 uses 24-bit |
| Copper | Shared | Already proven |
| Blitter | Shared (Task 49) | Mode0 blitter exists |
| HAM decoder | Adapter-local (never?) | No Mode0 equivalent |
| EHB | Adapter-local | Palette trick |

### 5.5 Realism for default bitstring

**Partially realistic.** The Amiga adapter can support standard bitplane modes (1–6 planes, 2–64 colors) reasonably well. HAM and EHB are gaps. The low sprite count (8) is actually easier for Mode0 than other platforms.

Estimated cost: ~400 LUT, ~300 FF (standard bitplanes). HAM adds ~200 LUT if implemented.

**Explicitly excluded from default bitstring** per `MODE0_GAP_TASKLIST.md` due to HAM/EHB complexity.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Standard bitplanes:** 1–6 plane modes, 8 sprites, 12-bit palette, Copper, display window.
2. **v1.1 — Blitter proxy:** Map Amiga blitter commands to Mode0 blitter.
3. **v2 — EHB support:** Palette-based half-brightness.
4. **v3 — HAM mode (optional):** Dedicated HAM decoder (large effort, may remain honest gap).

### 6.2 Prerequisite substrate tasks

- **R7.1 Planar Line Fetch** — ✅ DONE
- **R2 Sprite Evaluator** — ✅ DONE
- **R1 Raster Trigger** — ✅ DONE
- **R3 Copper** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **Task 49 BlitterEngine** — ⚠️ **Required for v1.1**

### 6.3 Proof plan

**Simulation:**
- `AmigaAdapterSim`: Test bitplane modes, sprite upload, Copper color bars, display window
- `VdpTopSim` regression: Amiga-style 4-plane bitplane scene

**Hardware proof:**
- Scenario: Amiga demo scene (e.g., `State of the Art` style color bars or `Shadow of the Beast` static image)
- Upload bitplane data, palette, Copper list via QSPI
- Verify color splits and sprite overlay
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~400 (v1 standard bitplanes) | Under 700 |
| LUT | ~600 (v1 + EHB) | Under 1000 |
| LUT | ~800 (v2 + HAM) | Under 1200 |
| FF | ~300 | Under 600 |
| BSRAM | 0–1 | Under 2 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** 1–6 bitplanes, 8 sprites, Copper color bars, display window.
- **v1.1:** Blitter proxy.
- **v2:** EHB palette trick.
- **v3:** HAM decoder (honest gap if too expensive).
- **Never:** Perfect Agnus DMA timing, audio DMA, disk drive emulation.
