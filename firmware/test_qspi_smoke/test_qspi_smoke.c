/**
 * test_qspi_smoke.c — Minimal QSPI smoke test for spinalhdlVDP Checkpoint C.
 *
 * Runs on a Raspberry Pi Pico 2 (RP2350). Drives the Tang Nano 20K QSPI
 * slave via PIO, sending a REG_WRITE every ~500 ms that toggles the VDP
 * LAYER_ENABLE register between 0x0005 (L0 + sprite) and 0x0007 (L0 + L1
 * + sprite). The visible layer toggle on the HDMI output is the hardware
 * pass criterion for Checkpoint C.
 *
 * Pin map (Pico GPn → Tang pin, per QSPI_HOST_CONTROL_PLAN.md §2):
 *   GP8  = SCK  → Tang pin 41
 *   GP9  = CS_N → Tang pin 42
 *   GP10 = IO0  → Tang pin 48
 *   GP11 = IO1  → Tang pin 49
 *   (GP12/GP13 = IO2/IO3 — unused, Tang ties them low internally)
 *
 * Packet format (plan §3.1): 6-byte header [CMD:1][ADDR:3][LEN:2] +
 * little-endian 16-bit payload words. CMD=0x01 is REG_WRITE.
 */
#include <stdio.h>
#include <stdint.h>
#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"

#include "qspi_quad.pio.h"

#define PIN_QSPI_SCK   8
#define PIN_QSPI_CS_N  9
#define PIN_QSPI_IO0   10
#define PIN_QSPI_IO1   11
#define PIN_QSPI_IO2   12     /* unused on Tang in lane 1; still driven by PIO */
#define PIN_QSPI_IO3   13

#define QSPI_PIO       pio0
#define QSPI_SM_TX     0

#define QSPI_SCK_HZ    8000000u    /* 8 MHz — safe margin for 74.25 MHz oversampling */

static uint tx_offset;

static inline void cs_assert(void)   { gpio_put(PIN_QSPI_CS_N, 0); }
static inline void cs_deassert(void) { gpio_put(PIN_QSPI_CS_N, 1); }

/* Pack 4 bytes into 32 bits MSB-first — matches qspi_quad_tx shift layout. */
static inline uint32_t pack_bytes(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3)
{
    return ((uint32_t)b0 << 24) | ((uint32_t)b1 << 16) |
           ((uint32_t)b2 << 8)  | (uint32_t)b3;
}

static void qspi_hw_init(void)
{
    /* CS_N as GPIO, held high by default. */
    gpio_init(PIN_QSPI_CS_N);
    gpio_set_dir(PIN_QSPI_CS_N, GPIO_OUT);
    gpio_put(PIN_QSPI_CS_N, 1);

    /* IO0..IO3 + SCK as PIO. */
    for (uint p = PIN_QSPI_SCK; p <= PIN_QSPI_IO3; ++p) {
        if (p == PIN_QSPI_CS_N) continue;
        pio_gpio_init(QSPI_PIO, p);
    }

    tx_offset = pio_add_program(QSPI_PIO, &qspi_quad_tx_program);
    pio_sm_config c = qspi_quad_tx_program_get_default_config(tx_offset);

    /* side-set pin = SCK */
    sm_config_set_sideset_pins(&c, PIN_QSPI_SCK);
    /* OUT pins = IO0..IO3 (4 consecutive) */
    sm_config_set_out_pins(&c, PIN_QSPI_IO0, 4);
    /* autopull 32 bits, MSB-first */
    sm_config_set_out_shift(&c, false, true, 32);

    /* Set pin directions: SCK + IO0..IO3 all outputs. */
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_IO0, 4, true);

    /* Clock divider for SCK. Each nibble = 5 cycles (out+nop with [1] delay),
     * so one byte = 10 cycles. SCK frequency = sys_clk / (div * 2 * 5).
     * For 125 MHz sys_clk and 8 MHz SCK: div = 125e6 / (8e6 * 2 * 5) = 1.5625. */
    uint32_t sys_hz = clock_get_hz(clk_sys);
    float div = (float)sys_hz / (QSPI_SCK_HZ * 10.0f);
    sm_config_set_clkdiv(&c, div);

    pio_sm_init(QSPI_PIO, QSPI_SM_TX, tx_offset, &c);
    pio_sm_set_enabled(QSPI_PIO, QSPI_SM_TX, true);
}

/* Send 4 bytes through PIO as one 32-bit word. */
static inline void qspi_tx_word(uint32_t w)
{
    pio_sm_put_blocking(QSPI_PIO, QSPI_SM_TX, w);
}

/* Send N bytes (must be multiple of 4 for simplicity). */
static void qspi_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        qspi_tx_word(pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    /* Drain: wait until TX FIFO is empty and SM is idle. */
    while (!pio_sm_is_tx_fifo_empty(QSPI_PIO, QSPI_SM_TX)) { /* spin */ }
    sleep_us(2);
}

static void reg_write_word(uint32_t addr, uint16_t data)
{
    /* 8-byte transaction: 6-byte header + 2-byte payload (one 16-bit word).
     * LEN is in 16-bit words per plan §3.1. */
    uint8_t frame[8];
    frame[0] = 0x01;                         /* CMD = REG_WRITE */
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >> 8)  & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = 0x01;                         /* LEN low = 1 word */
    frame[5] = 0x00;                         /* LEN high = 0 */
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);
    cs_assert();
    qspi_tx_bytes(frame, sizeof frame);
    cs_deassert();
    sleep_us(10);
}

int main(void)
{
    stdio_init_all();
    qspi_hw_init();

    /* Brief settle period for Tang bitstream to finish its bootstrap. */
    sleep_ms(200);

    bool on = true;
    while (true) {
        /* Toggle LAYER_ENABLE between 0x0005 (L0+sprite) and 0x0000
         * (all layers off). On HDMI: the entire screen goes to black
         * backdrop when off, normal Sc16 scene when on — a dramatic
         * visible toggle that works regardless of L1 configuration. */
        reg_write_word(0x0300, on ? 0x0005 : 0x0000);
        on = !on;
        sleep_ms(500);
    }
}
