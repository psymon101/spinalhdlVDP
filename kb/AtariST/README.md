# Atari ST VDP (Shifter)

## 1. Video Model Summary

- **Native logical resolutions:**
  - Low: 320×200, 16 colors (4 bitplanes)
  - Medium: 640×200, 4 colors (2 bitplanes)
  - High: 640×400, 2 colors (1 bitplane), 71.2 Hz monochrome
- **Display structure:** Planar framebuffer with bitplane interleaving
- **Frame rate:** 50/60 Hz (Low/Medium); 71.2 Hz (High)

## 2. Supported Features

- 320×200 4-plane planar output (primary v1 target)
- 640×200 2-plane planar output
- 16-entry palette (9-bit RGB on ST; 12-bit RGB on STE)
- Bitplane interleaving in 16-bit words
- Contiguous 32 KB Chip RAM framebuffer

## 3. Unsupported / Deferred Features

- **STE Blitter:** Not in scope for v1 adapter.
- **71.2 Hz monochrome mode:** Mode0 targets 60 Hz standard HDMI output.
- **Exact interleaving format:** Host may pre-interleave, or Mode0 may use shuffled fetch.

## 4. Adapter Register Surface

- `$FFFF8201/03/0D`: Video Base Address (High, Mid, Low)
- `$FFFF8205/07/09`: Video Counter (current read pointer)
- `$FFFF820A`: Sync Mode (Bit 0: External Sync; Bit 1: 0=60 Hz, 1=50 Hz)
- `$FFFF8240–$FFFF825F`: Palette Registers (16 words)
- `$FFFF8260`: Resolution (0=Low, 1=Medium, 2=High)

### Palette Formats
- **ST:** `0000 0RRR 0GGG 0BBB` (9-bit, 512 colors)
- **STE:** `0000 Rrrr Gggg Bbbb` (12-bit, 4,096 colors)

## 5. Mode0 Mapping

| Atari ST Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| 320×200 4-plane | Planar fetch (4 bitplanes) | Map interleaved bitplanes to planarLineFetch |
| 640×200 2-plane | Planar fetch (2 bitplanes) | Subset of 4-plane path |
| Palette | Palette RAM | Load 16-entry palette from host |
| Raster timing | Raster trigger / Copper-lite | Support palette changes during display |

## 6. Host Memory Layout

- **Frame buffer:** Exactly 32,000 bytes (32 KB) of contiguous Chip RAM
- **Format:** Bitplane interleaving in 16-bit words (Low Res)
  - 4 words define 16 pixels
  - Word 0 = Bit 0 of all 16 pixels
  - Word 1 = Bit 1 of all 16 pixels
  - Word 2 = Bit 2 of all 16 pixels
  - Word 3 = Bit 3 of all 16 pixels

## 7. Firmware Workflow

1. Host sets video base address via `$FFFF8201/03/0D`
2. Host loads palette via `$FFFF8240–$FFFF825F`
3. Host sets resolution to Low (0) via `$FFFF8260`
4. Host uploads frame buffer data to Chip RAM
5. Adapter presents planar output with correct palette mapping

## 8. Proof / Validation Plan

- **Sim:** Verify planar fetch produces correct 16-color output from interleaved bitplanes
- **Hardware:** Static Atari ST-style test pattern renders correctly; palette swap via raster trigger if used; 30s capture freeze=0
- **Scope check:** No hidden expansion beyond bounded v1 scope

## 9. Known Gaps / Gotchas

- **Planar framebuffer look:** Preserve the planar framebuffer look and resolution-dependent presentation rather than flattening into a generic tilemap output.
- **Palette sensitivity:** Palette behavior is mode-sensitive and should not be generalized away.
- **Raster/border tricks:** May matter to the final character of the adapter proof.
- **Minimum readiness:** Through `R7` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Atari ST Shifter technical documentation (Info-Coach)](http://www.info-coach.fr/atari/hardware/video.php)

### Localized References

The following reference materials are stored locally in `kb/AtariST/references/MiSTeryNano`:
- `gstshifter.v`
- `shifter_video.v`
- `README.md`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

