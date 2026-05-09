<!-- NON-CANONICAL: superseded by PROJECT_PLAN/ASSESSMENT.md -->
**DEPRECATED -- merged into ASSESSMENT.md. This file is now archive reference only.**


# Mode0 Fetch Envelope Assessment Report

**Assessment version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Commit:** TBD  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

---

## 1. Executive Summary

This assessment answers the four acceptance questions defined in `TASK_MODE0_FETCH_ENVELOPE_HARDENING.md` §7, using empirical evidence from the current codebase, external platform research, and `MODE0_STOPLINES.md` budget framing.

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
- ~2050 SDRAM cycles available per line (@ 64.8 MHz, ~800 pixel cycles @ 25.2 MHz)
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
