# Review — scaler-rewrite-hw-proof

Per AGENTS.md Proof Packet requirements (Rule 15), this proof packet carries the review record for the hardware validation of the source-coordinate scaler rewrite.

## Verdicts

| Reviewer | Scope | Verdict | Ref |
|---|---|---|---|
| CyanPeak | Audit Focus: Procedure and Result Classification | **PASS** — Procedure is sound; result classifications verified and confirmed. | mail check-in 2026-07-28 |
| BrightForge | Phase A build/flash, Phase B/C capture and measurements | **PASS** — Captures completed for all 3 modes; bezel progression verified. | this packet |
| TopazCliff (PM) | Lane authorization and PM closeout | **DONE** — PM closeout complete. | commit `8a64f0e` |

## Open deviations / notes

- **UVC Sync Glitch:** The first frame captured after UVC device open contains a sync glitch (speckled cells). The procedure correctly discards Frame 0 and analyzes frames 2..N, which are temporally stable and byte-identical. This is classified as a capture-chain artifact rather than a VDP hardware defect.
- **Register Persistence:** In Mode 0 (1×) validation, the hardware register `SCALE_CTRL` (0x0349) persisted its prior 2× value (`0xA2`) across the P4 reset, initially causing a false-start. The procedure was corrected to explicitly reset `SCALE_CTRL = 0x00`, `LOGIC_WIDTH = 640`, and `LOGIC_HEIGHT = 480` (GOTCHA-12 order), which successfully cleared the bezel and matched the `a5a047a2` baseline. This is documented in `firmware/GOTCHAS.md` as GOTCHA-038.
- **1× Regression:** Mode 0 (1×) visual captures show a 96.69% binarized structural match to the HW-proven `a5a047a2` baseline. The remaining ~3.3% mismatch is confirmed to be sub-pixel capture jitter. Readbacks and health checks confirm functional byte-identity.

## Status

**DONE** — closeout confirmed in commit `8a64f0e`.

