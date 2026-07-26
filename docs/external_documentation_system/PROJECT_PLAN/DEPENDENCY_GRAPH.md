# Dependency Graph

```text
FOUNDATION-0
    ↓
FOUNDATION-1 — shared Mode0 substrate
    ↓
FOUNDATION-2 — host-independent libvdp
    ↓
GENERIC-MODE0 closure
    ↓
ZX closure
    ↓
TMS9918A
    ↓
SMS / Game Gear
    ↓
NES
    ↓
C64
    ↓
Atari ST / STE
    ↓
Amiga OCS / ECS
    ↓
Mega Drive / Genesis
    ↓
SNES Modes 0–3-lite
```

Atari 2600 TIA depends on Foundation 1 and Foundation 2, but uses a dedicated
procedural scanline frontend. Its research and test-vector work may proceed in
parallel. Its shared-RTL integration waits for the active integration lane.

## Shared dependency matrix

| Capability | First lane requiring closure |
|---|---|
| Stable packed bitmap | Generic Mode0 |
| Stable tile/sprite substrate | TMS9918A |
| Native planar tile decode | NES |
| Raster-event automation | C64 |
| Interleaved framebuffer planar | Atari ST |
| Independent 1–6 plane pointers | Amiga |
| Complex layer priority | Genesis |
| Windows/color math/HDMA | SNES |
| Procedural beam-timed writes | Atari 2600 |
