# VDP Programming Guide

**Version:** 1.3 (Draft)  
**Date:** 2026-05-23  
**Target Platform:** Tang Nano 20K (Mode0)  
**Host Libraries:** `libvdp` (C/C++)

This guide provides a practical, example-driven introduction to programming the `spinalhdlVDP`. It is divided into two parts: a high-level API guide for application developers using `libvdp`, and a low-level register map reference for system-level integration.

## Documentation Template
When adding new functions or features to this guide, use the following structure to maintain consistency:

```markdown
### `function_name`
**Description**: [Deep architectural explanation of how the function interacts with the hardware, including timing constraints, register shadowing, or memory bus behavior.]

**Real World Use**: [A concrete scenario explaining why and when a developer would call this function in a real application or game.]

**Example**:
\```c
// Implementation snippet
function_name(args);
\```
```

---

# Part I: libvdp API Guide

The `libvdp` library is the authoritative host-side coordination layer. It handles QSPI transport, timing synchronization, and provides high-level helpers for the Mode0 display engine.

## 1. Initialization and Basic I/O

### `vdp_qspi_init`
**Description**: Initializes the host microcontroller's QSPI hardware. On the **Pico 2**, it configures the PIO (Programmable I/O) state machines and state; on **ESP32/ESP8266**, it initializes the GPIO pins for a custom high-speed nibble-wide protocol. This must be the first VDP-related function called in your `setup()` or `main()`.

**Real World Use**: Use this at the very top of your program to "unlock" communication with the FPGA.

```c
#include "vdp_qspi.h"

void setup() {
    vdp_qspi_init(); // Establish the 2MHz QSPI link
}
```

### `vdp_read_status`
**Description**: Performs a synchronous read from the VDP status registers. It takes a `selector` (0-255) to choose which internal data word to return. 
- `sel 0`: Magic Value (`0x51560002`).
- `sel 5`: `STATUS_STICKY` bits (Raster Match, DMA Done, etc.).

**Real World Use**: Use this to check if the FPGA is "alive" before starting a complex graphics sequence.

```c
void check_vdp_status() {
    uint32_t magic = vdp_read_status(0);
    if (magic != 0x51560002) {
        // Serial.println("Error: VDP not found or wrong bitstream!");
        while(1); // Halt
    }
}
```

### `vdp_reg_write`
**Description**: Encapsulates a 6-byte QSPI command to write a single 16-bit value into the VDP register bus. It automatically handles the 15-bit address framing and little-endian data ordering.

**Real World Use**: Use for one-off configuration changes, such as enabling a specific display layer.

```c
#include "vdp_mode0.h"

void hide_sprites() {
    // Write 0x0001 to LAYER_ENABLE (0x0300) to keep L0 visible but hide L1 and sprites.
    vdp_reg_write(VDP_MODE0_REG_LAYER_ENABLE, 0x0001); 
}
```

### `vdp_reg_write_burst`
**Description**: The most efficient way to update multiple contiguous registers. It sends a single QSPI header followed by a stream of data words. The VDP's internal address counter automatically increments after each word.

**Real World Use**: Use this to setup a "Window" or a "Color Math" block in one high-speed transaction.

```c
void setup_ui_window() {
    // We want to set WIN1 X0, X1, Y0, Y1 (0x0330..0x0333)
    uint16_t bounds[] = { 40, 600, 30, 450 };
    vdp_reg_write_burst(VDP_MODE0_REG_WIN1_X0, bounds, 4);
}
```

---

## 2. Display Setup (Layers and Modes)

### `vdp_mode0_set_mode_select`
**Description**: Selects the runtime "Adapter Mode" for the VDP. This function updates the `0x0313` register, which triggers a structural reconfiguration of the pixel pipeline at the next VSync. Valid IDs include `VDP_MODE_ID_SPECTRUM`, `VDP_MODE_ID_NES`, etc.

**Real World Use**: Use this when your application transitions from a custom splash screen to a specific console emulation or legacy graphics mode.

```c
void start_nes_game() {
    // Transition the VDP into NES-compatible background and sprite logic.
    vdp_mode0_set_mode_select(VDP_MODE_ID_NES);
}
```

