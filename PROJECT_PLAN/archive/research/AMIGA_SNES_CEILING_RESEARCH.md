# Amiga + SNES Ceiling Research Packet

**Task:** BronzeGate #9986  
**Author:** TopazCliff  
**Date:** 2026-05-15  
**Sources:** `kb/Amiga_OCS_ECS/README.md`, `kb/SNES/README.md`, `kb/NeoGeo/README.md`, `PROJECT_PLAN/MODE0_PLANNING.md`, `PROJECT_PLAN/ASSESSMENT.md`, `hw/spinal/spinalhdlvdp/VdpTop.scala`, `hw/spinal/spinalhdlvdp/BitplaneRowFetch.scala`

---

## 1. Amiga Facts

### Visible graphics capabilities that matter to a substrate ceiling

| Capability | Current Mode0 State | Core / Optional | FPGA Cost |
|---|---|---|---|
| 1-6 bitplanes (2-32 colors) | `BitplaneRowFetch` supports 1-8 planes (`planeCount` param). Task 3 5-plane SMPTE bars HW proven. Integration into `VdpTop` exists but Coverage Matrix calls planar "Usable" not "Strong". | **Core** | Medium — fetch FSM restructuring needed for scanline-oriented DMA vs per-tile |
| EHB (6 planes, 64 colors) | No EHB decoder logic. `kb/Amiga_OCS_ECS/README.md` §3 marks as deferred. | Optional | Medium — half-brightness table or shift |
| HAM (6 planes, 4096 colors) | No HAM decoder. `kb/Amiga_OCS_ECS/README.md` §3 marks as deferred. | Optional | **High** — dedicated post-fetch logic block |
| 8 sprites, 16px wide, linked pairs | `descCount=8` matches count. Linked-pair / 15-color mode not generalized. | Core | Low |
| Copper beam-synchronous writes | Copper-lite / HDMA automator (R5) DONE. `kb/Amiga_OCS_ECS/README.md` §5 maps Copper to raster trigger. | **Core** | Sunk cost — already implemented |
| Blitter DMA | Task 49 blitter DONE. Basic copy/fill/line. Full 256-op minterms deferred. | **Core** | Sunk cost — already implemented |
| Display window / border | Windowing primitive (R6) DONE. `kb/Amiga_OCS_ECS/README.md` §9 notes border timing is part of identity. | **Core** | Sunk cost — already implemented |
| Dual Playfield (2×3 planes) | 4-layer compositor code exists in `VdpTop.scala` (L0-L3), but only L0/L1 have SDRAM fetch. L2/L3 are on-chip `BasicPatternSource` only. | Core | Medium — requires SDRAM fetch path for L2/L3 |

### Amiga readiness verdict

Substrate groundwork exists for all core Amiga features. The honest blockers are:
1. **Planar fetch integration:** `BitplaneRowFetch` supports 5+ planes, but the main pipeline integration is "Usable" not "Strong" per Coverage Matrix.
2. **Dual-playfield / 4-layer SDRAM:** L2/L3 lack SDRAM backing. `MODE0_PLANNING.md` §6 marks "4-Layer Compositor Expansion" as a forward-roadmap item.
3. **HAM/EHB:** Deferred; not required for v1 bounded adapter.

---

## 2. SNES Facts

### Visible graphics capabilities that matter to a substrate ceiling

