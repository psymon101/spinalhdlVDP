# VDP Programming Guide

**Version:** 1.5 (Draft)  
**Date:** 2026-06-20  
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

The `libvdp` library is the authoritative host-side coordination layer. It handles the low-level hardware transport, timing synchronization, and high-level helpers for the Mode0 display engine. The current Tang Nano 20K host path is **QSPI/ESP32-P4**; i80/ESP32-S3 and legacy SPI remain in the tree as historical references.

## 1. Initialization and Basic I/O

### `vdp_host_init`
**Description**: Initializes the host microcontroller's interface to the VDP. 
- **QSPI (Primary)**: Configures the 1-1-4 quad-SPI host interface used on the current Tang Nano 20K deployment (ESP32-P4). The active RTL front-end is `QspiSlave` → `QspiDecoder` → `QspiSdramBridge`.
- **i80 (Retired)**: The 8-bit parallel ESP32-S3 interface is no longer the canonical path. It remains supported as a historical reference.
- **Legacy SPI**: Retained for Pico 2 and earlier ESP32/ESP8266 bench setups through aliases such as `vdp_qspi_init()`.

This must be the first VDP-related function called in your `setup()` or `main()`.

**Real World Use**: Use this at the very top of your program to "unlock" communication with the FPGA.

```c
#include "vdp_host.h"

void setup() {
    vdp_host_init(); // Establish the current host-to-FPGA link
}
```

### `vdp_read_status`
**Description**: Performs a synchronous read from the VDP status registers. It takes a `selector` (0-255) to choose which internal data word to return.

**Important**: On the current QSPI interface, the `READ_STATUS` opcode (`0x04`) **is implemented** in `QspiDecoder` and returns live transport/SDRAM status via `vdp_read_status()`. On retired i80 builds the opcode was not implemented; historical i80 code should poll status through normal register reads or write-1-to-clear operations as described in the register spec.

**Real World Use**: On QSPI builds, use this to check if the FPGA is "alive" before starting a complex graphics sequence.

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
**Description**: Issues a single 16-bit write into the VDP register bus. It automatically handles the 15-bit address framing and little-endian data ordering required by the active host interface.

**Real World Use**: Use for one-off configuration changes, such as enabling a specific display layer.

```c
#include "vdp_mode0.h"

void hide_sprites() {
    // Write 0x0001 to LAYER_ENABLE (0x0300) to keep L0 visible but hide L1 and sprites.
    vdp_reg_write(VDP_MODE0_REG_LAYER_ENABLE, 0x0001); 
}
```

### `vdp_reg_read`
**Description**: Issues a single 16-bit read from the VDP register bus.

> [!WARNING]
> On the current i80 parallel interface, `vdp_reg_read()` is **unreliable** for verifying hardware state. The i80 read path returns either the **last value written by the host** (loopback) or a bus-idle pattern (`0x7F7F`), not the live register-file contents. Do not use readback to confirm that `BITMAP_CTRL`, `LAYER_ENABLE`, or other configuration registers have actually latched. Verify behavior visually or through a dedicated status/capture test instead.
>
> This limitation is tracked as `I80-STATUS-DECODE-152` and will be resolved when the i80 FSM adds a real readback/status-decode path.

**Real World Use**: Avoid on i80. On legacy QSPI builds the read path may return live values, but portable code should treat register writes as fire-and-forget and verify by observation.

```c
void do_not_do_this_on_i80() {
    // This readback is NOT trustworthy on the current i80 bitstream.
    uint16_t ctrl = vdp_reg_read(VDP_MODE0_REG_BITMAP_CTRL);
    if ((ctrl & 0x0001) == 0) {
        // May be loopback of the write you just issued, or 0x7F7F.
    }
}
```

### `vdp_reg_write_burst`
**Description**: Writes a contiguous block of registers. On the legacy QSPI backend, this sends a single command header followed by a stream of data words with an auto-incrementing address counter. On the canonical i80 backend, the helper issues a separate `opcode+addr+data` transaction for each word; the contiguous addresses are generated in firmware, not by an internal VDP counter.

**Real World Use**: Use this to setup a "Window" or a "Color Math" block in one high-level call. The i80 path is still faster than individual `vdp_reg_write()` calls because it avoids per-call overhead, but it does not use a hardware auto-increment protocol.

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
**Description**: Writes the `MODE_SELECT` register (`0x0313`). The 16-bit value is split as `[3:0]` = mode select and `[15:8]` = mode flags. A value of `0x0000` selects native Mode0. Non-zero mode values are reserved for future runtime adapter selection and are not yet defined in the current implementation.

**Real World Use**: Use this to select native Mode0 explicitly or to reserve a future adapter mode. Most applications write `0x0000`.

