# NES/Famicom — Scope and Video Model

## Supported visual target

- 256×240/224 presentation
- 2bpp pattern tables
- nametables/attribute tables
- fine scrolling
- 64 sprites/8 per line
- sprite-zero hit
- sprite/background priority

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
