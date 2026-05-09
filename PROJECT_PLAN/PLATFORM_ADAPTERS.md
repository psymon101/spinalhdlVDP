# Platform Adapter Compilation

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-07  
**Status:** Canonical active adapter document  
**Governing directive:** BronzeGate #9421

This document replaces the previously-scattered per-platform adapter specs in `PROJECT_PLAN/artifacts/`. Full historical content is archived in `PROJECT_PLAN/archive/adapters/`.

If any adapter spec disagrees with `TASKS.md` on execution priority, `TASKS.md` wins.

---

## 1. Adapter Honesty Matrix

This matrix tracks whether the current `Mode0` substrate supports an "honest" implementation of each platform's video hardware.

| Adapter | Tier | Honest Now? | Blocked By | Acceptable Gaps |
|---------|------|-------------|------------|-----------------|
| ZX Spectrum | 1 | ✅ Yes | — | Border effects, ULA+ |
| C64 | 1 | ✅ Yes | — | Sprite-sprite collision (Task 54) |
| Atari ST | 1 | ✅ Yes | — | Immediate palette, blitter semantics |
| NES | 2 | ✅ Yes | — | Colour emphasis, mapper IRQs |
| PC Engine | 2 | ✅ Yes | descCount 64→80 | SATB DMA, per-tile palette bank |
| SMS/GG | 1/2 | ⚠️ v1 only | Verify 4bpp tile decoder (v1.1) | Sprite zoom, line interrupt reload |
| MSX2 | 2 | ✅ Yes | — | Command engine quirks |
| Genesis | 3 | ⚠️ v1 only | descCount 64→80 | — |
| SNES | 4 | ⚠️ v1 only | descCount 64→128 | — |
| Neo Geo | 3 | 🔴 No | descCount 64→380, per-line 32→96, palette 512→4096 | — |
| Amiga | 4 | ✅ Yes | — | Copper wait/move exact timing |

**Rules:**
- `✅ Yes` = substrate supports honest v1 claim now
- `⚠️ v1 only` = honest for bounded v1, but higher-fidelity claims blocked
- `🔴 No` = cannot claim honest implementation until blocker closes
- `Blocked By` = substrate gap only; adapter-local simplifications go in `Acceptable Gaps`
- Every `Blocked By` entry must reference a specific task or substrate capability

---

## 2. Tier 1 Adapters

### 2.1 ZX Spectrum

