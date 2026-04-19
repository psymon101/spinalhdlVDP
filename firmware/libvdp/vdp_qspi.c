/**
 * vdp_qspi.c — Transport layer implementation.
 *
 * Lifted and cleaned from firmware/test_qspi_smoke/test_qspi_smoke.c
 * (Tasks 26, 27, 38a–c, 34). No protocol changes; this is a packaging
 * refactor only. All timing constants and PIO program remain identical
 * to the proven baseline.
 */
#include "vdp_qspi.h"
#include "vdp_platform.h"

#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"

#include "qspi_quad.pio.h"

static uint   s_tx_offset;
static bool   s_initialized = false;
static int    s_last_error = 0;

int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { gpio_put(VDP_PIN_QSPI_CS_N, 0); }
static inline void vdp_cs_deassert(void) { gpio_put(VDP_PIN_QSPI_CS_N, 1); }

static inline uint32_t vdp_pack_bytes(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3)
{
    return ((uint32_t)b0 << 24) | ((uint32_t)b1 << 16) |
           ((uint32_t)b2 << 8)  | (uint32_t)b3;
}

void vdp_qspi_init(void)
{
    if (s_initialized) return;

    /* CS_N as GPIO, held high by default. */
    gpio_init(VDP_PIN_QSPI_CS_N);
    gpio_set_dir(VDP_PIN_QSPI_CS_N, GPIO_OUT);
    gpio_put(VDP_PIN_QSPI_CS_N, 1);

    /* IO0..IO3 + SCK as PIO. */
    for (uint p = VDP_PIN_QSPI_SCK; p <= VDP_PIN_QSPI_IO3; ++p) {
        if (p == VDP_PIN_QSPI_CS_N) continue;
        pio_gpio_init(VDP_QSPI_PIO, p);
    }

    s_tx_offset = pio_add_program(VDP_QSPI_PIO, &qspi_quad_tx_program);
    pio_sm_config c = qspi_quad_tx_program_get_default_config(s_tx_offset);
    sm_config_set_sideset_pins(&c, VDP_PIN_QSPI_SCK);
    sm_config_set_out_pins(&c, VDP_PIN_QSPI_IO0, 4);
    sm_config_set_out_shift(&c, false, true, 32);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_IO0, 4, true);
    uint32_t sys_hz = clock_get_hz(clk_sys);
    float div = (float)sys_hz / ((float)VDP_QSPI_SCK_HZ * 10.0f);
    sm_config_set_clkdiv(&c, div);
    pio_sm_init(VDP_QSPI_PIO, VDP_QSPI_SM_TX, s_tx_offset, &c);
    pio_sm_set_enabled(VDP_QSPI_PIO, VDP_QSPI_SM_TX, true);

    s_last_error = 0;
    s_initialized = true;
}

/* Wait for PIO FIFO empty + OSR drain. Proven 20 µs margin from Task 38c.
 * Exposed as public API (vdp_qspi.h) per Task 42 §4.3 option A — any custom
 * PIO TX burst must call this before CS deassertion or pin-function switch. */
void vdp_pio_wait_sm_idle(void)
{
    while (!pio_sm_is_tx_fifo_empty(VDP_QSPI_PIO, VDP_QSPI_SM_TX)) { /* spin */ }
    sleep_us(20);
}

static inline void vdp_tx_word(uint32_t w)
{
    pio_sm_put_blocking(VDP_QSPI_PIO, VDP_QSPI_SM_TX, w);
}

/* Send N bytes via PIO (N must be a multiple of 4). */
static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        vdp_tx_word(vdp_pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    vdp_pio_wait_sm_idle();
}

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    uint8_t frame[8];
    frame[0] = 0x01;
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >> 8)  & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = 0x01;                     /* LEN = 1 word */
    frame[5] = 0x00;
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);
    vdp_cs_assert();
    vdp_tx_bytes(frame, sizeof frame);
    vdp_cs_deassert();
    sleep_us(10);
}

/* Bit-bang helpers (used by vdp_read_status). High nibble first. */
static void vdp_bitbang_byte(uint8_t val)
{
    uint32_t mask = 0xFu << VDP_PIN_QSPI_IO0;
    gpio_put_masked(mask, (uint32_t)((val >> 4) & 0xF) << VDP_PIN_QSPI_IO0);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put_masked(mask, (uint32_t)(val & 0xF) << VDP_PIN_QSPI_IO0);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
}

static uint8_t vdp_bitbang_read_byte(void)
{
    uint8_t rx = 0;
    uint32_t pins;
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx = (uint8_t)(((pins >> VDP_PIN_QSPI_IO0) & 0xF) << 4);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx |= (uint8_t)((pins >> VDP_PIN_QSPI_IO0) & 0xF);
    return rx;
}

uint32_t vdp_read_status(uint8_t sel)
{
    /* SIO-mode output for header. */
    gpio_set_function(VDP_PIN_QSPI_SCK, GPIO_FUNC_SIO);
    gpio_set_dir(VDP_PIN_QSPI_SCK, GPIO_OUT);
    gpio_put(VDP_PIN_QSPI_SCK, 0);
    for (uint i = 0; i < 4; i++) {
        gpio_set_function(VDP_PIN_QSPI_IO0 + i, GPIO_FUNC_SIO);
        gpio_set_dir(VDP_PIN_QSPI_IO0 + i, GPIO_OUT);
    }

    vdp_cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    for (int i = 0; i < 6; i++) vdp_bitbang_byte(hdr[i]);

    /* 2-edge turnaround, IO[3:0] to input. */
    for (uint i = 0; i < 4; i++) gpio_set_dir(VDP_PIN_QSPI_IO0 + i, GPIO_IN);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);

    uint8_t b0 = vdp_bitbang_read_byte();
    uint8_t b1 = vdp_bitbang_read_byte();
    uint8_t b2 = vdp_bitbang_read_byte();
    uint8_t b3 = vdp_bitbang_read_byte();

    gpio_put(VDP_PIN_QSPI_SCK, 0);
    vdp_cs_deassert();

    /* Restore PIO function for next TX. */
    pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_SCK);
    for (uint i = 0; i < 4; i++) pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_IO0 + i);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_IO0, 4, true);
    sleep_us(10);

    return (uint32_t)b0
         | ((uint32_t)b1 << 8)
         | ((uint32_t)b2 << 16)
         | ((uint32_t)b3 << 24);
}

/* Maximum single-burst SDRAM_WRITE. Larger uploads must use vdp_upload_asset
 * which chunks into vblank-paced pieces. */
#define VDP_SDRAM_WRITE_MAX_WORDS  253   /* 6 hdr + 506 payload = 512 byte frame */

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (num_words == 0 || num_words > VDP_SDRAM_WRITE_MAX_WORDS) {
        s_last_error = 2;   /* out-of-range LEN */
        return;
    }
    uint8_t frame[512];
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x02;                     /* SDRAM_WRITE */
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >> 8)  & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)( num_words       & 0xFF);
    frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)( words[i]       & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    while (n & 3) { frame[n++] = 0x00; }   /* pad to word boundary */
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
    sleep_us(10);
}