```c
void start_native_mode0() {
    // Select native Mode0 (mode=0, flags=0).
    vdp_mode0_set_mode_select(0x0000u);
}
```

### `vdp_mode0_set_layer_enable`
**Description**: A high-level helper for the global `LAYER_ENABLE` register. It uses a bitmask where:
- `bit 0`: Layer 0 (Bottom)
- `bit 1`: Layer 1 (Top)
- `bit 2`: Sprites

> [!IMPORTANT]
> Setting a global `LAYER_ENABLE` bit is **necessary but not sufficient**. Each output line also has a per-line **linestate** record that gates the layer. A layer is visible on a line only when **both** the global `LAYER_ENABLE` bit and the line's linestate enable bit are 1. For a simple full-screen L0 bitmap you must write `0x0800` to every active line's linestate entry (addresses `0x0000..0x01DF`).
>
> See the RGB565 full-frame example in §10 for the canonical linestate setup.

**Real World Use**: Use this to toggle UI overlays or to create "fading" effects by disabling layers during a transition. Remember that any line whose linestate enable is 0 will stay blank regardless of this register.

```c
void toggle_ui(bool show) {
    if (show) {
        vdp_mode0_set_layer_enable(0x07); // Enable L0, L1, and Sprites globally
        // Per-line linestate must also be enabled (e.g. via vdp_mode0_write_linestate)
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
    // NOTE: See Section 8 for BACKDROP_INDEX behavior.
    vdp_mode0_palette_write_rgb888(0, 255, 128, 0);
}
```

### Raw palette register access

If you are not using the helper, palette entries are written through two registers. Each entry is **24-bit RGB888**; border and sprite indices reference the same palette RAM directly.

| Register | Address | Purpose |
|---|---|---|
| `PALETTE_PTR` | `0x0601` | Sets the half-pointer for the next `PALETTE_DATA` write. |
| `PALETTE_DATA` | `0x0600` | Writes one 16-bit half of an entry; auto-increments the pointer. |

**Pointer units:** The pointer counts **half-entries**. A complete 24-bit color needs two 16-bit writes:
- `ptr = entry_index * 2` selects the low half of `entry_index`.
- `ptr = entry_index * 2 + 1` selects the high half.

**Write sequence for one RGB888 entry:**

```c
void raw_palette_write_rgb888(uint8_t entry, uint8_t r, uint8_t g, uint8_t b) {
    vdp_reg_write(0x0601, (uint16_t)(entry * 2u));     // PALETTE_PTR -> low half
    vdp_reg_write(0x0600, ((uint16_t)g << 8) | b);     // low half: G:B
    vdp_reg_write(0x0600, (uint16_t)r);                // high half: R (commits entry)
}
```

**Writing RGB565 source colors:** The palette stores RGB888, so convert RGB565 to RGB888 first. The common conversion is bit-replication:

```c
uint16_t rgb565 = 0x07E0;                       // green in RGB565
uint8_t r5 = (rgb565 >> 11) & 0x1F;
uint8_t g6 = (rgb565 >>  5) & 0x3F;
uint8_t b5 =  rgb565        & 0x1F;
uint8_t r = (r5 << 3) | (r5 >> 2);              // 5 -> 8 bits
uint8_t g = (g6 << 2) | (g6 >> 4);              // 6 -> 8 bits
uint8_t b = (b5 << 3) | (b5 >> 2);              // 5 -> 8 bits
raw_palette_write_rgb888(1, r, g, b);
```

> [!IMPORTANT]
> `BORDER_CTRL` bits `[12:8]` select the border palette entry directly: border color is exactly `palette[N]`. Sprites are different — a sprite's final palette entry is `(pal_bank << 4) | pixel_nibble` for 4bpp sprites, where `pixel_nibble` comes from the sprite pattern data. Pixel value `0` is transparent. Reserve sprite palette entries when using copper/raster palette animation, or use a non-overlapping `pal_bank`.

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
        .pal_bank = 0,   // Final palette entry = (0 << 4) | pixel_nibble
        .enabled = true,
        .bpp_sel = 0     // 0 = 4bpp, 1 = 2bpp, 2 = 1bpp
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

## 6. Copper (Beam Coprocessor)

The **Copper** is a dedicated raster coprocessor that writes VDP registers at exact scanline positions without host CPU intervention. It is useful for split-screen effects, per-scanline palette swaps, raster bars, and mid-frame register changes.

### Program memory

- **Address range:** `0x0400..0x05FF` (512 words, 1 KiB).
- **Double-buffered:** two 512-word banks. One bank is **active** (executed by the copper), the other is **inactive** (written by the host).
- **Maximum program size:** 512 words per bank. For larger programs use `JUMP` loops.

### Instruction helpers

Copper opcodes are 16-bit words. The helpers below encode them for you.

