# Task 10026 — Barebones Simple Sprite over Background

**Version:** 1.0-draft  
**Author:** BronzeGate / Codex  
**Date:** 2026-05-16  
**Status:** Checkpoint A artifact drafted  
**Branch:** `mode0t20-barebones-rebuild`

## Task

Add one proof-sized sprite slice on top of the already-working barebones scrolling background path. The slice must prove sprite-over-background composition on Tang Nano 20K without pulling in the full sprite engine or any SDRAM-backed sprite path.

## Purpose

The barebones branch already proves the stable host-driven background substrate. The next useful proof is the smallest sprite overlay that can be driven from the same tiny QSPI register file and shown moving over the scrolling background.

This closes the gap between "background only" and "one visible sprite primitive" on the stable barebones lane.

## Scope

- in scope: one sprite only
- in scope: one sprite composited above the existing L0/L1 background pair
- in scope: host-written X/Y/enable control for the single sprite
- in scope: a fixed, small sprite pattern source
- in scope: simulation that proves sprite-over-background composition and position update
- in scope: hardware proof with one visible moving sprite over a scrolling background
- out of scope: sprite engine expansion
- out of scope: multiple sprites or per-line sprite selection
- out of scope: SDRAM-backed sprite storage or sprite DMA
- out of scope: sprite collision, masking, affine, or sprite priority tables
- out of scope: reusing or reintroducing the main `VdpTop` sprite pipeline

## Dependencies

- Barebones scrolling background path already proven on `mode0t20-barebones-rebuild`
- `QspiBarebones` register-write path already proven for the scroll registers
- Existing 2-layer barebones compositor remains the background baseline

## Interfaces / State

- add a minimal sprite register block to the barebones QSPI map
- reserve a small contiguous range, likely `0x0010..0x0013`, for:
- `SPRITE_EN`
- `SPRITE_X`
- `SPRITE_Y`
- `SPRITE_STYLE` or fixed-pattern selector if the final slice needs a second visual
- store only the state needed for one visible sprite
- keep the sprite source fixed-size and on-chip

## Timing / Memory Notes

- sprite evaluation should be combinational against the current pixel coordinate
- composition order should be `sprite over L1 over L0`
- sprite pixels must be transparent outside the sprite footprint
- no SDRAM reads are allowed on this lane
- the sprite source should fit in small on-chip ROM or logic constants

## Risks

- the sprite can disappear into the background if its palette choice is too close to the backdrop
- a large or dynamic sprite source can pull the slice out of "barebones" scope
- adding too many control registers can drift the branch back toward the main VDP control plane
- a coordinate-only proof can be visually ambiguous unless the sprite motion is clearly distinct from the scroll motion

## Validation

- sim: sprite visible above the scrolling background at a fixed coordinate
- sim: updating X/Y moves only the sprite, not the background
- sim: transparency works so the background is visible outside the sprite footprint
- hardware: one moving sprite is visible over the scrolling background during a capture run

## Audit Focus

- is the sprite slice still exactly one sprite?
- does the implementation avoid SDRAM and the full sprite engine?
- is the composition order unambiguous on screen?
- are the control registers minimal and self-contained?

## Exit Condition

This task is ready for implementation when the barebones top has a single host-driven sprite overlay, simulation proves the overlay over the scrolling background, and the hardware capture shows one unambiguous moving sprite on top of the existing background path.