| Capability | Current Mode0 State | Core / Optional | FPGA Cost |
|---|---|---|---|
| 4 BG layers (modes 0-3) | 4-layer compositor code exists (`VdpTop.scala` L0-L3). L2/L3 on-chip only. `MODE0_PLANNING.md` §6 marks 4-layer expansion as forward roadmap. | **Core** | Medium — SDRAM fetch paths for L2/L3 |
| 128 sprites / 32 per scanline | `descCount=8`, `visiblePerLine=8` (Task 57 Path 5A). `kb/SNES/README.md` §3 marks 32/line as deferred. | Core | **High** to expand — DFF budget was the limiting factor (Task 57) |
| 15-bit RGB, 256-color palette | Palette path DONE. `kb/SNES/README.md` §2 notes 32,768 master / 256 active. Current palette RAM size fits 256 active. | **Core** | Sunk cost — already implemented |
| Color math (add/sub/half) | Color-math / shadow-highlight stage (R6) DONE. | **Core** | Sunk cost — already implemented |
| Window masks | Window mask unit (R6) DONE. Per-layer masking proven (CW-6). | **Core** | Sunk cost — already implemented |
| HDMA per-line updates | Copper-lite / HDMA automator (R5) DONE. `kb/SNES/README.md` §5 maps HDMA to raster trigger. | **Core** | Sunk cost — already implemented |
| Mode 7 affine | Affine stepper (R8) DONE. `kb/SNES/README.md` §2 notes "complex Mode 7 matrix math" deferred. | Core | Sunk cost — already implemented |
| 8×8 and 16×16 tiles | Tile+attribute fetch (R4) DONE. Size selection exists. | **Core** | Sunk cost — already implemented |
| Interlace (256×448) | `kb/SNES/README.md` §3 marks as deferred for v1. | Optional | Low — timing generator extension |

### SNES readiness verdict

Substrate groundwork exists for most SNES features. The honest blockers are:
1. **4-layer SDRAM fetch:** Same blocker as Amiga dual-playfield. L2/L3 need SDRAM backing for honest SNES modes 0-3.
2. **Sprite evaluator expansion:** `descCount=8` → 128 is a massive jump. Current DFF budget is the hard limit (Task 57 settled on descCount=8 after DFF overflow). `MODE0_PLANNING.md` §2 says sprite system is "Usable" with "richer sprite capability envelope still likely needed for top-end Amiga/Neo Geo pressure."
3. **Mode 7 precision:** Affine stepper exists but "deeper affine tuning intentionally deferred" per Coverage Matrix.

---

## 3. Combined Ceiling

### Overlap between Amiga and SNES

| Shared Need | Mode0 Status | Notes |
|---|---|---|
| Beam-driven automation (Copper/HDMA) | DONE (R5) | Both platforms need scanline-synchronous register updates |
| Windowing / masking | DONE (R6) | Both need per-layer rectangular masks |
| Color math / post-compositor | DONE (R6) | Both need add/sub/half/intensity stages |
| Tile+attribute fetch | Strong (R4) | SNES primary; Amiga Fix layer secondary |
| Sprite system | Usable | Both need sprites, but at very different scales |
| Transfer engine (blitter/DMA) | Strong (Task 49) | Amiga primary; SNES DMA secondary |

### Union of features worth keeping

| Feature | Pressure Source | Mode0 Status | Fit Risk |
|---|---|---|---|
| 4-layer compositor with SDRAM fetch for all layers | SNES primary, Amiga dual-playfield secondary | Partial — code exists, L2/L3 lack SDRAM | Yellow — needs arbiter client expansion + scheduler slot re-analysis |
| 5-plane planar fetch | Amiga OCS standard, Atari ST low-res (4 planes) | Usable — primitive exists, integration needs hardening | Green-Yellow — bandwidth proven for 5 planes, but scheduler integration needs sim |
| Sprite evaluator at descCount=8, visiblePerLine=8 | Compromise ceiling for both | DONE | Green — already fits within DFF stop-line (40%) |
| Affine stepper | SNES Mode 7 | DONE (R8) | Green — already implemented |
| Copper-lite / HDMA automator | Both | DONE (R5) | Green — already implemented |
| Blitter + DMA transfer | Amiga primary | Strong (Task 49) | Green — already implemented |

### Features that appear in only one side

| Feature | Platform | Keep in ceiling? | Rationale |
|---|---|---|---|
| HAM decoder | Amiga | No — too expensive for Tang20k | `kb/Amiga_OCS_ECS/README.md` §3 already deferred |
| EHB (64 colors) | Amiga | Optional — low cost if planar fetch already does 6 planes | Half-brightness table is cheap; only add if 5-plane hardening succeeds |
| Mode 7 affine precision | SNES | Yes — already implemented (R8) | Low ongoing cost |
| Interlace output | SNES | No — deferred | `kb/SNES/README.md` §3 deferred for v1 |
| Hardware shrinking | Neo Geo (old ceiling) | No — drop with Neo Geo | Not needed by Amiga or SNES |

---

