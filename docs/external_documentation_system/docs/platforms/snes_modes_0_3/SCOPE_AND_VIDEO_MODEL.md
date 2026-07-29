# SNES Modes 0–3-lite — Scope and Video Model

## Supported visual target

- Modes 0–3 only
- up to four backgrounds
- required 2/4/8bpp tiles
- 128 descriptors/approved 32 sprites per line
- windows/masks
- color math
- per-line HDMA
- mode priority
- Mode 7 optional only after closure

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
