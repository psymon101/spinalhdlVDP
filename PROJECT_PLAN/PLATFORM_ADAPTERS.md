# Platform Adapter Compilation

**Version:** 1.1-draft  
**Author:** CoralReef / TopazCliff (consolidation update)  
**Date:** 2026-05-12  
**Status:** Canonical active adapter document  
**Governing directive:** BronzeGate #9421, BronzeGate #9777

This document is the **central summary/index** for all platform adapters. The **live adapter contract** for each platform lives in its canonical knowledge file under `kb/<Adapter>/README.md`.

If any adapter spec disagrees with `TASKS.md` on execution priority, `TASKS.md` wins.

---

## 1. Adapter Index

| # | Platform | Tier | Status | Canonical kb File | Notes |
|---|---|---|---|---|---|
| 1 | ZX Spectrum | 1 | ✅ Complete | [`kb/ZX_Spectrum/README.md`](../kb/ZX_Spectrum/README.md) | v1/v2 proven; v3 border in progress |
| 2 | Commodore 64 | 1 | ✅ Complete | [`kb/Commodore64/README.md`](../kb/Commodore64/README.md) | Smoke test proven; Task 40b gaps documented |
| 3 | Atari ST | 1 | ✅ Spec drafted | [`kb/AtariST/README.md`](../kb/AtariST/README.md) | Lowest-cost Tier 1; planar + raster only |
| 4 | NES / Famicom | 2 | ✅ Spec drafted | [`kb/NES/README.md`](../kb/NES/README.md) | Highest leverage; needs sprite expansion |
| 5 | TMS9918-family / MSX1 | 1 | ✅ Spec drafted | [`kb/TMS9918/README.md`](../kb/TMS9918/README.md) | Family doc covers base + SMS delta + GG delta |
| 6 | Master System / Game Gear | 1/2 | ⚠️ v1 only | [`kb/SMS_GG/README.md`](../kb/SMS_GG/README.md) | VDP evolution of TMS |
| 7 | PC Engine / TurboGrafx-16 | 2 | ✅ PASS #9192 | [`kb/PC_Engine/README.md`](../kb/PC_Engine/README.md) | Standalone; needs sprite expansion for honest support |
| 8 | MSX2 | 2 | ✅ PASS #9192 | [`kb/MSX2_V9938/README.md`](../kb/MSX2_V9938/README.md) | Standalone; V9938-based; command engine gap |
| 9 | Genesis / Mega Drive | 3 | ✅ PASS #9192 | [`kb/Genesis/README.md`](../kb/Genesis/README.md) | Excluded from default bitstream until sprite expansion |
| 10 | SNES / Super Famicom | 4 | ✅ PASS #9192 | [`kb/SNES/README.md`](../kb/SNES/README.md) | Excluded from default bitstring |
| 11 | Amiga OCS/ECS | 4 | ✅ PASS #9192 | [`kb/Amiga_OCS_ECS/README.md`](../kb/Amiga_OCS_ECS/README.md) | Excluded from default bitstring; HAM mode gap |
| 12 | Neo Geo | 3 | ✅ PASS #9192 | [`kb/NeoGeo/README.md`](../kb/NeoGeo/README.md) | Excluded from default bitstring until sprite expansion |

---

## 2. Adapter Honesty Matrix

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

## 3. Per-Adapter Summary

For full adapter contracts, see the canonical `kb/<Adapter>/README.md` files above. The summaries below list only status and honest gaps.

### 3.1 ZX Spectrum
- **Status:** Implemented + proven (v3.8 + E3.45 polish, closure #8976)
- **Honest gaps:** None for v1. Border effects and ULA+ are deferred.

### 3.2 Commodore 64
- **Status:** Implemented + proven (smoke test proven; Task 40b gaps documented)
- **Honest gaps:** Sprite-sprite collision detector (Task 54) enhances but is not required for baseline honesty.

### 3.3 Atari ST
- **Status:** Spec drafted — lowest-risk Tier 1 target
- **Honest gaps:** Immediate palette effect (safe-boundary commits), no hardware scroll, STE Blitter semantics differ.

### 3.4 TMS9918 Family (MSX1 / SMS / GG)
- **Status:** Spec drafted — family doc
- **Honest gaps:** Hardware sprite-sprite collision not available; TMS text mode approximated with 8×8; SMS/GG sprite zoom not directly supported; line interrupt auto-reload may need substrate support.

### 3.5 NES / Famicom
- **Status:** Spec drafted
- **Honest gaps:** Colour emphasis has no Mode0 equivalent; mapper IRQs are cartridge-specific; PPU open-bus read quirks not emulated.

### 3.6 PC Engine / TurboGrafx-16
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** SATB DMA not present; per-tile palette bank may not be natively supported; Y/X coordinate offsets need adapter translation.

### 3.7 MSX2
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** Sprite Mode 2 per-line colour attribute table missing; command engine exact semantics differ from Mode0 blitter; interlace modes not supported.

### 3.8 Genesis / Mega Drive
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** 80 sprite descriptors needed; Window layer primitive missing; shadow/highlight mode absent; linked-list sprite order vs fixed index order.

### 3.9 Neo Geo
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** 380 sprite descriptors needed; 96 sprites/line needed; sprite shrinking has no Mode0 equivalent; external ROM graphics must be pre-loaded to SDRAM.

### 3.10 SNES / Super Famicom
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** 128 sprite descriptors needed; Mode 7 affine transform has no Mode0 equivalent; colour math (add/sub/blend) missing; interlace output not supported.

### 3.11 Amiga OCS/ECS
- **Status:** Spec drafted — audit PASS #9192
- **Honest gaps:** HAM mode has no Mode0 equivalent decoder; EHB mode is not a native Mode0 feature; 640-pixel hires horizontal may exceed Mode0 output capabilities.

---

## 4. Substrate Blocker Summary

| Substrate Gap | Task / Capability | Unblocks These Adapters |
|---------------|-------------------|------------------------|
| Sprite descriptor 64→80 | Task 2b extension | Genesis, PC Engine |
| Sprite descriptor 80→128 | — | SNES |
| Sprite descriptor 128→380 | — | Neo Geo |
| Sprite per-line 32→96 | — | Neo Geo |
| Palette 512→4096 | — | Neo Geo |
| 4bpp tile decoder verify | — | SMS/GG v1.1 |

---

## 5. Adapter Spec Template

Every platform adapter MUST have one canonical knowledge file at
`kb/<Adapter>/README.md`. That file is the live adapter contract and must
include these sections when drafted:

1. **Video model summary**
2. **Supported features**
3. **Unsupported / deferred features**
4. **Adapter register surface**
5. **Mode0 mapping** (platform function → Mode0 primitive → adapter responsibility)
6. **Host memory layout**
7. **Firmware workflow** (init flow, asset upload, runtime control, status/IRQ)
8. **Proof / validation plan**
9. **Known gaps / gotchas**
10. **Reference links**

`PROJECT_PLAN/` should summarize status and priority only, then point back to
the adapter's `kb/` file. Full historical specs remain archived in
`PROJECT_PLAN/archive/adapters/`.
