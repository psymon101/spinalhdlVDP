# Task 3 — Planar Fetch Hardening (2→5+ planes)

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-06  
**Status:** Audit PASS — authorized for implementation  
**Governing directive:** BronzeGate #9306 (Task 3 activation)

**Audit Pass:** #9309 (CyanPeak)

**Tied back to:** TASKS.md §Task 3, ASSESSMENT.md §1 (Fetch Envelope Assessment)

---

## Task

Integrate multi-plane bitplane fetch into the main VdpTop pipeline, raising the planar plane count from 2 → 5+ (target: 5 planes for Amiga OCS/ECS honesty, 6 for EHB coverage).

## Purpose

The current planar fetch in `SdramTileAttributeFetch` is hardcoded to 2 planes (4 colors), covering only NES-style 2bpp and a limited Amiga-style shuffled mode. This blocks honest Tier 1/2 adapter claims:

| Platform | Mode | Planes | Colors | Current | After 3 |
|---|---|---|---|---|---|
| Atari ST low-res | 320×200 | 4 | 16 | ✗ | ✓ |
| Atari ST medium | 640×200 | 2 | 4 | ✓ (existing) | ✓ |
| Amiga OCS low-res | 320×200 | 3–5 | 8–32 | ✗ | ✓ |
| Amiga EHB | 320×200 | 6 | 64 | ✗ | target 6 |
| Amiga Dual Playfield | 320×200 | 2×3 | 2×8 | ✗ | deferred |

Task 3 makes planar fetch a first-class pipeline primitive, not a tile-decode side path.

## Scope

### In scope

1. **Integrate `PlanarLineFetch` into main pipeline**
   - Add a `PlanarLineFetch` instance to `VdpTop` as an alternative L0 source
   - Wire it to the scheduler as a new client (use available slots 2–7)
   - Add `layer0PlanarEnable` register bit (e.g., `LAYER_ENABLE` extension or new mode register)
   - Route `PlanarLineFetch.io.pixel` → L0 pixel mux when planar mode is active
   - Wire `planeBaseAddr` Vec(5) to register-bus writable addresses

2. **SDRAM arbiter / `dout32` wide-read path**
   - `PlanarLineFetch` requires `dout32` (32-bit) SDRAM reads, not the 8-bit `dout` path used by tile fetch
   - Ensure the SDRAM controller's `dout32` aperture is available to the planar fetch client
   - Add muxing if `dout32` is currently dedicated to tile fetch
   - Prove no collision between tile fetch (8-bit) and planar fetch (32-bit) on the same SDRAM controller

3. **Scheduler integration**
   - Add scheduler slot(s) for planar row fetch
   - A 320-pixel row at 5 planes × 40 bytes/plane = 200 bytes = 50 `dout32` reads
   - At 25.2 MHz, 50 reads ≈ 2 μs; must fit within H-blank (6.35 μs) or be spread across line
   - Prove scheduler slot allocation does not starve tile fetch or sprite DMA

4. **Parameterization**
   - `planeCount` parameterized 1–6 (or 1–8) in the integrated instance
   - Default: 5 for Amiga OCS coverage
   - `planeWidth` parameterized to match target resolution (320 for low-res, 640 for medium)

5. **Bit-identical regression**
   - Existing tile-fetch modes (packed 4bpp, planar 2bpp, shuffled 2bpp) must remain unchanged
   - Scenario 9 (planar 2bpp) and Scenario 10 (shuffled 2bpp) must still PASS

6. **Sim proof**
   - `PlanarLineFetchSim` already tests 5-plane standalone — adapt to integrated path
   - New sim: 5-plane fetch through scheduler + SDRAM mock → correct pixels
   - New sim: bandwidth report — 5-plane fetch + concurrent tile fetch within per-line budget

7. **Synthesis / P&R**
   - Elaborate `TopTang20kHdmi(scenarioId=0)` with planar instance integrated
   - Target: within +400–600 LUT, +200–300 FF of current V=32 baseline (13,924 logic)
   - Zero timing violations

