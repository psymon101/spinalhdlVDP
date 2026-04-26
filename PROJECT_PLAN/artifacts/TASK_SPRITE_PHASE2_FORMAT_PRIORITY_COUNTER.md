# Task — Sprite Phase 2: Format, Priority, and Tile Counter

**Artifact version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-26  
**Implementation commit:** `de63ede` (BrightForge)  
**Audit:** pending CyanPeak audit of `7ad262f..de63ede`  
**Scope:** Bounded substrate hardening — pixel format, priority matrix, tile budget, and Phase 1 residual fix

---

## 1. Why This Task Exists

Phase 1 (Pattern Memory Foundation, `e86fe49`) landed BSRAM-backed pattern RAM but deferred:
- Pixel format flexibility (currently fixed 4bpp)
- Full priority matrix (currently 1-bit, compositor ignores paletteBank)
- Tile-fetch budget counter (needed for SNES 34-tile/line limit)
- 1-pixel horizontal shift from `readSync` latency

This artifact scopes the smallest bounded slice that closes those gaps.

---

## 2. Scope

### In Scope

1. **Configurable pixel format**
   - Per-sprite `bppSel` field in descriptor: `00`=4bpp (current), `01`=2bpp, `10`=1bpp
   - Pattern fetch path unpacks raw bits to 4-bit palette index accordingly
   - 2bpp mode: 2-bit pixels, 4 colors including transparent (NES, Amiga OCS, C64 multicolor)
   - 1bpp mode: 1-bit pixels, 2 colors (C64 hires sprite)
   - C64 multicolor 2× width: horizontal stepping divide-by-2 when in 2bpp mode

2. **2-bit per-sprite priority + compositor matrix**
   - Expand `priority` from 1 bit to 2 bits (4 levels: 0..3)
   - Compositor evaluates full priority matrix:
     - BG priority (from tile attribute) vs sprite priority
     - Higher numeric wins; sprite default when tied
   - Wire `paletteBank` into compositor pixel fill path (currently stored but forced to 0)

3. **Tile-fetch budget counter**
   - Evaluator or fetch path counts 8×8 tiles consumed by active sprites
   - Overflow when >34 tiles/line (SNES limit)
   - Status bit `SPRITE_OVERFLOW` already exists; tie counter to it

4. **1-pixel horizontal shift fix**
   - Pre-advance pattern RAM read address by 1 cycle to compensate `readSync` latency
   - Or: offset `activeX` by -1 in compositor fetch
   - Verify sprite position is pixel-accurate vs. pre-`e86fe49` baseline

### Explicitly Out of Scope
- Pattern DMA from SDRAM (Phase 3)
- Sprite-sprite collision (Phase 3)
- Sprite masking/suppress flag (can be Phase 2b or deferred)
- VisiblePerLine capacity bump (still blocked on resource; needs separate assessment)
- Any new descriptor fields beyond `bppSel` and `priority` width expansion

---

## 3. Technical Approach

### 3.1 Pixel Format

Descriptor add:
- `bppSel: UInt(2 bits)` — default `00` (4bpp back-compat)

Pattern fetch path changes:
- Current: `pixel = rom.read(addr)` returns 4 bits directly
- New: raw data width depends on `bppSel`
  - `00`: 4bpp — direct read, 1 pixel per address
  - `01`: 2bpp — read 4 bits, extract 2-bit pixel via `col[1:0]` mux, divide horizontal step by 2
  - `10`: 1bpp — read 4 bits, extract 1-bit pixel via `col[1:0]` mux, divide horizontal step by 4

Address generation for sub-4bpp:
- 2bpp: `addr = {patternIndex, row[3:0], col[4:2]}` (2 pixels per byte, 4 pixels per 4-bit word)
- Actually, since pattern RAM is 4-bit wide, 2bpp gives 2 pixels per address, 1bpp gives 4 pixels per address
- Horizontal counter `col` is shifted right by `bppSel` to get address; lower bits select pixel within the 4-bit word

### 3.2 Priority Matrix

Current compositor logic (simplified):
```
if (spritePriority) sprite wins over BG
else sprite only where BG is transparent
```

New logic:
```
spriteWins = (spritePriority > bgPriority) ||
             (spritePriority == bgPriority && spritePriority > 0) ||
             (bgPixel == 0)
```

Where:
- `bgPriority` comes from L0 tile attribute or L1/L2/L3 fixed priorities
- `spritePriority` is 2-bit from descriptor
- Transparent pixel (0) always loses

Palette bank wiring:
- `fillBank := activePaletteBank(s)` instead of hardcoded `U(0)`
- Requires 3-bit-wide last-hit-wins mux (the timing issue from Sprite Hardening)
- **Mitigation:** pipeline the paletteBank selection by 1 cycle (register stage) to break the combinational chain

### 3.3 Tile-Fetch Budget Counter

