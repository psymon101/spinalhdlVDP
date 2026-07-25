/**
 * esp32s3_qspi_smoke.ino — QSPI link sanity test for ESP32-S3 + Tang Nano 20K.
 *
 * Reads the FPGA magic word every second and prints it. A passing link
 * returns exactly 0x51560002. Any other value means the link is broken;
 * the failure pattern tells us the failure mode.
 *
 * Pin map (libvdp/vdp_platform.h S3 branch, side-A header):
 *   GPIO 4 = CS#, 5 = SCK, 6 = IO0, 7 = IO1, 15 = IO2, 16 = IO3
 *
 * Backend: hardware SPI2 quad mode @ VDP_QSPI_SCK_HZ (40 MHz default).
 * Drop to 10 MHz in vdp_platform.h if smoke fails to isolate signal
 * integrity from protocol.
 */
#include <Arduino.h>
#include <vdp_qspi.h>

namespace {
constexpr uint32_t kExpectedMagic = 0x51560002u;
}

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("=== esp32s3 QSPI smoke ===");
    Serial.print("Expecting magic 0x");
    Serial.println(kExpectedMagic, HEX);

    vdp_qspi_init();
    delay(50);
}

void loop(void)
{
    const uint32_t magic    = vdp_read_status(0);
    const uint32_t rx_cnt   = vdp_read_status(1);
    const uint32_t last_addr = vdp_read_status(2);
    const uint32_t last_data = vdp_read_status(3);

    Serial.print("magic=0x");
    Serial.print(magic, HEX);
    Serial.print(magic == kExpectedMagic ? " [PASS]" : " [FAIL]");
    Serial.print("  rx_cnt=");
    Serial.print(rx_cnt);
    Serial.print("  last_addr=0x");
    Serial.print(last_addr, HEX);
    Serial.print("  last_data=0x");
    Serial.println(last_data, HEX);

    delay(10);  /* 100 Hz polling — gives the scope a steady trigger source */
}
