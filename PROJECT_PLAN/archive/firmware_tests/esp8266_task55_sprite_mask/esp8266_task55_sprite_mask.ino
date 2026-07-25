/**
 * esp8266_task55_sprite_mask.ino — Task 55 Checkpoint C HW proof for ESP8266.
 *
 * 1. Genesis sprite masking (word 8 bit [4]) suppresses lower-priority
 *    sprites on the masked scanline.
 * 2. SNES tile-fetch budget overflow (TileBudget=34) trips when a
 *    single line carries 35 tiles.
 *
 * This version reuses libvdp for transport.
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>

#define SPRITE_BASE(slot)  (0x0800u + (uint32_t)(slot) * 8u)
#define SPRITE_W8_BASE     0x0D20u

// Word 0: enabled[15], patIdx[3:0]@[14:11], affineEnable[10], y[9:0]
static inline uint16_t sprite_word0(bool enabled, uint8_t patIdxLow, uint16_t y)
{
    return (enabled ? 0x8000u : 0x0000u)
         | ((uint16_t)(patIdxLow & 0xF) << 11)
         | (y & 0x3FFu);
}

// Word 1: x[9:0]
static inline uint16_t sprite_word1(uint16_t x) { return x & 0x3FFu; }

// Word 8 (Task 55 layout):
//   sizeSel[15:14], paletteBank[13:11], priority[10:9],
//   flipH[8], flipV[7], bppSel[6:5], mask[4], _[3:2], patIdx[1:0]
static inline uint16_t sprite_word8(uint8_t sizeSel, uint8_t paletteBank,
                                    uint8_t priority, bool flipH, bool flipV,
                                    uint8_t bppSel, bool mask, uint8_t patIdxHigh)
{
    return ((uint16_t)(sizeSel     & 0x3) << 14)
         | ((uint16_t)(paletteBank & 0x7) << 11)
         | ((uint16_t)(priority    & 0x3) <<  9)
         | (flipH ? (1u << 8) : 0u)
         | (flipV ? (1u << 7) : 0u)
         | ((uint16_t)(bppSel      & 0x3) <<  5)
         | (mask  ? (1u << 4) : 0u)
         | ((uint16_t)(patIdxHigh  & 0x3));
}

static void program_sprite(uint8_t slot, uint16_t x, uint16_t y,
                           uint8_t patIdx6, uint8_t sizeSel,
                           uint8_t paletteBank, uint8_t priority,
                           bool mask)
{
    const uint8_t low  = patIdx6 & 0x0F;
    const uint8_t high = (patIdx6 >> 4) & 0x03;

    vdp_reg_write(SPRITE_BASE(slot) + 0u, sprite_word0(true, low, y));
    vdp_reg_write(SPRITE_BASE(slot) + 1u, sprite_word1(x));
    vdp_reg_write(SPRITE_W8_BASE + slot,
                  sprite_word8(sizeSel, paletteBank, priority,
                               /*flipH*/false, /*flipV*/false,
                               /*bppSel*/0, mask, high));
}

static void disable_sprite(uint8_t slot)
{
    vdp_reg_write(SPRITE_BASE(slot) + 0u, sprite_word0(false, 0, 1023));
    vdp_reg_write(SPRITE_BASE(slot) + 1u, sprite_word1(1023));
    vdp_reg_write(SPRITE_W8_BASE + slot, 0);
}

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 task55_sprite_mask host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.println(F("disabling slots 4..47..."));
    for (uint8_t s = 4; s < 48; ++s) {
        disable_sprite(s);
    }

    Serial.println(F("programming mask band @ Y=200 — slot 4 mask=1, slots 5/6 opaque"));
    program_sprite(4, 80, 200, 0, 1, 0, 2, true);
    program_sprite(5, 200, 200, 0, 1, 0, 2, false);
    program_sprite(6, 400, 200, 0, 1, 0, 2, false);

    Serial.println(F("programming reference band @ Y=100 — slot 7 (no mask)"));
    program_sprite(7, 320, 100, 0, 1, 0, 2, false);

    Serial.println(F("programming overflow band @ Y=300 — 35 tiles"));
    program_sprite(8, 0, 300, 0, 2, 0, 2, false);
    program_sprite(9, 64, 300, 0, 1, 0, 2, false);
    program_sprite(10, 96, 300, 0, 1, 0, 2, false);
    program_sprite(11, 128, 300, 0, 1, 0, 2, false);
    program_sprite(12, 160, 300, 0, 1, 0, 2, false);
    program_sprite(13, 200, 300, 0, 0, 0, 2, false);
    program_sprite(14, 216, 300, 0, 0, 0, 2, false);
    program_sprite(15, 232, 300, 0, 0, 0, 2, false);

    Serial.println(F("setup complete; static scene running"));
}

void loop(void)
{
    delay(1000);
}
