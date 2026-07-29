# SNES Modes 0–3-lite — Limitations and Deferred Work

## Current exclusions

- No interlace or full cycle-accurate PPU behavior.
- Mode 7 is a later lane; advanced mosaic/offset corner cases deferred.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.
