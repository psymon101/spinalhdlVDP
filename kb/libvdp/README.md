# libvdp API Reference

Canonical API reference for `firmware/libvdp/`.

## Scope

| Item | Value |
|---|---|
| Library path | `firmware/libvdp/` |
| Platforms | Pico 2 (Authoritative), ESP32, ESP8266 |
| Contract style | blocking C API |
| Source of truth | public headers in `firmware/libvdp/*.h` |

## Modules

| Module | Files | Purpose |
|---|---|---|
| Transport | `vdp_qspi.h`, `vdp_qspi.c` | QSPI register/status/SDRAM transactions |
| Status | `vdp_status.h`, `vdp_status.c` | sticky-bit polling and vblank waits |
| Upload | `vdp_upload.h`, `vdp_upload.c` | vblank-paced SDRAM asset upload |
| Mode0 | `vdp_mode0.h`, `vdp_mode0.c` | generic Mode0 helper layer |
| Copper | `vdp_copper.h`, `vdp_copper.c` | Copper opcode encoding + program upload |
| Platform | `vdp_platform.h` | board pin maps and transport constants |

## Initialization

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_qspi_init` | `void vdp_qspi_init(void)` | initialize QSPI pins and transport | call once before any other `libvdp` API |
| `vdp_pio_wait_sm_idle` | `void vdp_pio_wait_sm_idle(void)` | drain Pico PIO TX path before CS/pin changes | Pico-only effect; no-op on Arduino targets |
| `vdp_last_error` | `int vdp_last_error(void)` | read sticky host-library error state | library-side state only; FPGA-side errors come from status reads |

## Register / Status I/O

| Function | Signature | Purpose | Inputs | Output |
|---|---|---|---|---|
| `vdp_reg_write` | `void vdp_reg_write(uint32_t addr, uint16_t data)` | write one 16-bit VDP register word | 15-bit register address, 16-bit payload | none |
| `vdp_reg_write_burst` | `void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)` | write a contiguous block of register words | start address, little-endian word array, word count (1..253) | none |
| `vdp_read_status` | `uint32_t vdp_read_status(uint8_t sel)` | read one 32-bit status selector | selector `0..7` | 32-bit little-endian response |

### `vdp_read_status` selectors

| `sel` | Meaning |
|---|---|
| `0` | magic value |
| `1` | `rx_cmd_cnt` |
| `2` | `last_addr` |
| `3` | `last_data` |
| `4` | `last_error` |
| `5` | sticky status bits |
| `6` | upload status |
| `7` | committed live mode |

## SDRAM Upload

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_sdram_write` | `void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)` | stream 16-bit words into SDRAM | low-level burst write; does not vblank-pace itself |
| `vdp_upload_asset` | `bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words, uint16_t num_words, vdp_upload_cb cb)` | paced SDRAM upload across vblank windows | preferred high-level upload helper |

### Asset Pipeline

Host-side PNG conversion lives in [`scripts/assets/png_to_vdp_assets.py`](/home/itadmin/github/spinalhdlVDP/scripts/assets/png_to_vdp_assets.py).
Use it to generate raw `.bin` outputs plus optional `--header` metadata for
sketches or test harnesses.

If you want the raw payload embedded directly into a C header, use
[`scripts/assets/bin_to_c_array.py`](/home/itadmin/github/spinalhdlVDP/scripts/assets/bin_to_c_array.py)
on the generated `.bin` file.
[`firmware/esp8266_asset_upload/`](/home/itadmin/github/spinalhdlVDP/firmware/esp8266_asset_upload/)
shows the corresponding ESP8266 sketch template.

Typical flow:

1. Convert the source image into raw data and a generated header.
2. Include the generated header from a sketch or test harness.
3. Pass the exported SDRAM base address to `vdp_upload_asset()` or
   `vdp_sdram_write()`.

Example:

```sh
python3 scripts/assets/png_to_vdp_assets.py background frame.png build/frame \
  --bpp 4 --header build/frame.h --sdram-base 0x6000

python3 scripts/assets/bin_to_c_array.py build/frame.tiles.bin \
  build/frame_tiles.h --symbol frame_tiles
```

### Upload callback

| Type | Signature | Purpose |
|---|---|---|
| `vdp_upload_cb` | `void (*)(uint16_t words_sent, uint16_t words_total)` | progress callback after each burst |

### Upload constants

| Constant | Value | Meaning |
|---|---|---|
| `VDP_UPLOAD_WORDS_PER_VBLANK` | `16` | default chunk size per vblank burst |

