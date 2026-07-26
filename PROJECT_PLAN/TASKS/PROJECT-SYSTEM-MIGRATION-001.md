# PROJECT-SYSTEM-MIGRATION-001 — Controlled Modular Documentation Migration

**Owner:** `TopazCliff`  
**Repository:** `/home/itadmin/github/spinalhdlVDP`  
**Opened:** 2026-07-26  
**Pre-migration commit:** `958a01d`

## Mission

Convert spinalhdlVDP to a modular engineering, documentation, verification, and reproducibility system **without** losing current state, history, role boundaries, source authority, build knowledge, or proof evidence.

## Controls preserved

- Authoritative project mailbox.
- `STATUS.md` remains the sole durable live-state authority.
- Current agent identities and role boundaries.
- One critical-path engineering lane.
- SpinalHDL as editable FPGA source; generated Verilog as build artifact.
- `libvdp` as reusable host SDK.
- Simulator-first validation and matched firmware/bitstream hardware proof.
- Prior-art search and closeout memory.

## What the migration adds

- Shared architecture / FPGA / firmware specifications.
- One canonical directory per platform adapter (`kb/<Adapter>/`).
- Validated runbooks.
- Test specifications and golden vectors.
- Proof packets under `PROJECT_PLAN/proof_packets/<LANE>/`.
- ADRs under `PROJECT_PLAN/DECISIONS/`.
- Reproducibility manifests.

## Authority order

1. Latest authoritative mailbox instruction.
2. Repository-root `STATUS.md`.
3. This task file and any linked active task.
4. Current repository state and commit.

## Migration state model

Track through:

1. `PROPOSED`
2. `AUTHORIZED`
3. `SNAPSHOT`
4. `INVENTORY`
5. `AUTHORITY_RECONCILIATION`
6. `RULE_UPDATE`
7. `STRUCTURE_CREATED`
8. `ACTIVE_LANE_MAPPED`
9. `PILOT_EXECUTION`
10. `AUDIT`
11. `CUTOVER_READY`
12. `CUTOVER`
13. `OBSERVATION`
14. `CLOSED`

## Current state

`AUTHORIZED` — migration packet read, pre-migration baseline committed as `958a01d`.

## Next action

Phase 1 — preserve pre-migration state: record branch, commit, working tree, artifact hashes, and rollback instructions under `PROJECT_PLAN/proof_packets/PROJECT-SYSTEM-MIGRATION-001/pre_migration/`.

## Exit criteria

- Pre-migration state is reproducibly identified.
- Mail, `STATUS.md`, task file, and repo agree.
- All agent files are updated and reviewed.
- Role ownership remains separate.
- Modular directories and ownership guides exist.
- Pilot lane uses one canonical adapter directory.
- Governing FPGA and firmware specs exist for active work.
- Runbooks work.
- Expected results documented; proof packet complete.
- Required advisory reviews pass.
- Pilot closes normally.
- Explicit cutover decision issued and recorded.
- Observation task closes.
- Memory closeout written.
- `STATUS.md` records exact next work.