**Tier:** 1  
**Status:** Implemented + proven (v3.8 + E3.45 polish, closure #8976)  
**Default bitstring:** Yes  
**Archive:** `archive/tasks/TASK_50_ZX_SPECTRUM_ADAPTER.md`

A 256×192 tile+attribute ULA-compatible adapter. Uses `SdramTileAttributeFetch` + `SdramTileFetch` (1bpp) with the standard ZX attribute byte (paper/ink/bright/flash). Border colour and ULA+ contention are out of scope.

**Honest gaps:** None for v1. Border effects and ULA+ are deferred.

---

### 2.2 Commodore 64

**Tier:** 1  
**Status:** Implemented + proven (smoke test proven; Task 40b gaps documented)  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_C64.md` (if present) or `archive/tasks/TASK_40_FIRST_PLATFORM_ADAPTER.md`

A 320×200 multi-mode adapter supporting character, bitmap, and sprite modes. Uses `SdramTileAttributeFetch`, `BitmapRowFetch`, `SpriteEvaluator`, and `FourLayerCompositor`. Sprite-sprite collision (Task 54) is an adapter-local enhancement.

**Honest gaps:** Sprite-sprite collision detector (Task 54) enhances but is not required for baseline honesty.

---

### 2.3 Atari ST

**Tier:** 1  
**Status:** Spec drafted — lowest-risk Tier 1 target  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_ATARI_ST.md`

The SHIFTER IC scans planar bitplanes (1–4 planes, 320×200×16-color down to 640×400 mono) with no hardware sprites and no scroll registers. Raster effects rely on the MFP Timer B and immediate palette updates.

**Key Mode0 mapping:** `PlanarLineFetch` (word-interleaved), `BITMAP_BASE_LO/HI`, `BITMAP_CTRL`, CW-1 palette RAM, `RasterTriggerUnit`.

**Honest gaps:**
- Immediate palette effect: Mode0 uses safe-boundary commits
- No hardware scroll; adapter offers `layer0ScrollX/Y` as convenience only
- STE Blitter semantics differ from Mode0 blitter
- Border removal / overscan tricks out of scope

---

### 2.4 TMS9918 Family (MSX1 / SMS / GG)

**Tier:** 1 (base) / 2 (SMS/GG)  
**Status:** Spec drafted — family doc  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_TMS9918_FAMILY.md`

The TMS9918A uses 1bpp tiles with a fixed 16-color palette, 32 sprites (4/line), and no scroll. The SMS VDP (Mode 4) adds 4bpp tiles, 64 sprites (8/line), hardware scroll, and line interrupts. The Game Gear further adds a 12-bit palette and a 160×144 viewport window.

**Key Mode0 mapping:** `SdramTileAttributeFetch`, `SdramTileFetch` (1bpp/4bpp), `SpriteEvaluator`, CW-1 palette, `RasterTriggerUnit`, `layer0ScrollX/Y` (SMS/GG), `WindowUnit` (GG viewport).

**Honest gaps:**
- Hardware sprite-sprite collision not available in Mode0
- TMS9918A 40×24 text mode (6×8 chars) approximated with 8×8
- SMS/GG sprite zoom not directly supported
- Line interrupt auto-reload may need substrate support
- GG hidden border sprite evaluation may not replicate perfectly

---

## 3. Tier 2 Adapters

### 3.1 NES / Famicom

**Tier:** 2  
**Status:** Spec drafted  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_NES.md`

The Ricoh PPU generates 256×224/240 video from 2bpp planar tiles, a single scrollable background nametable, and 64 sprites (8/scanline). Key behaviours include sprite-0 hit raster splits, OAM DMA, 2×2 attribute-table palette granularity, and a 6-bit master palette with colour emphasis.

**Key Mode0 mapping:** `SdramTileAttributeFetch`, `SdramTileFetch` (2bpp), `SpriteEvaluator`, `FourLayerCompositor`, CW-1 palette, `RasterTriggerUnit`.

**Honest gaps:**
- 64 sprite descriptors require expansion from current 32 (Task 2b already provides 64)
- Colour emphasis (global RGB tint) has no Mode0 equivalent
- Mapper IRQs (MMC3 etc.) are cartridge-specific and out of scope
- PPU open-bus read quirks not emulated
- PAL/NTSC composite artifacts not reproduced

---

### 3.2 PC Engine / TurboGrafx-16

**Tier:** 2  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_PC_ENGINE.md`

The HuC6270 VDC plus HuC6260 VCE provide a 256×239 4bpp tile background, 64 sprites (16/line), variable sprite sizes, and a 512-entry 9-bit master palette. The RCR raster interrupt is heavily used for effects, and a VRAM-to-SATB DMA auto-copies sprite attributes each frame.

**Key Mode0 mapping:** `SdramTileAttributeFetch`, `SdramTileFetch` (4bpp), `SpriteEvaluator`, `FourLayerCompositor`, CW-1 palette, `RasterTriggerUnit`.

**Honest gaps:**
- 64 sprite descriptors require expansion from 32 (DONE via Task 2b)
- 16 sprites/line require per-line expansion from 8 (DONE via Task 2b)
- SATB DMA not present; host must write descriptors individually
- Per-tile palette bank may not be natively supported
- Y/X coordinate offsets (64/32) need adapter translation

---

### 3.3 MSX2

**Tier:** 2  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** Yes  
**Archive:** `archive/adapters/ADAPTER_MSX2.md`

Yamaha V9938 supports TMS9918A-compatible tile modes (G1–G3) and bitmap modes (G4–G7) up to 256×212/512×212. It has 32 sprites, a hardware command engine (blitter), vertical scroll, and a 16-entry 9-bit palette.

**Key Mode0 mapping:** `SdramTileAttributeFetch` (G1–G3), `PlanarLineFetch` (G4–G6), `BitmapRowFetch` (G7), `SpriteEvaluator`, CW-1 palette, `RasterTriggerUnit`, `BlitterEngine`.

**Honest gaps:**
- Sprite Mode 2 per-line colour attribute table missing
- Command engine exact semantics differ from Mode0 blitter
- Text modes T1/T2 (40×24, 80×24) not supported
- No horizontal scroll on V9938 (V9958 adds it)
- Interlace modes not supported by Mode0

---

## 4. Tier 3 Adapters

### 4.1 Genesis / Mega Drive

**Tier:** 3  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** No — excluded until sprite expansion  
**Archive:** `archive/adapters/ADAPTER_GENESIS.md`

The Genesis VDP provides three background layers (Plane A, Plane B, Window) plus 80 sprites arranged in a linked list with shadow/highlight effects. It supports per-line H-scroll, per-column V-scroll, and a DMA engine for VRAM/CRAM/VSRAM transfers.

**Key Mode0 mapping:** `SdramTileAttributeFetch`, `SdramTileFetch` (4bpp), `SpriteEvaluator`, `FourLayerCompositor`, CW-1 palette, `RasterTriggerUnit`, `BlitterEngine`.

**Honest gaps:**
- 80 sprite descriptors needed (Mode0 has 64)
- Window layer primitive missing in Mode0
- Shadow/highlight mode absent
- Linked-list sprite order vs Mode0 fixed index order
- Per-column vertical scroll not natively supported

---

### 4.2 Neo Geo

**Tier:** 3  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** No — excluded until massive sprite expansion  
**Archive:** `archive/adapters/ADAPTER_NEO_GEO.md`

Neo Geo uses a unique line-sprite architecture where the entire screen is built from sprites (380 in list, 96/line) plus a 40×32 Fix tile layer for HUD. Features include hardware H/V shrinking, 256 palettes × 16 colours (4096 entries), and external ROM storage for graphics.

**Key Mode0 mapping:** `SdramTileAttributeFetch` (Fix layer), `SdramTileFetch` (4bpp), `SpriteEvaluator`, CW-1 palette, `RasterTriggerUnit`.

**Honest gaps:**
- 380 sprite descriptors needed (Mode0 has 64)
- 96 sprites/line needed (Mode0 has 32)
- Sprite shrinking (H/V scaling) has no Mode0 equivalent
- External ROM graphics must be pre-loaded to SDRAM
- Palette may need expansion from 512 to 4096 entries

---

## 5. Tier 4 Adapters

### 5.1 SNES / Super Famicom

**Tier:** 4  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** No  
**Archive:** `archive/adapters/ADAPTER_SNES.md`

The SNES PPU1/PPU2 support up to 4 background layers plus sprites across modes 0–7, with 2bpp–8bpp tiles, 128 sprites (32/line), Mode 7 affine transforms, HDMA per-line register updates, colour math add/subtract, and complex windowing (AND/OR/XOR).

**Key Mode0 mapping:** `SdramTileAttributeFetch`, `SdramTileFetch` (2/4/8bpp), `SpriteEvaluator`, `FourLayerCompositor`, CW-1 palette, `RasterTriggerUnit`, `Copper` (HDMA proxy), `WindowUnit`.

**Honest gaps:**
- 128 sprite descriptors needed (Mode0 has 64)
- Mode 7 affine transform has no Mode0 equivalent
- Colour math (add/sub/blend) missing
- Interlace output not supported
- Offset-per-tile scroll may not map natively

---

### 5.2 Amiga OCS/ECS

**Tier:** 4  
**Status:** Spec drafted — audit PASS #9192  
**Default bitstring:** No  
**Archive:** `archive/adapters/ADAPTER_AMIGA.md`

Commodore Amiga OCS/ECS uses 1–6 bitplanes (2–64 colours) via Agnus/Denise/Paula custom chips. It features 8 hardware sprites, the iconic Copper co-processor for beam-synchronous effects, and a Blitter with 256 minterms. HAM mode delivers 4096 on-screen colours and EHB provides 64 via half-brightness.

**Key Mode0 mapping:** `PlanarLineFetch` (bitplanes), `SpriteEvaluator` (8 sprites), `RasterTriggerUnit`, `Copper` (R3), `BlitterEngine` (Task 49), `WindowUnit`, CW-1 palette RAM.

**Honest gaps:**
- HAM mode: no Mode0 equivalent decoder
- EHB mode: not a native Mode0 feature
- 640-pixel hires horizontal may exceed Mode0 output capabilities
- Sprite DMA fetch absent; host must write descriptors
- Blitter minterms may not map fully to Mode0 blitter

---

## 6. Substrate Blocker Summary

This table distills the prose above into actionable substrate gaps.

| Substrate Gap | Task / Capability | Unblocks These Adapters |
|---------------|-------------------|------------------------|
| Sprite descriptor 64→80 | Task 2b extension | Genesis, PC Engine |
| Sprite descriptor 80→128 | — | SNES |
| Sprite descriptor 128→380 | — | Neo Geo |
| Sprite per-line 32→96 | — | Neo Geo |
| Palette 512→4096 | — | Neo Geo |
| 4bpp tile decoder verify | — | SMS/GG v1.1 |

**Rules:**
- `Task / Capability` = the specific substrate work that closes the gap
- `Unblocks These Adapters` = all adapters that become more honest when this gap closes

---

## 7. Adapter Spec Template

Every platform adapter MUST include these sections when drafted:

1. **Platform video hardware study**
2. **Pipeline decomposition** (fetch → decode → staging → sprite eval → composition → palette → beam control → host plane)
3. **Mode0 mapping** (platform function → Mode0 primitive → adapter responsibility)
4. **MCU-visible adapter contract** (registers, init flow, asset upload, runtime control, status/IRQ)
5. **Honest gaps** (what's missing, approximate, or out of scope)
6. **Development plan** (order, prerequisites, proof plan, resource/stop-line)

Full historical specs containing all six sections are archived in `PROJECT_PLAN/archive/adapters/`.
