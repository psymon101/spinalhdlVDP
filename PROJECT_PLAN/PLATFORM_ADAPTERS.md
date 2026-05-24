# Platform Adapter Compilation (HISTORICAL)

**Status:** **HISTORICAL / SUPERSEDED** (2026-05-24)  
**Reason:** RTL-side platform adapters have been removed in the **Platform-Agnosticism Purge (#10567)**. All platform "personality" (register shims, initialization, and assets) now resides in `libvdp`. This document is preserved for reference during firmware-side implementation of the adapter sequences.

---

## 1. Executive Summary

The VDP IP has transitioned to a purely generic graphics IP. Previously, platform-specific adapters (C64, ZX Spectrum) were implemented as hardware shims. These have been stripped to recover logic budget and enforce a cleaner hardware/firmware boundary.

**New Model:**
- **RTL**: Generic Mode0 (registers, fetch, compositor).
- **Firmware (`libvdp`)**: Translates legacy platform writes into generic Mode0 register writes.

---

## 2. Relocation Matrix

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
| PC Engine | 2 | ✅ Yes | descCount 8→80 | SATB DMA, per-tile palette bank |
| SMS/GG | 1/2 | ⚠️ v1 only | Verify 4bpp tile decoder (v1.1) | Sprite zoom, line interrupt reload |
| MSX2 | 2 | ✅ Yes | — | Command engine quirks |
| Genesis | 3 | ⚠️ v1 only | descCount 8→80 | — |
| SNES | 4 | ⚠️ v1 only | descCount 8→128 | — |
| Neo Geo | 3 | 🔴 No | descCount 8→380, per-line 8→96, palette 512→4096 | — |
| Amiga | 4 | ✅ Yes | — | Copper wait/move exact timing |

**Rules:**
- `✅ Yes` = substrate supports honest v1 claim now
- `⚠️ v1 only` = honest for bounded v1, but higher-fidelity claims blocked
- `🔴 No` = cannot claim honest implementation until blocker closes
- `Blocked By` = substrate gap only; adapter-local simplifications go in `Acceptable Gaps`
- Every `Blocked By` entry must reference a specific task or substrate capability

---

## 3. Per-Adapter Summary

For status and honest gaps, see the Adapter Index (§1) and Honesty Matrix (§2) above. Full adapter contracts live in the canonical `kb/<Adapter>/README.md` files.

---

## 4. Substrate Blocker Summary

| Substrate Gap | Task / Capability | Unblocks These Adapters |
|---------------|-------------------|------------------------|
| Sprite descriptor 8→80 | Task 2b extension (deferred) | Genesis, PC Engine |
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
