# ZX Spectrum VDP (ULA)

## 1. Video Model Summary

- **Native logical resolution:** 256×192 pixels
- **Display structure:** Compact bitmap centered within a bordered display area
- **Border:** Software-controlled via port `$FE`; visually important and often used deliberately
- **Cell structure:** 1 attribute byte per 8×8 pixel block
- **Frame rate:** 50 Hz (PAL)

## 2. Supported Features

- Bitmap display with attribute-color pairing
- 8-color base palette with bright variants (15 effective colors)
- Software-controlled border color
- Flash / blink attribute (alternates ink/paper at ~1.5 Hz)
- Bright attribute (applies to both ink and paper simultaneously)

## 3. Unsupported / Deferred Features

- **Contention:** ULA stalls the Z80 when accessing contended memory (`$4000`–`$7FFF`). Mode0 substrate ignores host-side contention.
- **Flash timing:** The ~1.5 Hz blink rate is handled by the adapter or host; Mode0 does not have a native 1.5 Hz blinker.
- **Exact ULA timing:** Cycle-accurate ULA behavior is not required for v1 adapter proof.

## 4. Adapter Register Surface

- **Port `$FE` (Write):** Border color (bits 0–2), BEEP audio (bit 4), MIC (bit 3)
- **Port `$FE` (Read):** Keyboard matrix + EAR input
- No direct VDP-style register file; display is memory-mapped

## 5. Mode0 Mapping

| ZX Spectrum Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| 256×192 bitmap | Bitmap + attribute pairing | Present as shuffled fetch with cell attributes |
| 8-color + bright palette | Indexed palette (16 entries) | Map ink/paper + bright to palette bank |
| Border color | Background/border fill | Drive border color from port `$FE` writes |
| Flash attribute | Adapter/host toggle | Alternate ink/paper at ~1.5 Hz in software |
| Attribute clash | Preserved limitation | Do not silently upgrade to per-pixel color |

## 6. Host Memory Layout

- **Bitmap:** `$4000`–`$57FF` (6,144 bytes)
- **Attributes:** `$5800`–`$5AFF` (768 bytes)
- **Non-linear layout:** Address bits for bitmap offset in `$4000`:
  `00 [Y7 Y6] [Y2 Y1 Y0] [Y5 Y4 Y3] [X4 X3 X2 X1 X0]`

### Attribute Byte Format
- Bits 0–2: Ink (foreground) color
- Bits 3–5: Paper (background) color
- Bit 6: Bright (applies to both ink and paper)
- Bit 7: Flash (alternates ink and paper)

## 7. Firmware Workflow

1. Host uploads bitmap data to `$4000`–`$57FF` via QSPI or parallel bus
2. Host uploads attribute data to `$5800`–`$5AFF`
3. Host sets border color via port `$FE` writes
4. Adapter presents the framebuffer with correct attribute pairing

## 8. Proof / Validation Plan

- **Sim:** Verify bitmap + attribute pairing produces correct colors per 8×8 cell
- **Hardware:** Static test pattern with border, attributes, and bright bit; 30s capture freeze=0
- **Honest check:** Confirm attribute clash is visible, not smoothed away

## 9. Known Gaps / Gotchas

- **Attribute clash:** Moving objects crossing 8×8 color cells will show color-cell limitations. The adapter must preserve this visible artifact instead of silently upgrading to per-pixel color freedom.
- **Border importance:** The border is visually characteristic of the Spectrum. Active picture should not simply fill the whole HDMI frame unless the adapter explicitly documents a "cropped modern presentation" mode.
- **Pixel scaling:** Integer enlargement is preferred where practical to preserve the compact bitmap feel.
- **Minimum readiness:** Through `R7.2` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [ZX Spectrum ULA technical documentation (Chris Smith)](http://www.zxdesign.info/book.shtml) — Definitive guide to ULA timing and behavior
- [Spectrum for Everyone - ULA details](https://spectrumforeveryone.com/technical/zx-spectrum-ula-details/)
