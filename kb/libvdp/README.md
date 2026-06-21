# libvdp API Reference

Canonical API reference for `firmware/libvdp/`.

## Scope

| Item | Value |
|---|---|
| Library path | `firmware/libvdp/` |
| Platforms | ESP32-S3 (Authoritative), ESP32, ESP8266, Pico 2 (Legacy), Tang Nano 20K (Target) |
| Contract style | blocking C API |
| Source of truth | public headers in `firmware/libvdp/*.h` |

## Modules

| Module | Files | Purpose |
|---|---|---|
| Transport | `vdp_host.h`, `vdp_host.c` | host register/status/SDRAM transactions |
| Legacy transport shim | `vdp_legacySpi.h` | deprecated compatibility include and `vdp_legacy_spi_*` aliases |
| Status | `vdp_status.h`, `vdp_status.c` | sticky-bit polling and vblank waits |
| Upload | `vdp_upload.h`, `vdp_upload.c` | vblank-paced SDRAM asset upload |
| Mode0 | `vdp_mode0.h`, `vdp_mode0.c` | generic Mode0 helper layer |
| Copper | `vdp_copper.h`, `vdp_copper.c` | Copper opcode encoding + program upload |
| Platform | `vdp_platform.h` | board pin maps and transport constants |

## Initialization

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_host_init` | `void vdp_host_init(void)` | initialize host pins and transport | call once before any other `libvdp` API |
| `vdp_legacy_spi_init` | `void vdp_legacy_spi_init(void)` | deprecated alias for `vdp_host_init()` | kept for legacy sketches |
| `vdp_pio_wait_sm_idle` | `void vdp_pio_wait_sm_idle(void)` | drain Pico PIO TX path before CS/pin changes | Pico-only effect; no-op on Arduino targets |
| `vdp_last_error` | `int vdp_last_error(void)` | read sticky host-library error state | library-side state only; FPGA-side errors come from status reads |
| `vdp_host_set_speed_hz` | `void vdp_host_set_speed_hz(uint32_t hz)` | change host transport frequency at runtime | only effective on legacy SPI2/legacy SPI compatibility builds; no-op elsewhere |
| `vdp_legacy_spi_set_speed_hz` | `void vdp_legacy_spi_set_speed_hz(uint32_t hz)` | deprecated alias for `vdp_host_set_speed_hz()` | kept for legacy sketches |

## Host Interface Policy

The Tang Nano 20K deployment currently uses i80 as the canonical host
interface. Legacy legacy SPI code remains available for compatibility and historical
bench work.

| Interface | Type | Usage | Pin Group |
|---|---|---|---|
| **i80 (Primary)** | 8-bit Parallel | Current bench setup (ESP32-S3). Lowest latency. | I80 (GPIOs) |
| **legacy SPI (Legacy)** | 4-bit Serial | Compatibility/Pico 2 historical setup. | legacy SPI (FSPI/PIO) |

The `libvdp` API abstractions (`vdp_reg_write`, `vdp_sdram_write`) remain identical across both transports.

## Host Speed Policy

Legacy legacy SPI/SPI2 compatibility builds support a "two-speed" policy to maximize
throughput while maintaining read reliability. The canonical ESP32-S3 i80 host
path ignores `vdp_host_set_speed_hz()`.

| Direction | Recommended Speed | Rationale |
|---|---|---|
| **Reads** | **3 MHz** (`VDP_HOST_SCK_HZ`, legacy alias `VDP_SPI_SCK_HZ`) | FPGA response FSM caps at 3 MHz; higher rates cause read failure. |
| **Writes** | **8 MHz** (`VDP_HOST_SCK_WRITE_HZ`, legacy alias `VDP_SPI_SCK_WRITE_HZ`) | Firmware physical cap for compatibility builds. |

### Usage Example

```c
// Perform bulk upload at high speed
vdp_host_set_speed_hz(VDP_HOST_SCK_WRITE_HZ);
vdp_sdram_write(addr, big_buffer, 253);

