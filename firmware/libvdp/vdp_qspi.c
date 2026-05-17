/**
 * vdp_qspi.c — Transport layer implementation.
 */
#include "vdp_qspi.h"
#include "vdp_platform.h"

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"
#include "qspi_quad.pio.h"
#endif

static bool   s_initialized = false;
static int    s_last_error = 0;

int vdp_last_error(void) { return s_last_error; }

// ---- Pico 2 / RP2350 PIO Implementation -------------------------------------
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)

static uint   s_tx_offset;

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

void vdp_pio_wait_sm_idle(void)
{
    while (!pio_sm_is_tx_fifo_empty(VDP_QSPI_PIO, VDP_QSPI_SM_TX)) { /* spin */ }
    sleep_us(20);
}

static inline void vdp_tx_word(uint32_t w)
{
    pio_sm_put_blocking(VDP_QSPI_PIO, VDP_QSPI_SM_TX, w);
}

static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        vdp_tx_word(vdp_pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    vdp_pio_wait_sm_idle();
}

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

    pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_SCK);
    for (uint i = 0; i < 4; i++) pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_IO0 + i);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_IO0, 4, true);
    sleep_us(10);

    return (uint32_t)b0 | ((uint32_t)b1 << 8) | ((uint32_t)b2 << 16) | ((uint32_t)b3 << 24);
}

// ---- ESP32 / ESP8266 Arduino Bit-bang Implementation -------------------------
#elif defined(ARDUINO)

static inline void vdp_cs_assert(void)   { digitalWrite(VDP_PIN_QSPI_CS_N, LOW); }
static inline void vdp_cs_deassert(void) { digitalWrite(VDP_PIN_QSPI_CS_N, HIGH); }

#if defined(ESP32)
#define HALF_PERIOD_US 1
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
#define MASK_IO3    (1u << VDP_PIN_QSPI_IO3)
#define MASK_IO_ALL (MASK_IO0 | MASK_IO1 | MASK_IO2 | MASK_IO3)

static inline void vdp_drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    if (n & 0x8) set |= MASK_IO3;
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_IO_ALL);
    if (set) REG_WRITE(GPIO_OUT_W1TS_REG, set);
}

static inline void vdp_set_sck(bool high)
{
    REG_WRITE(high ? GPIO_OUT_W1TS_REG : GPIO_OUT_W1TC_REG, MASK_SCK);
}

static inline uint8_t vdp_read_nibble(void)
{
    uint32_t pins = REG_READ(GPIO_IN_REG);
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1;
    if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4;
    if (pins & MASK_IO3) n |= 0x8;
    return n;
}

#elif defined(ESP8266)
/* ESP8266 uses GPIO16 for IO3, which is driven/read through the slower
 * RTC pad path. Give the nibble more settle margin before clock edges. */
#define HALF_PERIOD_US 4
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
/* IO3 (GPIO16) is RTC pad, no fast mask access. */
#define MASK_IO_LOW (MASK_IO0 | MASK_IO1 | MASK_IO2)

static inline void vdp_drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW;
    if (set) GPOS = set;
    digitalWrite(VDP_PIN_QSPI_IO3, (n & 0x8) ? HIGH : LOW);
}

static inline void vdp_set_sck(bool high)
{
    if (high) GPOS = MASK_SCK; else GPOC = MASK_SCK;
}

static inline uint8_t vdp_read_nibble(void)
{
    uint32_t pins = GPI;
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1;
    if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4;
    if (digitalRead(VDP_PIN_QSPI_IO3)) n |= 0x8;
    return n;
}
#endif

static void vdp_send_nibble(uint8_t n)
{
    vdp_drive_nibble(n);
    vdp_set_sck(false);
    delayMicroseconds(HALF_PERIOD_US);
    vdp_set_sck(true);
    delayMicroseconds(HALF_PERIOD_US);
}

static void vdp_send_byte(uint8_t b)
{
    vdp_send_nibble((b >> 4) & 0x0F);
    vdp_send_nibble( b       & 0x0F);
}

void vdp_qspi_init(void)
{
    if (s_initialized) return;
    pinMode(VDP_PIN_QSPI_SCK,  OUTPUT);
    pinMode(VDP_PIN_QSPI_CS_N, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO0,  OUTPUT);
    pinMode(VDP_PIN_QSPI_IO1,  OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2,  OUTPUT);
    pinMode(VDP_PIN_QSPI_IO3,  OUTPUT);
    vdp_cs_deassert();
    vdp_set_sck(false);
    s_initialized = true;
}

void vdp_pio_wait_sm_idle(void) { /* no-op on Arduino */ }

static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    for (size_t i = 0; i < n; ++i) vdp_send_byte(buf[i]);
}

uint32_t vdp_read_status(uint8_t sel)
{
    vdp_set_sck(false);
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO3, OUTPUT);

    vdp_cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    vdp_tx_bytes(hdr, 6);

    /* 2-edge turnaround */
    pinMode(VDP_PIN_QSPI_IO0, INPUT);
    pinMode(VDP_PIN_QSPI_IO1, INPUT);
    pinMode(VDP_PIN_QSPI_IO2, INPUT);
    pinMode(VDP_PIN_QSPI_IO3, INPUT);
    for (int i = 0; i < 2; i++) {
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(true);  delayMicroseconds(HALF_PERIOD_US);
    }

    uint8_t bytes[4];
    for (int i = 0; i < 4; i++) {
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(true);  uint8_t hi = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(true);  uint8_t lo = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        bytes[i] = (hi << 4) | lo;
    }

    vdp_set_sck(false);
    vdp_cs_deassert();
    /* Restore IO[3:0] to OUTPUT — the 2-edge turnaround left them as INPUT
     * for the response read, and the next vdp_drive_nibble must drive the
     * bus. Without this, GPOS/GPOC writes hit only the output-VALUE
     * register; the pins float (per ESP8266 Arduino-core `pinMode(INPUT)`
     * which clears the output-enable bit via GPEC / GP16E). The FPGA's
     * QspiSlave then samples nibble=0x2 consistently and assembles every
     * post-Magic write byte as 0x22 (PM #9863 / BrightForge #9869
     * empirical localization). */
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO3, OUTPUT);
    delayMicroseconds(10);

    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
           ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}

#endif // platform switch

// ---- Common Shared Implementation -------------------------------------------

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    vdp_reg_write_burst(addr, &data, 1);
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    uint8_t frame[512];
    size_t n;

    if (num_words == 0 || num_words > 253u || words == NULL) {
        s_last_error = 2;
        return;
    }

    n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x01;
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >>  8) & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)( num_words       & 0xFF);
    frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)( words[i]       & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    while (n & 3u) { frame[n++] = 0x00; }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
    sleep_us(10);
#else
    delayMicroseconds(10);
#endif
}

#define VDP_SDRAM_WRITE_MAX_WORDS  253

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (num_words == 0 || num_words > VDP_SDRAM_WRITE_MAX_WORDS) {
        s_last_error = 2;
        return;
    }
    uint8_t frame[512];
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x02;
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >>  8) & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)( num_words       & 0xFF);
    frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)( words[i]       & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    while (n & 3) { frame[n++] = 0x00; }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
    sleep_us(10);
#else
    delayMicroseconds(10);
#endif
}
