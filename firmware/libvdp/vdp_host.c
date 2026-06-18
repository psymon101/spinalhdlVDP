/**
 * vdp_host.c — Host transport layer implementation.
 */
#include "vdp_host.h"
#include "vdp_platform.h"

#if defined(VDP_HOST_BACKEND_I80_GPIO)
#include <Arduino.h>
#if defined(CONFIG_IDF_TARGET_ESP32S3)
#include "soc/gpio_reg.h"
#include "soc/soc.h"
#endif

static bool s_initialized = false;
static int s_last_error = 0;

#ifndef VDP_I80_CPU_HZ
#define VDP_I80_CPU_HZ 240000000u
#endif

static const uint8_t s_i80_data_pins[8] = {
    VDP_PIN_I80_D0, VDP_PIN_I80_D1, VDP_PIN_I80_D2, VDP_PIN_I80_D3,
    VDP_PIN_I80_D4, VDP_PIN_I80_D5, VDP_PIN_I80_D6, VDP_PIN_I80_D7
};

#if defined(CONFIG_IDF_TARGET_ESP32S3) && !defined(VDP_I80_DISABLE_FAST_GPIO)
#define VDP_I80_FAST_GPIO 1
static uint32_t s_i80_half_period_cycles = 8u;

static const uint32_t VDP_I80_DATA_MASK =
    (1u << VDP_PIN_I80_D0) | (1u << VDP_PIN_I80_D1) |
    (1u << VDP_PIN_I80_D2) | (1u << VDP_PIN_I80_D3) |
    (1u << VDP_PIN_I80_D4) | (1u << VDP_PIN_I80_D5) |
    (1u << VDP_PIN_I80_D6) | (1u << VDP_PIN_I80_D7);
static const uint32_t VDP_I80_DC_MASK = (1u << VDP_PIN_I80_DC);
static const uint32_t VDP_I80_CS_MASK = (1u << VDP_PIN_I80_CS_N);
static const uint32_t VDP_I80_WR_MASK = (1u << VDP_PIN_I80_WR_N);
static const uint32_t VDP_I80_RD_MASK = (1u << VDP_PIN_I80_RD_N);

static inline void vdp_i80_gpio_set(uint32_t mask)
{
    REG_WRITE(GPIO_OUT_W1TS_REG, mask);
}

static inline void vdp_i80_gpio_clear(uint32_t mask)
{
    REG_WRITE(GPIO_OUT_W1TC_REG, mask);
}

static inline uint32_t vdp_i80_cycle_count(void)
{
    uint32_t ccount;
    __asm__ __volatile__("rsr.ccount %0" : "=a"(ccount));
    return ccount;
}

static inline void vdp_i80_fast_delay(void)
{
    const uint32_t start = vdp_i80_cycle_count();
    while ((uint32_t)(vdp_i80_cycle_count() - start) < s_i80_half_period_cycles) {}
}
#endif

int vdp_last_error(void) { return s_last_error; }

static bool vdp_transport_ready(void)
{
    if (!s_initialized) {
        s_last_error = VDP_HOST_ERR_NOT_INITIALIZED;
        return false;
    }
    return true;
}

static void vdp_i80_set_data_output(void)
{
    for (uint8_t i = 0; i < 8; ++i) pinMode(s_i80_data_pins[i], OUTPUT);
}

static void vdp_i80_set_data_input(void)
{
    for (uint8_t i = 0; i < 8; ++i) pinMode(s_i80_data_pins[i], INPUT);
}

static void vdp_i80_write_data(uint8_t value)
{
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_DATA_MASK);
    vdp_i80_gpio_set(((uint32_t)value << VDP_PIN_I80_D0) & VDP_I80_DATA_MASK);
#else
    for (uint8_t bit = 0; bit < 8; ++bit) {
        digitalWrite(s_i80_data_pins[bit], (value & (uint8_t)(1u << bit)) ? HIGH : LOW);
    }
#endif
}

static uint8_t vdp_i80_read_data(void)
{
    uint8_t value = 0;
    for (uint8_t bit = 0; bit < 8; ++bit) {
        if (digitalRead(s_i80_data_pins[bit]) != LOW) value |= (uint8_t)(1u << bit);
    }
    return value;
}

static void vdp_i80_pulse_wr(void)
{
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_WR_MASK);
    vdp_i80_fast_delay();
    vdp_i80_gpio_set(VDP_I80_WR_MASK);
    vdp_i80_fast_delay();
