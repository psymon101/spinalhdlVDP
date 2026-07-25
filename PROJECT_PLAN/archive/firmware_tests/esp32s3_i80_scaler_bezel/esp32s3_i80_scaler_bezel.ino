/**
 * ESP32-S3 i80 scaler + border mode sequence.
 */
#include <Arduino.h>

#include <vdp_host.h>
#include <vdp_mode0.h>

namespace {

constexpr uint16_t kHoldMs = 6000u;
constexpr uint16_t kBorderWhite = 0x0101u;
constexpr uint16_t kBackdropIndexReg = 0x0348u;

struct ScaleMode {
    const char *name;
    uint8_t scale_x;
    uint8_t scale_y;
    bool auto_center;
    uint16_t logic_width;
    uint16_t logic_height;
};

constexpr ScaleMode kModes[] = {
    {"1x bypass", 1u, 1u, false, 640u, 480u},
    {"2x repeat", 2u, 2u, false, 320u, 240u},
    {"3x auto-center", 3u, 3u, true, 160u, 160u},
    {"4x auto-center", 4u, 4u, true, 128u, 120u},
};

void init_scene()
{
    const vdp_mode0_rect_t full = {0u, 640u, 0u, 480u};
    vdp_mode0_set_vdp_ctrl_word(0x0000u);
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);
    vdp_reg_write(VDP_MODE0_REG_PLANAR_CTRL, 0x0000u);
    for (uint8_t bank = 0; bank < 8u; ++bank) {
        vdp_mode0_palette_write_rgb888((uint8_t)(bank * 16u), 0u, 0u, 0u);
    }
    vdp_reg_write(kBackdropIndexReg, 64u);
    vdp_mode0_palette_write_rgb888(1u, 255u, 255u, 255u);
    vdp_mode0_set_border_window(&full, kBorderWhite);
}

void apply_mode(const ScaleMode &mode)
{
    const uint16_t ctrl = vdp_mode0_scale_ctrl(mode.scale_x, mode.scale_y, mode.auto_center);
    vdp_reg_write(VDP_MODE0_REG_LOGIC_WIDTH, mode.logic_width);
    const bool width_ok = vdp_reg_read(VDP_MODE0_REG_LOGIC_WIDTH) == mode.logic_width;
    vdp_reg_write(VDP_MODE0_REG_LOGIC_HEIGHT, mode.logic_height);
    const bool height_ok = vdp_reg_read(VDP_MODE0_REG_LOGIC_HEIGHT) == mode.logic_height;
    vdp_reg_write(VDP_MODE0_REG_SCALE_CTRL, ctrl);
    const bool ctrl_ok = vdp_reg_read(VDP_MODE0_REG_SCALE_CTRL) == ctrl;
    delay(20);
    const bool pass = width_ok && height_ok && ctrl_ok;
    Serial.printf("mode=%s ctrl=0x%04X logic=%ux%u %s\n",
                  mode.name, ctrl, mode.logic_width, mode.logic_height,
                  pass ? "PASS" : "FAIL");
}

}  // namespace

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("ESP32-S3 i80 scaler bezel");
    vdp_host_init();
    delay(50);
    init_scene();
}

void loop()
{
    for (const ScaleMode &mode : kModes) {
        apply_mode(mode);
        delay(kHoldMs);
    }
}
