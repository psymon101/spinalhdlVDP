# external-review-tile-pipeline

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE — deferred  
**Opened:** 2026-07-25  
**Closed:** 2026-07-28  

---

## Purpose

Evaluate pipelining `BasicPatternSource` tile-map / tile-row reads (external static review Priority 7).

## PM disposition

Deferred. The tile-pipeline optimization is off the current production display path:

- Production builds use `layer0UseSdram = True` with bitmap/planar assets fetched from SDRAM.
- The on-chip test-pattern / tile-map path is disabled in production (`layer0TestPatternEnable = False`).
- No current firmware or product feature depends on `BasicPatternSource` being enabled at scale.

The two dependent asynchronous `readAsync` memory reads in `BasicPatternSource.scala:39-48` remain a latent timing/BSRAM risk if standalone diagnostic mode or on-chip tile layers are ever activated. This task file records the deferral so the risk is not lost.

## Reactivation criteria

Reopen this lane if any of the following become true:

1. `BasicPatternSource` is enabled in a production bitstream.
2. A standalone diagnostic build using on-chip tile patterns is adopted as a release target.
3. Timing closure or BSRAM inference issues are observed in the `BasicPatternSource` path.
4. The external reviewer or a regression gate requires Priority 7 to be implemented.

## References

- External static review Priority 7: `kb/reviews/external_static_review_2026-07-25.md`
- BrightForge technical assessment: #14317
- Source: `hw/spinal/spinalhdlvdp/BasicPatternSource.scala:39-48`