8. **Hardware proof**
   - 30s capture with 5-plane diagnostic scene (e.g., SMPTE color bars via planar palette)
   - `analyze.py`: freeze=0, glitch=0
   - Retained artifact must visibly show 5-plane content (not background-only)

### Out of scope

- Amiga Dual Playfield (two independent layers) — deferred to Task 3b or adapter lane
- Amiga AGA 8-plane / 256 colors — deferred
- HAM mode — deferred
- Copper-controlled plane pointers — deferred
- Scroll-table integration for planar layers — deferred
- Exact Amiga/ST memory-map adapters — adapter-local, not substrate
- Changes to sprite path, compositor beyond L0 source mux

## Dependencies

- **Task 16 Planar Fetch Path (R4.1b)** — ✅ DONE. Provides 2-plane tile-decode groundwork.
- **Task 17 Shuffled Fetch Path (R4.1d)** — ✅ DONE. Provides dual-base read groundwork.
- **Task 30 Pre-Announced Arbiter Grant** — ✅ DONE. Provides scheduler slot framework.
- **Task 31 Scroll Table Primitive** — ✅ DONE. Scroll infra available if needed.
- **Task 2b Sprite Capacity Bump** — ✅ DONE. V=32 substrate is the new baseline.
- **BitplaneReconstruct / BitplaneRowFetch / PlanarLineFetch primitives** — ✅ DONE (standalone, proven in sim + `Hdmi720pPlanarProofTop`).

## Interfaces / State

### Changed interfaces

| Interface | Change | Files |
|---|---|---|
| L0 source mux | Add `PlanarLineFetch.io.pixel` as new input | `VdpTop.scala` |
| Scheduler | Add planar fetch client slot(s) | `VdpTop.scala` |
| SDRAM `dout32` | Mux between tile fetch and planar fetch | `TopTang20kHdmi.scala` |
| Register bus | Add `planeBaseAddr[0..4]` writable registers | `VdpTop.scala`, `UnifiedRegMap.scala` |
| `layer0TileDecodeMode` | May extend to select planar vs tile vs bitmap | `VdpTop.scala` |

### No-change interfaces

- Tile fetch (`SdramTileAttributeFetch`) 8-bit `dout` path — unchanged when planar is inactive
- Sprite path — unchanged
- Compositor priority math — unchanged
- Host bus protocol — unchanged (new registers added, existing layout preserved)
- HDMI output timing — unchanged

## Timing / Memory Notes

