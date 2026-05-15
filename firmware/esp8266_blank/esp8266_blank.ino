/**
 * esp8266_blank.ino — minimal blank host sketch.
 *
 * Purpose:
 *   Prove whether the display changes when the ESP8266 stops issuing VDP
 *   traffic entirely after boot.
 *
 * Board: NodeMCU 1.0 (ESP-12E)
 * FQBN:  esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>

void setup(void)
{
    Serial.begin(115200);
    delay(100);
    Serial.println();
    Serial.println(F("ESP8266 blank sketch — no VDP traffic"));
}

void loop(void)
{
    delay(1000);
}