// Switch back to safe speed for status polling
vdp_host_set_speed_hz(VDP_HOST_SCK_HZ);
uint32_t magic = vdp_read_status(0);
```

## Register / Status I/O

| Function | Signature | Purpose | Inputs | Output |
|---|---|---|---|---|
| `vdp_reg_write` | `void vdp_reg_write(uint32_t addr, uint16_t data)` | write one 16-bit VDP register word | 15-bit register address, 16-bit payload | none |
| `vdp_reg_write_burst` | `void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)` | write a contiguous block of register words | start address, little-endian word array, word count (1..253) | none |
| `vdp_read_status` | `uint32_t vdp_read_status(uint8_t sel)` | read one 32-bit status selector | selector `0..7` | 32-bit little-endian response |

> **Transport note:** `vdp_read_status()` is implemented only on the legacy SPI backend. The canonical i80 RTL decoder does not currently decode the `READ_STATUS` opcode (`0x04`); on i80/ESP32-S3 hosts, poll status through normal register reads (e.g., `0x0320` for sticky status).

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
| `VDP_STICKY_HOST_READY` | `0x0004` | command accepted |
| `VDP_STICKY_HOST_ERROR` | `0x0008` | host error |
| `VDP_STICKY_LEGACY_SPI_READY` | `0x0004` | deprecated alias for `VDP_STICKY_HOST_READY` |
| `VDP_STICKY_LEGACY_SPI_ERROR` | `0x0008` | deprecated alias for `VDP_STICKY_HOST_ERROR` |
| `VDP_STICKY_SPRITE_0_HIT` | `0x0010` | slot-0 hit |
| `VDP_STICKY_SPRITE_BG_HIT` | `0x0020` | sprite/background hit |
| `VDP_STICKY_DMA_DONE` | `0x0100` | DMA transfer complete |
| `VDP_STICKY_BLIT_DONE` | `0x0200` | Blitter block transfer complete |
| `VDP_STICKY_MODE_SELECT_CHANGED` | `0x0800` | Mode selection committed at V=0 |

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
| **Globals** | `vdp_mode0_set_layer_enable`, `vdp_mode0_set_vdp_ctrl`, `vdp_mode0_set_vdp_ctrl_word`, `vdp_mode0_set_mode_select`, `vdp_mode0_read_live_mode` | Full control plane. |
| **Window / Border** | `vdp_mode0_set_window1`, `vdp_mode0_set_window2`, `vdp_mode0_set_window_combine`, `vdp_mode0_border_ctrl`, `vdp_mode0_set_border_window`, `vdp_mode0_set_border_ctrl`, `vdp_mode0_set_color_math` | 2-window + border + color-math. |
| **Bitmap / DirectColor** | `vdp_mode0_bitmap_ctrl`, `vdp_mode0_set_bitmap_cfg`, `vdp_mode0_set_bitmap_ctrl`, `vdp_mode0_set_bitmap_base`, `vdp_mode0_set_attr_base`, `vdp_mode0_set_bitmap_stride`, `vdp_mode0_set_attr_stride`, `vdp_mode0_set_affine` | RGB565 DirectColor + Affine path. |
| **Palette** | `vdp_mode0_palette_set_ptr`, `vdp_mode0_palette_write_data`, `vdp_mode0_palette_write_rgb888` | RGB888 burst writes. |
| **Palette LUTs** | `vdp_tms9918_load_palette`, `vdp_sms_palette_write`, `vdp_gg_palette_write`, `vdp_atarist_palette_write`, `vdp_atariste_palette_write` | Per-platform native-value → RGB888 converters. |
| **Copper / HDMA** | `vdp_copper_enable`, `vdp_copper_upload`, `vdp_copper_swap_request`, `vdp_copper_upload_and_swap`, `vdp_mode0_set_hdma_ctrl`, `vdp_mode0_hdma_done_ack`, `vdp_mode0_set_hdma_ch_addr`, `vdp_mode0_set_hdma_data_ptr`, `vdp_mode0_hdma_write_data`, `vdp_mode0_set_hdma_base`, `vdp_mode0_hdma_ctrl_encode` | Double-buffer RAM and FSM control. |
| **DMA / Blitter** | `vdp_mode0_dma_config`, `vdp_mode0_dma_ctrl`, `vdp_mode0_dma_write_staging`, `vdp_mode0_blit_config`, `vdp_mode0_blit_ctrl`, `vdp_mode0_blit_write_src` | DMA/Blitter engine init and control. |
| **Raster** | `vdp_mode0_set_raster_trigger`, `vdp_mode0_trigger_ctrl` | All 3 hardware triggers. |
| **Scaler** | `vdp_mode0_scale_ctrl`, `vdp_mode0_set_scale_ctrl`, `vdp_mode0_set_logic_size`, `vdp_mode0_set_scale_mode` | Integer pixel repetition + auto-center (Task 10590). |
| **Sprite** | `vdp_mode0_set_sprite`, `vdp_sprite_upload`, `vdp_mode0_set_pattern_ptr`, `vdp_mode0_write_pattern_data` | 32-descriptor substrate + Pattern RAM. |
| **Tables** | `vdp_mode0_write_linestate`, `vdp_mode0_set_vscroll_base`, `vdp_mode0_write_vscroll_entry` | V-scroll and line-buffer init. |

### All-in-one sprite upload (`vdp_sprite_upload`)

```c
bool vdp_sprite_upload(uint8_t slot,
                       const uint16_t *pattern, uint16_t pattern_start, uint16_t pattern_pixels,
                       const uint32_t *palette, uint8_t palette_start, uint8_t palette_count,
                       const vdp_mode0_sprite_cfg_t *cfg);