| Helper | Purpose |
|---|---|
| `vdp_copper_wait(y)` | Stall until `vCounter == y && hCounter == 0`. Match window is one cycle per frame; miss it and you wait a full frame. |
| `vdp_copper_wait_xy(x)` | Two-word pixel-precise `WAIT(X,Y)` header; append `y` as the next word. |
| `vdp_copper_write_op(addr)` | Single 16-bit `WRITE` header. The **data word must follow** immediately in the program stream. |
| `vdp_copper_write_seq_hdr(addr, n-1)` | Burst `WRITE_SEQ` header for `n` data words (`1..8`); append the data words next. |
| `vdp_copper_jump(pc)` | Unconditional jump to program word `pc`. |
| `vdp_copper_skip_op(cond, offset)` | Conditional skip (advanced). |

> [!WARNING]
> `vdp_copper_write_op(addr)` returns only the **header** word. You must place the data word after it in the array. This is the most common mistake when hand-assembling copper programs.

### Two-phase operation

The copper has two distinct programming phases. Using the wrong helper for the phase corrupts the active program.

#### Phase 1 — initial program (copper disabled)

When the copper is disabled, writes to `0x0400..0x05FF` land in the **active** bank. The canonical first-time sequence is:

1. Make sure the copper is disabled (`VDP_CTRL = 0x0000`).
2. Upload the program with `vdp_copper_upload(prog, nwords)`.
3. Enable the copper with `vdp_copper_enable(true)`.
4. Wait at least one full frame for the enable to commit and the first raster match to occur.

```c
#include "vdp_copper.h"

void copper_first_load(void) {
    uint16_t prog[] = {
        vdp_copper_wait(100),               // Wait for scanline 100
        vdp_copper_write_op(0x0347),        // WRITE header: BORDER_CTRL
        vdp_mode0_border_ctrl(true, 2),     // Data word: enable border, palette entry 2
        vdp_copper_jump(0)                  // Loop forever
    };

    vdp_copper_enable(false);               // ensure disabled
    vdp_copper_upload(prog, 4);             // lands in active bank
    vdp_copper_enable(true);                // PC resets to 0, execution starts
    vdp_wait_vblank(100000);                // wait ≥1 frame for visible effect
}
```

#### Phase 2 — live update (copper enabled)

Once the copper is running, writes to `0x0400..0x05FF` are automatically routed to the **inactive** bank. To switch programs without tearing:

1. Prepare the new program.
2. Call `vdp_copper_upload_and_swap(prog, nwords)` (or `vdp_copper_upload` followed by `vdp_copper_swap_request()`).
3. Wait at least one full frame for the swap to commit at the next `vSyncStart`.

> [!IMPORTANT]
> `vdp_copper_upload_and_swap()` **requires the copper to already be enabled**. If the copper is disabled, writes land in the active bank and the swap request is ignored.

```c
void copper_live_update(void) {
    uint16_t prog2[] = {
        vdp_copper_wait(200),
        vdp_copper_write_op(0x0347),
        vdp_mode0_border_ctrl(true, 3),
        vdp_copper_jump(0)
    };

    // Precondition: copper is already enabled and running.
    vdp_copper_upload_and_swap(prog2, 4);   // write inactive bank + request swap
    vdp_wait_vblank(100000);                // swap commits at vSyncStart
}
```

### Timing and commit boundaries

| Action | Commit point | Host guidance |
|---|---|---|
| `vdp_copper_enable(true)` | `hCounter == 0` (line boundary) | Wait ≥1 frame before assuming the copper is running. |
| `vdp_copper_swap_request()` | `vSyncStart && hCounter == 0` | Wait ≥1 full frame for the swap to take effect. |
| Copper `WRITE` instructions | Drained from copper FIFO at `hCounter == 0`, one per line | A WAIT on line `y` followed by a WRITE means the WRITE's effect is visible no earlier than line `y+1`. |

> [!TIP]
> Do not rely on reading back `VDP_CTRL` to confirm enable/swap. i80 readback returns the **last value written by the host** (loopback), not the live committed state. Verify visually or with a raster-trigger/status read.

### Minimal working example: border color split

This example changes the border color at scanline 160. It assumes the border window is disabled (`BORDER_X0..Y1` = 0) so the border fills the whole screen, and palette entries 1 and 2 have been loaded.

