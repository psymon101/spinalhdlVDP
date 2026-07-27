# P3a — >1× source-coordinate integration proof (ScaleUpFrameCoSim)

**Lane:** external-review-scaler-rewrite · **Branch:** `topazcliff/scaler-rewrite`
**PM directive:** #14439 Option A — prove the procedural/testpattern >1× path first,
with no bitmap/indexed fetch-side change.

## What it proves

Drives the FULL `VdpTop` at 1×, 2×, 3× with procedural test patterns whose colours are
generated directly from the new `logicalX`/`logicalY` coordinates, so a PASS proves the
`ScaleCoordGen` source-coordinate scaler is correctly wired end-to-end into the real
compositor render path (fetch/compositor advance at the LOGICAL rate → each source pixel
emitted for exactly `scaleX` physical columns × `scaleY` physical lines).

- **VERTICAL STRIPES (TestPatternSource pattern 7, 1×/2×/3×):** 1-px black/white stripes
  in x alternate every SOURCE pixel, so a dropped/duplicated source column (the classic
  sink-side skip `P0 P0 P2 P2`) cannot survive. Definitive HORIZONTAL per-pixel proof.
- **CHECKERBOARD (pattern 5, 2×/3×):** 16-px tiles in x and y. Exact horizontal AND
  vertical transition SPACING (`16·scale`) proves both-axes tile-level scaling.

## Method (phase-independent)

Proof signal is `dut.bgOrDirectRgb` (top-level `simPublic` compositor output, +2 from
hCounter, aligned with `io.x`) keyed by `(io.x, io.y)` during `io.de` — the SAME signal the
repo's canonical frame co-sims (`Indexed2bppFrameCoSim`, `DirectColorFrameCoSim`) sample.
`io.red` (HDMI output) is captured for diagnosis.

Checks assert run LENGTHS (stripes) and transition SPACINGS (checker), never absolute column,
so they are immune to the constant 1-column probe-phase offset between `io.x`,
`bgOrDirectRgb` (−1), and `io.red` (+1). The 1× control demonstrates that offset is
PRE-EXISTING (present at 1×, where the production path is byte-identical) — a probe artifact,
not a scaler bug. Both signals show identical run-lengths, confirming the scaling is correct
on the compositor AND the output.

## Result — PASS

```
STRIPES 1x: bgOrDirectRgb Hrun=1,1,1,... expect=1 viol=0/3780 distinct=2 => OK   (io.red viol=0/3780)
STRIPES 2x: bgOrDirectRgb Hrun=2,2,2,... expect=2 viol=0/1878 distinct=2 => OK   (io.red viol=0/1878)
STRIPES 3x: bgOrDirectRgb Hrun=3,3,3,... expect=3 viol=0/1158 distinct=2 => OK   (io.red viol=0/1158)
CHECKER 2x2: Hspacing=32 viol=0/84  | Vspacing=32 viol=0/54 => OK
CHECKER 3x3: Hspacing=48 viol=0/42  | Vspacing=48 viol=0/24 => OK
ScaleUpFrameCoSim: PASS
```

Full log: `simulation/P3a_ScaleUpFrameCoSim.log`.

## Command

```
sbt "runMain spinalhdlvdp.ScaleUpFrameCoSim"
```

## Scope note (Landmine 2 — reported to PM before any change)

This proof covers the procedural/testpattern (and, by identical coordinate wiring, the
tile-layer) render path. It does NOT cover the bitmap/indexed SDRAM-fetch path, whose
fetch-side signals still key off physical scan position rather than logical coordinates:

- `bitmapFetch.io.pixelWithinByte := RegNext(hCounter(2:0))` (VdpTop:1619) — raw hCounter,
  not `logicalX` → intra-byte pixel scramble at `scaleX>1` for multi-pixel-per-byte modes
  (byte-uniform content is immune, as with the PIXELWITHINBYTE-ALIGN bug).
- `bitmapFetchLineReg := fillLine` (VdpTop:1634) — vCounter-based, not `logicalY` → source
  rows do not repeat at `scaleY>1`.
- Bitmap fetch grant hardcodes `/2` line-doubling via `vCounter(0)` (VdpTop:1647); the
  bitmap path is already a fixed 320-source-×2 stretch, so how `SCALE_CTRL scaleY` composes
  with that built-in doubling is a SEMANTICS question, not just wiring.

All three are identity at 1× (which is why the byte-identical 1× regression passed). Applying
`SCALE_CTRL` to the bitmap/indexed path requires fetch-side changes to the shimmer-sensitive
grant/fetch-ahead geometry — deferred to a PM decision (absorb vs spin out).
