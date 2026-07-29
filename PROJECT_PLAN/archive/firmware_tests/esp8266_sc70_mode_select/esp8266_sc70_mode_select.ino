/**
 * esp8266_sc70_mode_select.ino — Task 51 MODE_SELECT proof host sketch.
 *
 * Sc70: verifies runtime switching between C64 and ZX Spectrum modes.
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
    Serial.println(F("ESP8266 sc70 mode-select host (libvdp version) — booting"));

    vdp_qspi_init();
    delay(200);

    // sc70 copper program and register state is handled by the FPGA side
    // for this scenario; the host just provides the transport heartbeat.
    Serial.println(F("sc70 init done; idling"));
}

void loop(void)
{
    delay(1000);
}