### `vdp_mode0_set_layer_enable`
**Description**: A high-level helper for the `LAYER_ENABLE` register. It uses a bitmask where:
- `bit 0`: Layer 0 (Bottom)
- `bit 1`: Layer 1 (Top)
- `bit 2`: Sprites

**Real World Use**: Use this to toggle UI overlays or to create "fading" effects by disabling layers during a transition.

```c
void toggle_ui(bool show) {
    if (show) {
        vdp_mode0_set_layer_enable(0x07); // Enable L0, L1, and Sprites
    } else {
        vdp_mode0_set_layer_enable(0x05); // Disable L1 (UI layer), keep L0 and Sprites
    }
}
```

---

## 3. Asset Management (SDRAM)

### `vdp_upload_asset`
**Description**: The VDP shares external SDRAM with the display engine. Writing to SDRAM while the screen is drawing can cause "snow" or glitches. This helper chunks your data and only performs writes during the ~1.4ms **Vertical Blanking (VBlank)** window when the memory bus is idle.

**Real World Use**: Use this for loading new character textures or level tiles *while the game is running* to ensure the player doesn't see any flickering.

```c
#include "vdp_upload.h"

void load_player_tiles(const uint16_t *gfx, uint16_t words) {
    // Upload tiles to SDRAM 0x8000. Helper will wait for VBlank automatically.
    vdp_upload_asset(0x8000, gfx, words, NULL);
}
```

---

## 4. Palette and Color

### `vdp_mode0_palette_write_rgb888`
**Description**: Mode0 supports 256 colors chosen from a 16.7-million color space. This function takes an 8-bit index and three 8-bit R/G/B components, maps them to the internal palette format, and writes them to the FPGA's Palette RAM.

**Real World Use**: Use this for dynamic color effects, like changing the world's lighting from day to night.

```c
void set_sunset_lighting() {
    // Change palette index 0 (backdrop) to a deep orange.
    // NOTE: See Section 8 for bank-fallthrough behavior.
    vdp_mode0_palette_write_rgb888(0, 255, 128, 0);
}
```

---

## 5. Sprites

### `vdp_mode0_set_sprite`
**Description**: Directly updates a single **Sprite Descriptor**. This control word determines the sprite's X/Y coordinate, its pattern index in memory, flipping flags, and palette bank selection. The VDP `main` baseline supports 32 of these descriptors.

**Real World Use**: Call this every frame to move your player or enemy objects across the screen.

```c
void update_player_pos(int x, int y) {
    vdp_mode0_sprite_cfg_t player = {
        .x = x, .y = y,
        .pat_idx = 0,    // Use pattern 0
        .pal_bank = 0,
        .enabled = true,
        .bpp_sel = 2     // 4bpp
    };
    vdp_mode0_set_sprite(0, &player); // Descriptor 0 is the player
}
```

### `vdp_sprite_upload`
**Description**: A powerful "macro" helper. It can simultaneously upload new pixel data to the Sprite Pattern RAM, new colors to the Palette RAM, and update the Sprite Descriptor in one operation. If any pointer is `NULL`, that part of the update is skipped.

**Real World Use**: Use this for "one-shot" sprite initialization, such as spawning a new projectile.

```c
void fire_bullet(int x, int y, const uint16_t *bullet_gfx) {
    vdp_mode0_sprite_cfg_t bullet_cfg = { .x = x, .y = y, .enabled = true };
    // Update slot 10 with new pixels and new position
    vdp_sprite_upload(10, bullet_gfx, 0, 32, NULL, 0, 0, &bullet_cfg);
}
```

---

## 6. Automation Engines

### `vdp_copper_upload_and_swap`
**Description**: The **Copper** is a dedicated "beam coprocessor" that can change VDP registers at specific raster lines without host CPU intervention. This helper writes your Copper program into the inactive RAM bank and signals the hardware to swap to the new program at the next VBlank.

**Real World Use**: Use the Copper to create "Split-Screen" effects where the top half of the screen has a different scroll position or background color than the bottom half.

```c
#include "vdp_copper.h"

void create_water_reflection() {
    uint16_t water_fx[] = {
        vdp_copper_wait(160),           // At scanline 160 (waterline)...
        vdp_copper_write_op(0x0347), 4, // Change Border to 'water' color
        vdp_copper_jump(0)              // Loop
    };
    vdp_copper_upload_and_swap(water_fx, 3);
}
```

