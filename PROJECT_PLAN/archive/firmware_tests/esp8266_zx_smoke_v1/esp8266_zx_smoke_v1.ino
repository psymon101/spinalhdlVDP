/**
 * esp8266_zx_smoke_v1.ino — ZX Spectrum Firmware Host Flow (v1)
 *
 * This sketch proves that the firmware can drive the ZX Spectrum adapter
 * honestly end-to-end.
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_upload.h>
#include <vdp_status.h>

/* ZX Spectrum 15-color palette (RGB888) */
static const uint32_t ZX_PALETTE[16] = {
    0x000000, 0x0000CD, 0xCD0000, 0xCD00CD, // 0..3: Black, Blue, Red, Magenta
    0x00CD00, 0x00CDCD, 0xCDCD00, 0xCDCDCD, // 4..7: Green, Cyan, Yellow, White
    0x000000, 0x0000FF, 0xFF0000, 0xFF00FF, // 8..11: Bright variants
    0x00FF00, 0x00FFFF, 0xFFFF00, 0xFFFFFF  // 12..15
};

#define ZX_ATTR_BASE   0x00005800u
#define ZX_BITMAP_BASE 0x00006000u

static uint16_t s_attr_data[384];
static uint16_t s_bitmap_data[3072];

static void load_zx_palette(void)
{
    Serial.println(F("zx-smoke: loading palette..."));
    for (int i = 0; i < 16; ++i) {
        uint32_t color = ZX_PALETTE[i];
        vdp_reg_write(0x0601u, i * 2);           // PALETTE_PTR
        vdp_reg_write(0x0600u, color & 0xFFFFu); // PALETTE_DATA (bits 15:0)
        vdp_reg_write(0x0600u, (color >> 16) & 0xFFu); // PALETTE_DATA (bits 23:16)
    }
}

static bool upload_test_pattern(void)
{
    Serial.println(F("zx-smoke: generating patterns..."));
    for (int y = 0; y < 24; ++y) {
        for (int x = 0; x < 32; x += 2) {
            uint8_t a0 = (y % 8) | (0 << 3) | ((y / 12) << 6);
            uint8_t a1 = ((y + 1) % 8) | (0 << 3) | ((y / 12) << 6);
            s_attr_data[(y * 16) + (x / 2)] = (uint16_t)a0 | ((uint16_t)a1 << 8);
        }
    }

    memset(s_bitmap_data, 0, sizeof(s_bitmap_data));
    for (int y = 0; y < 192; ++y) {
        for (int x_byte = 0; x_byte < 32; ++x_byte) {
            uint8_t byte = 0;
            if (y == 0 || y == 191 || x_byte == 0 || x_byte == 31) byte = 0xFF;
            int x_start = x_byte * 8;
            for (int bit = 0; bit < 8; ++bit) {
                int x = x_start + bit;
                if (x == y || x == (255 - y)) byte |= (1 << (7 - bit));
            }
            if (x_byte % 2 == 0) {
                s_bitmap_data[(y * 16) + (x_byte / 2)] = (uint16_t)byte;
            } else {
                s_bitmap_data[(y * 16) + (x_byte / 2)] |= ((uint16_t)byte << 8);
            }
        }
    }

    Serial.println(F("zx-smoke: uploading assets..."));
    if (!vdp_upload_asset(ZX_ATTR_BASE, s_attr_data, 384, NULL)) return false;
    if (!vdp_upload_asset(ZX_BITMAP_BASE, s_bitmap_data, 3072, NULL)) return false;
    return true;
}

static void configure_vdp_zx(void)
{
    Serial.println(F("zx-smoke: configuring registers..."));
    vdp_reg_write(0x0310u, 0x0000u); // Copper off
    vdp_reg_write(0x0313u, 0x0002u); // Mode ZX
    vdp_reg_write(0x0311u, 0x0000u); // Bitmap mode
    vdp_reg_write(0x0351u, (uint16_t)(ZX_BITMAP_BASE & 0xFFFFu));
    vdp_reg_write(0x0352u, (uint16_t)((ZX_BITMAP_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0353u, (uint16_t)(ZX_ATTR_BASE & 0xFFFFu));
    vdp_reg_write(0x0354u, (uint16_t)((ZX_ATTR_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0355u, 32);
    vdp_reg_write(0x0356u, 32);
    vdp_reg_write(0x0350u, 0x0081u); // Enable | 1bpp | useSdram
    vdp_reg_write(0x0300u, 0x0001u); // L0 on
    vdp_reg_write(0x0F00u, 0x0005u); // Border Cyan
    vdp_reg_write(0x0F03u, 0x0001u); // ZX enable
}

void setup(void)
{
    Serial.begin(115200);
    delay(100);
    Serial.println(F("\nESP8266 ZX Smoke v1.1 - debugging transport"));

    vdp_qspi_init();
    delay(200);

    uint32_t magic = vdp_read_status(0);
    Serial.print(F("Magic: 0x")); Serial.println(magic, HEX);

    load_zx_palette();
    if (upload_test_pattern()) {
        Serial.println(F("Upload: OK"));
    } else {
        Serial.println(F("Upload: TIMEOUT"));
    }
    configure_vdp_zx();

    Serial.println(F("Looping..."));
}

void loop(void)
{
    static uint8_t border = 0;
    static uint32_t last_ms = 0;
    
    if (millis() - last_ms > 1000) {
        last_ms = millis();
        border = (border + 1) % 8;
        vdp_reg_write(0x0F00u, (uint16_t)border);
        
        uint32_t sticky = vdp_read_status(5);
        uint32_t up_stat = vdp_read_status(6);
        Serial.print(F("Border: ")); Serial.print(border);
        Serial.print(F(" Sticky: 0x")); Serial.print(sticky, HEX);
        Serial.print(F(" UpStat: 0x")); Serial.println(up_stat, HEX);
    }
}
