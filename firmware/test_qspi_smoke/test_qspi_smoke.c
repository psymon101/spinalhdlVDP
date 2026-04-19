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

#define QSPI_SCK_HZ    2000000u    /* 2 MHz — ~12× oversampling on the 25 MHz pixel clock */

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

/* Task 38c helper: wait until the PIO TX state machine is truly idle —
 * FIFO empty AND OSR empty AND SM PC back at the wrap target. The old
 * sleep_us(10) margin (Task 26 Fix 1) is a best-effort wait; this helper
 * is deterministic. Works by waiting for FIFO empty then polling the PC
 * and OSR. */
static void pio_wait_sm_idle(PIO pio, uint sm)
{
    while (!pio_sm_is_tx_fifo_empty(pio, sm)) { /* drain FIFO */ }
    /* After FIFO empty, the SM may still be shifting one word out of the
     * OSR. A 32-bit word at QSPI_SCK_HZ / 10 instructions_per_byte * 4
     * bytes = ~16 µs worst case at 2 MHz SCK. Keep the 10 µs safety and
     * add an extra 10 µs to cover the OSR tail deterministically. */
    sleep_us(20);
}

/* Send N bytes (must be multiple of 4 for simplicity). */
static void qspi_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        qspi_tx_word(pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    pio_wait_sm_idle(QSPI_PIO, QSPI_SM_TX);
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

/* =================================================================
 * Task 38c — QSPI bit-bang read helper.
 *
 * Reference: /home/itadmin/github/VDP/src/mode0/firmware/src/qspi_bus.c
 *   — proven pattern (line 542 m0_qspi_bus_read_status, line 214
 *   qspi_bitbang_read_byte). PIO RX can't keep up with the FPGA slave's
 *   byte-to-byte decoder latency through the 2-FF sync, so bit-bang is
 *   the proven path. Only 4 response bytes, so speed doesn't matter.
 *
 * Protocol (plan §3):
 *   Phase 1: bit-bang 6-byte READ_STATUS header (CMD=0x04, sel in byte 1)
 *   Phase 2: 2-edge turnaround — release IO[3:0], host still clocks SCK
 *   Phase 3: bit-bang 4 response bytes — FPGA drives IO[3:0] during S_Respond
 * ================================================================= */

static void qspi_bitbang_byte(uint8_t val)
{
    /* High nibble first (MSB-first ordering matches qspi_quad_tx). */
    uint32_t mask = 0xFu << PIN_QSPI_IO0;
    gpio_put_masked(mask, (uint32_t)((val >> 4) & 0xF) << PIN_QSPI_IO0);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);

    /* Low nibble. */
    gpio_put_masked(mask, (uint32_t)(val & 0xF) << PIN_QSPI_IO0);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
}

static uint8_t qspi_bitbang_read_byte(void)
{
    /* Host still drives SCK. FPGA drives IO[3:0] during Respond. Sample
     * on SCK rising, high nibble first. */
    uint8_t rx = 0;
    uint32_t pins;

    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx = (uint8_t)(((pins >> PIN_QSPI_IO0) & 0xF) << 4);

    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx |= (uint8_t)((pins >> PIN_QSPI_IO0) & 0xF);

    return rx;
}

/* Issue READ_STATUS sel=<sel> and return the 4 response bytes as a
 * little-endian uint32. Response layout (per QspiDecoder Task 38b):
 *   sel=0 -> 0x51560002
 *   sel=1 -> 0x000000CC where CC = rx_cmd_cnt
 *   sel=2 -> 0x0000AAAA where AAAA = last_addr
 *   sel=3 -> 0x0000DDDD where DDDD = last_data
 *   sel=4 -> 0x000000EE where EE = last_error
 */