### `vdp_mode0_dma_config`
**Description**: Triggers the FPGA's **Direct Memory Access** engine. It can rapidly fill a block of VDP RAM with a constant value or copy data from a host-supplied staging buffer. This is significantly faster than using `vdp_reg_write` in a loop.

**Real World Use**: Use this to instantly clear the entire Tile Map or Palette RAM to zeroes.

```c
void clear_tile_map() {
    vdp_mode0_dma_cfg_t clear_cfg = {
        .dst_addr = 0x0000,
        .len_m1 = 1199,     // 1200 tiles
        .fill_val = 0x0000, // Blank tile
        .mode = 0           // FILL mode
    };
    vdp_mode0_dma_config(&clear_cfg);
}
```

---

## 7. Timing and Synchronization

### `vdp_wait_vblank`
**Description**: A blocking synchronization primitive. It polls the VDP's raster status and returns `true` only when the vertical blanking period begins. This ensures your CPU doesn't try to update graphics while the VDP is actively scanning the screen.

**Real World Use**: This is the "Heartbeat" of your game engine. Put it at the top of your main loop.

```c
void main_loop() {
    while (1) {
        // 1. Wait for safe update window
        if (vdp_wait_vblank(1000000)) {
            // 2. Perform ALL register/sprite writes here
            move_sprites();
            update_scroll();
            // 3. Game logic can happen while screen draws
            process_input();
        }
    }
}
```

### `vdp_mode0_set_raster_trigger`
**Description**: Configures a hardware comparison unit that watches the current `hCounter` and `vCounter`. When they match your target, a bit is set in the `STATUS_STICKY` register. 

**Real World Use**: Use this to time a CPU action to a precise line, such as triggering a "Wavy" screen effect precisely when the player's character is drawn.

```c
void setup_line_interrupt() {
    vdp_mode0_trigger_t irq = { .line = 100, .enable = true };
    vdp_mode0_set_raster_trigger(1, &irq); // Trigger 1 @ Line 100
}
```

---

## 8. Hardware Behavior Notes

### Disabled-Layer Backdrop
**Behavior**: When all layers and sprites are disabled via `LAYER_ENABLE` (0x0300 = 0), the VDP compositor falls through to a default "backdrop" color. 

**Important**: This backdrop is NOT guaranteed to be `palette[0]`. The compositor continues to use the current **Layer 0 Palette Bank** even when Layer 0 is disabled. At Power-On Reset (POR), if SDRAM has not been initialized, the Layer 0 bank is sourced from uninitialized SDRAM Attribute memory, which often defaults to **Bank 4** (Grayscale). 

**Recommended Action**: To ensure a consistent backdrop color (e.g., Red) when layers are off:
1. Initialize the Layer 0 Attribute memory in SDRAM to Bank 0.
2. OR, write your backdrop color to the first index of ALL 8 palette banks (`palette[0]`, `palette[16]`, `palette[32]`, etc.).

---

# Part II: Internal Register Reference

| Address | Name | Description |
|---|---|---|
| `0x0300` | `LAYER_ENABLE` | bit0:L0, bit1:L1, bit2:Sprite, bit3:L2, bit4:L3 |
| `0x0310` | `VDP_CTRL` | bit0:Copper Enable, bit1:Copper Swap Request |
| `0x0320` | `STATUS_STICKY` | bit0:Raster Match, bit8:DMA Done, bit9:Blit Done |
| `0x0330` | `WIN1_X0` | Window 1 Left Boundary |
| `0x0347` | `BORDER_CTRL` | bit0:Enable, bits[15:8]:Palette Index |
| `0x0350` | `BITMAP_CTRL` | *Deprecated* (no-op since `8b61a2e`) |
| `0x0351..0x0356` | `BITMAP_BASE / STRIDE` | *Deprecated* (no-op since `8b61a2e`) |
| `0x0360` | `TRIGGER1_LINE` | Target scanline for Raster Trigger 1 |
| `0x0B00` | `DMA_DST` | Destination address for DMA operation |
| `0x0C00` | `BLIT_CTRL` | bit0:Go, bits[2:1]:Mode, bit3:Done Ack |

---
*End of Guide.*