```c
#include "vdp_copper.h"
#include "vdp_mode0.h"

static const uint32_t palette[] = {
    0x00000000, // 0: black
    0x00FF0000, // 1: red
    0x0000FF00, // 2: green
};

void copper_border_split_demo(void) {
    // 1. Seed the border so something is visible before the copper runs.
    //    Leave BORDER_X0..Y1 at 0 so the border fills the whole screen.
    vdp_mode0_set_border_ctrl(vdp_mode0_border_ctrl(true, 1));

    for (uint8_t i = 0; i < 3; ++i) {
        vdp_mode0_palette_write_rgb888(i,
            (palette[i] >> 16) & 0xFF,
            (palette[i] >> 8) & 0xFF,
            palette[i] & 0xFF);
    }

    // 2. First copper program: red above line 160, green below.
    uint16_t prog[] = {
        vdp_copper_wait(160),
        vdp_copper_write_op(0x0347),        // BORDER_CTRL
        vdp_mode0_border_ctrl(true, 2),     // green
        vdp_copper_jump(0)
    };

    vdp_copper_enable(false);
    vdp_copper_upload(prog, 4);
    vdp_copper_enable(true);
}
```

> [!WARNING]
> The border window (`BORDER_X0..Y1`) defines the **inner/active screen region**. Pixels **outside** that rectangle show the border color; pixels inside show layers/backdrop. A full-screen window such as `{0,0,640,480}` leaves no outside area, so the border becomes invisible even when `BORDER_CTRL` is enabled. For a full-screen border effect, keep the window registers at `0`.

### Raw i80 fallback (debug)

If you suspect a firmware-helper bug, you can bypass the helpers and drive the i80 bus directly. The byte sequence for a single register write is:

```text
DC=0: opcode 0x00          // REG_WRITE
DC=0: addr_lo              // low byte of 16-bit register address
DC=0: addr_hi              // high byte of address
DC=1: data_lo              // low byte of data
DC=1: data_hi              // high byte of data
```

Copper-specific raw addresses:

| Register | Address | Typical data |
|---|---|---|
| `VDP_CTRL` | `0x0310` | `0x0001` to enable copper; `0x0003` to enable + request swap. |
| Copper program RAM | `0x0400 + i` | Program word `i` (0..511). |

A minimal copper enable from scratch therefore emits: `0x00 0x10 0x03 0x01 0x00`.

Use this raw path only for debugging; normal code should use the helpers above so the hardware can evolve without host-side changes.

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

**Implementation Note**: This backdrop is deterministic and is controlled by the `BACKDROP_INDEX` register (`0x0348`). It defaults to 0 (black) at power-on.

**Recommended Action**: To change the background color when all layers are off, write a 7-bit palette index (0..127) to `BACKDROP_INDEX`.

```c
// Set backdrop to entry 10 (e.g. Blue)
vdp_mode0_set_backdrop_index(10);
```

### Scaling and Logical Resolution
**Behavior**: The VDP includes an integer pixel-repetition scaler that can repeat logical pixels (1x to 6x) to fill the 640x480 physical panel. This allows for lower logical resolutions (like 320x240 or 256x192) while maintaining a high-quality HDMI signal.

**Implementation Note**: Use the `SCALE_CTRL` register (`0x0349`) to set the X and Y repeat factors. Setting `autoCenter` (bit 7) automatically centers the logical canvas on the screen using the `LOGIC_WIDTH` and `LOGIC_HEIGHT` registers.

**Recommended Action**: Use the `libvdp` helper `vdp_mode0_set_logical_resolution(w, h)` to configure scaling. It automatically computes the best integer scale and centers the image.

```c
// Configure a 320x240 logical canvas with auto-centering
vdp_mode0_set_logical_resolution(320, 240);
```

### Per-Layer Transparency and Planar Clip Width

Each tile/planar layer can have its own transparent color index. A pixel whose palette entry equals the layer's `Lx_TRANS_KEY` register is treated as fully transparent, revealing the layer behind it (or the backdrop).

| Register | Address | Purpose |
|---|---|---|
| `L0_TRANS_KEY` | `0x0314` | 4-bit transparent palette index for layer 0 |
| `L1_TRANS_KEY` | `0x0315` | 4-bit transparent palette index for layer 1 |
| `L2_TRANS_KEY` | `0x0316` | 4-bit transparent palette index for layer 2 |
| `L3_TRANS_KEY` | `0x0317` | 4-bit transparent palette index for layer 3 |

`PLANAR_WIDTH` (`0x0D4B`) sets the 10-bit planar clip width. The default `320` matches the existing 320-pixel planar window. Values larger than `320` wrap around the line.

```c
// Make palette entry 0 transparent on layer 0
vdp_mode0_set_trans_key(0, 0);

// Keep the default 320-pixel planar clip width
vdp_mode0_set_planar_width(320);
```

> [!NOTE]
> These defaults match the pre-register hardcoded behavior: index `0` is transparent and the planar clip width is `320` pixels. Writing non-default values requires a next-bitstream build that implements the registers.

---

### Host-Triggered Soft Reset

The host can return the VDP to a clean POR-equivalent state by writing `1` to bit 2 of `VDP_CTRL` (`0x0310`). The reset is equivalent to a POR and runs as a 4-stage chain:

1. **Host-writable BSRAM memories zeroed** — copper program RAM (both banks), HDMA data/table, palette, sprite pattern RAM, sprite external descriptors + affine matrices, linestate (prepare+commit), scroll tables, DMA staging buffer, blitter source RAM.
2. **SDRAM occupied-region zero-fill** — for each active layer source the engine zeroes `[base, base + stride·height)` using the last host-programmed geometry registers, **before** those registers are reset. SDRAM outside the configured regions is left untouched. **Refresh interleave is retained:** the fill FSM issues a lightweight auto-refresh roughly every 15 µs, keeping the clear within the 64 ms SDRAM retention window even for large framebuffers.
3. **Core register reset** — all host-writable config registers return to their SpinalHDL `init` values; pending/commit hits are cleared so no stale in-flight write lands post-reset. `STATUS_STICKY`, `STATUS_ENABLE` (IRQ mask), and the sprite-collision mask are also cleared so no stale flag or IRQ fires after reset.
4. **Done** — `VDP_CTRL[2]` is released synchronously at `hCounter == 0` to avoid any glitched pulse to the datapath. The controller guarantees the pipeline/counter regs re-settle within one frame.

A 1000 ms timeout is retained as a safety bound. After reset, re-initialize the display and reload any palette/sprite patterns you need.

```c
#include "vdp_mode0.h"

void vdp_soft_reset(void) {
    // Initiates the 4-stage reset and polls the live busy bit.
    vdp_mode0_soft_reset();

    // Helper returns only after SOFT_RESET_BUSY is clear.
    // Re-initialize display state here.
}
```

> [!WARNING]
> Do not poll `VDP_CTRL` inside an interrupt-critical section for longer than necessary. If the readback path is loopback-only, use a fixed delay or a status interrupt instead.
>
> `affineTexture`, immutable tile ROMs, transient per-line render buffers, and legacy demo sprite input ports are **not** affected by the reset.

---

## 9. Verification Guidelines
# Part II: Internal Register Reference

| Address | Name | Description |
|---|---|---|
| `0x0300` | `LAYER_ENABLE` | bit0:L0, bit1:L1, bit2:Sprite, bit3:L2, bit4:L3. **Global enable only** — each bit is ANDed with the per-line linestate enable bit (addresses `0x0000..0x01DF`). |
| `0x0310` | `VDP_CTRL` | bit0:Copper Enable, bit1:Copper Swap Request, bit2:Soft Reset Request |
| `0x0314` | `L0_TRANS_KEY` | 4-bit transparency palette index for layer 0 |
| `0x0315` | `L1_TRANS_KEY` | 4-bit transparency palette index for layer 1 |
| `0x0316` | `L2_TRANS_KEY` | 4-bit transparency palette index for layer 2 |
| `0x0317` | `L3_TRANS_KEY` | 4-bit transparency palette index for layer 3 |
| `0x0320` | `STATUS_STICKY` | bit0:Raster Match, bit2:HOST_READY, bit3:HOST_ERROR, bit8:DMA Done, bit9:Blit Done |
| `0x0D4B` | `PLANAR_WIDTH` | 10-bit planar clip width (default 320) |
| `0x0330` | `WIN1_X0` | Window 1 Left Boundary |
| `0x0347` | `BORDER_CTRL` | bit0:Enable, bits[12:8]:Palette Index |
| `0x0348` | `BACKDROP_INDEX` | 7-bit palette index for background fallthrough |
| `0x0349` | `SCALE_CTRL` | bit[2:0]:scaleX, bit[6:4]:scaleY, bit[7]:autoCenter |
| `0x034A` | `LOGIC_WIDTH` | 11-bit logical canvas width (1..640) |
| `0x034B` | `LOGIC_HEIGHT` | 11-bit logical canvas height (1..480) |
| `0x0350`          | `BITMAP_CTRL`          | bit[0]:enable, bits[2:1]:bpp, bits[6:3]:cellWidthLog2 |
| `0x0351..0x0356` | `BITMAP_BASE / STRIDE` | Base/stride offsets for bitmap/attribute fetch (Task 129) |
| `0x0357`          | `BITMAP_HEIGHT`        | Source bitmap height in rows (default 240) |
| `0x0360` | `TRIGGER1_LINE` | Target scanline for Raster Trigger 1 |
| `0x0B00` | `DMA_DST` | Destination address for DMA operation |
| `0x0C00` | `BLIT_CTRL` | bit0:Go, bits[2:1]:Mode, bit3:Done Ack |

---

## 10. RGB565 Full-Frame Bitmap Mode

Mode0 supports a direct-color RGB565 bitmap layer. In this mode the bitmap data is split into two SDRAM byte planes:
- **Low byte plane** — pointed to by `BITMAP_BASE` (`0x0351`/`0x0352`).
- **High byte plane** — pointed to by `ATTR_BASE` (`0x0353`/`0x0354`).

