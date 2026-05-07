**DEPRECATED -- merged into ASSESSMENT.md. This file is now archive reference only.**


# Mode0 Universal Sprite Engine — Gap Analysis

**Assessment version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Commit:** TBD  
**Scope:** Assessment only; defines substrate gaps between current sprite engine and honest universal emulation

---

## Design Goal

One substrate sprite engine that can be **configured per-platform** to emulate:
- C64 (8 sprites, 24×21, multicolor, collision)
- NES (64 sprites, 8×8/8×16, 4 palettes, sprite-0 hit)
- Genesis (80 total, 20/line, 8×8..32×32, shared palettes, link order, masking)
- SNES (128 total, 32/line, 34 tiles/line, 8×8..64×64, 8 palettes, FirstSprite)
- Amiga OCS (8/line, arbitrary width, attach mode, DMA fetch)
- Neo Geo (384 total, 96/line, 16×16..16×512, shrink, auto-animation)

The engine must be **honest**: the substrate provides the primitive; the adapter provides the mapping, not the workaround.

---

## Current Substrate Capabilities

| Feature | Current State | Coverage |
|---|---|---|
| Descriptor slots | 32 fixed | SNES (32/line ✓), Genesis (20/line ✓), NES (8/line ✓), C64 (8 ✓), Amiga (8/line ✓) |
| Visible per line | 32 (post-Stage B) | Covers all targets except Neo Geo 96/line |
| Per-sprite size | 8/16/32/64 via `sizeSel` | SNES ✓, Genesis ✓, NES (8/16) ✓, C64 (24×21 needs 32×32 with mask) |
| Per-sprite flip | `flipH`, `flipV` | Universal ✓ |
| Per-sprite palette | `paletteBank` (3b = 8 banks) | SNES (8) ✓, NES (4) ✓, Genesis (4 shared) ✓ |
| Per-sprite priority | `priority` (1b) | Genesis ✓, NES ✓, SNES (needs 2b) partial |
| Affine transform | Matrix + stepper (Task 37 proven) | Neo Geo shrink ✓, Mode7-style ✓ |
| Evaluator | Two-pass sequential scan | Works for all linear-table platforms |
| Compositor | Back-to-front slot priority | Adapter-mappable for most; link order needs mapping |
| Pattern storage | 2 × 256×4-bit on-chip ROM | **Universal blocker** — only 2 unique 16×16 patterns |

---

## Hard Substrate Gaps (Require Engine Redesign)

### Gap 1: Pattern Memory Architecture

**Current:** Two on-chip ROMs (`sprite0Pattern`, `sprite1Pattern`), 256×4-bit each = two 16×16 patterns. Address masked to `{row[3:0], col[3:0]}`. Sprites >16×16 tile-repeat.

**What breaks:**
- **All platforms** needing more than 2 unique sprites on screen simultaneously
- **Genesis/SNES** 32×32 sprites need 4 unique 16×16 tiles each, not repeated tiles
- **SNES** 64×64 sprites need 16 unique tiles
- **C64** 24×21 sprites need unique patterns (not 16×16 repeat)
- **Amiga** arbitrary-width sprites fetched per-scanline from RAM
- **Neo Geo** 16×16..16×512 needs large pattern tables

**Required substrate change:** Replace on-chip ROM with **RAM-backed pattern memory** accessible via bus writes and/or DMA fetch from SDRAM.

| Option | Capacity | Bus Interface | Effort |
|---|---|---|---|
| A: On-chip BSRAM (single/dual port) | 8–32 KB | Bus writes + direct read | Medium |
| B: SDRAM-backed with line cache | Large (MB) | DMA fetch + small cache | Large |
| C: Hybrid (BSRAM table + SDRAM bulk) | Flexible | Two-tier | Large |

**Recommendation:** Option A (on-chip BSRAM) as first step. 16–32 KB gives 256–512 unique 16×16 4bpp tiles. Honest for Genesis, SNES, NES, C64. Amiga/Neo Geo may still need Option B later.

---

### Gap 2: Pattern Address Width

**Current:** `addr = {row[3:0], col[3:0]}` = 8 bits → 16×16 max unique addressable area.

**What breaks:** Any sprite larger than 16×16 with unique pixels (not tiled repeat).

**Required substrate change:** Expand pattern address to support larger sprites:
- 10 bits → 32×32 (1024 entries)
- 12 bits → 64×64 (4096 entries)
- Or tile-indexed: `{tileRow, tileCol, subRow[3:0], subCol[3:0]}`

**Effort:** Small. Widens pattern RAM address, fetch mux, and affine stepper clamp.

---

### Gap 3: Tile-Fetch Budget Counter

**Current:** Evaluator counts **sprites** (up to 32). No concept of tiles fetched.

**What breaks:** SNES enforces **34 tiles/line**, not 32 sprites/line. A single 64×64 SNES sprite consumes 64 tiles but only 1 sprite slot.

**Required substrate change:** Add a second counter in the evaluator or fetch path that counts 8×8 tiles consumed by active sprites. Assert overflow when budget exceeded.

**Effort:** Small. Counter + comparator in evaluator.

---

### Gap 4: Sprite-Sprite Collision

**Current:** Only `SPRITE_0_HIT` (slot 0 vs BG) and `SPRITE_BG_HIT` (any vs BG).

**What breaks:** C64 `$D01E` detects **any pair of sprites overlapping**. Games rely on this for hit detection.

**Required substrate change:** Combinational overlap detector comparing all active sprite bounding boxes (or visible pixels). 32 sprites → pairwise compare is O(n²) = 496 comparisons. Can be optimized to bounding-box first, then pixel-precision for candidates.

**Effort:** Medium. New collision unit, status register expansion.

---

### Gap 5: Sprite Masking / Suppress Flag

