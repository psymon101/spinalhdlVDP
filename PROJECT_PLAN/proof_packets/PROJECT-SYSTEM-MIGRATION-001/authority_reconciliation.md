> This file is part of `PROJECT-SYSTEM-MIGRATION-001`.
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Authority Reconciliation

**Date:** 2026-07-26  
**Commit:** `095b65d`

## Authority order

1. Latest authoritative mailbox instruction.
2. Repository-root `STATUS.md`.
3. `PROJECT_PLAN/TASKS/PROJECT-SYSTEM-MIGRATION-001.md` and active task.
4. Current repository state and commit.

## Fact reconciliation

| Fact | Mail | STATUS.md | Task file | Repo | Resolved authority | Action |
|---|---|---|---|---|---|---|
| Active migration lane | Kickoff 14352 | `PROJECT-SYSTEM-MIGRATION-001` RUNNING | Task file exists, state `AUTHORIZED` | Lane row and task file committed | `STATUS.md` + task file | None — consistent. |
| External docs adoption scope | CoralReef review file (not mail); kickoff references it | `external-docs-system-review` REVIEW; PM decision pending | Selective merge, keep STATUS.md canonical | `external_docs_system_review.md` committed | `STATUS.md` + CoralReef review | Awaiting PM disposition (this migration implements selective merge). |
| Live-state authority | Kickoff lists `STATUS.md` as #2 | `STATUS.md` is authoritative | Task file lists `STATUS.md` as #2 | `AGENTS.md` and `PROJECT_PLAN.md` confirm | `STATUS.md` | None — consistent. |
| 2bpp-backlog-cosim status | External review consolidated handoff | DONE — `5efe049`; gate met | N/A | Commit `5efe049` exists | `STATUS.md` + commit | None — consistent. |
| 2bpp-bank-completion-rtl status | External review consolidated handoff | UNBLOCKED — cleared to implement | N/A | `BitmapRowFetch.scala`, `VdpTop.scala` source | `STATUS.md` + source | None — consistent. |
| 2bpp-hardware-reproof-4mhz blocker | BronzeGate updates | BLOCKED on #14345 | N/A | No `b04c5546…` bitstream on disk | `STATUS.md` | None — consistent. |
| Agent registration names | Team mail 14346 | AGENTS.md update committed | N/A | `AGENTS.md` lists canonical names | `AGENTS.md` | None — consistent. |
| Current commit/branch | Kickoff 14352 | N/A (repo state) | `958a01d` pre-migration, now `095b65d` | `095b65d` on `brightforge/ham-decoder-171` | Repo state | Update task file to current commit after each phase. |

## Technical conflicts requiring separate reconciliation tasks

No new technical conflicts identified. Existing conflicts are already tracked:

1. **External docs adoption scope** — resolved by this migration (selective merge).
2. **2bpp-hardware-reproof-4mhz** — remains BLOCKED on bitstream authority; tracked in `STATUS.md`.

## Open items

| Item | Owner | Status | Resolution path |
|---|---|---|---|
| BrightForge snapshot confirmation | BrightForge | Pending mail 14353 | Await reply or escalate if no response. |
| BronzeGate snapshot confirmation | BronzeGate | Pending mail 14354 | Await reply or escalate if no response. |
| PM disposition on external-docs adoption | TopazCliff | In progress | This migration implements CoralReef's selective-merge recommendation. |
