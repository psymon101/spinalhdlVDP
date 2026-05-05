# Mode0 Canonical Gap Task List

**Version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-05-03  
**Commit:** `8507a7f`  
**Scope:** Shared Mode0 substrate gaps only, ranked by project impact. Does not include adapter-local quirks or platform-specific register semantics.

---

## How to Use This List

- **OPEN** — gap exists in current code; no implementation yet
- **PARTIAL** — some groundwork exists; remaining work is bounded and well-understood
- **CLOSED** — fully implemented and hardware-proven since the assessment was written

If this list and `TASKS.md` disagree on execution order, `TASKS.md` wins.
If this list and actual code disagree, the code and proof artifacts win and this file must be corrected.

---

## Verification Note

The source assessment documents (`MODE0_*_ASSESSMENT.md`) were written on 2026-04-23..2026-04-25. Substantial substrate work landed between those dates and this list:

- Sprite Phase 2 + 2-bis (DONE, audit PASS #8638)
- Color/Window Hardening (DONE, audit PASS #8654)
- Beam-Driven Automation Hardening (DONE, audit PASS #8660)
- Task 52 Per-Sprite X/Y Flip (DONE, audit PASS #9127)
- #9026 Zero-Footprint ROM Elimination (DONE, audit PASS #9142)

As a result, many gaps identified in the original assessments are now **CLOSED**. Each entry below states its current status and the commit or task that closed it when applicable.

---

## Ranking Criteria

Impact is scored by:
1. **Platform breadth** — how many target platforms benefit
2. **Unlock power** — does it unblock previously blocked adapter claims?
3. **Prerequisite depth** — does it gate later high-value work?
4. **Effort leverage** — high impact / low effort ranks higher

---

## Task 1 — MODE_SELECT Runtime Adapter Selection

| Field | Value |
|---|---|
| **Status** | **CLOSED** — Full lane DONE; audit PASS #9201 |
| **Gap** | No runtime mechanism to switch between platform adapters in a single bitstream |
| **Why it matters** | Unlocks the entire Tier 1+2 adapter strategy. Without it, adapters are scenario-conditional compile-time choices. |
| **Platforms helped** | **All 12** — C64, ZX Spectrum, NES, TMS9918, SMS/GG, MSX2, PC Engine, Atari ST, Genesis, SNES, Amiga, Neo Geo |
| **Impact** | **Critical** — infrastructure, not substrate, but gates all adapter lanes |
| **Risk/Complexity** | Low-Medium. Infrastructure only; no new substrate primitives. Architecture audit PASS #8692. |
| **Proof shape** | `Sc70RuntimeAdapterSim` 6/6 PASS; HW `freeze_count=0` over mode-select switches. |
| **Prerequisite for** | All adapter lanes beyond C64 smoke-test and ZX Spectrum |
| **Task doc** | `MODE_SELECT_ARCHITECTURE.md` (Option B, Tier 1+2 default) |

**Scope boundary:** `MODE_SELECT` register at `0x0313`, `AdapterRegRouter`, `AdapterBusMux`, output gating on existing adapters, always-instantiated adapters in top. **Lane closed at commit 9f0c22a / cdd3877.**

---

## Task 2 — Sprite Capacity Expansion (visiblePerLine 8→32 + descCount 32→64)

| Field | Value |
|---|---|
| **Status** | **BLOCKED** — direct bump reproduces 51k-LUT synthesis failure (#9210). Split into 2a + 2b. |
| **Gap** | Only 8 sprites visible per line; only 32 descriptor slots total |
| **Why it matters** | Blocks honest Tier 2 adapter claims. NES needs 64 desc, Genesis needs 80, SNES needs 128. Even `visiblePerLine=32` alone unlocks Genesis (20/line) and SNES (32/line). |
| **Platforms helped** | NES, Genesis, SNES, PC Engine, MSX2, Neo Geo (groundwork) |
| **Impact** | **High** — 5+ platforms; prerequisite for Tier 2/3 adapter honesty |
| **Risk/Complexity** | **Reassessed as Large.** Direct bump 8→32 replicates prior #8577 failure (51k LUT vs 20.7k limit). Substrate redesign required first. |
| **Proof shape** | See Task 2a and Task 2b artifacts |
| **Prerequisite for** | Honest NES/Genesis/SNES adapter claims; Tier 2+3 MODE_SELECT coexistence |
| **Source assessment** | `MODE0_SPRITE_ENVELOPE_ASSESSMENT.md` §3, `MODE0_PLATFORM_COVERAGE_AUDIT.md` §2-4, `MODE_SELECT_ARCHITECTURE.md` §4, blocker #9210 |

**Note:** The MODE_SELECT architecture recommends separating descriptor expansion from the default bitstream if LUT budget becomes tight. Tier 1 adapters (C64, ZX, Atari ST, TMS9918) do not need this expansion.

### Task 2a — Sprite Capacity Substrate Pre-Hardening (CLOSED)

**Purpose:** Redesign sprite render substrate so that a future capacity bump is a small parameter change rather than a structural rewrite.

**Reshape (2026-05-05):** The parallel per-slot substrate path (Checkpoint 2 shared AffineStepper) is **retired** after V=16 P&R failure (#9231). New direction: **Sequential Scanline Rasterizer**.

**Smallest sufficient focus:**
1. **Sequential Scanline Rasterizer** — replace the parallel per-slot pixel-generation loop with a single sequential drawer FSM that paints active sprites one-at-a-time into a dedicated sprite line buffer during the 800-cycle line window. One hitbox evaluator, one shared AffineStepper, one pixel unpack path — not replicated per slot.
2. **Sprite line buffer** — new single-port (SDPB) BSRAM-backed line buffer, double-buffered (ping-pong) matching existing `LineBuffer` semantics. Fill phase (line N): drawer writes. Drain phase (line N+1): compositor reads alongside background layers.
3. **Cycle-budget overflow mapping** — drawer halts when cycle budget exhausted; tail sprites not painted. Reuse existing `spriteOverflow` sticky flag.

**Success condition:**
- `visiblePerLine = 8` behavior remains bit-identical in sim after substrate changes ✅
- Projected LUT cost of V=32 bump is **flat** (~+0–1,000 LUT vs V=8 baseline) because drawer logic does not scale with slot count ✅
- Follow-on Task 2b becomes a parameter flip — **partially achieved** (renderer scales flatly; evaluator FF density remains a separate bottleneck)

**Proof shape:** Sim regression (all existing sprite sims PASS) + resource projection showing V=32 within budget + 30s HW capture `freeze=0`.

**Authority:** BronzeGate #9235; convergent diagnoses CyanPeak #9233 + CoralReef #9234; design packet BrightForge #9236; CyanPeak audit PASS #9250.

**Closure:** BronzeGate #9252 accepted Task 2a CLOSED. Renderer substrate hardened. V=32 fit gap migrated to SpriteEvaluator FF density.

### Task 2c — Sprite Evaluator Hardening (CLOSED)

**Purpose:** Remove the `SpriteEvaluator` `active*` Vec FF-density wall so V=32 can physically place on Tang Nano 20K.

**Status:** CLOSED. CyanPeak audit PASS #9278; BronzeGate PM closeout #9279.

**Commits:** `b2f4a5d` (evaluator RAM), `7b42b6a` (rasterizer narrow + VdpTop wiring), `b558cee` (final cleanup)

**Results:**
- V=32: **13,940 logic (68%), 9,611 LUT, 7,726 FF, 0 unplaced REGs, 0 timing violations** — exit condition MET
- V=8: 13,625 logic (66%), 9,103 LUT, 6,982 FF — improvement vs Task 2a CP2
- Regression: 10/10 PASS bit-identical

**Proof shape:** All existing sims PASS bit-identical; V=32 synthesis zero unplaced REGs.

**Authority:** BronzeGate #9252; artifact `TASK_2C_SPRITE_EVALUATOR_HARDENING.md`; CyanPeak audit PASS #9278 on proof packet.

### Task 2b — Sprite Capacity Bump (IN-PROGRESS)

**Purpose:** Execute the actual `visiblePerLine` 8→32 and `descCount` 32→64 bump on the hardened substrate.

**Status:** IN-PROGRESS. Artifact drafted; awaiting CyanPeak audit.

**Proof shape:** `SpriteCapacityExpansionSim` 6 cases (4 legacy + 2 new) + regression + V=32 synthesis + 30s HW capture.

**Authority:** BronzeGate #9279; artifact `TASK_2B_SPRITE_CAPACITY_BUMP.md`.

---

## Task 3 — Planar Fetch Hardening (2→5+ planes + dedicated row fetcher)

| Field | Value |
|---|---|
| **Status** | OPEN — `planeCount = 2` in `SdramTileAttributeFetch.scala:138` |
| **Gap** | Planar fetch limited to 2 planes (4 colors). No dedicated scanline-oriented bitplane row fetcher. |
| **Why it matters** | Blocks honest Amiga (3–5 bitplanes, 8–32 colors) and Atari ST low-res (4 bitplanes, 16 colors) adapter claims. Current planar is a tile-decode mode, not a general bitplane primitive. |
| **Platforms helped** | Amiga, Atari ST |
| **Impact** | **High** — 2 platforms, but they are computer-class targets with strong demo pressure. Atari ST is "surprisingly thin as an adapter" per MODE_SELECT_ARCHITECTURE.md. |
| **Risk/Complexity** | Large. Extend pixel reconstruction to 5–6 planes, add `BitplaneRowFetch` primitive, add `dout32` wide-read arbiter path, prove SDRAM bandwidth for 5-plane fetch. Estimated +400–600 LUT, +200–300 FF. |
| **Proof shape** | Sim: 5-plane fetch produces correct pixels for Amiga low-res and ST low-res test patterns; SDRAM bandwidth report within per-line budget; resource report |
| **Prerequisite for** | Honest Amiga/ST adapter claims; bitplane-dependent demos |
| **Source assessment** | `MODE0_FETCH_ENVELOPE_ASSESSMENT.md` §3, §6 |
| **Deferred sub-gap** | Multi-layer SDRAM fetch (L1–L3) is explicitly out of scope for this task |

---

## Task 4 — Sprite Pattern Address Width Expansion

| Field | Value |
|---|---|
| **Status** | OPEN — pattern RAM is 4096×4-bit = 16 unique 16×16 patterns per logical table; address ` {patIdx[3:0], row[3:0], col[3:0]}` |
| **Gap** | Pattern address limited to 16×16 tiles. Sprites >16×16 tile-repeat the same pattern. |
| **Why it matters** | SNES 64×64 sprites need 16 unique 16×16 tiles. Genesis 32×32 needs 4 unique tiles. With only 16 patterns total, a single 64×64 sprite consumes the entire table. |
| **Platforms helped** | SNES, Genesis, Neo Geo (groundwork) |
| **Impact** | **Medium-High** — 3 platforms; blocks honest large-sprite claims |
| **Risk/Complexity** | Medium. Expand `patIdx` width, expand pattern RAM depth, update fetch address generation. Pattern RAM already BSRAM-backed (broadcast writes), so depth expansion is a BSRAM count increase. |
| **Proof shape** | Sim: 32×32 and 64×64 sprites render with unique tiles; pattern RAM upload/download via QSPI; resource report |
| **Prerequisite for** | Honest SNES/Genesis sprite claims |
| **Source assessment** | `MODE0_UNIVERSAL_SPRITE_ENGINE_GAP.md` §Gap 2 |

---

## Task 5 — Sprite-Sprite Collision Detector

| Field | Value |
|---|---|
| **Status** | OPEN — only `SPRITE_0_HIT` (slot 0 vs BG) and `SPRITE_BG_HIT` (any vs BG) exist |
| **Gap** | No pairwise sprite-sprite overlap detection. C64 `$D01E` requires detecting any pair of sprites overlapping. |
| **Why it matters** | C64 games rely on sprite-sprite collision for hit detection. Current substrate can only detect slot-0 vs background. |
| **Platforms helped** | C64 (primary); NES/Genesis (secondary, some games use it) |
| **Impact** | **Medium** — 1 primary platform; C64 adapter already honest without this (adapter-local enhancement) |
| **Risk/Complexity** | Medium. Combinational overlap detector for 32 sprites = 496 pairwise comparisons. Can optimize to bounding-box first, then pixel-precision for candidates. |
| **Proof shape** | Sim: overlapping sprites set collision bits; non-overlapping sprites do not; status register readback correct |
| **Prerequisite for** | Honest C64 sprite collision claims |
| **Source assessment** | `MODE0_UNIVERSAL_SPRITE_ENGINE_GAP.md` §Gap 4 |

---

## Task 6 — Sprite Masking + Tile-Fetch Budget Counter

| Field | Value |
|---|---|
| **Status** | OPEN — no `mask` bit in descriptor; no tile-consumption counter |
| **Gap** | Genesis sprite masking (one sprite suppresses all lower-priority sprites on a line) and SNES 34-tiles/line fetch budget are unimplemented. |
| **Why it matters** | Genesis masking is used by games for sprite-culling effects. SNES 34-tile limit is hardware-enforced and affects large-sprite scenes. Both are edge cases — most games work without them. |
| **Platforms helped** | Genesis, SNES |
| **Impact** | **Medium** — 2 platforms; edge-case features, not foundation blockers |
| **Risk/Complexity** | Low. Masking = 1 bit + suppress logic in compositor loop. Budget counter = counter + comparator in evaluator/fetch path. |
| **Proof shape** | Sim: masked sprite suppresses lower slots; 35-tile scene triggers overflow flag; regression PASS |
| **Prerequisite for** | Pixel-perfect Genesis/SNES behavior |
| **Source assessment** | `MODE0_UNIVERSAL_SPRITE_ENGINE_GAP.md` §Gap 3, §Gap 5 |

---

## Task 7 — Multi-Layer SDRAM Fetch

| Field | Value |
|---|---|
| **Status** | OPEN — only L0 has SDRAM fetch; L1–L3 are on-chip `BasicPatternSource` only |
| **Gap** | No SDRAM-backed fetch for background layers beyond L0. Amiga dual-playfield and Genesis-style multi-layer backgrounds need multiple SDRAM-backed layers. |
| **Why it matters** | Blocks honest Amiga dual-playfield claims and rich Genesis/SNES multi-layer scenes. High architectural cost. |
| **Platforms helped** | Amiga, Genesis, SNES |
| **Impact** | **Medium** — 3 platforms, but deferred in fetch assessment as "future task with its own stop-line review" |
| **Risk/Complexity** | Large. New arbiter clients, fetch FSMs, slot allocation policy, per-line budget re-analysis. Could push scheduler into yellow zone. |
| **Proof shape** | Sim: L0+L1 both fetch from SDRAM concurrently; arbitration priority correct; no line-drop under max load; resource + bandwidth report |
| **Prerequisite for** | Honest Amiga dual-playfield; rich Genesis/SNES multi-layer scenes |
| **Source assessment** | `MODE0_FETCH_ENVELOPE_ASSESSMENT.md` §5.1, §8.1 |

---

## Closed Gaps (for audit trail)

The following gaps were identified in the 2026-04-25 assessment batch but have been closed by subsequent work. They are listed here so future readers do not re-discover them.

| Gap | Closed By | Date |
|---|---|---|
| Runtime-writable palette RAM | Color/Window Hardening (CW-1) | 2026-04-25 |
| Sprite palette bank plumbing | Sprite Phase 2 P2-3a + CW-2 | 2026-04-25 |
| Sprite priority bit wiring | Sprite Phase 2 P2-3a | 2026-04-25 |
| `mathEnable` metadata → ColorMath gate | Color/Window Hardening (CW-3) | 2026-04-25 |
| Highlight mode in ColorMath | Color/Window Hardening (CW-4) | 2026-04-25 |
| Second window + combination logic | Color/Window Hardening (CW-5) | 2026-04-25 |
| Per-layer window masking | Color/Window Hardening (CW-6) | 2026-04-25 |
| Copper pixel-precision WAIT | Beam Hardening (BH-1) | 2026-04-25 |
| Copper conditional SKIP | Beam Hardening (BH-2) | 2026-04-25 |
| HDMA 9-bit line compare | Beam Hardening (BH-3) | 2026-04-25 |
| HDMA indirect mode | Beam Hardening (BH-4) | 2026-04-25 |
| BSRAM-backed sprite pattern RAM | Sprite Pattern Memory Foundation (#8596) | 2026-04-25 |
| Per-sprite flip H/V | Task 52 (#9127) | 2026-05-03 |
| Per-sprite `sizeSel` | Sprite Phase 2 (#8577) | 2026-04-25 |
| Per-sprite `bppSel` | Sprite Phase 2-bis (#8622) | 2026-04-25 |

---

## Summary Table

| Rank | Task | Status | Platforms | Impact | Risk | Prereq For |
|---|---|---|---|---|---|---|
| 1 | MODE_SELECT Runtime Adapter Selection | **CLOSED** | All 12 | Critical | Low-Med | All adapter lanes |
| 2a | Sprite Capacity Substrate Pre-Hardening | **ACTIVE** | NES/Gen/SNES/PCE/MSX2 | High | Large | Tier 2/3 adapter honesty |
| 2b | Sprite Capacity Bump (8→32 / 32→64) | DEFERRED | NES/Gen/SNES/PCE/MSX2 | High | Low (post-2a) | Tier 2/3 adapter honesty |
| 3 | Planar Fetch Hardening (2→5+ planes) | OPEN | Amiga, Atari ST | High | Large | Amiga/ST adapter honesty |
| 4 | Pattern Address Width Expansion | OPEN | SNES/Gen/Neo Geo | Med-High | Medium | Large-sprite honesty |
| 5 | Sprite-Sprite Collision Detector | OPEN | C64 (primary) | Medium | Medium | C64 collision honesty |
| 6 | Sprite Masking + Tile-Fetch Budget | OPEN | Genesis, SNES | Medium | Low | Pixel-perfect edge cases |
| 7 | Multi-Layer SDRAM Fetch | OPEN | Amiga/Gen/SNES | Medium | Large | Dual-playfield / rich layers |

---

## Next-Step Recommendation

**Immediate:** Activate **Task 1 (MODE_SELECT)**. It is the only formal TODO in `TASKS.md`, its dependencies are cleared, and it unlocks the entire adapter lane strategy. It is infrastructure, not substrate, but no substrate gap has higher project impact.

**After MODE_SELECT:** Open **Task 2a (Sprite Capacity Substrate Pre-Hardening)**. The direct capacity bump (Task 2b) is blocked by substrate-fit failure (#9210). Task 2a is a substrate redesign lane aimed at making the future bump a small parameter change. Task 2b remains deferred until 2a closes.

**After Sprite Capacity:** Open **Task 3 (Planar Fetch Hardening)**. It unlocks Atari ST (Tier 1, very coexistence-friendly) and Amiga groundwork. Atari ST is the lowest-risk next adapter after MODE_SELECT per `MODE_SELECT_ARCHITECTURE.md` §5.

---

## Exit Condition

This list is successful when:
1. All identified gaps map to current code or a closed task
2. Ranking is justified by platform breadth and unlock power
3. Closed gaps are clearly separated so they are not re-discovered
4. The next-step recommendation is unambiguous and tied to `TASKS.md`
