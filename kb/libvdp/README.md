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

## Mode0 Helpers

Implementation rule: when Mode0 gains a new register block or control path,
the firmware surface should grow a matching `vdp_mode0_*` helper and this
table should be updated in the same change unless BronzeGate approves a
documented raw-only exception.

| Area | Helpers |
|---|---|
| globals | `vdp_mode0_set_layer_enable`, `vdp_mode0_set_vdp_ctrl`, `vdp_mode0_set_tile_mode`, `vdp_mode0_set_attr_mode`, `vdp_mode0_set_mode_select`, `vdp_mode0_read_live_mode` |
| status | `vdp_mode0_set_status_enable`, `vdp_mode0_clear_status`, `vdp_mode0_clear_sprite_coll_mask` |
| windows / border | `vdp_mode0_set_window1`, `vdp_mode0_set_window2`, `vdp_mode0_set_window_combine`, `vdp_mode0_set_border_window`, `vdp_mode0_border_ctrl` |
| affine | `vdp_mode0_set_affine` |
| bitmap | `vdp_mode0_bitmap_ctrl`, `vdp_mode0_set_bitmap_cfg` |
| raster | `vdp_mode0_trigger_ctrl`, `vdp_mode0_set_raster_trigger` |
| palette | `vdp_mode0_palette_set_ptr`, `vdp_mode0_palette_write_data`, `vdp_mode0_palette_write_rgb888` |
| copper / hdma | `vdp_mode0_write_copper_word`, `vdp_mode0_hdma_write`, `vdp_mode0_set_hdma_base`, `vdp_mode0_set_hdma_ctrl`, `vdp_mode0_hdma_done_ack`, `vdp_mode0_set_hdma_ch_addr`, `vdp_mode0_set_hdma_data_ptr`, `vdp_mode0_hdma_write_data` |
| tables | `vdp_mode0_write_linestate`, `vdp_mode0_write_vscroll_entry`, `vdp_mode0_set_vscroll_base` |
| dma | `vdp_mode0_dma_ctrl`, `vdp_mode0_dma_write_staging`, `vdp_mode0_dma_config` |
| blitter | `vdp_mode0_blit_ctrl`, `vdp_mode0_blit_write_src`, `vdp_mode0_blit_config` |
| sprite | `vdp_mode0_set_sprite`, `vdp_mode0_set_pattern_ptr`, `vdp_mode0_write_pattern_data` |

### Sprite Programming Example

```c
vdp_mode0_sprite_cfg_t cfg = {0};
cfg.x = 100;
cfg.y = 150;
cfg.pat_idx = 4;
cfg.enabled = true;
cfg.size_sel = 1;     // 16x16
cfg.pal_bank = 0;
cfg.prio = 1;
vdp_mode0_set_sprite(0, &cfg);
```

## Copper Helpers

| Function | Signature | Purpose |
|---|---|---|
| `vdp_copper_wait` | `uint16_t vdp_copper_wait(uint16_t y)` | Encode legacy `WAIT(Y)` opcode (1 word) |
| `vdp_copper_wait_xy` | `uint16_t vdp_copper_wait_xy(uint16_t x)` | Encode pixel-precise `WAIT(X,Y)` header word (2-word sequence) |
| `vdp_copper_write_seq_hdr` | `uint16_t vdp_copper_write_seq_hdr(uint16_t addr, uint8_t count_m1)` | Encode `WRITE_SEQ` header for N consecutive register writes |
| `vdp_copper_write_op` | `uint16_t vdp_copper_write_op(uint16_t addr)` | Encode single `WRITE` opcode header (1 word; data word follows) |
| `vdp_copper_jump` | `uint16_t vdp_copper_jump(uint16_t target_pc)` | Encode `JUMP` opcode (1 word) |
| `vdp_copper_skip_op` | `uint16_t vdp_copper_skip_op(uint8_t cond, uint8_t offset)` | Encode `SKIP` opcode (BH-2, 1 word). `cond` = 3-bit comparator, `offset` = words to skip |
| `vdp_copper_upload` | `void vdp_copper_upload(const uint16_t *prog, uint16_t nwords)` | Upload a copper program into FPGA copper RAM via burst writes (disables copper first) |
| `vdp_copper_enable` | `void vdp_copper_enable(bool en)` | Enable/disable copper via `VDP_CTRL` bit[0] |
| `vdp_copper_swap_request` | `void vdp_copper_swap_request(void)` | Request atomic bank swap at next vSyncStart. Copper must be enabled. Writes `0x0003` to `VDP_CTRL @ 0x0310` (bit[0]=enable, bit[1]=swap). HW auto-clears bit[1] after commit. |
| `vdp_copper_upload_and_swap` | `void vdp_copper_upload_and_swap(const uint16_t *prog, uint16_t nwords)` | Upload to inactive bank (copper must be enabled) and request atomic swap. Closes stale-bank hazard by making swap unskippable. |

## Platform Constants