#else
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_WR_N, LOW);
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    delayMicroseconds(2);
#endif
}

static void vdp_i80_write_byte(bool data_phase, uint8_t value)
{
#if defined(VDP_I80_FAST_GPIO)
    if (data_phase) {
        vdp_i80_gpio_set(VDP_I80_DC_MASK);
    } else {
        vdp_i80_gpio_clear(VDP_I80_DC_MASK);
    }
#else
    digitalWrite(VDP_PIN_I80_DC, data_phase ? HIGH : LOW);
#endif
    vdp_i80_write_data(value);
    vdp_i80_pulse_wr();
}

static uint8_t vdp_i80_read_byte(void)
{
    digitalWrite(VDP_PIN_I80_DC, HIGH);
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_RD_N, LOW);
    delayMicroseconds(2);
    const uint8_t value = vdp_i80_read_data();
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    delayMicroseconds(2);
    return value;
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    vdp_i80_set_data_output();
    pinMode(VDP_PIN_I80_DC, OUTPUT);
    pinMode(VDP_PIN_I80_CS_N, OUTPUT);
    pinMode(VDP_PIN_I80_WR_N, OUTPUT);
    pinMode(VDP_PIN_I80_RD_N, OUTPUT);
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK | VDP_I80_WR_MASK | VDP_I80_RD_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#endif
    vdp_i80_write_data(0x00);
    s_last_error = VDP_HOST_ERR_NONE;
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_pio_wait_sm_idle(void) {}
void vdp_host_set_speed_hz(uint32_t hz)
{
#if defined(VDP_I80_FAST_GPIO)
    if (hz == 0u) return;
    uint32_t half_cycles = VDP_I80_CPU_HZ / (hz * 2u);
    if (half_cycles < 8u) half_cycles = 8u;
    s_i80_half_period_cycles = half_cycles;
#else
    (void)hz;
#endif
}
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

uint32_t vdp_read_status(uint8_t sel)
{
    if (!vdp_transport_ready()) return 0;
    s_last_error = VDP_HOST_ERR_NONE;
    vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
    vdp_i80_gpio_clear(VDP_I80_CS_MASK);
    vdp_i80_fast_delay();
#else
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
#endif
    vdp_i80_write_byte(false, 0x04);
    vdp_i80_write_byte(false, sel);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_set_data_input();
    delayMicroseconds(2);
    const uint8_t b0 = vdp_i80_read_byte();
    const uint8_t b1 = vdp_i80_read_byte();
    const uint8_t b2 = vdp_i80_read_byte();
    const uint8_t b3 = vdp_i80_read_byte();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_set_data_output();
    vdp_i80_write_data(0x00);
    return (uint32_t)b0 | ((uint32_t)b1 << 8) |
           ((uint32_t)b2 << 16) | ((uint32_t)b3 << 24);
}

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    vdp_reg_write_burst(addr, &data, 1);
}

void vdp_clear_upload_status(uint16_t mask)
{
    (void)mask;
    s_last_error = VDP_HOST_ERR_NONE;
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (!vdp_transport_ready()) return;
    s_last_error = VDP_HOST_ERR_NONE;
    if (num_words == 0 || words == NULL) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }

    for (uint16_t i = 0; i < num_words; ++i) {
        const uint32_t reg_addr = addr + i;
        vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
        vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
        vdp_i80_gpio_clear(VDP_I80_CS_MASK);
        vdp_i80_fast_delay();
#else
        digitalWrite(VDP_PIN_I80_RD_N, HIGH);
        digitalWrite(VDP_PIN_I80_WR_N, HIGH);
        digitalWrite(VDP_PIN_I80_CS_N, LOW);
        delayMicroseconds(5);
#endif
        vdp_i80_write_byte(false, 0x00);
        vdp_i80_write_byte(false, (uint8_t)(reg_addr & 0xFFu));
        vdp_i80_write_byte(false, (uint8_t)((reg_addr >> 8) & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)(words[i] & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)((words[i] >> 8) & 0xFFu));
#if defined(VDP_I80_FAST_GPIO)
        vdp_i80_gpio_set(VDP_I80_CS_MASK);
#else
        digitalWrite(VDP_PIN_I80_CS_N, HIGH);
#endif
    }
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_write_data(0x00);
}