## 4. Dropped from Old Ceiling

If Neo Geo is removed as a ceiling reference, the following pressures disappear:

| Neo Geo Feature | Old Ceiling Role | Hardware Pressure Removed |
|---|---|---|
| 380 total sprites, 96 per scanline | Sprite/composition pressure ceiling | No need to scale evaluator beyond descCount=8/32 |
| 4,096 active colors / 256 sub-palettes | Palette pressure ceiling | Current 256-active palette RAM is sufficient |
| 65,536 master palette (RGB666+3) | Palette depth ceiling | No need for expanded palette storage |
| Dual 320-pixel line buffers | Composition architecture | No need for line-buffer substrate |
| Hardware shrinking | Scaling primitive | No need for shrink/scale logic |
| Sprite-centric composition (no tilemaps) | Composition model | Normal tilemap+sprite model suffices |

**Net hardware pressure reduction:**
- Sprite evaluator: massive relief. Neo Geo 96/scanline was impossible within Tang20K DFF budget (Task 57 settled on 8 after hitting 111% DFF load).
- Palette RAM: significant relief. 4,096 active colors would require BSRAM expansion.
- Line buffers: complete relief. Dual 320-pixel buffers would consume significant BSRAM.
- Compositor: moderate relief. Neo Geo's everything-is-sprite model would stress the sprite rasterizer differently than Amiga+SNES tilemap+sprite model.

---

## 5. Proposed New Mode0 Requirements

### Required (must have for honest Amiga+SNES ceiling)

| Requirement | Current Gap | Smallest Closure |
|---|---|---|
| 4-layer compositor with SDRAM fetch on L0-L3 | L2/L3 are on-chip `BasicPatternSource` only (`VdpTop.scala` lines 1170-1180) | Add SDRAM fetch arbiter clients for L2/L3; prove per-line bandwidth under 4-layer load |
| 5-plane planar fetch integrated into main pipeline | `BitplaneRowFetch` supports 5+ planes but Coverage Matrix calls planar "Usable" not "Strong" | Harden planar→compositor integration; prove 5-plane + concurrent sprite load in sim |
| Sprite evaluator: maintain descCount=8 / visiblePerLine=8 | Already DONE (Task 57) | Document honest clamp: SNES adapter must accept 8 visible/line, not 32 historical |
| Beam-driven automation (Copper-lite/HDMA) | Already DONE (R5) | Document as sufficient for both platforms |
| Window + color-math stages | Already DONE (R6) | Document as sufficient for both platforms |
| Affine stepper | Already DONE (R8) | Document as sufficient for SNES Mode 7 |

### Optional (nice to have, not blocking)

| Requirement | Cost | When to add |
|---|---|---|
| 6th plane for EHB (64 colors) | Low — add one plane to existing `BitplaneRowFetch` | Only after 5-plane integration is proven |
| Full HDMA channel model beyond Copper-lite | Medium — bounded table/channel model | Only if SNES adapter hits Copper-lite limits |
| Interlace output support | Low — timing generator extension | Only if SNES/Amiga interlace modes are claimed |

### Probably too expensive for Tang20k (explicitly excluded)

| Feature | Why excluded | Evidence |
|---|---|---|
| Neo Geo-scale sprite system (380/96) | DFF budget overflow proven (Task 57 hit 111% at descCount=64) | `MODE0_PLANNING.md` §2: DFF 40% at descCount=8; red zone >80% |
| Neo Geo-scale palette (4,096 active) | BSRAM pressure. Current 17/46 blocks (37%). 4,096 colors ≈ 12-16 additional BSRAM blocks. | `MODE0_PLANNING.md` §2: BSRAM green zone <50% (23 blocks). 17+16=33 > 23. |
| Hardware shrinking | No Amiga/SNES requirement | Only Neo Geo needs this |
| HAM decoder (4096 colors) | Dedicated post-fetch logic block. `kb/Amiga_OCS_ECS/README.md` §3 deferred. | High LUT cost for color-table decode |
| Dual line buffers | No Amiga/SNES requirement | Only Neo Geo uses this architecture |

---

## 6. Recommendation

**Yes — adopt Amiga+SNES as the new Mode0 ceiling**, with the following exact caveats:

