> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-004 — Shared engines plus platform adapters

## Status

Accepted.

## Decision

Platforms reuse shared bitmap, planar, tile, sprite, palette, Copper, HDMA,
Blitter, compositor, scaler, and HDMI engines. Platform-specific logic is added
only where shared engines cannot represent required visual behavior.
