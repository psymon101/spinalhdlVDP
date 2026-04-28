# Platform Adapter Spec Index

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-28  
**Status:** In progress — first batch landing  
**Governing directive:** BronzeGate #8688

---

## 1. Purpose

This index tracks the per-platform VDP pipeline research and Mode0 adapter mapping specs for all target platforms. Each platform gets a dedicated spec file covering hardware study, pipeline decomposition, Mode0 mapping, MCU-visible contract, honest gaps, and development plan.

## 2. Platform Coverage

| # | Platform | Tier | Status | Spec File | Notes |
|---|---|---|---|---|---|
| 1 | ZX Spectrum | 1 | ✅ Complete | `artifacts/TASK_50_ZX_SPECTRUM_ADAPTER.md` | v1/v2 proven; v3 border in progress |
| 2 | Commodore 64 | 1 | ✅ Complete | `artifacts/TASK_40_FIRST_PLATFORM_ADAPTER.md` | Smoke test proven; Task 40b gaps documented |
| 3 | Atari ST | 1 | ✅ Complete | `artifacts/ADAPTER_ATARI_ST.md` | Lowest-cost Tier 1; planar + raster only |
| 4 | NES / Famicom | 2 | ✅ Complete | `artifacts/ADAPTER_NES.md` | Highest leverage; needs sprite expansion |
| 5 | TMS9918-family / MSX1 | 1 | ⏳ Pending | `artifacts/ADAPTER_TMS9918_FAMILY.md` | Family doc + MSX1 delta |
| 6 | Master System / Game Gear | 2 | ⏳ Pending | (included in TMS-family doc or separate) | VDP evolution of TMS |
| 7 | PC Engine / TurboGrafx-16 | 2 | ⏳ Pending | `artifacts/ADAPTER_PC_ENGINE.md` | Standalone |
| 8 | MSX2 | 2 | ⏳ Pending | `artifacts/ADAPTER_MSX2.md` | Standalone; V9938-based |
| 9 | Genesis / Mega Drive | 3 | ⏳ Pending | `artifacts/ADAPTER_GENESIS.md` | Excluded from default bitstream until sprite expansion |
| 10 | SNES / Super Famicom | 4 | ⏳ Pending | `artifacts/ADAPTER_SNES.md` | Excluded from default bitstream |
| 11 | Amiga OCS/ECS | 4 | ⏳ Pending | `artifacts/ADAPTER_AMIGA.md` | Excluded from default bitstream |
| 12 | Neo Geo | 3 | ⏳ Pending | `artifacts/ADAPTER_NEO_GEO.md` | Excluded from default bitstream until sprite expansion |

## 3. Spec Template

Every platform spec file MUST include these sections:

1. **Platform video hardware study**
2. **Pipeline decomposition** (fetch → decode → staging → sprite eval → composition → palette → beam control → host plane)
3. **Mode0 mapping** (platform function → Mode0 primitive → adapter responsibility)
4. **MCU-visible adapter contract** (registers, init flow, asset upload, runtime control, status/IRQ)
5. **Honest gaps** (what's missing, approximate, or out of scope)
6. **Development plan** (order, prerequisites, proof plan, resource/stop-line)

## 4. Batching Plan

**Batch 1 (landing now):** Atari ST + NES  
**Batch 2 (next):** TMS9918-family / MSX1 / SMS / GG (family doc)  
**Batch 3 (next):** PC Engine + MSX2  
**Batch 4 (deferred):** Genesis, SNES, Amiga, Neo Geo (Tier 3/4; excluded from default bitstream)

## 5. Family Grouping Rationale

**TMS9918-family group:** TMS9918A (ColecoVision, SG-1000, MSX1), VDP-derived Master System VDP, VDP-derived Game Gear VDP. These share a common lineage: tile-based backgrounds, sprite patterns, fixed palettes. The family doc covers the base architecture; platform deltas cover SMS palette banks, GG viewport, etc.

**All other platforms:** Standalone specs. Each has a sufficiently distinct video subsystem that a family doc would create more confusion than savings.