Each logical row is `BITMAP_STRIDE` bytes wide. For a 320×240 RGB565 image, the visible portion consumes 320 bytes per row; the canonical stride is **512 bytes**.

### Memory layout example

```text
Row 0:   BITMAP_BASE + 0            -> low bytes of pixels 0..319
         ATTR_BASE   + 0            -> high bytes of pixels 0..319
Row 1:   BITMAP_BASE + 512          -> low bytes of row 1
         ATTR_BASE   + 512          -> high bytes of row 1
...
Row 239: BITMAP_BASE + 239*512      -> low bytes of row 239
         ATTR_BASE   + 239*512      -> high bytes of row 239
```

### Required configuration

| Register | Recommended value | Why |
|---|---|---|
| `BITMAP_BASE_LO` / `BITMAP_BASE_HI` | `0x0000` / `0x0010` → base `0x100000` | Non-overlapping with attribute plane |
| `ATTR_BASE_LO` / `ATTR_BASE_HI` | `0x0000` / `0x0020` → base `0x200000` | Non-overlapping with bitmap plane |
| `BITMAP_STRIDE` / `ATTR_STRIDE` | `512` | 32-byte aligned; matches 320-byte visible width with padding |
| `BITMAP_HEIGHT` | `240` | Source bitmap height in rows |
| `BITMAP_CTRL` | `0x0005` | enable (`bit0=1`) + BPP=`0b10` (`bits[2:1]=2`) |
| `LAYER_ENABLE` | `0x0001` | Enable bitmap layer 0 |

> [!WARNING]
> **POR default base overlap:** The power-on reset defaults are `BITMAP_BASE=0x3000` and `ATTR_BASE=0x4000`. At a 512-byte stride these overlap after just 8 rows. A full 320×240 RGB565 image **must** use non-overlapping bases such as `0x100000` and `0x200000`.
>
> **32-byte alignment:** In RGB565 direct-color burst mode the hardware masks the low 5 bits of `BITMAP_BASE`, `ATTR_BASE`, `BITMAP_STRIDE`, and `ATTR_STRIDE`. All four values must be multiples of 32 bytes. The recommended bases and the default 512-byte stride are already aligned.
>
> **Deprecated bit:** `BITMAP_CTRL` bit 7 is deprecated and has no effect. Use `0x0005`, not `0x0085`.

### Linestate precondition

Enabling the global `LAYER_ENABLE` bit 0 is **not enough** to make the bitmap visible. The render pipeline also reads a per-line **linestate** record that gates layer 0 for that line.

- Linestate entries live at addresses `0x0000..0x01DF` (one 16-bit word per active display line; 480 lines for 480p output).
- Record format: `{l0en[11], l1en[10], l0scrollX[9:0]}`.
- For a simple full-screen L0 bitmap, write `0x0800` to all 480 entries: L0 enabled, L1 disabled, `layer0ScrollX = 0`.
- `effectiveL0Enable = linestate.layer0Enable && LAYER_ENABLE(0)`.

The canonical example initializes linestate like this:

```c
for (uint16_t line = 0; line < VDP_MODE0_LINESTATE_COUNT; ++line) {
    vdp_mode0_write_linestate(line, 0x0800u); // L0 on, L1 off, scrollX = 0
}
```

Without this step the screen will show only the backdrop color, even though `LAYER_ENABLE` is set.

### Uploading the image

For each row, pack the low bytes of two consecutive pixels into one 16-bit word, and the high bytes into another 16-bit word, then upload to the respective planes. The canonical example `firmware/esp32s3_rgb565_fullframe/esp32s3_rgb565_fullframe.ino` demonstrates the full sequence.

> [!NOTE]
> **Bitmap pipeline latency:** The bitmap/double-buffer line buffer is filled and drained by the same pixel clock. The current implementation aligns the fill and drain paths with a **zero-cycle write delay** (`BITMAP_PIPELINE_LATENCY = 0`): source pixel `k` is written to `dcLineBuf[k]` and displayed at the screen column corresponding to `k`. Earlier prototypes required a non-zero compensation delay; that was an artifact of co-simulator sampling and has been removed.

### Code example

```c
#include "vdp_host.h"
#include "vdp_mode0.h"

void setup_rgb565_fullscreen(void) {
    vdp_host_init();

    // Upload pattern to SDRAM first (omitted: row-by-row plane packing)
    // ...

    vdp_mode0_set_bitmap_base(0x100000u);
    vdp_mode0_set_attr_base(0x200000u);
    vdp_mode0_set_bitmap_stride(512u);
    vdp_mode0_set_attr_stride(512u);
    vdp_mode0_set_bitmap_height(240u);

    // enable + BPP=0b10 (RGB565 direct-color)
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, 0x0005u);

    // Per-line linestate precondition: L0 on, L1 off, scrollX = 0
    for (uint16_t line = 0; line < VDP_MODE0_LINESTATE_COUNT; ++line) {
        vdp_mode0_write_linestate(line, 0x0800u);
    }

    vdp_mode0_set_layer_enable(0x0001u);
}
```

