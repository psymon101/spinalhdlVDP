> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-006 — One active shared RTL integration lane

## Status

Accepted.

## Decision

Only one lane may modify common top-level or shared timing/memory components at
a time. Parallel work is limited to research, documents, vectors, firmware-only
work, and isolated components that do not create integration conflicts.
