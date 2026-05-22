/**
 * esp8266_asset_upload.ino — generated-asset upload template.
 *
 * This sketch shows the shortest path from host-side PNG conversion to the
 * ESP8266 firmware:
 *   1. Convert PNG assets with scripts/assets/png_to_vdp_assets.py
 *   2. Convert the raw .bin payload with scripts/assets/bin_to_c_array.py
 *   3. Include the generated headers and upload the payload with libvdp
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_upload.h>

#include "asset_demo_meta.h"
#include "asset_demo_tiles.h"

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 asset-upload template — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.println(F("Uploading generated tile payload..."));
    vdp_upload_asset(ASSET_DEMO_SDRAM_BASE,
                     asset_demo_tiles,
                     ASSET_DEMO_TILES_WORD_COUNT,
                     NULL);
    Serial.println(F("Upload complete; idling"));
}

void loop(void)
{
    delay(1000);
}
