# Sega Mega Drive/Genesis — Limitations and Deferred Work

## Current exclusions

- No complete FIFO or CPU interface timing.
- Undocumented VDP quirks require separate approved scope.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.
