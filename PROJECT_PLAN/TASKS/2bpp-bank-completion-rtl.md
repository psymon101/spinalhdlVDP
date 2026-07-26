# Task: 2bpp-bank-completion-rtl

**Owner:** BrightForge  
**Reviewer:** CyanPeak (architecture + interface checkpoint), CoralReef (runbooks + proof packet)  
**Migration pilot:** PROJECT-SYSTEM-MIGRATION-001 Phase 10  
**Opened:** 2026-07-26  

## Goal

Implement the pixel-domain bank-completion token path for the 2bpp bitmap layer, integrating the `docs/fpga/BITMAP_ENGINE.md` contract, and produce a cosim-passing proof packet under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`.

## Background

- Commit `5efe049` established the cosim harness and proved the failing 2bpp behavior on the pre-fix RTL.
- The fix hardens the line-granularity 3-bank bitmap fetch in `BitmapRowFetch`/`VdpTop` so that the display side rotates to a new bank only after that bank's bitmap and attribute writes have landed and its row tag matches the expected display row. See `docs/fpga/BITMAP_ENGINE.md` §Open hardening for the exact contract.
- This is the first engineering lane executed under the post-migration system, so it must also validate:
  - `docs/fpga/BITMAP_ENGINE.md` as the canonical RTL specification source.
  - `docs/testing/TP-2bpp-backlog-cosim.md` as the mandatory test plan.
  - The proof-packet structure under `PROJECT_PLAN/proof_packets/<LANE>/`.

## Authority order

1. This task file.
2. `docs/fpga/BITMAP_ENGINE.md` (RTL contract).
3. `docs/testing/TP-2bpp-backlog-cosim.md` (test acceptance criteria).
4. `docs/runbooks/COSIM_VALIDATION.md` (execution steps).
5. `docs/firmware/HOST_TRANSPORT_ABI.md` (host-side constraints; BronzeGate as consultant).

## Acceptance criteria

- [ ] RTL change committed on branch `brightforge/ham-decoder-171`.
- [ ] `sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"` passes nominal and forced-late modes without display-bank violations after hardening (and fails before hardening in forced-late mode).
- [ ] Diff against `5efe049` ≤ 200 lines or accompanied by a short ADR if larger.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/` with:
  - `PASS.txt` containing commit hash, tool versions, and pass summary.
  - `synthesis_summary.md` from a successful Gowin synthesis run (area/timing).
  - `cosim_log.sha256` and `cosim_log.txt` (curated, not raw multi-MB dump).
  - `diff.patch` from the baseline commit.
- [ ] Runbook feedback filed: if `COSIM_VALIDATION.md` is missing a step, open a correction PR or mail CoralReef.

## Blockers / dependencies

- None. Lane is unblocked per `STATUS.md`.

## Notes

- BrightForge: run this as you normally would, but route status updates through `STATUS.md` and closeout via MCP mail to TopazCliff + CyanPeak.
- TopazCliff will use this lane's proof packet to validate Phase 10 of the migration.