## 11. Host Interface Notes

The canonical Tang Nano 20K host path is **QSPI/ESP32-P4**, with RTL front-end `QspiSlave` → `QspiDecoder` → `QspiSdramBridge`:
- Register writes use opcode `0x00`.
- SDRAM writes use opcode `0x01`.
- `READ_STATUS` reads use opcode `0x04` and return live transport/SDRAM status.

Use `firmware/libvdp/vdp_host.h` for transport-agnostic calls such as `vdp_reg_write()`, `vdp_reg_read()`, `vdp_sdram_write()`, and `vdp_read_status()`.

> [!WARNING]
> **`vdp_reg_read()` is not a reliable verification primitive on QSPI.** The readback path returns the **last-written value** for most addresses (loopback), not the live committed register-file contents. Code that reads back `BITMAP_CTRL`, `LAYER_ENABLE`, `BITMAP_BASE`, etc., to decide whether a configuration step succeeded is at risk of false positives. Treat register writes as fire-and-forget and verify by visual output or a dedicated status read.

### i80 (retired)

The historical ESP32-S3 i80 8-bit parallel bus used opcodes `0x00` (register write), `0x01` (register read, loopback), and `0x02` (SDRAM block write). `vdp_i80.h` is preserved as a historical reference. `READ_STATUS` (opcode `0x04`) was not implemented on the i80 path; see `I80-STATUS-DECODE-152` in the task backlog.

> [!WARNING]
> **Full-Screen RGB565 Bitmap Limitation (legacy warning):** The power-on reset (POR) default bases for the bitmap layer are `0x3000` (Bitmap) and `0x4000` (Attribute). These defaults overlap after 8 rows when rendering direct-color (RGB565) mode at a 512-byte stride. For full-screen RGB565 bitmaps, you **must** configure non-overlapping bases (e.g., `0x100000` and `0x200000`) using registers `0x0351..0x0354`. The defaults are retained for backward compatibility with legacy indexed 1/2bpp mode demos.

## 12. Bitmap Reference Modes

The current Tang Nano 20K reference image uses a **2bpp indexed-color bitmap** uploaded over the word-drain QSPI transport. The earlier **HAM6** mode has been **shelved** from the active critical path and is documented below for historical reference only.

### 12.1 2bpp Indexed Bitmap Mode (active reference)

Mode0 supports an indexed bitmap layer with configurable bits-per-pixel. The active lane uses **2bpp** (`bpp=0b01`), giving four palette-selectable colors — enough for a clear vertical-bar or checkerboard reference pattern while staying safely within SDRAM fetch bandwidth.

#### Memory layout

- **Bits per pixel:** 2 (`bpp=0b01`).
- **Pixels per byte:** 4 (packed MSB-first within each byte).
- **Logical size:** 320×240 source pixels, scaled 2× to 640×480 display pixels.
- **Stride:** 80 bytes per source row (320 pixels ÷ 4 pixels/byte).
- **Total image size:** 80 bytes/row × 240 rows = 19 200 bytes.
- **Upload packing:** Pack each row into little-endian 16-bit words (`word = byte[x] | (byte[x + 1] << 8)`) and upload to `BITMAP_BASE`.

#### Required configuration

| Register | Recommended value | Why |
|---|---|---|
| `BITMAP_BASE_LO` / `BITMAP_BASE_HI` | `0x0000` / `0x0010` → base `0x100000` | Aligned SDRAM base for the indexed byte plane |
| `BITMAP_STRIDE` | `80` | 320 source pixels at 2bpp = 80 bytes/row |
| `BITMAP_HEIGHT` | `240` | Source bitmap height in rows |
| `BITMAP_CTRL` | `0x0003` | enable (`bit0=1`) + BPP=`0b01` (`bits[2:1]=1`) |
| `LAYER_ENABLE` | `0x0001` | Enable bitmap layer 0 |

> [!NOTE]
> The 2bpp indexed mode uses only the `BITMAP_BASE` plane. `ATTR_BASE` is ignored for indexed fetch; set it to the same SDRAM row/bank as `BITMAP_BASE` (e.g., `0x100020`) so the unused attribute fetch does not introduce SDRAM bank thrashing.

#### Linestate precondition

As with RGB565 direct-color, enabling `LAYER_ENABLE` bit 0 is not enough. Write `0x0800` to every active line's linestate entry (`0x0000..0x01DF`) to enable L0 for the full screen.

#### Palette

