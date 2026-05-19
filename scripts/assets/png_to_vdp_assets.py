#!/usr/bin/env python3
"""
Convert PNG assets into raw VDP-friendly data files.

Supported workflows:
- background: full-frame PNG -> deduplicated tileset + tilemap + attrmap
- tilesheet: tiled PNG -> packed tile rows
- spritesheet: sprite atlas PNG -> packed sprite pattern blocks
- palette: PNG palette -> RGB888 table

Outputs are raw files intended for firmware upload or downstream packaging.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from typing import Iterable

from PIL import Image


DEFAULT_TILE_W = 16
DEFAULT_TILE_H = 16
DEFAULT_BPP = 4
DEFAULT_MAX_TILES = 256


def ensure_dir(path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def open_image(path: pathlib.Path) -> Image.Image:
    try:
        return Image.open(path)
    except FileNotFoundError as exc:
        raise SystemExit(f"input image not found: {path}") from exc


def to_indexed_image(img: Image.Image, max_colors: int, dither: Image.Dither) -> Image.Image:
    if img.mode == "P":
        lo, hi = img.getextrema()
        if hi < max_colors:
            return img.copy()
        img = img.convert("RGBA")
    elif img.mode in {"L", "LA"}:
        img = img.convert("RGBA")
    elif img.mode != "RGBA":
        img = img.convert("RGBA")
    return img.quantize(colors=max_colors, dither=dither)


def palette_from_image(img: Image.Image, count: int) -> list[tuple[int, int, int]]:
    pal = img.getpalette()
    if pal is None:
        return [(0, 0, 0)] * count
    out: list[tuple[int, int, int]] = []
    for i in range(count):
        base = i * 3
        if base + 2 < len(pal):
            out.append((pal[base], pal[base + 1], pal[base + 2]))
        else:
            out.append((0, 0, 0))
    return out


def write_palette(path: pathlib.Path, palette: list[tuple[int, int, int]]) -> None:
    ensure_dir(path)
    with path.open("wb") as f:
        for r, g, b in palette:
            f.write(bytes((r & 0xFF, g & 0xFF, b & 0xFF)))


def pack_pixels(pixels: Iterable[int], bpp: int) -> bytes:
    mask = (1 << bpp) - 1
    # Match the repo's tile-row convention: leftmost pixel occupies the low
    # bits of the first byte, so we pack from right to left before emitting
    # little-endian bytes.
    values = list(pixels)[::-1]
    if not values:
        return b""
    total_bits = len(values) * bpp
    if total_bits % 8 != 0:
        raise ValueError(f"pixel count {len(values)} at {bpp} bpp does not pack to whole bytes")
    packed = 0
    for value in values:
        packed = (packed << bpp) | (value & mask)
    return packed.to_bytes(total_bits // 8, "little")


def cell_rows(img: Image.Image, x0: int, y0: int, cell_w: int, cell_h: int, bpp: int) -> bytes:
    out = bytearray()
    px = img.load()
    for y in range(y0, y0 + cell_h):
        row = [int(px[x, y]) for x in range(x0, x0 + cell_w)]
        out.extend(pack_pixels(row, bpp))
    return bytes(out)


def quantize_for_bpp(img: Image.Image, bpp: int, dither: Image.Dither) -> Image.Image:
    max_colors = 1 << bpp
    if img.mode == "P":
        lo, hi = img.getextrema()
        if hi < max_colors:
            return img.copy()
    return to_indexed_image(img, max_colors=max_colors, dither=dither)


def emit_manifest(path: pathlib.Path, payload: dict) -> None:
    ensure_dir(path)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def build_tilesheet(
    input_path: pathlib.Path,
    output_prefix: pathlib.Path,
    tile_w: int,
    tile_h: int,
    bpp: int,
    dither: Image.Dither,
) -> None:
    with open_image(input_path) as src:
        img = quantize_for_bpp(src, bpp, dither)
        if img.width % tile_w or img.height % tile_h:
            raise SystemExit(
                f"image size {img.width}x{img.height} must be a multiple of tile size {tile_w}x{tile_h}"
            )
        tiles_x = img.width // tile_w
        tiles_y = img.height // tile_h
        tile_blobs = []
        for ty in range(tiles_y):
            for tx in range(tiles_x):
                tile_blobs.append(cell_rows(img, tx * tile_w, ty * tile_h, tile_w, tile_h, bpp))

        tiles_path = output_prefix.with_suffix(".tiles.bin")
        ensure_dir(tiles_path)
        tiles_path.write_bytes(b"".join(tile_blobs))

        palette_path = output_prefix.with_suffix(".palette.bin")
        write_palette(palette_path, palette_from_image(img, 1 << bpp))

        emit_manifest(
            output_prefix.with_suffix(".json"),
            {
                "kind": "tilesheet",
                "input": str(input_path),
                "tile_w": tile_w,
                "tile_h": tile_h,
                "bpp": bpp,
                "tiles_x": tiles_x,
                "tiles_y": tiles_y,
                "tiles": tiles_x * tiles_y,
                "outputs": {
                    "tiles": str(tiles_path),
                    "palette": str(palette_path),
                },
            },
        )


def build_spritesheet(
    input_path: pathlib.Path,
    output_prefix: pathlib.Path,
    sprite_w: int,
    sprite_h: int,
    bpp: int,
    dither: Image.Dither,
) -> None:
    with open_image(input_path) as src:
        img = quantize_for_bpp(src, bpp, dither)
        if img.width % sprite_w or img.height % sprite_h:
            raise SystemExit(
                f"image size {img.width}x{img.height} must be a multiple of sprite size {sprite_w}x{sprite_h}"
            )
        sprites_x = img.width // sprite_w
        sprites_y = img.height // sprite_h
        sprite_blobs = []
        for sy in range(sprites_y):
            for sx in range(sprites_x):
                sprite_blobs.append(cell_rows(img, sx * sprite_w, sy * sprite_h, sprite_w, sprite_h, bpp))

        patterns_path = output_prefix.with_suffix(".patterns.bin")
        ensure_dir(patterns_path)
        patterns_path.write_bytes(b"".join(sprite_blobs))

        palette_path = output_prefix.with_suffix(".palette.bin")
        write_palette(palette_path, palette_from_image(img, 1 << bpp))

        emit_manifest(
            output_prefix.with_suffix(".json"),
            {
                "kind": "spritesheet",
                "input": str(input_path),
                "sprite_w": sprite_w,
                "sprite_h": sprite_h,
                "bpp": bpp,
                "sprites_x": sprites_x,
                "sprites_y": sprites_y,
                "sprites": sprites_x * sprites_y,
                "outputs": {
                    "patterns": str(patterns_path),
                    "palette": str(palette_path),
                },
            },
        )


def build_background(
    input_path: pathlib.Path,
    output_prefix: pathlib.Path,
    tile_w: int,
    tile_h: int,
    bpp: int,
    attr_fill: int,
    attr_image_path: pathlib.Path | None,
    dither: Image.Dither,
    max_tiles: int,
) -> None:
    with open_image(input_path) as src:
        img = quantize_for_bpp(src, bpp, dither)
        if img.width % tile_w or img.height % tile_h:
            raise SystemExit(
                f"image size {img.width}x{img.height} must be a multiple of tile size {tile_w}x{tile_h}"
            )
        tiles_x = img.width // tile_w
        tiles_y = img.height // tile_h
        tile_map: list[int] = []
        unique_tiles: list[bytes] = []
        tile_to_idx: dict[bytes, int] = {}
        for ty in range(tiles_y):
            for tx in range(tiles_x):
                blob = cell_rows(img, tx * tile_w, ty * tile_h, tile_w, tile_h, bpp)
                idx = tile_to_idx.get(blob)
                if idx is None:
                    idx = len(unique_tiles)
                    if idx >= max_tiles:
                        raise SystemExit(
                            f"background uses more than {max_tiles} unique tiles; increase --max-tiles or simplify the image"
                        )
                    tile_to_idx[blob] = idx
                    unique_tiles.append(blob)
                tile_map.append(idx)

        tiles_path = output_prefix.with_suffix(".tiles.bin")
        tilemap_path = output_prefix.with_suffix(".tilemap.bin")
        attrmap_path = output_prefix.with_suffix(".attrmap.bin")
        ensure_dir(tiles_path)
        tiles_path.write_bytes(b"".join(unique_tiles))
        tilemap_path.write_bytes(bytes(tile_map))

        if attr_image_path is not None:
            with open_image(attr_image_path) as attr_src:
                attr_img = attr_src.convert("L") if attr_src.mode != "P" else attr_src.copy()
                if attr_img.width != tiles_x or attr_img.height != tiles_y:
                    raise SystemExit(
                        f"attr image size {attr_img.width}x{attr_img.height} must match tile grid {tiles_x}x{tiles_y}"
                    )
                attr_bytes = bytes(int(attr_img.getpixel((x, y))) & 0xFF for y in range(tiles_y) for x in range(tiles_x))
        else:
            attr_bytes = bytes([attr_fill & 0xFF] * (tiles_x * tiles_y))
        attrmap_path.write_bytes(attr_bytes)

        palette_path = output_prefix.with_suffix(".palette.bin")
        write_palette(palette_path, palette_from_image(img, 1 << bpp))

        emit_manifest(
            output_prefix.with_suffix(".json"),
            {
                "kind": "background",
                "input": str(input_path),
                "tile_w": tile_w,
                "tile_h": tile_h,
                "bpp": bpp,
                "tiles_x": tiles_x,
                "tiles_y": tiles_y,
                "unique_tiles": len(unique_tiles),
                "outputs": {
                    "tiles": str(tiles_path),
                    "tilemap": str(tilemap_path),
                    "attrmap": str(attrmap_path),
                    "palette": str(palette_path),
                },
            },
        )


def add_common_image_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("input", type=pathlib.Path, help="input PNG")
    parser.add_argument("output_prefix", type=pathlib.Path, help="output file prefix")
    parser.add_argument(
        "--bpp",
        type=int,
        default=DEFAULT_BPP,
        choices=(1, 2, 4, 8),
        help="pixel depth for indexed output",
    )
    parser.add_argument(
        "--dither",
        choices=("none", "floyd"),
        default="none",
        help="quantization dither mode",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    bg = sub.add_parser("background", help="deduplicate a full image into tiles + tilemap")
    add_common_image_args(bg)
    bg.add_argument("--tile-w", type=int, default=DEFAULT_TILE_W)
    bg.add_argument("--tile-h", type=int, default=DEFAULT_TILE_H)
    bg.add_argument("--attr-image", type=pathlib.Path, default=None, help="optional attr PNG, one pixel per tile")
    bg.add_argument("--attr-fill", type=int, default=0, help="attr byte used when no attr image is supplied")
    bg.add_argument("--max-tiles", type=int, default=DEFAULT_MAX_TILES)

    ts = sub.add_parser("tilesheet", help="pack a tiled PNG into raw tile row blocks")
    add_common_image_args(ts)
    ts.add_argument("--tile-w", type=int, default=DEFAULT_TILE_W)
    ts.add_argument("--tile-h", type=int, default=DEFAULT_TILE_H)

    ss = sub.add_parser("spritesheet", help="pack a sprite atlas into raw sprite pattern blocks")
    add_common_image_args(ss)
    ss.add_argument("--sprite-w", type=int, default=DEFAULT_TILE_W)
    ss.add_argument("--sprite-h", type=int, default=DEFAULT_TILE_H)

    pal = sub.add_parser("palette", help="extract the image palette as RGB888")
    pal.add_argument("input", type=pathlib.Path)
    pal.add_argument("output", type=pathlib.Path)
    pal.add_argument("--count", type=int, default=16)

    args = parser.parse_args()
    dither = Image.Dither.NONE if getattr(args, "dither", "none") == "none" else Image.Dither.FLOYDSTEINBERG

    if args.cmd == "background":
        build_background(
            args.input,
            args.output_prefix,
            args.tile_w,
            args.tile_h,
            args.bpp,
            args.attr_fill,
            args.attr_image,
            dither,
            args.max_tiles,
        )
    elif args.cmd == "tilesheet":
        build_tilesheet(args.input, args.output_prefix, args.tile_w, args.tile_h, args.bpp, dither)
    elif args.cmd == "spritesheet":
        build_spritesheet(
            args.input,
            args.output_prefix,
            args.sprite_w,
            args.sprite_h,
            args.bpp,
            dither,
        )
    elif args.cmd == "palette":
        with open_image(args.input) as src:
            img = to_indexed_image(src, max_colors=max(args.count, 1), dither=dither)
            write_palette(args.output, palette_from_image(img, args.count))
            emit_manifest(
                args.output.with_suffix(".json"),
                {
                    "kind": "palette",
                    "input": str(args.input),
                    "count": args.count,
                    "output": str(args.output),
                },
            )
    else:
        raise SystemExit(f"unsupported command: {args.cmd}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