- **Planar fetch timing:** `PlanarLineFetch` requires one full row of bitplane data before the line starts. The `BitplaneRowFetch` FSM inside it issues `dout32` reads sequentially. For 320 px × 5 planes = 200 bytes = 50 reads. At 25.2 MHz SDRAM, assume ~4 cycles per read = 200 cycles ≈ 8 μs. H-blank is ~6.35 μs (160 cycles). This may exceed h-blank if reads are back-to-back.
- **Mitigation:** Spread reads across the line using the scheduler's slot windows, or use burst mode if the SDRAM controller supports it. The existing `Hdmi720pPlanarProofTop` latches the full row at clean-start and then does combinational lookup — this proves the primitive but not the real-time fetch.
- **Bandwidth budget:** Current tile fetch uses ~2 slots (hTotal-1 to hTotal-1, plus 0 to hTotal-1). Adding planar fetch may require slot 2 or a share of slot 1. The scheduler has 8 slots; 6 are currently disabled.
- **BSRAM:** `PlanarLineFetch` does not use BSRAM (it's a fetch + reconstruct pipeline). `BitplaneReconstruct` is combinational. No new BSRAM expected.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **dout32 arbitration collision with tile fetch** | Medium | High | Add explicit arbiter or time-division via scheduler slots. Tile fetch and planar fetch should never need SDRAM simultaneously if scheduled correctly. |
| **5-plane row fetch exceeds h-blank budget** | Medium | High | Spread reads across line via scheduler; prove with bandwidth sim. Fallback: reduce to 4 planes (ST-only) if 5 doesn't fit. |
| **L0 source mux adds long combinational path** | Low | Medium | `PlanarLineFetch.io.pixel` is combinational from `planeRows` + `pixelIdx`. Add pipeline stage if timing closes poorly. |
| **Integration breaks existing tile modes** | Low | High | Full regression mandatory. Scenarios 9 and 10 are the critical canaries. |
| **LUT exceeds budget** | Low | Medium | Standalone `Hdmi720pPlanarProofTop` synthesized to ~3k logic. Integrated version should be smaller due to shared infrastructure. Target +400–600 LUT. |

## Validation

### Simulation

- **Regression:** Scenarios 9 (planar 2bpp) and 10 (shuffled 2bpp) must PASS bit-identically
- **`PlanarLineFetchSim`:** 5-plane standalone must still PASS
- **New `PlanarIntegrationSim`:** 5-plane fetch through scheduler + mock SDRAM → correct pixel sequence for known test pattern
- **New `PlanarBandwidthSim`:** 5-plane fetch + concurrent tile fetch within per-line cycle budget
- **`VdpTopSim`:** regression PASS with planar instance elaborated but inactive

### Synthesis / P&R

- Elaborate `TopTang20kHdmi(scenarioId=0)` with integrated planar
- Record LUT/FF delta vs Task 2b baseline (13,924 logic)
- Target: within +400–600 LUT
- Zero timing violations

### Hardware Proof

- Flash bitstream with planar instance integrated
- 5-plane diagnostic scene (SMPTE bars or equivalent)
- 30s capture, `analyze.py` freeze=0, glitch=0
- Retained artifact must visibly show 5-plane content

## Decomposition

1. **Checkpoint A — Design packet:** Exact scheduler slot allocation, dout32 arbiter design, register map, L0 mux integration. *(BrightForge after audit)*
2. **Checkpoint B — Audit:** CyanPeak verifies scheduler math, bandwidth budget, arbiter safety.
3. **Checkpoint C — Integration:** Wire `PlanarLineFetch` into VdpTop, scheduler slots, register bus. `VdpTopSim` regression PASS.
4. **Checkpoint D — Sim proof:** `PlanarIntegrationSim` + `PlanarBandwidthSim` PASS.
5. **Checkpoint E — Synthesis:** Gowin synthesis, verify LUT/FF delta and timing.
6. **Checkpoint F — Hardware proof:** 30s capture, `analyze.py` PASS, retained artifact.

## Audit Focus

- Does the scheduler slot allocation guarantee no SDRAM collision between tile and planar fetch?
- Is the per-line bandwidth budget (5 planes × 320 px) within SDRAM capacity under concurrent load?
- Does the L0 mux preserve exact pixel output for all existing modes when planar is inactive?
- Is the register-bus extension backward-compatible?
- Does the HW proof artifact visibly demonstrate 5-plane content per policy #9294?

## Exit Condition

> This task is done when `PlanarLineFetch` is integrated into the main VdpTop pipeline as a selectable L0 source, 5-plane fetch produces correct pixels in sim, synthesis places with zero timing violations and LUT delta within +400–600 of the Task 2b baseline, and a 30s hardware capture of a 5-plane diagnostic scene shows `freeze = 0`.

---

## Appendix: Existing Primitives

The following modules already exist and are proven standalone. Task 3 is an **integration lane**, not a rewrite:

| Module | Status | Notes |
|---|---|---|
| `BitplaneReconstruct` | ✅ Standalone proven | 1–8 planes, combinational |
| `BitplaneRowFetch` | ✅ Standalone proven | 1–8 planes, `dout32` reads |
| `PlanarLineFetch` | ✅ Standalone proven | Combines RowFetch + Reconstruct |
| `Hdmi720pPlanarProofTop` | ✅ HW proven | 5-plane SMPTE bars on Tang Nano 20K |
| `PlanarLineFetchSim` | ✅ Sim proven | 5-plane pixel correctness |
| `BitplaneRowFetchSim` | ✅ Sim proven | Row assembly correctness |

The gap is **integration** — connecting these primitives to the scheduler, SDRAM arbiter, and L0 source mux in the main pipeline.
