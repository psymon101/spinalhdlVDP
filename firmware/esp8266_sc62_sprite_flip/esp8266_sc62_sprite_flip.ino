/**
 * esp8266_sc62_sprite_flip.ino — Task 52 sprite-flip HW proof host sketch
 * (ESP8266 / NodeMCU port).
 *
 * Sc62: upload one asymmetric 16×16 4bpp sprite pattern into pattern slot 0.
 *
 * This version reuses libvdp for transport.
 *
 * Board:  NodeMCU 1.0 (ESP-12E module)
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>

// Asymmetric L-shape: col 0 OR row 15 → palette index 1, elsewhere → 0.
static inline uint8_t pattern_pixel(uint8_t row, uint8_t col)
{
    return (col == 0 || row == 15) ? 0x1 : 0x0;
}

static void sc62_upload_pattern(void)
{
    Serial.println(F("sc62: uploading 16x16 4bpp asymmetric-L pattern to slot 0..."));

    vdp_reg_write(0x0D11u, 0x0000u);   // pointer = 0 (pattern slot 0, pixel 0)

    for (uint8_t row = 0; row < 16; ++row) {
        for (uint8_t col = 0; col < 16; ++col) {
            vdp_reg_write(0x0D10u, (uint16_t)pattern_pixel(row, col));
        }
    }

    Serial.println(F("sc62: pattern uploaded; sprite descriptors are configured by copper"));
}

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 sc62 sprite-flip host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);
    sc62_upload_pattern();
    Serial.println(F("sc62 init done; idling (sprite descriptors driven by copper)"));
}

void loop(void)
{
    delay(1000);
}
