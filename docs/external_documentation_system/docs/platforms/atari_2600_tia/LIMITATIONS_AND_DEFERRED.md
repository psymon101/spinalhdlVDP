# Atari 2600 TIA — Limitations and Deferred Work

## Current exclusions

- Visual procedural TIA only.
- Host must prepare timed writes ahead of the beam; late events are reported.
- No 6507 or complete console emulation.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.
