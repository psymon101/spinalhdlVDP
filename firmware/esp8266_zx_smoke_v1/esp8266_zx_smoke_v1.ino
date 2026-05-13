/**
 * esp8266_zx_smoke_v1.ino — ZX Spectrum Firmware Host Flow (v1)
 *
 * This sketch proves that the firmware can drive the ZX Spectrum adapter
 * honestly end-to-end. It sets up the Spectrum palette, configures the
 * global bitmap and ZX adapter registers, and uploads a static test
 * pattern to SDRAM.
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_upload.h>

/* ZX Spectrum 15-color palette (RGB888) */
static const uint32_t ZX_PALETTE[16] = {
    0x000000, 0x0000CD, 0xCD0000, 0xCD00CD, // 0..3: Black, Blue, Red, Magenta
    0x00CD00, 0x00CDCD, 0xCDCD00, 0xCDCDCD, // 4..7: Green, Cyan, Yellow, White
    0x000000, 0x0000FF, 0xFF0000, 0xFF00FF, // 8..11: Bright variants
    0x00FF00, 0x00FFFF, 0xFFFF00, 0xFFFFFF  // 12..15
};

/* SDRAM Layout per Checkpoint A */
#define ZX_ATTR_BASE   0x00005800u
#define ZX_BITMAP_BASE 0x00006000u

static void load_zx_palette(void)
{
    Serial.println(F("zx-smoke: loading 16-color Spectrum palette..."));
    for (int i = 0; i < 16; ++i) {
        uint32_t color = ZX_PALETTE[i];
        vdp_reg_write(0x0601u, i * 2);           // PALETTE_PTR
        vdp_reg_write(0x0600u, color & 0xFFFFu); // PALETTE_DATA (bits 15:0)
        vdp_reg_write(0x0600u, (color >> 16) & 0xFFu); // PALETTE_DATA (bits 23:16)
    }
}

static void upload_test_pattern(void)
{
    Serial.println(F("zx-smoke: generating and uploading test pattern..."));

    /* 1. Generate attribute data (32x24 = 768 bytes, 384 words) */
    /* Layout: {flash[7], bright[6], paper[5:3], ink[2:0]} */
    uint16_t attr_data[384];
    for (int y = 0; y < 24; ++y) {
        for (int x = 0; x < 32; x += 2) {
            uint8_t a0 = (y % 8) | (0 << 3) | ((y / 12) << 6); // Ink=y%8, Paper=Black, Bright=top/bottom split
            uint8_t a1 = ((y + 1) % 8) | (0 << 3) | ((y / 12) << 6);
            attr_data[(y * 16) + (x / 2)] = (uint16_t)a0 | ((uint16_t)a1 << 8);
        }
    }
    vdp_upload_asset(ZX_ATTR_BASE, attr_data, 384, NULL);

    /* 2. Generate bitmap data (256x192 pixels = 32x192 bytes = 6144 bytes, 3072 words) */
    /* Pattern: A simple border frame and a large 'X' */
    uint16_t bitmap_data[3072];
    memset(bitmap_data, 0, sizeof(bitmap_data));

    for (int y = 0; y < 192; ++y) {
        for (int x_byte = 0; x_byte < 32; ++x_byte) {
            uint8_t byte = 0;
            // Border
            if (y == 0 || y == 191 || x_byte == 0 || x_byte == 31) {
                byte = 0xFF;
            }
            // 'X'
            int x_start = x_byte * 8;
            for (int bit = 0; bit < 8; ++bit) {
                int x = x_start + bit;
                if (x == y || x == (255 - y)) {
                    byte |= (1 << (7 - bit));
                }
            }
            
            // Pack into 16-bit words
            if (x_byte % 2 == 0) {
                bitmap_data[(y * 16) + (x_byte / 2)] = (uint16_t)byte;
            } else {
                bitmap_data[(y * 16) + (x_byte / 2)] |= ((uint16_t)byte << 8);
            }
        }
    }
    vdp_upload_asset(ZX_BITMAP_BASE, bitmap_data, 3072, NULL);
}

static void configure_vdp_zx(void)
{
    Serial.println(F("zx-smoke: configuring VDP registers for ZX mode..."));

    // 1. Global Bitmap Configuration
    vdp_reg_write(0x0311u, 0x0000u); // VDP_TILE_MODE = bitmap
    vdp_reg_write(0x0351u, (uint16_t)(ZX_BITMAP_BASE & 0xFFFFu));
    vdp_reg_write(0x0352u, (uint16_t)((ZX_BITMAP_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0353u, (uint16_t)(ZX_ATTR_BASE & 0xFFFFu));
    vdp_reg_write(0x0354u, (uint16_t)((ZX_ATTR_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0355u, 32);      // BITMAP_STRIDE
    vdp_reg_write(0x0356u, 32);      // ATTR_STRIDE
    vdp_reg_write(0x0350u, 0x0081u); // BITMAP_CTRL = enable | 1bpp | useSdram

    // 2. ZX Adapter Configuration
    vdp_reg_write(0x0F00u, 0x0005u); // ZX_BORDER = Cyan
    vdp_reg_write(0x0F03u, 0x0001u); // ZX_CTRL = enable (auto-emits LAYER_ENABLE)

    Serial.println(F("zx-smoke: configuration complete"));
}

void setup(void)
{
    Serial.begin(115200);
    delay(100);
    Serial.println();
    Serial.println(F("ESP8266 ZX Spectrum Smoke Test (v1) — booting"));

    vdp_qspi_init();
    delay(200);

    load_zx_palette();
    upload_test_pattern();
    configure_vdp_zx();

    Serial.println(F("ZX Smoke Test active. Displaying pattern..."));
}

void loop(void)
{
    // Flash the border color for extra verification
    static uint8_t border = 5;
    delay(1000);
    border = (border + 1) % 8;
    vdp_reg_write(0x0F00u, (uint16_t)border);
}
