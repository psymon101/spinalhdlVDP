/**
 * esp8266_rgb565_bench.ino — RGB565 directcolor bitmap bench proof.
 *
 * BrightForge fix: single BITMAP_CTRL=0x0085 write, no copper override,
 * no extra 0x0350 touches.
 *
 * WARNING: This sketch uses the POR default bases 0x3000/0x4000. At the
 * default 512-byte stride those bases overlap after 8 rows, so this sketch
 * is only suitable for a small-pattern bench, NOT for a full 320x240 RGB565
 * image. For full-screen RGB565 use non-overlapping bases such as 0x100000
 * and 0x200000 (see firmware/esp32s3_rgb565_fullframe/).
 *
 * NOTE: BITMAP_CTRL=0x0085 sets bit 7, which is deprecated/no-op in the
 * current register spec. The canonical RGB565 value is 0x0005 (enable + BPP=0b10).
 * This sketch keeps 0x0085 for historical bench compatibility with the ESP8266
 * QSPI path.
 */
#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>

#define IMG_W       320
#define IMG_H       240
#define TILE_W      40
#define ROW_STRIDE  512u
#define LOW_BASE    0x3000u
#define HIGH_BASE   0x4000u

static uint16_t plane_buf[IMG_W / 2];

static const uint16_t RGB565_RED   = 0xF800;
static const uint16_t RGB565_WHITE = 0xFFFF;

static void upload_row_planes(uint16_t y, const uint16_t *pixels)
{
    for (uint16_t x = 0; x < IMG_W; x += 2) {
        uint8_t lo0 = (uint8_t)( pixels[x]       & 0xFFu);
        uint8_t lo1 = (uint8_t)( pixels[x + 1]   & 0xFFu);
        plane_buf[x / 2] = (uint16_t)(lo0 | (lo1 << 8));
    }
    vdp_sdram_write(LOW_BASE  + (uint32_t)y * ROW_STRIDE, plane_buf, IMG_W / 2);

    for (uint16_t x = 0; x < IMG_W; x += 2) {
        uint8_t hi0 = (uint8_t)((pixels[x]     >> 8) & 0xFFu);
        uint8_t hi1 = (uint8_t)((pixels[x + 1] >> 8) & 0xFFu);
        plane_buf[x / 2] = (uint16_t)(hi0 | (hi1 << 8));
    }
    vdp_sdram_write(HIGH_BASE + (uint32_t)y * ROW_STRIDE, plane_buf, IMG_W / 2);
}

static void upload_pattern(void)
{
    uint16_t row_pixels[IMG_W];
    for (uint16_t y = 0; y < IMG_H; ++y) {
        uint8_t ytile = y / TILE_W;
        for (uint16_t x = 0; x < IMG_W; ++x) {
            uint8_t xtile = x / TILE_W;
            bool white = ((xtile + ytile) & 1u);
            row_pixels[x] = white ? RGB565_WHITE : RGB565_RED;
        }
        upload_row_planes(y, row_pixels);
        if ((y & 0x1Fu) == 0x1Fu) yield();
    }
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println(F("RGB565 bench — minimal"));

    vdp_qspi_init();
    delay(200);

    Serial.print(F("Magic: 0x"));
    Serial.println(vdp_read_status(0), HEX);

    upload_pattern();
    Serial.println(F("Upload done"));

    /* Single clean BITMAP_CTRL write — 0x85 = enable|bpp=0b10|useSdram */
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, 0x0085u);
    Serial.println(F("BITMAP_CTRL = 0x85"));

    vdp_mode0_set_bitmap_base(LOW_BASE);
    vdp_mode0_set_attr_base(0x4000u);
    vdp_mode0_set_bitmap_stride(ROW_STRIDE);
    vdp_mode0_set_attr_stride(512u);
    Serial.println(F("Base/stride set"));

    /* Enable bitmap layer only */
    vdp_mode0_set_layer_enable(0x0001u);
    Serial.println(F("Layer enabled"));

    Serial.println(F("Bench active"));
}

void loop(void)
{
    delay(5000);
}
