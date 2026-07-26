# Active Lane Snapshot — Pre-migration

Captured: 2026-07-26
Commit: `958a01d61012a4043c78f330262db759d909eb73`

## Active lanes (from `STATUS.md`)

| Lane | Owner | Status | Blocker | Key next action |
|---|---|---|---|---|
| PROJECT-SYSTEM-MIGRATION-001 | TopazCliff | RUNNING — AUTHORIZED | — | Phase 1: complete pre-migration snapshot and obtain BrightForge/BronzeGate confirmation. |
| agent-rule-alignment | TopazCliff | RUNNING | — | Apply external reviewer's updated per-agent rule files; align role boundaries, documentation authority, proof-packet requirements, adapter-directory policy, and interface checkpoints. |
| 2bpp-backlog-cosim | BrightForge | DONE — `5efe049` | — | Gate met: forced-late reproduces incomplete-bank display; unblocks `2bpp-bank-completion-rtl`. |
| 2bpp-bank-completion-rtl | BrightForge | UNBLOCKED | — | Implement pixel-domain completion tokens, `bankReady` + `bankRowTag` state, display-bank rotation gated on valid + matching tag. |
| 2bpp-hardware-reproof-4mhz | BronzeGate | BLOCKED | #14345 | Need CyanPeak to provide exact authority `.fs`, or PM to re-baseline onto freshly built, hash-recorded bitstream. |
| external-review-tierB-measure | BrightForge / CyanPeak | RUNNING | — | HDMI reset sequencing, LineBuffer BSRAM inference, RGB565 `bitmapWritePipelineDelay` deterministic co-sim. |
| external-review-scaler-rewrite | BrightForge | OPEN | — | Design/source-coordinate scaler; deferred behind Tier A/B. |
| external-review-tile-pipeline | BrightForge | OPEN | — | Evaluate pipelining `BasicPatternSource`; low priority / backlog. |

## Critical-path note

The migration lane runs alongside the existing `2bpp-bank-completion-rtl` RTL lane and `2bpp-hardware-reproof-4mhz` firmware lane. The migration must not interrupt BrightForge's RTL implementation or BronzeGate's reproof work.
