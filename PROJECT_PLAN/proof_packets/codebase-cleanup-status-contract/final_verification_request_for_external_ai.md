# External AI — Final Verification Request

**Project:** spinalhdlVDP  
**Lane:** `codebase-cleanup-status-contract` (Step B RTL + Step C firmware sync)  
**Branch:** `brightforge/status-contract-cleanup` (base `main` `fd39d2b0`)  
**Date:** 2026-08-03  
**Requested by:** TopazCliff (Project Lead)  
**Bundle location:** `PROJECT_PLAN/proof_packets/codebase-cleanup-status-contract/`  

---

## What we are asking

This is the **final verification gate** before the Project Lead authorizes merging `brightforge/status-contract-cleanup` into `main`. We need you to confirm that the implementation actually matches the canonical contract we agreed on, and that no new contradictions or host-visible regressions were introduced.

All prior gates are closed:
- Rule 19 sign-off: BrightForge #14629, BronzeGate #14631, External AI approval.
- CyanPeak code-to-spec review: PASS (#14647).
- BronzeGate Step C firmware/header sync + ESP-IDF v6.0.2 builds: PASS (#14650).
- BrightForge Step B RTL + SpinalSim + Gowin PnR: PASS (#14643).

---

## What to review

1. **`rtl_implementation_bundle.md`** — high-level contract map, source-diff summary, sim results, PnR summary, hashes.
2. **`rtl_source.diff`** — 261-line full diff of `main..brightforge/status-contract-cleanup`.
3. **`review.md`** — CyanPeak's Step C firmware verdict.
4. **`hashes.sha256`** — build artifact hashes.
5. **Canonical contract reference:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md`.

If you want the full regenerated codebase bundle as well, let us know and we will produce it. For this gate we are hoping a focused diff + bundle review is sufficient.

---

## Specific verification questions

1. **Contract conformance:** Does the implemented selector map (`READ_STATUS sel=0x05` sticky, `sel=0x06` upload, reg `0x0320`/`0x0323` W1C, i80 read mux) match `rule19_signoff_request.md`?
2. **Host-visible deviations:** Are there any selector collisions, bitfield mismatches, or register-address changes that would break existing firmware or host code?
3. **W1C semantics:** Is the `0x0323` write-1-to-clear decode correct (bits 2/3 only, set-wins-on-tie, no corruption of other bits)?
4. **i80 parity:** Does the i80 `readData` mux for `0x0320`/`0x0323` give i80 hosts the same status words QSPI hosts see via `READ_STATUS`?
5. **No dead-code reintroduction:** Did the cleanup leave any new tie-offs, stubs, or duplicated status definitions?
6. **Scope discipline:** Did the changes stay inside the approved contract, or did they creep into Lane 1 hardware-debug logic, the production bitstream, or unrelated register decode?
7. **Documentation alignment:** Do the updated docs (`MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, `kb/libvdp/README.md`, `firmware/libvdp/mode0_regs.json`) still match the RTL?

---

## Verdict format

Please return a single verdict:

- **PASS** — implementation matches the canonical contract; no host-visible regressions; merge can proceed.
- **PASS WITH CONDITIONS** — minor findings that must be fixed before merge; list them explicitly.
- **NEEDS-CHANGES** — significant deviation or regression; do not merge until re-reviewed.

For any finding, include:
- File/module
- Line or selector/register reference
- Why it matters
- Suggested fix

---

## Do not do

- Do not propose new host-visible changes (selector numbers, bitfield changes, new registers) — those would need a fresh Rule 19 cycle.
- Do not re-audit the entire unrelated codebase unless you believe the focused bundle is insufficient.

Thank you.