Current evaluator counts **sprites** (up to 32/line). Need to count **8×8 tiles**.

Per-sprite tile count:
- 8×8 sprite: 1 tile
- 16×16 sprite: 4 tiles
- 32×32 sprite: 16 tiles
- 64×64 sprite: 64 tiles

Counter implementation:
- In evaluator Pass 1 or Pass 2, sum `(sizeForSel(sizeSel) / 8) ^ 2` for each active sprite
- Or simpler: in fetch path, count each 8×8 tile address generation as 1 tile
- Assert overflow when sum > 34

**Effort:** Small. Counter + comparator.

### 3.4 1-Pixel Shift Fix

Options:
- A: Pre-advance pattern RAM address by 1: `readAddr = addr + 1` during fetch
- B: Register `activeX` delayed by 1 cycle so pixel appears at correct raster position
- C: Offset sprite X position by -1 in descriptor (adapter-level, not substrate)

**Recommendation:** Option B — register `activeX` to align with `readSync` latency. Minimal code change, no address math complexity.

---

## 4. Validation

### Sim Proof
- `SpriteFormatSim`: prove 2bpp/1bpp unpack produces correct pixels
- `SpritePrioritySim`: prove 2-bit priority matrix resolves correctly in mixed BG/sprite scenes
- `SpriteTileBudgetSim`: prove 34-tile limit enforced, overflow bit set
- `VdpTopSim` regression: existing scenes remain bit-identical

### Hardware Proof
- Scenario with 4+ unique sprites using different `bppSel` values
- Scenario with sprites at different priority levels overlapping BG

### Resource Report
- LUT/FF/BSRAM before vs. after
- Timing closure check

---

## 5. Stop-Line

| Resource | Current (e86fe49) | Add | Ceiling | Zone After |
|---|---|---|---|---|
| LUT/ALU/ROM16 | 9,240 | +300–400 | 13,478 | Green |
| FF | 5,705 | +100–200 | 10,109 | Green |
| BSRAM | 15 | +0 | 23 | Green |
| DSP | 18 | +0 | 24 | Yellow (unchanged) |

**Risk:** PaletteBank pipelining adds 1 register stage per sprite slot (32 × 3 bits = 96 FFs) but breaks the combinational chain that caused timing revert in `d44a9c0`. Timing should improve or stay neutral.

---

## 6. Exit Condition

This task is successful when:
1. Per-sprite `bppSel` controls pixel format (4bpp/2bpp/1bpp)
2. 2-bit priority + compositor matrix resolves BG vs sprite correctly
3. `paletteBank` is consumed in compositor pixel fill path
4. Tile-fetch budget counter enforces 34 tiles/line
5. 1-pixel horizontal shift is compensated
6. Sim proof + hardware proof pass
7. Resource report confirms green zone
8. All existing regressions still pass

---

## 7. Next Owner

- **BrightForge** for implementation (if authorized)
- **CyanPeak** to audit artifact and implementation

---

## 8. Implementation Status (Post-Landing)

**Landing commit:** `de63ede` (BrightForge #8619)

### Landed
| Sub-slice | Commit | Description |
|---|---|---|
| P2-1 | `7ad262f` | 1-pixel shift fix (pre-roll `fillX`) |
| P2-3a | `92fa8ca` | Pipeline per-sprite `paletteBank` into compositor |
| P2-4 | `b6f0a7e` | SNES tile-fetch budget counter |
| P2-2 + P2-3b | `1b150ea` | `bppSel` + priority width 1→2 (storage + bus + active output) |
| Bus-map fix | `de63ede` | Relocate sprite RAM ptr/data + ext block to `0x0D10..0x0D3F` |

### Deferred to Phase 2-bis
1. **bppSel pixel-fetch unpacker:** descriptor/bus/active output land; actual 4bpp→2bpp/1bpp unpack in slot loop deferred. Adapters can program `bppSel`; substrate continues 4bpp rendering.
2. **Compositor priority matrix:** `priority` widened to 2 bits in storage; compositor still uses bit 0 (LSB) for binary above-/below-bg. Full matrix needs bg-priority exposed multi-bit upstream.

Both deferrals are **plumbing-complete substrate-side** — surgical consumer-side follow-ons.

---

## 9. Phase 2-bis Scope (Auto-Authorized In-Lane Continuation)

Per PM operational coverage (#8620) and auto-continue policy (#8537):

1. **bppSel unpacker in slot loop:** unpack 2bpp/1bpp pattern RAM data to 4-bit palette index
2. **Full priority matrix in compositor:** `(spritePriority > bgPriority) || (== && >0) || (bgPixel == 0)`
3. Sim proof for both consumers
4. Resource report

Stop-line: +50–100 LUT / +20–50 FF / +0 BSRAM.

---

## 10. Next Owner

- **BrightForge** for Phase 2-bis implementation (auto-authorized)
- **CyanPeak** to audit full Phase 2 + Phase 2-bis together
