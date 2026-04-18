/**
 * test_qspi_wire_read.c — Reverse-direction QSPI wire-test reader.
 *
 * FPGA drives the four Tang QSPI pins as OUTPUTS with a 1-second walking
 * one-hot (see TopTang20kWireRev.scala).  This firmware reads Pico
 * GP8..GP11 as inputs with internal pull-downs and prints the sampled
 * 4-bit vector over USB serial every 200 ms.
 *
 * Expected wiring (same physical pins as the main QSPI path):
 *   Tang pin 41 (O_qspi_sck) -> Pico GP8
 *   Tang pin 42 (O_qspi_cs ) -> Pico GP9
 *   Tang pin 48 (O_qspi_io0) -> Pico GP10
 *   Tang pin 49 (O_qspi_io1) -> Pico GP11
 *   GND shared.
 *
 * Expected USB serial output (one line every 200 ms, hand-assembled):
 *   step 0 (all low)  : "wire: GP8=0 GP9=0 GP10=0 GP11=0"
 *   step 1 (SCK  high): "wire: GP8=1 GP9=0 GP10=0 GP11=0"
 *   step 2 (CS   high): "wire: GP8=0 GP9=1 GP10=0 GP11=0"
 *   step 3 (IO0  high): "wire: GP8=0 GP9=0 GP10=1 GP11=0"
 *   step 4 (IO1  high): "wire: GP8=0 GP9=0 GP10=0 GP11=1"
 * Host reads `/dev/ttyACM*` and verifies the expected pattern cycles.
 *
 * Pull-down is critical: any line that's actually floating (open circuit)
 * will read 0 with PULL_DOWN, not garbage, so a missing wire shows up as
 * a stuck-low observation instead of oscillating noise.
 */
#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"

#define PIN_SCK  8
#define PIN_CS   9
#define PIN_IO0  10
#define PIN_IO1  11

int main(void)
{
    stdio_init_all();
    /* Small delay so USB CDC is enumerated before we spam output. */
    sleep_ms(2000);

    for (uint p = PIN_SCK; p <= PIN_IO1; ++p) {
        gpio_init(p);
        gpio_set_dir(p, GPIO_IN);
        gpio_pull_down(p);    /* no float; open input -> reads 0 */
    }

    gpio_init(PICO_DEFAULT_LED_PIN);
    gpio_set_dir(PICO_DEFAULT_LED_PIN, GPIO_OUT);

    uint32_t heartbeat = 0;
    while (true) {
        bool sck = gpio_get(PIN_SCK);
        bool cs  = gpio_get(PIN_CS);
        bool io0 = gpio_get(PIN_IO0);
        bool io1 = gpio_get(PIN_IO1);
        printf("wire: GP8=%d GP9=%d GP10=%d GP11=%d\n", sck, cs, io0, io1);
        gpio_put(PICO_DEFAULT_LED_PIN, (heartbeat++ & 1));
        sleep_ms(200);
    }
}