**Current:** No per-sprite mask bit. All active sprites render.

**What breaks:** Genesis sprite masking — a sprite with the mask bit set suppresses all lower-priority sprites on that scanline.

**Required substrate change:** Add `mask` bit to descriptor. In compositor, when a masked sprite is active, suppress all sprites with lower display priority on that line.

**Effort:** Small. One bit + suppress logic in compositor loop.

---

### Gap 6: Configurable Pixel Format

**Current:** Fixed 4bpp indexed (pixel value 0 = transparent).

**What breaks:**
- **C64 multicolor sprites**: 2bpp within sprite, pixels are 2× wide
- **Amiga OCS sprites**: 2bpp (3 colors + transparent) or 4bpp attached (15 colors)
- **NES**: 2bpp sprites (4 colors including transparent)

**Required substrate change:** Per-sprite `bppSel` or `format` field that controls how pattern bits are unpacked into palette indices. Options:
- `00` = 4bpp indexed (current)
- `01` = 2bpp indexed (C64 multicolor, NES, Amiga)
- `10` = 1bpp + 2 color registers (C64 hires sprite)
- For C64 multicolor 2× width: horizontal stepping divide-by-2

**Effort:** Medium. New unpack logic in pattern fetch path.

---

### Gap 7: Per-Sprite Priority Depth

**Current:** Single `priority` bit (high = above all BG, low = behind opaque BG).

**What breaks:** SNES has **2 priority bits** per sprite (4 levels: 0..3). Genesis has per-sprite priority plus per-tile BG priority creating complex interaction matrices.

**Required substrate change:** Expand `priority` to 2 bits (4 levels). Compositor needs full priority matrix:

| BG prio | Sprite prio | Result |
|---|---|---|
| 0 (low) | 0 (low) | BG wins if opaque |
| 0 (low) | 1+ (high) | Sprite wins |
| 1+ (high) | 0 (low) | BG wins |
| 1+ (high) | 1+ (high) | Higher numeric wins, sprite default |

**Effort:** Medium. Compositor priority matrix rewrite.

---

### Gap 8: Pattern DMA Fetch (Amiga-style)

**Current:** Pattern data lives in on-chip ROM/BSRAM. No runtime fetch from SDRAM.

**What breaks:** Amiga fetches 2 words per sprite per scanline from chip RAM via DMA. This enables arbitrary-height sprites and dynamic pattern changes without CPU intervention.

**Required substrate change:** New DMA channel or arbiter slot for sprite pattern fetch. Line-buffered pattern data fetched during H-blank.

**Effort:** Large. New arbiter client, fetch FSM, line buffer.

---

## Adapter-Emulable Features (No Substrate Change Needed)

These can be handled by platform adapters without engine modification:

| Feature | Platform | Emulation Strategy |
|---|---|---|
| Link draw order | Genesis | Adapter writes descriptors in reverse-link order; substrate back-to-front = Genesis display order |
| FirstSprite rotation | SNES | Adapter rotates OAM into substrate slots so FirstSprite → slot 0 |
| Sprite attach | Amiga | Adapter maps attached pair to two substrate sprites at same (x,y) with pre-combined palette |
| Arbitrary height (>64) | Amiga | Adapter vertically chains multiple substrate sprites |
| Neo Geo shrink | Neo Geo | Adapter loads scaling matrix into affine fields |
| Neo Geo auto-animation | Neo Geo | Adapter updates `patternIndex` via Copper/animator or CPU writes |
| C64 X/Y expand | C64 | Adapter doubles sizeSel + halves pattern resolution |

---

## Prioritized Implementation Roadmap

### Phase 1: Pattern Memory Foundation (blocks ALL platforms)
1. Replace ROM with BSRAM-backed pattern RAM (16–32 KB)
2. Expand pattern address width to 10–12 bits
3. Bus interface for host writes to pattern RAM
4. Sim proof + resource report

**Why first:** Every honest platform adapter needs unique patterns. Without this, the engine is a demo toy, not a universal primitive.

### Phase 2: Format + Priority + Counter (blocks SNES/Genesis/C64)
5. Configurable pixel format (2bpp/4bpp)
6. 2-bit per-sprite priority + compositor matrix
7. Tile-fetch budget counter (SNES 34-tile limit)
8. Sprite masking bit (Genesis)
9. Sim proof + resource report

### Phase 3: Collision + DMA (blocks C64/Amiga advanced)
10. Sprite-sprite collision detector (C64)
11. Pattern DMA fetch from SDRAM (Amiga)
12. Sim proof + resource report

---

## Resource Estimate (Cumulative)

| Phase | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Phase 1 (Pattern RAM) | +200 | +300 | +2–4 | 0 |
| Phase 2 (Format/Prio/Counter) | +400 | +200 | 0 | 0 |
| Phase 3 (Collision/DMA) | +600 | +400 | 0 | 0 |
| **Total** | **+~1,200** | **+~900** | **+2–4** | **0** |

**Zone:** Green. Tang Nano 20K has ~20K LUTs. Current sprite engine is ~2K LUTs. Full universal engine ≈ 3–4K LUTs.

---

## Exit Condition

This assessment is successful because it:
1. Defines what "universal" means for the substrate sprite engine
2. Distinguishes substrate gaps from adapter-emulable features
3. Prioritizes by platform-blocking impact (Pattern RAM is #1)
4. Provides phased implementation roadmap with resource estimates
5. Identifies stop-lines (Phase 3 collision/DMA is stretch; Phase 1+2 is honest for Genesis/SNES/NES/C64)

**Recommendation:** Open **Phase 1 (Pattern Memory Foundation)** as the next sprite substrate lane after Stage B completes. Without it, the engine cannot honestly claim universal coverage regardless of how many descriptor fields are added.
