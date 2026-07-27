# external-review-scaler-rewrite

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** RUNNING  
**Opened:** 2026-07-27  
**Branch:** `topazcliff/scaler-rewrite`  

## Checkpoints

- **P0 DONE** (`eb08b3d`): `ScaleCoordGen` combinational coordinate generator +
  `ScaleCoordGenSim` unit co-sim PASS 8/8 cases. Verified 1× identity, 2×/3×
  horizontal source-coord repeat, vertical repeat, auto-center borders, silent
  clamp, and `sourceValid`.
- **P1 IN PROGRESS**: `VdpTop` integration — feed `sourceX`/`sourceY` to renderer
  while preserving 1× byte-identical behavior.

## Objective

Replace the current sink-side `PixelRepeatScaler` with a source-coordinate scaler
that generates logical `(sourceX, sourceY)` coordinates before rendering, as
specified by the external static review Priority 4/5 findings
(`kb/reviews/external_static_review_2026-07-25.md`).

## Background

The existing `PixelRepeatScaler` scales after the compositor while the compositor
advances at one source pixel per physical clock. For `scaleX > 1` this produces:

```text
Input:   P0 P1 P2 P3 P4 P5
Current: P0 P0 P2 P2 P4 P4   (wrong)
Correct: P0 P0 P1 P1 P2 P2
```

The same skip pattern occurs vertically. A sink-side latch cannot recover source
pixels the upstream compositor has already skipped.

The required architecture produces physical→logical coordinates first, then lets
the renderer consume `logicalX`/`logicalY`:

```text
Physical hCounter/vCounter
        |
        v
Scale and centering coordinate generator
        |
        +--> sourceX / sourceY / sourceValid
        +--> borderX0 / borderX1 / borderY0 / borderY1
        |
        v
Tile / bitmap / planar / sprite rendering
        |
        v
Final RGB
```

## Scope

1. **Coordinate generator** — replace or augment `PixelRepeatScaler` with a new
   source-coordinate scaler that outputs:
   - `sourceX`, `sourceY` (logical coordinates)
   - `sourceValid`
   - `borderX0`, `borderX1`, `borderY0`, `borderY1`
   - `scaleXEffOut`, `scaleYEffOut` (optional, for downstream use)
2. **Renderer integration** — wire the coordinate generator so that layer fetchers
   (`layer0`, `layer1`, `testPattern`, etc.) consume `logicalX`/`logicalY` during
   active video, while physical counters still drive sync/DE.
3. **1× behavior preservation** — when `scaleX == scaleY == 1`, the output must be
   byte-identical to the current 1× path (no visible change).
4. **>1× validation** — build deterministic co-sim tests that prove correct 2×/3×
   repetition and centering for bitmap, indexed, and test-pattern sources.

## Out of scope

- New host ABI / register map (reuse existing `scaleCtrl` fields).
- Hardware flash / bench test (this lane is sim+PnR only; a separate HW gate can
  be opened if needed).
- HAM6, sprites, Copper timing changes unrelated to scaling.

## Acceptance criteria

- [ ] New coordinate-generator module compiles and elaborates.
- [ ] `sbt compile` and `TopTang20kHdmiVerilog` PASS on the target branch.
- [ ] 1× regression: existing co-sims (`Indexed2bppFineCoSim`, `Indexed2bppFrameCoSim`,
  `DirectColorFrameCoSim` at 1×) produce byte-identical or visually equivalent
  output compared to `topazcliff/migration-phase11` HEAD.
- [ ] >1× proof: a deterministic co-sim demonstrates correct 2×/3× repetition and
  centering (golden-vector comparison).
- [ ] Gowin PnR clean: **TNS=0**, no new BSRAM inferred unless architecturally
  required and reviewed.
- [ ] Independent CyanPeak code-to-spec review PASS (mailbox-visible).
- [ ] Proof packet complete under `PROJECT_PLAN/proof_packets/external-review-scaler-rewrite/`.

## Proof packet contents

- `PASS.txt` — summary, commit hashes, verdict.
- `review.md` — reviewer sign-off table.
- `hashes.sha256` — artifact hashes.
- Co-sim logs / frame captures for 1× regression and >1× validation.
- Gowin PnR timing/resource summary.

## Decision rule

If the coordinate-generator approach requires a host-visible ABI change or a
non-trivial integration change, stop and call an interface checkpoint with
BronzeGate before continuing.
