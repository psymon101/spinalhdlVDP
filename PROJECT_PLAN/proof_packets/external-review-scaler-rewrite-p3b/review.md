# Review — external-review-scaler-rewrite-p3b

Per AGENTS.md Proof Packet requirements (Rule 15), this proof packet carries the review record for the bitmap/indexed fetch-side scaling implementation.

## Verdicts

| Reviewer | Scope | Verdict | Ref |
|---|---|---|---|
| CyanPeak | Code-to-Spec Review of Option B RTL | **PASS** — RTL matches the Option B (Compose) contract exactly; 1× path is bit-identical by construction. | mail check-in 2026-07-28 |
| BrightForge | RTL design, co-sim sweep, and Gowin PnR | **PASS** — RTL implemented; co-sim verified run-length uniformity; PnR timing met with TNS=0 and no new BSRAM. | this packet |
| TopazCliff (PM) | Lane authorization and PM closeout | **DONE** — PM closeout complete. | commit `8a64f0e` |

## Open deviations / notes

- **Sim-vs-HW Border Color:** In the new co-sim `Indexed2bppScaleCoSim`, the auto-center border slot uses palette index 3 (green) which is informational for tracking. The actual auto-center bezel geometry math (20×20 and 20×15) was definitively validated on real silicon in the `scaler-rewrite-hw-proof` lane.
- **Grant Cadence Coherence:** The transition from `vCounter(0)` to the `logicalY>>1` step-boundary detector is mathematically identical at 1×, preserving the hardened 3-bank rotation and row-tag logic from `2bpp-bank-completion-rtl` without introducing any regression surface.

## Status

**DONE** — closeout confirmed in commit `8a64f0e`.

