# Mode0 Substrate Assessment Compilation

**Updated:** 2026-05-24
**Purpose:** Single canonical assessment document for the `spinalhdlVDP` Mode0 substrate. Consolidates all active assessment content into one indexed file. (RTL Platform-Agnosticism Purge #10567 active).

This document replaces the following previously-scattered assessment files (now archived in `PROJECT_PLAN/archive/assessments/`):
- `MODE0_FETCH_ENVELOPE_ASSESSMENT.md` → §1 Fetch Envelope
- `MODE0_SPRITE_ENVELOPE_ASSESSMENT.md` → §2 Sprite Envelope
- `MODE0_COLOR_WINDOW_BEAM_ASSESSMENT.md` → §3 Color, Window, and Beam-Driven Automation
- `MODE0_PLATFORM_COVERAGE_AUDIT.md` → §4 Platform Coverage Audit
- `MODE0_UNIVERSAL_SPRITE_ENGINE_GAP.md` → §5 Universal Sprite Engine Gaps

If any assessment disagrees with `TASKS.md` on execution priority, `TASKS.md` wins.

---

## Table of Contents

- [§1 — Fetch Envelope Assessment](#1-fetch-envelope-assessment)
- [§2 — Sprite Envelope Assessment](#2-sprite-envelope-assessment)
- [§3 — Color, Window, and Beam-Driven Automation Assessment](#3-color-window-and-beam-driven-automation-assessment)
- [§4 — Platform Coverage Audit](#4-platform-coverage-audit)
- [§5 — Universal Sprite Engine Gaps](#5-universal-sprite-engine-gaps)
- [§6 — Resource and Toolchain Gotchas](#6-resource-and-toolchain-gotchas)
- [§7 — RTL Agnosticism Audit (2026-05-24)](#7-rtl-agnosticism-audit-2026-05-24)

---

---

> **Erratum (2026-05-19):** This assessment describes the *target* substrate sizing pursued by Tasks 2a/2c/2b. The **live shipped substrate** was rolled back to `descCount=8`, `visiblePerLine=8` per Task 57 Path 5A (`VdpTop.scala:1399/1413`). Claims below that the substrate "now supports" `64/32` or that `32/64` is the "current default" are stale. BrightForge's active redesign-feasibility lane (`#10340`) is authoritative for the next substrate target. Until that lane lands, the honest hardware floor is `8/8`.

# §1 — Fetch Envelope Assessment

> Verdict: Tile+attribute and bitmap+attribute are **Strong**; planar is **Usable but limited** (needs 5–6 planes for Amiga/ST); shuffled is honest with adapter-local address calc. **(*) Relocating to libvdp.**


**Assessment version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Commit:** TBD  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

---

## 1. Executive Summary

| Question | Ruling |
|---|---|
| Q1 — Can planar fetch support Amiga/ST planning without a second engine? | **PARTIAL — substrate hardening required** before serious Amiga adapter claims |
| Q2 — Can shuffled/bitmap+attribute fetch support Spectrum-class work? | **YES — honest with minor adapter-local semantics** |
| Q3 — Are remaining problems substrate or adapter-local? | **Mixed — planar bit-depth and multi-layer fetch are substrate; exact memory maps and clash rules are adapter-local** |
| Q4 — Can next hardening step be described with explicit budget? | **YES — see §6 recommendations** |

**Top-line recommendation:** Open a bounded **Planar Fetch Hardening** task before any serious Amiga/ST adapter lane. Tile+attribute and bitmap+attribute paths are already strong enough for their respective adapter families.

---

## 2. Evidence Base

### 2.1 Current Codebase State

Reviewed files:
- `hw/spinal/spinalhdlvdp/SdramTileAttributeFetch.scala`
- `hw/spinal/spinalhdlvdp/BitmapFetch.scala`
- `hw/spinal/spinalhdlvdp/BitmapRowFetch.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala` (fetch integration)
- `hw/spinal/spinalhdlvdp/FetchSlotScheduler.scala`
- `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` (arbiter)

### 2.2 External Research

- Amiga OCS/ECS bitplane DMA architecture (pouët.net, reaktor.com, A.D.A. demoscene archive)
- ZX Spectrum ULA screen memory layout and attribute model (breakintoprogram.co.uk, espamatica.com, overtakenbyevents.com)
- Atari ST Shifter video modes and planar framebuffer (atari-wiki.com, arnaud-carre.github.io)

### 2.3 Planning Stack References

- `MODE0_MAX_CAPABILITIES.md` §4 (fetch system max envelope)
- `MODE0_COVERAGE_MATRIX.md` (current fetch classifications)
- `MODE0_STOPLINES.md` (resource baseline and green/yellow/red zones)

---

## 3. Q1 — Planar Fetch for Amiga/ST-Oriented Planning

### 3.1 Current Planar Capability

The codebase implements planar fetch **inside** `SdramTileAttributeFetch.scala` as a tile-decode mode, not as a standalone fetch primitive. Two variants exist:

| Mode | Description | Plane Count | Bits/Pixel |
|---|---|---|---|
| `0x01` | NES-style 2-plane 2bpp | 2 | 2 |
| `0x02` | Amiga-style shuffled/bitplane | 2 | 2 |

Both modes reconstruct pixels by sampling the same bit-offset from two planes:
```scala
val px2Planar = (plane1Bits(planarBitIdx) ## plane0Bits(planarBitIdx)).asBits
```

**Critical limit:** exactly **2 planes maximum**. There is no path for 3+ bitplanes.

### 3.2 Amiga OCS/ECS Pressure

Amiga bitplane architecture (verified from demoscene docs and reaktor.com):
- Standard modes use **1–5 bitplanes** (2–32 colors)
- Extra Half-Brite (EHB): 6 bitplanes → 64 colors
- Dual Playfield: two independent layers, each up to 3 bitplanes
- AGA extends to 8 bitplanes / 256 colors

**Gap:** The current 2-plane limit supports only 4 colors per pixel. This covers:
- Atari ST medium res (640×200, 2 bitplanes, 4 colors) ✓
- C64 multicolor-like modes (2bpp) ✓

But it does **NOT** cover:
- Amiga standard low-res (320×200, 3–5 bitplanes, 8–32 colors) ✗
- Atari ST low-res (320×200, 4 bitplanes, 16 colors) ✗
- Any EHB or dual-playfield configuration ✗

### 3.3 Atari ST Pressure

Atari ST Shifter modes (verified from atari-wiki.com):
- Low: 320×200, **4 bitplanes**, 16 colors
- Medium: 640×200, **2 bitplanes**, 4 colors
- High: 640×400, 1 bitplane, monochrome

The current 2-plane implementation covers medium and high but **not** the 4-plane low-res mode, which is the most commonly used ST mode for games and demos.

### 3.4 SDRAM Bandwidth Considerations

Current planar fetch uses the same sequential byte-read pattern as tile fetch:
- Per tile: 4 individual SDRAM transactions (map + attr + rowWord0 + rowWord1)
- No burst mode
- For Amiga-style planar, a 320-pixel line at 1 bitplane = 40 bytes; at 5 bitplanes = 200 bytes
- At 640×480@60 with current ~2050 SDRAM cycles/line, 5 bitplanes × 40 bytes = 200 bytes/line is feasible in raw bandwidth
- However, the current architecture fetches **per-tile** (16×16 pixel blocks), not per-scanline. Adapting planar to scanline-oriented bitplane DMA would require restructuring the fetch FSM

### 3.5 Q1 Ruling

**PARTIAL — substrate hardening required.**

The current planar fetch is an honest 2-plane proof, not a general bitplane primitive. It can serve as architectural groundwork, but a serious Amiga or ST low-res adapter would require:
- Increasing the plane count from 2 to at least 5 (preferably 6 for EHB coverage)
- Restructuring fetch from per-tile to per-scanline or adding a dedicated bitplane row fetcher
- Proving SDRAM bandwidth for multi-plane fetch under concurrent sprite/L0 load

**This is a shared-substrate gap, not an adapter-local quirk.** The plane-count limit is baked into `SdramTileAttributeFetch`'s pixel-reconstruction logic.

---

## 4. Q2 — Shuffled / Bitmap+Attribute for Spectrum-Class Work

### 4.1 Current Bitmap+Attribute Capability

`BitmapRowFetch.scala` implements a dedicated bitmap row fetcher with:
- 1bpp and 2bpp decode modes
- 80 bytes bitmap + 80 bytes attributes per line
- 128-byte power-of-two row stride
- 32-line vertical buffer

`BitmapFetch.scala` decodes:
- 1bpp: ZX Spectrum / C64 hires style (one bit per pixel, 8 pixels per byte)
- 2bpp: C64 multicolor style (two bits per pixel, 4 pixels per byte)

Attribute decode supports:
- `{flash, bright, paper[2:0], ink[2:0]}` for 1bpp (Spectrum-style)
- `{bg, fg1, fg2, fg3}` for 2bpp (C64 multicolor-style)

### 4.2 ZX Spectrum ULA Pressure

Spectrum display characteristics (verified from breakintoprogram.co.uk and espamatica.com):
- **Resolution:** 256×192 pixels
- **Bitmap:** 6144 bytes at 0x4000, 1bpp, 32 bytes × 192 rows
- **Attributes:** 768 bytes at 0x5800, 32×24 grid of 8×8 cells
- **Address swizzle:** Non-linear. Pixel address = `0b010TTSSS RRCCCCC`
  - T = third (0–2), S = scanline-in-char (0–7), R = row-in-third (0–7), C = column (0–31)
- **Attribute format:** `FBPPPIII` (Flash, Bright, Paper 3-bit, Ink 3-bit)
- **Color constraint:** 2 colors per 8×8 cell (attribute clash)

### 4.3 Honest Assessment

**Bitmap decode:** The current 1bpp mode with 8 pixels/byte is architecturally identical to Spectrum bitmap decode. The 80-byte fetch width maps cleanly to Spectrum's 32 bytes/line (or 40 bytes with border). The substrate does not need to change for basic Spectrum bitmap rendering.

**Attribute decode:** The current attribute decode is more general than Spectrum needs. Spectrum's `FBPPPIII` fits within the existing attribute byte model. The specific interpretation (bright bit, flash bit, 3-bit paper/ink) is adapter-local semantics.

**Non-linear addressing (the swizzle):** The current `BitmapRowFetch` uses a linear `lineReg << 7` + byte offset addressing model. Spectrum's non-linear layout requires an address swizzler. However:
- The `SdramTileAttributeFetch` already implements address swizzling for shuffled planar mode (`tileRowByteAddr` with bit-manipulation)
- Bit-manipulation is "free" in FPGA logic
- A Spectrum adapter would supply its own address-calculation function; the substrate only needs to accept a computed row base and stride

**Buffer sizing:** The current 32-line `MaxLines` is too small for a full Spectrum screen (192 lines). This is a configuration parameter, not an architecture limit. A Spectrum adapter would set `MaxLines = 192` and `BitmapBytesPerRow = 32`.

### 4.4 Q2 Ruling

**YES — honest with minor adapter-local semantics.**

The current bitmap+attribute substrate is strong enough for Spectrum-class work. What remains adapter-local:
- Exact memory map (0x4000/0x5800 base addresses)
- Address swizzle function
- Attribute interpretation (`FBPPPIII`)
- Border/overscan presentation
- Attribute clash visibility (a presentation quirk, not a fetch gap)

No substrate fork is required. The adapter would configure existing bitmap+attribute parameters and supply platform-specific address calculation.

---

## 5. Q3 — Substrate vs Adapter-Local Problem Classification

### 5.1 Substrate-Hardening Issues (Shared)

| Issue | Current State | Impact | Proposed Fix |
|---|---|---|---|
| **Planar plane count limit (2 max)** | Hardcoded in `SdramTileAttributeFetch` pixel reconstruction | Blocks Amiga 3-5bp and ST low-res 4bp | Extend to 5–6 planes with configurable plane count |
| **No dedicated bitplane row fetcher** | Planar is a tile-decode mode, not a scanline fetcher | Amiga/ST expect scanline-oriented bitplane DMA | Add `BitplaneRowFetch` primitive or restructure planar as standalone fetcher |
| **No wide-read SDRAM path** | All fetches use 8-bit `dout`; `dout32` 32-bit aperture unused | Inefficient for multi-plane; wastes SDRAM cycles | Add arbiter FSM path that uses `dout32` for contiguous row reads (4 bytes/transaction, no controller change) |
| **Only L0 has SDRAM fetch** | L1–L3 are on-chip `BasicPatternSource` only | Multi-layer Amiga/ST needs multiple SDRAM-backed layers | Extend arbiter to support multi-layer SDRAM fetch (high architectural cost) |
| **Scheduler slots underutilized** | 2 of 8 slots used; both for client 0 (tile) | Headroom exists but no multi-client allocation policy | Define slot allocation for multi-fetch scenarios |

### 5.2 Adapter-Local Issues (Platform-Specific)

| Issue | Why It's Adapter-Local |
|---|---|
| **Spectrum address swizzle** | Only Spectrum uses this exact `010TTSSS RRCCCCC` layout |
| **Spectrum attribute clash** | Presentation quirk of the target platform; substrate provides 1bpp+attr honestly |
| **Amiga bitplane modulo / scrolling** | Amiga-specific `BPL1MOD`/`BPL2MOD` register semantics |
| **Amiga display window (`DIWSTRT`/`DIWSTOP`)** | Amiga-specific display-window registers |
| **Amiga copper list integration** | Copper is a beam-driven control primitive; fetch substrate should not care about copper commands |
| **ST Shifter resolution switching** | ST-specific mode register (low/med/high) |
| **Exact palette mapping per platform** | Adapter translates platform indices to Mode0 palette banks |

### 5.3 Q3 Ruling

**Mixed.** The highest-impact remaining gaps are substrate-level (plane count, burst reads, multi-layer SDRAM). The presentation and register-map quirks are correctly adapter-local. No adapter should need a substrate fork for register semantics, but serious Amiga/ST adapters would be blocked by the 2-plane limit and single-SDRAM-layer constraint.

---

## 6. Q4 — Next Hardening Step with Explicit Budget

### 6.1 Recommended Next Task

**Open: Mode0 Planar Fetch Hardening**

Bounded scope:
1. Extend planar pixel reconstruction from 2 planes to **5 planes** (covers Amiga OCS standard and ST low-res)
2. Add a **dedicated `BitplaneRowFetch` primitive** that fetches scanline-oriented bitplane data from SDRAM
3. Add **burst-read support** for contiguous bitplane rows using the existing `dout32` 32-bit read aperture (4 bytes per read transaction, no controller change)
4. Prove SDRAM bandwidth for 5-plane fetch at 320×200 within the existing 640×480@60 timing
5. Do NOT implement multi-layer SDRAM fetch (out of scope for this hardening step)

### 6.2 Resource Budget Estimate

Based on `MODE0_STOPLINES.md` baseline:

| Resource | Current | Green Ceiling | Available | Estimated Hardening Cost | Zone After |
|---|---|---|---|---|---|
| LUT/ALU/ROM16 | 9,566 | ~6,739 (65%) | ~2,827 headroom to green | +400–600 LUT (plane mux + burst FSM) | Still green |
| FF | 6,033 (39%) | ~10,109 (65%) | ~4,076 headroom | +200–300 FF (row buffers + burst regs) | Still green |
| BSRAM | 5 / 46 (11%) | 23 (50%) | 18 remaining | +0–1 BSRAM (if row buffer grows) | Still green |
| DSP | 18 / 24 (75%) | ~17 (70%) | Already yellow | +0 DSP (fetch is logic, not math) | Still yellow |

**Assessment:** This hardening step stays in the **green zone** for LUT/FF/BSRAM and does not worsen the already-yellow DSP position.

### 6.3 SDRAM Bandwidth Budget

Current baseline (per line at 640×480@60):
- ~1286 SDRAM cycles available per line (@ 40.5 MHz, ~800 pixel cycles @ 25.2 MHz). The 64.8 MHz figure was a retired target and is kept here only as historical context.
- Current tile fetch: ~164 transactions/line × ~5 cycles = ~820 SDRAM cycles

Proposed 5-plane bitplane fetch (320×200 active window within 640×480 frame):
- 320 pixels / 8 = 40 bytes per plane
- 5 planes = 200 bytes per line
- Using `dout32` 32-bit read aperture: each read transaction yields 4 bytes
- 200 bytes / 4 bytes per transaction = **50 read transactions**
- 50 transactions × ~5 cycles = **~250 SDRAM cycles**
- **Well within budget.** (~250 cycles vs. ~2050 available per line, leaving ~1800 for sprite/L0/concurrent load)

**Correction note:** An earlier version of this assessment assumed a true burst-mode controller (multiple bytes per row activation without precharge). The current nand2mario controller is byte-based and non-bursting. The feasible interpretation is using the existing `dout32[31:0]` 32-bit read port, which gives 4 bytes per transaction without controller modification. True burst mode would require controller replacement or redesign and is explicitly out of scope per PM #8505.

### 6.4 Follow-On Task Recommendation

If Planar Fetch Hardening passes audit:
- **Then** open Amiga or Atari ST adapter lanes with honest planar substrate claims
- **Else** if multi-layer SDRAM fetch proves necessary for Amiga dual-playfield, that becomes a separate bounded task with its own stop-line review

If Planar Fetch Hardening is deferred:
- Adapter lanes must honestly claim "planar limited to 2 planes / 4 colors" or use bitmap/chunky fallback
- This is acceptable for ST medium-res and some Amiga demo effects but not for general Amiga/ST game emulation

---

## 7. Comparison Against Coverage Matrix

| Category | Matrix Status | Assessment Finding | Disposition |
|---|---|---|---|
| tile + attribute | Strong | Confirmed strong. No substrate changes needed. | **No action** |
| bitmap + attribute | Strong | Confirmed strong for Spectrum/C64. Adapter-local semantics only. | **No action** |
| planar | Usable | Honest for 2-plane/4-color. Needs 5-plane extension for Amiga/ST. | **Harden** |
| shuffled / non-linear | Usable | Honest for current proofs. Spectrum swizzle is adapter-local address calc. | **No action** |
| scheduler / memory arbitration | Usable | Sufficient for single-layer. Multi-layer is future task, not this hardening. | **Monitor** |

---

## 8. Honest Residual Gaps

1. **Multi-layer SDRAM fetch:** Only L0 can fetch from SDRAM. A true Amiga dual-playfield or Genesis-style multi-layer background would need multiple SDRAM-backed layers. This is explicitly **not** in scope for the recommended planar hardening task.

2. **Affine fetch tuning:** Task 19 / Scenario 37 proved affine groundwork, but deeper affine tuning (SNES Mode 7 pressure) is intentionally deferred per `MODE0_COVERAGE_MATRIX.md`.

3. **Wide-read arbiter path:** The recommended `dout32`-based read path for bitplane fetch does not yet exist in the SDRAM arbiter. Adding it requires arbiter FSM changes only — no SDRAM controller modification.

4. **DSP yellow zone:** DSP is already at 75% (yellow). Any future feature adding DSP usage (e.g., more affine math) needs explicit justification.

---

## 9. Conclusion

The Mode0 fetch envelope is **strong for tile+attribute and bitmap+attribute**, **usable but limited for planar**, and **honest for shuffled/non-linear with adapter-local address calculation**.

The project should:
1. **Accept** that bitmap+attribute and tile+attribute are ready for adapter lanes now
2. **Open a bounded Planar Fetch Hardening task** (5 planes, `dout32` wide reads via arbiter FSM, dedicated row fetcher) before serious Amiga/ST adapter claims
3. **Defer** multi-layer SDRAM fetch to a later task with its own stop-line review

This preserves the architectural rule that `Mode0` owns the reusable superset while adapters own platform-specific semantics.

---

# §2 — Sprite Envelope Assessment

> Verdict: Strong for NES/C64/Amiga (≤8/line). **Usable but insufficient for Genesis/SNES** (needs visiblePerLine=32 + 5 new descriptor fields). Recommendations implemented via Tasks 2a/2c/2b.


**Assessment version:** 1.1-updated  
**Author:** CoralReef  
**Date:** 2026-04-25 (updated 2026-05-06)  
**Commit:** `31e3de0` (assessment base); recommendations implemented in Tasks 2a/2c/2b  
**Audit:** PASS #8577  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

**Implementation note:** The recommendations in this assessment were pursued via Tasks 2a, 2c, and 2b (Sprite Capacity Expansion epic) but **never shipped at 64/32**. The live substrate was rolled back to `descCount=8`, `visiblePerLine=8` per Task 57 Path 5A. The 64/32 sizing remains a **parked target** pending BrightForge's substrate-redesign feasibility lane (`#10340`). See `TASKS.md` and `VdpTop.scala:1399/1413` for the shipped reality.

---

## 1. Executive Summary

This assessment answers the four acceptance questions defined in `TASK_MODE0_SPRITE_ENVELOPE_HARDENING.md` §9, using empirical evidence from the current codebase, external platform research, and `MODE0_STOPLINES.md` budget framing.

| Question | Ruling |
|---|---|
| Q1 — Is `visiblePerLine=8` sufficient? | **NO — increase to 32 recommended** (covers SNES; Neo Geo deferred). The 32/line target was **attempted** via Tasks 2a/2c/2b but **rolled back** to 8/8 per Task 57 Path 5A. A substrate redesign is required before 32/line can land. |
| Q2 — Which descriptor fields belong in shared substrate? | **flip H/V, palette bank (3b), priority bit, size select (2b)** — all shared |
| Q3 — Can current evaluator absorb extensions without a second engine? | **YES — two-pass architecture is fundamentally sound; no second engine needed** |
| Q4 — Exact stop-line-aware cost? | **+~900 LUT, +~1,200 FF, +0 DSP, +0–1 BSRAM** — stays green zone |

**Top-line recommendation:** The 32/64 substrate target was **attempted** via Tasks 2a/2c/2b but **rolled back** to 8/8 per Task 57 Path 5A due to PnR failure (51 k logic demand vs 20.7 k available). BrightForge's active redesign-feasibility lane (`#10340`) is the authorized path to unblock larger sizing. Until that lane lands, the honest hardware floor is `descCount=8`, `visiblePerLine=8` on Tang Nano 20K.

---

## 2. Evidence Base

### 2.1 Current Codebase State

Reviewed files:
- `hw/spinal/spinalhdlvdp/SpriteDescriptor.scala`
- `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala` (sprite instantiation + compositor integration)
- `hw/spinal/spinalhdlvdp/SpriteAttributes.scala`
- `hw/spinal/spinalhdlvdp/SpriteCollisionSim.scala`
- `hw/spinal/spinalhdlvdp/SpriteCapacitySim.scala`

### 2.2 External Research

- Sega Genesis VDP sprite architecture (segaretro.org, md.squee.co)
- SNES OAM/sprite architecture (snesdevwiki.net, fullsnes.htm)
- Neo Geo sprite system (wiki.neogeodev.org, mame driver docs)
- Amiga OCS/ECS sprite hardware (amiga-hardware.com, copper/sprite DMA docs)
- NES PPU sprite architecture (nesdev.com)

### 2.3 Planning Stack References

- `MODE0_MAX_CAPABILITIES.md` §5 (sprite system max envelope)
- `MODE0_COVERAGE_MATRIX.md` (current sprite classification = `Usable`)
- `MODE0_STOPLINES.md` (resource baseline and green/yellow/red zones)
- `TASK_MODE0_SPRITE_ENVELOPE_HARDENING.md` (this lane's artifact)

---

## 3. Q1 — Is `visiblePerLine=8` Sufficient?

### 3.1 Platform Requirements

| Platform | Max Sprites / Line | Current `visiblePerLine=8` | Gap |
|---|---|---|---|
| NES / Famicom | 8 | 8 | **none** ✓ |
| C64 | 8 | 8 | **none** ✓ |
| Amiga OCS/ECS | 8 (hardware) | 8 | **none** ✓ |
| Sega Genesis | 20 (hscan) / 16 (vscan) | 8 | **−12** ✗ |
| SNES / Super Famicom | 32 | 8 | **−24** ✗ |
| Neo Geo | 96 | 8 | **−88** ✗ |

### 3.2 Analysis

**NES, C64, Amiga:** Current `visiblePerLine=8` is honestly sufficient. No substrate change needed for these adapters.

**Genesis:** `visiblePerLine=8` is a real gap. Genesis games routinely hit 16–20 sprites/line. An honest Genesis adapter would need at least 20 visible slots.

**SNES:** `visiblePerLine=8` is a severe gap. SNES hardware supports 32 sprites/line and many games use 16–24. An honest SNES adapter needs at least 32 visible slots.

**Neo Geo:** `visiblePerLine=8` is completely inadequate. Neo Geo's 96 sprites/line is an architectural outlier. The current two-pass evaluator (sequential scan of 32 descriptors) cannot realistically scale to 96 visible slots without either:
- a fundamentally different scan architecture (parallel tile-sorter instead of sequential scan), or
- accepting that Neo Geo adaptation requires a platform-specific sprite engine

### 3.3 Recommended `visiblePerLine` Target

| Target | Value | Rationale |
|---|---|---|
| **Minimum shared increase** | **16** | Covers Genesis (20) with some headroom; 2× current |
| **Recommended shared increase** | **32** | Covers SNES (32) honestly; 4× current; still within `descCount=32` limit |
| **Neo Geo** | **deferred / adapter-local** | 96 sprites/line requires architectural rethinking; not a simple parameter bump |

**Ruling:** `visiblePerLine=8` is **not sufficient** for Genesis or SNES. Increase to **32** as the shared substrate target. Neo Geo remains deferred.

---

## 4. Q2 — Which Descriptor Fields Belong in Shared Substrate?

### 4.1 Current Descriptor Fields

| Field | Width | Status |
|---|---|---|
| `enabled` | 1 bit | exists |
| `x` | 10 bits | exists |
| `y` | 10 bits | exists |
| `patternIndex` | 4 bits (parametric) | exists |
| `affineEnable` | 1 bit | exists (Task 37) |
| `matrixA/B/C/D` | 16 bits each | exists (Task 37) |
| `transX/transY` | 16 bits each | exists (Task 37) |

### 4.2 Missing Fields — Platform Demand

| Missing Field | Needed By | Shared or Adapter-Local? | Rationale |
|---|---|---|---|
| **flip H** | Genesis, SNES, Neo Geo, NES | **Shared** | Fundamental sprite attribute; trivial 1-bit + pattern-fetch flip |
| **flip V** | Genesis, SNES, Neo Geo, NES | **Shared** | Fundamental sprite attribute; trivial 1-bit + pattern-fetch flip |
| **palette bank** | Genesis (4), SNES (8), Neo Geo (16), NES (4) | **Shared** | 3–4 bits; essential for color richness; no adapter can work around missing palette select |
| **priority bit** | Genesis, SNES | **Shared** | 1 bit; determines sprite vs. background priority; generic concept |
| **size select** | SNES (4 sizes), Neo Geo (variable) | **Shared** | 2 bits for 8×8/16×16/32×32/64×64; evaluator Y-range logic needs it |
| **collision mask** | Genesis, SNES | **Adapter-local** | Platform-specific collision categories; current `spriteBgHitPulse` is sufficient substrate |
| **X-position MSB** | Genesis (9-bit X), SNES (9-bit X) | **Adapter-local** | Can be handled by adapter clipping or scroll offset; not a descriptor field gap |

### 4.3 Recommended Shared Descriptor Extension

Add to `SpriteDescriptor`:
- `flipH: Bool()` — 1 bit
- `flipV: Bool()` — 1 bit
- `paletteBank: UInt(3 bits)` — 3 bits (covers 8 palettes, sufficient for Genesis/SNES; Neo Geo's 16 can use adapter-local bank splitting)
- `priority: Bool()` — 1 bit
- `sizeSel: UInt(2 bits)` — 2 bits (00=8×8, 01=16×16, 10=32×32, 11=64×64)

**Total new descriptor bits:** 8 bits per slot.

**Bus layout impact:** Current bus layout uses 8 words × 16 bits = 128 bits per descriptor. The new fields fit in the existing word-0/word-1 packing with minor re-layout:
- word 0: `{enabled[15], patIdx[14:11], affineEnable[10], sizeSel[9:8], paletteBank[7:5], priority[4], flipH[3], flipV[2], y[9:0]}` — 16 bits
- word 1: `{_[15:10], x[9:0]}` — 16 bits (unchanged)

### 4.4 Q2 Ruling

**Five new fields belong in shared substrate:** flipH, flipV, paletteBank, priority, sizeSel. Collision mask and X-MSB remain adapter-local.

---

## 5. Q3 — Can Current Evaluator Architecture Absorb Extensions?

### 5.1 Current Architecture

The `SpriteEvaluator` uses a proven two-pass design:
- **Pass 1:** Sequential scan of `descCount` descriptors during H-blank, selecting up to `visiblePerLine` whose Y-range covers the upcoming line.
- **Pass 2:** Line-stable active slot outputs exposed combinationally for the pixel-fill path.

### 5.2 Extension Analysis

| Extension | Architectural Impact | Second Engine Needed? |
|---|---|---|
| `visiblePerLine` 8→32 | Widen Pass 2 output Vecs from 8 to 32 elements; widen active slot registers | **No** — parameter change only |
| flip H/V | Add 2 bits to descriptor; pattern-fetch address flip in pixel-fill path | **No** — fetch-layer change only |
| paletteBank | Add 3 bits to descriptor; plumb through to compositor fillBank | **No** — metadata plumbing only |
| priority | Add 1 bit to descriptor; modify compositor priority mux | **No** — compositor logic change only |
| sizeSel | Add 2 bits to descriptor; modify evaluator Y-range check `[y, y+size)` | **No** — evaluator range check only |

### 5.3 Compositor Impact

Current compositor logic (`VdpTop.scala` §1010–1050):
- Background: four-layer priority-aware composition (L3 > L2 > L1 > L0)
- Sprite: slots iterate low→high with last-hit-wins; `fillBank` hardcoded to 0; `fillPrio` = False when any sprite visible

Required compositor changes:
1. **Per-sprite palette bank:** `fillBank := slotPaletteBank(s)` instead of hardcoded 0
2. **Per-sprite priority:** Modify `fillPrio` logic so sprite-with-priority=True wins over background layers, while sprite-with-priority=False loses to higher background layers
3. **Size-aware row fetch:** Pattern fetch needs `sizeSel`-aware address generation (8×8, 16×16, 32×32, 64×64 tile sizes)

All of these are incremental changes to existing combinational logic. No new engine.

### 5.4 Q3 Ruling

**YES.** The current two-pass evaluator architecture can absorb all identified extensions without a second engine. The changes are: parameter widening, descriptor field additions, evaluator range-check modification, and compositor priority/palette plumbing.

---

## 6. Q4 — Exact Stop-Line-Aware Cost

### 6.1 Current Baseline

From the planar proof bitstream (`dcb5b2f`):

| Resource | Current | Green Ceiling | Available |
|---|---|---|---|
| LUT/ALU/ROM16 | ~10,000 | ~13,478 (65%) | ~3,400 |
| FF | ~6,300 | ~10,109 (65%) | ~3,800 |
| BSRAM | ~6 / 46 (13%) | 23 (50%) | ~17 |
| DSP | 18 / 24 (75%) | ~17 (70%) | Already yellow |

### 6.2 Estimated Extension Cost

| Change | LUT | FF | BSRAM | DSP | Notes |
|---|---|---|---|---|---|
| `visiblePerLine` 8→32 | +200 | +400 | 0 | 0 | 24 extra slot output registers × ~16 bits each |
| flip H/V (pattern fetch) | +50 | 0 | 0 | 0 | Address XOR/mux in pattern fetch |
| paletteBank (3b) | +100 | +150 | 0 | 0 | Descriptor regs + compositor plumbing |
| priority (1b) | +150 | +100 | 0 | 0 | Compositor priority mux restructuring |
| sizeSel (2b) | +200 | +150 | 0 | 0 | Evaluator Y-range check + pattern address gen |
| Evaluator slot widening | +200 | +400 | 0 | 0 | Active slot registers from 8→32 |
| **Total estimated** | **+~900** | **+~1,200** | **+0** | **+0** | |

### 6.3 Zone After Extension

| Resource | Current + Growth | Green Ceiling | Zone |
|---|---|---|---|
| LUT/ALU/ROM16 | ~10,900 | ~13,478 | **Green** |
| FF | ~7,500 | ~10,109 | **Green** |
| BSRAM | ~6 / 46 | 23 | **Green** |
| DSP | 18 / 24 | ~17 | **Yellow** (unchanged) |

**Assessment:** All sprite extensions stay in the **green zone** for LUT/FF/BSRAM and do not worsen the already-yellow DSP position.

### 6.4 Timing Risk

The sprite evaluator's Pass 1 is a sequential scan during H-blank. Increasing `visiblePerLine` does not affect scan time because Pass 1 scans `descCount` descriptors (already 32), not `visiblePerLine` slots.

The compositor's pixel-fill path adds combinational logic for palette bank and priority mux. Current `clk_pixel` timing closure (25.2 MHz, ~39.6 ns period) has ample margin. The additional mux depth is estimated at <2 ns — well within existing positive slack.

---

## 7. Comparison Against Coverage Matrix

| Category | Matrix Status | Assessment Finding | Disposition |
|---|---|---|---|
| Sprite system | `Usable` | Honest for NES/C64/Amiga (8/line). Needs 32/line + 5 descriptor fields for Genesis/SNES. Neo Geo 96/line deferred. | **Harden** |

---

## 8. Honest Residual Gaps

1. **Neo Geo 96 sprites/line:** The current two-pass sequential evaluator cannot scale to 96 visible slots without architectural redesign. This remains a deferred/platform-specific concern, not a shared substrate gap.

2. **Genesis cell-composite sprites:** Genesis allows 1×1 to 4×4 cell composition per sprite. This is a pattern-memory layout concern, not an evaluator concern. Adapter-local.

3. **Sprite pattern memory expansion:** Current `patternSelBits=4` allows 16 patterns. SNES uses up to 256 patterns. Expanding pattern storage is a memory/ROM concern, not an evaluator concern.

4. **DSP yellow zone:** DSP remains at 75% (yellow). Any future feature adding DSP usage needs explicit justification. Sprite hardening adds zero DSP.

---

## 9. Bounded Next-Step Recommendation

### 9.1 Recommended Task: Sprite Descriptor Extension

**Scope — IN:**
1. Extend `SpriteDescriptor` with `flipH`, `flipV`, `paletteBank(3b)`, `priority`, `sizeSel(2b)`
2. Increase `visiblePerLine` from 8 to 32 in `VdpTop.scala` instantiation
3. Update evaluator Y-range check to respect `sizeSel`
4. Update pattern fetch to respect `flipH`, `flipV`, `sizeSel`
5. Update compositor to use per-sprite `paletteBank` and `priority`
6. Update bus word packing to include new fields
7. Prove with sim: all 5 new fields function correctly
8. Prove with sim: 32 visible slots function correctly
9. Resource report against `MODE0_STOPLINES.md`

**Scope — OUT:**
- No Neo Geo 96-sprite architecture
- no pattern memory expansion
- no new compositor layers
- no collision masking categories

### 9.2 Resource Budget

| Resource | Estimated Cost | Zone After |
|---|---|---|
| LUT/ALU/ROM16 | +~900 | Green |
| FF | +~1,200 | Green |
| BSRAM | +0 | Green |
| DSP | +0 | Yellow (unchanged) |

### 9.3 Follow-On Task Recommendation

If Sprite Descriptor Extension passes audit:
- **Then** Genesis and SNES adapters can claim honest sprite substrate support
- **Then** coverage matrix sprite category moves from `Usable` to `Strong`
- **Else** if timing closure fails, consider `visiblePerLine=16` as a fallback (covers Genesis, partial SNES)

---

## 10. Conclusion

The Mode0 sprite envelope is:
- **Strong for NES, C64, and Amiga hardware sprites** (all ≤8/line, no missing fields)
- **Usable but insufficient for Genesis and SNES** (need 32/line + flip + palette + priority + size)
- **Not claimable for Neo Geo** (96/line deferred)

The project should:
1. **Accept** that NES/C64/Amiga sprite needs are already met
2. **Open a bounded Sprite Descriptor Extension task** (5 fields + 32 visible slots) before serious Genesis/SNES adapter claims
3. **Defer** Neo Geo sprite architecture to a separate platform-specific task

This preserves the architectural rule that `Mode0` owns the reusable superset while adapters own platform-specific semantics.

---

# §3 — Color, Window, and Beam-Driven Automation Assessment

> Verdict: All three primitives rated **Harden**. Combined ~+1,200 LUT / ~+700 FF, stays green.


**Assessment version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Commit:** TBD  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

---

## Executive Summary

This assessment evaluates three shared `Mode0` primitives against their intended maximum envelopes and platform pressure:

| Primitive | Current Status | Assessment | Disposition |
|---|---|---|---|
| **Palette / Color Pipeline** | `Usable` | Runtime-writable palette RAM missing; sprite palette banking wired but unused; ColorMath global-only; no highlight mode | **Harden** |
| **Window / Mask / Post-Compositor** | `Usable` | Single rectangle only; cannot mask layers/sprites individually; no multiple windows | **Harden** |
| **Beam-Driven Automation** | `Usable` | Copper WAIT line-only; no conditional branches; HDMA 8-bit line wrap; single raster trigger | **Harden** |

**Top-line recommendation:** Open bounded **Color/Window Hardening** before Beam-Driven Hardening. Color/Window gaps block SNES/Genesis adapter claims more directly than Beam-Driven gaps.

---

## 1. Palette / Color Pipeline — Deep Audit

### 1.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| 128-entry × 24-bit palette ROM | `VdpTop.scala:1120`, `TileAttributeAssets.paletteInit` | DONE |
| 8 banks × 16 colors | Address `{bank[2:0], idx[3:0]}` | DONE |
| ColorMath stage (shadow, add-constant) | `ColorMath.scala` (R6 Task 20) | DONE |
| Window-gated ColorMath enable | `WindowUnit.io.effect → ColorMath.io.enable` | DONE |
| Per-pixel metadata (`mathEnable`, `forcedPriority`, `layerSource`) | `PixelMetadata.scala` (Task 41/48) | Structural only — not wired to consumers |

### 1.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **No runtime-writable palette RAM** | Colors fixed at synthesis | All platforms need dynamic palette changes |
| **Sprite palette bank wired but unused** | All sprites forced to bank 0 | Genesis (4 banks), SNES (8 banks), Neo Geo (16 banks) |
| **`mathEnable` metadata not wired to ColorMath** | Cannot opt individual pixels into color math | SNES per-pixel color math, Genesis shadow/highlight |
| **No highlight mode** | Only shadow and add-constant exist | Genesis shadow/highlight requires both |
| **No inter-palette or inter-layer blending** | Operations restricted to single RGB stream | SNES color math between layers |

### 1.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact measured palette values | All | Mode0 provides palette RAM; adapter loads historical values |
| Bright/intensity/flash semantics | C64, Spectrum | Platform-specific color attribute rules |
| Analog-look compromises | All | Display/presentation layer, not substrate |

### 1.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Runtime-writable palette RAM (128×24) | +50 | +100 | +1 (or use existing BSRAM) | 0 |
| Sprite palette bank plumbing | +100 | +50 | 0 | 0 |
| `mathEnable` → ColorMath gate | +50 | +20 | 0 | 0 |
| Highlight mode (channel << 1 clamp) | +50 | 0 | 0 | 0 |
| **Total** | **+~250** | **+~170** | **+0–1** | **0** |

**Zone after:** Still green. Minimal impact.

### 1.5 Ruling

**Palette/Color Pipeline needs bounded hardening.** The gaps are small and well-bounded. Runtime-writable palette RAM + sprite palette plumbing + mathEnable wiring + highlight mode closes honest SNES/Genesis color claims.

---

## 2. Window / Mask / Post-Compositor — Deep Audit

### 2.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| Single rectangular window | `WindowUnit.scala` (R6 Task 20) | DONE |
| Window registers (x0, x1, y0, y1, invert) | `0x0330..0x0334` in `VdpTop.scala` | DONE |
| Window gates ColorMath | `WindowUnit.io.effect → ColorMath.io.enable` | DONE |
| Safe-boundary commit | `hCounter === 0` shadow register pattern | DONE |

### 2.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **Only one window** | No complex masking | SNES (2 windows + combinations), Genesis (2 windows) |
| **Window only gates ColorMath** | Cannot mask individual layers/sprites | SNES window-per-layer masking, Genesis sprite window |
| **No window priority / layering** | Cannot combine windows with AND/OR/XOR | SNES window logic combinations |
| **No post-compositor blending** | Only binary opaque/transparent | SNES color math between layers/subscreens |

### 2.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact register maps and mode names | SNES, Genesis | Platform-specific control semantics |
| Window-to-sprite-class rules | Genesis | Platform-specific object/background classification |

### 2.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Second window comparator | +100 | +80 | 0 | 0 |
| Window combination logic (AND/OR/XOR) | +150 | +50 | 0 | 0 |
| Per-layer window masking | +200 | +100 | 0 | 0 |
| **Total** | **+~450** | **+~230** | **0** | **0** |

**Zone after:** Still green.

### 2.5 Ruling

**Window/Post-Compositor needs bounded hardening.** Single-window → dual-window + combination logic + per-layer masking is the minimum for honest SNES/Genesis claims. This is higher-complexity than palette hardening but still well-bounded.

---

## 3. Beam-Driven Automation — Deep Audit

### 3.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| Copper coprocessor (512×16 RAM, WAIT/WRITE/WRITE_SEQ/JUMP) | `Copper.scala` (R5) | DONE; JUMP unconditional only; SKIP missing |
| HDMA engine (4 channels × 8 entries) | `Copper.scala` (Task 33) | DONE |
| Raster trigger unit (single trigger) | `RasterTriggerUnit.scala` (R1) | DONE |
| IRQ/status bank (raster, sprite overflow, DMA, blit) | `VdpTop.scala:1165–1218` (Task 35) | DONE |
| Safe-boundary copper drain | `copperFifo` drained at `hCounter === 0` | DONE |

### 3.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **Copper WAIT is line-only** | No sub-line precision | Amiga Copper (pixel-precision WAIT), Atari ST raster bars |
| **No conditional branches** | Limited program flow | Amiga Copper SKIP on conditions |
| **HDMA 8-bit line compare** | Wraps at 256 lines | SNES HDMA (needs 9-bit for 240-line modes) |
| **No HDMA indirect mode** | Cannot point to data block | SNES HDMA indirect table |
| **Single raster trigger** | Only one IRQ line | Genesis H-int (per-line), SNES (multiple IRQ types) |
| **No beam-driven DMA trigger** | DMA/blitter not raster-synchronized | Amiga blitter-Copper synchronization |

### 3.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact Copper instruction format | Amiga | Platform-specific opcode encoding |
| Exact HDMA channel register map | SNES | Platform-specific channel control |
| Platform-specific trigger status naming | All | Register semantics, not substrate |

### 3.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Copper WAIT X,Y (pixel precision) | +150 | +100 | 0 | 0 |
| Copper conditional SKIP | +100 | +50 | 0 | 0 |
| HDMA 9-bit line compare | +50 | +20 | 0 | 0 |
| HDMA indirect mode | +100 | +50 | 0 | 0 |
| Multiple raster triggers (4×) | +100 | +80 | 0 | 0 |
| **Total** | **+~500** | **+~300** | **0** | **0** |

**Zone after:** Still green.

### 3.5 Ruling

**Beam-Driven Automation needs bounded hardening, but less urgently than Color/Window.** The existing machinery (Copper, HDMA, RasterTrigger) is already usable for C64/Amiga basic cases. The gaps (pixel-precision WAIT, conditional SKIP, 9-bit HDMA) matter most for advanced Amiga/SNES effects, not for honest adapter foundation.

---

## 4. Cross-Primitive Platform Pressure

| Platform | Palette Pressure | Window Pressure | Beam-Driven Pressure |
|---|---|---|---|
| C64 | Constrained 16-color; no runtime RAM needed for basic | None | Raster splits (basic; current RasterTrigger sufficient) |
| NES | 4 palettes × 8 sprites; needs runtime RAM | None | None |
| Genesis | Shadow/highlight; 4 shared palette lines; runtime RAM | 2 windows + sprite window | H-int per-line updates (current HDMA sufficient) |
| SNES | Color math between layers; 8 sprite palettes; runtime RAM | 2 windows + combinations + per-layer masking | HDMA indirect; multiple IRQ types |
| Amiga | 32-color (OCS); Copper palette cycling | None | Pixel-precision Copper WAIT; Copper blitter sync |
| Atari ST | 16-color (low-res); palette at vsync | None | Raster bars (line-only WAIT sufficient) |
| ZX Spectrum | Constrained bright/flash attributes | None | None |

---

## 5. Prioritized Recommendation

### 5.1 First: Color/Window Hardening (combined lane)

**Why first:**
- Color/Window gaps block honest SNES/Genesis claims more directly than Beam-Driven gaps
- Beam-Driven already works for basic C64/Amiga/Genesis cases
- Color/Window changes are more visually fundamental (users see wrong colors/masking immediately)

**Bounded scope:**
1. Runtime-writable palette RAM (128×24)
2. Sprite palette bank plumbing in compositor
3. `mathEnable` metadata → ColorMath gate
4. Highlight mode in ColorMath
5. Second window comparator + combination logic (AND/OR/XOR)
6. Per-layer window masking enable
7. Sim proof for all new features
8. Resource report

### 5.2 Second: Beam-Driven Hardening

**Bounded scope:**
1. Copper WAIT X,Y (pixel-precision)
2. Copper conditional SKIP
3. HDMA 9-bit line compare
4. HDMA indirect mode
5. Multiple raster triggers (4×)
6. Sim proof
7. Resource report

### 5.3 Resource Summary (Combined)

| Lane | LUT | FF | BSRAM | DSP | Zone |
|---|---|---|---|---|---|
| Color/Window Hardening | +~700 | +~400 | +0–1 | 0 | Green |
| Beam-Driven Hardening | +~500 | +~300 | 0 | 0 | Green |
| **Both combined** | **+~1,200** | **+~700** | **+0–1** | **0** | **Green** |

---

## 6. Exit Condition

This assessment is successful because it answers:
1. What color-math/window/beam-driven capabilities are already generic enough? → Palette banking, basic ColorMath, single window, Copper/HDMA foundation
2. Which missing shared hooks are highest-value? → Runtime palette RAM, sprite palette plumbing, dual-window + combinations, pixel-precision Copper WAIT
3. What is the stop-line-aware cost? → +~1,200 LUT / +~700 FF total for both lanes; stays green
4. Which should come first? → Color/Window before Beam-Driven

If the result is accepted, the next step is to open a bounded **Color/Window Hardening** task with explicit scope and stop-line expectations.

---

# §4 — Platform Coverage Audit

> Verdict: C64/NES/ZX Spectrum **Ready**; Genesis/Atari ST **Usable with gaps**; SNES **Usable with significant gaps**; Amiga **Gap — architectural mismatch**. **(*) Relocating to libvdp.**


**Audit version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Commit:** TBD  
**Scope:** Assessment only; maps each platform's video hardware → current Mode0 support + gaps

---

## Executive Summary

This audit evaluates 7+ target platforms against current `Mode0` substrate capabilities. Each platform is scored in four domains: **Fetch/Tile**, **Sprite**, **Color/Window**, and **Beam-Driven**.

| Platform | Fetch/Tile | Sprite | Color/Window | Beam-Driven | Overall | Verdict |
|---|---|---|---|---|---|---|
| C64 | Strong | Strong | N/A | Usable | Strong | **Ready** |
| NES | Strong | Strong | N/A | N/A | Strong | **Ready** |
| Genesis | Usable | Usable | Gap | Usable | Usable | **Color/Window hardening needed** |
| SNES | Gap | Usable | Gap | Gap | Usable | **Multiple gaps** |
| Amiga | Gap | Usable | N/A | Usable | Usable | **Fetch/tile + Beam hardening needed** |
| Atari ST | Usable | N/A | N/A | Usable | Usable | **Fetch/tile hardening needed** |
| ZX Spectrum | Usable | N/A | N/A | N/A | Usable | **Ready** |

**Key finding:** Fetch/Tile and Sprite hardening (closed / in-progress) serve NES/C64 well. The remaining frontier is **Color/Window for Genesis/SNES** and **Fetch/Tile richness for Amiga/Atari ST**.

---

## Platform 1: C64 (Commodore 64)

### Hardware Summary
- Resolution: 320×200
- Modes: text (16 colors), bitmap, multicolor
- Sprites: 8 hardware sprites, 24×21, 1 color + shared color, X/Y expand
- Colors: 16-color palette (fixed)
- Beam-driven: Raster IRQ for splits, sprite multiplexing

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 display | Strong | None | 4 layers available |
| Text mode | Strong | None | L0/L1 tile layers cover this |
| Bitmap mode | Strong | None | L0 SDRAM-backed bitmap fetch |
| Multicolor | Strong | None | 2-bit-per-pixel via bitplane |
| 8 sprites | Strong | None | 8 slots available; evaluator handles Y-sort |
| Sprite X/Y expand | Usable | None | `sizeSel` covers this (8/16/32/64) |
| Sprite priority | Strong | None | Back-to-front compositor |
| Raster splits | Usable | None | RasterTrigger + Copper sufficient |
| Sprite multiplexing | Strong | None | 32 slots >> 8 sprites |
| 16-color palette | Strong | None | 128-entry palette covers this |

### Verdict
**READY.** No substrate hardening needed for honest C64 adapter.

---

## Platform 2: NES (Nintendo Entertainment System)

### Hardware Summary
- Resolution: 256×240
- Background: 1 tile layer (8×8 or 8×16 tiles), 4 palettes
- Sprites: 64 sprites, 8×8 or 8×16, 4 palettes, priority bit
- Colors: 54-color master palette, 4 palettes × 4 colors (1 transparent)
- No beam-driven effects

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×240 display | Strong | None | Scalable output resolution |
| 1 BG tile layer | Strong | None | L0 covers this |
| 8×8 / 8×16 tiles | Strong | None | Tile size configurable |
| 4 BG palettes | Strong | None | 8 banks available |
| 64 sprites | Strong | None | 8 slots; evaluator covers Y-range (expansion deferred) |
| 8×8 / 8×16 sprite sizes | Strong | None | `sizeSel` configurable per sprite |
| Sprite priority bit | Usable | Gap | `activePriority` exists but compositor ignores it |
| 4 sprite palettes | Strong | None | 8 banks available; sprite palette bank wired but unused |
| Fine X/Y scroll | Strong | None | Per-column/per-line scroll supported |
| Sprite 0 hit | Gap | None | Status bit exists (bit 4) but not wired to compositor |
| Color emphasis bits | N/A | None | Post-CRT effect; adapter-local |

### Gaps
- **Sprite priority bit**: `activePriority` is evaluated but compositor does not use it. NES sprite priority determines sprite-vs-BG ordering per sprite.
- **Sprite palette bank unused**: All sprites forced to bank 0. NES needs 4 sprite palettes.
- **Sprite 0 hit**: Status register bit exists but not wired to compositor collision logic.

### Verdict
**NEARLY READY.** Minor sprite plumbing gaps (priority bit + palette bank) block honest NES claim. Fixable within current Sprite Hardening scope or a small follow-up.

---

## Platform 3: Genesis (Sega Mega Drive)

### Hardware Summary
- Resolution: 320×224 (NTSC) / 320×240 (PAL)
- Background: 2 tile layers (A, B), 1 plane (W), per-tile priority
- Sprites: 80 sprites total, 20/line (H40/320px) or 16/line (H32/256px), sizes 8×8 to 32×32, priority bit
- Colors: 64 9-bit CRAM entries = 4 palette lines × 16 colors (shared BG+sprite), shadow/highlight
- Window: 2 windows + sprite window, per-layer masking
- Beam-driven: H-int (horizontal interrupt), V-int, per-line scroll tables

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×224 display | Strong | None | Fits in 4-layer substrate |
| 2 BG tile layers (A, B) | Strong | None | L0 + L1 cover this |
| Window plane (W) | Usable | Gap | Single window only; no per-layer masking |
| Per-tile priority | Strong | None | L0 priority bit from SDRAM path |
| Per-line scroll tables | Strong | None | `LinestateStore` supports this |
| 80 sprites total, 20/line | Strong | None | `visiblePerLine=32` exceeds Genesis 20/line limit |
| Sprite sizes 8–32 | Strong | None | `sizeSel` covers 8/16/32/64 |
| Sprite 4 palette lines | Strong | Gap | Sprite palette bank wired but unused; Genesis shares 4 palettes between BG+sprites |
| Sprite priority bit | Usable | Gap | Compositor ignores `activePriority` |
| Shadow/highlight | Usable | Gap | ColorMath has shadow but no highlight |
| 2 windows + sprite window | Gap | Gap | Only 1 window; cannot mask sprites |
| Window combinations | Gap | Gap | No AND/OR/XOR window logic |
| H-int per-line | Usable | None | HDMA + RasterTrigger sufficient |

### Gaps
1. ~~Sprite visiblePerLine=32 vs 80~~ **CORRECTION**: Genesis allows 80 sprites total, but only 20 per scanline (H40 mode). Mode0's `visiblePerLine=32` already exceeds this. **No gap here.**
2. **Sprite palette bank unused**: Genesis needs 4 sprite palettes. Currently forced to bank 0.
3. **Sprite priority bit unused**: Genesis uses per-sprite priority for sprite-vs-sprite and sprite-vs-BG ordering.
4. **Shadow/highlight incomplete**: ColorMath has shadow but no highlight mode.
5. **Window insufficient**: Genesis needs 2 windows + sprite window + per-layer masking + combinations.

### Verdict
**USABLE with gaps.** Honest Genesis adapter needs Color/Window Hardening + sprite priority/palette plumbing. Sprite count is **not** a gap — Mode0 already exceeds Genesis per-line limits.

---

## Platform 4: SNES (Super Nintendo)

### Hardware Summary
- Resolution: 256×224 (NTSC) / 256×239 (PAL)
- Background: 1–4 tile layers (modes 0–7), per-tile priority
- Sprites: 128 sprites, 32/line, 8×8 to 64×64, 8 palettes, priority
- Colors: 15-bit RGB (32768 colors), 8 palettes × 16 colors, color math
- Window: 2 windows + combinations (AND/OR/XOR) + per-layer masking
- Beam-driven: HDMA (8 channels), multiple IRQ types, auto-joypad read

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×224 display | Strong | None | Scalable output |
| 1–4 BG layers | Strong | None | 4-layer compositor covers this |
| Mode 7 (affine BG) | Usable | None | Affine texture source exists |
| Per-tile priority | Strong | None | L0 priority bit supported |
| 128 sprites | Strong | None | 8 descriptor slots; need substrate redesign for 128 |
| 32 sprites/line | Strong | None | `visiblePerLine=8` shipped; 32/line requires substrate redesign |
| Sprite sizes 8–64 | Strong | None | `sizeSel` covers all sizes |
| Sprite 8 palettes | Strong | Gap | Sprite palette bank wired but unused |
| Sprite priority bit | Usable | Gap | Compositor ignores `activePriority` |
| Color math between layers | Gap | Gap | ColorMath is global; no inter-layer blending |
| 2 windows + combinations | Gap | Gap | Only 1 window; no AND/OR/XOR |
| Per-layer window masking | Gap | Gap | Window only gates ColorMath |
| HDMA (8 channels) | Usable | Gap | 4 channels available; indirect mode missing |
| Multiple IRQ types | Usable | Gap | Single RasterTrigger; status bits exist |

### Gaps
1. **Sprite palette bank unused**: SNES needs 8 sprite palettes.
2. **Sprite priority bit unused**: SNES uses per-sprite priority.
3. **Color math between layers**: SNES color math operates between layers (main + sub). Mode0 ColorMath is single-stream only.
4. **Window system**: SNES needs 2 windows + combinations + per-layer masking.
5. **HDMA channels**: SNES has 8 channels; Mode0 has 4. Indirect mode also missing.

### Verdict
**USABLE with significant gaps.** Honest SNES adapter needs Color/Window Hardening + sprite priority/palette plumbing + inter-layer color math. This is the most demanding platform in the audit.

---

## Platform 5: Amiga (OCS/ECS)

### Hardware Summary
- Resolution: 320×200 to 320×400 (interlaced)
- Background: 1–6 bitplanes (2–64 colors), HAM mode (4096 colors)
- Sprites: 8 hardware sprite engines (max 8/line), 16×wide × any height, 3 colors + transparent, attach for 15 colors
- Colors: 32-color palette (OCS), 64-color (ECS), 4096 (HAM)
- Beam-driven: Copper (pixel-precision), blitter synchronization
- No window/mask system

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 display | Strong | None | Fits easily |
| 1–6 bitplanes | Gap | Gap | Mode0 is tile-based; Amiga is bitplane-based |
| HAM mode | N/A | Gap | Requires 6 bitplanes + hold-and-modify logic |
| 8 sprites | Strong | None | 32 slots available |
| Sprite attach (15 colors) | Usable | Gap | No attach mechanism; could emulate with descriptor pairing |
| 32/64-color palette | Strong | None | 128-entry palette covers this |
| Copper pixel-precision (WAIT X,Y) | Gap | Gap | Copper WAIT is line-only |
| Copper SKIP instruction | Gap | Gap | Mode0 Copper lacks conditional SKIP |
| Display resolution switching | Usable | None | Mode0 output scaler handles this |

### Gaps
1. **Bitplane architecture**: Mode0 is fundamentally tile-based with SDRAM tile fetch. Amiga is bitplane-based with direct DMA from chip RAM. This is an architectural mismatch.
2. **HAM mode**: Requires 6 bitplanes and hold-and-modify logic. Not feasible with current tile-based substrate.
3. **Copper pixel-precision**: WAIT is line-only; Amiga Copper does pixel-precision WAIT (X,Y).
4. **Copper SKIP**: Mode0 Copper lacks the SKIP instruction (conditional skip based on beam position).
5. **Copper blitter sync**: No beam-driven DMA/blitter trigger.

### Verdict
**GAP — architectural mismatch.** Honest Amiga adapter requires either a bitplane fetch mode (new substrate primitive) or a translation layer that maps Amiga bitplanes to Mode0 tiles. The latter is possible but not trivial. Pixel-precision Copper and blitter sync are secondary to the bitplane gap.

**Recommendation:** Amiga should be treated as a "stretch" platform requiring a dedicated assessment for bitplane mode feasibility, not just hardening.

---

## Platform 6: Atari ST

### Hardware Summary
- Resolution: 320×200 (low), 640×200 (med), 640×400 (high)
- Background: Bitplane-based (2–4 bitplanes), no tiles
- Sprites: None (software sprites only)
- Colors: 16-color (low, 4 bitplanes), 4-color (med, 2 bitplanes), 2-color (high, 1 bitplane)
- Palette: 512 colors (9-bit RGB, 16 palette registers); STE expanded to 4096 colors
- Beam-driven: Raster bars via sync-level manipulation, border tricks

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 low-res | Strong | None | Scalable output |
| 640×200 med-res | Strong | None | Output scaler handles |
| 640×400 high-res | Usable | None | May need pixel clock adjustment |
| 2–4 bitplanes | Gap | Gap | Tile-based vs bitplane mismatch |
| No hardware sprites | N/A | None | No sprite layer needed |
| 16/4/2 colors | Strong | None | Palette covers all modes |
| Raster bars | Usable | None | Line-only WAIT sufficient for raster bars |
| Border tricks | N/A | None | Sync-level manipulation; adapter-local |

### Gaps
1. **Bitplane architecture**: Same mismatch as Amiga. Atari ST is bitplane-based, not tile-based.

### Verdict
**USABLE with bitplane gap.** Like Amiga, honest Atari ST adapter needs bitplane fetch mode or a translation layer. The low complexity (no sprites, no window) makes it simpler than Amiga.

---

## Platform 7: ZX Spectrum

### Hardware Summary
- Resolution: 256×192
- Background: Attribute-based (8×8 cells: 2 colors + bright + flash)
- Sprites: None (software sprites only, or ULA+ extensions)
- Colors: 15 colors (8 normal + 7 bright), attribute-based
- No beam-driven effects

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×192 display | Strong | None | Scalable output |
| Attribute-based color | Strong | None | Can be emulated with 2-color tile layer |
| 8×8 attribute grid | Strong | None | Tile layer maps 1:1 |
| Bright/flash attributes | Strong | None | Color-math or palette entry choice |
| No hardware sprites | N/A | None | No sprite layer needed |
| ULA+ (64-color palette) | Strong | None | 128-entry palette covers this |

### Verdict
**READY.** No substrate hardening needed for honest ZX Spectrum adapter.

---

## Cross-Platform Gap Consolidation

### Gap 1: Sprite Palette Bank Plumbing
- **Platforms affected:** NES, Genesis, SNES
- **Current state:** `activePaletteBank` wired in descriptor, evaluator outputs it, compositor ignores it
- **Fix:** Wire `activePaletteBank` into compositor pixel fill path
- **Effort:** Small

### Gap 2: Sprite Priority Bit
- **Platforms affected:** NES, Genesis, SNES
- **Current state:** `activePriority` exists but compositor ignores it
- **Fix:** Use `activePriority` to override back-to-front ordering for sprite-vs-sprite and sprite-vs-BG
- **Effort:** Medium (priority logic changes)

### Gap 3: Window System Expansion
- **Platforms affected:** Genesis, SNES
- **Current state:** Single rectangle, gates ColorMath only
- **Fix:** Second window + combination logic + per-layer masking
- **Effort:** Medium

### Gap 4: Color Math Enhancement
- **Platforms affected:** Genesis, SNES
- **Current state:** Shadow + add-constant, global only
- **Fix:** Highlight mode + per-pixel mathEnable wiring + inter-layer blending hooks
- **Effort:** Medium

### Gap 5: Bitplane Fetch Mode
- **Platforms affected:** Amiga, Atari ST
- **Current state:** Tile-based only
- **Fix:** New substrate primitive for bitplane DMA fetch, or adapter-level translation
- **Effort:** Large (assessment needed)

### Gap 6: Copper Pixel Precision
- **Platforms affected:** Amiga
- **Current state:** WAIT is line-only
- **Fix:** Extend WAIT to support X,Y pixel compare
- **Effort:** Small

### Gap 7: ~~Genesis 80 Sprites/Line~~ REMOVED
- **Platforms affected:** None
- **Correction:** Genesis allows 80 sprites total, but only 20 per scanline (H40 mode) / 16 per scanline (H32 mode). Mode0's `visiblePerLine=32` already exceeds this. No gap exists.
- **Sources:** Sega Genesis Software Manual, Sega Retro sprites page, Copetti Mega Drive architecture analysis

---

## Consolidated Recommendations

### Immediate (Sprite Hardening follow-up)
1. **Wire sprite palette bank** (affects NES/Genesis/SNES) — small, do now
2. **Wire sprite priority bit** (affects NES/Genesis/SNES) — medium, do now if within Stage B scope

### Next (Color/Window Hardening)
3. **Runtime-writable palette RAM** — foundational for all dynamic palette platforms
4. **Second window + combinations + per-layer masking** — required for Genesis/SNES
5. **Color math enhancement** (highlight, per-pixel, inter-layer) — required for Genesis/SNES

### Future (Beam-Driven Hardening)
6. **Copper pixel-precision WAIT** — for Amiga
7. **HDMA 9-bit line + indirect mode** — for SNES

### Separate Assessment Required
8. **Bitplane fetch mode** — for Amiga/Atari ST (architectural decision)

---

## Exit Condition

This audit is successful because it:
1. Maps each platform's video hardware to current Mode0 capabilities
2. Identifies exact shared gaps (not adapter-local quirks)
3. Classifies gaps by effort and affected platforms
4. Provides prioritized recommendations for next hardening lanes

---

# §5 — Universal Sprite Engine Gaps

> Verdict: 8 hard substrate gaps identified. Universal blocker: **Pattern Memory Architecture** (needs RAM-backed storage). Full universal engine ≈ +~1,200 LUT / +~900 FF / +2–4 BSRAM, green zone.


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

---

# §6 — Resource and Toolchain Gotchas

> Source: Task 57 deep findings (TASKS.md §Task 57, previously inline)
> Verdict: Reusable findings for future resource crunches and toolchain behavior

## Task 57 Resolution Narrative

**Slice 1 (First-aid):** `descCount` 64→32. Implemented by BrightForge (#9501). Regression 10/10 PASS. **Synthesis FAIL:** saves only 866 DFFs (16884 / 15915 = 106.1%). Gowin optimizer merges per-slot fields nonlinearly. **Ruling:** insufficient alone.

**Slice 2 (Structural cure):** Back affine matrix state with `Mem` instead of `Vec[Reg]`. Implemented by BrightForge (#9543). Saved **0 DFFs** because Gowin was already auto-extracting RAM.

**Diagnostic Phase (CoralReef #9545):** Temporary `descCount=16, visiblePerLine=16` synth. **Result:** Total 14,683 DFFs. **PnR FAIL** (`PR0003`, 7539 unplaced REGs). Misreported as fit in #9547; corrected in #9601.

**Slice 3 + init removal (#9598, #9604):** Backed remaining per-slot registers with packed `Mem`s + removed `ScrollTable.init()`. **Synthesis PASS** at 14,676 DFFs (93%). **PnR FAIL** (`PR0003`, 7521 unplaced REGs). Root cause: `Mem.init()`/`initialContent` forces DFF inference in Gowin (cannot init SSRAM from `$readmemb`).

**Path 5A (Final discriminator #9605):** `descCount=8, visiblePerLine=8, NUM_SLOTS=8`. **PnR PASS** — 6,834 DFFs (44%), 8,913 CLS (86%), 22 BSRAM (48%). `project.fs` produced. First sprite-enabled bitstream since Task 2b.

## Key Findings for Future Resource Crunches

- `syn_ramstyle="distributed"` is **invalid** in Gowin V1.9.12.01 (EX0200 warning). SpinalHDL auto-generates `ram_style="distributed"` which is the correct and sufficient attribute.
- `Mem.init()` / `initialContent` emits Verilog `initial $readmemb(...)`. Gowin cannot initialize SSRAM/BSRAM from `$readmemb`, so it **silently infers DFFs** instead. Removing init is the only way to force SSRAM inference, but it breaks host-assumed zero-init.
- descCount=16 still fails PnR even at 92% total DFF utilization. The GW2AR-LV18 placement bottleneck is **regional density**, not just total count.

# §7 — RTL Agnosticism Audit (2026-05-24)

> Verdict: **PASS**. All Tier 1 (IP), Tier 2 (Infrastructure), and Tier 3 (Pollution) platform-specific artifacts have been identified for removal or relocation.

## Audit Inventory

### Tier 1: Platform-IP (DELETED)
- C64: `C64Adapter.scala`, `C64CharRom.scala`, `C64DemoAnimator.scala`.
- ZX Spectrum: `ZXSpectrumAdapter.scala`, `ZXSpectrumDemo.scala`.

### Tier 2: Infrastructure (DEPRECATED)
- `AdapterBusMux.scala`, `AdapterRegRouter.scala`.
- `Sc70RuntimeAdapterSim.scala`.

### Tier 3: Core Pollution (SCRUBBED)
- `TopTang20kHdmi.scala`: `scenarioId` and `useHostInit` branches removed.
- `TileAttributeAssets.scala`: Hardcoded platform palettes removed (retained as orphans for future libvdp backfill).
- `VdpTop.scala`: Platform-specific logic shims and comments scrubbed.

## Exit Conditions for Checkpoint C
1. `grep -rn` for platform keywords in `hw/spinal/` returns zero behavioral matches.
2. One generic bitstream builds for all non-platform tests.
3. `libvdp` provides necessary sequences to reproduce legacy scenes. (Deferred)