```

One-call palette + pattern + descriptor upload. Any step can be skipped with `NULL` / `0`:

1. **Palette** — uploads `palette_count` RGB888 entries from `palette` to `palette_start`.
2. **Pattern RAM** — streams `pattern_pixels` 4bpp pixels from `pattern` to `pattern_start`.
3. **Descriptor** — writes the sprite descriptor via `vdp_mode0_set_sprite()` when `cfg != NULL`.

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

| Rule | Detail |
|---|---|
| Barebones separation | Registers `0x0000..0x0005` (barebones) conflict with Mode0 `LINESTATE` (`0x0000`). Do not mix `vdp_mode0_*` helpers with barebones builds. |
| Naming convention | `vdp_barebones_*` = barebones-only registers; `vdp_mode0_*` = rich-top control plane. |
| Refactoring freeze | No renaming of existing `vdp_mode0_*` symbols or new `vdp_barebones_*` symbols until this document is audited. |
| Transition path | When a barebones feature moves to rich-top, retire its `vdp_barebones_*` wrapper for the equivalent `vdp_mode0_*` helper. |

## Critical Implementation Facts

| Fact | Implication |
|---|---|
| **HostInterface is ABSENT** | The active host bridge writes directly to the internal register bus. No host-side entry FIFO; bursts are not silently dropped by transport. |
| **Copper Upload is Unbuffered** | Writes to `0x0400..0x05FF` hit Copper Program RAM directly. Chunking and inter-chunk delays are unnecessary and removed. |
| **Copper Drain Latency** | `copperFifo` (64 words) drains at most once per scanline at `hCounter == 0`. Effects via Copper have ~1-line vertical lag. |
| **Copper Double-Buffer** | Two 512-word banks. Upload while copper is **enabled** routes to the inactive bank. `vdp_copper_swap_request()` swaps at the next `vSyncStart`. Upload *before* requesting swap. |
| **Disabled-layer Backdrop** | When all layers are disabled, the compositor falls through to a color indexed by `palette[layer0Bank*16+0]`. At POR, `layer0Bank` is often **Bank 4** (Black) due to uninitialized SDRAM. |
| **Mode0 Coverage** | `vdp_mode0_*` mirrors the rich-top register map block-by-block. New Mode0 blocks land with matching helpers and doc updates; the only intentional gap is high-level sprite programming. |

## Minimal Usage Order

| Step | Call |
|---|---|
| 1 | `vdp_host_init()` |
| 2 | `vdp_reg_write(...)` and/or `vdp_read_status(...)` |
| 3 | `vdp_wait_vblank(...)` for paced visible updates |
| 4 | `vdp_reg_write_burst(...)` for contiguous register blocks, `vdp_upload_asset(...)` for bulk SDRAM upload |
| 5 | `vdp_wait_sticky(...)` / `vdp_clear_sticky(...)` for proof/status checks |

## Proof Notes

| Rule | Requirement |
|---|---|
| Trustworthy upload proof | verify `VDP_STICKY_HOST_ERROR` is clear (`VDP_STICKY_LEGACY_SPI_ERROR` is the legacy alias) |
| Visible update pacing | use `vdp_wait_vblank` or equivalent |
| Reuse | keep common transport logic in `libvdp`, not per-sketch code |

## Platform Personality (2026-05-24)

As part of the **RTL Platform-Agnosticism Purge (#10567)**, all platform-specific initialization and register shims have moved from the FPGA into `libvdp`.

The following high-level helpers provide functional parity for legacy scenes:

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_mode_c64_init` | `void vdp_mode_c64_init(void)` | setup C64-accurate registers/palette | replaces Scenario 20 |
| `vdp_mode_zx_init` | `void vdp_mode_zx_init(void)` | setup Spectrum-accurate registers/palette | replaces Scenario 50 |

These helpers perform the necessary `vdp_reg_write` and `vdp_palette_write` sequences previously hardcoded in the RTL bootstrap.