| Constant | Value | Meaning |
|---|---|---|
| `VDP_QSPI_SCK_HZ` | `2000000u` | proven transport clock |

## API Classification

| Category | Description | Target Build | Status |
|---|---|---|---|
| **Transport** | QSPI framing (`vdp_reg_write`, `vdp_reg_write_burst`, `vdp_read_status`) | All | Authoritative |
| **System** | Status, vblank sync, asset upload | All | Authoritative |
| **Generic Mode0** | Rich-top register surface (`vdp_mode0_*`) | mode2optimized | Authoritative — all allocated write-path registers have direct helpers. |
| **Barebones Proof**| Barebones-top registers (scroll + sprite) | barebones-rebuild| Functional — inline bit-bang sketches; not yet wrapped in `libvdp` |
| **Copper** | Copper opcode helpers + program upload | mode2optimized | Authoritative |

## Mode0 Helper Coverage

| Area | Helpers | Coverage Notes |
|---|---|---|
| **Background** | `vdp_mode0_set_layer_enable`, `vdp_mode0_write_vscroll_entry`, `vdp_mode0_set_vscroll_base` | Covers global enable and 1D scroll table. |
| **Window / Border / Color Math** | `vdp_mode0_set_window1`, `vdp_mode0_set_window2`, `vdp_mode0_set_window_combine`, `vdp_mode0_set_border_window`, `vdp_mode0_border_ctrl`, `vdp_mode0_set_border_ctrl`, `vdp_mode0_set_color_math` | Comprehensive 2-window + border + color-math control. Standalone helpers for dynamic updates without rewriting full blocks. |
| **Affine** | `vdp_mode0_set_affine` | Covers regs A-D, X, Y, and ctrl with one contiguous burst. |
| **Bitmap** | `vdp_mode0_bitmap_ctrl`, `vdp_mode0_set_bitmap_cfg`, `vdp_mode0_set_bitmap_ctrl` | Base addresses, stride, and BPP with one contiguous burst. Standalone bitmap ctrl helper for quick enable/disable. |
| **Palette** | `vdp_mode0_palette_set_ptr`, `vdp_mode0_palette_write_data`, `vdp_mode0_palette_write_rgb888` | High-level RGB888 and low-level word access. |
| **DMA / Blitter** | `vdp_mode0_dma_ctrl`, `vdp_mode0_dma_config`, `vdp_mode0_blit_ctrl`, `vdp_mode0_blit_config` | Staging RAM and FSM control with batched contiguous register writes. |
| **Raster** | `vdp_mode0_trigger_ctrl`, `vdp_mode0_set_raster_trigger` | Covers all 3 bus-controlled triggers in one burst per trigger. |
| **Copper / HDMA** | `vdp_mode0_write_copper_word`, `vdp_mode0_hdma_write`, `vdp_mode0_set_hdma_base`, `vdp_mode0_set_hdma_ctrl`, `vdp_mode0_hdma_done_ack`, `vdp_mode0_set_hdma_ch_addr`, `vdp_mode0_set_hdma_data_ptr`, `vdp_mode0_hdma_write_data` | RAM and HDMA config registers. Structured helpers cover enable/mask, done-ack, channel addresses, and indirect-data pointer/write. |
| **Status** | `vdp_mode0_set_status_enable`, `vdp_mode0_clear_status`, `vdp_wait_sticky`, `vdp_wait_vblank`, `vdp_clear_sticky` | Interrupt/sticky mask control + polling helpers. Sticky constants cover RASTER_MATCH through MODE_SELECT_CHANGED. |
| **Sprite** | `vdp_mode0_set_sprite`, `vdp_mode0_set_pattern_ptr`, `vdp_mode0_write_pattern_data` | Covers 32 slots of affine descriptor RAM (8 words/slot) plus Hardening extension (word 8). Pattern-RAM upload helpers for `0x0D10/0x0D11` (Task 53). |

## Sprite API Surface

| Sub-area | Helpers / Constants | Status | Notes |
|---|---|---|---|
| **Status (Sticky)** | `VDP_STICKY_SPRITE_OVERFLOW` | **DONE** | Set when line-buffer limit reached. |
| **Status (Sticky)** | `VDP_STICKY_SPRITE_0_HIT` | **DONE** | Slot-0 opaque-on-opaque hit. |
| **Status (Sticky)** | `VDP_STICKY_SPRITE_BG_HIT` | **DONE** | Any-sprite opaque-on-opaque hit. |
| **Control** | `vdp_mode0_clear_sprite_coll_mask` | **DONE** | Clears sticky collision bits in `0x0322`. |
| **Programming** | `vdp_mode0_set_sprite` | **DONE** | High-level API for sprite attribute table (SDRAM/Reg-backed) and hardening extension. |
| **Pattern RAM** | `vdp_mode0_set_pattern_ptr`, `vdp_mode0_write_pattern_data` | **DONE** | Upload 4bpp pixels into sprite pattern RAM at `0x0D10/0x0D11` (Task 53). Pointer auto-increments on data write. |

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
