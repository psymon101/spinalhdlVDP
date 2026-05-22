#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>
#include <vdp_copper.h>
#include <vdp_palette_lut.h>
#define NUM_COLORS 24
#define LINES_PER_BAND 20
static uint16_t copper_prog[512];

static uint16_t build_prog(void) {
    uint16_t pc = 0;
    for (uint8_t band = 0; band < NUM_COLORS; ++band) {
        uint16_t line = (uint16_t)(band * LINES_PER_BAND);
        copper_prog[pc++] = vdp_copper_wait(line);
        copper_prog[pc++] = vdp_copper_write_op(0x0347u);
        copper_prog[pc++] = vdp_mode0_border_ctrl(true, band);
    }
    copper_prog[pc++] = vdp_copper_jump(0);
    return pc;
}
void setup(void) {
    Serial.begin(115200);
    delay(1000);
    vdp_qspi_init();
    delay(200);
    for (uint8_t i = 0; i < NUM_COLORS; ++i) {
        uint16_t hue = (uint16_t)(i * 360UL / NUM_COLORS);
        vdp_atarist_palette_write_hsv(i, hue, 255, 255);
    }
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);
    vdp_mode0_set_border_ctrl(vdp_mode0_border_ctrl(true, 0));
    vdp_mode0_rect_t rect = { 0, 1280, 0, 0 };
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));
    uint16_t prog_len = build_prog();
    Serial.printf("Copper: %u words\\n", prog_len);
    vdp_copper_upload(copper_prog, prog_len);
    vdp_copper_enable(true);
}
void loop(void) { delay(5000); }
