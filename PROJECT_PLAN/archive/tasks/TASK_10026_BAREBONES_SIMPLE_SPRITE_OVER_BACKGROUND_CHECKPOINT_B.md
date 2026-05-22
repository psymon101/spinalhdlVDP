# Task 10026 Checkpoint B — Barebones Simple Sprite Slice

**Version:** 1.0-draft  
**Author:** BronzeGate / Codex  
**Date:** 2026-05-16  
**Status:** Ready for BrightForge implementation handoff  
**Branch:** `mode0t20-barebones-rebuild`

## Task

Implement the smallest visible sprite overlay on the barebones Tang Nano 20K path: one host-driven sprite, composited over the existing scrolling background, with no dependency on the main `VdpTop` sprite engine.

## Purpose

The barebones branch already proves stable scrolling background output and a working host write path. This slice adds the first visible sprite primitive so the lane can prove composition, movement, and visibility on the stable substrate before any broader sprite work is considered.

## Scope

- in scope: one sprite only
- in scope: sprite composited over the existing two-layer barebones background
- in scope: host-written X/Y/enable control
- in scope: a fixed on-chip sprite pattern or logic-backed sprite shape
- in scope: simulation of sprite-over-background composition and motion
- in scope: hardware proof with one visible moving sprite over scroll motion
- out of scope: sprite engine expansion
- out of scope: multiple sprites or per-line selection logic
- out of scope: SDRAM-backed sprite storage
- out of scope: sprite DMA, collision, masking, affine, or priority tables
- out of scope: reintroducing the main `VdpTop` sprite subsystem

## Dependencies

- Barebones scrolling background path is already working
- QSPI register-write path already working
- Existing background compositor remains the base display path

## Interfaces / State

- add a minimal host register block for sprite enable and position
- keep the state small and local to the barebones top
- choose a register layout that does not conflict with the existing scroll registers
- use a fixed sprite source that is easy to see against the current background palette

## Timing / Memory Notes

- composition should be simple and deterministic
- sprite visibility must be obvious in a single capture frame
- sprite transparency outside the footprint must preserve the background
- no SDRAM reads are allowed for this slice

## Risks

- sprite contrast may be too low against the current background
- the proof can become ambiguous if motion is too subtle
- the slice can drift out of barebones scope if the sprite source or control block grows
- a coordinate-only proof is not enough if the sprite is not visually distinct

## Validation

- sim: sprite visible above the scrolling background
- sim: changing X/Y moves only the sprite
- sim: background remains visible outside the sprite footprint
- hardware: capture shows one unambiguous moving sprite over the scrolling background

## Audit Focus

- still exactly one sprite
- no SDRAM dependency introduced
- no main VdpTop sprite engine pulled in
- proof is visually unambiguous

## Exit Condition

This checkpoint is done when the barebones top displays one host-driven sprite over the existing scrolling background, the simulation proves the composition and motion contract, and the hardware capture shows the sprite clearly moving on top of the background.

