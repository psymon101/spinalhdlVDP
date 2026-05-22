# VDP Programming Guide

**Version:** 1.1 (Draft)  
**Date:** 2026-05-22  
**Target Platform:** Tang Nano 20K (Mode0)  
**Host Libraries:** `libvdp` (C/C++)

This guide provides a practical, example-driven introduction to programming the `spinalhdlVDP`. It is divided into two parts: a high-level API guide for application developers using `libvdp`, and a low-level register map reference for system-level integration.

**Note on Scope:** This guide covers the most common programming patterns. For a complete, exhaustive reference of every function, constant, and struct field, always consult the [**`libvdp` API Reference**](kb/libvdp/README.md).

**Canonical References:**
- **`libvdp` API:** [`kb/libvdp/README.md`](kb/libvdp/README.md)
- **Register Map:** [`PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md`](PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md)
- **Architecture Overview:** [`PROJECT_PLAN/MODE0_PLANNING.md`](PROJECT_PLAN/MODE0_PLANNING.md)

---

# Part I: libvdp API Guide

The `libvdp` library is the authoritative host-side coordination layer. It handles QSPI transport, timing synchronization, and provides high-level helpers for the Mode0 display engine.

## 1. Initialization and Basic I/O

Every VDP application must start by initializing the transport layer.

```c
#include "vdp_qspi.h"
#include "vdp_mode0.h"

void setup() {
    // Initialize QSPI pins and transport
    vdp_qspi_init();

    // Optional: Verify communication by reading the magic status value
    uint32_t magic = vdp_read_status(0);
    if (magic != 0x51560002) {
        // Handle transport error
    }
}
```

### Writing Registers
Registers are 16-bit. Writes pulse directly into the VDP's internal register bus. Most writes land in a shadow register and are committed at the start of the next scanline (`hCounter == 0`) to prevent mid-frame tearing.

```c
// Write a single register
vdp_reg_write(VDP_MODE0_REG_LAYER_ENABLE, 0x0007); // Enable L0, L1, and Sprites

// Write a contiguous block (burst write)
uint16_t window_cfg[] = { 10, 310, 20, 220, 0x0001 };
vdp_reg_write_burst(VDP_MODE0_REG_WIN1_X0, window_cfg, 5); // Setup WIN1 X/Y and Color Math
```

## 2. Display Setup (Layers and Modes)

The VDP supports multiple background layers and specific platform "adapter" modes.

```c
// Select a platform adapter (e.g., ZX Spectrum mode)
vdp_mode0_set_mode_select(VDP_MODE_ID_SPECTRUM);

// Enable layers: L0 (bit 0), L1 (bit 1), Sprites (bit 2)
vdp_mode0_set_layer_enable(0x07);
```

## 3. Asset Management

Assets (tiles, bitmaps, patterns) are typically stored in the VDP's external SDRAM.

### Paced Asset Upload
For large assets, use the vblank-paced helper to avoid starving the display engine of memory bandwidth.

```c
#include "vdp_upload.h"

extern const uint16_t my_tiles_bin[];
extern const uint16_t my_tiles_len;

void upload_graphics() {
    // Upload tiles to SDRAM address 0x4000
    // This helper automatically waits for vblank windows to perform bursts
    vdp_upload_asset(0x4000, my_tiles_bin, my_tiles_len, NULL);
}
```

## 4. Palette and Color

Mode0 uses an RGB888 (24-bit) internal palette, but writes are performed as 16-bit words to the palette data register.

### Generic Palette Write
```c
// Set palette index 1 to full red using convenience helper
vdp_mode0_palette_write_rgb888(1, 0xFF, 0x00, 0x00);
```

### Platform-Specific LUTs
Fidelity-focused helpers map native platform color values to the best RGB888 representation.

```c
// Load the canonical TMS9918 (MSX/Coleco) 16-color palette
vdp_tms9918_load_palette();

// Write an Atari ST native color (12-bit) to index 1
vdp_atarist_palette_write(1, 0x700); // ST 'Red' -> Mode0 RGB888
```

## 5. Sprites

The `main` substrate supports **32 total descriptors** (slots) and **8 visible sprites per scanline**.

### Manual Configuration
```c
vdp_mode0_sprite_cfg_t my_sprite = {
    .x = 100,
    .y = 50,
    .pat_idx = 4,    // Pattern index in Pattern RAM
    .pal_bank = 0,   // 3-bit palette bank
    .prio = 0,       // 2-bit priority
    .enabled = true,
    .bpp_sel = 2,    // 4bpp
    .flip_h = false,
    .flip_v = false
};
vdp_mode0_set_sprite(0, &my_sprite); // Update slot 0
```

### All-in-One Upload
A convenience helper for updating pattern data, optional palette, and descriptor in one call.
```c
vdp_sprite_upload(0, 
    pattern_data, 0, 64,   // Pattern RAM: upload 64 pixels starting at index 0
    palette_data, 16, 16,  // Palette: 16 colors starting at index 16
    &my_sprite             // Descriptor update (NULL to skip)
);
```

## 6. Automation and Engines

Beyond static registers, the VDP includes several specialized engines for high-performance updates.

### Copper Coprocessor
The Copper executes a program from its 1024-word (2x512) dual-banked RAM, synchronized to the beam.
```c
vdp_copper_enable(true);
vdp_copper_upload_and_swap(my_copper_prog, prog_len);
```

