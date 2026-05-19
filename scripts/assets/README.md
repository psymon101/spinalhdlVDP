# PNG Asset Conversion

`png_to_vdp_assets.py` converts art sources into raw data files that match the
current VDP asset layouts.

Supported modes:

- `background`: full-frame PNG -> deduplicated tileset + tilemap + attrmap
- `tilesheet`: tiled PNG -> packed tile-row blocks
- `spritesheet`: sprite atlas PNG -> packed sprite pattern blocks
- `palette`: PNG palette -> RGB888 table

Packed tile and sprite rows follow the repo convention: leftmost pixel first
in memory, with 4bpp pixels stored two per byte.

Examples:

```sh
python3 scripts/assets/png_to_vdp_assets.py background input.png out/bg --bpp 4
python3 scripts/assets/png_to_vdp_assets.py tilesheet input.png out/tiles --bpp 4
python3 scripts/assets/png_to_vdp_assets.py spritesheet input.png out/sprites --bpp 4
python3 scripts/assets/png_to_vdp_assets.py palette input.png out/palette.bin --count 16
```

Outputs are raw files plus a small JSON manifest with the inferred dimensions
and destination paths.
