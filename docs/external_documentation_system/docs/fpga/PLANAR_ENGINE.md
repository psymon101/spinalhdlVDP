# Planar Engine

## Goal

Provide one shared planar fetch/decode substrate for native platform adapters.

## Required capability

- one through six planes;
- independent-plane layout;
- Atari ST interleaved-word layout;
- tile-planar layout where reused;
- selectable bit significance;
- per-plane base;
- common or odd/even modulo;
- logical width and clipping;
- line reset hooks for stateful decoders such as HAM6.

## Output

Produce palette indices or platform-decoder inputs at a documented latency.

## SpinalSim vectors

- one-plane bit significance;
- all plane counts 1–6;
- independent pointers;
- nonzero modulo;
- interleaved ST 16-pixel groups;
- line boundaries;
- missing/disabled plane;
- forced SDRAM latency;
- EHB/HAM handoff.