### Caveats

1. **4-layer compositor expansion is the highest-risk item.** The code structure exists (`VdpTop.scala` L0-L3) but L2/L3 lack SDRAM fetch. Adding two more SDRAM arbiter clients requires scheduler slot re-analysis and per-line bandwidth proof. This is a yellow-zone feature per `MODE0_PLANNING.md` §2.

2. **5-plane planar fetch integration is the second-highest-risk item.** The primitive (`BitplaneRowFetch`) supports 5+ planes and 5-plane SMPTE bars were HW proven in Task 3. But the Coverage Matrix still calls planar "Usable" because the integration into the main pipeline and honest adapter use needs more hardening. Risk is green-yellow — the primitive is real, integration is the gap.

3. **Sprite pressure drops to manageable levels.** Amiga needs 8 sprites (already matched). SNES historically wants 128/32 but the substrate honest limit is 8/8 after Task 57 DFF optimization. A SNES adapter must document this clamp honestly. This is acceptable because Neo Geo's 380/96 was already impossible.

4. **Palette pressure is resolved.** Both Amiga (32 colors standard, 64 EHB) and SNES (256 colors) fit within current palette RAM. Neo Geo's 4,096 active colors would have required BSRAM expansion into yellow/red zone.

5. **No new primitive invention is required.** All required substrate features for Amiga+SNES ceiling already exist in some form. The work is hardening/expansion, not invention. This matches `MODE0_PLANNING.md`'s current phase: "substrate gap closure + adapter readiness."

### Smallest next engineering step

Open a bounded **"4-Layer SDRAM Fetch Integration"** lane with:
- Scope: Add SDRAM fetch paths for L2 and L3, reusing `SdramTileAttributeFetch` pattern
- Proof: 4-layer concurrent fetch sim with per-line bandwidth report
- Resource gate: PnR must stay within yellow zone (LUT<80%, FF<80%, BSRAM<70%)
- Fallback: If 4-layer SDRAM exceeds budget, keep L2/L3 on-chip and document as honest adapter clamp

**Alternative smaller step:** Open **"Planar Fetch Integration Hardening: 3-5 planes through main pipeline"** first. Lower risk than compositor expansion; narrower scope; unlocks Amiga/Atari ST adapter work sooner.

---

## Exact Sources Cited

| Source | What it provided |
|---|---|
| `kb/Amiga_OCS_ECS/README.md` | Amiga feature list, register surface, Mode0 mapping, deferred features (HAM/EHB/Blitter minterms) |
| `kb/SNES/README.md` | SNES feature list, register surface, Mode0 mapping, deferred features (32 sprites/line, interlace, Mode 7 precision) |
| `kb/NeoGeo/README.md` | Neo Geo feature list for comparison/drop analysis: 380 sprites, 96/scanline, 4,096 colors, shrinking, line buffers |
| `PROJECT_PLAN/MODE0_PLANNING.md` §1-2 | Current ceiling references, resource stop-lines (LUT/FF/BSRAM/DSP budgets), green/yellow/red zones |
| `PROJECT_PLAN/MODE0_PLANNING.md` §3 | Roadmap phases R1-R8, adapter readiness matrix (Amiga through R7, SNES through R6+R8) |
| `PROJECT_PLAN/MODE0_PLANNING.md` §4 | Coverage Matrix — current substrate status per category |
| `PROJECT_PLAN/MODE0_PLANNING.md` §6 | Execution-ready queue: Task 56 (Multi-Layer SDRAM Fetch) ranked #4 |
| `PROJECT_PLAN/ASSESSMENT.md` §3 | Planar fetch assessment: 2-plane limit in `SdramTileAttributeFetch`, need for 3-5+ plane hardening |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | Verified 4-layer compositor code exists (L0-L3), descCount=8/visiblePerLine=8, L2/L3 are on-chip only |
| `hw/spinal/spinalhdlvdp/BitplaneRowFetch.scala` | Verified planeCount 1-8 supported, default=5 |
| `PROJECT_PLAN/archive/tasks/TASK_3_PLANAR_FETCH_HARDENING.md` | Task 3 scope: 2→5+ planes, 5-plane SMPTE bars HW proven |
