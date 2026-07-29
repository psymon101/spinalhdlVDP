# external-review-hdmi-tx-blackbox-review

## Owner
BrightForge

## Status
DONE — 2026-07-27

## Background

`Tang20kHdmiTx.scala` is only a SpinalHDL black-box declaration. The external static review flagged that the actual implementation file (likely `tang20k_hdmi_tx.v` or a Gowin IP core) was never reviewed for:

- TMDS encoder implementation;
- serializer primitive configuration;
- clock phase assumptions;
- reset behavior;
- pixel-to-5× clock crossing;
- Gowin-specific `OSER10` / output-buffer configuration.

## Objective

Locate, review, and characterize the actual HDMI transmitter implementation used by the Tang Nano 20K build. Determine whether any black-box internals pose a risk to the observed HDMI stability / startup behavior, or whether the current wrapper is sufficient.

## Acceptance criteria

- [x] Identify the actual HDMI TX source or IP used by the build (file path, Gowin IP report, etc.).
- [x] Review TMDS encoder, serializer, clock/reset crossing, and OSER10/output-buffer configuration.
- [x] Compare against observed HDMI behavior (cold-start locking, capture stability, existing 10/10 POR evidence).
- [x] Produce a concise review report under `kb/reviews/hdmi_tx_blackbox_review_2026-07-28.md` with verdict: **OK**.
- [x] No fix or measurement needed — no follow-up lane.
- [x] Updated `PROJECT_PLAN/external_review_doc_impact.md` to record the limitation as reviewed/closed.
- [x] PM closeout — see mail thread / `STATUS.md`.

## Blockers
None.

## Artifacts / References

- Black-box declaration: `hw/spinal/spinalhdlvdp/Tang20kHdmiTx.scala`
- Build output / IP report: `impl/pnr/project.fs`, Gowin synthesis report
- External review brief: `kb/reviews/external_static_review_2026-07-25.md` §"`Tang20kHdmiTx` Review Limitation"
