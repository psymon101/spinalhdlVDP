/**
 * ESP32-S3 i80 copper-only horizontal bars proof.
 *
 * This sketch is intentionally standalone. It clears unrelated display state,
 * disables all sprite descriptors, uploads its own palette and copper program,
 * then enables only the copper-driven border/backdrop scene.
 */
#include <Arduino.h>

#include <vdp_copper.h>
#include <vdp_host.h>
#include <vdp_mode0.h>

namespace {

constexpr uint8_t kNumBars = 24u;
constexpr uint16_t kLinesPerBar = 20u;
constexpr uint16_t kBackdropIndexReg = 0x0348u;
constexpr uint16_t kProgramWords = (uint16_t)((kNumBars * 3u) + 1u);

uint16_t g_program[kProgramWords];

bool write_expect(uint16_t addr, uint16_t value, const char *label)
{
    vdp_reg_write(addr, value);
    const uint16_t got = vdp_reg_read(addr);
    const bool ok = (got == value);
    Serial.printf("%s reg[0x%04X]=0x%04X read=0x%04X %s\n",
                  label, addr, value, got, ok ? "PASS" : "FAIL");
    return ok;
}

void hsv_to_rgb(uint16_t hue, uint8_t &r, uint8_t &g, uint8_t &b)
{
    const uint8_t sector = (uint8_t)(hue / 60u);
    const uint8_t frac = (uint8_t)(((hue % 60u) * 255u) / 60u);
    const uint8_t inv = (uint8_t)(255u - frac);

    switch (sector % 6u) {
    case 0: r = 255u; g = frac; b = 0u;    break;
    case 1: r = inv;  g = 255u; b = 0u;    break;
    case 2: r = 0u;   g = 255u; b = frac; break;
    case 3: r = 0u;   g = inv;  b = 255u; break;
    case 4: r = frac; g = 0u;   b = 255u; break;
    default:r = 255u; g = 0u;   b = inv;  break;
    }
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

bool reset_vdp_state()
{
    bool ok = true;

    ok &= write_expect(VDP_MODE0_REG_VDP_CTRL, 0x0000u, "reset");
    delay(4);
    ok &= write_expect(VDP_MODE0_REG_LAYER_ENABLE, 0x0000u, "reset");
    ok &= write_expect(VDP_MODE0_REG_BITMAP_CTRL, 0x0000u, "reset");
    ok &= write_expect(VDP_MODE0_REG_MODE_SELECT, 0x0000u, "reset");
    ok &= write_expect(VDP_MODE0_REG_PLANAR_CTRL, 0x0000u, "reset");
    ok &= write_expect(VDP_MODE0_REG_COLOR_MATH_CTRL, 0x0000u, "reset");
    ok &= write_expect(VDP_MODE0_REG_BORDER_CTRL, 0x0000u, "reset");
    ok &= write_expect(kBackdropIndexReg, 0x0000u, "reset");

    for (uint16_t line = 0; line < VDP_MODE0_LINESTATE_COUNT; ++line) {
        vdp_mode0_write_linestate(line, 0x0800u);
    }

    for (uint8_t slot = 0u; slot < 32u; ++slot) {
        disable_sprite(slot);
    }

    for (uint8_t idx = 0u; idx < 128u; ++idx) {
        vdp_mode0_palette_write_rgb888(idx, 0u, 0u, 0u);
    }

    delay(80);
    return ok;
}

void upload_bar_palette()
{
    for (uint8_t idx = 0u; idx < kNumBars; ++idx) {
        uint8_t r = 0u, g = 0u, b = 0u;
        hsv_to_rgb((uint16_t)((uint16_t)idx * 360u / kNumBars), r, g, b);
        vdp_mode0_palette_write_rgb888(idx, r, g, b);
    }
}

uint16_t build_copper_program()
{
    uint16_t pc = 0u;

    for (uint8_t bar = 0u; bar < kNumBars; ++bar) {
        g_program[pc++] = vdp_copper_wait((uint16_t)((uint16_t)bar * kLinesPerBar));
        g_program[pc++] = vdp_copper_write_op(VDP_MODE0_REG_BORDER_CTRL);
        g_program[pc++] = vdp_mode0_border_ctrl(true, bar);
    }

    g_program[pc++] = vdp_copper_jump(0u);
    return pc;
}

bool seed_static_border()
{
    vdp_mode0_set_border_ctrl(vdp_mode0_border_ctrl(true, 0u));
    const uint16_t got = vdp_reg_read(VDP_MODE0_REG_BORDER_CTRL);
    const bool ok = ((got & 0x1F01u) == vdp_mode0_border_ctrl(true, 0u));
    Serial.printf("seed border_ctrl read=0x%04X %s\n", got, ok ? "PASS" : "FAIL");
    return ok;
}

}  // namespace

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("ESP32-S3 i80 copper bars clean");

    vdp_host_init();
    delay(50);

    bool ok = reset_vdp_state();
    upload_bar_palette();
    ok &= seed_static_border();

    const uint16_t words = build_copper_program();
    vdp_copper_upload(g_program, words);
    delay(20);
    vdp_copper_enable(true);
    delay(40);

    const uint16_t ctrl = vdp_reg_read(VDP_MODE0_REG_VDP_CTRL);
    ok &= ((ctrl & 0x0001u) != 0u);
    Serial.printf("i80_copper_bars_clean words=%u vdp_ctrl=0x%04X %s last_error=%d\n",
                  words, ctrl, ok ? "PASS" : "FAIL", vdp_last_error());
    Serial.println("expected visual: clean full-screen horizontal copper color bars; no sprites");
}

void loop()
{
    delay(1000);
}
