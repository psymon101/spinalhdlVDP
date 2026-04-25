# Task — Sprite Pattern Memory Foundation

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Scope:** Bounded substrate primitive — replace on-chip ROM with BSRAM-backed pattern RAM

---

## 1. Why This Task Exists

The current sprite engine stores pattern data in two on-chip ROMs (`sprite0Pattern`, `sprite1Pattern`), 256×4-bit each = two 16×16 patterns. This is a **universal blocker** for every platform adapter needing more than 2 unique sprites on screen.

From the Universal Sprite Engine gap analysis (#8586):
- **Genesis:** 32×32 sprites need 4 unique 16×16 tiles each
- **SNES:** 64×64 sprites need 16 unique tiles
- **C64:** 24×21 sprites need unique patterns
- **NES:** needs more than 2 unique sprite patterns
- **Amiga/Neo Geo:** need large pattern tables

Without pattern memory expansion, the sprite engine is structurally incapable of honest universal coverage.

---

## 2. Scope

### In Scope
1. Replace `sprite0Pattern`/`sprite1Pattern` ROMs with BSRAM-backed `Mem(Bits(4 bits), capacity)`
2. Expand pattern address width from 8 bits (16×16) to at least 10 bits (32×32) or 12 bits (64×64)
3. Host bus interface for runtime pattern RAM writes (register block)
4. Preserve existing `patternIndex` semantics for backward compatibility
5. Sim proof: multiple unique sprites render correctly
6. Resource report against `MODE0_STOPLINES.md`

### Explicitly Out of Scope
- Pattern DMA fetch from SDRAM (Phase 3, large effort)
- Configurable pixel format / 2bpp mode (Phase 2)
- Tile-fetch budget counter (Phase 2)
- Sprite-sprite collision (Phase 3)
- Changes to descriptor fields, evaluator, or compositor logic beyond pattern fetch

---

## 3. Technical Approach

### 3.1 Memory Organization

Option A: Single flat BSRAM
- `Mem(Bits(4 bits), 4096 entries)` = 16 Kbit = 2 KB
- 4096 entries = 256 unique 16×16 tiles, or 64 unique 32×32 tiles, or 16 unique 64×64 tiles
- Address = `{patternIndex[7:0], row[3:0], col[3:0]}` for 16×16 tile mode

Option B: Banked BSRAM (2 banks)
- Two `Mem(Bits(4 bits), 2048 entries)` = 8 Kbit each
- Preserves current `patternIndex(0)` bank select semantics
- Better for dual-port or simultaneous fetch scenarios

**Recommendation:** Option A (single flat). Simpler address math, larger effective capacity, no need for bank-select mux in fetch path.

### 3.2 Address Width Expansion

Current: `addr = {row[3:0], col[3:0]}` = 8 bits

New addressing modes (per-sprite selectable or global):
- `mode=00` (16×16): `addr = {patternIndex[7:0], row[3:0], col[3:0]}` = 16 bits → 256 unique tiles
- `mode=01` (32×32): `addr = {patternIndex[5:0], row[4:0], col[4:0]}` = 14 bits → 64 unique tiles
- `mode=10` (64×64): `addr = {patternIndex[3:0], row[5:0], col[5:0]}` = 16 bits → 16 unique tiles

**Recommendation:** Start with 16×16 mode only (largest tile count). 32×32 and 64×64 modes require wider pattern RAM or tile-indexed addressing and can be deferred.

### 3.3 Bus Interface

New register block for pattern RAM writes:
- `0x0A00..0x0AFF` = pattern RAM data (256 words, 16-bit each = 2 words per 16×16 row)
- `0x0B00` = pattern RAM address pointer (auto-increment on data write)
- Or: direct indexed write `0x0A00 + index`

**Recommendation:** Simple indexed write at `0x0A00..0x0AFF` with auto-increment pointer at `0x0B00`. Matches existing bus semantics.

### 3.4 Backward Compatibility

- Default pattern RAM initialized with existing diamond + cross patterns at indices 0 and 1
- Existing descriptor `patternIndex` semantics preserved
- No change to evaluator, compositor, or descriptor format

---

## 4. Validation

### Sim Proof
- `SpritePatternRamSim`: prove pattern write → pattern read → correct pixel output
- `VdpTopSim` regression: existing sprite scenes still render identically
- Multi-sprite scene: 4+ unique sprites on screen simultaneously

### Hardware Proof
- Sc0 default with unique sprites at indices 0, 1, 2, 3
- Verify all 4 patterns render correctly

### Resource Report
- LUT/FF/BSRAM before vs. after
- Timing closure check

---

## 5. Stop-Line

| Resource | Current | Add | Ceiling | Zone After |
|---|---|---|---|---|
| LUT/ALU/ROM16 | ~9,635 | +100–200 | 13,478 | Green |
| FF | ~5,608 | +50–100 | 10,109 | Green |
| BSRAM | 7 | +1–2 | 23 | Green |
| DSP | 18 | +0 | 24 | Yellow (unchanged) |

---

## 6. Exit Condition

This task is successful when:
1. BSRAM-backed pattern RAM replaces on-chip ROM
2. At least 256 unique 16×16 patterns available
3. Host can write patterns at runtime
4. Sim proof and hardware proof pass
5. Resource report confirms green zone
6. All existing sprite regressions still pass

---

## 7. Next Owner

- **BrightForge** for implementation (if authorized)
- **CyanPeak** to audit artifact and implementation
