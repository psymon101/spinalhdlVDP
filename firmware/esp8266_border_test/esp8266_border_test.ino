/*
 * Border path diagnostic — no copper, no layers.
 * Goal: verify BORDER_CTRL writes from host directly affect the screen.
 *
 * Pin map (NodeMCU 1.0):
 *   GPIO14 (D5) -> SCK
 *   GPIO13 (D7) -> MOSI
 *   GPIO12 (D6) -> MISO
 *   GPIO15 (D8) -> CS_N
 *
 * Boot sequence:
 *   1. QSPI init, 200 ms settle
 *   2. Disable all layers, bitmap, color math
 *   3. Upload 32-entry rainbow palette
 *   4. Set empty border rect (border everywhere)
 *   5. Cycle BORDER_CTRL through 4 solid colors with 2s delay
 *
 * Expected RTSP result:
 *   Screen cycles through colors every 2s.
 *   If screen stays colored blocks → border path broken in FPGA/bitstream
 *   If screen cycles colors → border path works, issue is copper execution
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>

static void set_border(uint8_t palette_idx)
{
    uint16_t ctrl = vdp_mode0_border_ctrl(true, palette_idx);
    vdp_mode0_set_border_ctrl(ctrl);
    Serial.printf("BORDER_CTRL = 0x%04X (idx=%u)\n", ctrl, palette_idx);
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== Border Path Diagnostic ===");

    vdp_qspi_init();
    delay(200);

    // Disable all rendering sources so only border shows
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);       // bitmap off
    vdp_mode0_set_color_math(0x0000u);        // passthrough

    // Upload rainbow palette (entries 0..31)
    for (uint8_t i = 0; i < 32; ++i) {
        uint16_t hue = i * 360 / 32;
        uint8_t r, g, b;
        uint8_t hi = hue / 60;
        uint8_t f = (((hue % 60) * 255) / 60);
        uint8_t p = 0, q = 255 - f, t = f;
        switch (hi % 6) {
            case 0: r = 255; g = t;   b = p;   break;
            case 1: r = q;   g = 255; b = p;   break;
            case 2: r = p;   g = 255; b = t;   break;
            case 3: r = p;   g = q;   b = 255; break;
            case 4: r = t;   g = p;   b = 255; break;
            case 5: r = 255; g = p;   b = q;   break;
        }
        vdp_mode0_palette_write_rgb888(i, r, g, b);
    }
    Serial.println("Palette uploaded (32 entries).");

    // Border everywhere: empty inner rect so borderActive=true everywhere
    vdp_mode0_rect_t rect = { 0, 1280, 0, 0 };
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));

    Serial.println("Border configured. Cycling colors every 2s...");
}

void loop(void)
{
    // idx 0 = black (palette entry 0 = RGB 0,0,0 from HSV hue=0)
    set_border(0);
    delay(2000);

    set_border(8);   // orange
    delay(2000);

    set_border(16);  // green/cyan
    delay(2000);

    set_border(24);  // blue/purple
    delay(2000);
}
