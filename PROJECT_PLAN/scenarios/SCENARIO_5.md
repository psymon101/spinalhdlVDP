# SCENARIO_5.md — Four Bouncing Sprites Over Static Background

**Wave:** 1
**Validates:** Task 11 (sprite renderer at full descriptor density of the closed substrate)
**Depends on:** Scenario 4 (single sprite must work first)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate that all 4 sprite descriptors (`sprite0..sprite3`) render simultaneously over the static `BasicPatternSource` background, with bouncing motion driven by simple counter logic in the top-level Scala (per CoralReef #7264 direction — no copper, self-contained).

**Note on count:** Original Wave 1 specification said "ten bouncing sprites"; the closed substrate (`VdpTop.scala`) exposes 4 sprite slots. Renamed to "Four bouncing sprites" per CoralReef #7264 + CyanPeak #7267 direction. A descriptor-count expansion to 10 would be a separate Task 11 re-opening, out of Wave 1 scope.

## 2. Bootstrap register sequence

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0006` | LAYER_ENABLE = Sprite (bit[2]) + L1 (bit[1]); L0 disabled |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (`TopTang20kHdmiScenario5`):
- `io.spriteN_Enabled = True` for all 4 slots
- `io.spriteN_PatternIdx = U(N % 2, 1 bit)` (alternate pattern 0 / 1)
- Per-frame bounce logic in the top-level (no copper):
  - 4 X counters and 4 Y counters, each with its own velocity (e.g. ±1 px/frame and ±2 px/frame mixed)
  - Reflect at the 0/640 (x) and 0/480 (y) screen boundaries
  - Initial positions spread across the screen so all 4 sprites are visible from frame 0
- L1 background as Scenario 1 (no scroll, no L0)

## 3. Expected visual output

Same `BasicPatternSource` background as Scenario 1. Four 16×16-pixel sprites visible simultaneously at any given frame, bouncing diagonally at independent rates. Two sprites use pattern 0 (arrow/diamond) and two use pattern 1 (different shape per `VdpTop` sprite definitions). Background outside sprite footprints is unchanged.

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless.

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present (4-sprite density)** | mean inter-frame diff ≥ 0.1 | Confirms sprites are actually moving above noise floor. Calibrated to physics: 4 sprites × 16×16 = 1024 px / (720×480) ≈ 0.06 % of pixels change per frame, giving expected mean diff ~0.2 — the original ≥5 threshold was unrealistic for sprite-only motion |
| **C2 motion bounded** | mean inter-frame diff ≤ 1.0; std ≤ 0.5 | Confirms motion is sprite-scope (small footprint) and not a full-frame change indicating a runaway |
| **C3 stability** | 0 freezes; 0 isolated jumps > 5σ above mean over 30 s | No drop / overflow / flicker |
| **C4 sprite-pattern footprint count** | At any given sample frame, segment by per-pixel diff vs Scenario 1's static baseline; expect 4 connected components of ≥ 64 px and ≤ 400 px each | Confirms 4 simultaneously-visible sprites |
| **C5 background unchanged** | Pixels outside the union of the 4 sprite-footprint bounding boxes match Scenario 1's histogram within ±5 % per band | Sprites don't bleed into the background |
| **C6 motion variety** | Max inter-frame diff timestamp distribution shows no periodic clumping that would indicate one sprite never moving | All 4 sprites contribute to motion (catches a stuck-sprite descriptor) |

Scenario passes when C1, C2, C3, C4, C5 all PASS. C6 is a catch-net for stuck descriptors.

## 5. Failure modes to watch for

- Only 2 sprites visible: per-line selection limit firing (R2 designed for 2-per-line); if so, sprites at distinct Y bands should each show — check if all 4 bounce within different Y windows
- Sprite trails / smearing: ping-pong race in sprite buffer
- One sprite stuck: descriptor wiring bug, or motion counter reset bug
- All sprites move identically: shared counter reused for all 4 X/Y instead of independent

## 6. Out of scope

- Per-line selection-limit overflow proof (R2 closed; covered by long-soak Scenario 16)
- 5+ sprite descriptors (would re-open Task 11)
- Sprite over scrolling background (covered later in the matrix)
