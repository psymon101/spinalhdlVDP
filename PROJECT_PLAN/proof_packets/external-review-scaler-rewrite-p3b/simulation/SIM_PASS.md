# P3b simulation proof

All co-sims drive the REAL VdpTop + BitmapRowFetch bitmap path.

## 1× byte-identity guardrail — PASS (existing bitmap co-sims, unchanged)
Confirms the three fetch-side edits are byte-identical at SCALE_CTRL=default(1×):
- **`Indexed2bppFineCoSim`** (intra-byte): **BIT-PERFECT**, 0 mismatched cols across 3 rows.
  Directly validates `pixelWithinByte ← logicalX(2:0)` (logicalX==hCounter at 1×).
- **`Indexed2bppFrameCoSim`**: ROW-CODED **bestDv=3, 479/480** ("VdpTop selects the
  CORRECT source row every display line") — validates `bitmapFetchLineReg ← logicalY`;
  vertical-bar **SHEAR_SPAN=0px** (boundary stable at col 320 over all 480 rows) —
  validates the horizontal path.
- **`DirectColorFrameCoSim`**: PASS, `bitmapWritePipelineDelay=0` byte-exact at dh=0
  (0.9956 canonical) — RGB565 direct-color path unchanged.

## >1× scaling proof — PASS (new `Indexed2bppScaleCoSim`)
Source checkerboard, cell = 16 source px (white value-1 / red value-2). Composed
vertical/horizontal scale = 2·SCALE_CTRL (Option B). Fill-the-frame modes isolate the
run-length scaling (no bezel/clamp edge effects); run-lengths measured in an edge-free
central window and required uniform == composed cell (no skip/dup).

| Mode | SCALE_CTRL | logic | composed cell (pred) | H-runs | V-runs | verdict |
|---|---|---|---|---|---|---|
| 1× | 0x11 | 640×480 | 32 (2·1·16) | [32] | [32] | PASS |
| 2× | 0x22 | 320×240 | 64 (2·2·16) | [64] | [64] | PASS |
| 3× | 0x33 | 213×160 | 96 (2·3·16) | [96] | [96] | PASS |

Every central-window run equals the composed cell exactly ⇒ source rows/columns
repeat correctly with no skipped or duplicated rows/columns at 1×/2×/3×.

Auto-center mode (SCALE_CTRL[7]=1, logic 300×220): scaled bitmap content stays uniform
([64]) with auto-center armed. The auto-center **bezel geometry** (20×20 / 20×15) is
measured definitively on real hardware in the `scaler-hw-proof` lane with real bitmap
content; the idealized-palette sim border colour is informational only.

Command: `sbt "runMain spinalhdlvdp.Indexed2bppScaleCoSim"` → `[P3b] Indexed2bppScaleCoSim ALL PASS`.

## Regression surface
Additive co-sim (`Indexed2bppScaleCoSim.scala`); RTL change is 3 fetch-side edits in
`VdpTop.scala`, all reducing to the pre-P3b code at 1× (see `../source/RTL_CHANGES.md`).