uint16_t vdp_reg_read(uint32_t addr)
{
    if (!vdp_transport_ready()) return 0;
    s_last_error = VDP_HOST_ERR_NONE;
    vdp_i80_set_data_output();
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
    vdp_i80_write_byte(false, 0x01);
    vdp_i80_write_byte(false, (uint8_t)(addr & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    vdp_i80_set_data_input();
    delayMicroseconds(5);
    const uint8_t lo = vdp_i80_read_byte();
    const uint8_t hi = vdp_i80_read_byte();
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    vdp_i80_set_data_output();
    digitalWrite(VDP_PIN_I80_DC, LOW);
    vdp_i80_write_data(0x00);
    return (uint16_t)lo | ((uint16_t)hi << 8);
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (!vdp_transport_ready()) return;
    s_last_error = VDP_HOST_ERR_NONE;
    if (num_words == 0 || words == NULL || num_words > 32767u) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }

    const uint16_t byte_len = (uint16_t)(num_words * 2u);
    vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
    vdp_i80_gpio_clear(VDP_I80_CS_MASK);
    vdp_i80_fast_delay();
#else
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
#endif
    vdp_i80_write_byte(false, 0x02);
    vdp_i80_write_byte(false, (uint8_t)(addr & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 16) & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)(byte_len & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((byte_len >> 8) & 0xFFu));
    for (uint16_t i = 0; i < num_words; ++i) {
        vdp_i80_write_byte(true, (uint8_t)(words[i] & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)((words[i] >> 8) & 0xFFu));
    }
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_write_data(0x00);
}

#elif defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"
#include "qspi_quad.pio.h"

static bool   s_initialized = false;
static int    s_last_error = 0;
static uint   s_tx_offset;

int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { gpio_put(VDP_PIN_QSPI_CS_N, 0); }
static inline void vdp_cs_deassert(void) { gpio_put(VDP_PIN_QSPI_CS_N, 1); }

static inline uint32_t vdp_pack_bytes(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3)
{
    return ((uint32_t)b0 << 24) | ((uint32_t)b1 << 16) |
           ((uint32_t)b2 << 8)  | (uint32_t)b3;
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    gpio_init(VDP_PIN_QSPI_CS_N);
    gpio_set_dir(VDP_PIN_QSPI_CS_N, GPIO_OUT);
    gpio_put(VDP_PIN_QSPI_CS_N, 1);
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
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }

void vdp_pio_wait_sm_idle(void)
{
    while (!pio_sm_is_tx_fifo_empty(VDP_QSPI_PIO, VDP_QSPI_SM_TX)) { /* spin */ }
    sleep_us(20);
}

void vdp_host_set_speed_hz(uint32_t hz) { (void)hz; }
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

static inline void vdp_tx_word(uint32_t w) { pio_sm_put_blocking(VDP_QSPI_PIO, VDP_QSPI_SM_TX, w); }

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

// ---- ESP32-S3 Hardware SPI2 (Quad, DMA) Implementation ----------------------
#elif defined(VDP_QSPI_BACKEND_SPI2)
#include <Arduino.h>
#include <driver/spi_master.h>

static spi_device_handle_t s_spi = NULL;
static bool s_bus_initialized = false;
static bool s_initialized = false;
static int s_last_error = 0;

int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { digitalWrite(VDP_PIN_QSPI_CS_N, LOW); }
static inline void vdp_cs_deassert(void) {
    digitalWrite(VDP_PIN_QSPI_CS_N, HIGH);
    delayMicroseconds(10);
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    pinMode(VDP_PIN_QSPI_CS_N, OUTPUT);
    vdp_cs_deassert();
    if (!s_bus_initialized) {
        spi_bus_config_t buscfg = {0};
        buscfg.mosi_io_num    = VDP_PIN_QSPI_IO0;
        buscfg.miso_io_num    = VDP_PIN_QSPI_IO1;
        buscfg.sclk_io_num    = VDP_PIN_QSPI_SCK;
        buscfg.quadwp_io_num  = VDP_PIN_QSPI_IO2;
        buscfg.quadhd_io_num  = VDP_PIN_QSPI_IO3;
        buscfg.max_transfer_sz = 4096;
        buscfg.flags          = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD;
        if (spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO) != ESP_OK) { s_last_error = 3; return; }
        s_bus_initialized = true;
    }
    spi_device_interface_config_t devcfg = {0};
    devcfg.clock_speed_hz = VDP_QSPI_SCK_HZ;
    devcfg.mode           = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 4;
    devcfg.flags          = SPI_DEVICE_HALFDUPLEX;
    if (spi_bus_add_device(SPI2_HOST, &devcfg, &s_spi) != ESP_OK) { s_spi = NULL; s_initialized = false; s_last_error = 4; return; }
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }

void vdp_pio_wait_sm_idle(void) {}

void vdp_host_set_speed_hz(uint32_t hz)
{
    if (!s_bus_initialized) return;
    if (hz > VDP_QSPI_SCK_WRITE_HZ) hz = VDP_QSPI_SCK_WRITE_HZ;
    vdp_cs_deassert();
    if (s_spi) {
        if (spi_bus_remove_device(s_spi) != ESP_OK) {
            s_last_error = 4;
            return;
        }
        s_spi = NULL;
        s_initialized = false;
    }
    spi_device_interface_config_t devcfg = {0};
    devcfg.clock_speed_hz = (int)hz;
    devcfg.mode           = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 4;
    devcfg.flags          = SPI_DEVICE_HALFDUPLEX;
    if (spi_bus_add_device(SPI2_HOST, &devcfg, &s_spi) != ESP_OK) {
        s_spi = NULL;
        s_initialized = false;
        s_last_error = 4;
        return;
    }
    s_initialized = true;
    vdp_cs_deassert();
}

void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

static bool vdp_spi_ready(void)
{
    if (!s_initialized || s_spi == NULL) {
        s_last_error = 4;
        return false;
    }
    return true;
}

static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    if (n == 0) return;
    if (!vdp_spi_ready()) return;
    spi_transaction_t t = {0};
    t.flags     = SPI_TRANS_MODE_QIO;
    t.length    = n * 8u;
    t.tx_buffer = buf;
    if (spi_device_polling_transmit(s_spi, &t) != ESP_OK) s_last_error = 5;
}

uint32_t vdp_read_status(uint8_t sel)
{
    if (!vdp_spi_ready()) return 0;
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    uint8_t rx[4]  = { 0, 0, 0, 0 };
    vdp_cs_assert();
    spi_transaction_t tx = {0};
    tx.flags     = SPI_TRANS_MODE_QIO;
    tx.length    = 6u * 8u;
    tx.tx_buffer = hdr;
    esp_err_t err = spi_device_polling_transmit(s_spi, &tx);
    if (err != ESP_OK) { vdp_cs_deassert(); s_last_error = 5; return 0; }
    spi_transaction_ext_t rd = {0};
    rd.base.flags    = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    rd.base.rxlength = 4u * 8u;
    rd.base.rx_buffer = rx;
    rd.dummy_bits    = 2;
    err = spi_device_polling_transmit(s_spi, (spi_transaction_t *)&rd);
    vdp_cs_deassert();
    if (err != ESP_OK) { s_last_error = 6; return 0; }
    return (uint32_t)rx[0] | ((uint32_t)rx[1] << 8) | ((uint32_t)rx[2] << 16) | ((uint32_t)rx[3] << 24);
}

// ---- ESP32 / ESP8266 Arduino Bit-bang Implementation -------------------------
#elif defined(ARDUINO)
#include <Arduino.h>
static bool s_initialized = false;
static int s_last_error = 0;
int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { digitalWrite(VDP_PIN_QSPI_CS_N, LOW); }
static inline void vdp_cs_deassert(void) {
    digitalWrite(VDP_PIN_QSPI_CS_N, HIGH);
    delayMicroseconds(10);
}

