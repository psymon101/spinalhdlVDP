# Amiga OCS/ECS — Scope and Video Model

## Supported visual target

- 1–6 independent bitplanes
- lores/hires
- 32-color palette
- dual playfield
- 8 OCS-style sprites
- attached sprites
- display/fetch windows
- odd/even modulo
- Copper changes
- basic Blitter copy/fill/line
- EHB
- HAM6
- selected ECS positioning
- explicitly no AGA

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.
