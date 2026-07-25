/**
 * esp8266_rainbow_background.ino
 *
 * Minimal rainbow background demo for ESP8266.
 */

#include <Arduino.h>
#include <vdp_copper.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>

namespace {

constexpr uint8_t kBands = 24u;
constexpr uint16_t kBandLines = 20u;
uint16_t copper_prog[128];

void hue_to_rgb(uint16_t hue, uint8_t *r, uint8_t *g, uint8_t *b)
{
    const uint8_t region = (uint8_t)((hue / 60u) % 6u);
    const uint16_t rem = (uint16_t)((hue % 60u) * 255u / 60u);
    const uint8_t q = (uint8_t)(255u - rem);
    const uint8_t t = (uint8_t)rem;

    switch (region) {
    case 0: *r = 255u; *g = t;    *b = 0u;   break;
    case 1: *r = q;    *g = 255u; *b = 0u;   break;
    case 2: *r = 0u;   *g = 255u; *b = t;    break;
    case 3: *r = 0u;   *g = q;    *b = 255u; break;
    case 4: *r = t;    *g = 0u;   *b = 255u; break;
    default:*r = 255u; *g = 0u;   *b = q;    break;
    }
}

void load_palette(void)
{
    for (uint8_t i = 0; i < kBands; ++i) {
        const uint16_t hue = (uint16_t)(i * 360u / kBands);
        uint8_t r = 0u;
        uint8_t g = 0u;
        uint8_t b = 0u;
        hue_to_rgb(hue, &r, &g, &b);
        vdp_mode0_palette_write_rgb888(i, r, g, b);
    }
}

uint16_t build_program(void)
{
    uint16_t pc = 0u;

    for (uint8_t i = 0; i < kBands; ++i) {
        copper_prog[pc++] = vdp_copper_wait((uint16_t)(i * kBandLines));
        copper_prog[pc++] = vdp_copper_write_op(VDP_MODE0_REG_BORDER_CTRL);
        copper_prog[pc++] = vdp_mode0_border_ctrl(true, i);
    }

    copper_prog[pc++] = vdp_copper_jump(0u);
    return pc;
}

void make_border_only(void)
{
    const vdp_mode0_rect_t rect = { 0u, 1280u, 0u, 0u };

    vdp_copper_enable(false);
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0u));
}

}  // namespace

void setup(void)
{
    Serial.begin(115200);
    delay(200);

    vdp_qspi_init();
    delay(200);

    make_border_only();
    load_palette();

    vdp_copper_upload(copper_prog, build_program());
    vdp_copper_enable(true);
}

void loop(void)
{
    delay(1000);
}
