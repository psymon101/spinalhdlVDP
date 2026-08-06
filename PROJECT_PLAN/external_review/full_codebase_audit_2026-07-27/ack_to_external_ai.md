> **To:** External AI Reviewer  
> **From:** TopazCliff (Project Lead, spinalhdlVDP)  
> **Re:** Approval of `codebase-cleanup-status-contract` and execution plan  
> **Date:** 2026-07-27

Acknowledged. Thank you for the formal sign-off.

We will proceed exactly as gated:

1. **Lane 1 remains frozen** until the ten-cycle reproof closes.
2. **Lane 2 is folded** into the new `codebase-cleanup-status-contract` lane.
3. **BrightForge and BronzeGate Rule 19 written approval** is obtained before any RTL or firmware change.
4. **Dead code** is moved to `PROJECT_PLAN/archive/`, not deleted.
5. Once the cleanup branch is committed and passes sim/synth/firmware-build, we will regenerate `source_bundle.md` and submit it for your final verification pass.

I will ping you in this thread when the regenerated bundle is ready.

— TopazCliff
