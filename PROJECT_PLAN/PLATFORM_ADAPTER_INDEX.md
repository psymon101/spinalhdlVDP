# Platform Adapter Spec Index

**Version:** 1.2-draft  
**Author:** CoralReef (consolidation update)  
**Date:** 2026-05-07  
**Status:** All 12 platform specs consolidated — canonical doc active  
**Governing directive:** BronzeGate #9421

---

## 1. Purpose

This index tracks the per-platform VDP pipeline research and Mode0 adapter mapping specs for all target platforms. Each platform gets a dedicated spec covering hardware study, pipeline decomposition, Mode0 mapping, MCU-visible contract, honest gaps, and development plan.

**Canonical active document:** `PROJECT_PLAN/PLATFORM_ADAPTERS.md`

**Full historical specs:** `PROJECT_PLAN/archive/adapters/`

---

## 2. Platform Coverage

| # | Platform | Tier | Status | Canonical Section | Notes |
|---|---|---|---|---|---|
| 1 | ZX Spectrum | 1 | ✅ Complete | `PLATFORM_ADAPTERS.md` §2.1 | v1/v2 proven; v3 border in progress |
| 2 | Commodore 64 | 1 | ✅ Complete | `PLATFORM_ADAPTERS.md` §2.2 | Smoke test proven; Task 40b gaps documented |
| 3 | Atari ST | 1 | ✅ Complete | `PLATFORM_ADAPTERS.md` §2.3 | Lowest-cost Tier 1; planar + raster only |
| 4 | NES / Famicom | 2 | ✅ Complete | `PLATFORM_ADAPTERS.md` §3.1 | Highest leverage; needs sprite expansion |
| 5 | TMS9918-family / MSX1 | 1 | ✅ Complete | `PLATFORM_ADAPTERS.md` §2.4 | Family doc covers base + SMS delta + GG delta |
| 6 | Master System / Game Gear | 2 | ✅ Complete | `PLATFORM_ADAPTERS.md` §2.4 | VDP evolution of TMS |
| 7 | PC Engine / TurboGrafx-16 | 2 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §3.2 | Standalone; needs sprite expansion for honest support |
| 8 | MSX2 | 2 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §3.3 | Standalone; V9938-based; command engine gap |
| 9 | Genesis / Mega Drive | 3 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §4.1 | Excluded from default bitstream until sprite expansion |
| 10 | SNES / Super Famicom | 4 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §5.1 | Excluded from default bitstring |
| 11 | Amiga OCS/ECS | 4 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §5.2 | Excluded from default bitstring; HAM mode gap |
| 12 | Neo Geo | 3 | ✅ PASS #9192 | `PLATFORM_ADAPTERS.md` §4.2 | Excluded from default bitstring until sprite expansion |

---

## 3. Spec Template

Every platform spec MUST include these sections:

1. **Platform video hardware study**
2. **Pipeline decomposition** (fetch → decode → staging → sprite eval → composition → palette → beam control → host plane)
3. **Mode0 mapping** (platform function → Mode0 primitive → adapter responsibility)
4. **MCU-visible adapter contract** (registers, init flow, asset upload, runtime control, status/IRQ)
5. **Honest gaps** (what's missing, approximate, or out of scope)
6. **Development plan** (order, prerequisites, proof plan, resource/stop-line)

---

## 4. Batching Plan

**Batch 1 (landing now):** Atari ST + NES  
**Batch 2 (done):** TMS9918-family / MSX1 / SMS / GG (family doc)  
**Batch 3 (done):** PC Engine + MSX2 — drafted  
**Batch 4 (done):** Genesis, SNES, Amiga, Neo Geo — drafted (Tier 3/4; excluded from default bitstream)

---

## 5. Family Grouping Rationale

**TMS9918-family group:** TMS9918A (ColecoVision, SG-1000, MSX1), VDP-derived Master System VDP, VDP-derived Game Gear VDP. These share a common lineage: tile-based backgrounds, sprite patterns, fixed palettes. The family doc covers the base architecture; platform deltas cover SMS palette banks, GG viewport, etc.

**All other platforms:** Standalone specs. Each has a sufficiently distinct video subsystem that a family doc would create more confusion than savings.
