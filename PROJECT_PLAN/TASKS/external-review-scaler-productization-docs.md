# external-review-scaler-productization-docs

## Owner
CyanPeak

## Status
OPEN

## Background

External static review Priority 4/5 found the old sink-side `PixelRepeatScaler` architecturally incorrect for scaling greater than 1×. The source-coordinate scaler (`ScaleCoordGen`) and the P3b bitmap/indexed fetch-side scaling have since been implemented on branch `topazcliff/scaler-rewrite` and are sim+PnR proven (and the 1×/2×/3× source-coordinate scaler is hardware-proven).

The scaled-mode feature is functionally implemented but not yet documented as a productized host-facing capability.

## Objective

Decide whether scaled modes are productized for the VDP host ABI. If **yes**, update the spec, programming guide, and compliance docs to match the implemented Option B (Compose) semantics. If **no**, document the scaled modes as experimental/dormant and close.

## Acceptance criteria

- [ ] Obtain PM decision: productized or dormant.
- [ ] If **productized**:
  - [ ] Update `docs/firmware/HOST_TRANSPORT_ABI.md` with the formal scaled-mode contract:
    - effective bitmap/indexed scale = 2·`SCALE_CTRL`;
    - crop-then-scale workflow via `LOGIC_WIDTH` / `LOGIC_HEIGHT`;
    - auto-center bezel math;
    - default `scaleX=scaleY=1` byte-identical to HW-proven `a5a047a2`.
  - [ ] Update `VDP_PROGRAMMING_GUIDE.md` §scaling with register programming order and usage examples.
  - [ ] Update `firmware/GOTCHAS.md` with any new host gotchas (e.g., `SCALE_CTRL` persistence across MCU resets).
  - [ ] Add or update a co-sim / test-plan entry tying the documented contract to the implementation.
  - [ ] CyanPeak spec review and CoralReef doc review.
- [ ] If **dormant**:
  - [ ] Document in `VDP_PROGRAMMING_GUIDE.md` that scaled modes are implemented but not supported for general use.
  - [ ] Mark `PROJECT_PLAN/external_review_doc_impact.md` F5 as Done with rationale.
- [ ] PM closeout with proof packet or doc-update commit hashes.

## Blockers

PM decision required before writing docs.

## Artifacts / References

- Implementation: `hw/spinal/spinalhdlvdp/ScaleCoordGen.scala`, `hw/spinal/spinalhdlvdp/VdpTop.scala`
- Existing contract sketch: `docs/firmware/HOST_TRANSPORT_ABI.md` §"Bitmap/indexed `SCALE_CTRL` semantics (P3b)"
- External review brief: `kb/reviews/external_static_review_2026-07-25.md` Priority 4/5
- Doc impact tracker: `PROJECT_PLAN/external_review_doc_impact.md` F5