## Sticky Status Helpers

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_wait_sticky` | `bool vdp_wait_sticky(uint16_t bit_mask, uint32_t timeout_us)` | wait until all requested sticky bits are set | polls selector `5` |
| `vdp_wait_vblank` | `bool vdp_wait_vblank(uint32_t timeout_us)` | clear and wait for next raster/vblank sticky event | convenience wrapper for paced loops |
| `vdp_clear_sticky` | `void vdp_clear_sticky(uint16_t mask)` | write-1-to-clear sticky bits | uses register `0x0320` |

### Sticky-bit constants

| Constant | Value | Meaning |
|---|---|---|
| `VDP_STICKY_RASTER_MATCH` | `0x0001` | raster trigger matched |
| `VDP_STICKY_SPRITE_OVERFLOW` | `0x0002` | sprite overflow |
| `VDP_STICKY_QSPI_READY` | `0x0004` | command accepted |
| `VDP_STICKY_QSPI_ERROR` | `0x0008` | QSPI error |
| `VDP_STICKY_SPRITE_0_HIT` | `0x0010` | slot-0 hit |
| `VDP_STICKY_SPRITE_BG_HIT` | `0x0020` | sprite/background hit |

## Mode0 Struct Types

| Type | Fields | Purpose |
|---|---|---|
| `vdp_mode0_rect_t` | `x0, x1, y0, y1` (all `uint16_t`) | Axis-aligned rectangle for windows / borders |
| `vdp_mode0_affine_t` | `a, b, c, d, x, y, ctrl` (all `uint16_t`) | Affine transform matrix + control |
| `vdp_mode0_bitmap_cfg_t` | `ctrl`, `bitmap_base`, `attr_base`, `bitmap_stride`, `attr_stride` | Bitmap+attribute fetch configuration |
| `vdp_mode0_trigger_t` | `line`, `pixel`, `ctrl` (all `uint16_t`) | Raster trigger line/pixel + control |
| `vdp_mode0_dma_cfg_t` | `dst`, `len_m1`, `fill`, `mode` | DMA engine configuration (FILL or COPY) |
| `vdp_mode0_blit_cfg_t` | `ctrl`, `width_m1`, `height_m1`, `dst_addr`, `dst_stride`, `src_addr`, `src_stride`, `fill_val` | Blitter engine configuration |

## Mode0 Helper Coverage

| Area | Helpers | Status / Coverage |
|---|---|---|
| **Globals** | `vdp_mode0_set_layer_enable`, `vdp_mode0_set_vdp_ctrl`, `vdp_mode0_set_mode_select`, `vdp_mode0_read_live_mode` | Full control plane. |
| **Window / Border** | `vdp_mode0_set_window1`, `vdp_mode0_set_window2`, `vdp_mode0_set_window_combine`, `vdp_mode0_border_ctrl`, `vdp_mode0_set_color_math` | 2-window + color-math. |
| **Bitmap / Affine** | `vdp_mode0_set_bitmap_cfg`, `vdp_mode0_set_bitmap_base`, `vdp_mode0_set_bitmap_stride`, `vdp_mode0_set_affine` | Page-flipping and transform. |
| **Palette** | `vdp_mode0_palette_set_ptr`, `vdp_mode0_palette_write_rgb888` | RGB888 burst writes. |
| **Copper / HDMA** | `vdp_copper_upload`, `vdp_copper_swap_request`, `vdp_mode0_set_hdma_ctrl`, `vdp_mode0_hdma_done_ack` | RAM and FSM control. |
| **DMA / Blitter** | `vdp_mode0_dma_config`, `vdp_mode0_blit_config` | Contiguous burst init. |
| **Raster** | `vdp_mode0_set_raster_trigger` | All 3 hardware triggers. |
| **Sprite** | `vdp_mode0_set_sprite`, `vdp_mode0_write_pattern_data`, `vdp_sprite_upload` | Descriptor, Pattern RAM, and All-in-one. |
| **Tables** | `vdp_mode0_write_linestate`, `vdp_mode0_set_vscroll_base` | V-scroll and line-buffer init. |

### All-in-one sprite upload (`vdp_sprite_upload`)

```c
bool vdp_sprite_upload(uint8_t slot,
                       const uint16_t *pattern, uint16_t pattern_start, uint16_t pattern_pixels,
                       const uint32_t *palette, uint8_t palette_start, uint8_t palette_count,
                       const vdp_mode0_sprite_cfg_t *cfg);
