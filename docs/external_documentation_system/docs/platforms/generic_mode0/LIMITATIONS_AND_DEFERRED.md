# Generic Mode0 — Limitations and Deferred Work

## Current exclusions

- No platform-specific native register compatibility in the generic API.
- Exact cycle behavior is not claimed unless a specific engine test states it.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.
