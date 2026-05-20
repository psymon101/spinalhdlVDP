# VDP Programming Guide

**Version:** 1.0 (Draft)  
**Date:** 2026-05-20  
**Target Platform:** Tang Nano 20K (Mode0)  
**Host Libraries:** `libvdp` (C/C++)

This guide provides a practical, example-driven introduction to programming the `spinalhdlVDP`. It is divided into two parts: a high-level API guide for application developers using `libvdp`, and a low-level register map reference for system-level integration.

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
Registers are 16-bit. Most writes land in a shadow register and are committed at the start of the next scanline (`hCounter == 0`) to prevent mid-frame tearing.

```c
// Write a single register
vdp_reg_write(0x0300, 0x0007); // Enable L0, L1, and Sprites

// Write a contiguous block (burst write)
uint16_t window_cfg[] = { 10, 310, 20, 220, 0x0001 };
vdp_reg_write_burst(0x0330, window_cfg, 5); // Setup WIN1 X/Y and Color Math
```

## 2. Display Setup (Layers and Modes)

The VDP supports multiple background layers and specific platform "adapter" modes.

```c
// Select a platform adapter (e.g., ZX Spectrum mode)
vdp_mode0_set_mode_select(VDP_MODE_ID_SPECTRUM, 0);

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

Mode0 uses an RGB888 (24-bit) palette. You can write individual entries or use platform-specific LUTs.

### Generic Palette Write
```c
// Set palette index 1 to full red
vdp_mode0_palette_write_rgb888(1, 0xFF0000);
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

The current substrate supports **8 total descriptors** and **8 visible sprites per scanline**.

### Manual Configuration
```c
vdp_mode0_sprite_cfg_t my_sprite = {
    .x = 100,
    .y = 50,
    .pattern_idx = 4,
    .palette_bank = 0,
    .priority = 0,
    .flags = 0
};
vdp_mode0_set_sprite(0, &my_sprite);
```

### All-in-One Upload
A convenience helper for updating pattern data, palette, and descriptor in one call.
```c
vdp_sprite_upload(0, 
    pattern_data, 0x2000, 64, // Pattern RAM: slot 0 uses 64 pixels at 0x2000
    palette_data, 16, 16,     // Palette: 16 colors starting at index 16
    &my_sprite                // Descriptor update
);
```

## 6. Timing and Synchronization

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
| `0x0350..0x035F` | Raw Fetch | Bitmap and Attribute base addresses and strides. |
| `0x0360..0x037F` | Raster | Config for 3 independent hardware triggers. |
| `0x0400..0x05FF` | Copper RAM | 512-word program space for the Copper coprocessor. |
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
- `bit 0`: `RASTER_MATCH`
- `bit 1`: `SPRITE_OVERFLOW`
- `bit 2`: `QSPI_READY`
- `bit 3`: `QSPI_ERROR`
- `bit 4`: `SPRITE_0_HIT`
- `bit 5`: `SPRITE_BG_HIT`
- `bit 8`: `DMA_DONE`
- `bit 9`: `BLIT_DONE`

## 3. Copper Coprocessor Instructions

The Copper executes mid-frame from its 512-word RAM. Instructions are 16-bit.

| Opcode | Name | Description |
|---|---|---|
| `00` | `WAIT` | Block until raster matches target X/Y. |
| `01` | `WRITE` | Immediate 8-bit write to a VDP register. |
| `10` | `WRITE_SEQ` | Burst write N+1 words from Copper RAM to a register. |
| `11` | `JUMP` | Branch to a new program address. |

## 4. Host Interface Protocol (QSPI)

The QSPI transport uses an **indirect register model** to decouple host speed from the pixel pipeline.

1. **Set Address**: Write the 15-bit VDP target address to host-side shadow register `0x00`.
2. **Write Data**: Write the 16-bit payload to host-side shadow register `0x02`.
3. **Auto-Increment**: The VDP address automatically increments by the value in register `0x04` after each data write.

This allow the host to stream palette or copper data with minimal overhead.

---
*End of Guide.*