Load the four display colors into palette entries `0..3` before enabling the layer. Palette entries are 24-bit RGB888, written through `PALETTE_PTR` (`0x0601`) and `PALETTE_DATA` (`0x0600`) as described in §4.

#### Code example

```c
#include "vdp_host.h"
#include "vdp_mode0.h"

static const uint32_t indexed2bpp_palette[4] = {
    0x00000000, // 0: black
    0x00FF0000, // 1: red
    0x0000FF00, // 2: green
    0x000000FF, // 3: blue
};

static const uint16_t indexed2bpp_image[80 * 240]; // packed row data, MSB-first

void setup_2bpp_indexed(void) {
    vdp_host_init();

    // 1. Load palette entries 0..3.
    for (uint8_t i = 0; i < 4; ++i) {
        vdp_mode0_palette_write_rgb888(i,
            (indexed2bpp_palette[i] >> 16) & 0xFF,
            (indexed2bpp_palette[i] >>  8) & 0xFF,
             indexed2bpp_palette[i]        & 0xFF);
    }

    // 2. Upload the packed 2bpp byte plane to SDRAM.
    vdp_sdram_write(0x100000u, indexed2bpp_image, 80u * 240u);

    // 3. Configure bitmap geometry.
    vdp_mode0_set_bitmap_base(0x100000u);
    vdp_mode0_set_attr_base(0x100020u);      // same row/bank, unused but safe
    vdp_mode0_set_bitmap_stride(80u);
    vdp_mode0_set_attr_stride(80u);          // unused but harmless
    vdp_mode0_set_bitmap_height(240u);

    // 4. Enable 2bpp indexed (BPP = 0b01).
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, 0x0003u);

    // 5. Enable L0 for every line.
    for (uint16_t line = 0; line < VDP_MODE0_LINESTATE_COUNT; ++line) {
        vdp_mode0_write_linestate(line, 0x0800u);
    }

    // 6. Enable the bitmap layer.
    vdp_mode0_set_layer_enable(0x0001u);
}
```

> [!IMPORTANT]
> The exact stride, logical resolution, and scaling factor for the reference pattern are subject to final RTL confirmation by BrightForge. Update this section once the bitstream proof is available.

---

### 12.2 HAM6 Bitmap Mode (shelved)

> [!WARNING]
> **HAM6 is shelved from the active critical path.** The owner decided on 2026-07-20 that HAM6 was too problematic to keep on the current lane (#14224). The `bpp=0b11` encoding is **reserved** for future HAM6 work. Do not use it on the current Tang Nano 20K deployment unless a new lane explicitly re-enables it.

Mode0 previously supported a **HAM6** (Hold-And-Modify 6-bit) bitmap layer. In this mode the source image is a single byte plane: each source pixel is a 6-bit code that either selects a base color from palette entries `0..15` or modifies one channel of the previous pixel's color.

#### HAM6 code format

| Bits | Field | Meaning |
|---|---|---|
| `[5:4]` | Control | `00` = SET from palette, `01` = modify blue, `10` = modify red, `11` = modify green |
| `[3:0]` | Data | For SET: palette index `0..15`. For modify: new 4-bit channel value. |
| `[7:6]` | — | Always zero. |

The first pixel of every scanline is decoded as a SET operation using palette entry `0` as the seed color, regardless of the control field.

#### Memory layout

- Source size: 320×240 logical pixels.
- Display expectation: 640×480 after 2× horizontal/vertical stretch.
- One byte per source pixel, packed as little-endian 16-bit words for `vdp_sdram_write()`.
- Only the **`BITMAP_BASE` byte plane** was used to decode HAM6 pixels. However, the RTL fetched both the bitmap and attribute planes unconditionally, so `ATTR_BASE` had to be configured to point to a memory location in the same SDRAM row/bank as `BITMAP_BASE` (e.g., `0x100020`).

#### Required configuration (historical)

| Register | Recommended value | Why |
|---|---|---|
| `BITMAP_BASE_LO` / `BITMAP_BASE_HI` | `0x0000` / `0x0010` → base `0x100000` | Aligned SDRAM base for the HAM byte plane |
| `ATTR_BASE_LO` / `ATTR_BASE_HI` | `0x0020` / `0x0010` → `0x100020` | Same SDRAM bank/row as BITMAPBase (to prevent thrashing) |
| `BITMAP_STRIDE` / `ATTR_STRIDE` | `320` | One byte per source pixel, 320 bytes/row |
| `BITMAP_HEIGHT` | `240` | Source bitmap height in rows |
| `BITMAP_CTRL` | `0x0007` | enable (`bit0=1`) + BPP=`0b11` (`bits[2:1]=3`) |
| `LAYER_ENABLE` | `0x0001` | Enable bitmap layer 0 |

---
*End of Guide.*
