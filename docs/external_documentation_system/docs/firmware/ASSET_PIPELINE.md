# Asset Pipeline

## Purpose

Convert common source assets into deterministic platform-native binary layouts.

## Required properties

- command-line operation;
- pinned dependencies;
- deterministic output;
- input and output hashes;
- explicit width/height/palette checks;
- failure on unsupported colors/layout;
- golden conversion tests.

## Required converters

- generic packed indexed formats;
- generic planar;
- ZX screen/attributes;
- TMS tables;
- Sega/NES/Genesis/SNES tile formats;
- C64 screen/color/bitmap;
- Atari ST interleaved planar;
- Amiga independent bitplanes;
- procedural helper data where useful for TIA.
