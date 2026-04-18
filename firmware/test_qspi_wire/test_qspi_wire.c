/**
 * test_qspi_wire.c — QSPI one-hot wire self-test (Task 26 debug).
 *
 * Per BronzeGate #7508. Bounded wiring diagnostic. Pico drives GP8..GP11
 * with a walking one-hot pattern as PLAIN GPIO (no PIO, no QSPI framing).
 * FPGA samples the four inputs and reports the observed 4-bit vector via
 * LEDs (see TopTang20kHdmi.scala scenarioId=99 path).
 *
 * Expected wiring:
 *   GP8  -> Tang pin 41 (I_qspi_sck)
 *   GP9  -> Tang pin 42 (I_qspi_cs)
 *   GP10 -> Tang pin 48 (I_qspi_io0)
 *   GP11 -> Tang pin 49 (I_qspi_io1)
 *   GND  -> Tang GND
 *
 * Sequence (1 s per state, cycles forever):
 *   step 0: all low       0000
 *   step 1: SCK high only 1000  — LED0 should light
 *   step 2: CS  high only 0100  — LED1 should light
 *   step 3: IO0 high only 0010  — LED2 should light
 *   step 4: IO1 high only 0001  — LED3 should light
 *
 * Pass = observed LED lights exactly one bit matching the driven one-hot.
 * Failure interpretation:
 *   wrong LED lit        → wires crossed
 *   no LED lit           → open connection or wrong Tang pin
 *   multiple LEDs lit    → short / crosstalk
 *   any LED always lit   → Tang-side pull-up / stuck input (check CST PULL_MODE)
 *
 * This is a throwaway diagnostic — not part of the final Task 26 build.
 */
#include "pico/stdlib.h"
#include "hardware/gpio.h"

#define PIN_SCK  8
#define PIN_CS   9
#define PIN_IO0  10
#define PIN_IO1  11

static inline void drive(int sck, int cs, int io0, int io1)
{
    gpio_put(PIN_SCK, sck);
    gpio_put(PIN_CS,  cs);
    gpio_put(PIN_IO0, io0);
    gpio_put(PIN_IO1, io1);
}

int main(void)
{
    stdio_init_all();
    for (uint p = PIN_SCK; p <= PIN_IO1; ++p) {
        gpio_init(p);
        gpio_set_dir(p, GPIO_OUT);
        gpio_put(p, 0);
    }
    /* Pico onboard LED can mirror the cycle so we know the firmware is alive. */
    gpio_init(PICO_DEFAULT_LED_PIN);
    gpio_set_dir(PICO_DEFAULT_LED_PIN, GPIO_OUT);

    /* Give the FPGA a moment to finish bootstrap + Tang pullups to settle. */
    sleep_ms(500);

    uint8_t heartbeat = 0;
    while (true) {
        /* Step 0: all low (should see 0000 on LEDs) */
        drive(0, 0, 0, 0);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(1000);

        /* Step 1: SCK high only */
        drive(1, 0, 0, 0);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(1000);

        /* Step 2: CS high only */
        drive(0, 1, 0, 0);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(1000);

        /* Step 3: IO0 high only */
        drive(0, 0, 1, 0);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(1000);

        /* Step 4: IO1 high only */
        drive(0, 0, 0, 1);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(1000);
    }
}
