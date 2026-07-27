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
- **P1a DONE** (`49040ae`): `ScaleCoordGen` wired into `VdpTop`; sink
  `PixelRepeatScaler` forced to bypass (no pipeline rebalance); all coordinate
  consumers rewired. 1× regression byte-identical:
  - `Indexed2bppFineCoSim`: intra-byte MATCH 3/3 rows, 0 mismatched cols.
  - `Indexed2bppFrameCoSim`: ROW-CODED `bestDv=3` (479/480), LEFT-EDGE CLEAN,
    shear 0px.
  - `DirectColorFrameCoSim`: delay=0 byte-exact `dh=0` (0.9956).
- **P1b DONE** (`5514d1d`, correcting `f805ef2`): sink `PixelRepeatScaler` retired
  via a plain `RegNext`, pipeline kept at +2 cycles. Broad 1× regression PASS —
  both `bgOrDirect` co-sims (`Indexed2bppFine`, `Indexed2bppFrame`, `DirectColor`)
  and `io.red` co-sims (`VdpInnerBorderCoSim`, `BitmapDirectColorSim`) are
  byte-identical. `VdpTopSim` `(0,50)` yellow→black failure confirmed pre-existing
  (identical at baseline `eb08b3d`, broken since `e1848b2`), not a scaler regression.
- **P3a DONE** (`15d5b8e`): >1× integration proof `ScaleUpFrameCoSim` — PASS. Drives
  full `VdpTop` at 1×/2×/3× with procedural patterns fed by `logicalX`/`logicalY`.
  Vertical stripes (pattern 7) prove per-pixel HORIZONTAL repetition (run-length ==
  scaleX, viol 0 — skip-sensitive); checkerboard proves both-axes tile scaling
  (H/V spacing == 16·scale, viol 0). Phase-independent (run-lengths/spacings, not
  absolute column); proof signal `dut.bgOrDirectRgb` keyed by io.x/io.de. The
  1-column io.x/bgOrDirectRgb/io.red probe-phase offset is PRE-EXISTING (present at
  the 1× control) and positional-only — not a scaler bug. Proof note:
  `proof_packets/external-review-scaler-rewrite/simulation/P3a_ScaleUpFrameCoSim.md`.
- **P3b SPUN OUT** (#14440): bitmap/indexed >1× vertical scaling requires
  fetch-side changes (`pixelWithinByte`, `bitmapFetchLineReg`, grant cadence) and
  a host-visible semantics decision (built-in 320×2 doubling vs generic scaler).
  Moved to a new lane to be opened after this one closes; do NOT modify the
  bitmap fetch path in this lane.
- **P4 DONE** (`7f8dde6`): Gowin PnR (effort 2, GW2AR-LV18QN88C8/I7, Verilog `b246aed7`).
  **clk_pixel TNS=0, Fmax 30.705 MHz (+21.8% margin)**; all clocks TNS=0. BSRAM **42→40**
  (−2, sink line buffer freed). DSP 46→50% (+2 reciprocal mults). P4 caught a real timing
  FAIL sim cannot: the P0 combinational divide + fitScale was an 82-level path (clk_pixel
  14.67 MHz, TNS −435.8 ns). FIX in 3 iterations: reciprocal-multiply
  `floor(x/s)=(x*ceil(2^18/s))>>18` (`38ee153`, →23.88 MHz) then register sourceX/Y/valid
  (`7f8dde6`, →30.705 MHz TNS=0). +1 latency in SCALED modes only (1× byte-identical via
  the VdpTop mux; >1× proof is phase-independent). Re-validated on `7f8dde6`: ScaleCoordGenSim
  8/8, ScaleUpFrameCoSim >1× PASS, full 1× regression byte-identical. Proof:
  `proof_packets/external-review-scaler-rewrite/synthesis/P4_pnr_PASS.md`.
- **P5 IN PROGRESS**: CyanPeak code-to-spec review + finalize proof packet.
  BrightForge prepped `PASS.txt`, `review.md`, `manifest.yaml`, and refreshed
  `hashes.sha256` (commit `a18d036`). PM activated CyanPeak review.

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
