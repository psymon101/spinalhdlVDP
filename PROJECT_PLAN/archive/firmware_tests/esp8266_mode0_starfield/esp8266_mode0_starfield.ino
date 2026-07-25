/**
 * esp8266_mode0_starfield.ino — generic Mode0 bitmap smoke sketch.
 *
 * Scenario: render a simple 640x480 1bpp starfield through the generic
 * bitmap+attribute fetch path using libvdp only.
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 *
 * Pin map:
 *   SCK  D5 / GPIO14
 *   CS_N D6 / GPIO12
 *   IO0  D7 / GPIO13
 *   IO1  D1 / GPIO5
 *   IO2  D2 / GPIO4
 *   IO3  D0 / GPIO16
 *
 * Boot sequence:
 *   1. Init QSPI transport
 *   2. Hide layers and disable copper
 *   3. Upload 640x480 bitmap and attribute planes row-by-row
 *   4. Program bitmap fetch registers
 *   5. Reveal L0
 *
 * Expected result:
 *   Black screen with static white stars at 640x480 output timing.
 */

#include <Arduino.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>
#include <vdp_upload.h>

#define STARFIELD_W 640u
#define STARFIELD_H 480u
#define STARFIELD_ROW_BYTES (STARFIELD_W / 8u)
#define STARFIELD_ROW_WORDS (STARFIELD_ROW_BYTES / 2u)

#define STARFIELD_BITMAP_BASE 0x00003000u
#define STARFIELD_ATTR_BASE   0x00004000u

static uint16_t s_bitmap_row[STARFIELD_ROW_WORDS];
static uint16_t s_attr_row[STARFIELD_ROW_WORDS];

static inline void clear_row(uint16_t *row_words)
{
    memset(row_words, 0x00, STARFIELD_ROW_BYTES);
}

static inline void fill_attr_row(uint16_t *row_words, uint8_t attr_byte)
{
    for (uint16_t i = 0; i < STARFIELD_ROW_WORDS; ++i) {
        row_words[i] = (uint16_t)attr_byte | ((uint16_t)attr_byte << 8);
    }
}

static inline void set_pixel(uint16_t *row_words, uint16_t x)
{
    uint8_t *row_bytes = (uint8_t *)row_words;
    row_bytes[x >> 3] |= (uint8_t)(0x80u >> (x & 7u));
}

static void populate_star_row(uint16_t *row_words, uint16_t y)
{
    clear_row(row_words);

    const uint16_t x0 = (uint16_t)((y * 37u + 11u) % STARFIELD_W);
    const uint16_t x1 = (uint16_t)((y * 73u + 101u) % STARFIELD_W);
    const uint16_t x2 = (uint16_t)((y * 151u + 307u) % STARFIELD_W);

    set_pixel(row_words, x0);
    if ((y & 1u) == 0u) {
        set_pixel(row_words, x1);
    }
    if ((y % 5u) == 0u) {
        set_pixel(row_words, x2);
    }
}

static void upload_starfield(void)
{
    fill_attr_row(s_attr_row, 0x47u); /* bright white ink on black paper */

    for (uint16_t y = 0; y < STARFIELD_H; ++y) {
        populate_star_row(s_bitmap_row, y);
        vdp_upload_asset(STARFIELD_BITMAP_BASE + (uint32_t)y * STARFIELD_ROW_BYTES,
                         s_bitmap_row, STARFIELD_ROW_WORDS, NULL);
        vdp_upload_asset(STARFIELD_ATTR_BASE + (uint32_t)y * STARFIELD_ROW_BYTES,
                         s_attr_row, STARFIELD_ROW_WORDS, NULL);
        yield();
    }
}

static void program_mode0_bitmap(void)
{
    vdp_mode0_bitmap_cfg_t cfg;

    cfg.ctrl = (uint16_t)(vdp_mode0_bitmap_ctrl(true, VDP_MODE0_BITMAP_BPP_1, 0u) | 0x0080u);
    cfg.bitmap_base = STARFIELD_BITMAP_BASE;
    cfg.attr_base = STARFIELD_ATTR_BASE;
    cfg.bitmap_stride = STARFIELD_ROW_BYTES;
    cfg.attr_stride = STARFIELD_ROW_BYTES;

    vdp_mode0_palette_write_rgb888(0u, 0x00u, 0x00u, 0x00u);
    vdp_mode0_palette_write_rgb888(7u, 0xFFu, 0xFFu, 0xFFu);

    vdp_mode0_set_mode_select(0x0000u);
    vdp_mode0_set_vdp_ctrl(false);
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_cfg(&cfg);
    vdp_mode0_set_layer_enable(0x0001u);
}

void setup(void)
{
    Serial.begin(115200);
    delay(100);
    Serial.println();
    Serial.println(F("ESP8266 Mode0 starfield — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.print(F("Magic: 0x"));
    Serial.println(vdp_read_status(0), HEX);
    Serial.print(F("Live mode before: 0x"));
    Serial.println(vdp_read_status(7), HEX);

    Serial.println(F("Uploading starfield..."));
    vdp_mode0_set_layer_enable(0x0000u);
    upload_starfield();

    Serial.println(F("Programming bitmap path..."));
    program_mode0_bitmap();
    delay(50);

    Serial.print(F("Live mode after: 0x"));
    Serial.println(vdp_read_status(7), HEX);
    Serial.print(F("Sticky: 0x"));
    Serial.println(vdp_read_status(5), HEX);
    Serial.print(F("Upload status: 0x"));
    Serial.println(vdp_read_status(6), HEX);
    Serial.print(F("Last error: 0x"));
    Serial.println(vdp_read_status(4), HEX);

    Serial.println(F("Starfield armed"));
}

void loop(void)
{
    delay(1000);
}
