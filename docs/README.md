> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# spinalhdlVDP Documentation Guide

## Purpose

This directory holds stable technical documentation for the spinalhdlVDP project.

## Levels

- `architecture/` — system-wide technical contracts.
- `fpga/` — SpinalHDL component specifications and verification expectations.
- `firmware/` — `libvdp`, ABI, transport, and host-porting contracts.
- `runbooks/` — exact commands and operational procedures.
- `testing/` — objective test oracles, evidence, and clean-room reproduction.
- `troubleshooting/` — known issues and diagnostic procedures.
- `reproducibility/` — release manifests and reproducibility requirements.

## Authority rule

No fact should be manually maintained in multiple authoritative locations.

| Information | Authority |
|---|---|
| Live state, history, blockers, active lanes | `STATUS.md` |
| Active task/checkpoint details | `PROJECT_PLAN/TASKS/<TASK>.md` |
| Register addresses and fields | authoritative register schema |
| FPGA behavior | approved SpinalHDL/component specification |
| Public firmware API | `libvdp` headers/generated API docs |
| Stable platform behavior | `kb/<Adapter>/` |
| Exact commands | `docs/runbooks/` |
| Expected results | test specifications and golden vectors |
| Actual results | `PROJECT_PLAN/proof_packets/<LANE>/` |
| Architecture decisions | `PROJECT_PLAN/DECISIONS/` |
| Release hashes and tool versions | release manifest |

## Adding a document

1. Identify the correct directory and authority.
2. Include the authority banner at the top.
3. Link to `STATUS.md` for live state.
4. Do not duplicate register addresses, APIs, live status, or proof results.
