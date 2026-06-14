/**
 * ESP32-S3 i80 copper diagnostic.
 *
 * Standalone two-phase test:
 * 1. Disabled-upload/start: upload bank 0 while copper disabled, enable it.
 * 2. Live-upload/swap: upload bank 1 while copper enabled, request swap.
 *
 * Each program uses a single immediate WRITE loop, so any color change proves
 * copper fetch/decode/writeback without relying on line timing.
 */
#include <Arduino.h>

#include <vdp_copper.h>
#include <vdp_i80.h>
#include <vdp_mode0.h>

namespace {

constexpr uint16_t kBackdropIndexReg = 0x0348u;
constexpr uint16_t kGreenBorder = 0x0101u;
constexpr uint16_t kBlueBorder = 0x0201u;

const uint16_t kProgramGreen[] = {
    vdp_copper_write_op(VDP_MODE0_REG_BORDER_CTRL),
    kGreenBorder,
    vdp_copper_jump(0u),
};

const uint16_t kProgramBlue[] = {
    vdp_copper_write_op(VDP_MODE0_REG_BORDER_CTRL),
    kBlueBorder,
    vdp_copper_jump(0u),
};

bool write_expect(uint16_t addr, uint16_t value, const char *label)
{
    vdp_reg_write(addr, value);
    const uint16_t got = vdp_reg_read(addr);
    const bool ok = (got == value);
    Serial.printf("%s reg[0x%04X]=0x%04X read=0x%04X %s\n",
                  label, addr, value, got, ok ? "PASS" : "FAIL");
    return ok;
}

void disable_sprite(uint8_t slot)
{
    const uint16_t base = (uint16_t)(VDP_MODE0_REG_SPRITE_ATTR_BASE + (uint16_t)slot * 8u);
    vdp_reg_write(base + 0u, 0x03FFu);
    vdp_reg_write(base + 1u, 0x03FFu);
    for (uint8_t word = 2u; word < 8u; ++word) {
        vdp_reg_write((uint16_t)(base + word), 0x0000u);
    }
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_SPRITE_HARD_BASE + slot), 0x0000u);
}

void clean_scene()
{
    write_expect(VDP_MODE0_REG_VDP_CTRL, 0x0000u, "reset");
    delay(4);
    write_expect(VDP_MODE0_REG_LAYER_ENABLE, 0x0000u, "reset");
    write_expect(VDP_MODE0_REG_BITMAP_CTRL, 0x0000u, "reset");
    write_expect(VDP_MODE0_REG_MODE_SELECT, 0x0000u, "reset");
    write_expect(VDP_MODE0_REG_PLANAR_CTRL, 0x0000u, "reset");
    write_expect(VDP_MODE0_REG_COLOR_MATH_CTRL, 0x0000u, "reset");
    write_expect(VDP_MODE0_REG_BORDER_CTRL, 0x0000u, "reset");
    write_expect(kBackdropIndexReg, 0x0000u, "reset");

    for (uint8_t slot = 0u; slot < 32u; ++slot) {
        disable_sprite(slot);
    }

    for (uint8_t idx = 0u; idx < 16u; ++idx) {
        vdp_mode0_palette_write_rgb888(idx, 0u, 0u, 0u);
    }
    vdp_mode0_palette_write_rgb888(0u, 255u, 0u, 0u);
    vdp_mode0_palette_write_rgb888(1u, 0u, 255u, 0u);
    vdp_mode0_palette_write_rgb888(2u, 0u, 0u, 255u);
    delay(80);
}

void print_program(const char *label, const uint16_t *prog, uint16_t words)
{
    Serial.printf("%s words=%u:", label, words);
    for (uint16_t i = 0; i < words; ++i) {
        Serial.printf(" 0x%04X", prog[i]);
    }
    Serial.println();
}

}  // namespace

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("ESP32-S3 i80 copper diag");

    vdp_host_init();
    delay(50);
    clean_scene();

    write_expect(VDP_MODE0_REG_BORDER_CTRL, 0x0001u, "direct-red");
    delay(2200);

    print_program("phase1", kProgramGreen, 3u);
    vdp_copper_upload(kProgramGreen, 3u);
    delay(20);
    vdp_copper_enable(true);
    delay(2200);
    uint16_t ctrl = vdp_reg_read(VDP_MODE0_REG_VDP_CTRL);
    Serial.printf("phase1 helper-green ctrl=0x%04X %s\n",
                  ctrl, (ctrl & 0x0001u) ? "PASS" : "FAIL");

    print_program("phase2", kProgramBlue, 3u);
    vdp_copper_upload_and_swap(kProgramBlue, 3u);
    delay(2200);
    ctrl = vdp_reg_read(VDP_MODE0_REG_VDP_CTRL);
    Serial.printf("phase2 helper-blue ctrl=0x%04X %s\n",
                  ctrl, (ctrl & 0x0001u) ? "PASS" : "FAIL");

    Serial.printf("i80_copper_diag last_error=%d\n", vdp_last_error());
}

void loop()
{
    delay(1000);
}
