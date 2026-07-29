/**
 * ESP32-S3 i80 sprite descriptor/mask/priority proof.
 */
#include <Arduino.h>

#include <vdp_host.h>
#include <vdp_mode0.h>

namespace {

constexpr uint16_t kSpriteLayerEnable = 0x0004u;
constexpr uint16_t kPatternPixels = 16u * 16u;
constexpr uint8_t kFirstSlot = 10u;
constexpr uint8_t kMaskSlot = 12u;
constexpr uint16_t kSpriteY = 184u;
constexpr uint16_t kSpriteStepX = 56u;
constexpr uint16_t kSpriteStartX = 120u;
constexpr uint16_t kBackdropIndexReg = 0x0348u;

uint16_t g_solid_pattern[kPatternPixels];

bool write_expect(uint16_t addr, uint16_t value, const char *label)
{
    vdp_reg_write(addr, value);
    const uint16_t got = vdp_reg_read(addr);
    const bool ok = (got == value);
    Serial.printf("%s reg[0x%04X]=0x%04X read=0x%04X %s\n",
                  label, addr, value, got, ok ? "PASS" : "FAIL");
    return ok;
}

void build_pattern()
{
    for (uint16_t y = 0; y < 16u; ++y) {
        for (uint16_t x = 0; x < 16u; ++x) {
            const bool border = (x == 0u || x == 15u || y == 0u || y == 15u);
            const bool cross = (x == y) || ((uint16_t)(x + y) == 15u);
            g_solid_pattern[(uint16_t)(y * 16u + x)] = (border || cross) ? 2u : 1u;
        }
    }
}

void zero_display()
{
    write_expect(VDP_MODE0_REG_BITMAP_CTRL, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_LAYER_ENABLE, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_VDP_CTRL, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_MODE_SELECT, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_PLANAR_CTRL, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_COLOR_MATH_CTRL, 0x0000u, "init");
    write_expect(VDP_MODE0_REG_BORDER_CTRL, 0x0000u, "init");

    for (uint8_t bank = 0; bank < 8u; ++bank) {
        vdp_mode0_palette_write_rgb888((uint8_t)(bank * 16u), 0u, 0u, 0u);
    }
    vdp_reg_write(kBackdropIndexReg, 0u);

    for (uint16_t line = 0; line < VDP_MODE0_LINESTATE_COUNT; ++line) {
        vdp_mode0_write_linestate(line, 0x0800u);
    }
}

vdp_mode0_sprite_cfg_t sprite_cfg(uint8_t index, uint8_t slot, bool mask)
{
    vdp_mode0_sprite_cfg_t cfg = {};
    cfg.x = (uint16_t)(kSpriteStartX + (uint16_t)index * kSpriteStepX);
    cfg.y = kSpriteY;
    cfg.matrix[0] = 0x0100u;
    cfg.matrix[1] = 0x0000u;
    cfg.matrix[2] = 0x0000u;
    cfg.matrix[3] = 0x0100u;
    cfg.trans_x = 0x0000u;
    cfg.trans_y = 0x0000u;
    cfg.pat_idx = 0u;
    cfg.enabled = true;
    cfg.affine_en = false;
    cfg.size_sel = 1u;
    cfg.pal_bank = (uint8_t)(slot - kFirstSlot);
    cfg.prio = 2u;
    cfg.flip_h = false;
    cfg.flip_v = false;
    cfg.bpp_sel = 0u;
    cfg.mask = mask;
    return cfg;
}

uint16_t sprite_word0(const vdp_mode0_sprite_cfg_t &cfg)
{
    return (uint16_t)(cfg.y & 0x03FFu) |
           (uint16_t)(cfg.affine_en ? 0x0400u : 0u) |
           (uint16_t)(((uint16_t)cfg.pat_idx & 0x0Fu) << 11) |
           (uint16_t)(cfg.enabled ? 0x8000u : 0u);
}

uint16_t sprite_word1(const vdp_mode0_sprite_cfg_t &cfg)
{
    return (uint16_t)(cfg.x & 0x03FFu);
}

uint16_t sprite_word8(const vdp_mode0_sprite_cfg_t &cfg)
{
    return (uint16_t)(((uint16_t)cfg.pat_idx >> 4) & 0x3u) |
           (uint16_t)(cfg.mask ? 0x0010u : 0u) |
           (uint16_t)(((uint16_t)cfg.bpp_sel & 0x3u) << 5) |
           (uint16_t)(cfg.flip_v ? 0x0080u : 0u) |
           (uint16_t)(cfg.flip_h ? 0x0100u : 0u) |
           (uint16_t)(((uint16_t)cfg.prio & 0x3u) << 9) |
           (uint16_t)(((uint16_t)cfg.pal_bank & 0x7u) << 11) |
           (uint16_t)(((uint16_t)cfg.size_sel & 0x3u) << 14);
}

void disable_sprite(uint8_t slot)
{
    vdp_mode0_sprite_cfg_t cfg = {};
    cfg.x = 1023u;
    cfg.y = 1023u;
    cfg.size_sel = 1u;
    vdp_mode0_set_sprite(slot, &cfg);
}

bool program_sprite(uint8_t index, uint8_t slot, bool mask)
{
    const vdp_mode0_sprite_cfg_t cfg = sprite_cfg(index, slot, mask);
    vdp_mode0_set_sprite(slot, &cfg);

    bool ok = true;
    ok &= write_expect((uint16_t)(VDP_MODE0_REG_SPRITE_ATTR_BASE + slot * 8u + 0u),
                       sprite_word0(cfg), "sprite");
    ok &= write_expect((uint16_t)(VDP_MODE0_REG_SPRITE_ATTR_BASE + slot * 8u + 1u),
                       sprite_word1(cfg), "sprite");
    ok &= write_expect((uint16_t)(VDP_MODE0_REG_SPRITE_HARD_BASE + slot),
                       sprite_word8(cfg), "sprite");
    Serial.printf("sprite slot=%u x=%u y=%u bank=%u mask=%u %s\n",
                  slot, cfg.x, cfg.y, cfg.pal_bank, mask ? 1u : 0u,
                  ok ? "PASS" : "FAIL");
    return ok;
}

void upload_palette()
{
    const uint32_t colors[6] = {
        0x00FF2020u, 0x0020D850u, 0x003060FFu,
        0x00F0E040u, 0x00E040E0u, 0x0040E0E0u,
    };

    for (uint8_t bank = 0; bank < 6u; ++bank) {
        const uint32_t rgb = colors[bank];
        vdp_mode0_palette_write_rgb888((uint8_t)(bank * 16u + 1u),
                                       (uint8_t)((rgb >> 16) & 0xFFu),
                                       (uint8_t)((rgb >> 8) & 0xFFu),
                                       (uint8_t)(rgb & 0xFFu));
        vdp_mode0_palette_write_rgb888((uint8_t)(bank * 16u + 2u),
                                       255u, 255u, 255u);
    }
}

}  // namespace

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("ESP32-S3 i80 sprite mask");

    vdp_host_init();
    delay(50);
    build_pattern();
    zero_display();
    upload_palette();
    vdp_sprite_upload(kFirstSlot, g_solid_pattern, 0u, kPatternPixels, nullptr, 0u, 0u, nullptr);

    for (uint8_t s = 4u; s < 32u; ++s) {
        disable_sprite(s);
    }

    bool ok = true;
    for (uint8_t i = 0; i < 6u; ++i) {
        const uint8_t slot = (uint8_t)(kFirstSlot + i);
        ok &= program_sprite(i, slot, slot == kMaskSlot);
    }

    ok &= write_expect(VDP_MODE0_REG_LAYER_ENABLE, kSpriteLayerEnable, "enable");
    delay(40);
    Serial.printf("i80_sprite_mask result=%s last_error=%d\n",
                  ok ? "PASS" : "FAIL", vdp_last_error());
    Serial.println("expected visual: three 16x16 sprites visible; slots after mask are suppressed");
}

void loop()
{
    delay(1000);
}
