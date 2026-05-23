/**
 * esp32s3_scope_fodder.ino — pure continuous 10 MHz square wave on GPIO 5
 * (the SCK pin) for scope signal-integrity measurements. No SPI peripheral
 * involved — just LEDC hardware PWM, 50% duty.
 *
 * Use this to characterize *just the wire*, isolated from any protocol
 * issues. If this signal looks dirty at the Tang Nano end, the problem is
 * physical (wires, ground return, termination); if this signal is clean
 * but the QSPI burst is dirty, the problem is protocol-side.
 *
 * Toggle kFreqHz to sweep other speeds (1 MHz, 5 MHz, 20 MHz, 40 MHz)
 * without recompiling the full QSPI stack.
 */
#include <Arduino.h>

namespace {
constexpr int      kPin         = 5;         // SCK on the S3 → Tang Nano pin 41
constexpr uint32_t kFreqHz      = 5000000u;  // 5 MHz
constexpr uint8_t  kLedcChannel = 0;
constexpr uint8_t  kResolution  = 2;         // 2-bit → 4 duty levels; duty=2 = 50%
}

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.printf("Scope fodder: %u Hz on GPIO %d\n",
                  (unsigned)kFreqHz, kPin);

    ledcSetup(kLedcChannel, kFreqHz, kResolution);
    ledcAttachPin(kPin, kLedcChannel);
    ledcWrite(kLedcChannel, 2);   /* duty = 2/4 = 50% */
}

void loop(void)
{
    delay(1000);
}
