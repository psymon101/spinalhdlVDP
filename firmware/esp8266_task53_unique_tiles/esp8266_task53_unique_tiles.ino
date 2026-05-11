/**
 * esp8266_task53_unique_tiles.ino — Task 53 unique tile attribute proof.
 *
 * Sc53: verify expanded tile pattern address width.
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
    Serial.println(F("ESP8266 task53_unique_tiles host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);

    // Sc53 state is driven by FPGA side
    Serial.println(F("task53 init done; idling"));
}

void loop(void)
{
    delay(1000);
}
