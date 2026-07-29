# ADR-006 — One active shared RTL integration lane

## Status

Accepted.

## Decision

Only one lane may modify common top-level or shared timing/memory components at
a time. Parallel work is limited to research, documents, vectors, firmware-only
work, and isolated components that do not create integration conflicts.