```

Wraps the three most common sprite-setup steps into one call:

1. **Palette** (optional) — uploads `palette_count` entries from `palette` (0x00RRGGBB format) starting at `palette_start`.
2. **Pattern RAM** — streams `pattern_pixels` 4bpp pixels from `pattern` into pattern RAM at `pattern_start`.
3. **Descriptor** (optional) — writes the sprite descriptor via `vdp_mode0_set_sprite()` when `cfg != NULL`.

Any step can be skipped by passing `NULL` / `0`. This is the preferred path for the common "upload one sprite and turn it on" use case.

## Sprite API Surface

| Sub-area | Helpers / Constants | Status | Notes |
|---|---|---|---|
| **Status (Sticky)** | `VDP_STICKY_SPRITE_OVERFLOW` | **DONE** | Set when line-buffer limit reached. |
| **Status (Sticky)** | `VDP_STICKY_SPRITE_0_HIT` | **DONE** | Slot-0 opaque-on-opaque hit. |
| **Status (Sticky)** | `VDP_STICKY_SPRITE_BG_HIT` | **DONE** | Any-sprite opaque-on-opaque hit. |
| **Control** | `vdp_mode0_clear_sprite_coll_mask` | **DONE** | Clears sticky collision bits in `0x0322`. |
| **Programming** | `vdp_mode0_set_sprite` | **DONE** | High-level API for sprite attribute table (SDRAM/Reg-backed) and hardening extension. |
| **Pattern RAM** | `vdp_mode0_set_pattern_ptr`, `vdp_mode0_write_pattern_data` | **DONE** | Upload 4bpp pixels into sprite pattern RAM at `0x0D10/0x0D11` (Task 53). Pointer auto-increments on data write. |
| **All-in-one** | `vdp_sprite_upload` | **DONE** | One-call palette + pattern + descriptor upload for the common 4bpp sprite path. |

## Migration & Naming Plan

1. **Barebones Separation:** Registers `0x0000..0x0005` in `TopTang20kBarebones` are build-specific proof registers. They conflict with the standard Mode0 `LINESTATE` map and must not be used with `vdp_mode0_*` helpers.
2. **Naming Convention:**
   - Helpers targeting barebones-only registers must use the `vdp_barebones_*` prefix.
   - Helpers targeting the stable, rich-top control plane must use the `vdp_mode0_*` prefix.
3. **Refactoring:** No renaming of existing `vdp_mode0_*` symbols or introduction of new `vdp_barebones_*` symbols is permitted until this document is audited.
4. **Transition Path:** When a barebones feature (e.g., procedural sprite) is adopted into the rich-top baseline, its `vdp_barebones_*` wrapper will be retired in favor of the equivalent `vdp_mode0_*` helper.

## Critical Implementation Facts

| Fact | Implication |
|---|---|
| **HostInterface is ABSENT** | `HostInterface.scala` is not instantiated in `TopTang20kHdmi` or `VdpTop`. The QSPI transport writes directly to the internal register bus. There is no host-side entry FIFO; bursts are not silently dropped by the transport itself. |
| **Copper Upload is Unbuffered** | Writes to `0x0400..0x05FF` (Copper Program RAM) hit memory directly. Chunking and inter-chunk delays in `vdp_copper_upload` are unnecessary and have been removed. |
| **Copper Drain Latency** | `copperFifo` (64 words) buffers writes *from* the Copper script. It drains at most once per scanline at `hCounter == 0`. This introduces a ~1-line vertical lag for effects committed via Copper. |
| **Copper Double-Buffer** | Two 512-word banks. Upload to `0x0400..0x05FF` while copper is **enabled** routes to the inactive bank. `vdp_copper_swap_request()` atomically swaps banks at the next `vSyncStart`. Sequencing rule: always upload to the inactive bank *before* requesting swap. |
| **Mode0 Coverage** | The `vdp_mode0_*` surface mirrors the landed rich-top register map block-by-block. New Mode0 blocks should land with matching helpers and doc updates; the only intentional gap is high-level sprite programming. |

## Minimal Usage Order

| Step | Call |
|---|---|
| 1 | `vdp_qspi_init()` |
| 2 | `vdp_reg_write(...)` and/or `vdp_read_status(...)` |
| 3 | `vdp_wait_vblank(...)` for paced visible updates |
| 4 | `vdp_reg_write_burst(...)` for contiguous register blocks, `vdp_upload_asset(...)` for bulk SDRAM upload |
| 5 | `vdp_wait_sticky(...)` / `vdp_clear_sticky(...)` for proof/status checks |

## Proof Notes

| Rule | Requirement |
|---|---|
| Trustworthy upload proof | verify `VDP_STICKY_QSPI_ERROR` is clear |
| Visible update pacing | use `vdp_wait_vblank` or equivalent |
| Reuse | keep common transport logic in `libvdp`, not per-sketch code |
