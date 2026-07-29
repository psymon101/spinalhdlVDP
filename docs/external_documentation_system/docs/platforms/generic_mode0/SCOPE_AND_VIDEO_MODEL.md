# Generic Mode0 — Scope and Video Model

## Supported visual target

- Host-independent graphics-card programming model.
- Packed indexed 1/2/4/8bpp and RGB565 after encoding reconciliation.
- One-to-six-plane shared planar capability.
- Four background layers, shared sprites, palette, windows, color math.
- Copper, HDMA, LINESTATE, DMA, Blitter, scaling, borders, HDMI.
- Capability registers, ABI version, diagnostics, atomic commit.

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