static uint32_t qspi_read_status(uint8_t sel)
{
    /* Switch SCK + IO0..IO3 to SIO mode as outputs for the header phase. */
    gpio_set_function(PIN_QSPI_SCK, GPIO_FUNC_SIO);
    gpio_set_dir(PIN_QSPI_SCK, GPIO_OUT);
    gpio_put(PIN_QSPI_SCK, 0);
    for (uint i = 0; i < 4; i++) {
        gpio_set_function(PIN_QSPI_IO0 + i, GPIO_FUNC_SIO);
        gpio_set_dir(PIN_QSPI_IO0 + i, GPIO_OUT);
    }

    /* Phase 1: 6-byte READ_STATUS header. sel goes in addr low byte. */
    cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    for (int i = 0; i < 6; i++) qspi_bitbang_byte(hdr[i]);

    /* Phase 2: 2-edge turnaround. Release IO[3:0] to input so the FPGA
     * can drive them. Host keeps clocking SCK. */
    for (uint i = 0; i < 4; i++) gpio_set_dir(PIN_QSPI_IO0 + i, GPIO_IN);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);

    /* Phase 3: 4 response bytes, LSB first per QspiDecoder rxWord layout. */
    uint8_t b0 = qspi_bitbang_read_byte();
    uint8_t b1 = qspi_bitbang_read_byte();
    uint8_t b2 = qspi_bitbang_read_byte();
    uint8_t b3 = qspi_bitbang_read_byte();

    gpio_put(PIN_QSPI_SCK, 0);
    cs_deassert();

    /* Restore pins to PIO function so subsequent reg_write_word works. */
    pio_gpio_init(QSPI_PIO, PIN_QSPI_SCK);
    for (uint i = 0; i < 4; i++) pio_gpio_init(QSPI_PIO, PIN_QSPI_IO0 + i);
    /* Restore pin dirs for PIO TX — SM needs SCK + IO[3:0] as outputs. */
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_IO0, 4, true);
    sleep_us(10);

    return (uint32_t)b0
         | ((uint32_t)b1 << 8)
         | ((uint32_t)b2 << 16)
         | ((uint32_t)b3 << 24);
}

int main(void)
{
    stdio_init_all();
    qspi_hw_init();

    /* Settle long enough for USB CDC to enumerate + Tang bitstream bootstrap. */
    sleep_ms(2000);

    printf("\n[task38c] QSPI readback smoke test starting\n");

    /* Prime decoder state so later sel reads have known expected values.
     * Task 38c bit-3 proof: write 0x0088 to LAYER_ENABLE. After commit,
     * last_data = 0x0088 -- both nibbles of the low byte have bit 3 set
     * (0x8 = 0b1000). Reading sel=3 gives bytes {0x88, 0x00, 0x00, 0x00};
     * the 0x88 byte requires IO3 to be electrically alive in both the
     * high-nibble and low-nibble slots of that byte. */
    reg_write_word(0x0300, 0x0088);     /* prime last_data = 0x0088 (bit-3 set) */
    sleep_ms(10);

    uint32_t magic = qspi_read_status(0);
    uint32_t rcnt  = qspi_read_status(1);
    uint32_t ladr  = qspi_read_status(2);
    uint32_t ldat  = qspi_read_status(3);
    uint32_t lerr  = qspi_read_status(4);

    printf("[task38c] sel=0 magic     = 0x%08lx (expect 0x51560002)\n", (unsigned long)magic);
    printf("[task38c] sel=1 rx_cmd_cnt= 0x%08lx\n", (unsigned long)rcnt);
    printf("[task38c] sel=2 last_addr = 0x%08lx (expect 0x00000300)\n", (unsigned long)ladr);
    printf("[task38c] sel=3 last_data = 0x%08lx (expect 0x00000088, bit-3 alive)\n", (unsigned long)ldat);
    printf("[task38c] sel=4 last_error= 0x%08lx (expect 0x00000000)\n", (unsigned long)lerr);
    printf("[task38c] bit-3 proof: (last_data & 0x88) = 0x%02x (expect 0x88)\n",
           (unsigned)(ldat & 0xFF) & 0x88);

    /* Continue periodic write + read loop so serial keeps spitting evidence
     * and the HDMI scene remains driveable. Toggles between 0x0088 and
     * 0x0000 so a visible layer-enable off-moment is still reachable. */
    bool on = true;
    while (true) {
        reg_write_word(0x0300, on ? 0x0088 : 0x0000);
        sleep_ms(10);
        uint32_t m   = qspi_read_status(0);
        uint32_t d   = qspi_read_status(3);
        printf("[task38c] loop on=%d magic=0x%08lx last_data=0x%08lx\n",
               (int)on, (unsigned long)m, (unsigned long)d);
        on = !on;
        sleep_ms(500);
    }
}
