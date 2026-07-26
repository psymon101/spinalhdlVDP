> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document records the cutover decision; it does not own active-lane status.

# ADR-007 — Migration to Modular Documentation/Specification/Proof System

**Status:** approved  
**Date:** 2026-07-26  
**Owner:** `TopazCliff`  
**Reviewers:** `BrightForge`, `BronzeGate`, `CyanPeak` (architecture/interface review), `CoralReef` (proof-packet/runbook review)

## Context

`PROJECT-SYSTEM-MIGRATION-001` was opened to convert `spinalhdlVDP` from an ad-hoc documentation and coordination model to a modular system with shared specs, canonical adapter directories, validated runbooks, test plans, proof packets, and ADRs — while preserving `STATUS.md` as the live authority, role boundaries, and source/build separation.

The migration progressed through:
- Pre-migration snapshot and inventory.
- Authority reconciliation (`STATUS.md` remains live; external docs kept as reference-only).
- Agent-rule updates (`AGENTS.md` + `AGENTS/*.md`).
- Modular structure under `docs/`, `kb/`, `docs/runbooks/`, `docs/testing/`.
- Proof-packet template and `PROJECT_PLAN/proof_packets/` directory.

## Decision

Approve the cutover. The new modular documentation/specification/proof system is the active project system for `spinalhdlVDP`.

Basis:
- Pilot lane `2bpp-bank-completion-rtl` closed with a complete proof packet (`PASS.txt`, `manifest.yaml`, `hashes.sha256`, `synthesis_summary.md`, `cosim_log.txt` + `.sha256`, `diff.patch`), CyanPeak architecture/interface review PASS (#14375), and CoralReef proof-packet/runbook review PASS (#14376).
- Hardware reproof lane `2bpp-hardware-reproof-4mhz` closed with exact-approved-artifact flash and separated serial/readback/health/YUYV proof (#14415).
- All migration exit criteria are met: state reproducible, mail/`STATUS.md`/task file/repo agree, agent rules updated, role ownership preserved, canonical directories and specs exist, runbooks and test plans in place, expected results documented, proof packets complete, required reviews mailbox-visible, and the pilot closed normally.

## Consequences

- `STATUS.md` remains the sole durable live-state authority.
- `PROJECT_PLAN/TASKS/` owns durable task descriptions; active-lane status stays in `STATUS.md`.
- Proof packets are required under `PROJECT_PLAN/proof_packets/<LANE>/` for every closing lane.
- ADRs are required under `PROJECT_PLAN/DECISIONS/` for permanent architecture and project-system decisions.
- `docs/external_documentation_system/` remains a read-only reference snapshot; its files are not canonical.
- Superseded root `PROJECT_PLAN/TASKS.md` is already archived to `PROJECT_PLAN/archive/TASKS_stale_2026-06-19.md`.
- Existing engineering lanes (`external-review-tierB-measure`, etc.) continue under the new system.

## Related

- `PROJECT_PLAN/STATUS.md` — live lane state
- `PROJECT_PLAN/TASKS/PROJECT-SYSTEM-MIGRATION-001.md` — migration task file
- `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/` — pilot proof packet
- `PROJECT_PLAN/proof_packets/2bpp-hardware-reproof-4mhz/` — hardware reproof proof packet
- `AGENTS.md`, `AGENTS/*.md` — updated agent rules