#if defined(ESP32)
#define HALF_PERIOD_US 1
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
#define MASK_IO3    (1u << VDP_PIN_QSPI_IO3)
#define MASK_IO_ALL (MASK_IO0 | MASK_IO1 | MASK_IO2 | MASK_IO3)
static inline void vdp_drive_nibble(uint8_t n) {
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0; if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2; if (n & 0x8) set |= MASK_IO3;
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_IO_ALL);
    if (set) REG_WRITE(GPIO_OUT_W1TS_REG, set);
}
static inline void vdp_set_sck(bool high) { REG_WRITE(high ? GPIO_OUT_W1TS_REG : GPIO_OUT_W1TC_REG, MASK_SCK); }
static inline uint8_t vdp_read_nibble(void) {
    uint32_t pins = REG_READ(GPIO_IN_REG);
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1; if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4; if (pins & MASK_IO3) n |= 0x8;
    return n;
}
#elif defined(ESP8266)
#define HALF_PERIOD_US 4
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
#define MASK_IO_LOW (MASK_IO0 | MASK_IO1 | MASK_IO2)
static inline void vdp_drive_nibble(uint8_t n) {
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0; if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW; if (set) GPOS = set;
    digitalWrite(VDP_PIN_QSPI_IO3, (n & 0x8) ? HIGH : LOW);
}
static inline void vdp_set_sck(bool high) { if (high) GPOS = MASK_SCK; else GPOC = MASK_SCK; }
static inline uint8_t vdp_read_nibble(void) {
    uint32_t pins = GPI;
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1; if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4; if (digitalRead(VDP_PIN_QSPI_IO3)) n |= 0x8;
    return n;
}
#endif

static void vdp_send_nibble(uint8_t n) { vdp_drive_nibble(n); vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true); delayMicroseconds(HALF_PERIOD_US); }
static void vdp_send_byte(uint8_t b) { vdp_send_nibble((b >> 4) & 0x0F); vdp_send_nibble( b       & 0x0F); }

void vdp_host_init(void)
{
    if (s_initialized) return;
    pinMode(VDP_PIN_QSPI_SCK,  OUTPUT); pinMode(VDP_PIN_QSPI_CS_N, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO0,  OUTPUT); pinMode(VDP_PIN_QSPI_IO1,  OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2,  OUTPUT); pinMode(VDP_PIN_QSPI_IO3,  OUTPUT);
    vdp_cs_deassert(); vdp_set_sck(false); s_initialized = true;
}

void vdp_pio_wait_sm_idle(void) {}
void vdp_host_set_speed_hz(uint32_t hz) { (void)hz; }
void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }
static void vdp_tx_bytes(const uint8_t *buf, size_t n) { for (size_t i = 0; i < n; ++i) vdp_send_byte(buf[i]); }

uint32_t vdp_read_status(uint8_t sel)
{
    vdp_set_sck(false);
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT); pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT); pinMode(VDP_PIN_QSPI_IO3, OUTPUT);
    vdp_cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    vdp_tx_bytes(hdr, 6);
    pinMode(VDP_PIN_QSPI_IO0, INPUT); pinMode(VDP_PIN_QSPI_IO1, INPUT);
    pinMode(VDP_PIN_QSPI_IO2, INPUT); pinMode(VDP_PIN_QSPI_IO3, INPUT);
    for (int i = 0; i < 2; i++) { vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  delayMicroseconds(HALF_PERIOD_US); }
    uint8_t bytes[4];
    for (int i = 0; i < 4; i++) {
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  uint8_t hi = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  uint8_t lo = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        bytes[i] = (hi << 4) | lo;
    }
    vdp_set_sck(false); vdp_cs_deassert();
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT); pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT); pinMode(VDP_PIN_QSPI_IO3, OUTPUT);
    delayMicroseconds(10);
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) | ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}
#endif

// ---- Common Shared Implementation -------------------------------------------
#if !defined(VDP_HOST_BACKEND_I80_GPIO)

void vdp_reg_write(uint32_t addr, uint16_t data) { vdp_reg_write_burst(addr, &data, 1); }

void vdp_clear_upload_status(uint16_t mask)
{
    vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, (uint16_t)(mask & VDP_UPLOAD_STATUS_CLEAR_MASK));
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    uint8_t frame[512] __attribute__((aligned(4)));
    if (num_words == 0 || num_words > 253u || words == NULL) { s_last_error = 2; return; }
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x01;
    frame[1] = (uint8_t)(addr & 0xFF); frame[2] = (uint8_t)((addr >> 8) & 0xFF); frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)(num_words & 0xFF); frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)(words[i] & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    uint8_t frame[512] __attribute__((aligned(4)));
    if (num_words == 0 || num_words > 253u || words == NULL) { s_last_error = 2; return; }
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x02;
    frame[1] = (uint8_t)(addr & 0xFF); frame[2] = (uint8_t)((addr >> 8) & 0xFF); frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)(num_words & 0xFF); frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)(words[i] & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
}

uint16_t vdp_reg_read(uint32_t addr)
{
    (void)addr;
    s_last_error = VDP_HOST_ERR_RX;
    return 0;
}
#endif
