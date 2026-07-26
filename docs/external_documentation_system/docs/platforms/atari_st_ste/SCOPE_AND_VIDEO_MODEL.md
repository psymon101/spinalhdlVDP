# Atari ST/STE — Scope and Video Model

## Supported visual target

- ST low 320×200 4 planes
- ST medium 640×200 2 planes
- ST high 640×400 1 plane
- ST RGB333
- STE RGB444
- border/scaling
- raster palette changes
- selected STE extensions

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
