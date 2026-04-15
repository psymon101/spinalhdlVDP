# SCENARIO_4.md — Single Sprite Over Static Background

**Wave:** 1
**Validates:** Task 11 (sprite renderer + sprite layer enable + sprite-over-background composition)
**Depends on:** Scenario 1 (static background must be stable)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

---

## 1. Purpose

Validate that one sprite renders over the static `BasicPatternSource` background at the position specified by `io.sprite0X`/`Y`, with no rendering corruption of the background outside the sprite footprint.

## 2. Bootstrap register sequence

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0006` | LAYER_ENABLE = L1 + sprite layer (bit[2]=sprite, bit[1]=L1) |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0334` | `0x0000` | color math passthrough |

**LAYER_ENABLE bit[2] needs verification** — current 3-bit field is `B"111"` default. Confirm bit ordering before use; if bit ordering is `(sprite, L1, L0)` then `0x0006` enables (sprite + L1). If different, adjust per the actual `layerEnableReg` semantics in `VdpTop.scala`.

Top-level Scala (TopTang20kHdmiScenario4):
- `io.sprite0Enabled` = True
- `io.sprite0X` = 320, `io.sprite0Y` = 240 (centred — pinned, no motion)
- `io.sprite0PatternIdx` = 0
- `io.sprite1/2/3_Enabled` = False
- L1 background as Scenario 1 (no scroll, no L0)

## 3. Expected visual output

Same `BasicPatternSource` background as Scenario 1, with one 16×16-pixel sprite (pattern 0 — arrow/diamond shape per `VdpTop` sprite pattern definition) rendered at screen pixel (320, 240). Background is unchanged outside the sprite's 16×16 footprint.

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless.

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | 0 freezes; 0 isolated jumps; static-scene inter-frame diff < 3.0 | Static sprite + static bg = static scene |
| **C2 sprite present at expected location** | At source coord (320,240) in the captured frame (mapped to capture coordinates), pixel intensity differs by ≥ 30 from the same coord *without sprite* (i.e. Scenario 1 baseline at same pixel) | Confirms a sprite is actually drawn at the requested position |
| **C3 sprite footprint bounded** | The pixel difference vs Scenario 1 is non-zero only inside an expected ~16×16 region around (320,240); outside that region the difference is < 5 mean | Sprite doesn't bleed/corrupt the background |
| **C4 background unchanged outside footprint** | Whole-screen histogram excluding the 24×24 region around (320,240) matches Scenario 1's histogram within ±5 % per band | Sprite layer only affects sprite pixels, not the background |

Scenario passes when C1-C4 all PASS.

C2 requires having Scenario 1's baseline frame available for diff. If a baseline is not yet captured, accept C2 weak: "sprite-region pixels include sprite-pattern colors not present in the L1 palette mix at that coord" — informational only, with C3+C4 carrying the rigor.

## 5. Failure modes to watch for

- Sprite invisible: sprite enable bit not propagating, or LAYER_ENABLE sprite-bit mis-mapped
- Sprite at wrong position: X/Y wiring bug
- Background corrupted outside sprite: per-line evaluator overflow leaking, or compositor priority logic wrong
- Sprite flickers: ping-pong race in sprite buffer or pre-announce miss

## 6. Out of scope

- Multiple sprites (Scenario 5)
- Sprite motion (Scenario 5 covers that)
- Per-line selection-limit overflow proof (R2 already proved that — separate validation in long-soak)
