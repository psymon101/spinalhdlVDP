/**
 * esp8266_task2b_sprite32.ino — Task 2b sprite capacity bump proof.
 *
 * Sc2b: verify 32 bus-programmable sprites (slots 4..35).
 *
 * This version reuses libvdp for transport.
 *
 * Board:  NodeMCU 1.0
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>

#define SPRITE_BASE(slot)  (0x0800u + (uint32_t)(slot) * 8u)

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 task2b_sprite32 host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.println(F("programming 32 sprites (slots 4..35)..."));
    for (uint8_t s = 4; s < 36; s++) {
        uint16_t x = 20 + (s - 4) * 16;
        uint16_t y = 100 + (s % 4) * 20;
        vdp_reg_write(SPRITE_BASE(s) + 0, 0x8000u | (y & 0x3FFu)); // enable, pat=0
        vdp_reg_write(SPRITE_BASE(s) + 1, x & 0x3FFu);
    }

    Serial.println(F("task2b init done; idling"));
}

void loop(void)
{
    delay(1000);
}
