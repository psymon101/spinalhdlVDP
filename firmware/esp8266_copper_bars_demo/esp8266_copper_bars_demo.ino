/*
 * Copper bars demo — 24 bars × 20 lines = 480 lines, fits active area.
 */
#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>
#include <vdp_copper.h>

#define NUM_BARS    24
#define LINES_PER_BAR 20
#define NUM_PALETTE 32

static uint8_t hsv_r, hsv_g, hsv_b;

static void hsv_to_rgb(uint16_t hue)
{
    uint8_t hi = (uint8_t)(hue / 60);
    uint8_t f  = (uint8_t)((((uint16_t)(hue % 60)) * 255) / 60);
    uint8_t p  = 0, q = 255 - f, t = f;
    switch (hi % 6) {
        case 0: hsv_r = 255; hsv_g = t;     hsv_b = p;     break;
        case 1: hsv_r = q;     hsv_g = 255; hsv_b = p;     break;
        case 2: hsv_r = p;     hsv_g = 255; hsv_b = t;     break;
        case 3: hsv_r = p;     hsv_g = q;     hsv_b = 255; break;
        case 4: hsv_r = t;     hsv_g = p;     hsv_b = 255; break;
        case 5: hsv_r = 255; hsv_g = p;     hsv_b = q;     break;
    }
}

static void upload_rainbow_palette(void)
{
    Serial.println(F("Palette: uploading 32-entry rainbow..."));
    for (uint8_t i = 0; i < NUM_PALETTE; ++i) {
        uint16_t hue = (uint16_t)(i * 360u / NUM_PALETTE);
        hsv_to_rgb(hue);
        vdp_mode0_palette_write_rgb888(i, hsv_r, hsv_g, hsv_b);
    }
    Serial.println(F("Palette: done."));
}

static uint16_t copper_prog[512];

static uint16_t build_copper_program(void)
{
    uint16_t pc = 0;
    for (uint8_t bar = 0; bar < NUM_BARS; ++bar) {
        uint16_t y = (uint16_t)(bar * LINES_PER_BAR);
        copper_prog[pc++] = vdp_copper_wait(y);
        copper_prog[pc++] = (uint16_t)(0x4000u | 0x0347u);
        copper_prog[pc++] = vdp_mode0_border_ctrl(true, bar);
    }
    copper_prog[pc++] = vdp_copper_jump(0);
    return pc;
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println(F("\n=== Copper Bars Demo (24 bars) ===\n"));

    vdp_qspi_init();
    delay(200);

    upload_rainbow_palette();

    vdp_mode0_set_layer_enable(0x0000u);
    vdp_reg_write(0x0350u, 0x0000u);
    vdp_reg_write(0x0334u, 0x0000u);

    vdp_mode0_rect_t rect = { 0, 1280, 0, 0 };
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));

    uint16_t prog_len = build_copper_program();
    Serial.printf("Copper: program = %u words (%u bars + JUMP)\n", prog_len, NUM_BARS);

    vdp_copper_upload(copper_prog, prog_len);
    Serial.println(F("Copper: uploaded."));

    vdp_copper_enable(true);
    Serial.println(F("Copper: armed."));
    Serial.println(F("\n=== Demo running ==="));
}

void loop(void)
{
    delay(5000);
}
