# ADR-008 — BasicPatternSource Async Reads Acceptance

**Status:** approved  
**Date:** 2026-07-29  
**Owner:** `CyanPeak`  
**Reviewers:** `TopazCliff`

## Context

`BasicPatternSource` contains two asynchronous memory reads (`readAsync`) on the pixel critical path:
1. `tileMap.readAsync(tileAddress)` to look up the tile index (line 43).
2. `tileRows.readAsync(rowAddress)` to fetch the tile-row pixel data (line 48).

These asynchronous reads introduce combinatorial propagation delay that could potentially affect clock timing ($F_{\text{max}}$) under synthesis, and they bypass registered pipelining stages. The external review (F7) raised a query on whether these should be converted to synchronous reads (`readSync`) with lookahead-address generation.

## Decision

We formally accept the asynchronous read path in `BasicPatternSource` as an approved, deferred risk. We will not modify the RTL design to use `readSync` for this diagnostic-only block.

## Consequences

* **Positive:** Bypasses the need for a complex lookahead-address generation unit and extra latency-matching registers for the diagnostic tile generator, keeping the implementation simple and easy to maintain.
* **Negative:** Asynchronous read timing remains combinatorial. However, because the on-chip test-pattern/tile path is completely disabled in production builds (which use SDRAM Layer 0 with `layer0UseSdram=True`), this combinatorial path does not impact production timing constraints, $F_{\text{max}}$, or setup slack.

## Related

* **STATUS.md lane:** `external-review-doc-cleanup-f1-f7-stale-links`
* **Task file:** [external-review-doc-cleanup-f1-f7-stale-links.md](file:///home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/TASKS/external-review-doc-cleanup-f1-f7-stale-links.md)
* **Doc impact tracker:** [external_review_doc_impact.md](file:///home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/external_review_doc_impact.md) (item F7)
