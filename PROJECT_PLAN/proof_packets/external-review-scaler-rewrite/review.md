# Review — external-review-scaler-rewrite

| Reviewer | Scope | Verdict | Ref |
|---|---|---|---|
| BrightForge | Implementation / sim / PnR | **PASS** | commits `eb08b3d`→`7f8dde6`; `PASS.txt`, `simulation/`, `synthesis/` |
| CyanPeak | Code-to-spec review | PENDING (PM-activated) | requested via #14444 |
| TopazCliff (PM) | PM disposition | PENDING | thread #14432 |

## BrightForge verdict — PASS

- **Spec alignment:** source-coordinate scaler per external static review Priority 4/5; no
  host ABI / register change (reuses SCALE_CTRL 0x0349 / LOGIC_W 0x034A / LOGIC_H 0x034B).
- **1× preservation:** byte-identical across both signal groups (bgOrDirectRgb + io.red).
- **>1× correctness:** ScaleUpFrameCoSim — per-pixel horizontal repetition (stripes,
  skip-sensitive) + both-axes tile scaling (checkerboard), phase-independent, 0 violations.
- **Timing:** Gowin PnR clk_pixel TNS=0, Fmax 30.705 MHz (+21.8%); all clocks TNS=0.
- **Resource:** BSRAM 42→40 (sink line buffer freed); DSP 50%; within budget.

## Open items for P5 (CyanPeak)

1. ScaleCoordGen reciprocal-multiply exactness argument (floor(x/s) via ⌈2¹⁸/s⌉, s∈2..6).
   Exactness proof is in `ScaleCoordGen.scala` comments + `synthesis/P4_pnr_PASS.md`.
2. ~~The +1-cycle registered-coordinate latency in scaled modes~~ — **RESOLVED (`c93ad7a`)**:
   ScaleUpFrameCoSim now asserts SEPARABILITY — V-transition rows are column-independent
   (incl physical cols 0,1,2) and H-transition cols row-independent (incl rows 0,1,2) at
   2×/3×, zero mismatches ⇒ no per-edge artifact; the latency is a clean uniform effect and
   1× is unaffected (mux bypass).
3. Confirm P3b (bitmap/indexed fetch-side) is correctly out of scope for this lane.

## Status

RUNNING — P0/P1a/P1b/P3a/P4 DONE; P3b spun out (#14441); P5 review pending PM activation.
Task file `PROJECT_PLAN/TASKS/external-review-scaler-rewrite.md`.
