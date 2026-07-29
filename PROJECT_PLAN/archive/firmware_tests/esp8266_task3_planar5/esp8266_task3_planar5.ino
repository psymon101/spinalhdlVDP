/**
 * esp8266_task3_planar5.ino — Task 3 planar fetch hardening proof.
 *
 * Sc3: verify 5-plane planar layer (L0).
 *
 * This version reuses libvdp for transport.
 *
 * Board:  NodeMCU 1.0
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 task3_planar5 host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);

    vdp_reg_write(0x0311u, 0x0001u); // TILE_MODE = planar
    vdp_reg_write(0x0300u, 0x0001u); // LAYER_ENABLE = L0
    
    Serial.println(F("task3 init done; idling"));
}

void loop(void)
{
    delay(1000);
}
