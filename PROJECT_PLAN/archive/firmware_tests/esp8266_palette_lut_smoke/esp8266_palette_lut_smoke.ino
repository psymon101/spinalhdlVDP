/**
 * esp8266_palette_lut_smoke.ino — Smoke-test the per-platform palette LUT helpers.
 *
 * Exercises vdp_tms9918_load_palette, vdp_sms_palette_write,
 * vdp_gg_palette_write, vdp_atarist_palette_write, and
 * vdp_atariste_palette_write.  No visible output required; this is a
 * compile-and-link proof.
 *
 * Board:  NodeMCU 1.0
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_palette_lut.h>

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 palette LUT smoke test — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.println(F("Loading TMS9918A fixed palette into entries 0..15..."));
    vdp_tms9918_load_palette();

    Serial.println(F("Writing SMS CRAM color 0x3F (--111111) to entry 16..."));
    vdp_sms_palette_write(16, 0x3F);   /* max white in SMS 6-bit */

    Serial.println(F("Writing GG CRAM color 0x0FFF to entry 17..."));
    vdp_gg_palette_write(17, 0x0FFF);  /* max white in GG 12-bit */

    Serial.println(F("Writing Atari ST palette word 0x0777 to entry 18..."));
    vdp_atarist_palette_write(18, 0x0777); /* max white in ST 9-bit */

    Serial.println(F("Writing Atari STE palette word 0x0FFF to entry 19..."));
    vdp_atariste_palette_write(19, 0x0FFF); /* max white in STE 12-bit */

    Serial.println(F("Smoke test complete."));
}

void loop(void)
{
    delay(1000);
}
