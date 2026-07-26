# Superseded Files

This file lists documents and artifacts that have been replaced by the modular
system introduced in `PROJECT-SYSTEM-MIGRATION-001`. It is maintained by the
PM (`TopazCliff`).

## Reference-only external snapshot

- `docs/external_documentation_system/`
  - Superseded as a canonical authority by `PROJECT_PLAN/STATUS.md` and the
    modular docs under `docs/`, `kb/`, `docs/runbooks/`, and `docs/testing/`.
  - Retained as a read-only reference snapshot per
    `PROJECT_PLAN/external_docs_system_review.md` and
    `PROJECT_PLAN/DECISIONS/ADR-007-MIGRATION-SYSTEM-CUTOVER.md`.
  - Do not update these files to reflect live state.

## Archived stale task ledger

- `PROJECT_PLAN/archive/TASKS_stale_2026-06-19.md`
  - Superseded by `PROJECT_PLAN/STATUS.md` (live lane authority) and
    `PROJECT_PLAN/TASKS/*.md` (durable task descriptions).

## How to update this list

When a file, directory, or artifact is permanently replaced, add a row here
with the superseded path, the replacement authority, and the date. Do not
remove historical entries.