### DMA and Blitter
High-speed memory operations for clearing or copying blocks of data.
```c
vdp_mode0_dma_config(&dma_cfg);   // Fast fill or copy
vdp_mode0_blit_config(&blit_cfg); // Rectangular or line operations
```

### HDMA and Raster Triggers
Automation for per-line register updates and precise raster-line interrupts.
```c
vdp_mode0_set_hdma_ctrl(true, 0x0F, false); // Enable HDMA for 4 channels
vdp_mode0_set_raster_trigger(0, &trigger_cfg); // Configure Trigger 1
```

## 7. Timing and Synchronization

### Waiting for VBlank
To avoid "tearing" and ensure smooth animation, synchronize your logic with the vertical blanking interval.

```c
while (app_running) {
    // Wait for the start of the next VBlank
    if (vdp_wait_vblank(1000000)) { // 1s timeout
        update_game_logic();
        draw_frame();
    }
}
```

### Sticky Status Bits
Hardware events set "sticky" bits that stay set until explicitly cleared by the host.

```c
// Wait for a specific raster line trigger
if (vdp_wait_sticky(VDP_STICKY_RASTER_MATCH, 50000)) {
    // Line matched!
    vdp_clear_sticky(VDP_STICKY_RASTER_MATCH);
}
```

---

# Part II: Register Map and Bus Guide

This section defines the internal control surface. Developers building new host interfaces or copper programs should reference these addresses.

## 1. Internal Address Space (15-bit)

| Range | Purpose | Description |
|---|---|---|
| `0x0000..0x01DF` | Linestate Table | 480 words: per-line scroll and enable. |
| `0x0300..0x031F` | Globals | `LAYER_ENABLE`, `VDP_CTRL`, `MODE_SELECT`. |
| `0x0320..0x032F` | Status | `STATUS_STICKY` (W1C), `STATUS_ENABLE` (IRQ mask). |
| `0x0330..0x033F` | Windows | Config for WIN1, WIN2, BORDER, and Color Math. |
| `0x0340..0x034F` | Affine BG | Transform matrix (A, B, C, D) and pivot (X, Y). |
| `0x0350..0x035F` | DirectColor | RGB565 Bitmap and Attribute base/stride. |
| `0x0360..0x037F` | Raster | Config for 3 independent hardware triggers. |
| `0x0380..0x03DF` | HDMA | HDMA channel pointers and control. |
| `0x0400..0x05FF` | Copper RAM | 1024-word dual-banked program space. |
| `0x0A00..0x0AFF` | V-Scroll | Vertical scroll table (128 entries). |
| `0x0B00..0x0B4F` | DMA | DMA engine control and 64-word staging buffer. |
| `0x0C00..0x0D0F` | Blitter | Blitter control and 512-word source/store RAM. |
| `0x0F00..0x15FF` | Adapters | Shadow register pages for Spectrum, NES, SMS, etc. |

## 2. Core Registers Reference

### `LAYER_ENABLE` (0x0300)
- `bit 0`: Layer 0 Enable
- `bit 1`: Layer 1 Enable
- `bit 2`: Sprite Enable
- `bit 3`: Layer 2 Enable (Optional)
- `bit 4`: Layer 3 Enable (Optional)

### `STATUS_STICKY` (0x0320)
*Write-1-to-Clear (W1C)*
- `bit 0`: `RASTER_MATCH` — Fires at raster trigger line
- `bit 1`: `SPRITE_OVERFLOW` — Set if scanline sprite limit exceeded
- `bit 2`: `QSPI_READY` — Pulse on accepted command
- `bit 3`: `QSPI_ERROR` — Set if last QSPI transaction failed
- `bit 4`: `SPRITE_0_HIT` — Sprite slot 0 collision with BG
- `bit 5`: `SPRITE_BG_HIT` — Any sprite collision with BG
- `bit 8`: `DMA_DONE` — DMA transfer complete
- `bit 9`: `BLIT_DONE` — Blitter block transfer complete
- `bit 11`: `MODE_SELECT_CHANGED` — Mode selection committed at V=0

## 3. Copper Coprocessor Instructions

The Copper executes mid-frame from its dual-banked RAM. Instructions are 16-bit.

| Binary Prefix | Opcode | Description |
|---|---|---|
| `000` | `WAIT` | Block until raster matches target Y. |
| `001` | `WAIT_XY` | Block until raster matches target X and Y (2 words). |
| `01` | `WRITE` | Single 16-bit write to a VDP register. |
| `10` | `WRITE_SEQ` | Burst write N+1 words from Copper RAM to a register. |
| `110` | `JUMP` | Branch to a new program address. |
| `111` | `SKIP` | Conditionally skip the next instruction. |

## 4. Host Interface Protocol (QSPI)

The `spinalhdlVDP` uses a **Direct Register Bus** model via a 6-byte header QSPI contract.

1. **Direct Access**: QSPI writes pulse directly into the internal 15-bit address space. There are no host-side "address" or "data" shadow registers.
2. **Helpers**: Use `vdp_reg_write()` for single registers and `vdp_reg_write_burst()` for contiguous blocks.
3. **Pacing**: SDRAM uploads (`vdp_sdram_write`) should be paced to vblank using `vdp_upload_asset()` to prevent visible artifacts.
4. **Error Discipline**: Always verify `VDP_STICKY_QSPI_ERROR` is clear or check `vdp_last_error()` after critical sequences.

---
*End of Guide.*
