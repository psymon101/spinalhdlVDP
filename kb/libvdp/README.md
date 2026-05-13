# libvdp API Reference

Canonical API reference for `firmware/libvdp/`.

## Scope

| Item | Value |
|---|---|
| Library path | `firmware/libvdp/` |
| Platforms | Pico 2, ESP32, ESP8266 |
| Contract style | blocking C API |
| Source of truth | public headers in `firmware/libvdp/*.h` |

## Modules

| Module | Files | Purpose |
|---|---|---|
| Transport | `vdp_qspi.h`, `vdp_qspi.c` | QSPI register/status/SDRAM transactions |
| Status | `vdp_status.h`, `vdp_status.c` | sticky-bit polling and vblank waits |
| Upload | `vdp_upload.h`, `vdp_upload.c` | vblank-paced SDRAM asset upload |
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
| `vdp_read_status` | `uint32_t vdp_read_status(uint8_t sel)` | read one 32-bit status selector | selector `0..6` | 32-bit little-endian response |

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

## SDRAM Upload

| Function | Signature | Purpose | Notes |
|---|---|---|---|
| `vdp_sdram_write` | `void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)` | stream 16-bit words into SDRAM | low-level burst write; does not vblank-pace itself |
| `vdp_upload_asset` | `bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words, uint16_t num_words, vdp_upload_cb cb)` | paced SDRAM upload across vblank windows | preferred high-level upload helper |

### Upload callback

| Type | Signature | Purpose |
|---|---|---|
| `vdp_upload_cb` | `void (*)(uint16_t words_sent, uint16_t words_total)` | progress callback after each burst |

### Upload constants

| Constant | Value | Meaning |
|---|---|---|
| `VDP_UPLOAD_WORDS_PER_VBLANK` | `8` | default chunk size per vblank burst |

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

## Platform Constants

| Constant | Value | Meaning |
|---|---|---|
| `VDP_QSPI_SCK_HZ` | `2000000u` | proven transport clock |

## Minimal Usage Order

| Step | Call |
|---|---|
| 1 | `vdp_qspi_init()` |
| 2 | `vdp_reg_write(...)` and/or `vdp_read_status(...)` |
| 3 | `vdp_wait_vblank(...)` for paced visible updates |
| 4 | `vdp_upload_asset(...)` for bulk SDRAM upload |
| 5 | `vdp_wait_sticky(...)` / `vdp_clear_sticky(...)` for proof/status checks |

## Proof Notes

| Rule | Requirement |
|---|---|
| Trustworthy upload proof | verify `VDP_STICKY_QSPI_ERROR` is clear |
| Visible update pacing | use `vdp_wait_vblank` or equivalent |
| Reuse | keep common transport logic in `libvdp`, not per-sketch code |
