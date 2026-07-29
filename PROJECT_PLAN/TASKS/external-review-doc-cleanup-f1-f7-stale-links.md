# external-review-doc-cleanup-f1-f7-stale-links

## Owner
CyanPeak

## Status
DONE

## Background

The `scaler-rewrite` branch has been merged into `main` (`a442707`). Two external-review doc-impact items remain open, plus one stale meta-doc link. These are spec/documentation cleanup items only — no RTL, no firmware, no hardware.

## Scope

1. **F1 — Standalone diagnostic build procedure**
   - Location: `PROJECT_PLAN/external_review_doc_impact.md` row F1 (status: **Pending**).
   - Produce a short build/run procedure document (or section) describing how to build and run a standalone diagnostic image:
     - `useHostInit=false`
     - On-chip test-pattern source enabled
     - No SDRAM host upload required
     - Expected observable output (HDMI lock, test pattern on screen, relevant register/health checks)
   - Add the procedure to `PROJECT_PLAN/DIAGNOSTICS.md` (create if absent) or the appropriate runbook.
   - Update `PROJECT_PLAN/external_review_doc_impact.md` to mark F1 **Done**.

2. **F7 — `BasicPatternSource` pipeline latency / Tier C doc**
   - Location: `PROJECT_PLAN/external_review_doc_impact.md` row F7 (status: **Open — Tier C**).
   - Document the current `BasicPatternSource` implementation: two dependent asynchronous `readAsync` reads on the pixel path, no pipeline stage added, and why it is acceptable/deferred for the production path (the on-chip tile/test-pattern path is not used for production SDRAM Layer 0).
   - Capture the design decision and any future-pipeline notes in `VDP_PROGRAMMING_GUIDE.md` §relevant or in `PROJECT_PLAN/DECISIONS/` as a short ADR.
   - Update `PROJECT_PLAN/external_review_doc_impact.md` to mark F7 **Done** or **Accepted Risk** with rationale.

3. **Stale `PROJECT_PLAN.md` link**
   - `PROJECT_PLAN/PROJECT_PLAN.md` references `VOODOO_ADOPTION_PLAN.md`, which does not exist.
   - Either create a minimal `PROJECT_PLAN/VOODOO_ADOPTION_PLAN.md` stub explaining its purpose/scope, or remove/fix the link and inline the roadmap intent.
   - Update `PROJECT_PLAN/PROJECT_PLAN.md` date/version line if appropriate.

## Out of scope

- No RTL, firmware, or hardware changes.
- No flashing, no PnR, no co-sim.

## Acceptance criteria

- [x] F1 standalone diagnostic procedure documented and `external_review_doc_impact.md` updated.
- [x] F7 `BasicPatternSource` pipeline latency documented and `external_review_doc_impact.md` updated.
- [x] Stale `VOODOO_ADOPTION_PLAN.md` link resolved (file created or reference removed).
- [x] `PROJECT_PLAN/STATUS.md` row for this lane moved to **DONE**.
- [x] Closeout mail sent to TopazCliff with proof (diff + files changed).

## Blockers
None.

## Artifacts / References

- `PROJECT_PLAN/external_review_doc_impact.md`
- `PROJECT_PLAN/PROJECT_PLAN.md`
- `VDP_PROGRAMMING_GUIDE.md`
- `hw/spinal/spinalhdlvdp/BasicPatternSource.scala`
- `hw/spinal/spinalhdlvdp/I80HostInterface.scala` (for diagnostic context)
