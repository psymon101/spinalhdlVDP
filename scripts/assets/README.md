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

Optional `--header` and `--sdram-base` arguments generate a small C header
and embed the chosen upload address in the metadata for sketch-side use.
The header is metadata only; the raw `.bin` files remain the payload source.

If you want to include the payload directly in C, run
`bin_to_c_array.py` on the raw `.bin` file to generate a `static const`
`uint16_t` header for `vdp_upload_asset()`.
See `firmware/esp8266_asset_upload/` for the smallest in-tree example.

Examples:

```sh
python3 scripts/assets/png_to_vdp_assets.py background input.png out/bg --bpp 4
python3 scripts/assets/png_to_vdp_assets.py tilesheet input.png out/tiles --bpp 4
python3 scripts/assets/png_to_vdp_assets.py spritesheet input.png out/sprites --bpp 4
python3 scripts/assets/png_to_vdp_assets.py palette input.png out/palette.bin --count 16
python3 scripts/assets/png_to_vdp_assets.py background input.png out/bg --bpp 4 --header out/bg.h --sdram-base 0x6000
python3 scripts/assets/png_to_vdp_assets.py palette input.png out/palette.bin --count 16 --header out/palette.h --sdram-base 0x7000
python3 scripts/assets/bin_to_c_array.py out/bg.tiles.bin out/bg_tiles.h --symbol bg_tiles
```

Outputs are raw files plus a small JSON manifest with the inferred dimensions
and destination paths.
