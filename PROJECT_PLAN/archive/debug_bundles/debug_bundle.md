# Debug bundle: BronzeGate firmware + BrightForge RTL for 2bpp indexed display issue

This file concatenates the current source files used by BronzeGate (ESP32-P4 firmware)
and BrightForge (SpinalHDL RTL) for the reopened HAM6-removal / 2bpp indexed display
quality issue. The user observes stepped horizontal bands / shimmer in the live
capture/stream even after the LINESTATE L0-enable fix made the bars visible.

---


## File: firmware/esp32p4_qspi_proof/main/main.c

```c
/*
 * ESP32-P4 GPSPI proof app and stress harness.
 *
 * Baseline contract:
 * - SPI2/GPSPI master
 * - GPIO matrix pins: SCLK=21, CS#=20, IO0=32, IO1=33, IO2=22, IO3=23
 * - Half-duplex, 20 MHz default device, command_bits=8, address_bits=24
 * - Reads use dummy_bits=2 on the RX path by default
 *
 * Bench sequence:
 *   1. READ_STATUS sel=0 -> expect 0x51560002
 *   2. REG_WRITE 0x0305 = 0xA55A via LEN+payload quad write
 *   3. READ_STATUS sel=9 -> expect 0x12340042
 *   4. SDRAM_WRITE bulk 0x001000 = DE AD BE EF via LEN+payload quad write
 *   5. Stress phases: BER, clock sweep, throughput bursts, back-to-back framing
 */

#include <inttypes.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/spi_common.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "p4_qspi_proof";

enum {
    PIN_SCLK = 21,
    PIN_CS = 20,
    PIN_IO0 = 32,
    PIN_IO1 = 33,
    PIN_IO2 = 22,
    PIN_IO3 = 23,
};

enum {
    CMD_READ_STATUS = 0x04,
    CMD_REG_WRITE = 0x01,
    CMD_SDRAM_WRITE = 0x02,
    PHASE3_PATTERN_SIZE = 4094,
    INDEXED2_WIDTH = 320,
    INDEXED2_HEIGHT = 240,
    INDEXED2_ROW_BYTES = INDEXED2_WIDTH / 4,
    INDEXED2_ROW_STRIDE = 128,
    INDEXED2_IMAGE_BYTES = INDEXED2_HEIGHT * INDEXED2_ROW_STRIDE,
};

static const uint32_t QSPI_DEFAULT_CLOCK_HZ = 20u * 1000u * 1000u;
static const uint32_t QSPI_FUNCTIONAL_CLOCK_HZ = 40u * 1000u * 1000u;
static const uint32_t QSPI_EIGHTY_CLOCK_HZ = 80u * 1000u * 1000u;
static const uint32_t QSPI_SDRAM_CLOCK_HZ = 8u * 1000u * 1000u;
static const spi_clock_source_t QSPI_CLOCK_SOURCE = SPI_CLK_SRC_SPLL;
// Select proof stages intentionally at compile time.  The default is the
// bounded indexed display proof; the 30-minute campaign is opt-in.
#ifndef P4_QSPI_RUN_BASIC_PROOF
#define P4_QSPI_RUN_BASIC_PROOF 0
#endif
#ifndef P4_QSPI_RUN_INDEXED2_PROOF
#define P4_QSPI_RUN_INDEXED2_PROOF 1
#endif
#ifndef P4_QSPI_RUN_SHORT_SMOKE
#define P4_QSPI_RUN_SHORT_SMOKE 0
#endif
#ifndef P4_QSPI_RUN_DEFINITIVE_CAMPAIGN
#define P4_QSPI_RUN_DEFINITIVE_CAMPAIGN 0
#endif
#ifndef P4_QSPI_RUN_BULK_THROUGHPUT_ONLY
#define P4_QSPI_RUN_BULK_THROUGHPUT_ONLY 0
#endif
static const uint32_t LONG_PHASE_YIELD_INTERVAL = 10000u;
static const uint32_t SHORT_SMOKE_ROUNDTRIPS = 1000000u;
static const uint32_t SHORT_SMOKE_BULK_BYTES = 131072u;
static const uint32_t PHASE1_PROGRESS_INTERVAL = 1000000u;
static const uint32_t PHASE2_PROGRESS_INTERVAL = 25000u;
static const uint8_t READ_STATUS_SEL_MAGIC = 0x00u;
static const uint8_t READ_STATUS_SEL_HDR_ERR = 0x07u;
static const uint8_t READ_STATUS_SEL_SDRAM = 0x08u;
static const uint8_t READ_STATUS_SEL_LOOPBACK = 0x09u;
static const uint8_t READ_STATUS_SEL_TRANSPORT_HEALTH = 0x06u;
static const uint32_t REG_WRITE_ADDR = 0x0305u;
static const uint16_t REG_WRITE_VALUE = 0xA55Au;
static const uint32_t LOOPBACK_WRITE_ADDR = 0x0042u;
static const uint16_t LOOPBACK_WRITE_VALUE = 0x1234u;
static const uint32_t EXPECTED_MAGIC = 0x51560002u;
static const uint32_t EXPECTED_LOOPBACK = 0x12340042u;
static const uint32_t REG_SDRAM_READ_ADDR_LO = 0x0326u;
static const uint32_t REG_SDRAM_READ_ADDR_HI = 0x0327u;
static const uint32_t DMA_BUF_SIZE = 65536u;
// ESP32-P4 SPI_MS_DATA_BITLEN is an 18-bit count of data-phase bits. Keep
// every QIO TX transaction below its 32767-byte maximum.
#define QSPI_MAX_TX_BYTES 32767u
// SDRAM_WRITE adds a 2-byte word-count prefix; keep payloads even and below
// the transaction ceiling with a small margin for future framing changes.
static const uint64_t PHASE4_DURATION_US = 30ull * 60ull * 1000000ull;
static const uint64_t PHASE4_APPROX_PAYLOAD_BYTES_PER_ITER = 8ull;
// Keep all proof traffic below the reviewed QSPI oversampling ceiling.
static const uint32_t CLOCK_PROBE_ITERATIONS = 64u;
static const uint32_t PHASE1_ITERATIONS = 10000000u;
static const uint32_t PHASE2_ITERATIONS = 100000u;
enum { PHASE3_BURST_WORDS = (QSPI_MAX_TX_BYTES - 2u) / 2u };
static spi_device_handle_t s_spi = NULL;
static uint8_t *s_tx_buf = NULL;
static uint8_t *s_rx_buf = NULL;
static uint8_t s_phase3_pattern[PHASE3_PATTERN_SIZE];
static uint16_t s_reg_burst_words[PHASE3_BURST_WORDS];
static uint8_t s_indexed2_bitmap[INDEXED2_IMAGE_BYTES];
static uint8_t s_indexed2_attr[INDEXED2_IMAGE_BYTES];
static uint32_t s_input_delay_ns = 0;
static uint32_t s_configured_freq_hz = 0;
static bool s_use_header_parity = true;

typedef struct {
    uint32_t iterations;
    uint32_t write_errors;
    uint32_t read_errors;
    uint32_t transient_read_mismatches;
    uint32_t stable_mismatches;
} phase_stats_t;

typedef struct {
    uint32_t requested_freq_hz;
    uint32_t actual_freq_hz;
    uint32_t input_delay_ns;
    uint8_t dummy_bits;
    phase_stats_t stats;
} sweep_result_t;

static uint32_t qspi_get_actual_freq_hz(void);

static void log_hex_bytes(const char *label, const uint8_t *buf, size_t len)
{
    if (label == NULL || (buf == NULL && len != 0u)) {
        ESP_LOGE(TAG, "hex log rejected null argument");
        return;
    }
    char line[3 * 8 + 1] = {0};
    size_t pos = 0;
    for (size_t i = 0; i < len && pos + 3 < sizeof(line); ++i) {
        pos += (size_t)snprintf(line + pos, sizeof(line) - pos, "%s%02X",
                                (i == 0) ? "" : " ", buf[i]);
    }
    ESP_LOGI(TAG, "%s %s", label, line);
}

static uint32_t bytes_to_u32_le(const uint8_t *buf)
{
    return (uint32_t)buf[0] |
           ((uint32_t)buf[1] << 8) |
           ((uint32_t)buf[2] << 16) |
           ((uint32_t)buf[3] << 24);
}

static esp_err_t fill_tx_bytes(const uint8_t *src, size_t len)
{
    ESP_RETURN_ON_FALSE(s_tx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "TX DMA buffer unavailable");
    ESP_RETURN_ON_FALSE(src != NULL, ESP_ERR_INVALID_ARG, TAG, "null TX source");
    ESP_RETURN_ON_FALSE(len != 0u && len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG,
                        "invalid TX length");
    memcpy(s_tx_buf, src, len);
    return ESP_OK;
}

static uint8_t parity31(uint8_t cmd, uint32_t addr)
{
    uint32_t bits = ((uint32_t)cmd << 23) | (addr & 0x7FFFFFu);
    uint8_t parity = 0;

    while (bits != 0u) {
        parity ^= (uint8_t)(bits & 1u);
        bits >>= 1;
    }
    return parity;
}

static uint32_t qspi_encode_addr(uint8_t cmd, uint32_t addr)
{
    uint32_t trimmed = addr;

    if (!s_use_header_parity) {
        return trimmed;
    }
    return trimmed | ((uint32_t)parity31(cmd, trimmed) << 23);
}

static esp_err_t qspi_add_device(uint32_t clock_hz, uint32_t input_delay_ns, spi_clock_source_t clock_source)
{
    ESP_RETURN_ON_FALSE(clock_hz != 0u, ESP_ERR_INVALID_ARG, TAG, "zero QSPI clock");
    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = (int)clock_hz,
        .clock_source = clock_source,
        .mode = 0,
        .spics_io_num = PIN_CS,
        .queue_size = 4,
        .command_bits = 8,
        .address_bits = 24,
        .dummy_bits = 2,
        .input_delay_ns = (int)input_delay_ns,
        .cs_ena_pretrans = 2,
        .cs_ena_posttrans = 2,
        .flags = SPI_DEVICE_HALFDUPLEX | SPI_DEVICE_NO_DUMMY,
    };
    s_input_delay_ns = input_delay_ns;
    ESP_RETURN_ON_ERROR(spi_bus_add_device(SPI2_HOST, &dev_cfg, &s_spi),
                        TAG, "spi device add failed");
    s_configured_freq_hz = clock_hz;
    return ESP_OK;
}

static esp_err_t qspi_init(void)
{
    spi_bus_config_t bus_cfg = {
        .data0_io_num = PIN_IO0,
        .data1_io_num = PIN_IO1,
        .sclk_io_num = PIN_SCLK,
        .data2_io_num = PIN_IO2,
        .data3_io_num = PIN_IO3,
        .data4_io_num = -1,
        .data5_io_num = -1,
        .data6_io_num = -1,
        .data7_io_num = -1,
        .max_transfer_sz = DMA_BUF_SIZE,
        .flags = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD,
    };
    ESP_RETURN_ON_ERROR(spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO),
                        TAG, "spi bus init failed");
    return qspi_add_device(QSPI_DEFAULT_CLOCK_HZ, 0, QSPI_CLOCK_SOURCE);
}

static esp_err_t qspi_reconfigure_device(uint32_t clock_hz, uint32_t input_delay_ns)
{
    if (s_spi != NULL) {
        ESP_RETURN_ON_ERROR(spi_bus_remove_device(s_spi), TAG, "spi remove device failed");
        s_spi = NULL;
    }
    return qspi_add_device(clock_hz, input_delay_ns, QSPI_CLOCK_SOURCE);
}

static esp_err_t qspi_tx(uint8_t cmd, uint64_t addr, const uint8_t *tx, size_t len,
                         uint8_t dummy_bits, uint32_t override_freq_hz)
{
    spi_transaction_ext_t t = {0};
    uint64_t max_addr = s_use_header_parity ? 0x7FFFFFu : 0xFFFFFFu;
    ESP_RETURN_ON_FALSE(s_spi != NULL, ESP_ERR_INVALID_STATE, TAG, "QSPI device unavailable");
    ESP_RETURN_ON_FALSE(addr <= max_addr, ESP_ERR_INVALID_ARG, TAG, "QSPI address out of range");
    ESP_RETURN_ON_FALSE(tx != NULL, ESP_ERR_INVALID_ARG, TAG, "null QSPI TX buffer");
    ESP_RETURN_ON_FALSE(len != 0u, ESP_ERR_INVALID_ARG, TAG, "zero-length QSPI TX");
    ESP_RETURN_ON_FALSE(len <= QSPI_MAX_TX_BYTES, ESP_ERR_INVALID_ARG, TAG,
                        "qspi tx exceeds P4 transaction limit");
    // ESP-IDF 6.0.2: QIO selects quad data only here; command/address remain
    // single-line because no multiline command/address flags are set.
    // SPI_DEVICE_NO_DUMMY suppresses device auto-dummy insertion, while
    // SPI_TRANS_VARIABLE_DUMMY makes t.dummy_bits authoritative for reads.
    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.length = len * 8u;
    t.base.override_freq_hz = (override_freq_hz != 0u && override_freq_hz != s_configured_freq_hz)
                                  ? override_freq_hz : 0u;
    t.base.tx_buffer = tx;
    t.dummy_bits = dummy_bits;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_rx(uint8_t cmd, uint64_t addr, uint8_t *rx, size_t len,
                         uint8_t dummy_bits, uint32_t override_freq_hz)
{
    spi_transaction_ext_t t = {0};
    uint64_t max_addr = s_use_header_parity ? 0x7FFFFFu : 0xFFFFFFu;
    ESP_RETURN_ON_FALSE(s_spi != NULL, ESP_ERR_INVALID_STATE, TAG, "QSPI device unavailable");
    ESP_RETURN_ON_FALSE(addr <= max_addr, ESP_ERR_INVALID_ARG, TAG, "QSPI address out of range");
    ESP_RETURN_ON_FALSE(rx != NULL, ESP_ERR_INVALID_ARG, TAG, "null QSPI RX buffer");
    ESP_RETURN_ON_FALSE(len != 0u, ESP_ERR_INVALID_ARG, TAG, "zero-length QSPI RX");
    ESP_RETURN_ON_FALSE(len <= (DMA_BUF_SIZE / sizeof(uint8_t)), ESP_ERR_INVALID_ARG, TAG,
                        "qspi RX exceeds DMA buffer");
    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.rxlength = len * 8u;
    t.base.override_freq_hz = (override_freq_hz != 0u && override_freq_hz != s_configured_freq_hz)
                                  ? override_freq_hz : 0u;
    t.base.rx_buffer = rx;
    t.dummy_bits = dummy_bits;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_read_status(uint8_t sel, uint32_t *out_value,
                                  uint8_t dummy_bits, uint32_t override_freq_hz)
{
    ESP_RETURN_ON_FALSE(out_value != NULL, ESP_ERR_INVALID_ARG, TAG, "null status output");
    ESP_RETURN_ON_FALSE(s_rx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "RX DMA buffer unavailable");
    esp_err_t err = qspi_rx(CMD_READ_STATUS, sel, s_rx_buf, 4, dummy_bits, override_freq_hz);
    if (err != ESP_OK) {
        return err;
    }
    *out_value = bytes_to_u32_le(s_rx_buf);
    return ESP_OK;
}

static esp_err_t qspi_reg_write(uint32_t reg_addr, uint16_t value, uint32_t override_freq_hz)
{
    const uint8_t payload[4] = {
        0x01u, 0x00u,
        (uint8_t)(value & 0xFFu),
        (uint8_t)((value >> 8) & 0xFFu),
    };
    ESP_RETURN_ON_ERROR(fill_tx_bytes(payload, sizeof(payload)), TAG, "reg TX staging failed");
    return qspi_tx(CMD_REG_WRITE, reg_addr, s_tx_buf, sizeof(payload), 0, override_freq_hz);
}

static esp_err_t qspi_reg_write_burst(uint32_t reg_addr, const uint16_t *words, size_t word_count,
                                      uint32_t override_freq_hz)
{
    size_t total_len = 0u;

    ESP_RETURN_ON_FALSE(words != NULL, ESP_ERR_INVALID_ARG, TAG, "null reg burst words");
    ESP_RETURN_ON_FALSE(s_tx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "TX DMA buffer unavailable");
    ESP_RETURN_ON_FALSE(word_count != 0u, ESP_ERR_INVALID_ARG, TAG, "empty reg burst");
    ESP_RETURN_ON_FALSE(word_count <= UINT16_MAX, ESP_ERR_INVALID_ARG, TAG, "reg burst word count too large");
    ESP_RETURN_ON_FALSE(word_count <= ((QSPI_MAX_TX_BYTES - 2u) / 2u), ESP_ERR_INVALID_ARG, TAG,
                        "reg burst exceeds QSPI transaction limit");
    total_len = 2u + (word_count * 2u);
    ESP_RETURN_ON_FALSE(total_len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG, "reg burst too large");

    s_tx_buf[0] = (uint8_t)(word_count & 0xFFu);
    s_tx_buf[1] = (uint8_t)((word_count >> 8) & 0xFFu);
    for (size_t i = 0; i < word_count; ++i) {
        s_tx_buf[2u + (i * 2u)] = (uint8_t)(words[i] & 0xFFu);
        s_tx_buf[3u + (i * 2u)] = (uint8_t)((words[i] >> 8) & 0xFFu);
    }
    return qspi_tx(CMD_REG_WRITE, reg_addr, s_tx_buf, total_len, 0, override_freq_hz);
}

static esp_err_t qspi_read_hdr_err_status(uint32_t *raw_out, uint16_t *count_out,
                                          bool *sticky_out, uint32_t override_freq_hz)
{
    uint32_t raw = 0;
    esp_err_t err = qspi_read_status(READ_STATUS_SEL_HDR_ERR, &raw, 2, override_freq_hz);
    if (err != ESP_OK) {
        return err;
    }
    if (raw_out != NULL) {
        *raw_out = raw;
    }
    if (count_out != NULL) {
        *count_out = (uint16_t)(raw & 0xFFFFu);
    }
    if (sticky_out != NULL) {
        *sticky_out = ((raw >> 16) & 0x1u) != 0u;
    }
    return ESP_OK;
}

static esp_err_t qspi_read_transport_health_status(uint32_t *raw_out, bool *overflow_out,
                                                   bool *malformed_out, uint32_t override_freq_hz)
{
    uint32_t raw = 0;
    esp_err_t err = qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &raw, 2, override_freq_hz);
    if (err != ESP_OK) {
        return err;
    }
    if (raw_out != NULL) {
        *raw_out = raw;
    }
    if (overflow_out != NULL) {
        *overflow_out = (raw & 0x1u) != 0u;
    }
    if (malformed_out != NULL) {
        *malformed_out = (raw & 0x2u) != 0u;
    }
    return ESP_OK;
}

static bool read_and_log_transport_health(const char *label, uint32_t freq_hz)
{
    uint32_t raw = 0;
    bool overflow = false;
    bool malformed = false;
    esp_err_t err = qspi_read_transport_health_status(&raw, &overflow, &malformed, freq_hz);

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "%s result=ERR err=%s", label, esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG,
             "%s raw=0x%08" PRIX32 " overflow=%u malformed=%u req_freq=%" PRIu32 " actual_freq=%" PRIu32,
             label, raw, overflow ? 1u : 0u, malformed ? 1u : 0u, freq_hz, qspi_get_actual_freq_hz());
    return !overflow && !malformed;
}

static esp_err_t qspi_sdram_write(uint32_t sdram_addr, const uint8_t *payload, size_t len,
                                  uint32_t override_freq_hz)
{
    uint16_t len_words = 0;
    size_t total_len = 0;

    ESP_RETURN_ON_FALSE(payload != NULL, ESP_ERR_INVALID_ARG, TAG, "null SDRAM payload");
    ESP_RETURN_ON_FALSE(s_tx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "TX DMA buffer unavailable");
    ESP_RETURN_ON_FALSE(len != 0u, ESP_ERR_INVALID_ARG, TAG, "zero-length SDRAM write");
    ESP_RETURN_ON_FALSE((len % 2u) == 0u, ESP_ERR_INVALID_ARG, TAG, "sdram write len must be even");
    ESP_RETURN_ON_FALSE(len <= (QSPI_MAX_TX_BYTES - 2u), ESP_ERR_INVALID_ARG, TAG,
                        "sdram write payload exceeds P4 transaction limit");
    total_len = len + 2u;
    ESP_RETURN_ON_FALSE(total_len <= QSPI_MAX_TX_BYTES, ESP_ERR_INVALID_ARG, TAG,
                        "sdram write exceeds P4 transaction limit");
    ESP_RETURN_ON_FALSE(total_len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG, "sdram write too large");

    ESP_RETURN_ON_FALSE((len / 2u) <= UINT16_MAX, ESP_ERR_INVALID_ARG, TAG,
                        "sdram write word count too large");
    len_words = (uint16_t)(len / 2u);
    s_tx_buf[0] = (uint8_t)(len_words & 0xFFu);
    s_tx_buf[1] = (uint8_t)((len_words >> 8) & 0xFFu);
    memcpy(s_tx_buf + 2u, payload, len);
    return qspi_tx(CMD_SDRAM_WRITE, sdram_addr, s_tx_buf, total_len, 0, override_freq_hz);
}

static bool indexed2_reg_write(uint32_t reg_addr, uint16_t value)
{
    esp_err_t err = qspi_reg_write(reg_addr, value, 0u);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2_REG_WRITE addr=0x%04" PRIX32 " value=0x%04X err=%s",
                 reg_addr, value, esp_err_to_name(err));
        return false;
    }
    // The word-drain QSPI top has no REG_READ opcode.  READ_STATUS sel=9 is
    // its authoritative last-register-write loopback, so capture it after
    // every indexed2 write for the hardware debug packet.
    uint32_t loopback = 0;
    esp_err_t read_err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &loopback,
                                          2, 0u);
    uint32_t expected = ((uint32_t)value << 16) | (reg_addr & 0xFFFFu);
    if (read_err == ESP_OK && loopback == expected) {
        ESP_LOGI(TAG, "INDEXED2_REG_WRITE addr=0x%04" PRIX32
                 " value=0x%04X loopback=0x%08" PRIX32 "%s",
                 reg_addr, value, loopback,
                 " PASS");
        return true;
    }
    if (read_err == ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2_REG_WRITE addr=0x%04" PRIX32
                 " value=0x%04X loopback=0x%08" PRIX32 " MISMATCH expect=0x%08" PRIX32,
                 reg_addr, value, loopback, expected);
    } else {
        ESP_LOGE(TAG, "INDEXED2_REG_WRITE addr=0x%04" PRIX32
                 " value=0x%04X loopback_read err=%s",
                 reg_addr, value, esp_err_to_name(read_err));
    }
    return false;
}

static void indexed2_log_prefix(const char *name, const uint8_t *bytes)
{
    if (name == NULL || bytes == NULL) {
        ESP_LOGE(TAG, "INDEXED2 prefix log rejected null argument");
        return;
    }
    for (size_t i = 0; i < 32u; i += 8u) {
        ESP_LOGI(TAG, "INDEXED2_%s[%u..%u] %02X %02X %02X %02X %02X %02X %02X %02X",
                 name, (unsigned)i, (unsigned)(i + 7u), bytes[i + 0u],
                 bytes[i + 1u], bytes[i + 2u], bytes[i + 3u], bytes[i + 4u],
                 bytes[i + 5u], bytes[i + 6u], bytes[i + 7u]);
    }
}

static bool indexed2_load_palette(void)
{
    static const uint32_t palette_rgb888[4] = {
        0x000000u, 0xFF0000u, 0x00FF00u, 0x0000FFu,
    };

    if (!indexed2_reg_write(0x0601u, 0u)) {
        return false;
    }
    for (size_t i = 0; i < 4u; ++i) {
        uint8_t r = (uint8_t)((palette_rgb888[i] >> 16) & 0xFFu);
        uint8_t g = (uint8_t)((palette_rgb888[i] >> 8) & 0xFFu);
        uint8_t b = (uint8_t)(palette_rgb888[i] & 0xFFu);
        if (!indexed2_reg_write(0x0600u, (uint16_t)(((uint16_t)g << 8) | b)) ||
            !indexed2_reg_write(0x0600u, r)) {
            return false;
        }
    }
    ESP_LOGI(TAG, "INDEXED2 palette loaded entries=4 writes=8");
    return true;
}

static void indexed2_build_image(void)
{
    memset(s_indexed2_bitmap, 0, sizeof(s_indexed2_bitmap));
    memset(s_indexed2_attr, 0xE4, sizeof(s_indexed2_attr));

    // Four vertical palette bars. Each byte packs four 2-bit pixels, and the
    // indexed fetch path displays each source pixel across two HDMI columns.
    for (size_t y = 0; y < INDEXED2_HEIGHT; ++y) {
        uint8_t *row = s_indexed2_bitmap + (y * INDEXED2_ROW_STRIDE);
        for (size_t x = 0; x < INDEXED2_WIDTH; ++x) {
            uint8_t palette_index = (uint8_t)(x / (INDEXED2_WIDTH / 4));
            size_t byte_index = x / 4u;
            unsigned shift = 6u - (unsigned)((x & 3u) * 2u);
            row[byte_index] |= (uint8_t)(palette_index << shift);
        }
    }
    ESP_LOGI(TAG, "INDEXED2 image generated width=%u height=%u row_bytes=%u stride=%u",
             INDEXED2_WIDTH, INDEXED2_HEIGHT, INDEXED2_ROW_BYTES, INDEXED2_ROW_STRIDE);
    indexed2_log_prefix("BITMAP_PREFIX", s_indexed2_bitmap);
    indexed2_log_prefix("ATTR_PREFIX", s_indexed2_attr);
}

static bool indexed2_upload_image(void)
{
    esp_err_t err = qspi_reconfigure_device(QSPI_SDRAM_CLOCK_HZ, s_input_delay_ns);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 SDRAM clock configure err=%s", esp_err_to_name(err));
        return false;
    }
    err = qspi_sdram_write(0x100000u, s_indexed2_bitmap,
                           sizeof(s_indexed2_bitmap), 0u);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 bitmap upload err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2 bitmap uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_indexed2_bitmap), qspi_get_actual_freq_hz());

    err = qspi_sdram_write(0x110000u, s_indexed2_attr,
                           sizeof(s_indexed2_attr), 0u);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 attr upload err=%s", esp_err_to_name(err));
        return false;
    }
    err = qspi_reconfigure_device(QSPI_FUNCTIONAL_CLOCK_HZ, s_input_delay_ns);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 functional clock restore err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2 attr uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_indexed2_attr), qspi_get_actual_freq_hz());
    return true;
}

static bool indexed2_expected_word(uint32_t addr, uint32_t *expected_out)
{
    const uint32_t bitmap_base = 0x100000u;
    const uint32_t attr_base = 0x110000u;
    const uint8_t *source = NULL;
    size_t offset = 0u;

    if (expected_out == NULL) {
        return false;
    }
    if (addr >= bitmap_base && (addr - bitmap_base) <= (sizeof(s_indexed2_bitmap) - 4u)) {
        source = s_indexed2_bitmap;
        offset = (size_t)(addr - bitmap_base);
    } else if (addr >= attr_base && (addr - attr_base) <= (sizeof(s_indexed2_attr) - 4u)) {
        source = s_indexed2_attr;
        offset = (size_t)(addr - attr_base);
    } else {
        return false;
    }
    *expected_out = bytes_to_u32_le(source + offset);
    return true;
}

static bool indexed2_readback_samples(void)
{
    // Upper/lower rows plus one word from each visible color bar and both
    // planes.  The arm protocol is fixed by BrightForge #14249:
    // 0x0326=address[15:0], 0x0327=address[22:16] and the HI write arms the
    // one-shot SDRAM read returned by READ_STATUS sel=8.
    static const uint32_t sample_addresses[] = {
        0x100000u, 0x100080u, 0x100100u,
        0x100014u, 0x100028u, 0x10003Cu,
        0x106400u, 0x106480u, 0x106500u,
        0x106414u, 0x106428u, 0x10643Cu,
        0x110000u, 0x110080u, 0x116400u,
    };
    bool pass = true;
    uint32_t first_bad_addr = 0u;
    uint32_t first_bad_expected = 0u;
    uint32_t first_bad_got = 0u;

    for (size_t i = 0; i < (sizeof(sample_addresses) / sizeof(sample_addresses[0])); ++i) {
        const uint32_t addr = sample_addresses[i];
        uint32_t expected = 0u;
        uint32_t got = 0u;
        if (!indexed2_expected_word(addr, &expected)) {
            ESP_LOGE(TAG, "INDEXED2_READBACK invalid sample addr=0x%06" PRIX32, addr);
            return false;
        }
        if (!indexed2_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)(addr & 0xFFFFu)) ||
            !indexed2_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)((addr >> 16) & 0x007Fu))) {
            ESP_LOGE(TAG, "INDEXED2_READBACK arm failed addr=0x%06" PRIX32, addr);
            return false;
        }
        esp_rom_delay_us(20u);
        esp_err_t err = qspi_read_status(READ_STATUS_SEL_SDRAM, &got, 2, 0u);
        if (err != ESP_OK || got != expected) {
            if (pass) {
                first_bad_addr = addr;
                first_bad_expected = expected;
                first_bad_got = got;
            }
            pass = false;
            ESP_LOGE(TAG,
                     "INDEXED2_READBACK FAIL addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " got=0x%08" PRIX32 " err=%s",
                     addr, expected, got, esp_err_to_name(err));
        } else {
            ESP_LOGI(TAG, "INDEXED2_READBACK PASS addr=0x%06" PRIX32 " value=0x%08" PRIX32,
                     addr, got);
        }
    }
    if (!pass) {
        ESP_LOGE(TAG,
                 "INDEXED2_READBACK_DONE pass=0 first_bad_addr=0x%06" PRIX32
                 " expected=0x%08" PRIX32 " got=0x%08" PRIX32,
                 first_bad_addr, first_bad_expected, first_bad_got);
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2_READBACK_DONE pass=1 samples=%u",
             (unsigned)(sizeof(sample_addresses) / sizeof(sample_addresses[0])));
    return true;
}

static bool indexed2_enable_linestate_l0(void)
{
    // LINESTATE prepare entries are one register per active display line.
    // The low 12 bits are {layer1Enable, layer0Enable, layer0ScrollX}; keep
    // both scroll and L1 disabled while enabling L0 for the indexed proof.
    for (uint16_t line = 0; line < 480u; ++line) {
        if (!indexed2_reg_write(line, 0x0800u)) {
            ESP_LOGE(TAG, "INDEXED2 LINESTATE write failed line=%u", line);
            return false;
        }
    }
    ESP_LOGI(TAG, "INDEXED2 LINESTATE L0 enabled lines=480 value=0x0800");
    return true;
}

static bool run_indexed2_proof(void)
{
    uint32_t magic = 0;
    uint32_t health_before = 0;
    uint32_t health_after_upload = 0;
    uint32_t health_after_enable = 0;
    bool readback_pass = false;
    bool ok = true;

    if (qspi_reconfigure_device(QSPI_FUNCTIONAL_CLOCK_HZ, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 functional clock configure failed");
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2_PROOF begin source=320x240 display=640x480 functional_freq=%" PRIu32
             " sdram_freq=%" PRIu32, QSPI_FUNCTIONAL_CLOCK_HZ, QSPI_SDRAM_CLOCK_HZ);
    ESP_LOGI(TAG, "INDEXED2_READ_REG note=QSPITop has no REG_READ; sel=9 loopback follows each REG_WRITE");
    if (qspi_read_status(READ_STATUS_SEL_MAGIC, &magic, 2, 0u) != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2_MAGIC read failed");
        return false;
    }
    if (magic != EXPECTED_MAGIC) {
        ESP_LOGE(TAG, "INDEXED2_MAGIC mismatch got=0x%08" PRIX32 " expect=0x%08" PRIX32,
                 magic, EXPECTED_MAGIC);
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2_MAGIC value=0x%08" PRIX32 " actual_freq=%" PRIu32,
             magic, qspi_get_actual_freq_hz());
    ok &= read_and_log_transport_health("INDEXED2_HEALTH_BEFORE", QSPI_FUNCTIONAL_CLOCK_HZ);
    if (!ok) {
        return false;
    }
    if (qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_before, 2, 0u) != ESP_OK) {
        return false;
    }

    indexed2_build_image();
    if (!indexed2_reg_write(0x0300u, 0x0000u) || // disable visible layers while loading
        !indexed2_reg_write(0x0313u, 0x0000u) || // native Mode0
        !indexed2_reg_write(0x0349u, 0x0000u) || // no integer scaler; indexed path doubles naturally
        !indexed2_reg_write(0x034Au, 640u) ||
        !indexed2_reg_write(0x034Bu, 480u) ||
        !indexed2_reg_write(0x0351u, 0x0000u) || // bitmap base 0x100000
        !indexed2_reg_write(0x0352u, 0x0010u) ||
        !indexed2_reg_write(0x0353u, 0x0000u) || // attribute base 0x110000
        !indexed2_reg_write(0x0354u, 0x0011u) ||
        !indexed2_reg_write(0x0355u, INDEXED2_ROW_STRIDE) ||
        !indexed2_reg_write(0x0356u, INDEXED2_ROW_STRIDE) ||
        !indexed2_reg_write(0x0357u, INDEXED2_HEIGHT) ||
        !indexed2_load_palette() ||
        !indexed2_reg_write(0x0350u, 0x0002u)) { // bpp=0b01, fetch disabled while uploading
        return false;
    }

    if (!indexed2_upload_image() ||
        !read_and_log_transport_health("INDEXED2_HEALTH_AFTER_UPLOAD", QSPI_FUNCTIONAL_CLOCK_HZ) ||
        qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_after_upload, 2, 0u) != ESP_OK) {
        return false;
    }
    readback_pass = indexed2_readback_samples();
    if (!readback_pass) {
        return false;
    }
    if (!indexed2_enable_linestate_l0() ||
        !indexed2_reg_write(0x0350u, 0x0003u) || // enable + BPP=0b01 (2bpp indexed)
        !indexed2_reg_write(0x0300u, 0x0001u)) { // enable bitmap layer L0
        return false;
    }

    vTaskDelay(pdMS_TO_TICKS(100));
    if (!read_and_log_transport_health("INDEXED2_HEALTH_AFTER_ENABLE", QSPI_FUNCTIONAL_CLOCK_HZ) ||
        qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_after_enable, 2, 0u) != ESP_OK) {
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2_PROOF_DONE pass=%u health_before=0x%08" PRIX32
             " health_after_upload=0x%08" PRIX32 " health_after_enable=0x%08" PRIX32
             " readback_pass=%u",
             (ok && readback_pass) ? 1u : 0u, health_before, health_after_upload,
             health_after_enable, readback_pass ? 1u : 0u);
    return ok && readback_pass;
}

static void report_result(const char *label, esp_err_t err, uint32_t got, uint32_t expect)
{
    if (err == ESP_OK && got == expect) {
        ESP_LOGI(TAG, "%s result=PASS value=0x%08" PRIX32, label, got);
    } else if (err == ESP_OK) {
        ESP_LOGW(TAG, "%s result=FAIL value=0x%08" PRIX32 " expect=0x%08" PRIX32,
                 label, got, expect);
    } else {
        ESP_LOGE(TAG, "%s result=ERR err=%s", label, esp_err_to_name(err));
    }
}

static uint32_t xorshift32(uint32_t *state)
{
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

static void make_pattern(uint32_t iteration, uint32_t *addr_out, uint16_t *value_out)
{
    static const uint16_t adversarial[] = {
        0x0000u, 0xFFFFu, 0xAAAAu, 0x5555u,
    };
    static uint32_t prng_state = 0x13579BDFu;
    uint32_t rnd = xorshift32(&prng_state);
    uint16_t value = 0;

    switch (iteration % 8u) {
    case 0:
    case 1:
    case 2:
    case 3:
        value = adversarial[iteration % 4u];
        break;
    case 4:
        value = (uint16_t)(1u << (iteration % 16u));
        break;
    case 5:
        value = (uint16_t)~(1u << (iteration % 16u));
        break;
    default:
        value = (uint16_t)(rnd & 0xFFFFu);
        break;
    }

    *addr_out = (((iteration * 73u) ^ (rnd >> 8)) & 0x7FFFu);
    *value_out = value;
}

static void reset_phase_stats(phase_stats_t *stats)
{
    memset(stats, 0, sizeof(*stats));
}

static uint32_t total_phase_errors(const phase_stats_t *stats)
{
    return stats->write_errors + stats->read_errors +
           stats->transient_read_mismatches + stats->stable_mismatches;
}

static bool run_cross_freq_roundtrip(uint32_t addr, uint16_t value, uint32_t write_freq_hz,
                                     uint32_t read_freq_hz, uint8_t dummy_bits,
                                     phase_stats_t *stats, uint32_t *got_out)
{
    uint32_t got = 0;
    uint32_t retry = 0;
    ESP_RETURN_ON_FALSE(stats != NULL, false, TAG, "null cross-frequency stats");
    uint32_t expect = ((uint32_t)value << 16) | (addr & 0xFFFFu);

    esp_err_t err = qspi_reg_write(addr, value, write_freq_hz);
    if (err != ESP_OK) {
        stats->write_errors++;
        return false;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &got, dummy_bits, read_freq_hz);
    if (err != ESP_OK) {
        stats->read_errors++;
        return false;
    }
    if (got == expect) {
        if (got_out != NULL) {
            *got_out = got;
        }
        return true;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &retry, dummy_bits, read_freq_hz);
    if (err == ESP_OK && retry == expect) {
        stats->transient_read_mismatches++;
        if (got_out != NULL) {
            *got_out = got;
        }
    } else {
        stats->stable_mismatches++;
        if (got_out != NULL) {
            *got_out = (err == ESP_OK) ? retry : got;
        }
    }
    return false;
}

static void run_cross_freq_ber(const char *label, uint32_t iterations,
                               uint32_t write_freq_hz, uint32_t read_freq_hz,
                               uint8_t dummy_bits, phase_stats_t *stats)
{
    if (label == NULL || stats == NULL || iterations == 0u ||
        write_freq_hz == 0u || read_freq_hz == 0u) {
        ESP_LOGE(TAG, "cross-frequency BER invalid arguments");
        return;
    }
    reset_phase_stats(stats);
    for (uint32_t i = 0; i < iterations; ++i) {
        uint32_t addr = 0;
        uint16_t value = 0;
        uint32_t got = 0;

        make_pattern(i, &addr, &value);
        bool ok = run_cross_freq_roundtrip(addr, value, write_freq_hz, read_freq_hz, dummy_bits, stats, &got);
        stats->iterations++;

        if (!ok && total_phase_errors(stats) <= 8u) {
            ESP_LOGW(TAG,
                     "%s mismatch iter=%" PRIu32 " addr=0x%04" PRIX32 " value=0x%04X got=0x%08" PRIX32
                     " write_freq=%" PRIu32 " read_freq=%" PRIu32 " dummy=%u input_delay=%" PRIu32,
                     label, i, addr, value, got, write_freq_hz, read_freq_hz, dummy_bits, s_input_delay_ns);
        }
        if ((i + 1u) % LONG_PHASE_YIELD_INTERVAL == 0u) {
            vTaskDelay(1);
        }
        if ((i + 1u) % PHASE1_PROGRESS_INTERVAL == 0u || i + 1u == iterations) {
            ESP_LOGI(TAG, "%s progress=%" PRIu32 "/%" PRIu32 " errors=%" PRIu32,
                     label, i + 1u, iterations, total_phase_errors(stats));
        }
    }
}

static bool run_burst_roundtrip(uint32_t base_addr, const uint16_t *words, size_t word_count,
                                uint32_t write_freq_hz, uint32_t read_freq_hz,
                                uint8_t dummy_bits, phase_stats_t *stats, uint32_t *got_out)
{
    uint32_t got = 0;
    uint32_t retry = 0;
    if (words == NULL || word_count == 0u || stats == NULL) {
        ESP_LOGE(TAG, "burst roundtrip invalid arguments");
        return false;
    }
    uint32_t expect_addr = (base_addr + (uint32_t)word_count - 1u) & 0xFFFFu;
    uint32_t expect = ((uint32_t)words[word_count - 1u] << 16) | expect_addr;

    esp_err_t err = qspi_reg_write_burst(base_addr, words, word_count, write_freq_hz);
    if (err != ESP_OK) {
        stats->write_errors++;
        return false;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &got, dummy_bits, read_freq_hz);
    if (err != ESP_OK) {
        stats->read_errors++;
        return false;
    }
    if (got == expect) {
        if (got_out != NULL) {
            *got_out = got;
        }
        return true;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &retry, dummy_bits, read_freq_hz);
    if (err == ESP_OK && retry == expect) {
        stats->transient_read_mismatches++;
        if (got_out != NULL) {
            *got_out = got;
        }
    } else {
        stats->stable_mismatches++;
        if (got_out != NULL) {
            *got_out = (err == ESP_OK) ? retry : got;
        }
    }
    return false;
}

static uint32_t qspi_get_actual_freq_hz(void)
{
    int freq_khz = 0;

    if (s_spi == NULL) {
        return 0;
    }
    if (spi_device_get_actual_freq(s_spi, &freq_khz) != ESP_OK || freq_khz <= 0) {
        return 0;
    }
    return (uint32_t)freq_khz * 1000u;
}

static bool phase_passed(const phase_stats_t *stats)
{
    return total_phase_errors(stats) == 0u;
}

static void log_phase_summary(const char *label, uint32_t requested_freq_hz,
                              uint8_t dummy_bits, const phase_stats_t *stats)
{
    uint32_t actual_freq_hz = qspi_get_actual_freq_hz();

    ESP_LOGI(TAG,
             "%s summary req_freq=%" PRIu32 " actual_freq=%" PRIu32
             " dummy=%u input_delay=%" PRIu32
             " iterations=%" PRIu32 " write_err=%" PRIu32 " read_err=%" PRIu32
             " transient_read_mismatch=%" PRIu32 " stable_mismatch=%" PRIu32,
             label, requested_freq_hz, actual_freq_hz, dummy_bits, s_input_delay_ns,
             stats->iterations, stats->write_errors, stats->read_errors,
             stats->transient_read_mismatches, stats->stable_mismatches);
}

static bool run_loopback_roundtrip(uint32_t addr, uint16_t value, uint32_t freq_hz, uint8_t dummy_bits,
                                   phase_stats_t *stats, uint32_t *got_out)
{
    uint32_t got = 0;
    uint32_t retry = 0;
    if (stats == NULL) {
        ESP_LOGE(TAG, "loopback roundtrip null stats");
        return false;
    }
    uint32_t expect = ((uint32_t)value << 16) | (addr & 0xFFFFu);
    esp_err_t err = qspi_reg_write(addr, value, freq_hz);
    if (err != ESP_OK) {
        stats->write_errors++;
        return false;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &got, dummy_bits, freq_hz);
    if (err != ESP_OK) {
        stats->read_errors++;
        return false;
    }
    if (got == expect) {
        if (got_out != NULL) {
            *got_out = got;
        }
        return true;
    }

    err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &retry, dummy_bits, freq_hz);
    if (err == ESP_OK && retry == expect) {
        stats->transient_read_mismatches++;
        if (got_out != NULL) {
            *got_out = got;
        }
    } else {
        stats->stable_mismatches++;
        if (got_out != NULL) {
            *got_out = (err == ESP_OK) ? retry : got;
        }
    }
    return false;
}

static void run_phase_ber(const char *label, uint32_t iterations, uint32_t freq_hz,
                          uint8_t dummy_bits, phase_stats_t *stats)
{
    if (label == NULL || stats == NULL || iterations == 0u || freq_hz == 0u) {
        ESP_LOGE(TAG, "phase BER invalid arguments");
        return;
    }
    reset_phase_stats(stats);
    if (qspi_reconfigure_device(freq_hz, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "%s frequency configure failed", label);
        stats->write_errors++;
        return;
    }
    for (uint32_t i = 0; i < iterations; ++i) {
        uint32_t addr = 0;
        uint16_t value = 0;
        uint32_t got = 0;

        make_pattern(i, &addr, &value);
        bool ok = run_loopback_roundtrip(addr, value, 0u, dummy_bits, stats, &got);
        stats->iterations++;

        if (!ok && total_phase_errors(stats) <= 8u) {
            ESP_LOGW(TAG,
                     "%s mismatch iter=%" PRIu32 " addr=0x%04" PRIX32 " value=0x%04X got=0x%08" PRIX32
                     " freq=%" PRIu32 " dummy=%u input_delay=%" PRIu32,
                     label, i, addr, value, got, freq_hz, dummy_bits, s_input_delay_ns);
        }
        if ((i + 1u) % LONG_PHASE_YIELD_INTERVAL == 0u) {
            vTaskDelay(1);
        }
        if ((i + 1u) % PHASE2_PROGRESS_INTERVAL == 0u || i + 1u == iterations) {
            ESP_LOGI(TAG, "%s progress=%" PRIu32 "/%" PRIu32 " errors=%" PRIu32,
                     label, i + 1u, iterations, total_phase_errors(stats));
        }
    }
}

static bool run_proof_ladder(uint32_t freq_hz)
{
    uint32_t magic = 0;

    ESP_LOGI(TAG, "P4_QSPI_PROOF_PHASE0 begin req_freq=%" PRIu32, freq_hz);
    if (qspi_reconfigure_device(freq_hz, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_PROOF_PHASE0 frequency configure failed");
        return false;
    }
    for (uint32_t attempt = 0; attempt < 1000u; ++attempt) {
        esp_err_t err = qspi_read_status(READ_STATUS_SEL_MAGIC, &magic, 2, freq_hz);
        if (err == ESP_OK && magic == EXPECTED_MAGIC) {
            ESP_LOGI(TAG, "P4_QSPI_MAGIC result=PASS attempt=%" PRIu32 " value=0x%08" PRIX32,
                     attempt + 1u, magic);
            log_hex_bytes("P4_QSPI_MAGIC bytes=", s_rx_buf, 4);
            break;
        }

        if (err == ESP_OK) {
            ESP_LOGW(TAG, "P4_QSPI_MAGIC result=WAIT attempt=%" PRIu32 " value=0x%08" PRIX32,
                     attempt + 1u, magic);
            log_hex_bytes("P4_QSPI_MAGIC bytes=", s_rx_buf, 4);
        } else {
            ESP_LOGW(TAG, "P4_QSPI_MAGIC result=ERR attempt=%" PRIu32 " err=%s",
                     attempt + 1u, esp_err_to_name(err));
        }
        vTaskDelay(pdMS_TO_TICKS(250));
    }

    if (magic != EXPECTED_MAGIC) {
        ESP_LOGE(TAG, "magic read never converged; aborting stress phases");
        return false;
    }

    {
        const uint8_t reg_write_payload[4] = {0x01u, 0x00u, 0x5Au, 0xA5u};
        esp_err_t write_err = qspi_reg_write(REG_WRITE_ADDR, REG_WRITE_VALUE, 0u);
        if (write_err != ESP_OK) {
            ESP_LOGE(TAG, "P4_QSPI_REG_WRITE result=ERR err=%s", esp_err_to_name(write_err));
            return false;
        }
        ESP_LOGI(TAG, "P4_QSPI_REG_WRITE result=PASS addr=0x%04" PRIX32 " value=0x%04X tx=%02X %02X %02X %02X",
                 REG_WRITE_ADDR, REG_WRITE_VALUE,
                 reg_write_payload[0], reg_write_payload[1], reg_write_payload[2], reg_write_payload[3]);
    }

    {
        const uint8_t loopback_prime_payload[4] = {0x01u, 0x00u, 0x34u, 0x12u};
        uint32_t loopback = 0;
        esp_err_t write_err = qspi_reg_write(LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE, 0u);
        if (write_err != ESP_OK) {
            ESP_LOGE(TAG, "P4_QSPI_LOOPBACK_PRIME result=ERR err=%s", esp_err_to_name(write_err));
            return false;
        }
        ESP_LOGI(TAG, "P4_QSPI_LOOPBACK_PRIME result=PASS addr=0x%04" PRIX32 " value=0x%04X tx=%02X %02X %02X %02X",
                 LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE,
                 loopback_prime_payload[0], loopback_prime_payload[1],
                 loopback_prime_payload[2], loopback_prime_payload[3]);
        esp_err_t read_err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &loopback, 2, 0u);
        report_result("P4_QSPI_LOOPBACK", read_err, loopback, EXPECTED_LOOPBACK);
        if (read_err != ESP_OK || loopback != EXPECTED_LOOPBACK) {
            return false;
        }
        log_hex_bytes("P4_QSPI_LOOPBACK bytes=", s_rx_buf, 4);
    }

    {
        const uint8_t sdram_payload[4] = {0xDEu, 0xADu, 0xBEu, 0xEFu};
        esp_err_t write_err = qspi_sdram_write(0x001000u, sdram_payload, sizeof(sdram_payload), 0u);
        if (write_err != ESP_OK) {
            ESP_LOGE(TAG, "P4_QSPI_SDRAM_WRITE result=ERR err=%s", esp_err_to_name(write_err));
            return false;
        }
        ESP_LOGI(TAG, "P4_QSPI_SDRAM_WRITE result=PASS addr=0x001000 tx=%02X %02X %02X %02X %02X %02X",
                 0x02u, 0x00u, sdram_payload[0], sdram_payload[1], sdram_payload[2], sdram_payload[3]);
    }

    return true;
}

static bool read_and_log_hdr_err_status(const char *label, uint32_t freq_hz,
                                        uint32_t *raw_out, uint16_t *count_out, bool *sticky_out)
{
    uint32_t raw = 0;
    uint16_t count = 0;
    bool sticky = false;
    esp_err_t err = qspi_read_hdr_err_status(&raw, &count, &sticky, freq_hz);

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "%s result=ERR err=%s", label, esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG,
             "%s raw=0x%08" PRIX32 " count=%u sticky=%u req_freq=%" PRIu32 " actual_freq=%" PRIu32,
             label, raw, (unsigned)count, sticky ? 1u : 0u, freq_hz, qspi_get_actual_freq_hz());
    if (raw_out != NULL) {
        *raw_out = raw;
    }
    if (count_out != NULL) {
        *count_out = count;
    }
    if (sticky_out != NULL) {
        *sticky_out = sticky;
    }
    return true;
}

static void run_magic_only_ber(const char *label, uint32_t iterations, uint32_t freq_hz,
                               phase_stats_t *stats)
{
    if (label == NULL || stats == NULL || iterations == 0u || freq_hz == 0u) {
        ESP_LOGE(TAG, "magic BER invalid arguments");
        return;
    }
    reset_phase_stats(stats);
    for (uint32_t i = 0; i < iterations; ++i) {
        uint32_t got = 0;
        esp_err_t err = qspi_read_status(READ_STATUS_SEL_MAGIC, &got, 2, freq_hz);
        stats->iterations++;

        if (err != ESP_OK) {
            stats->read_errors++;
            if (total_phase_errors(stats) <= 8u) {
                ESP_LOGW(TAG, "%s mismatch iter=%" PRIu32 " err=%s freq=%" PRIu32 " actual_freq=%" PRIu32,
                         label, i, esp_err_to_name(err), freq_hz, qspi_get_actual_freq_hz());
            }
        } else if (got != EXPECTED_MAGIC) {
            stats->stable_mismatches++;
            if (total_phase_errors(stats) <= 8u) {
                ESP_LOGW(TAG,
                         "%s mismatch iter=%" PRIu32 " got=0x%08" PRIX32 " expect=0x%08" PRIX32
                         " freq=%" PRIu32 " actual_freq=%" PRIu32,
                         label, i, got, EXPECTED_MAGIC, freq_hz, qspi_get_actual_freq_hz());
            }
        }

        if ((i + 1u) % 1000u == 0u || i + 1u == iterations) {
            ESP_LOGI(TAG, "%s progress=%" PRIu32 "/%" PRIu32 " errors=%" PRIu32,
                     label, i + 1u, iterations, total_phase_errors(stats));
        }
    }
}

static esp_err_t probe_clock_rate(const char *label, uint32_t freq_hz, phase_stats_t *stats,
                                  uint32_t *actual_freq_out)
{
    ESP_RETURN_ON_FALSE(label != NULL, ESP_ERR_INVALID_ARG, TAG, "null clock probe label");
    ESP_RETURN_ON_FALSE(stats != NULL, ESP_ERR_INVALID_ARG, TAG, "null clock probe stats");
    ESP_RETURN_ON_FALSE(freq_hz != 0u, ESP_ERR_INVALID_ARG, TAG, "zero clock probe frequency");
    reset_phase_stats(stats);
    ESP_LOGI(TAG, "%s reconfigure_device req_freq=%" PRIu32, label, freq_hz);
    ESP_RETURN_ON_ERROR(qspi_reconfigure_device(freq_hz, s_input_delay_ns), TAG, "reconfigure failed");
    run_magic_only_ber(label, CLOCK_PROBE_ITERATIONS, freq_hz, stats);
    log_phase_summary(label, freq_hz, 2, stats);
    if (actual_freq_out != NULL) {
        *actual_freq_out = qspi_get_actual_freq_hz();
    }
    return phase_passed(stats) ? ESP_OK : ESP_FAIL;
}

static bool run_phase3_throughput(uint32_t freq_hz)
{
    static const size_t burst_sizes[] = {256u, 1024u, 4096u, 16384u, 65536u};
    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(i & 0xFFu);
    }

    ESP_LOGI(TAG, "P4_QSPI_PHASE3 note=current bring-up top has no real SDRAM backpressure; this phase is throughput-only");
    bool pass = qspi_reconfigure_device(freq_hz, s_input_delay_ns) == ESP_OK;
    if (!pass) {
        ESP_LOGE(TAG, "P4_QSPI_PHASE3 frequency configure failed");
        return false;
    }
    for (size_t i = 0; i < sizeof(burst_sizes) / sizeof(burst_sizes[0]); ++i) {
        uint32_t bytes_remaining = (uint32_t)burst_sizes[i];
        uint32_t addr = 0x001000u;
        uint32_t chunks = 0;
        uint32_t verify = 0;
        int64_t start_us = esp_timer_get_time();

        while (bytes_remaining != 0u) {
            size_t chunk = bytes_remaining;
            if (chunk > sizeof(s_phase3_pattern)) {
                chunk = sizeof(s_phase3_pattern);
            }
            if ((chunk & 1u) != 0u) {
                chunk--;
            }
            if (chunk == 0u) {
                ESP_LOGE(TAG, "P4_QSPI_PHASE3 rejected zero-length chunk remaining=%" PRIu32,
                         bytes_remaining);
                pass = false;
                break;
            }

            esp_err_t err = qspi_sdram_write(addr, s_phase3_pattern, chunk, 0u);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "P4_QSPI_PHASE3 sdram_write err=%s addr=0x%06" PRIX32 " chunk=%u",
                         esp_err_to_name(err), addr, (unsigned)chunk);
                pass = false;
                break;
            }
            addr += (uint32_t)chunk;
            bytes_remaining -= (uint32_t)chunk;
            chunks++;
        }

        esp_err_t verify_err = qspi_read_status(READ_STATUS_SEL_LOOPBACK, &verify, 2, 0u);
        if (verify_err != ESP_OK) {
            ESP_LOGE(TAG, "P4_QSPI_PHASE3 loopback read err=%s", esp_err_to_name(verify_err));
            pass = false;
        }
        {
            int64_t end_us = esp_timer_get_time();
            uint32_t actual_freq_hz = qspi_get_actual_freq_hz();
            double seconds = (double)(end_us - start_us) / 1000000.0;
            double mbps = seconds > 0.0 ? ((double)burst_sizes[i] / (1024.0 * 1024.0)) / seconds : 0.0;
            ESP_LOGI(TAG,
                     "P4_QSPI_PHASE3 size=%u chunks=%" PRIu32
                     " req_freq=%" PRIu32 " actual_freq=%" PRIu32
                     " duration_us=%" PRId64 " throughput_MBps=%.3f loopback_status=0x%08" PRIX32,
                     (unsigned)burst_sizes[i], chunks, freq_hz, actual_freq_hz,
                     end_us - start_us, mbps, verify);
        }
    }
    return pass;
}

static bool run_write_throughput_80m(phase_stats_t *stats)
{
    static const size_t burst_sizes[] = {256u, 1024u, 4096u, 16384u, 65536u, 131072u};
    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(0xA5u ^ (i & 0xFFu));
    }

    if (stats == NULL) {
        ESP_LOGE(TAG, "P4_QSPI_WRITE_AT_80 null stats");
        return false;
    }
    reset_phase_stats(stats);
    if (qspi_reconfigure_device(QSPI_EIGHTY_CLOCK_HZ, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_WRITE_AT_80 frequency configure failed");
        stats->write_errors++;
        return false;
    }
    ESP_LOGI(TAG, "P4_QSPI_WRITE_AT_80 note=throughput proof only on this top; no SDRAM storage or sel=8 readback is wired");
    for (size_t i = 0; i < sizeof(burst_sizes) / sizeof(burst_sizes[0]); ++i) {
        uint32_t bytes_remaining = (uint32_t)burst_sizes[i];
        uint32_t addr = 0x001000u;
        uint32_t chunks = 0;
        int64_t start_us = esp_timer_get_time();

        while (bytes_remaining != 0u) {
            size_t chunk = bytes_remaining;
            if (chunk > sizeof(s_phase3_pattern)) {
                chunk = sizeof(s_phase3_pattern);
            }
            if ((chunk & 1u) != 0u) {
                chunk--;
            }
            if (chunk == 0u) {
                ESP_LOGE(TAG, "P4_QSPI_WRITE_AT_80 rejected zero-length chunk remaining=%" PRIu32,
                         bytes_remaining);
                stats->write_errors++;
                return false;
            }

            esp_err_t err = qspi_sdram_write(addr, s_phase3_pattern, chunk, 0u);
            if (err != ESP_OK) {
                stats->write_errors++;
                ESP_LOGW(TAG, "P4_QSPI_WRITE_AT_80 sdram_write err=%s addr=0x%06" PRIX32 " chunk=%u",
                         esp_err_to_name(err), addr, (unsigned)chunk);
            }
            addr += (uint32_t)chunk;
            bytes_remaining -= (uint32_t)chunk;
            chunks++;
        }
        stats->iterations++;

        int64_t end_us = esp_timer_get_time();
        double seconds = (double)(end_us - start_us) / 1000000.0;
        double mbps = seconds > 0.0 ? ((double)burst_sizes[i] / (1024.0 * 1024.0)) / seconds : 0.0;
        ESP_LOGI(TAG,
                 "P4_QSPI_WRITE_AT_80 size=%u chunks=%" PRIu32
                 " req_freq=%" PRIu32 " actual_freq=%" PRIu32
                 " duration_us=%" PRId64 " throughput_MBps=%.3f",
                 (unsigned)burst_sizes[i], chunks, QSPI_EIGHTY_CLOCK_HZ, qspi_get_actual_freq_hz(),
                 end_us - start_us, mbps);
    }
    return stats->write_errors == 0u;
}

static bool run_bulk_write_smoke_80m(const char *label, uint32_t total_bytes, phase_stats_t *stats)
{
    uint32_t bytes_remaining = total_bytes;
    uint32_t addr = 0x001000u;
    uint32_t chunks = 0;
    int64_t start_us = 0;

    if (label == NULL || stats == NULL || total_bytes == 0u || (total_bytes & 1u) != 0u) {
        ESP_LOGE(TAG, "bulk smoke invalid arguments");
        return false;
    }
    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(0x5Au ^ (i & 0xFFu));
    }

    reset_phase_stats(stats);
    if (qspi_reconfigure_device(QSPI_EIGHTY_CLOCK_HZ, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "%s frequency configure failed", label);
        stats->write_errors++;
        return false;
    }
    start_us = esp_timer_get_time();
    while (bytes_remaining != 0u) {
        size_t chunk = bytes_remaining;
        if (chunk > sizeof(s_phase3_pattern)) {
            chunk = sizeof(s_phase3_pattern);
        }
        if ((chunk & 1u) != 0u) {
            chunk--;
        }
        if (chunk == 0u) {
            ESP_LOGE(TAG, "%s rejected zero-length chunk remaining=%" PRIu32, label, bytes_remaining);
            stats->write_errors++;
            return false;
        }

        esp_err_t err = qspi_sdram_write(addr, s_phase3_pattern, chunk, 0u);
        if (err != ESP_OK) {
            stats->write_errors++;
            ESP_LOGW(TAG, "%s sdram_write err=%s addr=0x%06" PRIX32 " chunk=%u",
                     label, esp_err_to_name(err), addr, (unsigned)chunk);
        }
        addr += (uint32_t)chunk;
        bytes_remaining -= (uint32_t)chunk;
        chunks++;
    }
    stats->iterations = 1u;

    {
        int64_t end_us = esp_timer_get_time();
        double seconds = (double)(end_us - start_us) / 1000000.0;
        double mbps = seconds > 0.0 ? ((double)total_bytes / (1024.0 * 1024.0)) / seconds : 0.0;
        ESP_LOGI(TAG,
                 "%s summary size=%" PRIu32 " chunks=%" PRIu32
                 " req_freq=%" PRIu32 " actual_freq=%" PRIu32
                 " duration_us=%" PRId64 " throughput_MBps=%.3f write_err=%" PRIu32,
                 label, total_bytes, chunks, QSPI_EIGHTY_CLOCK_HZ, qspi_get_actual_freq_hz(),
                 end_us - start_us, mbps, stats->write_errors);
    }
    return stats->write_errors == 0u;
}

static void run_phase4_back_to_back(uint32_t freq_hz, uint8_t dummy_bits, phase_stats_t *stats);

static bool relax_task_wdt_for_bench(void)
{
#if defined(CONFIG_FREERTOS_NUMBER_OF_CORES)
    const uint32_t idle_core_mask = (1u << CONFIG_FREERTOS_NUMBER_OF_CORES) - 1u;
#else
    const uint32_t idle_core_mask = 0x1u;
#endif
    const esp_task_wdt_config_t config = {
        .timeout_ms = 300000u,
        .idle_core_mask = idle_core_mask,
        .trigger_panic = true,
    };

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN task_wdt timeout_ms=%" PRIu32 " idle_core_mask=0x%" PRIX32,
             config.timeout_ms, config.idle_core_mask);
    esp_err_t err = esp_task_wdt_reconfigure(&config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_CAMPAIGN task_wdt reconfigure err=%s", esp_err_to_name(err));
        return false;
    }
    return true;
}

static void fill_reg_burst_words(uint32_t base_addr, size_t word_count)
{
    for (size_t i = 0; i < word_count; ++i) {
        s_reg_burst_words[i] = (uint16_t)(0xA500u ^ (uint16_t)(base_addr + (uint32_t)i));
    }
}

static void fill_reg_burst_words_for_length_sweep(size_t word_count)
{
    if (word_count == 0u || word_count > PHASE3_BURST_WORDS) {
        ESP_LOGE(TAG, "invalid register burst sweep length=%u", (unsigned)word_count);
        return;
    }
    for (size_t i = 0; i < word_count; ++i) {
        s_reg_burst_words[i] = (uint16_t)(0x5A00u ^ (uint16_t)i);
    }
    s_reg_burst_words[word_count - 1u] = (uint16_t)word_count;
}

static void run_reg_burst_matrix(const char *label, uint32_t write_freq_hz, uint32_t read_freq_hz,
                                 phase_stats_t *stats_out)
{
    static const size_t burst_words[] = {1024u, 2048u};
    phase_stats_t stats = {0};

    ESP_LOGI(TAG,
             "%s note=endpoint-only verification on this top; host-visible readback is final loopback word only",
             label);
    for (size_t i = 0; i < sizeof(burst_words) / sizeof(burst_words[0]); ++i) {
        uint32_t got = 0;
        uint32_t base_addr = 0x0400u + (uint32_t)(i * 0x200u);
        size_t count = burst_words[i];

        fill_reg_burst_words(base_addr, count);
        uint32_t expect_addr = (base_addr + (uint32_t)count - 1u) & 0xFFFFu;
        uint32_t expect = ((uint32_t)s_reg_burst_words[count - 1u] << 16) | expect_addr;
        if (!run_burst_roundtrip(base_addr, s_reg_burst_words, count, write_freq_hz, read_freq_hz, 2, &stats, &got)) {
            ESP_LOGW(TAG,
                     "%s burst size=%u mismatch base=0x%04" PRIX32
                     " expect_last=0x%08" PRIX32 " got=0x%08" PRIX32
                     " write_freq=%" PRIu32 " read_freq=%" PRIu32,
                     label, (unsigned)count, base_addr, expect, got, write_freq_hz, read_freq_hz);
        } else {
            ESP_LOGI(TAG,
                     "%s burst size=%u verified base=0x%04" PRIX32 " last=0x%08" PRIX32
                     " write_freq=%" PRIu32 " read_freq=%" PRIu32,
                     label, (unsigned)count, base_addr, got, write_freq_hz, read_freq_hz);
        }
        stats.iterations++;
    }

    log_phase_summary(label, write_freq_hz, 2, &stats);
    if (stats_out != NULL) {
        *stats_out = stats;
    }
}

static void run_reg_burst_length_sweep(const char *label, uint32_t write_freq_hz, uint32_t read_freq_hz)
{
    static const size_t burst_words[] = {256u, 512u, 600u, 700u, 750u, 780u, 800u, 850u, 1024u};
    const uint32_t base_addr = 0x0800u;
    phase_stats_t stats = {0};
    uint32_t first_fail_words = 0;

    ESP_LOGI(TAG,
             "%s note=length-threshold sweep; final loopback word encodes burst length N",
             label);
    for (size_t i = 0; i < sizeof(burst_words) / sizeof(burst_words[0]); ++i) {
        uint32_t got = 0;
        size_t count = burst_words[i];
        uint32_t expect_addr = (base_addr + (uint32_t)count - 1u) & 0xFFFFu;
        uint32_t expect = ((uint32_t)(uint16_t)count << 16) | expect_addr;
        int64_t start_us = esp_timer_get_time();

        fill_reg_burst_words_for_length_sweep(count);
        if (!run_burst_roundtrip(base_addr, s_reg_burst_words, count, write_freq_hz, read_freq_hz, 2, &stats, &got)) {
            if (first_fail_words == 0u) {
                first_fail_words = (uint32_t)count;
            }
            ESP_LOGW(TAG,
                     "%s N=%u FAIL expect_last=0x%08" PRIX32 " got=0x%08" PRIX32
                     " duration_us=%" PRId64 " write_freq=%" PRIu32 " read_freq=%" PRIu32,
                     label, (unsigned)count, expect, got, esp_timer_get_time() - start_us,
                     write_freq_hz, read_freq_hz);
        } else {
            ESP_LOGI(TAG,
                     "%s N=%u PASS last=0x%08" PRIX32
                     " duration_us=%" PRId64 " write_freq=%" PRIu32 " read_freq=%" PRIu32,
                     label, (unsigned)count, got, esp_timer_get_time() - start_us,
                     write_freq_hz, read_freq_hz);
        }
        stats.iterations++;
    }

    ESP_LOGI(TAG,
             "%s threshold_summary first_fail_words=%" PRIu32 " first_fail_bytes=%" PRIu32,
             label, first_fail_words, first_fail_words * 2u);
    log_phase_summary(label, write_freq_hz, 2, &stats);
}

static bool run_definitive_campaign(void)
{
    phase_stats_t phase1_40 = {0};
    phase_stats_t phase1_80 = {0};
    phase_stats_t phase2 = {0};
    phase_stats_t phase3_80 = {0};
    phase_stats_t phase4 = {0};
    uint32_t final_loopback = 0;
    bool phase1_40_health_ok = false;
    bool phase1_80_health_ok = false;
    bool phase2_health_ok = false;
    bool phase3_80_health_ok = false;
    bool phase4_health_ok = false;
    bool final_verify_ok = false;
    bool final_health_ok = false;

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase1 begin 40W/40R");
    run_cross_freq_ber("P4_QSPI_PHASE1_40W40R", PHASE1_ITERATIONS,
                       QSPI_FUNCTIONAL_CLOCK_HZ, QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase1_40);
    log_phase_summary("P4_QSPI_PHASE1_40W40R", QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase1_40);
    phase1_40_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_40W40R_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase1 begin 80W/40R");
    run_cross_freq_ber("P4_QSPI_PHASE1_80W40R", PHASE1_ITERATIONS,
                       QSPI_EIGHTY_CLOCK_HZ, QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase1_80);
    log_phase_summary("P4_QSPI_PHASE1_80W40R", QSPI_EIGHTY_CLOCK_HZ, 2, &phase1_80);
    phase1_80_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_80W40R_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase2 begin min-gap hammer @40W/40R");
    run_phase_ber("P4_QSPI_PHASE2_HAMMER", PHASE2_ITERATIONS, QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase2);
    log_phase_summary("P4_QSPI_PHASE2_HAMMER", QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase2);
    phase2_health_ok = read_and_log_transport_health("P4_QSPI_PHASE2_HAMMER_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase3 begin REG_WRITE bursts @80W/40R");
    run_reg_burst_matrix("P4_QSPI_PHASE3_BURST_80W40R", QSPI_EIGHTY_CLOCK_HZ, QSPI_FUNCTIONAL_CLOCK_HZ, &phase3_80);
    phase3_80_health_ok = read_and_log_transport_health("P4_QSPI_PHASE3_BURST_80W40R_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase4 begin back-to-back control @40W/40R");
    run_phase4_back_to_back(QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase4);
    log_phase_summary("P4_QSPI_PHASE4", QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase4);
    phase4_health_ok = read_and_log_transport_health("P4_QSPI_PHASE4_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN final control verify @40W/40R after 80W upload");
    if (qspi_reconfigure_device(QSPI_FUNCTIONAL_CLOCK_HZ, s_input_delay_ns) != ESP_OK ||
        qspi_reg_write(LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE, 0u) != ESP_OK ||
        qspi_read_status(READ_STATUS_SEL_LOOPBACK, &final_loopback, 2, 0u) != ESP_OK ||
        final_loopback != EXPECTED_LOOPBACK) {
        ESP_LOGW(TAG,
                 "P4_QSPI_CAMPAIGN_FINAL_VERIFY failed got=0x%08" PRIX32 " expect=0x%08" PRIX32
                 " actual_freq=%" PRIu32,
                 final_loopback, EXPECTED_LOOPBACK, qspi_get_actual_freq_hz());
    } else {
        ESP_LOGI(TAG,
                 "P4_QSPI_CAMPAIGN_FINAL_VERIFY PASS value=0x%08" PRIX32
                 " actual_freq=%" PRIu32,
                 final_loopback, qspi_get_actual_freq_hz());
        final_verify_ok = true;
    }
    final_health_ok = read_and_log_transport_health("P4_QSPI_CAMPAIGN_FINAL_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    {
        bool pass = phase_passed(&phase1_40) &&
                    phase_passed(&phase1_80) &&
                    phase_passed(&phase2) &&
                    phase_passed(&phase3_80) &&
                    phase_passed(&phase4) &&
                    phase1_40_health_ok &&
                    phase1_80_health_ok &&
                    phase2_health_ok &&
                    phase3_80_health_ok &&
                    phase4_health_ok &&
                    final_verify_ok &&
                    final_health_ok;
        ESP_LOGI(TAG,
                 "P4_QSPI_CAMPAIGN_DONE pass=%u"
                 " phase1_40_err=%" PRIu32
                 " phase1_80_err=%" PRIu32
                 " phase2_err=%" PRIu32
                 " phase3_80_err=%" PRIu32
                 " phase4_err=%" PRIu32
                 " final_verify=%u final_health=%u",
                 pass ? 1u : 0u,
                 total_phase_errors(&phase1_40),
                 total_phase_errors(&phase1_80),
                 total_phase_errors(&phase2),
                 total_phase_errors(&phase3_80),
                 total_phase_errors(&phase4),
                 final_verify_ok ? 1u : 0u,
                 final_health_ok ? 1u : 0u);
        return pass;
    }
}

static bool run_short_transport_smoke(void)
{
    phase_stats_t phase1_40 = {0};
    phase_stats_t bulk80 = {0};
    bool phase1_40_health_ok = false;
    bool bulk80_health_ok = false;

    ESP_LOGI(TAG, "P4_QSPI_SHORT_SMOKE phase1 begin 40W/40R roundtrips=%" PRIu32,
             SHORT_SMOKE_ROUNDTRIPS);
    run_cross_freq_ber("P4_QSPI_PHASE1_40W40R", SHORT_SMOKE_ROUNDTRIPS,
                       QSPI_FUNCTIONAL_CLOCK_HZ, QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase1_40);
    log_phase_summary("P4_QSPI_PHASE1_40W40R", QSPI_FUNCTIONAL_CLOCK_HZ, 2, &phase1_40);
    phase1_40_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_40W40R_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    ESP_LOGI(TAG, "P4_QSPI_SHORT_SMOKE phase2 begin 80W bulk bytes=%" PRIu32,
             SHORT_SMOKE_BULK_BYTES);
    bool bulk80_pass = run_bulk_write_smoke_80m("P4_QSPI_BULK_WRITE_80M", SHORT_SMOKE_BULK_BYTES, &bulk80);
    bulk80_health_ok = read_and_log_transport_health("P4_QSPI_BULK_WRITE_80M_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);

    {
        bool pass = phase_passed(&phase1_40) &&
                    bulk80_pass && phase_passed(&bulk80) &&
                    phase1_40_health_ok &&
                    bulk80_health_ok;
        ESP_LOGI(TAG,
                 "P4_QSPI_SHORT_SMOKE_DONE pass=%u"
                 " phase1_40_err=%" PRIu32
                 " bulk80_err=%" PRIu32
                 " phase1_40_health=%u bulk80_health=%u",
                 pass ? 1u : 0u,
                 total_phase_errors(&phase1_40),
                 total_phase_errors(&bulk80),
                 phase1_40_health_ok ? 1u : 0u,
                 bulk80_health_ok ? 1u : 0u);
        return pass;
    }
}

static void run_phase4_back_to_back(uint32_t freq_hz, uint8_t dummy_bits, phase_stats_t *stats)
{
    int64_t start_us = esp_timer_get_time();
    int64_t next_progress_us = start_us + 60ll * 1000000ll;

    if (stats == NULL || freq_hz == 0u) {
        ESP_LOGE(TAG, "P4_QSPI_PHASE4 invalid arguments");
        return;
    }
    reset_phase_stats(stats);
    if (qspi_reconfigure_device(freq_hz, s_input_delay_ns) != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_PHASE4 frequency configure failed");
        stats->write_errors++;
        return;
    }
    ESP_LOGI(TAG,
             "P4_QSPI_PHASE4 note=driver exposes no explicit inter-CS gap knob; this run uses minimum implicit gap from back-to-back polling transactions");
    ESP_LOGI(TAG, "P4_QSPI_PHASE4 target duration_s=%" PRIu64,
             PHASE4_DURATION_US / 1000000ull);

    while ((uint64_t)(esp_timer_get_time() - start_us) < PHASE4_DURATION_US) {
        uint32_t addr = 0;
        uint16_t value = 0;
        uint32_t got = 0;

        make_pattern(stats->iterations, &addr, &value);
        bool ok = run_loopback_roundtrip(addr, value, 0u, dummy_bits, stats, &got);
        stats->iterations++;

        if (!ok && total_phase_errors(stats) <= 8u) {
            ESP_LOGW(TAG,
                     "P4_QSPI_PHASE4 mismatch iter=%" PRIu32 " addr=0x%04" PRIX32
                     " value=0x%04X got=0x%08" PRIX32
                     " freq=%" PRIu32 " dummy=%u input_delay=%" PRIu32,
                     stats->iterations - 1u, addr, value, got, freq_hz, dummy_bits, s_input_delay_ns);
        }
        if ((stats->iterations % LONG_PHASE_YIELD_INTERVAL) == 0u) {
            vTaskDelay(1);
        }
        if (esp_timer_get_time() >= next_progress_us) {
            uint64_t elapsed_us = (uint64_t)(esp_timer_get_time() - start_us);
            uint64_t approx_payload_bytes = (uint64_t)stats->iterations * PHASE4_APPROX_PAYLOAD_BYTES_PER_ITER;
            ESP_LOGI(TAG,
                     "P4_QSPI_PHASE4 progress elapsed_s=%" PRIu64 " iterations=%" PRIu32
                     " approx_payload_bytes=%" PRIu64 " errors=%" PRIu32,
                     elapsed_us / 1000000ull, stats->iterations,
                     approx_payload_bytes, total_phase_errors(stats));
            while (esp_timer_get_time() >= next_progress_us) {
                next_progress_us += 60ll * 1000000ll;
            }
        }
    }
}

void app_main(void)
{
    uint32_t magic = 0;
    esp_err_t err = ESP_OK;
    phase_stats_t write80 = {0};
    bool overall_pass = true;
    bool any_test_enabled = false;

    ESP_LOGI(TAG, "P4_QSPI_PROOF start");
    ESP_LOGI(TAG, "pins sclk=%d cs=%d io0=%d io1=%d io2=%d io3=%d",
             PIN_SCLK, PIN_CS, PIN_IO0, PIN_IO1, PIN_IO2, PIN_IO3);
    ESP_LOGI(TAG, "qspi clock=%" PRIu32 " dummy_bits=2 addr_bits=24 cmd_bits=8 parity=%s",
             QSPI_DEFAULT_CLOCK_HZ, s_use_header_parity ? "on" : "off");

    s_tx_buf = heap_caps_aligned_calloc(64, 1, DMA_BUF_SIZE, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    s_rx_buf = heap_caps_aligned_calloc(64, 1, DMA_BUF_SIZE, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    ESP_LOGI(TAG, "dma buf tx=%p rx=%p size=%" PRIu32, (void *)s_tx_buf, (void *)s_rx_buf, DMA_BUF_SIZE);
    if (s_tx_buf == NULL || s_rx_buf == NULL) {
        ESP_LOGE(TAG, "DMA buffer allocation failed");
        abort();
    }

    vTaskDelay(pdMS_TO_TICKS(200));
    err = qspi_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_INIT result=ERR err=%s", esp_err_to_name(err));
        return;
    }
    ESP_LOGI(TAG, "P4_QSPI_INIT actual_freq=%" PRIu32, qspi_get_actual_freq_hz());

    err = qspi_reconfigure_device(QSPI_FUNCTIONAL_CLOCK_HZ, s_input_delay_ns);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI functional clock configure err=%s", esp_err_to_name(err));
        return;
    }
    err = qspi_read_status(READ_STATUS_SEL_MAGIC, &magic, 2, 0u);
    bool magic_pass = (err == ESP_OK && magic == EXPECTED_MAGIC);
    if (magic_pass) {
        ESP_LOGI(TAG, "P4_QSPI_MAGIC result=PASS value=0x%08" PRIX32 " actual_freq=%" PRIu32,
                 magic, qspi_get_actual_freq_hz());
        log_hex_bytes("P4_QSPI_MAGIC bytes=", s_rx_buf, 4);
    } else if (err == ESP_OK) {
        ESP_LOGE(TAG, "P4_QSPI_MAGIC result=FAIL value=0x%08" PRIX32 " expect=0x%08" PRIX32
                 " actual_freq=%" PRIu32, magic, EXPECTED_MAGIC, qspi_get_actual_freq_hz());
    } else {
        ESP_LOGE(TAG, "P4_QSPI_MAGIC result=ERR err=%s actual_freq=%" PRIu32,
                 esp_err_to_name(err), qspi_get_actual_freq_hz());
    }
    overall_pass = magic_pass;

    bool wdt_pass = relax_task_wdt_for_bench();
    overall_pass = overall_pass && wdt_pass;
    if (P4_QSPI_RUN_BULK_THROUGHPUT_ONLY) {
        any_test_enabled = true;
        ESP_LOGI(TAG, "P4_QSPI_BULK_ONLY starting 80 MHz bulk throughput rerun");
        bool bulk_pass = run_write_throughput_80m(&write80);
        overall_pass = overall_pass && bulk_pass;
        ESP_LOGI(TAG,
                 "P4_QSPI_BULK_ONLY summary pass=%u req_freq=%" PRIu32 " actual_freq=%" PRIu32
                 " iterations=%" PRIu32 " write_err=%" PRIu32,
                 bulk_pass ? 1u : 0u,
                 QSPI_EIGHTY_CLOCK_HZ, qspi_get_actual_freq_hz(),
                 write80.iterations, write80.write_errors);
        bool health_pass = read_and_log_transport_health("P4_QSPI_BULK_ONLY_HEALTH", QSPI_FUNCTIONAL_CLOCK_HZ);
        overall_pass = overall_pass && health_pass;
    }
    if (P4_QSPI_RUN_BASIC_PROOF) {
        any_test_enabled = true;
        bool pass = run_proof_ladder(QSPI_FUNCTIONAL_CLOCK_HZ);
        overall_pass = overall_pass && pass;
    }
    if (P4_QSPI_RUN_INDEXED2_PROOF) {
        any_test_enabled = true;
        ESP_LOGI(TAG, "P4_QSPI_INDEXED2 starting authorized 2bpp indexed proof");
        bool pass = run_indexed2_proof();
        overall_pass = overall_pass && pass;
    }
    if (P4_QSPI_RUN_SHORT_SMOKE) {
        any_test_enabled = true;
        bool pass = run_short_transport_smoke();
        overall_pass = overall_pass && pass;
    }
    if (P4_QSPI_RUN_DEFINITIVE_CAMPAIGN) {
        any_test_enabled = true;
        bool pass = run_definitive_campaign();
        overall_pass = overall_pass && pass;
    }
    if (!any_test_enabled) {
        ESP_LOGE(TAG, "P4_QSPI_APP no tests enabled; define at least one P4_QSPI_RUN_* flag");
        overall_pass = false;
    }

    ESP_LOGI(TAG, "P4_QSPI_APP done pass=%u magic_pass=%u tests_enabled=%u",
             overall_pass ? 1u : 0u, magic_pass ? 1u : 0u, any_test_enabled ? 1u : 0u);

    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
```

## File: hw/spinal/spinalhdlvdp/VdpTop.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib.BufferCC

case class VdpTop(sdramCd: ClockDomain = null, enableL1Fetch: Boolean = true, withExtraRasterTriggers: Boolean = false, enableL2L3: Boolean = false,
                  scaleCtrlInit:   Int = 0,
                  logicWidthInit:  Int = 640,
                  logicHeightInit: Int = 480,
                  borderCtrlInit:  Int = 0,
                  // Shared bitmap write-pipeline alignment. writeAddr = hCounter - delay
                  // shifts the RGB565 directcolor image LEFT by `delay` columns: delay=0 lands
                  // the image at dh=0 (aligned) byte-exact; delay>0 shifts it left (dh=-delay).
                  // (HAM6 shelved 2026-07-20 #14224; this knob now serves the directcolor path.)
                  bitmapWritePipelineDelay: Int = 0) extends Component {
  // BronzeGate #9366 Path A: PlanarLineFetch's row-fetch FSM is migrated
  // into the SDRAM clock domain. When `sdramCd` is null (sim-default),
  // use the current pixel ClockDomain so single-clock sims keep working;
  // top-level integrations (TopTang20kHdmi, Hdmi720pMode0ProofTop) pass
  // the real `sdramClockDomain` so the FSM runs natively on the SDRAM
  // side.
  private val effectiveSdramCd: ClockDomain =
    if (sdramCd != null) sdramCd else ClockDomain.current
  val io = new Bundle {
    val hsync   = out Bool()
    val vsync   = out Bool()
    val de      = out Bool()
    val red     = out Bits(8 bits)
    val green   = out Bits(8 bits)
    val blue    = out Bits(8 bits)
    val x       = out UInt(10 bits)
    val y       = out UInt(10 bits)
    val layer0ScrollX = in UInt(10 bits)
    val layer0ScrollY = in UInt(10 bits)
    val layer1ScrollX = in UInt(10 bits)
    val layer1ScrollY = in UInt(10 bits)
    // Task 48 — Four-Layer Compositor Expansion: L2/L3 are simple
    // BasicPatternSource layers with global-only scroll (no per-column
    // scroll tables, no LinestateStore widening for L2/L3).
    val layer2ScrollX = in UInt(10 bits)
    val layer2ScrollY = in UInt(10 bits)
    val layer3ScrollX = in UInt(10 bits)
    val layer3ScrollY = in UInt(10 bits)
    // R2 sprite descriptors. Four descriptors total; SpriteEvaluator selects up
    // to two visible per line via priority-on-index. `patternIdx` picks pattern
    // 0 (sprite0Pattern) or 1 (sprite1Pattern).
    val sprite0X = in UInt(10 bits)
    val sprite0Y = in UInt(10 bits)
    val sprite0Enabled = in Bool()
    val sprite0PatternIdx = in UInt(1 bit)
    val sprite1X = in UInt(10 bits)
    val sprite1Y = in UInt(10 bits)
    val sprite1Enabled = in Bool()
    val sprite1PatternIdx = in UInt(1 bit)
    val sprite2X = in UInt(10 bits)
    val sprite2Y = in UInt(10 bits)
    val sprite2Enabled = in Bool()
    val sprite2PatternIdx = in UInt(1 bit)
    val sprite3X = in UInt(10 bits)
    val sprite3Y = in UInt(10 bits)
    val sprite3Enabled = in Bool()
    val sprite3PatternIdx = in UInt(1 bit)

    // R2 diagnostic: sprite-per-line overflow flag (sticky within line).
    val spriteOverflow = out Bool()
    // VDP-SOFT-RESET-135: live SOFT_RESET_BUSY status for the i80 0x0310
    // readback. High from the cycle a soft reset is accepted until the
    // bounded reset sequence completes (host polls this; i80 register reads
    // are otherwise last-write loopback). See the soft-reset controller below.
    val softResetBusy = out Bool()
    // VDP-SOFT-RESET-135 #3: SDRAM zero-fill stage handshake to TopTang. After
    // the on-chip Mem clear sweep, the controller raises `sdramFillStart` (level)
    // and holds busy until TopTang's sdram-domain fill FSM returns
    // `sdramFillDone` (both crossed by BufferCC in TopTang). On single-clock
    // sims with no fill engine, tie sdramFillDone high so the stage passes through.
    val sdramFillStart = out Bool()
    val sdramFillDone  = in  Bool() default True
    // R5: unified register-write bus. Replaces the raw lsWrite* ports.
    //   0x0000-0x01DF  linestate prepare (addr low 9 bits = line; data low 12 bits = {l0en, l1en, l0scrollX[9:0]})
    //   0x0300         LAYER_ENABLE (data[0]=L0, data[1]=L1, data[2]=sprite) — global override
    //   0x0400-0x05FF  copper program RAM (host uploads program here)
    //   (other ranges reserved for stages 5+)
    // Task 32b: unified register bus — replaces the prior ad-hoc
    // regWriteAddr/Data/Enable inputs with the Mode0RegBus bundle.
    val regBus = in (Mode0RegBus())

    // R4.1b stage 3 / R4.1d Checkpoint A: tile decode mode select out to the
    // SDRAM fetch engine. 2-bit field encoding:
    //   0x00 = packed 4bpp (R4 baseline)
    //   0x01 = NES-style 2bpp planar (R4.1b)
    //   0x02 = Amiga-style shuffled/bitplane (R4.1d)
    //   0x03 = reserved
    // The latched register is inside VdpTop and safe-boundary-committed to
    // hCounter===0. TopTang routes this to SdramTileAttributeFetch.tileDecodeMode.
    val layer0TileDecodeMode = out Bits(2 bits)

    // R4.1c: attribute-pack mode select (VDP_ATTR_MODE @ 0x0312).
    //   bit 0: 0 = linear 1:1 (R4), 1 = NES-style 2×2 packing
    // Safe-boundary-committed to hCounter===0. Routed to
    // SdramTileAttributeFetch.attributeMode.
    val layer0AttributeMode  = out Bits(1 bits)

    // Task 15 Layer-0 SDRAM source interface.
    //   - layer0UseSdram routes the external SDRAM-backed pixel into L0
    //     instead of the on-chip BasicPatternSource (for the switchable
    //     comparison path).
    //   - layer0SdramPixel comes from SdramTileFetch.io.pixelIndex.
    //   - layer0Fetch* are outputs that drive the external fetch engine. The
    //     raster owner decides the scroll/line/pixelAddr so the fetch contract
    //     stays at the VdpTop boundary.
    val layer0UseSdram        = in Bool()
    // R4: widened SDRAM-backed L0 interface.
    //   - pixel index widens from 3bpp (Task-15) to 4bpp
    //   - paletteBank[3] picks one of 8 palette banks (drives top bits of
    //     palette address)
    //   - priority=1 means this L0 pixel wins over L1 (priority-aware composite)
    val layer0SdramPixel      = in Bits(4 bits)
    val layer0SdramBank       = in UInt(3 bits)
    val layer0SdramPriority   = in Bool()
    // Test-pattern override for hardware validation (bypasses both SDRAM and
    // on-chip BasicPatternSource so standard validation patterns are always
    // available regardless of fetch-engine state).
    val layer0TestPatternSelect = in UInt(3 bits)
    val layer0TestPatternEnable = in Bool()

    // Task 56 Checkpoint A — L1 SDRAM source interface (mirrors L0).
    //
    // Coding authorized per CyanPeak audit PASS #9683 on artifact #9678.
    // CyanPeak's correction: L1 fetch engine uses sdramArbiter clientId=3
    // (clientId=1 is occupied by Task 44b bitmapRowFetch).
    //
    // For Checkpoint A only the *plumbing* lands: VdpTop accepts the
    // L1 SDRAM inputs and muxes them into the compositor in place of
    // (or alongside) the existing on-chip BasicPatternSource L1 path.
    // Top-level ties these inputs to default-off until Checkpoint B
    // instantiates the second SdramTileAttributeFetch engine.
    val layer1UseSdram          = in Bool()
    val layer1SdramPixel        = in Bits(4 bits)
    val layer1SdramBank         = in UInt(3 bits)
    val layer1SdramPriority     = in Bool()
    // R4: scheduler outputs exposed so the top-level can wire them into the
    // new SdramTileAttributeFetch engine (which accepts grant / slotValid /
    // preAnnounce instead of the legacy level-based fetchStart).
    val layer0FetchStart      = out Bool()
    val layer0FetchGrant      = out Bool()
    val layer0FetchSlotValid  = out Bool()
    val layer0FetchPreAnnounce = out Bool()
    // Task 30: scheduler grantClientId exposed so the top-level SDRAM
    // arbiter can mux between fetch clients.
    val layer0FetchGrantClientId = out UInt(2 bits)
    val layer0FetchLine       = out UInt(10 bits)
    val layer0FetchScrollX    = out UInt(10 bits)
    val layer0FetchScrollY    = out UInt(10 bits)
    val layer0FetchPixelAddr  = out UInt(10 bits)

    // Task 56 Checkpoint B (#9678 / #9693): L1 fetch scheduler outputs.
    // Mirror the L0 surface so a second SdramTileAttributeFetch engine
    // (clientId=3) can consume scheduler grants from slots 3/4. Driven
    // off scheduler.io.grant gated on hCounter==hTotal-1 for the grant
    // edge, and the layer1 scroll latches mirror the L0 earlyLatchStrobe
    // pattern with `layer1Scroll*` substituted for `layer0Scroll*`.
    // `layer1FetchEnable` follows `layer1UseSdram` (gates scheduler
    // slots 3/4 and ANDed with the grant pulse so the FSM never starts
    // when the engine is inactive).
    val layer1FetchGrant        = out Bool()
    val layer1FetchSlotValid    = out Bool()
    val layer1FetchPreAnnounce  = out Bool()
    val layer1FetchGrantClientId = out UInt(2 bits)
    val layer1FetchLine         = out UInt(10 bits)
    val layer1FetchScrollX      = out UInt(10 bits)
    val layer1FetchScrollY      = out UInt(10 bits)
    val layer1FetchPixelAddr    = out UInt(10 bits)

    // Task 44b — bitmap SDRAM-fetch coupling. When `bitmapEnable=1`,
    // BitmapFetch's `bitmapByte` / `attrByte` inputs are sourced from
    // these incoming ports instead of the Task 44 CP-B deterministic
    // test generator. The top-level wires these to a `BitmapRowFetch`
    // instance whose SDRAM bus runs through arbiter client 1.
    val bitmapSdramCol        = out UInt(10 bits)
    val bitmapSdramFetchLine  = out UInt(10 bits)
    val bitmapSdramFetchGrant = out Bool()
    val bitmapSdramByte       = in  Bits(8 bits)
    val bitmapSdramAttrByte   = in  Bits(8 bits)
    val bitmapModeActive      = out Bool()   // Task 44b: BITMAP_CTRL[0]
    val bitmapDirectColor     = out Bool()   // CP-1c: BITMAP_CTRL enable & bpp=0b10 (RGB565)
    // BITMAP-PLUMB-129 (#12169/#12205): host-programmable bitmap/attr fetch
    // geometry. Decoded from 0x0351..0x0357 with the standard safe-boundary
    // shadow/pend/commit pattern below; the top level routes these into
    // BitmapRowFetch (replacing its formerly hardcoded 0x3000/0x4000/512/240).
    val bitmapBase            = out UInt(23 bits)  // 0x0351 LO + 0x0352 HI
    val attrBase              = out UInt(23 bits)  // 0x0353 LO + 0x0354 HI
    val bitmapStride          = out UInt(16 bits)  // 0x0355 (direct-color bytes/row)
    val attrStride            = out UInt(16 bits)  // 0x0356 (direct-color bytes/row)
    val bitmapHeight          = out UInt(10 bits)  // 0x0357 (source rows)

    // R1 Raster Trigger Unit control/status. Stable naming so a later Mode0
    // register bus can adopt these without behavior change.
    val rasterTriggerLine      = in UInt(10 bits)
    val rasterTriggerPixel     = in UInt(10 bits)
    val rasterTriggerPxEnable  = in Bool()
    val rasterTriggerEnable    = in Bool()
    val rasterTriggerClear     = in Bool()
    val rasterTriggerPulse     = out Bool()
    val rasterTriggerPending   = out Bool()

    // Task 35 — Host-facing status surface.
    // External event inputs (pixel clock domain, 1-cycle pulses or level):
    val statusEvQspiReady  = in Bool()  // pulses on QSPI cmd_valid
    val statusEvQspiError  = in Bool()  // level-high when QspiDecoder.last_error != 0
    // Sticky register output for QSPI READ_STATUS sel=5 readback:
    val statusSticky       = out Bits(16 bits)
    // Host-visible IRQ line — asserted while any enabled sticky bit is set:
    val irq                = out Bool()
    // Task 54 — sprite-sprite collision per-descriptor mask, addr 0x0322.
    // Width deliberately held at 8 bits per BronzeGate #10363 even though
    // descCount is now 32: each bit set indicates the corresponding
    // descriptor participated in at least one sprite-sprite overlap since
    // the last write-1-to-clear. With descCount=32 the hit-descriptor
    // index is truncated to 3 bits, so descriptors 8/16/24 alias onto
    // bit 0, 9/17/25 onto bit 1, etc. Widening to 32 bits is parked
    // until a concrete product need for per-descriptor collision
    // resolution above descriptor 7 is shown (#10363).
    val spriteCollMask     = out Bits(8 bits)

    // MODE_SELECT live-mode field (4-bit) — exported for host READ_STATUS
    // LIVE_MODE observability. 0x0 = native Mode0; non-zero values are
    // reserved for runtime adapter selection driven by libvdp.
    val modeSelect         = out UInt(4 bits)

    // I80-FRAME-ATOMIC-SWAP-145: host-readable swap-ctrl status for 0x035C
    // readback. b0 = swapRequest (armed, self-clears at the vblank commit),
    // b1 = swapCommitted (sticky until host W1C). Wired into the i80 read mux
    // in TopTang20kHdmi so firmware can poll real commit completion instead of
    // a fixed open-loop delay.
    val swapStatus         = out Bits(16 bits)

    // Task 3 — Planar Fetch Hardening: SDRAM master interface for
    // PlanarLineFetch. The instance lives inside VdpTop; its SDRAM
    // master ports route up to TopTang20kHdmi for arbitration as
    // sdramArbiter client 2 alongside tile fetch (client 0) and
    // bitmap row fetch (client 1).
    val planarSdramRd        = out Bool()
    val planarSdramAddr      = out UInt(23 bits)
    val planarSdramBusy      = in  Bool()
    val planarSdramDataReady = in  Bool()
    val planarSdramDout32    = in  Bits(32 bits)
    // VDP-SOFT-RESET-135 #3 part 2c: expose planar plane bases + active gate so
    // TopTang's zero-fill can clear the occupied planar regions. Each plane's
    // SDRAM footprint is PLANE_PIXELS/8 = 40 bytes (BitplaneRowFetch reads
    // planeBase + readIdx*4, readsPerPlane = planePixels/32; no line offset).
    val planeBaseAddr        = out Vec(UInt(23 bits), 5)   // = PLANE_COUNT
    val planarFillActive     = out Bool()
  }

  // 640x480@60 timing uses a 25.2 MHz pixel clock.
  // The Tang20K wrapper supplies that from a 27 MHz input and a PLL/CLKDIV chain.
  val hActive = 640
  val hFront = 16
  val hSync = 96
  val hBack = 48
  val hTotal = hActive + hFront + hSync + hBack

  val vActive = 480
  val vFront = 10
  val vSync = 2
  val vBack = 33
  val vTotal = vActive + vFront + vSync + vBack

  val hCounter = (Reg(UInt(log2Up(hTotal) bits)) init 0).simPublic()   // simPublic: in-phase display counter for sims (SIM-TEST-DEBT-138)
  val vCounter = (Reg(UInt(log2Up(vTotal) bits)) init 0).simPublic()   // simPublic: vblank detection for the atomic-swap sim (I80-FRAME-ATOMIC-SWAP-145)

  // Raster counters walk the full timing envelope, not just the visible area.
  when(hCounter === hTotal - 1) {
    hCounter := 0
    when(vCounter === vTotal - 1) {
      vCounter := 0
    } otherwise {
      vCounter := vCounter + 1
    }
  } otherwise {
    hCounter := hCounter + 1
  }

  val activeVideo = hCounter < hActive && vCounter < vActive
  val hSyncStart = hActive + hFront
  val hSyncEnd = hActive + hFront + hSync
  val vSyncStart = vActive + vFront
  val vSyncEnd = vActive + vFront + vSync

  // Deterministic startup: output black until first vblank primes the buffer.
  val primed = Reg(Bool()) init False
  when(hCounter === hTotal - 1 && vCounter === vTotal - 1) {
    primed := True
  }

  // Fill line: during visible line N, fill the buffer with line N+1.
  // During vblank or the last visible line, fill with line 0 to prime next frame.
  val fillLine = UInt(10 bits)
  when(vCounter < vActive - 1) {
    fillLine := (vCounter + 1).resize(10)
  } otherwise {
    fillLine := U(0, 10 bits)
  }

  // Linestate: double-buffered per-scanline control store.
  // Prepare side is writable; commit side is read by render pipeline.
  // Commit at line boundary: at the start of each line, the prepare entry for
  // the current fillLine is copied to the commit side.
  val linestate = LinestateStore(lineCount = vActive)

  // VDP-SOFT-RESET-135: soft-reset controller state. Declared here (before the
  // linestate/scroll/palette/pattern write ports that the clear-sweep muxes
  // reference) — the request-latch decode + FSM logic follow further below.
  // #2a/#2b: on-chip memory clear sweep over host-writable Mems. Single shared
  // address counter; each Mem's EXISTING single write port is MUXED to the
  // sweep when active (NOT a second write port — that broke Gowin BSRAM
  // inference before). Sized to the largest swept Mem (sprite pattern RAM =
  // 16384 entries = 14b). affineTexture excluded (no write port; immutable POR).
  val softResetRequest  = Reg(Bool()) init False
  val softResetBusy     = Reg(Bool()) init False
  val softResetMemClear = Reg(Bool()) init False
  val softResetMemAddr  = Reg(UInt(14 bits)) init 0
  // #3: SDRAM zero-fill stage — high after the on-chip Mem clear, while the
  // controller waits for TopTang's sdram-domain fill FSM (sdramFillDone).
  val softResetFillStage = Reg(Bool()) init False
  // #4: core register reset stage — high while config registers are forced to
  // `init` (Option B surgical reset; the reset block keys off this). LIVE reg
  // (not itself reset) so it survives the reset it drives.
  val softResetCoreActive = Reg(Bool()) init False
  // #2b: linestate clears BOTH prepare+commit in one pass — a same-cycle
  // prepare-write + commit at the same address hits the BH-6 collision path,
  // which writes the (zero) writeData into commit. Active for addr < lineCount.
  val lsSweepWr = softResetMemClear && (softResetMemAddr < U(vActive, 14 bits))

  linestate.io.readAddr := fillLine.resized
  linestate.io.commitLine   := Mux(lsSweepWr, softResetMemAddr.resize(log2Up(vActive)), fillLine.resized)
  linestate.io.commitStrobe := Mux(lsSweepWr, True, hCounter === hTotal - 1)
  // Prepare-side write interface exposed for simulation testing.
  // R5 Copper coprocessor, fed by the regWrite bus for program uploads and by
  // `copperCtrlReg(0)` (VDP_CTRL @ 0x0310) for run control — R5.3 unifies the
  // previously-standalone `io.copperEnable` port with the register bus.
  val copperCtrlReg     = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPend    = Reg(Bits(1 bits)) init B(0, 1 bits)
  val copperCtrlPendHit = Reg(Bool()) init False
  // R5.4: Copper double-buffered live-update. Host writes VDP_CTRL bit[1]=1
  // to request an atomic bank swap; HW commits the swap at vSyncStart && hCounter==0
  // (frame-atomic, matches MODE_SELECT cadence) and auto-clears the pending bit.
  // Requests while copper is disabled are dropped (a swap can only happen while
  // copper is running). Disable also clears any in-flight pending request.
  val copperSwapPending = Reg(Bool()) init False
  val copper = Copper()
  copper.io.hCounter := hCounter.resize(10)
  copper.io.vCounter := vCounter.resize(10)
  copper.io.enabled  := copperCtrlReg(0)
  val copperSwapNowPulse = copperSwapPending && copperCtrlReg(0) &&
    (vCounter === U(vSyncStart, log2Up(vTotal) bits)) &&
    (hCounter === U(0, log2Up(hTotal) bits))
  copper.io.bankSwapNow := copperSwapNowPulse
  // BH-2: feed Copper's SKIP comparator from the legacy TR0 raster
  // trigger config so SKIP shares the same (line, pixel) targets the
  // IRQ subsystem already exposes. Wired below the rasterTrigger
  // declaration; TR0 inputs are the top-level rasterTrigger* IO.
  copper.io.triggerLine0  := io.rasterTriggerLine
  copper.io.triggerPixel0 := io.rasterTriggerPixel
  val copperProgRangeHit = io.regBus.enable &&
    (io.regBus.addr >= U(0x0400, 15 bits)) &&
    (io.regBus.addr <  U(0x0600, 15 bits))
  copper.io.progAddr := io.regBus.addr(8 downto 0)
  copper.io.progData := io.regBus.data
  copper.io.progWr   := copperProgRangeHit
  // VDP-SOFT-RESET-135 #2c: drive the copper clear sweep from the shared counter.
  copper.io.softClear     := softResetMemClear
  copper.io.softClearAddr := softResetMemAddr

  // Task 33 — HDMA host-control sub-block @ 0x0380..0x03C9.
  // Decoded from the EFFECTIVE merged bus (effAddr/effWrite) so configuration
  // writes originating from the copper script also reach the HDMA engine —
  // not just host (QSPI/bootstrap) writes on io.regBus.
  // (effAddr/effWrite are defined further below; SpinalHDL resolves via
  //  concurrent-assignment, so the forward reference is fine.)

  // R5.2 (#7082 target 100%): copper writes now flow through a small drain
  // FIFO and are released only on the safe boundary (`hCounter === 0`).
  // Previously the combinational merge let copper regWrite pulses reach the
  // RegisterMap mid-line, producing the ~6 residual scroll skips and
  // red-flash artifacts the R5.1 partial fix couldn't fully eliminate.
  // Task 33: depth widened from 4 → 32 so a copper bootstrap script can fire
  // a burst of writes (e.g. HDMA config is 11 back-to-back writes) without
  // FIFO-full drops. Drain is still 1/line at hCounter===0.
  // Task 50 v3.2: depth widened 32 -> 64 to hold the 54-write per-frame burst
  // for the ZX Spectrum scene (palette load + border/bitmap control).
  val copperFifo = spinal.lib.StreamFifo(dataType = Bits(31 bits), depth = 64)
  copperFifo.io.push.valid   := copper.io.regWr
  copperFifo.io.push.payload := (copper.io.regAddr.asBits ## copper.io.regData).asBits.resize(31)
  val extHit     = io.regBus.enable
  val safeNow    = hCounter === U(0, log2Up(hTotal) bits)
  val copperDrain = safeNow && !extHit
  copperFifo.io.pop.ready := copperDrain
  val copperPopped = copperFifo.io.pop.fire

  // Task 47 — DMA-style block transfer primitive. Merges into effWrite with
  // lower priority than ext/copper; when a higher-priority master is
  // driving effWrite, the DMA pauses and resumes on the next free cycle.
  // Task 49 — Blitter engine added at the *lowest* priority (below DMA).
  // Fixed co-arbitration: dmaWr > blitWr (preserves Task 47 latency). Both
  // engines hold their counters when blocked.
  val dmaEngine     = DmaEngine()
  val blitterEngine = BlitterEngine()
  val dmaWr  = dmaEngine.io.dmaWr
  val blitWr = blitterEngine.io.blitWr
  val effWrite = (extHit || copperPopped || dmaWr || blitWr).simPublic()
  val effAddr  = Mux(extHit,      io.regBus.addr,
                 Mux(copperPopped, copperFifo.io.pop.payload(30 downto 16).asUInt,
                 Mux(dmaWr,        dmaEngine.io.dmaAddr,
                                   blitterEngine.io.blitAddr))).simPublic()
  val effData  = Mux(extHit,      io.regBus.data,
                 Mux(copperPopped, copperFifo.io.pop.payload(15 downto 0),
                 Mux(dmaWr,        dmaEngine.io.dmaData,
                                   blitterEngine.io.blitData))).simPublic()

  // DMA bus-write decode — only control registers (0x0B00..0x0B03) and the
  // staging buffer (0x0B10..0x0B4F) are consumed by DmaEngine. Writes from
  // ext/copper in this range program the DMA; writes from DMA itself always
  // target other ranges, so no self-recursion.
  val dmaRangeHit = (effAddr >= U(0x0B00, 15 bits)) && (effAddr < U(0x0B50, 15 bits))
  dmaEngine.io.busAddr := effAddr
  dmaEngine.io.busData := effData
  dmaEngine.io.busWr   := effWrite && dmaRangeHit
  // VDP-SOFT-RESET-135 #2d: drive the DMA staging clear from the shared sweep.
  dmaEngine.io.softClear     := softResetMemClear
  dmaEngine.io.softClearAddr := softResetMemAddr
  dmaEngine.io.busBusy := extHit || copperPopped

  // Task 49 — Blitter bus-write decode. Control registers at 0x0C00..0x0C07
  // and the 512-word source RAM at 0x0C10..0x0D0F are consumed by the
  // BlitterEngine. The blitter itself writes only to its programmed
  // destination address (15-bit), so self-recursion is precluded as long
  // as the host does not program dst into the blitter's own range.
  val blitRangeHit = (effAddr >= U(0x0C00, 15 bits)) && (effAddr < U(0x0D10, 15 bits))
  blitterEngine.io.busAddr := effAddr
  blitterEngine.io.busData := effData
  blitterEngine.io.busWr   := effWrite && blitRangeHit
  blitterEngine.io.busBusy := extHit || copperPopped || dmaWr
  // VDP-SOFT-RESET-135 #2d: drive the blitter srcRam clear from the shared sweep.
  blitterEngine.io.softClear     := softResetMemClear
  blitterEngine.io.softClearAddr := softResetMemAddr

  // Task 33 HDMA control decode (see forward-declared comment above).
  val copperHdmaRangeHit = effWrite &&
    (effAddr >= U(0x0380, 15 bits)) &&
    (effAddr <  U(0x0400, 15 bits))
  copper.io.hdmaCtrlAddr := effAddr(6 downto 0)
  copper.io.hdmaData     := effData
  copper.io.hdmaWr       := copperHdmaRangeHit

  // R5 RegisterMap decode off the merged bus. Writes to the linestate range
  // take the low 9 bits of effAddr as line index and the low 12 bits of
  // effData as the packed record. LAYER_ENABLE latches at 0x0300.
  val lsRangeHit = effWrite && (effAddr < U(480, 15 bits))
  // VDP-SOFT-RESET-135 #2b: prepare-side write muxed between host and the
  // zero-sweep. writeAddr/writeData here pair with the commitLine/commitStrobe
  // override above so each swept line zeroes prepare AND commit (BH-6 collision).
  linestate.io.writeAddr   := Mux(lsSweepWr, softResetMemAddr.resize(log2Up(480)), effAddr(log2Up(480) - 1 downto 0))
  linestate.io.writeData   := Mux(lsSweepWr, B(0, 12 bits), effData(11 downto 0))
  linestate.io.writeEnable := Mux(lsSweepWr, True, lsRangeHit)

  // R5.1 stutter fix (#7080): latch pending LAYER_ENABLE write into a shadow
  // register and apply it to `layerEnableReg` only at `hCounter === 0`.
  // Without this gate, the copper's combinational write arrives mid-line,
  // shifts the compositor's effective enable mask mid-scanline, and shows
  // up as 1-frame scroll skips + wrong-bank pixel flashes on hardware.
  // 5-bit layout: {L3[4], L2[3], sprite[2], L1[1], L0[0]}.
  // Reset default = all-off (lane #10567 agnosticism). The host owns layer
  // activation via libvdp.
  val layerEnableReg    = (Reg(Bits(5 bits)) init B"00000").simPublic()
  val layerEnablePend   = Reg(Bits(5 bits)) init B"00000"
  val layerEnablePendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0300, 15 bits)) {
    layerEnablePend    := effData(4 downto 0)
    layerEnablePendHit := True
  }
  // Register-programmability #3/#4 (TopazCliff #12578/#12649). Direct config regs
  // (host sets at setup, not mid-frame); reset to init by the #4 soft-reset block.
  // #3: per-layer transparency key — the palette index treated as transparent for
  // each layer (replaces the hardcoded index-0). Default 0 ⇒ bit-identical.
  val l0TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l1TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l2TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  val l3TransKeyReg = (Reg(Bits(4 bits)) init 0).simPublic()
  when(effWrite && effAddr === U(0x0314, 15 bits)) { l0TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0315, 15 bits)) { l1TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0316, 15 bits)) { l2TransKeyReg := effData(3 downto 0) }
  when(effWrite && effAddr === U(0x0317, 15 bits)) { l3TransKeyReg := effData(3 downto 0) }
  // #4: planar clip width — replaces the fixed PLANE_PIXELS clip. Default 320 ⇒
  // bit-identical; values >320 wrap (planar source native width is 320).
  val planarWidthReg = (Reg(UInt(10 bits)) init 320).simPublic()
  when(effWrite && effAddr === U(0x0D4B, 15 bits)) { planarWidthReg := effData(9 downto 0).asUInt }
  // R4.1b stage 3 / R4.1d Checkpoint A: VDP_TILE_MODE @ 0x0311 follows the
  // same safe-boundary pattern as layerEnable — pending shadow + commit at
  // hCounter===0. Widened from 1→2 bits to encode shuffled mode (0x02)
  // alongside packed (0x00) and planar (0x01). See layer0TileDecodeMode.
  val tileDecodeModeReg     = Reg(Bits(2 bits)) init B(0, 2 bits)
  val tileDecodeModePend    = Reg(Bits(2 bits)) init B(0, 2 bits)
  val tileDecodeModePendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0311, 15 bits)) {
    tileDecodeModePend    := effData(1 downto 0)
    tileDecodeModePendHit := True
  }
  // R4.1c: VDP_ATTR_MODE @ 0x0312, same safe-boundary pattern.
  val attributeModeReg      = Reg(Bits(1 bits)) init B(0, 1 bits)
  val attributeModePend     = Reg(Bits(1 bits)) init B(0, 1 bits)
  val attributeModePendHit  = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0312, 15 bits)) {
    attributeModePend    := effData(0 downto 0)
    attributeModePendHit := True
  }
  // BACKDROP_INDEX @ 0x0348 — host-writable 7-bit absolute palette index used
  // by the compositor `.otherwise` fallthrough as the displayed pixel when no
  // layer is opaque. Decouples the backdrop color from layer0Bank (which is
  // SDRAM-sourced and non-deterministic across reboots). POR=0 → palette[0].
  // Standard safe-boundary shadow+commit pattern.
  val backdropIndexReg     = (Reg(UInt(7 bits)) init U(0, 7 bits)).simPublic()
  val backdropIndexPend    = Reg(UInt(7 bits)) init U(0, 7 bits)
  val backdropIndexPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0348, 15 bits)) {
    backdropIndexPend    := effData(6 downto 0).asUInt
    backdropIndexPendHit := True
  }

  // PixelRepeatScaler register block (lane #10590 Path B).
  //   0x0349 SCALE_CTRL    : [2:0]=scaleX (0/1 = 1x, 2..6 = 2x..6x; ≥7 clamps)
  //                          [6:4]=scaleY (same encoding)
  //                          [7]  = autoCenter
  //   0x034A LOGIC_WIDTH   : 11-bit logical canvas width  (1..640)
  //   0x034B LOGIC_HEIGHT  : 11-bit logical canvas height (1..480)
  // Hardware silently clamps scale*logic to active dimensions (CyanPeak #10596).
  // Safe-boundary commit at hCounter===0 like the rest of the register file.
  val scaleCtrlReg     = (Reg(Bits(8 bits)) init B(scaleCtrlInit, 8 bits)).simPublic()
  val scaleCtrlPend    = Reg(Bits(8 bits)) init B(0, 8 bits)
  val scaleCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0349, 15 bits)) {
    scaleCtrlPend    := effData(7 downto 0)
    scaleCtrlPendHit := True
  }
  val logicWidthReg     = (Reg(UInt(11 bits)) init U(logicWidthInit, 11 bits)).simPublic()
  val logicWidthPend    = Reg(UInt(11 bits)) init U(640, 11 bits)
  val logicWidthPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034A, 15 bits)) {
    logicWidthPend    := effData(10 downto 0).asUInt
    logicWidthPendHit := True
  }
  val logicHeightReg     = (Reg(UInt(11 bits)) init U(logicHeightInit, 11 bits)).simPublic()
  val logicHeightPend    = Reg(UInt(11 bits)) init U(480, 11 bits)
  val logicHeightPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034B, 15 bits)) {
    logicHeightPend    := effData(10 downto 0).asUInt
    logicHeightPendHit := True
  }

  // Inner-border registers (0x034C..0x034F): border thickness in LOGICAL pixels.
  // Hardware auto-computes the physical BORDER_X0/Y0/X1/Y1 from these values
  // plus scale + logic dims, so the host need not do the math.
  //   0x034C INNER_BORDER_L  (10 bits)
  //   0x034D INNER_BORDER_R  (10 bits)
  //   0x034E INNER_BORDER_T  (10 bits)
  //   0x034F INNER_BORDER_B  (10 bits)
  //   0x0347 BORDER_CTRL     bit[1] = innerBorderEnable (in addition to bit[0]=enable)
  val innerBorderLReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderLPend    = Reg(UInt(10 bits)) init 0
  val innerBorderLPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034C, 15 bits)) {
    innerBorderLPend    := effData(9 downto 0).asUInt
    innerBorderLPendHit := True
  }
  val innerBorderRReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderRPend    = Reg(UInt(10 bits)) init 0
  val innerBorderRPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034D, 15 bits)) {
    innerBorderRPend    := effData(9 downto 0).asUInt
    innerBorderRPendHit := True
  }
  val innerBorderTReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderTPend    = Reg(UInt(10 bits)) init 0
  val innerBorderTPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034E, 15 bits)) {
    innerBorderTPend    := effData(9 downto 0).asUInt
    innerBorderTPendHit := True
  }
  val innerBorderBReg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val innerBorderBPend    = Reg(UInt(10 bits)) init 0
  val innerBorderBPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x034F, 15 bits)) {
    innerBorderBPend    := effData(9 downto 0).asUInt
    innerBorderBPendHit := True
  }

  // R5.3: VDP_CTRL @ 0x0310, safe-boundary shadow + commit for copper enable.
  // R5.4: bit[1] = COPPER_SWAP_REQUEST (latch-on-write). HW auto-clears at
  // commit. Last-write-wins precedence below: swap-commit and disable-clear
  // both override the host set, so a request that lands the same cycle as
  // disable or the commit pulse resolves cleanly.
  when(effWrite && effAddr === U(0x0310, 15 bits)) {
    copperCtrlPend    := effData(0 downto 0)
    copperCtrlPendHit := True
    when(effData(1)) { copperSwapPending := True }
    // VDP-SOFT-RESET-135: bit[2] = SOFT_RESET_REQUEST (latch-on-write, like the
    // copper-swap bit[1]). Honored by the soft-reset controller below.
    when(effData(2)) { softResetRequest := True }
  }
  // R5.4: auto-clear on commit, and clear if copper is disabled (pending
  // swap is dropped because requests are only honored while enabled).
  when(copperSwapNowPulse)   { copperSwapPending := False }
  when(!copperCtrlReg(0))    { copperSwapPending := False }

  // ===== VDP-SOFT-RESET-135: host-triggered soft-reset controller =====
  // Host writes VDP_CTRL @ 0x0310 bit[2]=1 to request a POR-equivalent soft
  // reset; HW runs a bounded, deadlock-free sequence and AUTO-CLEARS the
  // request + drops `softResetBusy` when complete. The host polls completion
  // by reading 0x0310 (i80 readback returns bit2=SOFT_RESET_BUSY; see TopTang).
  //
  // INCREMENTAL BUILD (lane VDP-SOFT-RESET-135): this increment (#1) wires the
  // request/busy/auto-clear handshake + the i80 status readback only. The
  // reset *actions* land in later increments, each slotting into the staged
  // sequence below WITHOUT changing this host-facing contract:
  //   [#2] on-chip MEM clear sweep (copper RAM, palette, sprite pattern/desc,
  //        linestate, scroll tables, affine texture) — zero per TopazCliff Q1.
  //   [#3] SDRAM zero-fill engine (TopTang arbiter client) — all of SDRAM (Q2).
  //   [#4] core register reset (ClockDomain soft-reset partition) — regs->init.
  // These controller regs live in the NORMAL clock domain (NOT the future
  // core-reset partition) so the controller survives the reset it drives and
  // can hold/clear the request + drive the busy status throughout.
  //
  // Sequence: stage 1 = on-chip Mem clear sweep (#2a-#2e, done); stage 2 = SDRAM
  // zero-fill via TopTang's fill FSM (#3); stage 3 = core register reset (#4,
  // pending). Busy is held across all stages; the request auto-clears at the end.
  // (softResetRequest/softResetBusy/softResetMemClear/softResetMemAddr/
  //  softResetFillStage declared above the 0x0310 decode.)
  // Sweep covers addr [0, 16383] (full 14-bit sprite-pattern depth). palette
  // (PaletteDepth=128) clears only while addr < PaletteDepth; pattern RAM clears
  // across the whole sweep. The per-Mem write muxes live at each Mem below.
  val softResetSweepLast = U((1 << 14) - 1, 14 bits)
  when(softResetRequest && !softResetBusy) {
    softResetBusy      := True           // accept the request; begin the sequence
    softResetMemClear  := True           // stage 1: on-chip memory clear sweep
    softResetMemAddr   := 0
    softResetFillStage := False
  }
  when(softResetBusy && softResetMemClear) {
    when(softResetMemAddr === softResetSweepLast) {
      softResetMemClear  := False
      // stage 2: SDRAM zero-fill. Raise the fill request and hold busy until
      // TopTang's sdram-domain fill FSM reports done (BufferCC-crossed). #4
      // (core register reset) will chain after the fill stage when it lands.
      softResetFillStage := True
    } otherwise {
      softResetMemAddr := softResetMemAddr + 1
    }
  }
  when(softResetBusy && softResetFillStage) {
    when(io.sdramFillDone) {             // SDRAM zero-fill complete ...
      softResetFillStage  := False
      softResetCoreActive := True        // ... enter stage 3: core register reset
    }
  }
  // Stage 3 (#4): hold the config registers at their `init` (the reset block
  // below keys off softResetCoreActive), then RELEASE synchronously at a clean
  // line boundary (hCounter==0) so the video datapath sees no glitched pulse
  // (CyanPeak #12589/#12609 safety rule). Config regs are stable at init through
  // the stage; releasing at hCounter==0 starts the next line cleanly.
  when(softResetBusy && softResetCoreActive) {
    when(hCounter === U(0, log2Up(hTotal) bits)) {
      softResetCoreActive := False
      softResetBusy       := False        // sequence complete: drop busy ...
      softResetRequest    := False        // ... and auto-clear the request bit
    }
  }
  // SDRAM-fill request to TopTang (level; CDC'd in TopTang to sdramClockDomain).
  io.sdramFillStart := softResetFillStage
  io.softResetBusy := softResetBusy

  // MODE_SELECT @ 0x0313: 16-bit register — [3:0] = MODE_SELECT,
  // [7:4] = reserved, [15:8] = MODE_FLAGS. Host/QSPI-write only.
  // Frame-atomic commit at V=0 (vsync start) — NOT the per-line hCounter===0
  // boundary used by other safe-boundary regs, since mode switch must not
  // produce split-frame artifacts.
  val modeSelectPend     = Reg(UInt(4 bits))  init U(0, 4 bits)
  val modeSelectFlagsPend = Reg(Bits(8 bits))  init B(0, 8 bits)
  val modeSelectPendHit  = Reg(Bool())        init False
  val modeSelectReg      = Reg(UInt(4 bits))  init U(0, 4 bits)
  val modeSelectFlagsReg  = Reg(Bits(8 bits))  init B(0, 8 bits)
  when(effWrite && effAddr === U(0x0313, 15 bits)) {
    modeSelectPend      := effData(3 downto 0).asUInt
    modeSelectFlagsPend := effData(15 downto 8)
    modeSelectPendHit   := True
  }
  io.modeSelect := modeSelectReg
  // R6 Task 20: Color Math + Window registers (0x0330..0x0334), same
  // safe-boundary shadow+commit pattern. Defaults are all-zero so the stage
  // is passthrough at power-on (no output regression).
  val winX0Reg     = Reg(UInt(10 bits)) init 0
  val winX0Pend    = Reg(UInt(10 bits)) init 0
  val winX0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0330, 15 bits)) {
    winX0Pend    := effData(9 downto 0).asUInt
    winX0PendHit := True
  }
  val winX1Reg     = Reg(UInt(10 bits)) init 0
  val winX1Pend    = Reg(UInt(10 bits)) init 0
  val winX1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0331, 15 bits)) {
    winX1Pend    := effData(9 downto 0).asUInt
    winX1PendHit := True
  }
  val winY0Reg     = Reg(UInt(10 bits)) init 0
  val winY0Pend    = Reg(UInt(10 bits)) init 0
  val winY0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0332, 15 bits)) {
    winY0Pend    := effData(9 downto 0).asUInt
    winY0PendHit := True
  }
  val winY1Reg     = Reg(UInt(10 bits)) init 0
  val winY1Pend    = Reg(UInt(10 bits)) init 0
  val winY1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0333, 15 bits)) {
    winY1Pend    := effData(9 downto 0).asUInt
    winY1PendHit := True
  }
  val colorMathReg     = Reg(Bits(16 bits)) init 0
  val colorMathPend    = Reg(Bits(16 bits)) init 0
  val colorMathPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0334, 15 bits)) {
    colorMathPend    := effData
    colorMathPendHit := True
  }
  // CW-5: Window 2 + combination logic registers.
  //   0x0335 win2X0 (inclusive)
  //   0x0336 win2X1 (exclusive)
  //   0x0337 win2Y0 (inclusive)
  //   0x0338 win2Y1 (exclusive)
  //   0x0339 win2Ctrl  bit[0] = invert2
  //   0x033A winCombMode bits[2:0]
  //                       000 = window1 only (legacy default)
  //                       001 = AND (e1 && e2)
  //                       010 = OR  (e1 || e2)
  //                       011 = XOR (e1 ^^ e2)
  //                       100 = INV_AND (!(e1 && e2))
  //                       101 = INV_OR  (!(e1 || e2))
  //                       11x = reserved (treated as window1 only)
  // All defaults are zero so existing scenes are bit-identical: with
  // win2 X/Y all zero and invert2=0 → effect2 = False; combMode=0 → use
  // effect1 unchanged.
  val win2X0Reg     = Reg(UInt(10 bits)) init 0
  val win2X0Pend    = Reg(UInt(10 bits)) init 0
  val win2X0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0335, 15 bits)) {
    win2X0Pend    := effData(9 downto 0).asUInt
    win2X0PendHit := True
  }
  val win2X1Reg     = Reg(UInt(10 bits)) init 0
  val win2X1Pend    = Reg(UInt(10 bits)) init 0
  val win2X1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0336, 15 bits)) {
    win2X1Pend    := effData(9 downto 0).asUInt
    win2X1PendHit := True
  }
  val win2Y0Reg     = Reg(UInt(10 bits)) init 0
  val win2Y0Pend    = Reg(UInt(10 bits)) init 0
  val win2Y0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0337, 15 bits)) {
    win2Y0Pend    := effData(9 downto 0).asUInt
    win2Y0PendHit := True
  }
  val win2Y1Reg     = Reg(UInt(10 bits)) init 0
  val win2Y1Pend    = Reg(UInt(10 bits)) init 0
  val win2Y1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0338, 15 bits)) {
    win2Y1Pend    := effData(9 downto 0).asUInt
    win2Y1PendHit := True
  }
  val win2CtrlReg     = Reg(Bits(16 bits)) init 0
  val win2CtrlPend    = Reg(Bits(16 bits)) init 0
  val win2CtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0339, 15 bits)) {
    win2CtrlPend    := effData
    win2CtrlPendHit := True
  }
  val winCombReg     = Reg(Bits(16 bits)) init 0
  val winCombPend    = Reg(Bits(16 bits)) init 0
  val winCombPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033A, 15 bits)) {
    winCombPend    := effData
    winCombPendHit := True
  }
  // CW-6: Per-layer window mask enable. When a layer's bit is set AND the
  // combined window effect is active for the current pixel, that layer's
  // contribution is masked at display time (forced to black). Bit layout
  // matches PixelMetadata.SourceXxx encoding so `layerMaskReg(source)`
  // selects the correct mask:
  //   bit[0] = mask SourceBG0
  //   bit[1] = mask SourceBG1
  //   bit[2] = mask SourceBG2
  //   bit[3] = mask SourceBG3
  //   bit[4] = mask SourceSprite
  //   bits[7:5] = reserved
  // Default 0 → no masking (legacy behavior).
  val layerMaskReg     = Reg(Bits(16 bits)) init 0
  val layerMaskPend    = Reg(Bits(16 bits)) init 0
  val layerMaskPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033B, 15 bits)) {
    layerMaskPend    := effData
    layerMaskPendHit := True
  }
  // Task 50 v3 — Visible-border-via-window registers.
  //
  // Defines a dedicated rectangular window at display coordinates. When
  // BORDER_CTRL[0] is set, pixels OUTSIDE the rectangle are replaced at
  // the final display stage with palette[BORDER_CTRL[12:8]]. The rectangle
  // is independent from the CW-5 WIN1/WIN2 windows so existing scenes using
  // those for ColorMath effects are unaffected. Defaults are all-zero so
  // v3-OFF scenes continue to render bit-identically.
  //
  //   0x033C BORDER_X0   (10 bits, inclusive)
  //   0x033D BORDER_X1   (10 bits, exclusive)
  //   0x033E BORDER_Y0   (10 bits, inclusive)
  //   0x033F BORDER_Y1   (10 bits, exclusive)
  //   0x0347 BORDER_CTRL bit[0]    = enable
  //                       bit[1]     = innerBorderEnable (auto-compute
  //                                    physical borders from INNER_BORDER_*)
  //                       bits[12:8] = palette index (0..31) for the
  //                                    border source pixel
  val borderX0Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderX0Pend    = Reg(UInt(10 bits)) init 0
  val borderX0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033C, 15 bits)) {
    borderX0Pend    := effData(9 downto 0).asUInt
    borderX0PendHit := True
  }
  val borderX1Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderX1Pend    = Reg(UInt(10 bits)) init 0
  val borderX1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033D, 15 bits)) {
    borderX1Pend    := effData(9 downto 0).asUInt
    borderX1PendHit := True
  }
  val borderY0Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderY0Pend    = Reg(UInt(10 bits)) init 0
  val borderY0PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033E, 15 bits)) {
    borderY0Pend    := effData(9 downto 0).asUInt
    borderY0PendHit := True
  }
  val borderY1Reg     = (Reg(UInt(10 bits)) init 0).simPublic()
  val borderY1Pend    = Reg(UInt(10 bits)) init 0
  val borderY1PendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x033F, 15 bits)) {
    borderY1Pend    := effData(9 downto 0).asUInt
    borderY1PendHit := True
  }
  val borderCtrlReg     = (Reg(Bits(16 bits)) init B(borderCtrlInit, 16 bits)).simPublic()
  val borderCtrlPend    = Reg(Bits(16 bits)) init 0
  val borderCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0347, 15 bits)) {
    borderCtrlPend    := effData
    borderCtrlPendHit := True
  }
  // Task 19 Checkpoint A: Affine Layer matrix + control registers.
  // Addresses 0x0340..0x0346, same safe-boundary shadow + commit pattern.
  //   0x0340 AFFINE_A    16b  signed 8.8 fixed point
  //   0x0341 AFFINE_B    16b  signed 8.8
  //   0x0342 AFFINE_C    16b  signed 8.8
  //   0x0343 AFFINE_D    16b  signed 8.8
  //   0x0344 AFFINE_X    16b  signed 10.6 translation
  //   0x0345 AFFINE_Y    16b  signed 10.6 translation
  //   0x0346 AFFINE_CTRL 16b  bit 0 = affineEnable, others reserved
  // Defaults are all-zero so AFFINE_CTRL[0]=0 at power-on — the L0 source mux
  // (landed in Checkpoint B) keeps the existing SDRAM/on-chip path unchanged.
  val affineAReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineAPend    = Reg(Bits(16 bits)) init 0
  val affineAPendHit = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0340, 15 bits)) {
    affineAPend    := effData
    affineAPendHit := True
  }
  val affineBReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineBPend    = Reg(Bits(16 bits)) init 0
  val affineBPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0341, 15 bits)) {
    affineBPend    := effData
    affineBPendHit := True
  }
  val affineCReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineCPend    = Reg(Bits(16 bits)) init 0
  val affineCPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0342, 15 bits)) {
    affineCPend    := effData
    affineCPendHit := True
  }
  val affineDReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineDPend    = Reg(Bits(16 bits)) init 0
  val affineDPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0343, 15 bits)) {
    affineDPend    := effData
    affineDPendHit := True
  }
  val affineXReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineXPend    = Reg(Bits(16 bits)) init 0
  val affineXPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0344, 15 bits)) {
    affineXPend    := effData
    affineXPendHit := True
  }
  val affineYReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineYPend    = Reg(Bits(16 bits)) init 0
  val affineYPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0345, 15 bits)) {
    affineYPend    := effData
    affineYPendHit := True
  }
  val affineCtrlReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val affineCtrlPend    = Reg(Bits(16 bits)) init 0
  val affineCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0346, 15 bits)) {
    affineCtrlPend    := effData
    affineCtrlPendHit := True
  }
  val affineEnable = affineCtrlReg(0)

  // Task 44 — raw bitmap + attribute fetch register block (0x0350..0x0356).
  //   0x0350 BITMAP_CTRL       bit[0] enable, bits[2:1] bpp, bits[6:3] cellWidth log2
  //   0x0351 BITMAP_BASE_LO    low 16 bits of bitmap SDRAM base
  //   0x0352 BITMAP_BASE_HI    high 7 bits of bitmap SDRAM base
  //   0x0353 ATTR_BASE_LO      low 16 bits of attribute SDRAM base
  //   0x0354 ATTR_BASE_HI      high 7 bits of attribute SDRAM base
  //   0x0355 BITMAP_STRIDE     bytes per bitmap row
  //   0x0356 ATTR_STRIDE       bytes per attribute row
  // All registers use the established safe-boundary {shadow, pend, commit
  // at hCounter===0} pattern. Defaults are zero → BITMAP_CTRL[0]=0 at
  // power-on, so the L0 source mux below keeps the existing tile path
  // (no regression for legacy scenarios).
  val bitmapCtrlReg     = (Reg(Bits(16 bits)) init 0).simPublic()
  val bitmapCtrlPend    = Reg(Bits(16 bits)) init 0
  val bitmapCtrlPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0350, 15 bits)) {
    bitmapCtrlPend    := effData
    bitmapCtrlPendHit := True
  }
  val bitmapEnable    = bitmapCtrlReg(0)
  val bitmapBpp       = bitmapCtrlReg(2 downto 1).asUInt

  // BITMAP-PLUMB-129 (#12169/#12205) — bitmap/attr fetch geometry registers.
  //   0x0351 BITMAP_BASE_LO   low 16 bits of bitmap SDRAM base
  //   0x0352 BITMAP_BASE_HI   high 7 bits  (base = HI##LO, 23-bit byte addr)
  //   0x0353 ATTR_BASE_LO     low 16 bits of attribute SDRAM base
  //   0x0354 ATTR_BASE_HI     high 7 bits
  //   0x0355 BITMAP_STRIDE    direct-color bytes per bitmap row
  //   0x0356 ATTR_STRIDE      direct-color bytes per attribute row
  //   0x0357 BITMAP_HEIGHT    source image height in rows (NEW)
  // Same safe-boundary {shadow, pend, commit at hCounter===0} pattern as
  // BITMAP_CTRL. Power-on defaults reproduce BitmapRowFetch's former hardcoded
  // constants (base 0x3000/0x4000, stride 512, height 240) so existing demos
  // do not regress.
  val bitmapBaseLoReg  = Reg(UInt(16 bits)) init 0x3000
  val bitmapBaseHiReg  = Reg(UInt(7 bits))  init 0
  val attrBaseLoReg    = Reg(UInt(16 bits)) init 0x4000
  val attrBaseHiReg    = Reg(UInt(7 bits))  init 0
  val bitmapStrideReg  = Reg(UInt(16 bits)) init 512
  val attrStrideReg    = Reg(UInt(16 bits)) init 512
  val bitmapHeightReg  = Reg(UInt(10 bits)) init 240
  val bitmapBaseLoPend = Reg(UInt(16 bits)) init 0x3000
  val bitmapBaseHiPend = Reg(UInt(7 bits))  init 0
  val attrBaseLoPend   = Reg(UInt(16 bits)) init 0x4000
  val attrBaseHiPend   = Reg(UInt(7 bits))  init 0
  val bitmapStridePend = Reg(UInt(16 bits)) init 512
  val attrStridePend   = Reg(UInt(16 bits)) init 512
  val bitmapHeightPend = Reg(UInt(10 bits)) init 240
  val bitmapBaseLoPendHit = Reg(Bool()) init False
  val bitmapBaseHiPendHit = Reg(Bool()) init False
  val attrBaseLoPendHit   = Reg(Bool()) init False
  val attrBaseHiPendHit   = Reg(Bool()) init False
  val bitmapStridePendHit = Reg(Bool()) init False
  val attrStridePendHit   = Reg(Bool()) init False
  val bitmapHeightPendHit = Reg(Bool()) init False
  when(effWrite && effAddr === U(0x0351, 15 bits)) { bitmapBaseLoPend := effData(15 downto 0).asUInt; bitmapBaseLoPendHit := True }
  when(effWrite && effAddr === U(0x0352, 15 bits)) { bitmapBaseHiPend := effData(6 downto 0).asUInt;  bitmapBaseHiPendHit := True }
  when(effWrite && effAddr === U(0x0353, 15 bits)) { attrBaseLoPend   := effData(15 downto 0).asUInt; attrBaseLoPendHit   := True }
  when(effWrite && effAddr === U(0x0354, 15 bits)) { attrBaseHiPend   := effData(6 downto 0).asUInt;  attrBaseHiPendHit   := True }
  when(effWrite && effAddr === U(0x0355, 15 bits)) { bitmapStridePend := effData(15 downto 0).asUInt; bitmapStridePendHit := True }
  when(effWrite && effAddr === U(0x0356, 15 bits)) { attrStridePend   := effData(15 downto 0).asUInt; attrStridePendHit   := True }
  when(effWrite && effAddr === U(0x0357, 15 bits)) { bitmapHeightPend := effData(9 downto 0).asUInt;  bitmapHeightPendHit := True }

  // I80-FRAME-ATOMIC-SWAP-145: dedicated double-buffer staging for the bitmap
  // and attribute base pointers. The host stages all four words (0x0358-0x035B)
  // then arms the swap (0x035C b0); RTL copies them to the live bitmapBase/
  // attrBase regs in ONE cycle at the start of vblank (see the commit block).
  // This is additive to the legacy 0x0351-0x0354 path (which keeps its
  // commit-at-hCounter0 semantics) so the fetcher never observes a mixed
  // old-LO/new-HI or old-plane/new-plane base => test07 tearing fix.
  //   0x0358 BITMAP_BASE_PENDING_LO   0x0359 BITMAP_BASE_PENDING_HI
  //   0x035A ATTR_BASE_PENDING_LO     0x035B ATTR_BASE_PENDING_HI
  //   0x035C BITMAP_SWAP_CTRL: b0 = arm request (host sets, RTL auto-clears at
  //          commit); b1 = committed (sticky, host write-1-to-clear acks it).
  val bitmapBaseSwapLo = (Reg(UInt(16 bits)) init 0x3000).simPublic()
  val bitmapBaseSwapHi = (Reg(UInt(7 bits))  init 0).simPublic()
  val attrBaseSwapLo   = (Reg(UInt(16 bits)) init 0x4000).simPublic()
  val attrBaseSwapHi   = (Reg(UInt(7 bits))  init 0).simPublic()
  val swapRequest      = (Reg(Bool()) init False).simPublic()
  val swapCommitted    = (Reg(Bool()) init False).simPublic()
  when(effWrite && effAddr === U(0x0358, 15 bits)) { bitmapBaseSwapLo := effData(15 downto 0).asUInt }
  when(effWrite && effAddr === U(0x0359, 15 bits)) { bitmapBaseSwapHi := effData(6 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035A, 15 bits)) { attrBaseSwapLo   := effData(15 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035B, 15 bits)) { attrBaseSwapHi   := effData(6 downto 0).asUInt }
  when(effWrite && effAddr === U(0x035C, 15 bits)) {
    when(effData(0)) { swapRequest   := True }   // arm
    when(effData(1)) { swapCommitted := False }  // W1C ack of committed flag
  }
  // Host-readable swap status: b0 = swapRequest, b1 = swapCommitted.
  io.swapStatus := (B(0, 14 bits) ## swapCommitted ## swapRequest)

  when(hCounter === U(0, log2Up(hTotal) bits)) {
    when(layerEnablePendHit) {
      layerEnableReg     := layerEnablePend
      layerEnablePendHit := False
    }
    when(tileDecodeModePendHit) {
      tileDecodeModeReg     := tileDecodeModePend
      tileDecodeModePendHit := False
    }
    when(attributeModePendHit) {
      attributeModeReg     := attributeModePend
      attributeModePendHit := False
    }
    when(backdropIndexPendHit) {
      backdropIndexReg     := backdropIndexPend
      backdropIndexPendHit := False
    }
    when(scaleCtrlPendHit) {
      scaleCtrlReg     := scaleCtrlPend
      scaleCtrlPendHit := False
    }
    when(logicWidthPendHit) {
      logicWidthReg     := logicWidthPend
      logicWidthPendHit := False
    }
    when(logicHeightPendHit) {
      logicHeightReg     := logicHeightPend
      logicHeightPendHit := False
    }
    when(innerBorderLPendHit) {
      innerBorderLReg     := innerBorderLPend
      innerBorderLPendHit := False
    }
    when(innerBorderRPendHit) {
      innerBorderRReg     := innerBorderRPend
      innerBorderRPendHit := False
    }
    when(innerBorderTPendHit) {
      innerBorderTReg     := innerBorderTPend
      innerBorderTPendHit := False
    }
    when(innerBorderBPendHit) {
      innerBorderBReg     := innerBorderBPend
      innerBorderBPendHit := False
    }
    when(copperCtrlPendHit) {
      copperCtrlReg     := copperCtrlPend
      copperCtrlPendHit := False
    }
    // Task 1 (#9154) — V=0 commit pulse drives modeSelect commit + side
    // effects below (out of this hCounter===0 block since the V=0 gate
    // is once per frame). See modeCommitPulse.
    when(winX0PendHit)     { winX0Reg     := winX0Pend;     winX0PendHit     := False }
    when(winX1PendHit)     { winX1Reg     := winX1Pend;     winX1PendHit     := False }
    when(winY0PendHit)     { winY0Reg     := winY0Pend;     winY0PendHit     := False }
    when(winY1PendHit)     { winY1Reg     := winY1Pend;     winY1PendHit     := False }
    when(colorMathPendHit) { colorMathReg := colorMathPend; colorMathPendHit := False }
    when(win2X0PendHit)    { win2X0Reg    := win2X0Pend;    win2X0PendHit    := False }
    when(win2X1PendHit)    { win2X1Reg    := win2X1Pend;    win2X1PendHit    := False }
    when(win2Y0PendHit)    { win2Y0Reg    := win2Y0Pend;    win2Y0PendHit    := False }
    when(win2Y1PendHit)    { win2Y1Reg    := win2Y1Pend;    win2Y1PendHit    := False }
    when(win2CtrlPendHit)  { win2CtrlReg  := win2CtrlPend;  win2CtrlPendHit  := False }
    when(winCombPendHit)   { winCombReg   := winCombPend;   winCombPendHit   := False }
    when(layerMaskPendHit) { layerMaskReg := layerMaskPend; layerMaskPendHit := False }
    // Task 50 v3 — visible-border window safe-boundary commits.
    when(borderX0PendHit)   { borderX0Reg   := borderX0Pend;   borderX0PendHit   := False }
    when(borderX1PendHit)   { borderX1Reg   := borderX1Pend;   borderX1PendHit   := False }
    when(borderY0PendHit)   { borderY0Reg   := borderY0Pend;   borderY0PendHit   := False }
    when(borderY1PendHit)   { borderY1Reg   := borderY1Pend;   borderY1PendHit   := False }
    when(borderCtrlPendHit) { borderCtrlReg := borderCtrlPend; borderCtrlPendHit := False }
    // Task 19 affine registers (safe-boundary commit).
    when(affineAPendHit)    { affineAReg    := affineAPend;    affineAPendHit    := False }
    when(affineBPendHit)    { affineBReg    := affineBPend;    affineBPendHit    := False }
    when(affineCPendHit)    { affineCReg    := affineCPend;    affineCPendHit    := False }
    when(affineDPendHit)    { affineDReg    := affineDPend;    affineDPendHit    := False }
    when(affineXPendHit)    { affineXReg    := affineXPend;    affineXPendHit    := False }
    when(affineYPendHit)    { affineYReg    := affineYPend;    affineYPendHit    := False }
    when(affineCtrlPendHit) { affineCtrlReg := affineCtrlPend; affineCtrlPendHit := False }
    // Task 44 bitmap-fetch register commits.
    when(bitmapCtrlPendHit)    { bitmapCtrlReg    := bitmapCtrlPend;    bitmapCtrlPendHit    := False }
    // BITMAP-PLUMB-129 bitmap/attr base/stride/height commits.
    when(bitmapBaseLoPendHit)  { bitmapBaseLoReg  := bitmapBaseLoPend;  bitmapBaseLoPendHit  := False }
    when(bitmapBaseHiPendHit)  { bitmapBaseHiReg  := bitmapBaseHiPend;  bitmapBaseHiPendHit  := False }
    when(attrBaseLoPendHit)    { attrBaseLoReg    := attrBaseLoPend;    attrBaseLoPendHit    := False }
    when(attrBaseHiPendHit)    { attrBaseHiReg    := attrBaseHiPend;    attrBaseHiPendHit    := False }
    when(bitmapStridePendHit)  { bitmapStrideReg  := bitmapStridePend;  bitmapStridePendHit  := False }
    when(attrStridePendHit)    { attrStrideReg    := attrStridePend;    attrStridePendHit    := False }
    when(bitmapHeightPendHit)  { bitmapHeightReg  := bitmapHeightPend;  bitmapHeightPendHit  := False }
  }

  // I80-FRAME-ATOMIC-SWAP-145: vblank-atomic base swap. At the first cycle of
  // vblank (vCounter===vActive, hCounter===0) copy all four staged base words
  // to the live regs in ONE cycle, so the fetcher sees either all-old or
  // all-new bases (never a torn mix). Placed AFTER the per-register hCounter0
  // commit above, so on the rare cycle a legacy 0x0351-0x0354 write commits at
  // the same vblank edge, the atomic swap value wins (staged is authoritative).
  // Auto-clears the request and raises the sticky committed flag for the host.
  when(hCounter === U(0, log2Up(hTotal) bits) &&
       vCounter === U(vActive, log2Up(vTotal) bits) &&
       swapRequest) {
    bitmapBaseLoReg := bitmapBaseSwapLo
    bitmapBaseHiReg := bitmapBaseSwapHi
    attrBaseLoReg   := attrBaseSwapLo
    attrBaseHiReg   := attrBaseSwapHi
    swapRequest     := False
    swapCommitted   := True
  }
  io.layer0TileDecodeMode := tileDecodeModeReg
  io.layer0AttributeMode  := attributeModeReg

  // Task 31 — per-layer scroll tables. 128 entries × 10 bits indexed by
  // hCounter(9 downto 3) (one band per 8 pixels, covering 640-pixel
  // active area with 80 in-frame entries + off-edge). Bus decode:
  //   0x0900..0x097F = layer 0 table (subAddr bit 7 = 0)
  //   0x0980..0x09FF = layer 1 table (subAddr bit 7 = 1)
  val scrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
  val scrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
  val scrollTableRangeHit = effWrite &&
    (effAddr >= U(0x0900, 15 bits)) &&
    (effAddr <  U(0x0A00, 15 bits))
  val scrollTableSub  = (effAddr - U(0x0900, 15 bits))(7 downto 0)
  val scrollTableEntry = scrollTableSub(6 downto 0)    // 7 bits
  val scrollTableLayer = scrollTableSub(7)             // 0 = L0, 1 = L1
  // VDP-SOFT-RESET-135 #2b: scroll tables (128 entries each) zeroed by the sweep
  // for addr < 128 — both H-scroll and V-scroll tables share this gate below.
  val scrollSweepWr = softResetMemClear && (softResetMemAddr < U(128, 14 bits))
  scrollTable0.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), scrollTableEntry)
  scrollTable0.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  scrollTable0.io.wr     := Mux(scrollSweepWr, True, scrollTableRangeHit && !scrollTableLayer)
  scrollTable1.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), scrollTableEntry)
  scrollTable1.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  scrollTable1.io.wr     := Mux(scrollSweepWr, True, scrollTableRangeHit && scrollTableLayer)

  val scrollTable0Addr = hCounter(9 downto 3).resize(7)
  val scrollTable1Addr = hCounter(9 downto 3).resize(7)
  scrollTable0.io.rdAddr := scrollTable0Addr
  scrollTable1.io.rdAddr := scrollTable1Addr
  val scrollTable0Offset = scrollTable0.io.rdData
  val scrollTable1Offset = scrollTable1.io.rdData

  // Task 46 — per-layer V-scroll tables. Structurally identical to the Task 31
  // H-scroll tables: 128 entries × 10 bits indexed by hCounter(9 downto 3);
  // each vertical band (~5 px wide across the 640-pixel active area) gets
  // its own Y offset added to `scrollY`. Default init-to-zero keeps existing
  // scenes bit-identical until host programs the table. Bus decode:
  //   0x0A00..0x0A7F = layer 0 V-scroll table (subAddr bit 7 = 0)
  //   0x0A80..0x0AFF = layer 1 V-scroll table (subAddr bit 7 = 1)
  val vScrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
  val vScrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
  val vScrollTableRangeHit = effWrite &&
    (effAddr >= U(0x0A00, 15 bits)) &&
    (effAddr <  U(0x0B00, 15 bits))
  val vScrollTableSub   = (effAddr - U(0x0A00, 15 bits))(7 downto 0)
  val vScrollTableEntry = vScrollTableSub(6 downto 0)    // 7 bits = 128 entries
  val vScrollTableLayer = vScrollTableSub(7)             // 0 = L0, 1 = L1
  // VDP-SOFT-RESET-135 #2b: V-scroll tables zeroed by the sweep (shared gate).
  vScrollTable0.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), vScrollTableEntry)
  vScrollTable0.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  vScrollTable0.io.wr     := Mux(scrollSweepWr, True, vScrollTableRangeHit && !vScrollTableLayer)
  vScrollTable1.io.wrAddr := Mux(scrollSweepWr, softResetMemAddr.resize(7), vScrollTableEntry)
  vScrollTable1.io.wrData := Mux(scrollSweepWr, U(0, 10 bits), effData(9 downto 0).asUInt)
  vScrollTable1.io.wr     := Mux(scrollSweepWr, True, vScrollTableRangeHit && vScrollTableLayer)

  val vScrollTable0Addr = hCounter(9 downto 3).resize(7)
  val vScrollTable1Addr = hCounter(9 downto 3).resize(7)
  vScrollTable0.io.rdAddr := vScrollTable0Addr
  vScrollTable1.io.rdAddr := vScrollTable1Addr
  val vScrollTable0Offset = vScrollTable0.io.rdData
  val vScrollTable1Offset = vScrollTable1.io.rdData

  // Layer 0 (lower priority background).
  val layer0 = BasicPatternSource()
  layer0.io.x := hCounter.resize(10)
  layer0.io.y := fillLine
  layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset
  layer0.io.scrollY := io.layer0ScrollY + vScrollTable0Offset

  // Test pattern source: combinational standard patterns for task validation.
  val testPattern = TestPatternSource()
  testPattern.io.x := hCounter.resize(10)
  testPattern.io.y := fillLine
  testPattern.io.patternSelect := io.layer0TestPatternSelect

  // === Task 3 — Planar Fetch Hardening (Checkpoint C, audit PASS #9313) ===
  // Multi-plane bitplane fetch path for Mode0 L0. PlanarLineFetch
  // combines BitplaneRowFetch (sdram dout32 reader) + BitplaneReconstruct
  // (per-pixel bit assembly). When planarFetchEnable is set via
  // PLANAR_CTRL @ 0x0D4A, slot 2 of the scheduler grants this client
  // its SDRAM bandwidth (clientId=2 on sdramArbiter, wired in
  // TopTang20kHdmi). 5 planes × 320 pixels = 50 dout32 reads/line.
  // planeBaseAddr[0..4] register-bus addresses at 0x0D40..0x0D49.
  // Task 3 risk #2 mitigation: planeCount=4 (Atari ST low-res — 16
  // colors, 4 bitplanes). 5-plane build hit CLS placement wall
  // (797 unplaced REGs from BitplaneRowFetch.planeWords storage =
  // 5×10×32 = 1,600 FFs exceeding CLS density). 4-plane saves
  // 1×10×32 = 320 FFs and lands within budget. Per artifact scope-
  // guard, this provisional drop preserves Task 3's "integration lane"
  // intent without reopening the standalone PlanarLineFetch primitive.
  // 5/6-plane Amiga OCS / EHB coverage deferred to a follow-on lane
  // that refactors planeWords to Mem-backed storage.
  val PLANE_COUNT = 5
  val PLANE_PIXELS = 320
  val planarLineFetch = PlanarLineFetch(sdramCd = effectiveSdramCd, planeCount = PLANE_COUNT, planePixels = PLANE_PIXELS, addrWidth = 23)
  val planarCtrlReg     = Reg(Bits(16 bits)) init 0
  val planeBaseAddrReg  = Vec.fill(PLANE_COUNT)(Reg(UInt(23 bits)) init 0)
  val planarFetchEnable = planarCtrlReg(0)
  // simPublic taps for PlanarIntegrationSim probes
  planarCtrlReg.simPublic()
  for (p <- 0 until PLANE_COUNT) planeBaseAddrReg(p).simPublic()

  // Register-bus decode for plane base addresses (PLANE_COUNT planes × 2 words each, lo/hi).
  val planarPlaneRangeHit = effWrite &&
    (effAddr >= U(0x0D40, 15 bits)) && (effAddr < U(0x0D40 + 2 * PLANE_COUNT, 15 bits))
  val planarCtrlWriteHit  = effWrite && (effAddr === U(0x0D4A, 15 bits))
  val planarSubAddr = (effAddr - U(0x0D40, 15 bits))(3 downto 0)   // 0..9
  val planarPlaneIdx = planarSubAddr(3 downto 1)                   // 0..4
  val planarHiSel    = planarSubAddr(0)                            // 0=lo, 1=hi
  when(planarPlaneRangeHit) {
    switch(planarPlaneIdx) {
      for (p <- 0 until PLANE_COUNT) {
        is(U(p, 3 bits)) {
          when(!planarHiSel) {
            planeBaseAddrReg(p)(15 downto 0)  := effData.asUInt
          } otherwise {
            planeBaseAddrReg(p)(22 downto 16) := effData(6 downto 0).asUInt
          }
        }
      }
    }
  }
  when(planarCtrlWriteHit) {
    planarCtrlReg := effData
  }

  planarLineFetch.io.planeBaseAddr  := planeBaseAddrReg
  // #3 part 2c: surface planar bases + active gate for the soft-reset zero-fill.
  io.planeBaseAddr    := planeBaseAddrReg
  io.planarFillActive := planarFetchEnable
  // Trigger row fetch one cycle into the active region — the FSM has
  // until next-line's display reaches pixelIdx N to land word N
  // (lead-time ≈ 160 cycles even for the first dout32 word).
  // Task 3 #9351 fix: align FSM start with slot 2's widened window so the
  // FSM transitions to State.Issue at the same cycle the slot opens
  // (hTotal-160) rather than 80 cycles before — the prior `hCounter ===
  // hActive` (= hTotal-160 only when hTotal=800 and hActive=640, which
  // matches by coincidence) is preserved as-is for now since hActive
  // happens to equal hTotal-160 with the widened slot. Documented for
  // future-proofing if either constant changes.
  planarLineFetch.io.start          := planarFetchEnable && (hCounter === U(hTotal - 160, log2Up(hTotal) bits))
  planarLineFetch.io.pixelIdx       := (hCounter % U(PLANE_PIXELS)).resize(log2Up(PLANE_PIXELS))
  // BronzeGate #9366 Path A: PlanarLineFetch's row-fetch FSM lives in
  // `effectiveSdramCd` and consumes data_ready/dout32/busy natively in
  // that domain. The top-level wires `io.planarSdram*` directly with
  // sdram-domain signals (no BufferCC stack here). On single-clock sims
  // (effectiveSdramCd == pixel CD), the wiring degenerates trivially.
  planarLineFetch.io.sdramBusy      := io.planarSdramBusy
  planarLineFetch.io.sdramDataReady := io.planarSdramDataReady
  planarLineFetch.io.sdramDout32    := io.planarSdramDout32
  io.planarSdramRd   := planarLineFetch.io.sdramRd
  io.planarSdramAddr := planarLineFetch.io.sdramAddr

  // Task 15 fetch-control outputs. Atomic CDC pattern per 6626/6628:
  //   1) Pulse-harden fetchStart: widen to 4 pixel cycles so the SDRAM-side
  //      BufferCC (2-stage synchronizer) reliably samples it despite routing
  //      delay and phase alignment with the 40.5 MHz SDRAM clock.
  //   2) Atomic latch: capture fetchLine/scrolls into registers ONCE on the
  //      line-boundary strobe so the multi-bit CDC sees stable values between
  //      pulses. Sampling `(vCounter+3)` combinationally through BufferCC would
  //      let bits transition asynchronously during the sync, risking a "torn"
  //      scanline index on specific raster positions.
  // R3: Static fetch-slot scheduler replaces the reactive end-of-line strobe.
  // Reading-B scope: a single tile-client slot at hCounter==hTotal-1 preserves
  // the pre-R3 strobe timing bit-for-bit, so the existing Task-15 fetch path
  // is unchanged from a behavioral standpoint. Extra slots are wired disabled
  // and remain available for future clients (e.g. sprite-to-SDRAM) without
  // further structural change.
  val scheduler = FetchSlotScheduler(slotCount = 8)
  // Task 56 Checkpoint C: simPublic mirror so MultiLayerSdramFetchSim
  // Cases 4-5 can observe per-line slot-grant counts (proves L1 slot 3
  // fires after the CP-C scheduler retime and planar slot 2 coexists).
  val schedulerLineGrantCount = CombInit(scheduler.io.lineGrantCount).simPublic()
  val schedulerGrantClientId  = CombInit(scheduler.io.grantClientId).simPublic()
  scheduler.io.hCounter  := hCounter.resize(10)
  scheduler.io.lineStart := hCounter === 0
  // R4.1: multi-slot schedule for clientId=0 (tile+attribute fetch). Three
  // non-contiguous windows prove pause/resume across slot gaps:
  //   slot 0: grant at hblank-end strobe (hTotal-1), window covers hblank
  //           into the start of the next line — starts a fresh fetch cycle
  //   slot 1: mid-line burst for additional SDRAM bandwidth
  //   slot 2: late-line burst for cleanup reads
  // Grant fires at startH of each slot; only slot 0's grant is consumed by
  // the fetch FSM's sIdle transition, the others simply widen slotValid.
  scheduler.io.schedule(0).enabled  := True
  scheduler.io.schedule(0).clientId := U(0, 2 bits)
  scheduler.io.schedule(0).startH   := U(hTotal - 1, 10 bits)
  scheduler.io.schedule(0).endH     := U(hTotal - 1, 10 bits)
  // Per CyanPeak #6804: slot 1 widened to cover the full line so the fetch
  // engine has continuous SDRAM bandwidth. slotValid gating still exercises
  // the pause/resume path at the line/domain boundary (grant → slot 0 pulse,
  // slotValid open for the whole line). Slot 2 disabled — the "thin lines"
  // artifact was caused by the prior h=[320,399] bandwidth gap.
  scheduler.io.schedule(1).enabled  := True
  scheduler.io.schedule(1).clientId := U(0, 2 bits)
  scheduler.io.schedule(1).startH   := U(0, 10 bits)
  // Task 56 Checkpoint C (#9678 §1 Resolution): narrow L0 burst window from
  // [0, hTotal-1] to [0, 399] so L1 burst slot 4 [400, hTotal-1] is exclusive
  // for clientId=3. L0 needs ~656 SDRAM cycles for 41 tiles ≈ 164 pixel cycles,
  // so 400 pixel cycles still gives ~2.4× margin (per artifact bandwidth table).
  scheduler.io.schedule(1).endH     := U(399, 10 bits)
  // Task 3 (Checkpoint A #9313): slot 2 dedicated to PlanarLineFetch
  // (clientId=2), gated on planarFetchEnable. Window covers H-blank
  // adjacent so 50 × dout32 reads for 5-plane × 320-pixel rows can be
  // granted without colliding with tile fetch's slot 0 (hTotal-1) or
  // slot 1 (full active line). FSM start is independent (mid-line)
  // per design packet §1.
  scheduler.io.schedule(2).enabled  := planarFetchEnable
  scheduler.io.schedule(2).clientId := U(2, 2 bits)
  // Task 3 #9351 fix (CoralReef bandwidth diagnosis): widen slot 2 from
  // 80 cycles (hTotal-80..hTotal-1) to 160 cycles (hTotal-160..hTotal-1).
  // 50 dout32 reads × 5 SDRAM cycles each = ~97 pixel-domain cycles
  // minimum; the 80-cycle window was below that floor. 160 cycles gives
  // headroom for FSM/CDC overhead and avoids deadlock when reads in
  // flight straddle the slot boundary.
  scheduler.io.schedule(2).startH   := U(hTotal - 160, 10 bits)
  scheduler.io.schedule(2).endH     := U(hTotal - 1,   10 bits)
  // Task 56 Checkpoint A — L1 fetch slots reserved on the scheduler
  // (clientId=3, per CyanPeak audit #9683 correction). Bandwidth plan
  // per artifact #9678:
  //   slot 3 (start):  hTotal-1   (single-cycle grant edge to start FSM)
  //   slot 4 (burst):  [400, hTotal-1]   (continuous bandwidth window)
  // Slots 0/1 still cover [hTotal-1] start and [0..hTotal-1] burst for
  // L0; FetchSlotScheduler resolves overlap by lowest-slot-index wins,
  // which gives Planar (slot 2) > L0 (slots 0/1) > L1 (slots 3/4) —
  // matches the audit-confirmed priority ranking. L1 FSM stalls in Rq
  // states during higher-priority overlap and resumes when slot 4 is
  // again exclusive.
  //
  // `enabled` is held False until the L1 SdramTileAttributeFetch
  // instance lands in Checkpoint B; at that point a new
  // `layer1FetchEnable` signal will gate this the same way Task 3
  // gates planar slot 2 on `planarFetchEnable`.
  // Task 56 Checkpoint B (#9678 / #9693): scheduler L1 slots enabled when
  // the top-level wires up an SDRAM-backed Layer 1 (clientId=3). When the
  // host runs an L1-disabled scene, `layer1UseSdram` is False so slots 3/4
  // stay gated off and the engine port stays inert at the arbiter.
  // NOTE on bandwidth: CP-A reserved slot 3 at hTotal-1 (collides with L0
  // slot 0) and L0 slot 1 still spans [0, hTotal-1] (full line). Under the
  // current scheduler, L1's grant edge is always shadowed by L0 → L1 FSM
  // stays in sIdle in practice. Checkpoint C will narrow L0 slot 1 to
  // [0, 399] and move L1 start slot to h=400 per artifact #9678 §1
  // "Resolution" plan. CP-B only proves the integration plumbing.
  val layer1FetchEnable = io.layer1UseSdram
  // PM #9907 Step 2: compile-time gate on L1 scaffolding. When
  // enableL1Fetch=false, the scheduler slot 3/4 entries collapse to disabled
  // tie-offs and the L1 fetch IO/registers below are likewise gated to
  // constant ties. This is a fit-stabilization probe — the L1 architectural
  // path stays available; only the surviving scaffolding is exercised.
  if (enableL1Fetch) {
    scheduler.io.schedule(3).enabled  := layer1FetchEnable
    scheduler.io.schedule(3).clientId := U(3, 2 bits)
    scheduler.io.schedule(3).startH   := U(400, 10 bits)
    scheduler.io.schedule(3).endH     := U(400, 10 bits)
    scheduler.io.schedule(4).enabled  := layer1FetchEnable
    scheduler.io.schedule(4).clientId := U(3, 2 bits)
    scheduler.io.schedule(4).startH   := U(400,        10 bits)
    scheduler.io.schedule(4).endH     := U(hTotal - 1, 10 bits)
    for (i <- 5 until 8) {
      scheduler.io.schedule(i).enabled  := False
      scheduler.io.schedule(i).clientId := U(0, 2 bits)
      scheduler.io.schedule(i).startH   := U(0, 10 bits)
      scheduler.io.schedule(i).endH     := U(0, 10 bits)
    }
  } else {
    for (i <- 3 until 8) {
      scheduler.io.schedule(i).enabled  := False
      scheduler.io.schedule(i).clientId := U(0, 2 bits)
      scheduler.io.schedule(i).startH   := U(0, 10 bits)
      scheduler.io.schedule(i).endH     := U(0, 10 bits)
    }
  }

  val fetchStartStrobe = scheduler.io.grant

  val fetchStartCount = Reg(UInt(3 bits)) init 0
  when(fetchStartStrobe) {
    fetchStartCount := 4
  }.elsewhen(fetchStartCount =/= 0) {
    fetchStartCount := fetchStartCount - 1
  }

  // R4.2-redo Early Latch fix (#7120 / #7121): latch fetch data ONE pixel-
  // cycle BEFORE the grant pulse so the multi-bit BufferCC synchronizers on
  // the SDRAM side see fully-stable operands when fetchGrantEdge fires.
  // Previously, reg update and grant coincided at hCounter=hTotal-1,
  // producing a classic source-domain race that manifested as systematic
  // wrong-bank scanlines at tile-row boundaries.
  val earlyLatchStrobe = hCounter === U(hTotal - 2, log2Up(hTotal) bits)
  val fetchLineReg    = RegNextWhen((vCounter + 3).resize(10),
                                    earlyLatchStrobe) init 0
  // Task 31: include scroll-table offset in the SDRAM-fetch scroll
  // snapshot. At `earlyLatchStrobe` hCounter is known, so the table
  // read produces a deterministic per-line offset. Full per-column
  // behaviour is visible only in the on-chip BasicPatternSource path;
  // the SDRAM fetch sees one offset per line.
  val fetchScrollXReg = RegNextWhen(
    (io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset).resize(10),
    earlyLatchStrobe) init 0
  val fetchScrollYReg = RegNextWhen(io.layer0ScrollY, earlyLatchStrobe) init 0

  io.layer0FetchStart       := fetchStartCount =/= 0
  // R4.1: only the "start-of-fetch-cycle" slot (slot 0 at hTotal-1) produces
  // the grant edge that transitions the fetch FSM from sIdle. The scheduler's
  // raw `grant` fires at every slot's startH, but secondary grants during a
  // line would reset the fetch mid-flight. Gate grant to the start strobe
  // only; let slotValid stay as the raw OR of all slot windows so reads can
  // span all three slots.
  // R4.2-redo Stage 2 (CyanPeak #7130): widen the grant pulse to 4 pixel
  // cycles so the SDRAM-side BufferCC reliably samples it after the bundled
  // fetch-data synchronizer has settled. Narrow 1-cycle pulses combined with
  // the bundled BufferCC's 2-cycle settling window gave the grant edge too
  // little margin on real silicon.
  val grantRaw  = scheduler.io.grant && (hCounter === hTotal - 1)
  val grantHold = Reg(UInt(3 bits)) init 0
  when(grantRaw) {
    grantHold := 4
  }.elsewhen(grantHold =/= 0) {
    grantHold := grantHold - 1
  }
  io.layer0FetchGrant       := grantHold =/= 0
  io.layer0FetchSlotValid   := scheduler.io.slotValid
  io.layer0FetchPreAnnounce := scheduler.io.preAnnounce
  io.layer0FetchGrantClientId := scheduler.io.grantClientId
  io.layer0FetchLine        := fetchLineReg
  io.layer0FetchScrollX     := fetchScrollXReg
  io.layer0FetchScrollY     := fetchScrollYReg
  /* Pre-advance pixelAddr by 1 cycle to compensate for the
   * SdramTileAttributeFetch / SdramTileFetch line-buffer `readSync`
   * latency. Without this, the leftmost active pixel of every scanline
   * paints with the previous clock's stale `readWord` (1-pixel bank-0
   * transient on the left edge — #10542/#10546).
   *
   * Mirrors the existing drainAddr pattern at line ~1610 (CyanPeak audit
   * #8760, sprite-pattern lane); explicit wrap at hTotal-1 → 0 so the
   * last active pixel (hCounter == hActive-1) reads mem[hActive-1] then
   * resets to 0 for the next line — without the conditional, the +1
   * would index past the line buffer's hActive-deep range. */
  val layer0FetchPixelAddrReg = UInt(10 bits)
  when(hCounter === hTotal - 1) {
    layer0FetchPixelAddrReg := U(0, 10 bits)
  }.elsewhen(hCounter < hActive - 1) {
    layer0FetchPixelAddrReg := (hCounter + 1).resize(10)
  }.otherwise {
    layer0FetchPixelAddrReg := U(0, 10 bits)
  }
  io.layer0FetchPixelAddr := layer0FetchPixelAddrReg

  // Task 56 Checkpoint B (#9678 / #9693): L1 fetch scheduler outputs.
  // Latch registers mirror the L0 earlyLatchStrobe pattern with `layer1*`
  // scroll inputs substituted. Grant pulse is gated on
  // `grantClientId === 3` so only L1's slot entries propagate to the L1
  // fetch engine; slotValid/preAnnounce are similarly client-id filtered
  // so the L1 FSM never sees an L0/Planar window as its own.
  if (enableL1Fetch) {
    val layer1FetchLineReg    = RegNextWhen((vCounter + 3).resize(10),
                                            earlyLatchStrobe) init 0
    val layer1FetchScrollXReg = RegNextWhen(
      (io.layer1ScrollX + scrollTable1Offset).resize(10),
      earlyLatchStrobe) init 0
    val layer1FetchScrollYReg = RegNextWhen(io.layer1ScrollY, earlyLatchStrobe) init 0

    val layer1GrantRaw  = scheduler.io.grant &&
                          (scheduler.io.grantClientId === U(3, 2 bits))
    val layer1GrantHold = Reg(UInt(3 bits)) init 0
    when(layer1GrantRaw) {
      layer1GrantHold := 4
    }.elsewhen(layer1GrantHold =/= 0) {
      layer1GrantHold := layer1GrantHold - 1
    }
    io.layer1FetchGrant         := layer1GrantHold =/= 0
    io.layer1FetchSlotValid     := scheduler.io.slotValid &&
                                   (scheduler.io.grantClientId === U(3, 2 bits))
    io.layer1FetchPreAnnounce   := scheduler.io.preAnnounce &&
                                   (scheduler.io.grantClientId === U(3, 2 bits))
    io.layer1FetchGrantClientId := scheduler.io.grantClientId
    io.layer1FetchLine          := layer1FetchLineReg
    io.layer1FetchScrollX       := layer1FetchScrollXReg
    io.layer1FetchScrollY       := layer1FetchScrollYReg
    io.layer1FetchPixelAddr     := hCounter.resize(10)
  } else {
    io.layer1FetchGrant         := False
    io.layer1FetchSlotValid     := False
    io.layer1FetchPreAnnounce   := False
    io.layer1FetchGrantClientId := U(0, 2 bits)
    io.layer1FetchLine          := U(0, 10 bits)
    io.layer1FetchScrollX       := U(0, 10 bits)
    io.layer1FetchScrollY       := U(0, 10 bits)
    io.layer1FetchPixelAddr     := U(0, 10 bits)
  }

  // Layer 1 (higher priority background).
  val layer1 = BasicPatternSource()
  layer1.io.x := hCounter.resize(10)
  layer1.io.y := fillLine
  layer1.io.scrollX := io.layer1ScrollX + scrollTable1Offset
  layer1.io.scrollY := io.layer1ScrollY + vScrollTable1Offset

  // Task 48 — Layer 2 and Layer 3. Simple BasicPatternSource layers with
  // global-only scroll (no per-column scroll tables or per-line enable —
  // those remain deferred). Compositor priority: L3 > L2 > L1 > L0 when
  // no L0 forcedPriority override is active; sprite slots still win via
  // the existing back-to-front iteration.
  //
  // Gate #2 (`enableL2L3`, default false): drop the L2/L3 BasicPatternSource
  // instances entirely from the default build. The `layer2/3ScrollX/Y` IO
  // ports remain declared on the bundle (zero hardware cost; they get
  // pruned at elaboration when nothing reads them) so TopTang20kHdmi can
  // wire them unconditionally. Downstream pixel/opaque signals are tied
  // off below to keep the compositor chain bit-identical to pre-Task-48
  // 2-layer behavior when the gate is off.
  val (layer2PixelRaw, layer3PixelRaw) = if (enableL2L3) {
    val layer2 = BasicPatternSource()
    layer2.io.x := hCounter.resize(10)
    layer2.io.y := fillLine
    layer2.io.scrollX := io.layer2ScrollX
    layer2.io.scrollY := io.layer2ScrollY

    val layer3 = BasicPatternSource()
    layer3.io.x := hCounter.resize(10)
    layer3.io.y := fillLine
    layer3.io.scrollX := io.layer3ScrollX
    layer3.io.scrollY := io.layer3ScrollY

    (layer2.io.pixelIndex, layer3.io.pixelIndex)
  } else {
    (B(0, 3 bits), B(0, 3 bits))
  }

  // Task 19 Checkpoint B: affine coordinate generator + texture BRAM. The
  // stepper runs combinationally against the current (hCounter, fillLine) so
  // its output is available in the same cycle as the existing layer0/layer1
  // sources. The texture is a 128×128 ROM-initialised Mem with async read.
  val affineStepper = AffineStepper()
  affineStepper.io.x := hCounter.resize(10)
  affineStepper.io.y := fillLine
  affineStepper.io.matrixA := affineAReg
  affineStepper.io.matrixB := affineBReg
  affineStepper.io.matrixC := affineCReg
  affineStepper.io.matrixD := affineDReg
  affineStepper.io.transX  := affineXReg
  affineStepper.io.transY  := affineYReg

  val affineTexture = Mem(Bits(8 bits), AffineAssets.Width * AffineAssets.Height)
    .init(AffineAssets.textureInit)
  val affineAddr  = (affineStepper.io.vInt ## affineStepper.io.uInt).asUInt
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — affine texture sample read
  // combinationally per pixel from the affine UV stepper; consumer is the
  // affineIndex/affineBank/affinePrio decomposition below feeding the L0 mux.
  // Candidate for readSync conversion + 1-cycle pipeline on the stepper output.
  val affinePixel = affineTexture.readAsync(affineAddr)
  val affineIndex = affinePixel(3 downto 0)
  val affineBank  = affinePixel(6 downto 4).asUInt
  val affinePrio  = affinePixel(7)

  // Task 15: runtime Layer-0 source mux. When layer0UseSdram is high, the
  // SDRAM-backed pixel from the external fetch engine feeds L0. The on-chip
  // BasicPatternSource is kept instantiated and reading as the comparison
  // baseline so A/B can happen on the same hardware image.
  // R4: L0 carries {index[4], bank[3], priority[1]} when driven by the R4
  // fetch engine; when fed by the on-chip 3bpp source we zero-extend the index
  // and force bank=0 / priority=0 to keep the legacy-path rendering identical.
  // Test-pattern override: when enabled, forces standard validation pattern
  // regardless of SDRAM or on-chip path state.
  val onChipIdx4   = layer0.io.pixelIndex.resize(4)
  // Task 44 — bitmap fetch pixel decoder.
  //
  val bitmapFetch = BitmapFetch()
  // Bitmap + attribute byte are sourced directly from the SDRAM-backed
  // BitmapRowFetch line buffers via the top-level wiring.
  val bmByteSel = io.bitmapSdramByte
  val bmAttrSel = io.bitmapSdramAttrByte
  bitmapFetch.io.bitmapByte      := bmByteSel
  bitmapFetch.io.attrByte        := bmAttrSel
  bitmapFetch.io.pixelWithinByte := hCounter(2 downto 0)
  bitmapFetch.io.bpp             := bitmapBpp
  // RGB565 directcolor (bpp=10): the 16-bit directcolor pixel is the two
  // fetched bytes packed {hi=attr, lo=bitmap}. CP-1b reuses the existing
  // bitmap+attr fetch as the lo/hi byte pair; CP-1c will widen
  // BitmapRowFetch to per-pixel (2-byte) addressing so each column has a
  // distinct RGB565 value (today they repeat across the fetcher's
  // 8-column byte span). The decoder raises directColorActive only for
  // bpp=0b10, so indexed bitmap modes are bit-unaffected.
  bitmapFetch.io.directPixel     := bmAttrSel ## bmByteSel

  // Export coupling signals to BitmapRowFetch at top level.
  io.bitmapSdramCol        := hCounter.resize(10)
  io.bitmapSdramFetchLine  := fillLine.resize(10)
  // RGB565-FULLFRAME-132 B.2 (CoralReef #12355 cond.4): grant ONCE PER SOURCE ROW,
  // not once per output line. Each source row is displayed on two output lines
  // (line-doubling: fillLine = vCounter+1, lineReg = pendingLine>>1), so the bank
  // rotation + fill-ahead geometry is only correct when the grant advances every
  // SECOND output line. Fire at hCounter==hTotal-1 (end of line, so the freshly
  // filled bank lands for the next line's pixel 0) gated on odd output lines
  // (vCounter(0)) and only within the active region (vCounter < vActive). The old
  // once-per-line hActive grant double-counted rows and broke the cadence.
  io.bitmapSdramFetchGrant := (hCounter === U(hTotal - 1, log2Up(hTotal) bits)) &&
                              (vCounter(0) === True) &&
                              (vCounter < U(vActive, log2Up(vTotal) bits))
  io.bitmapModeActive      := bitmapEnable
  // CP-1c: tell BitmapRowFetch to use the RGB565 directcolor fetch
  // schedule (2 bytes/pixel, 320 px/row) when bpp=0b10 is selected.
  // (HAM6 shelved #14224 — bpp=0b11 is now reserved and no longer selects directcolor.)
  io.bitmapDirectColor     := bitmapEnable && (bitmapBpp === U(2, 2 bits))
  // BITMAP-PLUMB-129: assemble the 23-bit bases (HI##LO) and drive the
  // geometry outputs to BitmapRowFetch via the top level.
  io.bitmapBase            := (bitmapBaseHiReg ## bitmapBaseLoReg).asUInt
  io.attrBase              := (attrBaseHiReg   ## attrBaseLoReg).asUInt
  io.bitmapStride          := bitmapStrideReg
  io.attrStride            := attrStrideReg
  io.bitmapHeight          := bitmapHeightReg

  // Task 19: when affineEnable is high, the affine-texture lookup wins over
  // every other L0 source (test-pattern / SDRAM / on-chip). Task 44
  // inserts the bitmap-fetch path between affine and SDRAM; when
  // bitmapEnable=0 (default) the ordering and values are unchanged.
  // Task 3 — planar fetch is an additional L0 source. When
  // planarFetchEnable is set, the planar pixel (5 bits) projects to the
  // 4-bit L0 idx + 1-bit bank-select for Amiga OCS 32-color coverage:
  //   idx[3:0] := planarPixel[3:0]
  //   bank[0]  := planarPixel[4]   (other bank bits = 0 → palette banks 0/1)
  //   prio     := False (priority handled by adapter-local future work)
  // 5-plane pixel = 4-bit palette idx + 1-bit bank-select for Amiga OCS
  // 32-color coverage (idx[3:0] in palette banks 0/1).
  val planarPixel = planarLineFetch.io.pixel
  val planarIdx4  = planarPixel(3 downto 0)
  val planarBank3 = (B"00" ## planarPixel(4)).asUInt
  // 320-pixel planar clipping mask (PM #9736, MODE0_PLANNING.md §6 rank 3).
  // The planar source's native width is PLANE_PIXELS=320; `planarLineFetch
  // .io.pixelIdx` is driven `hCounter % 320`, which means planar output
  // wraps and repeats for hCounter in [320, 639]. Suppress the planar
  // contribution to L0 outside the [0, 320) window so the existing L0
  // source chain (affine → test pattern → bitmap → SDRAM → on-chip
  // BasicPatternSource with layer0ScrollX/Y) is preserved bit-identically
  // there. Consumer-side gate only — no planar fetch rewrite, no
  // scheduler change, no scroll-latch change.
  // #4: clip width is now the PLANAR_WIDTH register (default PLANE_PIXELS=320).
  val planarClipActive          = (hCounter < planarWidthReg.resize(log2Up(hTotal))).simPublic()
  val planarFetchEnableClipped  = (planarFetchEnable && planarClipActive).simPublic()
  val layer0Index = (Mux(planarFetchEnableClipped, planarIdx4,
                         Mux(affineEnable, affineIndex,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.pixelIndex,
                             Mux(bitmapEnable, bitmapFetch.io.pixelIndex.asBits,
                                 Mux(io.layer0UseSdram, io.layer0SdramPixel, onChipIdx4)))))).simPublic()
  val layer0Bank  = (Mux(planarFetchEnableClipped, planarBank3,
                         Mux(affineEnable, affineBank,
                         Mux(io.layer0TestPatternEnable,
                             testPattern.io.paletteBank,
                             Mux(bitmapEnable, bitmapFetch.io.paletteBank,
                                 Mux(io.layer0UseSdram, io.layer0SdramBank,  U(0, 3 bits))))))).simPublic()
  val layer0Prio  = (Mux(planarFetchEnableClipped, False,
                         Mux(affineEnable, affinePrio,
                         Mux(io.layer0TestPatternEnable,
                             False,
                             Mux(bitmapEnable, False,
                                 Mux(io.layer0UseSdram, io.layer0SdramPriority, False)))))).simPublic()

  // R5: fold global LAYER_ENABLE register into the per-line linestate enable.
  // Task 48: L2/L3 use global enable only (bits 3/4) — LinestateStore is
  // NOT widened per artifact §3.5. bit 2 is sprite enable; unchanged.
  val effectiveL0Enable = linestate.io.layer0Enable && layerEnableReg(0)
  val effectiveL1Enable = linestate.io.layer1Enable && layerEnableReg(1)
  val effectiveL2Enable = layerEnableReg(3)
  val effectiveL3Enable = layerEnableReg(4)
  val layer0Pixel = Mux(effectiveL0Enable, layer0Index, B(0, 4 bits))
  val layer0PrioGated = effectiveL0Enable && layer0Prio
  // Task 56 Checkpoint A — L1 source mux. When `layer1UseSdram` is
  // asserted (driven by the L1 fetch engine in Checkpoint B), L1 takes
  // its pixel/bank/priority from the SDRAM-backed inputs; otherwise the
  // existing on-chip BasicPatternSource L1 path is preserved
  // bit-identically.  Compositor priority logic (L3 > L2 > L1 > L0) is
  // unchanged per artifact #9678 / audit #9683.
  val layer1Index = Mux(io.layer1UseSdram, io.layer1SdramPixel, layer1.io.pixelIndex.resize(4)).simPublic()
  val layer1Bank  = Mux(io.layer1UseSdram, io.layer1SdramBank,  U(0, 3 bits)).simPublic()
  val layer1Prio  = Mux(io.layer1UseSdram, io.layer1SdramPriority, False)

  val layer1Pixel = Mux(effectiveL1Enable, layer1Index, B(0, 4 bits))
  // Gate #2: when `enableL2L3=false`, `layer2PixelRaw`/`layer3PixelRaw`
  // are constant B(0,3 bits) (see L2/L3 instantiation block above) so
  // these Muxes degenerate to constant 0 → both opaque flags below stay
  // False → compositor reverts to the pre-Task-48 2-layer behavior.
  val layer2Pixel = Mux(effectiveL2Enable, layer2PixelRaw.resize(4), B(0, 4 bits))
  val layer3Pixel = Mux(effectiveL3Enable, layer3PixelRaw.resize(4), B(0, 4 bits))

  // Four-layer priority-aware composition. L0 forcedPriority override wins
  // over ALL layers (preserved from the 2-layer era). Otherwise, the
  // highest-index opaque layer wins (L3 > L2 > L1 > L0). When the only
  // visible layer is L0 (or nothing), L0 paints. This is bit-identical to
  // the pre-Task-48 2-layer compositor whenever L2/L3 are disabled (zero
  // pixel, not opaque).
  // #3: a layer pixel is opaque when its index differs from that layer's
  // transparency key (default key 0 ⇒ index-0-transparent, bit-identical).
  val layer0Opaque = layer0Pixel =/= l0TransKeyReg
  val layer1Opaque = layer1Pixel =/= l1TransKeyReg
  val layer2Opaque = layer2Pixel =/= l2TransKeyReg
  val layer3Opaque = layer3Pixel =/= l3TransKeyReg
  // Task 56 Checkpoint C: simPublic so MultiLayerSdramFetchSim Cases 3-5
  // can observe the compositor's actual mux output (proves L1>L0 opaque
  // priority and bank propagation under both-active workload).
  val composedBgIdx    = Bits(4 bits).simPublic()
  val composedBgBank   = UInt(3 bits).simPublic()
  val composedBgSource = UInt(3 bits)   // feeds fillMeta.layerSource
  when(layer0PrioGated && layer0Opaque) {
    composedBgIdx    := layer0Pixel
    composedBgBank   := layer0Bank
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }.elsewhen(layer3Opaque) {
    composedBgIdx    := layer3Pixel
    composedBgBank   := U(0, 3 bits)  // L3 uses legacy bank 0 like L1/L2
    composedBgSource := U(PixelMetadata.SourceBG3, 3 bits)
  }.elsewhen(layer2Opaque) {
    composedBgIdx    := layer2Pixel
    composedBgBank   := U(0, 3 bits)
    composedBgSource := U(PixelMetadata.SourceBG2, 3 bits)
  }.elsewhen(layer1Opaque) {
    composedBgIdx    := layer1Pixel
    // Task 56 — when L1 is fed by SDRAM the bank can be non-zero (4×16
    // colour banks of L0 mirror); falls back to bank 0 for the existing
    // on-chip BasicPatternSource path (bit-identical pre-Task-56).
    composedBgBank   := layer1Bank
    composedBgSource := U(PixelMetadata.SourceBG1, 3 bits)
  }.elsewhen(layer0Opaque) {
    // #11867 (CoralReef) ROOT-CAUSE FIX: the normal (non-priority) L0 paint path
    // was missing — only layer0PrioGated had a branch (the first `when`). A
    // non-priority opaque L0 (e.g. planar, whose layer0Prio is hardwired False at
    // :1376) fell through to .otherwise -> backdrop, so it never displayed. This
    // restores the compositor's own documented contract: "When the only visible
    // layer is L0 (or nothing), L0 paints." Opacity convention (index-0 transparent,
    // bank-ignored) is unchanged — see layer0Opaque @1416 / drainBgOpaque @1738.
    composedBgIdx    := layer0Pixel
    composedBgBank   := layer0Bank
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }.otherwise {
    // Backdrop: no layer is opaque (or all layers disabled). Display the
    // host-programmed BACKDROP_INDEX as an absolute 7-bit palette index.
    // Splitting it into bank[6:4] + idx[3:0] makes the downstream
    // `palette[bank*16+idx]` lookup map to palette[BACKDROP_INDEX] linearly.
    composedBgIdx    := backdropIndexReg(3 downto 0).asBits
    composedBgBank   := backdropIndexReg(6 downto 4)
    composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
  }
  val composedBg = composedBgIdx

  // Task 28: two-pass sprite evaluator over 32 descriptors, 8 visible per
  // line. Slots 0..3 come from the top-level sprite* inputs (backwards-
  // compat with TopTang20kHdmi scenarios + existing sims); slots 4..31
  // are Reg-backed and bus-programmable via the Mode0 register block at
  // 0x0800..0x083F. See SpriteEvaluator.scala for the slot layout and
  // the word-0 / word-1 packing.
  // Task 45 (BronzeGate #8189): restore sprite evaluator to full parametric
  // form. SpriteEvaluator case-class defaults are descCount=64, visiblePerLine=32.
  // Live instantiation is descCount=8, visiblePerLine=8 per Task 57 Path 5A.
  // 4 legacy IO slots + 4 bus-programmable extended slots.
  val spriteEval = SpriteEvaluator(
    // descCount=32 landed per BronzeGate #10363 (2026-05-19 lane).
    // The earlier descCount=16 PnR failure (`PR0003`, 7539 unplaced REGs)
    // and the 51 k-logic blowup at 32 were both artefacts of the old
    // readAsync descriptor-Mem substrate, which Gowin promoted to DFFs.
    // The storage-move redesign (#10357: descriptor Mems readAsync →
    // readSync/BSRAM) removed that promotion; the descCount=16/32
    // feasibility proof (#10360) showed both place, route, and meet
    // timing on Tang Nano with near-flat scaling. #10363 authorises
    // landing descCount=32 with visiblePerLine held at 8.
    descCount      = 32,
    visiblePerLine = 8,   // #10363: held at 8 (visible-per-line unchanged)
    patternSelBits = SpriteEvaluator.PatIdxWidth,   // Task 53 (#9419): 6 bits
    legacyIoCount  = 4)
  spriteEval.io.descX(0)          := io.sprite0X
  spriteEval.io.descY(0)          := io.sprite0Y
  spriteEval.io.descEnabled(0)    := io.sprite0Enabled
  spriteEval.io.descPatternIdx(0) := io.sprite0PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(1)          := io.sprite1X
  spriteEval.io.descY(1)          := io.sprite1Y
  spriteEval.io.descEnabled(1)    := io.sprite1Enabled
  spriteEval.io.descPatternIdx(1) := io.sprite1PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(2)          := io.sprite2X
  spriteEval.io.descY(2)          := io.sprite2Y
  spriteEval.io.descEnabled(2)    := io.sprite2Enabled
  spriteEval.io.descPatternIdx(2) := io.sprite2PatternIdx.resize(SpriteEvaluator.PatIdxWidth)
  spriteEval.io.descX(3)          := io.sprite3X
  spriteEval.io.descY(3)          := io.sprite3Y
  spriteEval.io.descEnabled(3)    := io.sprite3Enabled
  spriteEval.io.descPatternIdx(3) := io.sprite3PatternIdx.resize(SpriteEvaluator.PatIdxWidth)

  // Mode0RegBus decode for 0x0800..0x08FF → evaluator bus-write port.
  // Task 37 extended layout: 8 words per slot (word 0..7 = enable/pat/aff/y,
  // x, matA, matB, matC, matD, transX, transY). Task 45 restores full scale:
  // 32 slots × 8 words = 256 addresses (0x0800..0x08FF). slot = subAddr[7:3]
  // (5 bits → 32 slots), word = subAddr[2:0] (unchanged).
  // Kept bit-identical for scenario 28 and any host firmware that hardcodes
  // `slot*8 + word`.
  val spriteBusRangeHit = effWrite &&
    (effAddr >= U(0x0800, 15 bits)) &&
    (effAddr <  U(0x0900, 15 bits))
  val spriteBusSub    = (effAddr - U(0x0800, 15 bits))(7 downto 0)
  val spriteBusSlot8  = spriteBusSub(7 downto 3).resize(spriteEval.descIdxBits)
  val spriteBusWord8  = spriteBusSub(2 downto 0).resize(spriteEval.busWordBits)

  // Sprite Envelope Hardening (CyanPeak #8577): word 8 lives in a
  // separate bus block so the legacy 8-words-per-slot map above stays
  // intact. 0x0D20..0x0D3F = 32 slots × 1 word (word 8 only).
  // slot = subAddr[4:0]. busWord forced to 8.
  // (Phase 2 fix: original 0x0900..0x091F conflicted with L0 scroll
  // table; 0x0C00..0x0C1F conflicted with the Blitter control range
  // 0x0C00..0x0D0F; 0x0D20 is in the free post-Blitter region.)
  val spriteExtBusRangeHit = effWrite &&
    (effAddr >= U(0x0D20, 15 bits)) &&
    (effAddr <  U(0x0D40, 15 bits))
  val spriteExtBusSlot = (effAddr - U(0x0D20, 15 bits))(4 downto 0)
    .resize(spriteEval.descIdxBits)

  spriteEval.io.busSlot := Mux(spriteExtBusRangeHit, spriteExtBusSlot, spriteBusSlot8)
  spriteEval.io.busWord := Mux(spriteExtBusRangeHit, U(8, spriteEval.busWordBits bits),
                                                     spriteBusWord8)
  spriteEval.io.busData := effData
  spriteEval.io.busWr   := spriteBusRangeHit || spriteExtBusRangeHit
  // VDP-SOFT-RESET-135 #2e: drive the sprite ext-descriptor clear from the sweep.
  spriteEval.io.softClear     := softResetMemClear
  spriteEval.io.softClearAddr := softResetMemAddr

  // Pass 1 strobe at end of line — evaluator takes descCount cycles to
  // complete (well under hBlank = 160 cycles at 640×480@60).
  // Shift strobe earlier by descCount cycles so the scan completes before
  // the next line begins drawing.
  spriteEval.io.evalLine  := (fillLine + 1).resize(10)
  // Scan start shifted earlier by descCount+margin so the sequential
  // Pass-1 FSM completes before the line-fill swap. Task 45 descCount=32
  // needs ~32 cycles; hTotal-45 gives a 13-cycle completion margin before
  // the swap at hTotal-1 (476 ns at 25.2 MHz, well within hBlank=160).
  spriteEval.io.evalStart := hCounter === U(hTotal - 77, log2Up(hTotal) bits)
  io.spriteOverflow := spriteEval.io.overflowFlag

  // Sprite Pattern Memory Foundation (CyanPeak #8596): BSRAM-backed
  // pattern RAM, replicated **per slot** so each Mem has exactly one read
  // port (writeFirst SDP) and infers cleanly to a Gowin BSRAM tile.
  // A single shared 4096×4-bit Mem with NUM_SLOTS read ports could not be
  // inferred — Gowin fell back to 16,384 DFFs and exceeded the chip
  // budget. Per-slot replication uses NUM_SLOTS BSRAM tiles (each
  // 16 kbit) but stays within the `MODE0_STOPLINES.md` BSRAM ceiling of
  // 23/46 with the current 7-tile baseline.
  //
  // All NUM_SLOTS Mems share identical contents at all times — bus writes
  // are broadcast to every Mem so the host sees one logical pattern table.
  // Address layout per Mem: {patternIndex[3:0], row[3:0], col[3:0]} =
  // 12 bits → 16 unique 16×16 patterns. Slots 0/1 pre-initialise with the
  // legacy diamond / cross so any existing scenario that selects
  // patternIndex 0 or 1 sees bit-identical pixels.
  // Per-slot Mems use **readSync** (not readAsync) so Gowin can infer them
  // as BSRAM tiles. readAsync on 4096-entry Mems forced 16,384-DFF
  // distributed-RAM synthesis which exceeded the chip's 15,915-DFF budget.
  // The cost of readSync is one extra clock of latency — `pixel` now
  // arrives one cycle after `ramAddr` is presented, which is compensated
  // for by registering the slot-visible flag and slot pixel below.
  // Task 2a Checkpoint 2 Step 2: trimmed from 8 per-slot Mems to 1 shared
  // Mem. The sequential rasterizer (single read port) replaces the parallel
  // per-slot for-loop's NUM_SLOTS read ports.
  val spritePatternRams = (0 until 1).map { _ =>
    Mem(Bits(4 bits), initialContent = VdpTop.spritePatternRamInit)
  }
  spritePatternRams.head.simPublic()    // mem visible for sim probes

  // Bus interface for runtime pattern RAM writes.
  //   0x0B00 (single word): pointer write — sets `patternRamPtr[11:0]` to
  //                         data[11:0]. Use this before a streaming load.
  //   0x0A00 (single word): data write — writes data[3:0] as the next 4-bit
  //                         pixel at the current pointer, then increments
  //                         the pointer (wraps mod 4096). Stream out a
  //                         16×16 pattern with one pointer-set + 256
  //                         data writes.
  // Bus addresses relocated to 0x0D10/0x0D11 — Phase 1's original
  // 0x0A00/0x0B00 collided with V-scroll-table (0x0A00..0x0AFF) and
  // the DMA control range (0x0B00..0x0B4F). 0x0D10 is free per the
  // Blitter range ending at 0x0D0F.
  val patternRamPtrWriteHit  = effWrite && (effAddr === U(0x0D11, 15 bits))
  val patternRamDataWriteHit = effWrite && (effAddr === U(0x0D10, 15 bits))
  // Task 53 (#9419): pointer widened 12→14 to address the new
  // 16384-entry pattern RAM (64 unique 16×16 tiles, Option A).
  val patternRamPtr = Reg(UInt(14 bits)) init 0
  when(patternRamPtrWriteHit) {
    patternRamPtr := effData(13 downto 0).asUInt
  }.elsewhen(patternRamDataWriteHit) {
    patternRamPtr := patternRamPtr + 1
  }
  // Broadcast write — every per-slot Mem must observe the same write so the
  // logical pattern table stays consistent across slots.
  // VDP-SOFT-RESET-135 #2a: pattern RAM write port muxed between host streaming
  // writes and the soft-reset zero-sweep (full 16384-entry clear).
  for (mem <- spritePatternRams) {
    mem.write(
      address = Mux(softResetMemClear, softResetMemAddr, patternRamPtr),
      data    = Mux(softResetMemClear, B(0, 4 bits), effData(3 downto 0)),
      enable  = softResetMemClear || patternRamDataWriteHit
    )
  }

  val fillX = hCounter.resize(10)

  // Sprite Phase 2 — P2-1 (CyanPeak #8614): the 1-cycle latency from
  // `readSync` on the per-slot pattern Mems would otherwise shift sprite
  // output right by 1 pixel relative to the line-buffer write address.
  // Pre-advance the address-gen / hitbox `fillX` by 1 so the pixel that
  // arrives at cycle T+1 corresponds to lineBuf write position T+1
  // (rather than T+1's read of T-cycle content). Pixel-accurate vs.
  // pre-Pattern-Memory baseline.
  val fillXAhead = (fillX + 1).resize(10)

  // Per active-slot pixel resolution (Task 28 — widened 2 → 8 slots).
  // patternIndex is now 4 bits; the low bit selects pattern Mem 0 vs 1 for
  // this task. Wider pattern-Mem banks land in a future sprite-attribute
  // extension task (Task 37), so bits [3:1] are ignored here.
  val NUM_SLOTS = 8  // Task 57 Path 5A (CyanPeak #9605): match evaluator visiblePerLine=8

  // === Task 2a Checkpoint 2 — Step 1 (PM #9244): SpriteRasterizer wired in
  // parallel to the existing per-slot pipeline. The rasterizer's drain
  // output is captured for inspection (simPublic) but NOT yet consumed by
  // the lineBuf write. Step 2 (next commit) cuts over and removes the
  // parallel for-loop + tree merge below.
  // ============================================================
  val spriteRasterizer = SpriteRasterizer(
    visiblePerLine = NUM_SLOTS,
    patternSelBits = SpriteEvaluator.PatIdxWidth,   // Task 53 (#9419): 6 bits
    hActive = hActive,
    cycleBudget = 798
  )
  // Task 2c Checkpoint E: narrow Evaluator → Rasterizer link via the
  // packed active-list RAM read port. Replaces 16 wide active* Vec
  // wires (~250 wires for V=8, ~4,500 for V=32) with a 3-wire bundle
  // (addr → eval, data ← eval, count ← eval).
  spriteEval.io.activeReadAddr := spriteRasterizer.io.activeReadAddr
  spriteRasterizer.io.activeReadData := spriteEval.io.activeReadData
  spriteRasterizer.io.activeCount    := spriteEval.io.activeCountOut
  spriteRasterizer.io.firstMaskSlot  := spriteEval.io.firstMaskSlot   // Task 55
  // Pattern Mem read interface — share with spritePatternRams(0). Adds a
  // second readSync port; Gowin will handle inference (LUTRAM fallback or
  // dual-port BSRAM split). Step 2 trims spritePatternRams to a single
  // shared instance.
  spriteRasterizer.io.patternRamData := spritePatternRams(0).readSync(spriteRasterizer.io.patternRamAddr)
  // Per-line trigger: fire at hCounter=hTotal-12, just after SpriteEvaluator
  // scan completes (evalStart at hTotal-45 + descCount=32 → done at
  // hTotal-13). active* are stable from hTotal-12 onward for the line-N+2
  // (= fillLine+1) target.
  spriteRasterizer.io.lineRenderStart := hCounter === U(hTotal - 12, log2Up(hTotal) bits)
  spriteRasterizer.io.fillLineY       := fillLine.resize(10)
  // Buffer swap aligned with the existing lineBuf swap.
  spriteRasterizer.io.bufferSwap      := hCounter === U(hTotal - 1, log2Up(hTotal) bits)
  // Drain addr — for Step 1, just feed hCounter (rasterizer drain is not
  // yet consumed downstream; this exists so the drain mux/registers
  // toggle and the module elaborates cleanly).
  // drainAddr is forward-declared; assigned in the bg-only compositor block below.
  val drainAddr = UInt(log2Up(hActive) bits)
  spriteRasterizer.io.drainAddr       := drainAddr
  // Expose drain outputs for sim inspection.
  spriteRasterizer.io.drainPixel.simPublic()
  spriteRasterizer.io.drainPaletteBank.simPublic()
  spriteRasterizer.io.drainPriority.simPublic()
  spriteRasterizer.io.drainSlot0.simPublic()
  spriteRasterizer.io.cycleOverflow.simPublic()
  // Task 54 — collision write-time pulse + participating descriptor IDs.
  spriteRasterizer.io.spriteSpriteHit.simPublic()
  spriteRasterizer.io.spriteSpriteHitDescA.simPublic()
  spriteRasterizer.io.spriteSpriteHitDescB.simPublic()
  spriteRasterizer.io.drainDescIdx.simPublic()
  // ============================================================

  // === Task 2a Checkpoint 2 Step 2 cutover (PM #9244): bg-only fillPacked ===
  // The parallel per-slot for-loop and Checkpoint 1 tree merge are replaced
  // by the SpriteRasterizer (instantiated above) producing the sprite drain.
  // The lineBuf now holds bg-only content; bg + sprite are composited at
  // drain time below.

  // bg-only compositor (single-cycle combinational; no merge pipeline).
  val bgPriorityHigh = layer0PrioGated && layer0Opaque &&
                       !layer1Opaque && !layer2Opaque && !layer3Opaque
  val fillIdx    = composedBgIdx
  val fillBank   = composedBgBank
  val fillSource = composedBgSource
  val fillPrio   = bgPriorityHigh
  val fillPixel  = (fillPrio ## fillBank.asBits ## fillIdx).asBits

  val fillMeta = PixelMetadata()
  fillMeta.mathEnable     := False
  fillMeta.forcedPriority := False
  fillMeta.layerSource    := fillSource
  val fillPacked = (fillMeta.toBits ## fillPixel).asBits

  val lineBuf = LineBuffer(pixelWidth = 8 + PixelMetadata.Width, lineWidth = hActive)
  lineBuf.io.writeEnable := hCounter < hActive
  lineBuf.io.writeAddr   := hCounter.resize(log2Up(hActive))
  lineBuf.io.writeData   := fillPacked
  lineBuf.io.swap        := hCounter === hTotal - 1

  // RGB565 directcolor (CP-1b): a parallel line buffer carrying the
  // 24-bit directcolor RGB plus its active flag {active, rgb[23:0]}.
  // Wired write/read/swap identically to `lineBuf` so it inherits the
  // same double-buffering and the same fill→drain line latency — the
  // drained directcolor pixel lands in the same cycle as `paletteRgb`.
  // The fill-side value is the 565→888-expanded pixel from BitmapFetch,
  // gated by bitmapEnable so non-bitmap scenes never see directcolor.
  // (HAM6 shelved 2026-07-20 #14224: HamDecoder + hamBase/hamMode/hamCode removed and
  // bpp=0b11 reserved. The directcolor carrier below is now RGB565-only.)

  // RGB565 directcolor (CP-1b) parallel line buffer carrying the 24-bit RGB plus its
  // active flag {active, rgb[23:0]}, drained co-timed with `paletteRgb` and
  // bypass-muxed at output.
  val dcFillActive = (bitmapEnable && bitmapFetch.io.directColorActive).simPublic()
  val dcFillRgb    = bitmapFetch.io.directRgb
  val dcLineBuf = LineBuffer(pixelWidth = 25, lineWidth = hActive)
  // HAM-DECODER-171 CP-D (TopazCliff #12987 / CyanPeak #12986): shared bitmap write-
  // pipeline alignment. The fetch→select→decode path delivers `dcFillRgb` for source
  // column k some cycles AFTER hCounter==k (BitmapRowFetch readSync +1, registered
  // hCounter, etc.), so the dcLineBuf write address lagged its data → +N-column display
  // shift for RGB565 directcolor (bpp=0b10), which uses this
  // carrier. Delay the write addr/enable by `bitmapWritePipelineDelay` columns so that
  // the value computed for source k lands at dcLineBuf[k]. Compile-time param:
  //   0 = legacy (pre-fix, write addr == hCounter) — exact prior behavior.
  //   3 = measured-aligned (measured on the directcolor path).
  // Bounds stay Scala-Int constants (hActive/hTotal are Ints) so hCounter is compared
  // against literals — no width extension. writeEnable gates the underflow window when
  // hCounter < delay, so the wrapped writeAddr there is never committed.
  dcLineBuf.io.writeEnable := (hCounter >= bitmapWritePipelineDelay) && (hCounter < hActive + bitmapWritePipelineDelay)
  dcLineBuf.io.writeAddr   := (hCounter - bitmapWritePipelineDelay).resize(log2Up(hActive))
  dcLineBuf.io.writeData   := dcFillActive ## dcFillRgb
  dcLineBuf.io.swap        := hCounter === hTotal - 1

  // drainAddr was forward-declared above (for SpriteRasterizer). Assign here.
  // Present 1 cycle early for readSync alignment.
  when(hCounter === hTotal - 1) {
    drainAddr := U(0, log2Up(hActive) bits)
  }.elsewhen(hCounter < hActive - 1) {
    drainAddr := (hCounter + 1).resized
  }.otherwise {
    drainAddr := U(0, log2Up(hActive) bits)
  }
  lineBuf.io.readAddr := drainAddr
  // RGB565 directcolor: drain the parallel buffer on the same address as
  // `lineBuf` so the directcolor pixel and `paletteRgb` are co-timed.
  dcLineBuf.io.readAddr := drainAddr

  // Drain — combine bg (lineBuf) + sprite (rasterizer) at output time.
  // drainWord@T = bg pixel for hCounter@T (modulo wrap).
  // spriteRasterizer.io.drain*@T = sprite pixel for hCounter@T (same drainAddr).
  val drainWord = lineBuf.io.readData
  val drainMeta = PixelMetadata.fromBits(drainWord(8 + PixelMetadata.Width - 1 downto 8)).setName("drainMeta")
  drainMeta.mathEnable.simPublic()
  drainMeta.forcedPriority.simPublic()
  drainMeta.layerSource.simPublic()
  val drainBgIdx    = drainWord(3 downto 0).asUInt
  val drainBgBank   = drainWord(6 downto 4).asUInt
  val drainBgPrio   = drainWord(7)
  val drainBgOpaque = drainBgIdx =/= U(0, 4 bits)

  val drainSpriteIdx     = spriteRasterizer.io.drainPixel.asUInt
  val drainSpriteBank    = spriteRasterizer.io.drainPaletteBank
  val drainSpritePrio    = spriteRasterizer.io.drainPriority
  val drainSpriteIsSlot0 = spriteRasterizer.io.drainSlot0
  val drainSpriteOpaque  = drainSpriteIdx =/= U(0, 4 bits)

  // Sprite-wins predicate at drain time. Mirrors the prior `spriteWinsAt`
  // 4-tier rule (Phase 2-bis), evaluated against drained bg state.
  val drainSpriteTier     = drainSpritePrio
  val drainSpriteAbove    = drainSpriteTier(1)              // tier 2 or 3 → always above
  val drainSpriteMediumOk = drainSpriteTier === U(1, 2 bits) && (!drainBgOpaque || !drainBgPrio)
  val drainSpriteLowOk    = drainSpriteTier === U(0, 2 bits) && !drainBgOpaque
  val drainSpriteWins     = drainSpriteOpaque &&
                            (drainSpriteAbove || drainSpriteMediumOk || drainSpriteLowOk)

  val drainIdx    = Mux(drainSpriteWins, drainSpriteIdx, drainBgIdx)
  val drainBank   = Mux(drainSpriteWins, drainSpriteBank, drainBgBank)

  // Drain-time collision pulses (replaces the prior fill-time slotVisible-
  // based versions; PM #9244 (ii) preserves slot-0 specificity via the
  // rasterizer's drainSlot0 metadata bit).
  val sprite0HitPulse  = drainSpriteIsSlot0 && drainSpriteOpaque && drainBgOpaque
  val spriteBgHitPulse = drainSpriteOpaque && drainBgOpaque
  val anySlotVisible   = drainSpriteOpaque   // backward-compat alias

  // ============================================================
  // Below this point: legacy per-slot for-loop body has been removed.
  // The original block (val NUM_SLOTS=8 ... val fillPixel = ...) is
  // replaced by the SpriteRasterizer + drain compositor above.
  // ============================================================
  val paletteAddr = (drainBank @@ drainIdx).resize(log2Up(TileAttributeAssets.PaletteDepth))

  // Palette: 128-entry × 24-bit banked RGB lookup from TileAttributeAssets.
  // Bank 0 reproduces the pre-R4 16-color palette so the legacy L1 path and
  // sprite rendering are unchanged. Color/Window Hardening (#8629) makes the
  // RAM runtime-writable while preserving the legacy init content.
  //
  // Bus protocol (mirrors the sprite pattern RAM scheme at 0x0D10/0x0D11):
  //   0x0601 PALETTE_PTR  : sets paletteWritePtr[7:0] (entry × 2 + half)
  //   0x0600 PALETTE_DATA : auto-incrementing two-write entry commit
  //                          half=0 (even ptr): low 16 bits = G[7:0]:B[7:0]
  //                          half=1 (odd  ptr): low 8 bits  = R[7:0],
  //                                            commits {R,G,B} into entry
  // Two writes per entry; pointer wraps modulo 256. Hosts should sequence
  // bulk palette uploads inside vblank to avoid mid-frame visible flicker
  // (the readSync pixel path sees the new entry one pixel-clock later
  // than the second write completes — still visible on the next pixel
  // for vblank-paced uploads).
  val paletteWritePtr  = Reg(UInt(8 bits)) init 0
  val paletteWriteAcc  = Reg(Bits(16 bits)) init 0
  val palettePtrHit    = effWrite && (effAddr === U(0x0601, 15 bits))
  val paletteDataHit   = effWrite && (effAddr === U(0x0600, 15 bits))
  val paletteHalfHi    = paletteWritePtr(0)
  val paletteEntryIdx  = paletteWritePtr(7 downto 1)
  val paletteCommitNow = paletteDataHit && paletteHalfHi
  val paletteCommitData = effData(7 downto 0) ## paletteWriteAcc
  when(palettePtrHit) {
    paletteWritePtr := effData(7 downto 0).asUInt
  }.elsewhen(paletteDataHit) {
    when(!paletteHalfHi) {
      paletteWriteAcc := effData
    }
    paletteWritePtr := paletteWritePtr + 1
  }

  // (HAM6 shelved #14224: hamBase palette-mirror + soft-reset clear removed.)

  val palette = Mem(Bits(24 bits), initialContent = TileAttributeAssets.paletteInit)
  // Lane #10686: force BSRAM inference (no LUT-RAM / distributed SSRAM).
  // The readAsync→readSync conversion below plus this attribute eliminates
  // the placement-sensitive prop-delay path that drove Gowin synthesis
  // non-determinism (4 distinct bitstream sha1s from identical source,
  // mail #10683 / #10652).
  palette.addAttribute("ram_style", "block")
  palette.simPublic()

  // Task 50 v3.3 — Palette mirror registers for the first 32 entries.
  // Mirroring the most-frequently-updated / low-index palette slots in
  // registers allows a zero-latency / async-free lookup for the border
  // display mux without adding a second read port to the palette Mem.
  // Adding a second readAsync port broke Gowin BSRAM inference in v3.0,
  // causing black-screen failure on hardware.
  val paletteMirror = Vec.fill(32)(Reg(Bits(24 bits)))
  for (i <- 0 until 32) {
    paletteMirror(i).init(TileAttributeAssets.paletteInit(i))
  }
  when(paletteCommitNow && paletteEntryIdx < 32) {
    paletteMirror(paletteEntryIdx.resize(5)) := paletteCommitData
  }
  // VDP-SOFT-RESET-135 #2a: zero the low-32 palette mirror regs during the sweep
  // (overrides the host commit above — host is mid-reset, polling completion).
  when(softResetMemClear && softResetMemAddr < U(32, 14 bits)) {
    paletteMirror(softResetMemAddr.resize(5)) := B(0, 24 bits)
  }

  // VDP-SOFT-RESET-135 #2a: palette write port muxed between host commit and the
  // soft-reset zero-sweep (single write port preserved for BSRAM inference).
  val paletteSweepWr = softResetMemClear && (softResetMemAddr < U(TileAttributeAssets.PaletteDepth, 14 bits))
  palette.write(
    address = Mux(softResetMemClear,
                  softResetMemAddr.resize(log2Up(TileAttributeAssets.PaletteDepth)),
                  paletteEntryIdx.resize(log2Up(TileAttributeAssets.PaletteDepth))),
    data    = Mux(softResetMemClear, B(0, 24 bits), paletteCommitData),
    enable  = Mux(softResetMemClear, paletteSweepWr, paletteCommitNow)
  )
  val paletteRgb = palette.readSync(paletteAddr)

  // R1 Raster Trigger Unit. Pending status is used below as a visible split
  // indicator (inverts the red channel after the trigger fires), which is the
  // mandated hardware proof signature from TASK_R1_RASTER_TRIGGER_UNIT.md.
  //
  // Beam Hardening BH-5 (#8656) extends this to 4 independent triggers.
  // TR0 keeps the existing top-level IO surface for backward compat with
  // sc0 / RasterTriggerUnitSim / VdpTopSim. TR1..TR3 are bus-addressable:
  //
  //   0x0360  TRIGGER1_LINE   (10 bits)
  //   0x0361  TRIGGER1_PIXEL  (10 bits)
  //   0x0362  TRIGGER1_CTRL   (bit[0]=enable, bit[1]=pixelCmpEnable,
  //                            bit[2]=clear-pending pulse)
  //   0x0364..0x0366  TRIGGER2_*
  //   0x0368..0x036A  TRIGGER3_*
  //   (offset 3 in each block reserved)
  //
  // All four trigger pulses are OR'd into evRasterMatch so the host sees
  // a single sticky bit (RASTER_MATCH) regardless of which trigger fired.
  // Per-trigger granularity is observable via rasterPendingMask (4 bits)
  // — wired to the existing top-level rasterTriggerPending IO output as
  // its OR for backward compat, and exposed individually as a 4-bit
  // bundle for downstream consumers.
  val rasterTrigger = RasterTriggerUnit()
  rasterTrigger.io.vCounter       := vCounter.resize(10)
  rasterTrigger.io.hCounter       := hCounter.resize(10)
  rasterTrigger.io.triggerLine    := io.rasterTriggerLine
  rasterTrigger.io.triggerPixel   := io.rasterTriggerPixel
  rasterTrigger.io.pixelCmpEnable := io.rasterTriggerPxEnable
  rasterTrigger.io.enable         := io.rasterTriggerEnable
  rasterTrigger.io.clear          := io.rasterTriggerClear
  io.rasterTriggerPulse           := rasterTrigger.io.triggerPulse

  // BH-5 extras (TR1..TR3) live behind `withExtraRasterTriggers`. Default
  // build (`false`) drops the per-trigger Regs, address-decode block, and
  // three additional RasterTriggerUnit instances. TR0 is unaffected.
  // The downstream-visible signals keep their shape so the IO contract
  // (`io.rasterTriggerPending`) and the `rasterPendingMask` simPublic tap
  // stay bit-stable for sims that don't toggle the extras.
  val extraTrigPending = Vec.fill(3)(Bool())
  val extraTrigPulse   = Vec.fill(3)(Bool())

  if (withExtraRasterTriggers) {
    // Per-trigger control register banks for TR1..TR3. Direct (non-shadow)
    // commits — the trigger compare is purely combinational on the
    // registers, so a host write that lands mid-frame just changes the
    // next-match condition without corrupting prior state.
    val tr1LineReg     = Reg(UInt(10 bits)) init 0
    val tr1PixelReg    = Reg(UInt(10 bits)) init 0
    val tr1CtrlReg     = Reg(Bits(3 bits))  init 0
    val tr2LineReg     = Reg(UInt(10 bits)) init 0
    val tr2PixelReg    = Reg(UInt(10 bits)) init 0
    val tr2CtrlReg     = Reg(Bits(3 bits))  init 0
    val tr3LineReg     = Reg(UInt(10 bits)) init 0
    val tr3PixelReg    = Reg(UInt(10 bits)) init 0
    val tr3CtrlReg     = Reg(Bits(3 bits))  init 0
    // Clear bits are pulse-style: they assert for one cycle when the host
    // writes a `1` to bit[2]. The Reg holds the rest of CTRL persistently;
    // the clear bit auto-deasserts the next cycle.
    val tr1Clear       = Bool()
    val tr2Clear       = Bool()
    val tr3Clear       = Bool()
    tr1Clear := False
    tr2Clear := False
    tr3Clear := False
    when(effWrite && effAddr === U(0x0360, 15 bits)) { tr1LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0361, 15 bits)) { tr1PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0362, 15 bits)) {
      tr1CtrlReg := effData(2 downto 0)
      tr1Clear   := effData(2)
    }
    when(effWrite && effAddr === U(0x0364, 15 bits)) { tr2LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0365, 15 bits)) { tr2PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0366, 15 bits)) {
      tr2CtrlReg := effData(2 downto 0)
      tr2Clear   := effData(2)
    }
    when(effWrite && effAddr === U(0x0368, 15 bits)) { tr3LineReg  := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x0369, 15 bits)) { tr3PixelReg := effData(9 downto 0).asUInt }
    when(effWrite && effAddr === U(0x036A, 15 bits)) {
      tr3CtrlReg := effData(2 downto 0)
      tr3Clear   := effData(2)
    }

    val rasterTrigger1 = RasterTriggerUnit()
    rasterTrigger1.io.vCounter       := vCounter.resize(10)
    rasterTrigger1.io.hCounter       := hCounter.resize(10)
    rasterTrigger1.io.triggerLine    := tr1LineReg
    rasterTrigger1.io.triggerPixel   := tr1PixelReg
    rasterTrigger1.io.pixelCmpEnable := tr1CtrlReg(1)
    rasterTrigger1.io.enable         := tr1CtrlReg(0)
    rasterTrigger1.io.clear          := tr1Clear

    val rasterTrigger2 = RasterTriggerUnit()
    rasterTrigger2.io.vCounter       := vCounter.resize(10)
    rasterTrigger2.io.hCounter       := hCounter.resize(10)
    rasterTrigger2.io.triggerLine    := tr2LineReg
    rasterTrigger2.io.triggerPixel   := tr2PixelReg
    rasterTrigger2.io.pixelCmpEnable := tr2CtrlReg(1)
    rasterTrigger2.io.enable         := tr2CtrlReg(0)
    rasterTrigger2.io.clear          := tr2Clear

    val rasterTrigger3 = RasterTriggerUnit()
    rasterTrigger3.io.vCounter       := vCounter.resize(10)
    rasterTrigger3.io.hCounter       := hCounter.resize(10)
    rasterTrigger3.io.triggerLine    := tr3LineReg
    rasterTrigger3.io.triggerPixel   := tr3PixelReg
    rasterTrigger3.io.pixelCmpEnable := tr3CtrlReg(1)
    rasterTrigger3.io.enable         := tr3CtrlReg(0)
    rasterTrigger3.io.clear          := tr3Clear

    extraTrigPending(0) := rasterTrigger1.io.pending
    extraTrigPending(1) := rasterTrigger2.io.pending
    extraTrigPending(2) := rasterTrigger3.io.pending
    extraTrigPulse(0)   := rasterTrigger1.io.triggerPulse
    extraTrigPulse(1)   := rasterTrigger2.io.triggerPulse
    extraTrigPulse(2)   := rasterTrigger3.io.triggerPulse
  } else {
    extraTrigPending.foreach(_ := False)
    extraTrigPulse.foreach(_ := False)
  }

  // Aggregate pending across all four — top-level pending output is OR
  // of the four for backward compat with the existing IO surface. When
  // the gate is off, bits[3..1] are tied False so the 4-bit shape and
  // simPublic tap stay stable for downstream consumers.
  val rasterPendingMask = (extraTrigPending(2) ##
                           extraTrigPending(1) ##
                           extraTrigPending(0) ##
                           rasterTrigger.io.pending).asBits
  rasterPendingMask.simPublic()
  io.rasterTriggerPending := rasterPendingMask.orR

  // -------------------------------------------------------------------
  // Task 35 — Host-Facing IRQ + Sticky Status Register Bank.
  //
  // Address map (within the 0x0320..0x032F reserved block per
  // MODE0_REGISTER_BUS_SPEC.md §3):
  //   0x0320  STATUS_STICKY  — read via QSPI sel=5; writes write-1-to-clear
  //   0x0321  STATUS_ENABLE  — IRQ mask (1 = bit contributes to irq)
  //
  // Sticky bit mapping (low byte, upper bits reserved for future events):
  //   bit 0 : RASTER_MATCH         — rasterTriggerPulse rising edge
  //   bit 1 : SPRITE_OVERFLOW      — spriteEval.overflowFlag pulse
  //   bit 2 : QSPI_READY           — QSPI cmd_valid pulse (command accepted)
  //   bit 3 : QSPI_ERROR           — QspiDecoder.last_error non-zero (level)
  //   bit 11: MODE_SELECT_CHANGED  — V=0 commit of MODE_SELECT @ 0x0313 (Task 1 #9154)
  //
  // Semantics:
  //   - Sticky bits SET on event pulse, PERSIST until write-1-to-clear.
  //   - QSPI_ERROR is level-triggered; sticky bit 3 follows the latched
  //     error state until host clears it AND the upstream error condition
  //     has also cleared (otherwise the bit re-asserts on the next cycle).
  //   - irq = (sticky & enable).orR — asserted while any enabled sticky
  //     bit is set; deasserts when host clears or disables the bit.
  //   - Safe-boundary commit: STATUS_ENABLE writes commit at hCounter===0
  //     per spec §4.1. Sticky bit sets propagate immediately (events are
  //     cycle-accurate and would be lost by a safe-boundary shadow).
  //   - Write-1-to-clear semantics for STATUS_STICKY: for each bit of the
  //     write data that is 1, the corresponding sticky bit clears. Bits
  //     written as 0 are preserved.
  // -------------------------------------------------------------------
  val statusStickyReg  = Reg(Bits(16 bits)) init 0
  val statusEnableReg  = Reg(Bits(16 bits)) init 0
  val statusEnablePend    = Reg(Bits(16 bits)) init 0
  val statusEnablePendHit = Reg(Bool()) init False

  // Event sources (low byte).
  // BH-5: any of the four triggers firing sets the sticky RASTER_MATCH bit.
  // When `withExtraRasterTriggers=false`, `extraTrigPulse` is tied False so
  // this collapses to TR0-only.
  val evRasterMatch    = rasterTrigger.io.triggerPulse ||
                         extraTrigPulse(0) ||
                         extraTrigPulse(1) ||
                         extraTrigPulse(2)
  val evSpriteOverflow = spriteEval.io.overflowFlag
  val evQspiReady      = io.statusEvQspiReady
  val evQspiError      = io.statusEvQspiError
  // Task 29 — extend event bus with sprite collision bits:
  //   bit 4: SPRITE_0_HIT   (sprite 0 non-transparent over non-transparent BG)
  //   bit 5: SPRITE_BG_HIT  (any sprite non-transparent over non-transparent BG)
  // Task 47 — DMA_DONE at bit 8 of the sticky word.
  // Task 49 — BLIT_DONE at bit 9 of the sticky word. Bit 10 (BLIT_BUSY) is
  // a live read-only signal (blitterEngine.io.busy) and does not flow into
  // the sticky pipeline; hosts that need the live state read it via a
  // future status-word read implementation.
  // Task 1 (#9154) — V=0 frame-atomic commit pulse for MODE_SELECT.
  // Fires for one cycle at the start of vsync (the unambiguous frame
  // boundary), per MODE_SELECT_ARCHITECTURE.md v1.1 §4.2 commit-boundary
  // rule. NOT the per-line hCounter===0 gate other safe-boundary regs
  // use, because mode switch must be frame-atomic to avoid split-frame
  // adapter-quiescence races.
  val modeCommitPulse = (vCounter === vSyncStart) && (hCounter === U(0, log2Up(hTotal) bits))
  when(modeCommitPulse && modeSelectPendHit) {
    modeSelectReg      := modeSelectPend
    modeSelectFlagsReg := modeSelectFlagsPend
    modeSelectPendHit  := False
    // §4.6.4 — Copper auto-disable on mode switch: stop the old program
    // immediately so the new mode starts with a clean copper state. The
    // host must upload a new copper program and re-enable.
    copperCtrlReg      := B(0, 1 bit)
    copperCtrlPendHit  := False
    // §4.6.5 — Optional MODE_FLAGS[0] auto-reset: clear LAYER_ENABLE so
    // the new mode starts with a clean visual slate.
    when(modeSelectFlagsPend(0)) {
      layerEnableReg    := B(0, layerEnableReg.getWidth bits)
      layerEnablePendHit := False
    }
  }
  // §4.2 — MODE_SELECT_CHANGED sticky event: one-cycle pulse at the V=0
  // commit if a pending mode write actually committed. Lets the host
  // poll for commit completion before issuing platform-specific traffic.
  // Sticky bit 11 (next free slot above blitterEngine.io.done at bit 9).
  val evModeSelectChanged = modeCommitPulse && modeSelectPendHit

  // Task 54 — SPRITE_SPRITE_HIT rollup pulse at bit 6 of STATUS_STICKY.
  // OR-reduction of the rasterizer's per-cycle collision pulse: any
  // sprite-sprite overlap pixel during the line sets the sticky bit;
  // host clears via W1C @ 0x0320 like the other sticky events.
  val evSpriteSpriteHit = spriteRasterizer.io.spriteSpriteHit

  val evBus = (B(0, 4 bits) ## evModeSelectChanged ## B(0, 1 bit) ##
               blitterEngine.io.done ## dmaEngine.io.done ##
               B(0, 1 bit) ## evSpriteSpriteHit ##
               spriteBgHitPulse ## sprite0HitPulse ##
               evQspiError ## evQspiReady ## evSpriteOverflow ## evRasterMatch).asBits

  // STATUS_ENABLE write (safe-boundary commit).
  when(effWrite && effAddr === U(0x0321, 15 bits)) {
    statusEnablePend    := effData
    statusEnablePendHit := True
  }

  // STATUS_STICKY write = write-1-to-clear. No shadow needed; clear is
  // an immediate action and cannot cause mid-line artifacts (it only
  // deasserts irq, it doesn't change visible pixel state).
  val statusClearMask = Bits(16 bits)
  statusClearMask := B(0, 16 bits)
  when(effWrite && effAddr === U(0x0320, 15 bits)) {
    statusClearMask := effData
  }

  // Sticky update: clear the host-requested bits FIRST, then set on any event
  // this cycle. If an event AND a clear both target the same bit in the same
  // cycle, the event WINS (new state takes precedence over the stale clear) —
  // matching the documented contract. (Bug 5, external review #13008: the prior
  // `(sticky | ev) & ~clear` form let clear win, dropping a same-cycle event.)
  // QSPI_ERROR uses the level directly so it re-asserts until the source clears.
  statusStickyReg := (statusStickyReg & (~statusClearMask)) | evBus

  // Safe-boundary commit of enable mask at hCounter===0.
  when(hCounter === U(0, log2Up(hTotal) bits)) {
    when(statusEnablePendHit) {
      statusEnableReg     := statusEnablePend
      statusEnablePendHit := False
    }
  }

  io.statusSticky := statusStickyReg
  io.irq          := (statusStickyReg & statusEnableReg).orR

  // -------------------------------------------------------------------
  // Task 54 — Sprite-Sprite Collision per-Descriptor Mask Register.
  //
  // Address map (within the 0x0320..0x032F STATUS block):
  //   0x0322  SPRITE_COLL_MASK — 8-bit per-descriptor sticky mask;
  //                              write-1-to-clear, read via io.spriteCollMask.
  //
  // Set semantics:
  //   - On every cycle the rasterizer asserts `spriteSpriteHit`, both
  //     `spriteSpriteHitDescA` (incoming sprite) and
  //     `spriteSpriteHitDescB` (existing sprite) bits are set in the
  //     mask. Reverse-iter draw order makes this OR-accumulation
  //     produce the canonical "every participating sprite has its bit
  //     set" semantic.
  //
  // Clear semantics:
  //   - Same write-1-to-clear pattern as STATUS_STICKY @ 0x0320: bits
  //     written as 1 clear; bits written as 0 are preserved. Sets and
  //     clears in the same cycle: set wins (event takes precedence).
  //
  // Rollup into STATUS_STICKY bit 6 (SPRITE_SPRITE_HIT) is wired below
  // by adding `spriteSpriteHit` into the evBus packing.
  // -------------------------------------------------------------------
  // Held at 8 bits per BronzeGate #10363 — NOT widened to descCount=32.
  // Hit-descriptor indices ≥8 alias into the low 3 bits (see io.spriteCollMask
  // comment). Widening is parked until a concrete product need is shown.
  val SpriteCollWidth = 8
  val spriteCollMaskReg = Reg(Bits(SpriteCollWidth bits)) init 0

  val spriteSpriteHit       = spriteRasterizer.io.spriteSpriteHit
  val spriteSpriteHitDescA  = spriteRasterizer.io.spriteSpriteHitDescA
  val spriteSpriteHitDescB  = spriteRasterizer.io.spriteSpriteHitDescB

  val collSetA = (B(1, SpriteCollWidth bits) |<<
                  spriteSpriteHitDescA.resize(log2Up(SpriteCollWidth)))
  val collSetB = (B(1, SpriteCollWidth bits) |<<
                  spriteSpriteHitDescB.resize(log2Up(SpriteCollWidth)))
  val collSetMask = Mux(spriteSpriteHit,
                        (collSetA | collSetB).resize(SpriteCollWidth),
                        B(0, SpriteCollWidth bits))

  val collClearMask = Bits(SpriteCollWidth bits)
  collClearMask := B(0, SpriteCollWidth bits)
  when(effWrite && effAddr === U(0x0322, 15 bits)) {
    collClearMask := effData(SpriteCollWidth - 1 downto 0)
  }

  // Bug 5 (external review #13008): clear FIRST then set, so a same-cycle
  // set wins (event takes precedence) — matches the documented contract above.
  spriteCollMaskReg := (spriteCollMaskReg & (~collClearMask)) | collSetMask
  io.spriteCollMask := spriteCollMaskReg

  // ===== VDP-SOFT-RESET-135 #4: core register reset (Stage 3 of the sequence) =====
  // Option B (surgical) per TopazCliff #12608 / CyanPeak #12609: while
  // `softResetCoreActive`, force every host-writable config register back to its
  // SpinalHDL `init` and clear its pend/commit hit so a mid-flight (uncommitted)
  // host write cannot land after the reset. Placed after ALL normal register
  // commit logic so it wins on the reset cycle (last-assignment-wins). The
  // soft-reset controller regs + i80/0x0310 status path are deliberately NOT
  // here — they stay LIVE to run the reset and keep the host poll alive.
  // Internal pipeline/counter regs (hCounter/vCounter/fillLine, copper pc, etc.)
  // are not reset; they re-settle within a frame (POR-equivalent; the sim proves
  // no visible artifact). Also clears STATUS_STICKY / STATUS_ENABLE (IRQ mask) /
  // sprite-collision mask so a stale flag or pending IRQ can't fire post-reset
  // (CyanPeak #12609).
  when(softResetCoreActive) {
    copperCtrlReg     := B(0, 1 bits);  copperCtrlPendHit     := False
    layerEnableReg    := B"00000";       layerEnablePendHit    := False
    tileDecodeModeReg := B(0, 2 bits);  tileDecodeModePendHit := False
    attributeModeReg  := B(0, 1 bits);  attributeModePendHit  := False
    backdropIndexReg  := U(0, 7 bits);  backdropIndexPendHit  := False
    scaleCtrlReg      := B(scaleCtrlInit, 8 bits);   scaleCtrlPendHit   := False
    logicWidthReg     := U(logicWidthInit, 11 bits); logicWidthPendHit  := False
    logicHeightReg    := U(logicHeightInit, 11 bits);logicHeightPendHit := False
    innerBorderLReg   := U(0, 10 bits); innerBorderLPendHit := False
    innerBorderRReg   := U(0, 10 bits); innerBorderRPendHit := False
    innerBorderTReg   := U(0, 10 bits); innerBorderTPendHit := False
    innerBorderBReg   := U(0, 10 bits); innerBorderBPendHit := False
    modeSelectReg     := U(0, 4 bits);  modeSelectFlagsReg := B(0, 8 bits); modeSelectPendHit := False
    winX0Reg := U(0, 10 bits); winX0PendHit := False
    winX1Reg := U(0, 10 bits); winX1PendHit := False
    winY0Reg := U(0, 10 bits); winY0PendHit := False
    winY1Reg := U(0, 10 bits); winY1PendHit := False
    colorMathReg := B(0, 16 bits); colorMathPendHit := False
    win2X0Reg := U(0, 10 bits); win2X0PendHit := False
    win2X1Reg := U(0, 10 bits); win2X1PendHit := False
    win2Y0Reg := U(0, 10 bits); win2Y0PendHit := False
    win2Y1Reg := U(0, 10 bits); win2Y1PendHit := False
    win2CtrlReg := B(0, 16 bits); win2CtrlPendHit := False
    winCombReg  := B(0, 16 bits); winCombPendHit  := False
    layerMaskReg := B(0, 16 bits); layerMaskPendHit := False
    borderX0Reg := U(0, 10 bits); borderX0PendHit := False
    borderX1Reg := U(0, 10 bits); borderX1PendHit := False
    borderY0Reg := U(0, 10 bits); borderY0PendHit := False
    borderY1Reg := U(0, 10 bits); borderY1PendHit := False
    borderCtrlReg := B(borderCtrlInit, 16 bits); borderCtrlPendHit := False
    affineAReg := B(0, 16 bits); affineAPendHit := False
    affineBReg := B(0, 16 bits); affineBPendHit := False
    affineCReg := B(0, 16 bits); affineCPendHit := False
    affineDReg := B(0, 16 bits); affineDPendHit := False
    affineXReg := B(0, 16 bits); affineXPendHit := False
    affineYReg := B(0, 16 bits); affineYPendHit := False
    affineCtrlReg := B(0, 16 bits); affineCtrlPendHit := False
    bitmapCtrlReg := B(0, 16 bits); bitmapCtrlPendHit := False
    bitmapBaseLoReg := U(0x3000, 16 bits); bitmapBaseLoPendHit := False
    bitmapBaseHiReg := U(0, 7 bits);       bitmapBaseHiPendHit := False
    attrBaseLoReg   := U(0x4000, 16 bits); attrBaseLoPendHit   := False
    attrBaseHiReg   := U(0, 7 bits);       attrBaseHiPendHit   := False
    bitmapStrideReg := U(512, 16 bits);    bitmapStridePendHit := False
    attrStrideReg   := U(512, 16 bits);    attrStridePendHit   := False
    bitmapHeightReg := U(240, 10 bits);    bitmapHeightPendHit := False
    // I80-FRAME-ATOMIC-SWAP-145: clear staged base double-buffer + swap flags.
    bitmapBaseSwapLo := U(0x3000, 16 bits)
    bitmapBaseSwapHi := U(0, 7 bits)
    attrBaseSwapLo   := U(0x4000, 16 bits)
    attrBaseSwapHi   := U(0, 7 bits)
    swapRequest      := False
    swapCommitted    := False
    planarCtrlReg := B(0, 16 bits)
    for (p <- 0 until PLANE_COUNT) planeBaseAddrReg(p) := U(0, 23 bits)
    // NOTE: extra raster triggers TR1-3 are conditionally instantiated
    // (withExtraRasterTriggers, off in the active i80/HW builds) and scoped inside
    // their own block — not resettable from here. If ever enabled, add their reset
    // inside that block keyed off softResetCoreActive.
    // CyanPeak #12609: clear sticky status / IRQ mask / collision mask so no
    // stale flag or pending interrupt survives the reset.
    statusStickyReg   := B(0, 16 bits)
    statusEnableReg   := B(0, 16 bits); statusEnablePendHit := False
    spriteCollMaskReg := B(0, spriteCollMaskReg.getWidth bits)
    // #3/#4 registers → init (transparency keys 0, planar width 320).
    l0TransKeyReg := B(0, 4 bits); l1TransKeyReg := B(0, 4 bits)
    l2TransKeyReg := B(0, 4 bits); l3TransKeyReg := B(0, 4 bits)
    planarWidthReg := U(320, 10 bits)
  }

  // R6 Task 20: post-palette color-math + window stage. Mux on `paletteRgb`
  // controlled by the window comparator and the colorMath op/constant fields.
  val windowUnit = WindowUnit()
  windowUnit.io.hCounter := hCounter.resize(10)
  windowUnit.io.vCounter := vCounter.resize(10)
  windowUnit.io.winX0    := winX0Reg
  windowUnit.io.winX1    := winX1Reg
  windowUnit.io.winY0    := winY0Reg
  windowUnit.io.winY1    := winY1Reg
  windowUnit.io.invert   := colorMathReg(13)

  // CW-5: second window comparator + combination logic. Defaults reduce
  // to legacy single-window behavior (combMode=0 → use window1 effect).
  val windowUnit2 = WindowUnit()
  windowUnit2.io.hCounter := hCounter.resize(10)
  windowUnit2.io.vCounter := vCounter.resize(10)
  windowUnit2.io.winX0    := win2X0Reg
  windowUnit2.io.winX1    := win2X1Reg
  windowUnit2.io.winY0    := win2Y0Reg
  windowUnit2.io.winY1    := win2Y1Reg
  windowUnit2.io.invert   := win2CtrlReg(0)

  val combMode = winCombReg(2 downto 0).asUInt
  val effect1  = windowUnit.io.effect
  val effect2  = windowUnit2.io.effect
  val combinedWindowEffect = combMode.mux(
    U(0, 3 bits) -> effect1,
    U(1, 3 bits) -> (effect1 && effect2),
    U(2, 3 bits) -> (effect1 || effect2),
    U(3, 3 bits) -> (effect1 ^ effect2),
    U(4, 3 bits) -> !(effect1 && effect2),
    U(5, 3 bits) -> !(effect1 || effect2),
    default      -> effect1
  )

  // CW-6: per-layer window mask. drainMeta.layerSource carries the
  // winning source ID (BG0..BG3=0..3, Sprite=4) selected at compose
  // time; if that layer's mask bit is set AND the combined window
  // effect is active here, the pixel is forced to black before
  // ColorMath. Default layerMaskReg=0 means no masking.
  val layerMaskBit    = layerMaskReg(drainMeta.layerSource(2 downto 0))
  val layerMaskActive = layerMaskBit && combinedWindowEffect

  // RGB565 directcolor bypass mux (CP-1b). The drained directcolor pixel
  // is co-timed with `paletteRgb`. When directcolor is active for this
  // pixel AND no sprite wins here, the 24-bit directcolor RGB replaces
  // the palette lookup — the bitmap layer is the background, sprites
  // still composite on top via the unchanged `drainSpriteWins` rule
  // (in directcolor mode the indexed bg reads as idx 0 / transparent,
  // so opaque sprites win naturally). Indexed modes: dcActive=0 → no-op.
  val dcDrained       = dcLineBuf.io.readData
  val dcActiveDrained = dcDrained(24).simPublic()
  val dcRgbDrained    = dcDrained(23 downto 0).simPublic()
  // Lane #10686 palette readSync compensation. paletteRgb is now +1 cycle
  // (readSync semantics). Delay every other input to this mux by 1 cycle
  // so all four inputs represent the same drain cycle. Pre-#10686 these
  // were combinationally co-timed with the old readAsync paletteRgb.
  // simPublic: these registered (2-cycle) outputs are co-timed with io.x/io.y and the
  // bypass mux below — co-sims MUST sample these, NOT the 1-cycle dcActiveDrained/
  // dcRgbDrained (which lead io.x by 1 col → false -1 column shift). CyanPeak #13009.
  val dcActiveDrainedR  = RegNext(dcActiveDrained)  init False        ; dcActiveDrainedR.simPublic()
  val dcRgbDrainedR     = RegNext(dcRgbDrained)     init B(0, 24 bits) ; dcRgbDrainedR.simPublic()
  val drainSpriteWinsR  = RegNext(drainSpriteWins)  init False
  val layerMaskActiveR  = RegNext(layerMaskActive)  init False
  val bgOrDirectRgb   = Mux(dcActiveDrainedR && !drainSpriteWinsR, dcRgbDrainedR, paletteRgb).simPublic()
  val maskedRgb       = Mux(layerMaskActiveR, B(0, 24 bits), bgOrDirectRgb)

  // CW Option 1 pipeline (CyanPeak #8649): register the new dual-window
  // / layer-mask combinational outputs before they enter ColorMath, so
  // the post-palette stage's combinational depth no longer pushes legacy
  // BG-layer paths over the line. Mirrors the P2-3a `slotPaletteBank`
  // pipeline that recovered Phase 2 timing. The 1-cycle latency at
  // ColorMath's input is matched by a 1-cycle shift on the display-side
  // sync/de/primed/raster-pending signals so the displayed pixel and
  // its sync envelope stay aligned.
  val combinedWindowEffectR = RegNext(combinedWindowEffect) init False
  val maskedRgbR            = RegNext(maskedRgb)            init B(0, 24 bits)
  val drainMetaMathEnR      = RegNext(drainMeta.mathEnable) init False
  val colorMathOpR          = RegNext(colorMathReg(15 downto 14).asUInt) init U(0, 2 bits)
  val colorMathConstR       = RegNext(colorMathReg(7 downto 0).asUInt)   init U(0, 8 bits)

  val colorMath = ColorMath()
  colorMath.io.rgbIn    := maskedRgbR
  colorMath.io.op       := colorMathOpR
  colorMath.io.constant := colorMathConstR
  // CW-3: per-pixel mathEnable metadata OR'd with the (possibly combined)
  // window effect, so individual line-buffer pixels can opt into color
  // math independent of the rectangular windows. Defaults all-zero
  // (line buffer drives False, combMode=0), so existing scenes are
  // unaffected.
  colorMath.io.enable   := combinedWindowEffectR || drainMetaMathEnR
  val mathRgb = colorMath.io.rgbOut

  // Task 50 v3 Slice 2 — visible-border window display mux.
  //
  // When BORDER_CTRL[0] is set, pixels OUTSIDE the rectangle
  // [borderX0, borderX1) × [borderY0, borderY1) are replaced by a
  // dedicated palette lookup. The border palette index is BORDER_CTRL
  // bits[12:8]; canonical assignment is slot 24 (written by the ZX
  // Spectrum adapter's border emitter). The replacement happens at
  // the same 1-cycle pipeline depth as the rest of the display
  // outputs (mathRgb / hsyncR / deR) — combinatorial border-active
  // and palette read are computed at cycle T from current
  // h/v/borderReg state, then registered to align with mathRgb at
  // cycle T+1.
  val borderEnable = borderCtrlReg(0)
  val borderIdx    = borderCtrlReg(12 downto 8).asUInt
  val innerBorderEnable = borderCtrlReg(1)
  // PixelRepeatScaler instantiation (lane #10590-reland, PM #10701).
  // Re-landed on top of the palette readSync fix (main @ 661907d) which
  // removed the Gowin placement-sensitivity that caused the original
  // intermittent black-HDMI. lineBuf write OOB-guard added per
  // BronzeGate #10697. POR scaleCtrlReg=0 yields 1x bypass (scaleX=1,
  // scaleY=1, autoCenter=0). Counters reset on the first cycle of
  // hsync/vsync (when hCounter/vCounter enter their respective sync
  // regions); we detect those edges combinationally here.
  val hsyncActive    = hCounter >= hSyncStart && hCounter < hSyncEnd
  val vsyncActive    = vCounter >= vSyncStart && vCounter < vSyncEnd
  val hsyncActivePrv = RegNext(hsyncActive) init False
  val vsyncActivePrv = RegNext(vsyncActive) init False
  val hsyncEdge      = hsyncActive && !hsyncActivePrv
  val vsyncEdge      = vsyncActive && !vsyncActivePrv
  val scaler = PixelRepeatScaler()
  scaler.io.hCounter     := hCounter.resize(10)
  scaler.io.vCounter     := vCounter.resize(10)
  scaler.io.hsyncRising  := hsyncEdge
  scaler.io.vsyncRising  := vsyncEdge
  scaler.io.hActive      := U(hActive, 11 bits)
  scaler.io.vActive      := U(vActive, 11 bits)
  scaler.io.scaleXReg    := scaleCtrlReg(2 downto 0).asUInt
  scaler.io.scaleYReg    := scaleCtrlReg(6 downto 4).asUInt
  scaler.io.autoCenter   := scaleCtrlReg(7)
  scaler.io.logicWidth   := logicWidthReg
  scaler.io.logicHeight  := logicHeightReg

  // Auto-center override of the host BORDER_X/Y0/1. Host BORDER_CTRL[12:8]
  // still picks the bezel palette slot. SCALE_CTRL[7] arms the override.
  //
  // INNER BORDER mode (BORDER_CTRL[1]): when set, the physical border
  // rectangle is auto-computed from INNER_BORDER_L/R/T/B (in logical pixels)
  // plus the scaler's effective scale factors. This lets the host set a
  // logical canvas resolution and inner border thickness without doing the
  // multiply-by-scale math in firmware. Inner border uses the same palette
  // index as the outer border (BORDER_CTRL[12:8]).
  val acActive    = scaleCtrlReg(7)
  val ibScaleX    = scaler.io.scaleXEffOut
  val ibScaleY    = scaler.io.scaleYEffOut
  val ibOffX      = scaler.io.acBorderX0
  val ibOffY      = scaler.io.acBorderY0

  // Defensive clamp: inner border thickness cannot exceed the logical canvas
  // on its own axis, and L+R (or T+B) cannot exceed the dimension. This
  // prevents silent unsigned-wrap misbehavior when the host writes out-of-range
  // values (BrightForge #11915 finding 1 / BronzeGate #11916 finding 1).
  val ibL = Mux(innerBorderLReg.resize(11) > logicWidthReg,  logicWidthReg,  innerBorderLReg.resize(11))
  val ibR = Mux(innerBorderRReg.resize(11) > logicWidthReg,  logicWidthReg,  innerBorderRReg.resize(11))
  val ibT = Mux(innerBorderTReg.resize(11) > logicHeightReg, logicHeightReg, innerBorderTReg.resize(11))
  val ibB = Mux(innerBorderBReg.resize(11) > logicHeightReg, logicHeightReg, innerBorderBReg.resize(11))
  val ibRSafe = Mux((ibL + ibR) > logicWidthReg,  logicWidthReg  - ibL, ibR)
  val ibBSafe = Mux((ibT + ibB) > logicHeightReg, logicHeightReg - ibT, ibB)

  val effBorderX0 = Mux(innerBorderEnable,
                        (ibOffX + (ibL * ibScaleX).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderX0, borderX0Reg)).simPublic()
  val effBorderX1 = Mux(innerBorderEnable,
                        (ibOffX + ((logicWidthReg  - ibRSafe) * ibScaleX).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderX1, borderX1Reg)).simPublic()
  val effBorderY0 = Mux(innerBorderEnable,
                        (ibOffY + (ibT * ibScaleY).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderY0, borderY0Reg)).simPublic()
  val effBorderY1 = Mux(innerBorderEnable,
                        (ibOffY + ((logicHeightReg - ibBSafe) * ibScaleY).resize(10)).resize(10),
                        Mux(acActive, scaler.io.acBorderY1, borderY1Reg)).simPublic()
  val effBorderEnable = borderEnable || acActive || innerBorderEnable
  val insideBorder = (hCounter >= effBorderX0.resize(log2Up(hTotal))) &&
                     (hCounter <  effBorderX1.resize(log2Up(hTotal))) &&
                     (vCounter >= effBorderY0.resize(log2Up(vTotal))) &&
                     (vCounter <  effBorderY1.resize(log2Up(vTotal)))
  val borderActive = effBorderEnable && !insideBorder
  // Task 50 v3.3: Use a combinational lookup from the palette mirror
  // registers to fetch the border color. This removes the second async
  // read port on the palette Mem which broke BSRAM inference in v3.0.
  val borderRgb = paletteMirror(borderIdx)
  val borderActiveR = RegNext(borderActive) init False
  val borderRgbR    = RegNext(borderRgb)    init B(0, 24 bits)

  // Display-side sync / DE / gating signals first stage (+1) — tracks the
  // ColorMath input pipeline. hsync/vsync are active-low so reset value
  // is True (inactive). The scaler re-land below adds a second RegNext
  // (RR) to match the scaler's +1 output latency; total display depth
  // becomes +2. Lane #10686's palette readSync is absorbed inside the
  // post-palette stage via the dcSide RegNexts at the bgOrDirectRgb
  // mux input, so it does NOT contribute to display-side depth here.
  val hsyncR         = RegNext(!(hCounter >= hSyncStart && hCounter < hSyncEnd)) init True
  val vsyncR         = RegNext(!(vCounter >= vSyncStart && vCounter < vSyncEnd)) init True
  val deR            = RegNext(activeVideo)           init False
  val primedR        = RegNext(primed)                init False
  val rasterPendingR = RegNext(rasterTrigger.io.pending) init False

  // Border bypasses ColorMath — when borderActiveR is set, displayRgb
  // is the border palette entry directly; otherwise the post-ColorMath
  // pixel.
  val displayRgb = Mux(borderActiveR, borderRgbR, mathRgb)

  // Wire displayRgb into the scaler. Scaler is +1 latency uniformly
  // across bypass (1x) and scaled paths — outRgb is registered.
  scaler.io.inRgb      := displayRgb
  val displayRgbScaled = scaler.io.outRgb

  // Display-side second-stage RegNext (+2 total) to align with the
  // scaler's +1 output latency. Matches the dc1fba8-pre-disconnect
  // depth, minus the third stage that was overcounted there (the
  // third stage was matched to a post-palette compensation that the
  // dcSide RegNexts now absorb upstream of maskedRgbR).
  val hsyncRR         = RegNext(hsyncR)          init True
  val vsyncRR         = RegNext(vsyncR)          init True
  val deRR            = RegNext(deR)             init False
  val primedRR        = RegNext(primedR)         init False
  val rasterPendingRR = RegNext(rasterPendingR)  init False

  io.hsync := hsyncRR
  io.vsync := vsyncRR
  io.de    := deRR
  io.red   := B(0, 8 bits)
  io.green := B(0, 8 bits)
  io.blue  := B(0, 8 bits)
  when(deRR && primedRR) {
    val redRaw = displayRgbScaled(23 downto 16)
    io.red   := Mux(rasterPendingRR, ~redRaw, redRaw)
    io.green := displayRgbScaled(15 downto 8)
    io.blue  := displayRgbScaled(7 downto 0)
  }
  // io.x/y track the same +2 cycle pipeline as the RGB output.
  val hCounterR = RegNext(hCounter.resize(10)) init 0
  val vCounterR = RegNext(vCounter.resize(10)) init 0
  io.x := RegNext(hCounterR) init 0
  io.y := RegNext(vCounterR) init 0
}

object VdpTop {
  // Palette entries: index -> RGB (8-bit per channel, packed as R[23:16] G[15:8] B[7:0]).
  // Entries 0-7 reproduce the previous switch-case colors exactly.
  // Entries 8-15 default to black.
  val paletteColors: Seq[Int] = Seq(
    0x000000, // 0: black
    0xFFFFFF, // 1: white
    0xFF0000, // 2: red
    0x00FF00, // 3: green
    0x0000FF, // 4: blue
    0xFFFF00, // 5: yellow
    0x00FFFF, // 6: cyan
    0xFF00FF, // 7: magenta
    0x000000, // 8-15: black (unused)
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000,
    0x000000
  )

  def paletteInit: Seq[Bits] = paletteColors.map(c => B(c, 24 bits))

  // Sprite pattern: 16x16 pixels, 4-bit palette index. Arrow/diamond shape using palette colors.
  val spritePatternData: Seq[Seq[Int]] = Seq(
    Seq(0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0),
    Seq(0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,0),
    Seq(0,0,0,0,1,2,2,5,5,2,2,1,0,0,0,0),
    Seq(0,0,0,1,2,2,5,5,5,5,2,2,1,0,0,0),
    Seq(0,0,1,2,2,5,5,5,5,5,5,2,2,1,0,0),
    Seq(0,1,2,2,5,5,5,1,1,5,5,5,2,2,1,0),
    Seq(1,2,2,5,5,5,1,1,1,1,5,5,5,2,2,1),
    Seq(1,2,2,5,5,5,1,1,1,1,5,5,5,2,2,1),
    Seq(0,1,2,2,5,5,5,1,1,5,5,5,2,2,1,0),
    Seq(0,0,1,2,2,5,5,5,5,5,5,2,2,1,0,0),
    Seq(0,0,0,1,2,2,5,5,5,5,2,2,1,0,0,0),
    Seq(0,0,0,0,1,2,2,5,5,2,2,1,0,0,0,0),
    Seq(0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,0),
    Seq(0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0)
  )

  // Sprite 0: diamond shape (white/red/yellow)
  def sprite0PatternInit: Seq[Bits] = spritePatternData.flatten.map(v => B(v, 4 bits))

  // Sprite 1: cross shape (cyan/magenta) — visually distinct from sprite 0.
  val sprite1PatternData: Seq[Seq[Int]] = Seq(
    Seq(0,0,0,0,0,0,6,6,6,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(6,6,6,6,6,6,6,7,7,6,6,6,6,6,6,6),
    Seq(6,7,7,7,7,7,7,7,7,7,7,7,7,7,7,6),
    Seq(6,7,7,7,7,7,7,7,7,7,7,7,7,7,7,6),
    Seq(6,6,6,6,6,6,6,7,7,6,6,6,6,6,6,6),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,7,7,6,0,0,0,0,0,0),
    Seq(0,0,0,0,0,0,6,6,6,6,0,0,0,0,0,0)
  )

  def sprite1PatternInit: Seq[Bits] = sprite1PatternData.flatten.map(v => B(v, 4 bits))

  /** Sprite Pattern Memory Foundation (CyanPeak #8596) — single 4096×4-bit
    * BSRAM-backed pattern RAM. Slot 0 holds the legacy diamond pattern,
    * slot 1 holds the legacy cross, slots 2..15 are zero (transparent)
    * until bus writes program them. Address layout per slot is
    * 256 entries (16×16 4-bit pixels). */
  def spritePatternRamInit: Seq[Bits] = {
    val slot0 = spritePatternData.flatten          // 256 nibbles
    val slot1 = sprite1PatternData.flatten         // 256 nibbles
    // Task 53 (#9419): RAM depth 4096 → 16384 (64 slots × 256 entries).
    val zeros = Seq.fill(16384 - 2 * 256)(0)       // slots 2..63
    (slot0 ++ slot1 ++ zeros).map(v => B(v, 4 bits))
  }

  def paletteRgb(index: Int): (Int, Int, Int) = {
    val c = paletteColors(index & 0xF)
    ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)
  }

  def sprite0PixelAt(row: Int, col: Int): Int = {
    if (row >= 0 && row < 16 && col >= 0 && col < 16)
      spritePatternData(row)(col)
    else 0
  }

  def sprite1PixelAt(row: Int, col: Int): Int = {
    if (row >= 0 && row < 16 && col >= 0 && col < 16)
      sprite1PatternData(row)(col)
    else 0
  }
}

object VdpTopVerilog extends App {
  Config.spinal.generateVerilog(VdpTop())
}

object VdpTopVhdl extends App {
  Config.spinal.generateVhdl(VdpTop())
}
```

## File: hw/spinal/spinalhdlvdp/BitmapRowFetch.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._

/** Task 44b — linear bitmap + attribute row fetch with SDRAM backing.
  *
  * Architecture (per BronzeGate #8023 — StreamFifoCC CDC bridge):
  *
  *   sdramCd:
  *     FSM does power-up init (writes test pattern into SDRAM regions),
  *     then on each CDC'd `fetchGrant` pulse reads one bitmap row + one
  *     attribute row from SDRAM and pushes each byte as a Stream
  *     element into `StreamFifoCC(pushCd=sdramCd, popCd=pixelCd)`.
  *     Stream payload = (kind, idx, data) where kind picks bitmap vs
  *     attribute buffer, idx is the target line-buffer index, data is
  *     the SDRAM byte.
  *
  *   pixelCd:
  *     Line buffers `bitmapLineBuf` / `attrLineBuf` are plain pixel-
  *     domain `Mem`s. A consumer pops the FIFO and writes the byte to
  *     the selected buffer at the indicated index. `BitmapFetch`
  *     reads these buffers via `readAsync`.
  *
  * The FIFO is the single CDC primitive — no dual-clock `Mem` and no
  * `addAttribute("crossClockDomain")` escape hatch.
  *
  * SDRAM layout:
  *   0x3000 .. 0x3000 + BitmapBytesPerRow*MaxLines - 1   bitmap region
  *   0x4000 .. 0x4000 + AttrBytesPerRow  *MaxLines - 1   attribute region
  */
case class BitmapRowFetch(sdramCd: ClockDomain, skipSdramInit: Boolean = false) extends Component {

  val BitmapSdramBase    = 0x3000
  val AttrSdramBase      = 0x4000
  val BitmapBytesPerRow  = 128   // Task 44b iter 6d: power-of-two for shift-addressing
  val AttrBytesPerRow    = 128
  // RGB565 directcolor (CP-1c) needs one buffer entry per source pixel
  // (320 source px shown at 2 HDMI columns each), so the line buffers
  // grew 128 → 512. Indexed 1bpp/2bpp still use only the low entries.
  val BitmapBufferDepth  = 512
  val AttrBufferDepth    = 512
  // Directcolor fetch: 320 source pixels per row, one byte per pixel in
  // each of the bitmap (lo) and attr (hi) regions; 512-byte row stride.
  val DirectColorPixels  = 320
  val DirectRowStrideLog = 9
  // Fun-demo friendly generalization: keep the existing proof fetcher shape
  // (80 active bytes inside a 128-byte row stride), but cover a full 240-row
  // source image by repeating each fetched row for two HDMI scanlines.
  val MaxLines           = 240
  val TotalBitmapBytes   = BitmapBytesPerRow * MaxLines
  val TotalAttrBytes     = AttrBytesPerRow   * MaxLines
  val FifoDepth          = 256

  require(isPow2(BitmapBufferDepth))
  require(isPow2(AttrBufferDepth))
  require(isPow2(FifoDepth))

  val io = new Bundle {
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    // RGB565-FULLFRAME-132 Phase 0: SDRAM read burst length (words) for THIS client's
    // reads, forwarded to the arbiter (which muxes it to sdram.v). Direct-color row
    // fetch drives 8 (one Activate → 8 consecutive column reads, ~4× the throughput of
    // single reads → closes the 40.5 MHz refresh-ON bandwidth wall); indexed/1bpp/2bpp
    // drives 1 (bit-identical legacy single read).
    val sdramBurstLen  = out UInt(4 bits)
    // #11246 F2 (defensive look-ahead, PM #11260): next-cycle value of the cmd regs
    // so the top upload gate avoids the registered-rd collision for this client too.
    val sdramRdNext    = out Bool()
    val sdramWrNext    = out Bool()
    val sdramDout      = in  Bits(8 bits)
    // RGB565-FULLFRAME-132 (#12283): the SDRAM controller is 32-bit (sdram.v
    // DATA_WIDTH=32); `sdramDout` is only a byte-select of this word. The
    // direct-color fetch reads `sdramDout32` (4 bytes per ~5-cycle SDRAM read)
    // instead of one byte, cutting 640 byte-reads/row to 160 word-reads/row so
    // a full 320×240 frame fits the bus budget. Same aperture PlanarLineFetch
    // already uses. Wired from ctrl.io.dout32 at the top level.
    val sdramDout32    = in  Bits(32 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()
    val fetchGrant     = in  Bool()
    val fetchLine      = in  UInt(10 bits)
    val col            = in  UInt(10 bits)
    val enable         = in  Bool()    // pixel-domain bitmap-mode enable
    val directColor    = in  Bool()    // CP-1c: RGB565 directcolor fetch mode (2 bytes/pixel)
    val tileBootDone   = in  Bool()    // iter 6: tile-fetch init complete (safe to init our SDRAM regions)
    // BITMAP-PLUMB-129 (#12169/#12205): host-programmable bitmap/attr SDRAM
    // base, row stride, and source height. Driven (pixel domain) from the
    // VdpTop register block at 0x0351..0x0357 and BufferCC'd into sdramCd
    // below. Power-on defaults reproduce the former hardcoded constants
    // (base 0x3000/0x4000, stride 512, height 240) byte-for-byte.
    val bitmapBase     = in  UInt(23 bits)
    val attrBase       = in  UInt(23 bits)
    val bitmapStride   = in  UInt(16 bits)   // direct-color row stride in bytes
    val attrStride     = in  UInt(16 bits)   // direct-color attr row stride in bytes
    val bitmapHeight   = in  UInt(10 bits)   // source image height in rows
    val bitmapByte     = out Bits(8 bits)
    val attrByte       = out Bits(8 bits)
    val bootDone       = out Bool()
    val sdramActive    = out Bool()    // pulses whenever SDRAM FSM wants the bus (pixel domain, BufferCC'd)
    // RGB565-FULLFRAME-132 Phase 0: raw sdramCd-domain fetch-active level (= sd.sdramActiveR,
    // no CDC). High across every fetch/init state, low only when the FSM is idle between
    // source rows. A same-domain refresh sequencer uses this to insert AUTO_REFRESH ONLY at
    // an idle (safe) boundary — never racing the FSM's registered cmdRd mid-fetch.
    val sdramActiveRaw = out Bool()
  }

  // RGB565-FULLFRAME-132 B.2 (#12309): the FIFO carries one 32-bit SDRAM word
  // (4 bytes) per entry. `idx` is the base line-buffer BYTE index of byte 0 of
  // the word (word reads are 4-aligned). The line buffers are now 32-bit wide so
  // the pop side stores one whole word per cycle — the old 4-byte expander cost
  // ~4 pixel-clocks/word and made a full line take ~920 pixel-clocks vs the 800
  // available (the bandwidth wall measured in #12306). They are also
  // double-buffered (readSync) so the compositor never reads the bank the
  // fetcher is filling.
  case class RowByte() extends Bundle {
    val kind = Bool()
    val idx  = UInt(log2Up(BitmapBufferDepth) bits)
    val data = Bits(32 bits)
    // RGB565-FULLFRAME-132 B.2 (#12350): target line-buffer BANK travels WITH the
    // word through the FIFO. With the grant queue the FSM fetch (sdramCd) is
    // decoupled from the pixel-domain display-bank rotation, so the fill bank must
    // be the one the FSM chose when it fetched this row — carried here, not a
    // separate pixel-side register that could drift out of sync.
    val bank = UInt(2 bits)
  }

  val byteFifo = StreamFifoCC(
    dataType  = RowByte(),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current)

  // 32-bit-wide, TRIPLE-buffered line buffers (Option B, #12346). WordDepth =
  // byte depth / 4. Three banks per plane so a source row is fetched TWO rows
  // ahead of its display: at any time one bank displays, one holds the next row
  // already complete, and one is filling. That gives the ~1566-pixel-clock fetch
  // up to ~2 source-row windows (~3200 pixel-clocks) of lead — ample slack so an
  // AUTO_REFRESH landing inside the fetch cannot push it past the budget (the
  // failure mode that sank the 2-bank/2-line-window Option A under refresh).
  val WordDepth = BitmapBufferDepth / 4
  require(WordDepth * 4 == BitmapBufferDepth)
  val NBanks    = 3
  val bitmapBuf = Seq.fill(NBanks)(Mem(Bits(32 bits), WordDepth))
  val attrBuf   = Seq.fill(NBanks)(Mem(Bits(32 bits), WordDepth))

  // Bank rotation (pixel domain). The fetchGrant pulse (driven from VdpTop once
  // per SOURCE ROW at hTotal-1) advances `dispBank` mod 3 to present the row that
  // finished filling, while `fillBankReg` (held 2 banks ahead) targets the bank
  // for the row two ahead. Advancing at hTotal-1 lands the new bank in `dispBankD`
  // (RegNext) exactly for the next row's pixel 0, absorbing the readSync latency.
  def inc3(x: UInt): UInt = Mux(x === U(NBanks - 1, 2 bits), U(0, 2 bits), x + 1)
  val fetchGrantPixPrev = RegNext(io.fetchGrant) init False
  val fetchGrantPixEdge = io.fetchGrant && !fetchGrantPixPrev
  val dispBank = RegInit(U(0, 2 bits))
  when(fetchGrantPixEdge) {
    dispBank := inc3(dispBank)
  }

  // Compositor read. Indexed 1bpp/2bpp pack 8 hCounter values per byte → byte =
  // col/8; directcolor stores one byte per source pixel shown at 2 HDMI columns
  // → byte = col/2. The byte index splits into a 32-bit word address and a byte
  // lane. readSync adds one cycle of latency, so the byte-lane and bank selects
  // are delayed one cycle to stay aligned with the registered word.
  val indexedRdAddr = io.col(9 downto 3).resize(log2Up(BitmapBufferDepth))
  val directRdAddr  = io.col(9 downto 1).resize(log2Up(BitmapBufferDepth))
  val lineRdByte    = Mux(io.directColor, directRdAddr, indexedRdAddr)
  val rdWordAddr    = (lineRdByte >> 2).resize(log2Up(WordDepth))
  val rdLane        = lineRdByte(1 downto 0)
  val bmW = Vec(bitmapBuf.map(_.readSync(rdWordAddr)))
  val atW = Vec(attrBuf.map(_.readSync(rdWordAddr)))
  val rdLaneD   = RegNext(rdLane) init 0
  val dispBankD = RegNext(dispBank) init 0
  val bmWord = bmW(dispBankD)
  val atWord = atW(dispBankD)
  io.bitmapByte := bmWord.subdivideIn(8 bits)(rdLaneD)
  io.attrByte   := atWord.subdivideIn(8 bits)(rdLaneD)

  // Pop side: one 32-bit word per cycle into the fill bank, routed by kind.
  // No 4-cycle byte expansion and no stale-state startup write — the old popBusy
  // expander emitted one spurious write (idx 116) before the first real word.
  byteFifo.io.pop.ready := True
  val popFire     = byteFifo.io.pop.fire
  val popData     = byteFifo.io.pop.payload.data
  val popKind     = byteFifo.io.pop.payload.kind
  val popBank     = byteFifo.io.pop.payload.bank   // FSM-chosen target bank, carried with the word
  val popWordAddr = (byteFifo.io.pop.payload.idx >> 2).resize(log2Up(WordDepth))
  for (b <- 0 until NBanks) {
    val isFillBank = popBank === U(b, 2 bits)
    bitmapBuf(b).write(popWordAddr, popData, enable = popFire && !popKind && isFillBank)
    attrBuf(b).write  (popWordAddr, popData, enable = popFire &&  popKind && isFillBank)
  }

  val sd = new ClockingArea(sdramCd) {
    val fetchGrantSync = BufferCC(io.fetchGrant, False)
    val fetchGrantPrev = RegNext(fetchGrantSync) init False
    val fetchGrantEdge = fetchGrantSync && !fetchGrantPrev
    // #11246 F1 (CyanPeak): gray-code fetchLine before the CDC so only ONE bit
    // flips per line — a raw multi-bit binary BufferCC can return a torn
    // intermediate at line/tileY boundaries (e.g. 239->240 flips 5 bits) -> wrong
    // line fetched. Same mitigation SdramTileAttributeFetch already uses (#7138);
    // this engine was missed.
    def bin2gray(b: UInt): UInt = b ^ (b >> 1).resize(b.getWidth)
    val fetchLineGraySync = BufferCC(bin2gray(io.fetchLine), init = U(0, 10 bits))
    val fetchLineSync = UInt(10 bits)
    for (i <- 0 until 10) { fetchLineSync(i) := fetchLineGraySync(9 downto i).xorR }
    val enableSync     = BufferCC(io.enable, False)
    val directColorSync = BufferCC(io.directColor, False)
    val tileBootDoneSync = BufferCC(io.tileBootDone, False)
    // BITMAP-PLUMB-129: quasi-static host config — these change only on a
    // safe-boundary register commit (0x0351..0x0357) and are then held stable
    // for many frames, so a plain multi-bit BufferCC is safe here (unlike the
    // per-line fetchLine, which is gray-coded above). Reset values reproduce
    // the legacy hardcoded constants until the host programs them.
    val bitmapBaseCdc   = BufferCC(io.bitmapBase,   U(BitmapSdramBase, 23 bits))
    val attrBaseCdc     = BufferCC(io.attrBase,     U(AttrSdramBase,   23 bits))
    val bitmapStrideCdc = BufferCC(io.bitmapStride, U(1 << DirectRowStrideLog, 16 bits))
    val attrStrideCdc   = BufferCC(io.attrStride,   U(1 << DirectRowStrideLog, 16 bits))
    val bitmapHeightCdc = BufferCC(io.bitmapHeight, U(MaxLines, 10 bits))

    // RGB565-FULLFRAME-132 (CoralReef #12355 cond.5, Option a): a burst-8 read must
    // start on a 32-byte boundary and stay inside one 1KB SDRAM row. Direct-color is
    // the only burst client, so when directColor is active we hard-enforce 32-byte
    // alignment on the host-programmable base AND stride by masking their low 5 bits.
    // (The POR defaults — base 0x3000/0x4000, stride 512 — are already aligned, so
    // this is a no-op for the demo; it only guards a mis-programmed host.) Indexed
    // 1bpp/2bpp uses single reads (no alignment requirement) and is left untouched.
    // Documented in MODE0_REGISTER_BUS_SPEC §3.1.3 (CoralReef owns the doc update).
    val Align32Mask     = ~U(0x1F, 23 bits)
    val bitmapBaseAln   = bitmapBaseCdc & Align32Mask
    val attrBaseAln     = attrBaseCdc   & Align32Mask
    val bitmapStrideAln = bitmapStrideCdc & ~U(0x1F, 16 bits)
    val attrStrideAln   = attrStrideCdc   & ~U(0x1F, 16 bits)
    // Base used by the row fetch: 32-byte-aligned in direct-color (burst), raw
    // otherwise (indexed single reads have no alignment constraint).
    val bitmapBaseUse   = Mux(directColorSync, bitmapBaseAln, bitmapBaseCdc)
    val attrBaseUse     = Mux(directColorSync, attrBaseAln,   attrBaseCdc)
    // Burst length for THIS client's reads: 8 words in direct-color, 1 otherwise.
    val burstWords      = Mux(directColorSync, U(8, 4 bits), U(1, 4 bits))

    val cmdAddr = Reg(UInt(23 bits)) init 0
    val cmdDin  = Reg(Bits(8 bits))  init 0
    val cmdRd   = RegInit(False)
    val cmdWr   = RegInit(False)
    val bootDoneR = RegInit(False)

    val bootCounter = Reg(UInt(log2Up(TotalBitmapBytes + 1) bits)) init 0
    val byteIdx     = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val lineReg     = Reg(UInt(10 bits)) init 0

    // CP-1c: per-line fetch count and SDRAM row byte-offset. Directcolor
    // fetches 320 bytes/row (one per source pixel) on a 512-byte stride;
    // indexed 1bpp/2bpp keep the legacy 80 bytes on a 128-byte stride.
    val fetchCount  = Mux(directColorSync, U(DirectColorPixels, 10 bits), U(80, 10 bits))
    // BITMAP-PLUMB-129: per-row byte offset. Direct-color now uses the host
    // BITMAP_STRIDE/ATTR_STRIDE byte stride (default 512 == legacy <<9);
    // indexed 1/2bpp keeps its hardwired 128-byte (<<7) legacy stride per the
    // approved scope (#12205). The bitmap and attr offsets are split so the two
    // strides are independent. The lineReg×stride product is registered to keep
    // the 10×16 multiply off the SDRAM address critical path — lineReg is set in
    // sIdle and held stable through the 16-cycle sFetchSettle window before
    // sFetchBitmap/sFetchAttr consume these, so RegNext is settled in time.
    val bitmapRowByteBase = RegNext(Mux(directColorSync,
                          (lineReg * bitmapStrideAln).resize(23),
                          (lineReg << 7).resize(23))) init 0
    val attrRowByteBase   = RegNext(Mux(directColorSync,
                          (lineReg * attrStrideAln).resize(23),
                          (lineReg << 7).resize(23))) init 0

    // Task 44b iter 6d (CyanPeak audit correction): replace dividers with
    // counters to ensure timing closure at the 40.5 MHz SDRAM clock.
    val initLineReg = Reg(UInt(8 bits)) init 0
    val initColReg  = Reg(UInt(8 bits)) init 0
    val initBitmapByte = (initLineReg + initColReg).resize(8).asBits
    val attrPaper = initLineReg(2 downto 0)
    val attrInk   = (initLineReg(2 downto 0) + initColReg(2 downto 0))(2 downto 0)
    val initAttrByte = (B(0, 2 bits) ## attrPaper.asBits ## attrInk.asBits)

    // Task 44b iter 6d (CyanPeak audit correction): pipeline metadata.
    // Latch the kind and index of the IN-FLIGHT request so we don't
    // rely on FSM registers being stable when dataReady eventually pulses.
    val inflightKind = (Reg(Bool())     init False).simPublic()
    val inflightIdx  = (Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0).simPublic()

    // RGB565-FULLFRAME-132 Phase 0: BURST capture. A burst-N read returns N words on
    // N consecutive `data_ready` pulses (one per sdramCd cycle). The previous depth-1
    // `pushPending` latch held only ONE word, so the 2nd pulse of a burst arrived while
    // pushPending was still set and was silently dropped. Instead push each word
    // straight into byteFifo: it is a StreamFifoCC of depth 256 whose pop side drains a
    // word every pixel-clk, so it is never near full during a ≤8-word burst and
    // push.ready stays high. `burstCnt` counts words within the current read and
    // offsets the target line-buffer index by 4 bytes per word. A push refused mid-burst
    // would drop a word and is caught by the proof gate (cosim 0-mismatch). The single
    // read (indexed/1bpp/2bpp, burstWords=1) is the N=1 special case — bit-for-bit the
    // old one-word-per-read behavior, minus the now-unnecessary 1-cycle latch delay.
    val burstCnt = (Reg(UInt(4 bits)) init 0).simPublic()  // words received in current read

    // Forward-declared so the FSM (below) and the push logic can reference it.
    val sdramActiveR = RegInit(False)
    // `fetchBank` = the line-buffer bank for the row the FSM is currently fetching;
    // advanced once per fetch in sIdle (below), 2 banks ahead of the display bank.
    val fetchBank = Reg(UInt(2 bits)) init 2   // 2 banks ahead of dispBank (init 0)

    // Push is enabled only in the fetch-WAIT states (set True there in the FSM). The
    // controller registers `rd` one cycle AFTER the issue state asserts cmdRd, by which
    // point the FSM is already in the WAIT state, so data_ready (≥ T_RCD+CAS+1 cycles
    // later) is always observed with pushEnable high — no early-data race.
    val pushEnable = Bool()
    pushEnable := False
    byteFifo.io.push.valid         := io.sdramDataReady && sdramActiveR && pushEnable
    byteFifo.io.push.payload.kind  := inflightKind
    byteFifo.io.push.payload.idx   := (inflightIdx + (burstCnt << 2)).resize(log2Up(BitmapBufferDepth))
    byteFifo.io.push.payload.data  := io.sdramDout32   // current burst word (dq_in_r), valid at data_ready
    byteFifo.io.push.payload.bank  := fetchBank        // FSM-chosen target bank, carried with the word

    // RGB565-FULLFRAME-132 B.2 (#12350): grant QUEUE. At 40.5 MHz a row fetch
    // (1566 pixel-clocks) + an AUTO_REFRESH can overrun the ~1600 pixel-clock
    // per-source-row grant period. Without a queue the FSM (busy, not in sIdle)
    // DROPS that grant and the row is never fetched. Here every fetchGrant pulse is
    // latched into `grantPending`/`pendingLine`; sIdle consumes it (immediately, no
    // wait for a fresh pulse) so the FSM does a back-to-back catch-up fetch. Depth
    // 1: a 2nd grant arriving while one is already pending is a true bandwidth
    // collapse — counted in `grantOverflow` (and surfaces as a sim mismatch).
    val grantPending  = (RegInit(False)).simPublic()
    val pendingLine   = Reg(UInt(10 bits)) init 0
    val grantOverflow = (Reg(UInt(8 bits)) init 0).simPublic()

    val fsm = new StateMachine {
      val sWaitEnable      = new State with EntryPoint
      val sInitSettle      = new State
      val sInitBitmap      = new State
      val sInitAttr        = new State
      val sIdle            = new State
      val sFetchSettle     = new State
      val sFetchBitmap     = new State
      val sFetchBitmapWait = new State
      val sFetchAttr       = new State
      val sFetchAttrWait   = new State

      // Wait for bitmap mode to be enabled before starting init. This
      // gates the SDRAM init writes behind BITMAP_CTRL[0] rising, which
      // also causes the top-level arbiter to route client-1 requests to
      // the SDRAM controller. Without this gate the init writes are
      // dropped by the arbiter (client-0 wins) and SDRAM stays
      // uninitialised, leading to a black render.
      sWaitEnable.whenIsActive {
        cmdRd := False; cmdWr := False
        sdramActiveR := False
        // Iter 6: also wait for tile-fetch bootDone so the arbiter has
        // no competing client for our SDRAM regions (0x3000..0x4FFF).
        // Previously our init cmdWr pulses were silently dropped for
        // the first ~4-6 cycles while BufferCC was propagating
        // sdramActive=True into pixelCd, because the arbiter was still
        // routing client 0 and tile fetch had its own init writes in
        // flight. bootCounter advanced unconditionally so the FSM
        // \"completed\" with many writes missing.
        //
        // Task 44b iter 6c (CyanPeak audit correction): removed !io.sdramBusy
        // from this transition. sInitSettle provides the mandatory window
        // for sdramActive to propagate; waiting for busy here could cause
        // a deadlock if client 0 is keeping the bus busy.
        when(enableSync && tileBootDoneSync) {
          bootCounter := 0
          initLineReg := 0
          initColReg  := 0
          sdramActiveR := True
          if (skipSdramInit) {
            // #9026 zero-footprint (BronzeGate ruling #9133): host owns SDRAM
            // population for the bitmap region too. Skip the procedural
            // bitmap/attr init fill and jump straight to fetch-idle so
            // host-staged SDRAM contents are preserved.
            bootDoneR := True
            goto(sIdle)
          } else {
            goto(sInitSettle)
          }
        }
      }

      // Task 44b iter 6b (CyanPeak audit fix): pre-arm the arbiter by
      // holding sdramActiveR high for a window before issuing any writes.
      // This ensures the top-level pixel-domain Mux has observed our
      // client-1 request before cmdWr pulses arrive at the controller.
      // Iter 6c: increased from 8 -> 16 cycles to safely cover the ~3-pixel-cycle
      // BufferCC delay (3 * 3.33 = 10 SDRAM cycles).
      sInitSettle.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        bootCounter := bootCounter + 1
        when(bootCounter >= 16) {
          bootCounter := 0
          goto(sInitBitmap)
        }
      }

      sInitBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < (bitmapHeightCdc << 7)) {
            cmdWr   := True
            cmdAddr := (bitmapBaseCdc + bootCounter.resize(23)).resized
            cmdDin  := initBitmapByte
            bootCounter := bootCounter + 1
            // Advance counters
            when(initColReg === 127) {
              initColReg := 0
              initLineReg := initLineReg + 1
            } otherwise {
              initColReg := initColReg + 1
            }
          } otherwise {
            bootCounter := 0
            initLineReg := 0
            initColReg  := 0
            goto(sInitAttr)
          }
        }
      }

      sInitAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < (bitmapHeightCdc << 7)) {
            cmdWr   := True
            cmdAddr := (attrBaseCdc + bootCounter.resize(23)).resized
            cmdDin  := initAttrByte
            bootCounter := bootCounter + 1
            // Advance counters
            when(initColReg === 127) {
              initColReg := 0
              initLineReg := initLineReg + 1
            } otherwise {
              initColReg := initColReg + 1
            }
          } otherwise {
            bootCounter := 0
            bootDoneR   := True
            goto(sIdle)
          }
        }
      }

      sIdle.whenIsActive {
        cmdRd := False; cmdWr := False
        sdramActiveR := False
        // Consume a QUEUED grant (latched below) — services both a fresh grant and
        // a grant that arrived while the previous fetch was still running.
        when(grantPending) {
          // Each source row is displayed for two screen lines so a 240-row
          // bitmap fills the 480-line HDMI output without adding a scaler.
          lineReg := (pendingLine >> 1).resize(10)
          fetchBank := inc3(fetchBank)   // advance to this fetch's target bank
          grantPending := False
          byteIdx := 0
          bootCounter := 0
          sdramActiveR := True
          goto(sFetchSettle)
        }
      }

      // Task 44b iter 6b (CyanPeak audit fix): pre-arm the arbiter for
      // per-line fetch. Iter 6c: increased to 16 cycles.
      sFetchSettle.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        bootCounter := bootCounter + 1
        when(bootCounter >= 16) {
          bootCounter := 0
          goto(sFetchBitmap)
        }
      }

      // RGB565-FULLFRAME-132 B.2 (#12318): INTERLEAVED bitmap/attr fetch —
      // bm@idx, at@idx, idx+=4, repeat. The old serial order (all 80 bitmap words
      // then all 80 attr words) left attr as the fetch tail, so the scanout beam
      // read attr's early pixels before they were fetched (cosim attr-lag, #12317).
      // Interleaving keeps both planes at the same fill-ahead distance; the total
      // line fetch time (~373 pixel-cycles) is unchanged.
      // RGB565-FULLFRAME-132 B.2 (#12318) + Phase 0 burst: INTERLEAVED bitmap/attr
      // fetch at BURST granularity — bm-burst@idx, at-burst@idx, idx += 8 words,
      // repeat. Direct-color issues 10 burst-8 reads/plane (byteIdx 0,32,..,288);
      // indexed issues single-word reads as before (byteIdx 0,4,..). Interleaving at
      // burst granularity keeps both planes at the same fill-ahead distance (the old
      // serial order left attr as a tail the beam outran, #12317). One burst-8 read
      // delivers 8 words back-to-back, so the whole 320×240 row fetch drops from
      // ~1566 to ~370 pixel-clocks — comfortably inside the ~1600/source-row budget
      // even with an AUTO_REFRESH landing mid-row.
      sFetchBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < fetchCount) {
            cmdRd   := True
            cmdAddr := (bitmapBaseUse +
                        bitmapRowByteBase +
                        byteIdx.resize(23)).resized
            inflightKind := False
            inflightIdx  := byteIdx
            burstCnt     := 0          // first word of this burst lands at idx+0
            goto(sFetchBitmapWait)
          } otherwise {
            // Both planes for the whole row are done (attr was fetched in lockstep).
            goto(sIdle)
          }
        }
      }

      sFetchBitmapWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        pushEnable := True   // capture each burst word as data_ready pulses arrive
        // Count words within the burst; after the LAST word the bitmap burst is done —
        // fetch the attr burst at the SAME idx before advancing, so the planes fill together.
        when(byteFifo.io.push.fire) {
          when(burstCnt === (burstWords - 1)) {
            burstCnt := 0
            goto(sFetchAttr)
          } otherwise {
            burstCnt := burstCnt + 1
          }
        }
      }

      sFetchAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          cmdRd   := True
          cmdAddr := (attrBaseUse +
                      attrRowByteBase +
                      byteIdx.resize(23)).resized
          inflightKind := True
          inflightIdx  := byteIdx
          burstCnt     := 0
          goto(sFetchAttrWait)
        }
      }

      sFetchAttrWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        pushEnable := True
        // After the LAST attr word, advance the word index by one burst (burstWords*4
        // bytes) and loop back to the bitmap read, interleaving the two planes.
        when(byteFifo.io.push.fire) {
          when(burstCnt === (burstWords - 1)) {
            burstCnt := 0
            byteIdx  := byteIdx + (burstWords << 2).resize(byteIdx.getWidth)
            goto(sFetchBitmap)
          } otherwise {
            burstCnt := burstCnt + 1
          }
        }
      }
    }

    // Depth-1 grant queue latch. Placed AFTER the FSM so that on a cycle where
    // sIdle consumes the pending grant (grantPending := False) AND a new grant edge
    // arrives, this block's `grantPending := True` wins — i.e. serve the old grant
    // and queue the new one. A grant edge while a grant is ALREADY pending is a
    // depth-1 overflow (the FSM fell two rows behind) — counted for the proof.
    when(fetchGrantEdge) {
      when(grantPending) { grantOverflow := grantOverflow + 1 }
      grantPending := True
      pendingLine  := fetchLineSync
    }

    io.sdramAddr := cmdAddr
    io.sdramDin  := cmdDin
    io.sdramRd   := cmdRd
    io.sdramWr   := cmdWr
    io.sdramRdNext := cmdRd.getAheadValue()   // #11246 F2 defensive look-ahead
    io.sdramWrNext := cmdWr.getAheadValue()
    // RGB565-FULLFRAME-132 Phase 0: this client's read burst length (quasi-static with
    // directColor). The arbiter forwards the granted client's value to sdram.v, which
    // latches it at the rd pulse. burstWords=1 outside direct-color → legacy single read.
    io.sdramBurstLen := burstWords

    // Level-high sdramActive: True across all non-idle states. Pulsing
    // on cmdRd/cmdWr alone is too narrow for the top-level pixelCd
    // BufferCC — arbiter would miss the window and drop writes. Gets
    // set when enable first sees high, stays high until fetch loop
    // quiesces in sIdle (with no new grant). Declared earlier now
    // so iter-5 always-on latch can reference it.
  }

  io.bootDone    := BufferCC(sd.bootDoneR, False)
  io.sdramActive := BufferCC(sd.sdramActiveR, False)
  io.sdramActiveRaw := sd.sdramActiveR   // raw sdramCd-domain level (no CDC) for same-domain refresh gating
}
```

## File: hw/spinal/spinalhdlvdp/LinestateStore.scala

```scala
package spinalhdlvdp

import spinal.core._

/** Double-buffered per-scanline control store.
  *
  * - Prepare side: writable by host via write interface at any time
  * - Commit side: readable by render pipeline only
  * - Atomic commit at line boundary: at each line start, the prepare entry for
  *   the current fill line is copied to the commit side
  *
  * Each entry is 12 bits packed as:
  *   [11]    = layer0Enable
  *   [10]    = layer1Enable
  *   [9:0]   = layer0ScrollX
  */
case class LinestateStore(lineCount: Int) extends Component {
  val io = new Bundle {
    // Write interface (prepare side)
    val writeAddr   = in UInt(log2Up(lineCount) bits)
    val writeData   = in Bits(12 bits)
    val writeEnable = in Bool()

    // Line-boundary commit: copies prepare[commitLine] to commit[commitLine].
    val commitLine   = in UInt(log2Up(lineCount) bits)
    val commitStrobe = in Bool()

    // Read interface (commit side, used by render pipeline)
    val readAddr      = in UInt(log2Up(lineCount) bits)
    val layer0Enable  = out Bool()
    val layer1Enable  = out Bool()
    val layer0ScrollX = out UInt(10 bits)
  }

  // BSRAM-first partial conversion (per fpga/ARCHITECTURE_RECOMMENDATIONS.md
  // Rec #2, addressing Gate #2 Mem→FF promotion on Tang Nano).
  //
  // `prepare` is converted to BSRAM via readSync + ram_style="block".
  // `commit` stays readAsync — the render pipeline reads it
  // combinationally per-pixel and a 1-cycle readSync delay there
  // produces a per-line first-pixel artifact (line-boundary stale
  // record) that breaks integration sims. The prepare-side
  // conversion alone frees ~half the previously-allocated SSRAM
  // blocks for the linestate submodule (~96 blocks), enough to
  // relieve the Gate #2 reshuffle's DFF promotion pressure.
  //
  // The commit pipeline is delayed by 1 cycle to align with the
  // prepare readSync output. BH-6 collision handling is preserved
  // via parallel pipeline of collide/writeData. Same external
  // contract — no impact on host-write or render-side timing.
  val prepare = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount))
  val commit  = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount))
  prepare.addAttribute("ram_style", "block")

  // Write to prepare side.
  when(io.writeEnable) {
    prepare.write(io.writeAddr, io.writeData)
  }

  // BH-6 (Beam Hardening artifact §3.6): same-cycle host write + commit
  // collision robustness — preserved via 1-cycle pipeline to align
  // with prepare.readSync output. Collision is detected at the
  // commit-strobe cycle, latched alongside the writeData and
  // commitLine, then applied to commit.write the next cycle when
  // prepareSync is valid.
  val commitCollide     = io.commitStrobe && io.writeEnable && (io.writeAddr === io.commitLine)
  val commitStrobeD1    = RegNext(io.commitStrobe) init False
  val commitLineD1      = RegNext(io.commitLine)   init 0
  val commitCollideD1   = RegNext(commitCollide)   init False
  val commitWriteDataD1 = RegNext(io.writeData)    init 0
  val prepareSync       = prepare.readSync(io.commitLine)
  val commitData        = Mux(commitCollideD1, commitWriteDataD1, prepareSync)
  when(commitStrobeD1) {
    commit.write(commitLineD1, commitData)
  }

  // Render-side read of commit: still readAsync so the per-pixel
  // render path gets the line's record combinationally with no
  // line-boundary stale-pixel artifact. Commit Mem stays in
  // distributed SSRAM/LUTRAM (unchanged from the original design).
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — RECLASSIFIED from Class 3.
  // Per the prior author comment at line 39-50 / line 77-80 above, this read
  // MUST stay readAsync: render pipeline reads commit per-pixel and a 1-cycle
  // readSync delay produces a per-line first-pixel artifact (line-boundary
  // stale-record). DO NOT convert without redesigning the commit-side pipeline.
  val record = commit.readAsync(io.readAddr)
  io.layer0Enable := record(11)
  io.layer1Enable := record(10)
  io.layer0ScrollX := record(9 downto 0).asUInt
}

object LinestateStore {
  def packRecord(l0en: Boolean, l1en: Boolean, l0sx: Int): BigInt = {
    val bits = (if (l0en) 1 << 11 else 0) |
               (if (l1en) 1 << 10 else 0) |
               (l0sx & 0x3FF)
    BigInt(bits)
  }

  /** Pad the init sequence to the next power-of-two depth. This sidesteps the
    * Gowin BSRAM non-power-of-two inference bug (GT-022 in kb/gowin/GOTCHAS.md,
    * reproduced on the 1200-entry tileMap in Task 15). The active lines are
    * 0..lineCount-1; extra padding entries return 0 (all enables off, no scroll)
    * and are never addressed at runtime since `io.*Addr` stays in range.
    */
  def nextPow2(n: Int): Int = {
    var p = 1
    while (p < n) p <<= 1
    p
  }

  // Generic boot default (lane #10567 agnosticism): all per-line layer-enable
  // bits start off. The host owns line-level layer activation via the copper
  // / linestate write path; the RTL ships quiescent.
  def defaultInit(lineCount: Int): Seq[Bits] = {
    val depth = nextPow2(lineCount)
    (0 until depth).map(_ => B(0, 12 bits))
  }

  def expectedRecord(line: Int): (Boolean, Boolean, Int) = (false, false, 0)
}
```

## File: hw/spinal/spinalhdlvdp/QspiDecoder.scala

```scala
package spinalhdlvdp

import spinal.core._

/** QSPI Decoder — turns the `QspiSlave` byte stream into VDP register-write
  * pulses and assembles the READ_STATUS response nibble stream.
  *
  * Packet format (from `QSPI_HOST_CONTROL_PLAN.md` §3):
  *   Header = [CMD:1] [ADDR:3] [LEN:2]  (little-endian)
  *   REG_WRITE (`CMD=0x01`): LEN pairs of little-endian 16-bit words; each
  *     pair emits one `regWriteAddr`/`regWriteData` pulse.  `addr` advances
  *     by 1 per word.
  *   READ_STATUS (`CMD=0x04`): LEN=0; FPGA drives `sel` bytes back to host.
  *     `sel` is the low byte of the incoming address.
  *
  * Checkpoint A responsibility: clean structural Verilog + stable control
  * contract.  Behavioural coverage lives in Checkpoint B sims.
  */
case class QspiDecoder() extends Component {
  val io = new Bundle {
    // Stream from QspiSlave.
    val cmd_opcode    = in Bits (8 bits)
    val cmd_addr      = in UInt (24 bits)
    val cmd_len       = in UInt (16 bits)
    val cmd_valid     = in Bool()
    val payload_byte  = in Bits (8 bits)
    val payload_valid = in Bool()
    // #13888 structural drain fix — word-granular payload path. The QSPI transport
    // packs 2 payload bytes into one 16-bit FIFO token SCLK-side and pops one WORD
    // per clk_sys cycle, so the drain (27 Mword/s = 54 MB/s) outpaces the 80 MHz quad
    // push (40 MB/s) and the CDC token FIFO can never overflow. ADDITIVE + mutually
    // exclusive with the byte path above: a consumer drives exactly one. payload_word
    // is a fully-assembled little-endian word (hi ## lo).
    val payload_word       = in Bits (16 bits)
    val payload_word_valid = in Bool()
    val tx_byte       = out Bits (8 bits)
    val tx_load       = out Bool()
    val tx_byte_sent  = in Bool()
    val active        = in Bool()

    // Task 32b: register bus output — bundle replaces the prior
    // regWriteAddr/Data/Enable triple.
    val regBus = out (Mode0RegBus())

    // Diagnostics / status echo.
    // Diagnostic-only outputs (sel=1/2/3 in READ_STATUS surface) removed
    // for fit budget — only `test_mode0_bad_apple` reads them, and the
    // production sketches (one_dot / starfield / zx_smoke) use sel=0/4/5/6/7
    // exclusively. `last_error` stays — it's the only diagnostic with a
    // live consumer (statusEvQspiError sticky bit).
    val last_error = out Bits (8 bits)
    // Task 35 — host-readable status sticky bits routed from VdpTop.
    val status_sticky = in Bits (16 bits)
    // Task 1 (#9154) — LIVE_MODE: committed MODE_SELECT value, observable
    // via READ_STATUS sel=7 per MODE_SELECT_ARCHITECTURE.md v1.1 §4.2 / Q6
    // (CyanPeak #9161 audit correction).
    val live_mode = in UInt (4 bits)
    // DIAG #10908 (P4 Task A): host-visible SDRAM readback. 32-bit word fetched
    // from a debug-configurable SDRAM address (regs 0x0326/0x0327 in TopTang),
    // surfaced over READ_STATUS sel=8. Diagnostic-only; remove with the lane.
    val debug_sdram_data = in Bits (32 bits)

    // Task 34 — SDRAM_WRITE bridge interface.
    val sdramHeaderValid = out Bool()
    val sdramAddrInit    = out UInt(23 bits)
    val sdramLenBytes    = out UInt(17 bits)
    val sdramByteOut     = out Bits(8 bits)
    val sdramByteValid   = out Bool()
    // #13888 — word-granular SDRAM_WRITE egress (paired with payload_word). One 16-bit
    // word per clk_sys cycle so the SDRAM path drains at the same word rate as REG_WRITE
    // (aligns with the 32-bit SDRAM word too). VdpTop-184 consumes this; the bring-up
    // top only lights the everSdram LED off sdramWordValid.
    val sdramWordOut     = out Bits(16 bits)
    val sdramWordValid   = out Bool()
    val upload_busy      = in Bool()
    val upload_done      = in Bool()
    // CP-A1 (Phase A #11411/#11419): sticky bridge watchdog-abort flag, surfaced
    // on READ_STATUS sel=6 bit2 so the host can detect an aborted upload + resync.
    val upload_error     = in Bool()
    // CP-A4 (#11443): sticky ingress-FIFO overflow flag, surfaced on sel=6 bit3 so
    // the host can detect a transport-ceiling drop (out-pacing the arbiter drain).
    val upload_overflow  = in Bool()

    // QSPI-pivot: expose the full 32-bit READ_STATUS word + a valid pulse so the
    // phase-based synchronous slave (QspiSlaveSync) can shift the response out
    // directly, without the byte-serial tx_byte/tx_load handshake. Additive — the
    // legacy tx_byte path is unchanged, so existing sims/consumers are unaffected.
    val rx_word       = out Bits(32 bits)
    val rx_word_valid = out Bool()
  }

  object Op {
    val REG_WRITE   = B"8'h01"
    val SDRAM_WRITE = B"8'h02"     // Task 34
    val READ_STATUS = B"8'h04"
  }

  // Word-assembly state: collect low byte then high byte, then emit.
  val dataLo    = Reg(Bits(8 bits)) init 0
  val haveLo    = Reg(Bool()) init False
  val writeAddr = Reg(UInt(15 bits)) init 0
  val writeData = Reg(Bits(16 bits)) init 0
  val writePulse = Reg(Bool()) init False
  writePulse := False

  val opcodeReg  = Reg(Bits(8 bits)) init 0
  val lenReg     = Reg(UInt(16 bits)) init 0
  val wordsLeft  = Reg(UInt(16 bits)) init 0
  val activeWrite = Reg(Bool()) init False
  val activeSdramWrite = Reg(Bool()) init False   // Task 34

  // Task 34 — SDRAM_WRITE bridge output registers.
  val sdramHeaderValidReg = Reg(Bool()) init False
  val sdramAddrInitReg    = Reg(UInt(23 bits)) init 0
  val sdramLenBytesReg    = Reg(UInt(17 bits)) init 0
  val sdramByteOutReg     = Reg(Bits(8 bits)) init 0
  val sdramByteValidReg   = Reg(Bool()) init False
  // #13888 — word-granular SDRAM egress registers.
  val sdramWordOutReg     = Reg(Bits(16 bits)) init 0
  val sdramWordValidReg   = Reg(Bool()) init False
  // #11308 hardening: bound the SDRAM_WRITE payload to LEN so trailing/padding/glitch
  // bytes past the declared length are IGNORED (not forwarded as spurious writes that
  // desync the address stream — the libvdp 4-byte-padding corruption, #11297/#11305).
  // Mirrors wordsLeft for REG_WRITE; counts payload BYTES (LEN = 2*words).
  val sdramBytesLeft = Reg(UInt(17 bits)) init 0
  sdramHeaderValidReg := False
  sdramByteValidReg   := False
  sdramWordValidReg   := False

  // Last bus-error diagnostic — read by `statusEvQspiError` for the
  // QSPI_ERROR sticky bit (Task 35). Other diagnostic Regs removed for
  // fit budget; their READ_STATUS sels return zero (handled by the
  // switch's `default` case after their `is` arms are stripped).
  val last_error = Reg(Bits(8 bits))  init 0

  // On a new header, latch opcode/len and reset the word-assembly state.
  when(io.cmd_valid) {
    opcodeReg := io.cmd_opcode
    lenReg    := io.cmd_len
    wordsLeft := io.cmd_len
    writeAddr := io.cmd_addr(14 downto 0)
    haveLo    := False
    activeWrite := io.cmd_opcode === Op.REG_WRITE
    // Task 34 — SDRAM_WRITE dispatch.
    activeSdramWrite := io.cmd_opcode === Op.SDRAM_WRITE
    when(io.cmd_opcode === Op.SDRAM_WRITE) {
      sdramAddrInitReg    := io.cmd_addr(22 downto 0)
      sdramLenBytesReg    := (io.cmd_len << 1).resize(17)   // bytes = 2 * words
      sdramBytesLeft      := (io.cmd_len << 1).resize(17)   // #11308: payload byte budget
      sdramHeaderValidReg := True
    }
  }

  // Each payload byte arrives on `payload_valid`. Assemble low then high.
  when(io.payload_valid) {
    when(activeWrite) {
      // #13838/#13843 hardening: bound REG_WRITE assembly to LEN, mirroring the
      // SDRAM_WRITE sdramBytesLeft guard (#11308). Without this, trailing/padding/
      // glitch bytes past the declared LEN words (or any payload while LEN=0) keep
      // assembling 16-bit words and pulsing regBus.enable onto the auto-incrementing
      // writeAddr, clobbering registers past the intended range. wordsLeft counts
      // WORDS; gating the whole assembly on wordsLeft>0 drops the lo-byte of a
      // would-be extra word too, so exactly LEN writes fire and no more.
      when(wordsLeft > U(0, 16 bits)) {
        when(!haveLo) {
          dataLo := io.payload_byte
          haveLo := True
        } otherwise {
          val word = io.payload_byte ## dataLo
          writeData  := word
          writePulse := True
          haveLo     := False
          wordsLeft  := wordsLeft - 1
        }
      }
    } elsewhen(activeSdramWrite) {
      // Task 34 — raw byte forwarded to the bridge; no word assembly here.
      // #11308: only forward while within the declared LEN budget. Bytes beyond
      // LEN (host 4-byte padding, or any trailing/glitch byte before CS-deassert)
      // are dropped so they cannot become spurious writes past addrInit+LEN.
      when(sdramBytesLeft > U(0, 17 bits)) {
        sdramByteOutReg   := io.payload_byte
        sdramByteValidReg := True
        sdramBytesLeft    := sdramBytesLeft - 1
      }
    } otherwise {
      // Unknown opcode — record error but drop the byte.
      last_error := opcodeReg
    }
  }

  // #13888 — word-granular payload path. Delivers a full 16-bit word per clk_sys
  // cycle (no lo/hi byte assembly here — the transport packed it SCLK-side). Mutually
  // exclusive with the byte path above (a consumer asserts payload_valid OR
  // payload_word_valid, never both), so the two guarded blocks never collide on the
  // shared wordsLeft/writeData/writePulse/sdramBytesLeft registers.
  when(io.payload_word_valid) {
    when(activeWrite) {
      // Same LEN bound as the byte path (#13838/#13843): drop words past LEN.
      when(wordsLeft > U(0, 16 bits)) {
        writeData  := io.payload_word
        writePulse := True
        wordsLeft  := wordsLeft - 1
      }
    } elsewhen(activeSdramWrite) {
      // Same LEN bound as #11308. sdramBytesLeft counts BYTES and (LEN=words) is always
      // even. Guard on >=2 (not >0) so the -2 can never underflow even if a future
      // LEN-injection bug left the counter odd — CoralReef #13893 hardening.
      when(sdramBytesLeft >= U(2, 17 bits)) {
        sdramWordOutReg   := io.payload_word
        sdramWordValidReg := True
        sdramBytesLeft    := sdramBytesLeft - 2
      }
    } otherwise {
      last_error := opcodeReg
    }
  }

  // Auto-increment writeAddr one cycle AFTER the pulse fires, so the pulse
  // itself carries the pre-increment address on the regWrite bus.
  when(writePulse) {
    writeAddr := writeAddr + 1
  }

  io.regBus.addr   := writeAddr
  io.regBus.data   := writeData
  io.regBus.enable := writePulse

  io.last_error := last_error

  // -------------------------------------------------------------------
  // READ_STATUS response FSM (Task 38b — expanded status surface).
  //
  // Plan §3.3 — on CMD=0x04 LEN=0, drive 4 bytes back to the host after
  // the slave's 2-edge turnaround. `sel` = low byte of cmd_addr.
  //
  //   sel=0 → magic 0x51560002 (host transport identification, retained
  //           from Task 27)
  //   sel=1 → rx_cmd_cnt in byte 0, upper 24 bits zero
  //   sel=2 → last_addr low byte in byte 0, high byte in byte 1,
  //           upper 16 bits zero
  //   sel=3 → last_data low byte in byte 0, high byte in byte 1,
  //           upper 16 bits zero
  //   sel=4 → last_error in byte 0, upper 24 bits zero
  //   sel>4 → zeroed word (reserved for future expansion)
  //
  // Load-time snapshot: rxWord is captured once on cmd_valid, never
  // mutated while the response walks Load→Wait→Shift. If rx_cmd_cnt /
  // last_addr / last_data / last_error update mid-response (e.g. a new
  // REG_WRITE lands while the READ_STATUS response is still shifting),
  // the in-flight response is not corrupted.
  // -------------------------------------------------------------------
  object RxState extends SpinalEnum { val Idle, Load, Wait = newElement() }
  val rxState = Reg(RxState()) init RxState.Idle
  val rxByteIdx = Reg(UInt(2 bits)) init 0
  val rxWord    = Reg(Bits(32 bits)) init 0
  val rxLoad    = Reg(Bool()) init False
  val rxTxByte  = Reg(Bits(8 bits)) init 0
  val rxWordValid = Reg(Bool()) init False   // QSPI-pivot: pulse when rxWord latched
  rxLoad := False
  rxWordValid := False

  // Kick off READ_STATUS on header pulse. rxWord is sampled atomically
  // from the current diagnostic state; later changes don't leak in.
  when(io.cmd_valid && io.cmd_opcode === Op.READ_STATUS && io.cmd_len === U(0, 16 bits)) {
    val sel = io.cmd_addr(7 downto 0)
    switch(sel) {
      is(U(0, 8 bits)) { rxWord := B"32'h51560002" }
      // sels 1/2/3 (rx_cmd_cnt / last_addr / last_data) removed; default
      // returns 0. Production sketches don't read them; only the retired
      // `test_mode0_bad_apple` Pico-era diagnostic did.
      is(U(4, 8 bits)) { rxWord := B(0, 24 bits) ## last_error }
      is(U(5, 8 bits)) { rxWord := B(0, 16 bits) ## io.status_sticky }   // Task 35
      is(U(6, 8 bits)) {                                                  // Task 34
        // sel=6 upload status: byte0[0]=upload_busy, byte0[1]=upload_done (latched),
        // byte0[2]=upload_error (CP-A1 sticky watchdog-abort), byte0[3]=upload_overflow
        // (CP-A4 sticky ingress-FIFO overflow).
        val statBits = B(0, 4 bits) ## io.upload_overflow ## io.upload_error ## io.upload_done ## io.upload_busy
        rxWord := B(0, 24 bits) ## statBits
      }
      is(U(7, 8 bits)) {                                                  // Task 1 (#9154)
        // sel=7 LIVE_MODE: byte0[3:0] = committed MODE_SELECT, upper bits zero.
        // Host polls this after a MODE_SELECT write to confirm V=0 commit
        // (alternative to STATUS_STICKY bit 11 MODE_SELECT_CHANGED).
        rxWord := B(0, 28 bits) ## io.live_mode.asBits
      }
      is(U(8, 8 bits)) {                                                  // DIAG #10908
        // sel=8 SDRAM readback: the 32-bit word the SDRAM controller returned
        // for the debug address armed via regs 0x0326/0x0327. Byte order matches
        // dout32 (little-endian: byte0 in [7:0]).
        rxWord := io.debug_sdram_data
      }
      default          { rxWord := B(0, 32 bits) }
    }
    rxByteIdx := 0
    rxState   := RxState.Load
    rxWordValid := True                        // QSPI-pivot: rxWord is now valid
  }

  switch(rxState) {
    is(RxState.Idle) { /* no-op */ }
    is(RxState.Load) {
      rxTxByte := rxWord.subdivideIn(8 bits)(rxByteIdx)
      rxLoad   := True
      rxState  := RxState.Wait
    }
    is(RxState.Wait) {
      when(io.tx_byte_sent) {
        when(rxByteIdx === U(3, 2 bits)) {
          rxState := RxState.Idle
        } otherwise {
          rxByteIdx := rxByteIdx + 1
          rxState   := RxState.Load
        }
      }
    }
  }

  io.tx_byte := rxTxByte
  io.tx_load := rxLoad
  io.rx_word       := rxWord
  io.rx_word_valid := rxWordValid

  // Task 34 — SDRAM bridge outputs
  io.sdramHeaderValid := sdramHeaderValidReg
  io.sdramAddrInit    := sdramAddrInitReg
  io.sdramLenBytes    := sdramLenBytesReg
  io.sdramByteOut     := sdramByteOutReg
  io.sdramByteValid   := sdramByteValidReg
  io.sdramWordOut     := sdramWordOutReg
  io.sdramWordValid   := sdramWordValidReg
}
```

## File: hw/spinal/spinalhdlvdp/QspiTransportCore.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** CDC token carried through the SCLK->clk_sys StreamFifoCC. One token per header
  * or per completed 16-bit payload word (the #13888 drain-fix packing). Moved here
  * from TopTang20kQspi (Option A / #13974) so the core is self-contained when the
  * barebones transport top is not part of the build. */
case class QspiToken() extends Bundle {
  val isHeader = Bool()
  val opcode   = Bits(8 bits)
  val addr     = UInt(24 bits)
  val len      = UInt(16 bits)
  val word     = Bits(16 bits)
}

/** QspiTransportCore — the simmable domain-split transport core (no vendor IO
  * primitives). Wrapped by TopTang20kQspi with GowinIobuf tri-state + LEDs.
  *
  * [SCLK, CS#-reset]  QspiSlaveSync capture + SCLK-side read responder (magic v1).
  * [CDC token FIFO]   StreamFifoCC header+payload writes SCLK -> clk_sys, PUSH on
  *                    GLOBAL reset (survives CS# deassert).
  * [clk_sys]          QspiDecoder + regBus + SDRAM bridge outputs.
  */
case class QspiTransportCore(fifoDepth: Int = 512, dummyCycles: Int = 2, hdrParity: Boolean = false) extends Component {
  val io = new Bundle {
    val clk   = in  Bool()                 // continuous system clock
    val sclk  = in  Bool()                 // QSPI clock (gated)
    val csn   = in  Bool()
    val ioIn  = in  Bits(4 bits)
    val ioOut = out Bits(4 bits)
    val ioOe  = out Bool()
    // downstream observation (clk_sys domain)
    val regBus         = out(Mode0RegBus())
    val sdramByteOut   = out Bits(8 bits)
    val sdramByteValid = out Bool()
    // #13888 — word-granular SDRAM_WRITE egress (the drain-fix path). VdpTop-184 wires
    // this to the SDRAM bridge; the bring-up top lights everSdram off sdramWordValid.
    val sdramWordOut   = out Bits(16 bits)
    val sdramWordValid = out Bool()
    val sdramHeaderValid = out Bool()
    // Option A (#13974) — header fields the byte-granular QspiSdramBridge samples on
    // sdramHeaderValid (the barebones bring-up top never wired a real bridge, so these
    // were not surfaced). Sourced from the internal decoder.
    val sdramAddrInit  = out UInt(23 bits)
    val sdramLenBytes  = out UInt(17 bits)
    val overflow       = out Bool()        // sticky: token FIFO overflowed (should never fire post-drain-fix)
    val malformed      = out Bool()        // sticky: a header arrived with a dangling half-word (odd payload)
    val hdrErr         = out Bool()        // sticky: a header parity mismatch was seen (hdrParity only)
    // HAM6-2bpp #14246: 32-bit SDRAM debug readback word (armed via TopTang regs
    // 0x0326/0x0327, one-shot read in the sdram domain), surfaced over READ_STATUS sel=8.
    // Quasi-static in the clk_sys domain (armed once → one-shot read completes → then read
    // via sel=8), so a 2FF BufferCC into the SCLK responder is safe — same justification as
    // the sel=9 loopback. Lets the host split QSPI-upload corruption from downstream defects.
    val debug_sdram_data = in Bits(32 bits)
  }

  val sysCd = ClockDomain(clock = io.clk, config = ClockDomainConfig(resetKind = BOOT))

  val slave = QspiSlaveSync(dummyCycles = dummyCycles, hdrParity = hdrParity)
  slave.io.sclk := io.sclk
  slave.io.csn  := io.csn
  slave.io.ioIn := io.ioIn
  io.ioOut := slave.io.ioOut
  io.ioOe  := slave.io.ioOe

  // SCLK domain with GLOBAL (BOOT) reset — used by the FIFO push side (pointers must
  // survive CS# deassert) and the loopback status sync (persists across transactions).
  val sclkGlobalCd = ClockDomain(clock = io.sclk, config = ClockDomainConfig(resetKind = BOOT))

  val sys = new ClockingArea(sysCd) {
    val dec = QspiDecoder()
    dec.io.status_sticky    := B(0, 16 bits)
    dec.io.live_mode        := U(0, 4 bits)
    dec.io.debug_sdram_data := B(0, 32 bits)
    dec.io.upload_busy      := False
    dec.io.upload_done      := False
    dec.io.upload_error     := False
    dec.io.upload_overflow  := False
    dec.io.tx_byte_sent     := False
    // loopback latch: last register write, so the host can verify write->read (the full
    // SCLK->CDC->clk_sys->decoder path) by reading it back via READ_STATUS sel=9.
    val lastRegAddr = Reg(UInt(16 bits)) init 0
    val lastRegData = Reg(Bits(16 bits)) init 0
    when(dec.io.regBus.enable) { lastRegAddr := dec.io.regBus.addr.resize(16 bits); lastRegData := dec.io.regBus.data }
  }

  // SCLK-side read responder. Reads answered locally (no CDC round-trip): sel=0 magic,
  // sel=9 loopback (last reg write, crossed clk_sys->SCLK via BufferCC — static between
  // writes so 2FF sync is safe). Combinational off slave.cmdAddr (stable after cmdValid).
  slave.io.hdrErr.addTag(crossClockDomain)
  val loop = new ClockingArea(sclkGlobalCd) {
    val lastDataCC = BufferCC(sys.lastRegData, B(0, 16 bits))
    val lastAddrCC = BufferCC(sys.lastRegAddr, U(0, 16 bits))
    // sel=8 SDRAM readback: quasi-static debug word (armed via 0x0326/0x0327), 2FF-synced
    // into the SCLK responder — same static-value CDC justification as the loopback above.
    val dbgSdramCC = BufferCC(io.debug_sdram_data, B(0, 32 bits))
    // header parity error: sticky flag + running count (survive CS# on the global reset)
    val hdrErrSticky = Reg(Bool()) init False
    val hdrErrCount  = Reg(UInt(16 bits)) init 0
    when(slave.io.hdrErr) { hdrErrSticky := True; hdrErrCount := hdrErrCount + 1 }
  }
  // Read-responder switch is defined AFTER `push` (below) so sel=10 can surface the
  // token-FIFO overflow + malformed-length sticky flags, which live in the push area.

  val fifo = StreamFifoCC(QspiToken(), depth = fifoDepth, pushClock = sclkGlobalCd, popClock = sysCd)

  slave.io.cmdValid.addTag(crossClockDomain)
  slave.io.payloadValid.addTag(crossClockDomain)
  slave.io.cmdOpcode.addTag(crossClockDomain)
  slave.io.cmdAddr.addTag(crossClockDomain)
  slave.io.cmdLen.addTag(crossClockDomain)
  slave.io.payloadByte.addTag(crossClockDomain)

  val push = new ClockingArea(sclkGlobalCd) {
    // #13888 structural drain fix — SCLK-side 2-byte word assembler. Pack consecutive
    // payload bytes into a 16-bit word (hi ## lo) and push ONE word token per two bytes.
    // This halves the FIFO push rate (40->20 Mtok/s at 80 MHz quad) so the 27 MHz
    // word-rate pop (27 Mword/s) strictly outpaces it and the FIFO can never overflow.
    // State lives on the GLOBAL (BOOT) reset so a word straddling CS# deassert is not
    // lost; a NEW header flushes stale state (a dangling half-byte from an odd/malformed
    // payload is DISCARDED — never committed as a half-word write — and flagged sticky).
    val loByte    = Reg(Bits(8 bits)) init 0
    val haveLo    = Reg(Bool()) init False
    val malformed = Reg(Bool()) init False

    val wordComplete = slave.io.payloadValid && haveLo             // 2nd byte -> emit word
    val assembled    = slave.io.payloadByte ## loByte             // word = hi(2nd) ## lo(1st)

    when(slave.io.cmdValid) {
      when(haveLo) { malformed := True }                          // prior txn left a half-word
      haveLo := False                                             // flush/discard on new header
    } elsewhen(slave.io.payloadValid) {
      when(haveLo) {
        haveLo := False                                           // completed a word this cycle
      } otherwise {
        loByte := slave.io.payloadByte; haveLo := True            // latch low byte
      }
    }

    val tok = QspiToken()
    tok.isHeader := slave.io.cmdValid
    tok.opcode   := slave.io.cmdOpcode
    tok.addr     := slave.io.cmdAddr
    tok.len      := slave.io.cmdLen
    tok.word     := assembled
    // Push on a header OR a completed payload word (never a lone byte).
    fifo.io.push.valid   := slave.io.cmdValid || wordComplete
    fifo.io.push.payload := tok
    val overflow = Reg(Bool()) init False
    when(fifo.io.push.valid && !fifo.io.push.ready) { overflow := True }
  }

  // SCLK-side read responder. Reads answered locally (no CDC round-trip): sel=0 magic,
  // sel=7 header-parity {sticky,count}, sel=9 loopback (last reg write), sel=10 transport
  // health {malformed, overflow}. Combinational off slave.cmdAddr (stable after cmdValid).
  // overflow/malformed are read directly from the `push` area (same sclkGlobalCd domain).
  val sel = slave.io.cmdAddr(7 downto 0)
  val rxWordSel = Bits(32 bits)
  rxWordSel := B(0, 32 bits)
  switch(sel) {
    is(U(0, 8 bits)) { rxWordSel := B"32'h51560002" }                                 // magic
    is(U(7, 8 bits)) { rxWordSel := B(0, 15 bits) ## loop.hdrErrSticky ## loop.hdrErrCount.asBits }  // {sticky, count}
    is(U(8, 8 bits)) { rxWordSel := loop.dbgSdramCC }                                  // SDRAM debug readback (#14246; armed via 0x0326/0x0327)
    is(U(9, 8 bits)) { rxWordSel := loop.lastDataCC ## loop.lastAddrCC.asBits }        // loopback {data,addr}
    is(U(10, 8 bits)){ rxWordSel := B(0, 30 bits) ## push.malformed ## push.overflow } // transport health
    default          { rxWordSel := B(0, 32 bits) }
  }
  slave.io.rxWord := rxWordSel

  val pop = new ClockingArea(sysCd) {
    val t    = fifo.io.pop.payload
    // Option A (#13974) — VdpTop integration feeds the byte-granular QspiDecoder byte
    // path + the byte-addressed QspiSdramBridge (no word-capable bridge exists in-tree).
    // Unpack each popped payload word into two byte pulses (lo then hi) and HOLD the FIFO
    // token across both cycles so nothing is dropped (real backpressure, unlike a naive
    // fire-and-forget word->byte splitter). The FIFO still carries WORD tokens, so the
    // #13888 half-rate-push anti-overflow property is preserved. Headers still pop in one
    // cycle. Reg-write word assembly happens inside the decoder from these two bytes.
    val hiPhase   = Reg(Bool()) init False    // False = emit lo byte, True = emit hi byte
    val isPayload = fifo.io.pop.valid && !t.isHeader
    // Pop the token on a header (1 cycle) or after the hi byte of a payload word.
    fifo.io.pop.ready := Mux(isPayload, hiPhase, fifo.io.pop.valid)
    val fire = fifo.io.pop.valid
    sys.dec.io.cmd_valid     := fire && t.isHeader
    sys.dec.io.cmd_opcode    := t.opcode
    sys.dec.io.cmd_addr      := t.addr
    sys.dec.io.cmd_len       := t.len
    // Byte path: word = hi ## lo (assembled SCLK-side), so emit t.word[7:0] (host's
    // 1st byte) then t.word[15:8]; the decoder reassembles word = 2nd ## 1st = t.word.
    sys.dec.io.payload_valid := isPayload
    sys.dec.io.payload_byte  := Mux(hiPhase, t.word(15 downto 8), t.word(7 downto 0))
    when(isPayload) { hiPhase := !hiPhase }
    // Word path unused in the byte-bridge integration — tie off.
    sys.dec.io.payload_word       := B(0, 16 bits)
    sys.dec.io.payload_word_valid := False
    val overflowCC  = BufferCC(push.overflow, False)
    val malformedCC = BufferCC(push.malformed, False)
  }

  io.regBus           := sys.dec.io.regBus
  io.sdramByteOut     := sys.dec.io.sdramByteOut
  io.sdramByteValid   := sys.dec.io.sdramByteValid
  io.sdramWordOut     := sys.dec.io.sdramWordOut
  io.sdramWordValid   := sys.dec.io.sdramWordValid
  io.sdramHeaderValid := sys.dec.io.sdramHeaderValid
  io.sdramAddrInit    := sys.dec.io.sdramAddrInit
  io.sdramLenBytes    := sys.dec.io.sdramLenBytes
  io.overflow         := pop.overflowCC
  io.malformed        := pop.malformedCC
  io.hdrErr           := loop.hdrErrSticky
}
```

## File: hw/spinal/spinalhdlvdp/QspiSlaveSync.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** QspiSlaveSync — true SCLK-domain synchronous QSPI slave front-end for the P4↔Tang
  * transport (QSPI pivot, contract QSPI_LINK_CONTRACT.md).
  *
  * Replaces the retired oversampled QspiSlave. ALL logic here is clocked by the
  * external SCLK; CS# (active-low) is the domain reset — the FSM restarts at bit 0
  * every transaction, and the reset release is naturally synchronized by the master's
  * CS#-to-first-SCLK setup (`cs_ena_pretrans`). Downstream feeds the EXISTING
  * `QspiDecoder` byte contract unchanged.
  *
  * Framing (phase-based 1-1-4, locked #13765; P4 has no SOC_SPI_HD_BOTH_INOUT):
  *   CS#↓ → CMD(8, IO0, MSB-first) → ADDR(40, IO0, MSB-first = {ADDR[23:0],LEN[15:0]})
  *        → WRITE: QIO-TX payload (IO0-3, high-nibble-first) → CS#↑
  *        → READ : DUMMY(N) → QIO-RX (FPGA drives IO0-3, high-nibble-first) → CS#↑
  *
  * Read data: the whole 32-bit status word is taken as `io.rxWord` (decoder computes
  * it combinationally from sel=addr[7:0] at cmdValid) and shifted out locally — no
  * byte-at-a-time handshake race. Byte order matches the decoder: byte0 (LSB) first,
  * high nibble first within each byte.
  *
  * v1 drives read outputs registered on the SCLK RISING edge — fine at 20 MHz (50 ns).
  * TODO(40 MHz): move the output register to the FALLING edge for a half-cycle of Tco
  * margin. Contention-safe: `ioOe` asserts ONLY in the RDATA phase (after the dummy
  * turnaround window), never while the master could still be driving.
  */
case class QspiSlaveSync(dummyCycles: Int = 2, extSclkCd: ClockDomain = null, hdrParity: Boolean = false) extends Component {
  require(dummyCycles >= 1, "need >=1 dummy cycle for read turnaround")
  val io = new Bundle {
    // ---- QSPI pads (SCLK domain derived internally) ----
    val sclk  = in  Bool()
    val csn   = in  Bool()               // active-low chip select
    val ioIn  = in  Bits(4 bits)         // captured pad inputs (IOBUF .O)
    val ioOut = out Bits(4 bits)         // to IOBUF .I
    val ioOe  = out Bool()               // drive shared bus when high (IOBUF !OEN)

    // ---- header out (feeds QspiDecoder cmd_* contract) ----
    val cmdOpcode = out Bits(8 bits)
    val cmdAddr   = out UInt(24 bits)
    val cmdLen    = out UInt(16 bits)
    val cmdValid  = out Bool()

    // ---- write payload out (feeds QspiDecoder payload_* contract) ----
    val payloadByte  = out Bits(8 bits)
    val payloadValid = out Bool()

    // ---- read data in: the 32-bit READ_STATUS word (decoder rxWord, sel-selected) ----
    val rxWord = in Bits(32 bits)

    val active = out Bool()               // CS# asserted (in a transaction)

    // ---- header integrity: pulse at header-complete when ADDR[23] parity mismatches
    // (only meaningful when hdrParity=true; else held False) ----
    val hdrErr = out Bool()
  }

  // ===========================================================================
  // SCLK clock domain. Reset = CS# HIGH (idle) → every transaction starts fresh
  // at bit 0. Sample on rising edge (SPI mode 0). CS#-low is stable before the
  // first SCLK edge (master cs_ena_pretrans), so async reset release is clean.
  // ===========================================================================
  val sclkCd = if (extSclkCd != null) extSclkCd else ClockDomain(
    clock = io.sclk,
    reset = io.csn,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  object Phase extends SpinalEnum { val CMD, ADDR, LENCAP, WDATA, DUMMY, RDATA = newElement() }

  // nibble k of the 32-bit read word: byte0 first, high nibble first (flash convention)
  def rdNibble(word: Bits, k: UInt): Bits = {
    val byteSel = (k >> 1).resize(2 bits)  // 0..3
    val hi      = !k(0)                     // even k = high nibble
    val b       = word.subdivideIn(8 bits)(byteSel)
    hi ? b(7 downto 4) | b(3 downto 0)
  }

  val area = new ClockingArea(sclkCd) {
    val phase = Reg(Phase()) init Phase.CMD
    val bitc  = Reg(UInt(6 bits)) init 0

    val cmdSh  = Reg(Bits(8 bits))  init 0
    val addrSh = Reg(Bits(24 bits)) init 0     // P4 caps address phase at 32b; we use 24

    val opcodeR = Reg(Bits(8 bits))  init 0
    val addrR   = Reg(UInt(24 bits)) init 0
    val lenR    = Reg(UInt(16 bits)) init 0
    val cmdValidR = Reg(Bool()) init False
    val hdrErrR   = Reg(Bool()) init False     // header parity mismatch pulse (hdrParity only)
    // LEN capture (writes): the P4 can't carry 40-bit address, so LEN arrives as the
    // first 2 quad-data bytes (LEN_lo, LEN_hi) after the 24-bit ADDR phase.
    val lenLo      = Reg(Bits(8 bits)) init 0
    val lenByteCnt = Reg(UInt(1 bits)) init 0

    // write nibble assembly (high nibble first). payload is COMBINATIONAL so the
    // decoder commits the byte ON the low-nibble edge — no trailing SCLK edge
    // needed (the last write byte would otherwise be lost when CS# deasserts).
    val nibHigh   = Reg(Bool()) init True
    val hiNib     = Reg(Bits(4 bits)) init 0

    // read shift-out. Output is launched on the FALLING edge (outArea below) — a
    // mode-0 master samples MISO on the RISING edge, so a rising-edge launch races
    // the sample (CoralReef review #13772). rdWord/rdNib/phase are read by the
    // falling-edge area (same SCLK, opposite edge = half-cycle path).
    val rdWord   = Reg(Bits(32 bits)) init 0
    val rdNib    = Reg(UInt(4 bits)) init 0     // 0..7 nibbles = 4 bytes
    rdWord.addTag(crossClockDomain)
    rdNib.addTag(crossClockDomain)
    phase.addTag(crossClockDomain)

    cmdValidR := False
    hdrErrR   := False

    switch(phase) {
      is(Phase.CMD) {
        cmdSh := cmdSh(6 downto 0) ## io.ioIn(0)
        when(bitc === 7) { bitc := 0; phase := Phase.ADDR } otherwise { bitc := bitc + 1 }
      }
      is(Phase.ADDR) {
        val a24 = addrSh(22 downto 0) ## io.ioIn(0)   // full 24 bits on the last bit
        addrSh := a24
        when(bitc === 23) {
          bitc    := 0
          opcodeR := cmdSh
          addrR   := a24.asUInt
          // header parity: bit 23 = even parity over {opcode, addr[22:0]}
          if (hdrParity) { hdrErrR := a24(23) =/= (cmdSh.xorR ^ a24(22 downto 0).xorR) }
          when(cmdSh === B"8'h04") {           // READ_STATUS → no LEN, decode now
            lenR      := 0
            cmdValidR := True
            phase     := Phase.DUMMY
          } otherwise {                        // WRITE → LEN comes as first 2 data bytes
            phase      := Phase.LENCAP
            nibHigh    := True
            lenByteCnt := 0
          }
        } otherwise { bitc := bitc + 1 }
      }
      is(Phase.LENCAP) {
        // quad TX-in: grab 2 bytes (LEN_lo, LEN_hi), then decode + go to payload
        when(nibHigh) { hiNib := io.ioIn; nibHigh := False } otherwise {
          val b = hiNib ## io.ioIn
          nibHigh := True
          when(lenByteCnt === 0) { lenLo := b; lenByteCnt := 1 } otherwise {
            lenR      := (b ## lenLo).asUInt   // {LEN_hi, LEN_lo}
            cmdValidR := True
            phase     := Phase.WDATA
          }
        }
      }
      is(Phase.WDATA) {
        // quad TX-in: capture high nibble, toggle. The byte + valid are driven
        // combinationally below so the decoder consumes on the low-nibble edge.
        when(nibHigh) { hiNib := io.ioIn; nibHigh := False } otherwise { nibHigh := True }
      }
      is(Phase.DUMMY) {
        when(bitc === (dummyCycles - 1)) {
          bitc   := 0
          phase  := Phase.RDATA
          rdWord := io.rxWord         // stable now (sel decoded 2+ cycles ago)
          rdNib  := 0
        } otherwise { bitc := bitc + 1 }
      }
      is(Phase.RDATA) {
        // advance the nibble pointer each rising edge; the falling-edge area drives
        // the pad. hold the last nibble after 8.
        when(rdNib < 7) { rdNib := rdNib + 1 }
      }
    }
  }

  io.cmdOpcode    := area.opcodeR
  io.cmdAddr      := area.addrR
  io.cmdLen       := area.lenR
  io.cmdValid     := area.cmdValidR
  // combinational payload: valid during the low-nibble cycle; byte = hiNib##ioIn.
  // Consumed by the decoder ON the low-nibble edge (no trailing-edge dependency).
  io.payloadByte  := area.hiNib ## io.ioIn
  io.payloadValid := (area.phase === Phase.WDATA) && !area.nibHigh
  // ===========================================================================
  // FALLING-edge output launch (SPI mode-0 read timing). Presenting read data on
  // the falling edge gives the master a half-cycle of setup before it samples on
  // the next rising edge — no race, no contention (ioOe also asserts on the falling
  // edge after the dummy window). Reads rdWord/rdNib/phase from the rising area.
  // ===========================================================================
  val outCd = ClockDomain(
    clock = io.sclk,
    reset = io.csn,
    config = ClockDomainConfig(clockEdge = FALLING, resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val outArea = new ClockingArea(outCd) {
    val ioOutF = Reg(Bits(4 bits)) init 0
    val ioOeF  = Reg(Bool()) init False
    when(area.phase === Phase.RDATA) {
      ioOutF := rdNibble(area.rdWord, area.rdNib)
      ioOeF  := True
    } otherwise {
      ioOeF := False
    }
  }

  io.ioOut  := outArea.ioOutF
  io.ioOe   := outArea.ioOeF
  io.active := !io.csn
  io.hdrErr := area.hdrErrR
}
```

## File: hw/spinal/spinalhdlvdp/SdramArbiter.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 30 — multi-client SDRAM arbiter.
  *
  * Takes the `FetchSlotScheduler`'s {grant, slotValid, grantClientId}
  * pre-announce/grant signals and routes a single client's SDRAM request
  * (rd, wr, addr, din) to the SDRAM controller on each cycle. Clients
  * whose slot is not currently granted present their requests on their
  * own port, but only the granted client's signals reach the SDRAM.
  *
  * Fan-out: `clientGrant(i)` pulses when the scheduler's grant pulse
  * fires with `grantClientId === i`. `clientSlotValid(i)` is the
  * scheduler's `slotValid` gated by `grantClientId === i` — clients can
  * use it to stall their internal FSM between their assigned windows.
  *
  * Bit-identical guarantee for the Task 30 baseline: with only client 0
  * wired and the current 2-slot schedule (both slots clientId=0), the
  * scheduler's `grantClientId` resolves to 0 on every cycle, so the
  * arbiter's mux output == client(0)'s input exactly.
  */
case class SdramArbiter(
    clientCount: Int = 4,
    addrWidth:   Int = 23,
    dataWidth:   Int = 8,
    refreshPeriodCycles: Int = 593,  // CP-A3: central refresh cadence (593 cyc = 14.64µs @40.5MHz)
    // SDRAM-BURST-REFRESH (P16, #11978). Opt-in: default false keeps the proven
    // distributed cadence. When true, refreshDue is sourced from a
    // BurstRefreshController — suppressed during active video, bursted in vblank
    // (paced; see the single-deep refreshPending constraint). io.vblankActive
    // must be driven with an SDRAM-domain-synced vblank in burst mode.
    burstRefresh:        Boolean = false,
    burstRefreshCount:   Int = 2048,    // rows per vblank (sdram.v = 2048 rows)
    burstPeriodCycles:   Int = 24,      // sdramCd cycles between burst pulses (>= service latency)
    burstWatchdogCycles: Int = 1350000  // ~2 frames @40.5MHz failsafe (< 64ms tREF = 2.59M cyc)
) extends Component {
  require(clientCount >= 1, "clientCount ≥ 1")

  val idBits = if (clientCount > 1) log2Up(clientCount) else 1

  val io = new Bundle {
    val grantClientId = in UInt(idBits bits)
    val slotValid     = in Bool()
    val grant         = in Bool()

    // Per-client request bundles.
    val clientRd   = in Vec(Bool(), clientCount)
    val clientWr   = in Vec(Bool(), clientCount)
    val clientAddr = in Vec(UInt(addrWidth bits), clientCount)
    val clientDin  = in Vec(Bits(dataWidth bits), clientCount)
    // RGB565-FULLFRAME-132: per-client SDRAM read burst length (words). Muxed by
    // grantClientId exactly like clientAddr. 0/1 = legacy single read. The bitmap
    // directcolor client drives 8; all other clients drive 1. Undriven (0) on a
    // standalone-DUT compile is treated as a single read by sdram.v.
    val clientBurstLen = in Vec(UInt(4 bits), clientCount)

    // Per-client grant / slot-valid fan-out.
    val clientGrant     = out Vec(Bool(), clientCount)
    val clientSlotValid = out Vec(Bool(), clientCount)

    // Arbitrated SDRAM request going to the controller.
    val sdramRd   = out Bool()
    val sdramWr   = out Bool()
    val sdramAddr = out UInt(addrWidth bits)
    val sdramDin  = out Bits(dataWidth bits)
    val sdramBurstLen = out UInt(4 bits)   // RGB565-FULLFRAME-132: granted client's burst length

    // CP-A3 (Phase A #11438/#11439, Option B): central refresh cadence. The arbiter
    // owns the single refresh timer (Priority-0 accounting); `refreshDue` pulses one
    // cycle every refreshPeriodCycles. Fetch engines consume it (replacing their own
    // per-engine timers) and insert the AUTO_REFRESH at their next safe point — one
    // timer, no per-engine drift, both layers on the same cadence.
    val refreshDue = out Bool()
    // SDRAM-BURST-REFRESH: vblank flag (SDRAM-domain synced). Only consumed when
    // burstRefresh=true; harmless/unused in the default distributed mode.
    val vblankActive = in Bool()
  }

  if (!burstRefresh) {
    // Central refresh timer (default distributed cadence — unchanged).
    val refreshTimer = Reg(UInt(log2Up(refreshPeriodCycles) bits)) init 0
    val refreshDueR  = Reg(Bool()) init False
    refreshDueR := False
    when(refreshTimer === U(refreshPeriodCycles - 1, log2Up(refreshPeriodCycles) bits)) {
      refreshTimer := 0
      refreshDueR  := True
    } otherwise {
      refreshTimer := refreshTimer + 1
    }
    io.refreshDue := refreshDueR
  } else {
    // Vblank burst refresh (opt-in). Suppressed in active video, bursted in vblank.
    val burstCtrl = BurstRefreshController(
      burstCount     = burstRefreshCount,
      periodCycles   = burstPeriodCycles,
      watchdogCycles = burstWatchdogCycles)
    burstCtrl.io.vblankActive := io.vblankActive
    io.refreshDue := burstCtrl.io.refreshDue
  }

  // Per-client fan-out — scheduler grants one client per cycle.
  for (i <- 0 until clientCount) {
    val selected = io.grantClientId === U(i, idBits bits)
    io.clientGrant(i)     := io.grant     && selected
    io.clientSlotValid(i) := io.slotValid && selected
  }

  // Mux client signals to SDRAM based on grantClientId.
  io.sdramRd       := io.clientRd(io.grantClientId)
  io.sdramWr       := io.clientWr(io.grantClientId)
  io.sdramAddr     := io.clientAddr(io.grantClientId)
  io.sdramDin      := io.clientDin(io.grantClientId)
  io.sdramBurstLen := io.clientBurstLen(io.grantClientId)
}
```

## File: hw/spinal/spinalhdlvdp/Indexed2bppFrameCoSim.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** HAM6-shelve #14227 — 2bpp indexed display bring-up co-sim.
  *
  * The bench shows a fully black HDMI frame (only the always-on cyan canary) even
  * though serial proof PASSes: transport + upload work but the 2bpp bitmap does not
  * composite. Receiver-lock is refuted (the canary is a clean RTL overlay). This sim
  * drives BronzeGate's EXACT indexed2 register sequence (main.c:472-497) through the
  * REAL BitmapRowFetch + VdpTop compositor to determine whether the RTL 2bpp indexed
  * DISPLAY path produces non-black pixels — a mode that was never content-sim'd
  * end-to-end before the hardware handoff.
  *
  * Stimulus: a UNIFORM value-1 2bpp bitmap (byte 0x55 = 0b01_01_01_01, four pixels of
  * value 1) + an IDENTITY attribute plane (byte 0xE4 = slot0..3 = 0,1,2,3). With the
  * default palette (legacyPalette[1] = white), a WORKING 2bpp path drives the whole
  * active area to palette[1] = white; a broken path leaves it at palette[0] (black).
  *
  * We sample `bgOrDirectRgb` (= paletteRgb for indexed) during DE and count black vs
  * non-black, once WITH `MODE_SELECT 0x0313=0` (BronzeGate's exact seq) and once
  * WITHOUT (to test whether MODE_SELECT=0 suppresses L0).
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppFrameCoSim"
  */
object Indexed2bppFrameCoSim {
  val SrcH      = 240
  val RowStride = 128           // indexed hardwired 128-byte row stride (BitmapRowFetch.scala:270, lineReg<<7)
  val BitmapBase = 0x100000     // matches firmware main.c 0x0351/0x0352
  val AttrBase   = 0x110000     // matches firmware main.c 0x0353/0x0354

  class Dut extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    val video = VdpTop(enableL1Fetch = false)
    val fetch = BitmapRowFetch(sdramCd, skipSdramInit = true)

    val io = new Bundle {
      val regBusAddr = in UInt (15 bits); val regBusData = in Bits (16 bits); val regBusEnable = in Bool()
      val sdramAddr = out UInt (23 bits); val sdramRd = out Bool(); val sdramWr = out Bool()
      val sdramBurstLen = out UInt (4 bits)
      val sdramDout = in Bits (8 bits); val sdramDout32 = in Bits (32 bits)
      val sdramDataReady = in Bool(); val sdramBusy = in Bool()
      val bootDone = out Bool()
      val x = out UInt (10 bits); val y = out UInt (10 bits); val de = out Bool()
      val probeBmByte = out Bits(8 bits); val probeAttrByte = out Bits(8 bits)
    }
    video.io.regBus.addr := io.regBusAddr; video.io.regBus.data := io.regBusData; video.io.regBus.enable := io.regBusEnable

    fetch.io.col          := video.io.bitmapSdramCol
    fetch.io.fetchGrant   := video.io.bitmapSdramFetchGrant
    fetch.io.fetchLine    := video.io.bitmapSdramFetchLine
    fetch.io.enable       := video.io.bitmapModeActive
    fetch.io.directColor  := video.io.bitmapDirectColor
    fetch.io.tileBootDone := True
    fetch.io.bitmapBase   := video.io.bitmapBase
    fetch.io.attrBase     := video.io.attrBase
    fetch.io.bitmapStride := video.io.bitmapStride
    fetch.io.attrStride   := video.io.attrStride
    fetch.io.bitmapHeight := video.io.bitmapHeight
    video.io.bitmapSdramByte     := fetch.io.bitmapByte
    video.io.bitmapSdramAttrByte := fetch.io.attrByte

    io.sdramAddr := fetch.io.sdramAddr; io.sdramRd := fetch.io.sdramRd; io.sdramWr := fetch.io.sdramWr
    io.sdramBurstLen := fetch.io.sdramBurstLen
    fetch.io.sdramDout := io.sdramDout; fetch.io.sdramDout32 := io.sdramDout32
    fetch.io.sdramDataReady := io.sdramDataReady; fetch.io.sdramBusy := io.sdramBusy
    io.bootDone := fetch.io.bootDone
    io.x := video.io.x; io.y := video.io.y; io.de := video.io.de
    io.probeBmByte := fetch.io.bitmapByte; io.probeAttrByte := fetch.io.attrByte

    video.io.layer0ScrollX := 0; video.io.layer0ScrollY := 0
    video.io.layer1ScrollX := 0; video.io.layer1ScrollY := 0
    video.io.layer2ScrollX := 0; video.io.layer2ScrollY := 0
    video.io.layer3ScrollX := 0; video.io.layer3ScrollY := 0
    video.io.sprite0X := 1000; video.io.sprite0Y := 1000; video.io.sprite0Enabled := False; video.io.sprite0PatternIdx := 0
    video.io.sprite1X := 1000; video.io.sprite1Y := 1000; video.io.sprite1Enabled := False; video.io.sprite1PatternIdx := 1
    video.io.sprite2X := 1000; video.io.sprite2Y := 1000; video.io.sprite2Enabled := False; video.io.sprite2PatternIdx := 0
    video.io.sprite3X := 1000; video.io.sprite3Y := 1000; video.io.sprite3Enabled := False; video.io.sprite3PatternIdx := 1
    video.io.layer0TestPatternSelect := 0; video.io.layer0TestPatternEnable := False
    video.io.layer0UseSdram := False; video.io.layer0SdramPixel := 0
    video.io.layer0SdramBank := 0; video.io.layer0SdramPriority := False
    video.io.layer1UseSdram := False; video.io.layer1SdramPixel := 0
    video.io.layer1SdramBank := 0; video.io.layer1SdramPriority := False
    video.io.rasterTriggerLine := 0; video.io.rasterTriggerPixel := 0
    video.io.rasterTriggerPxEnable := False; video.io.rasterTriggerEnable := False; video.io.rasterTriggerClear := False
    video.io.statusEvQspiReady := False; video.io.statusEvQspiError := False
    video.io.planarSdramBusy := False; video.io.planarSdramDataReady := False; video.io.planarSdramDout32 := 0
  }

  def runOne(writeMode0: Boolean): (Long, Long) = {
    var black = 0L; var nonBlack = 0L
    SimConfig.compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10); dut.sdramCd.forkStimulus(10)

      // Vertical-bar pattern IDENTICAL on every row: bitmap bytes [0..40)=0x55 (pixel value 1),
      // [40..80)=0xAA (pixel value 2) → one vertical boundary at source px 160 (~disp col 320).
      // Attr 0xE4 (identity). Since every source row is identical, ANY per-row horizontal drift
      // of that boundary in the composited output = a real RTL shear (the shimmer under test).
      val mem = mutable.HashMap[Int, Int]()
      for (row <- 0 until SrcH; b <- 0 until RowStride) {
        mem((BitmapBase + row * RowStride + b) & 0x7fffff) = (if (b < 40) 0x55 else 0xAA)
        mem((AttrBase   + row * RowStride + b) & 0x7fffff) = 0xE4
      }
      def rb(a: Int) = mem.getOrElse(a & 0x7fffff, 0)
      def rw(a: Int): Long = { val b = a & ~3
        (rb(b) & 0xFFL) | ((rb(b+1) & 0xFFL) << 8) | ((rb(b+2) & 0xFFL) << 16) | ((rb(b+3) & 0xFFL) << 24) }

      dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= true
      dut.io.regBusAddr #= 0; dut.io.regBusData #= 0; dut.io.regBusEnable #= false

      // Reactive SDRAM model — same as DirectColorFrameCoSim: 5-cycle latency, burst out.
      fork {
        for (_ <- 0 until 30) dut.sdramCd.waitSampling()
        dut.io.sdramBusy #= false
        while (true) {
          if (dut.io.sdramRd.toBoolean) {
            val a = dut.io.sdramAddr.toInt; val n = math.max(1, dut.io.sdramBurstLen.toInt)
            dut.sdramCd.waitSampling(5)
            for (k <- 0 until n) {
              dut.io.sdramDout #= rb(a + k*4) & 0xFF
              dut.io.sdramDout32 #= BigInt(rw(a + k*4) & 0xFFFFFFFFL)
              dut.io.sdramDataReady #= true; dut.sdramCd.waitSampling()
            }
            dut.io.sdramDataReady #= false
          } else dut.sdramCd.waitSampling()
        }
      }

      def writeReg(a: Int, d: Int): Unit = {
        dut.io.regBusAddr #= a; dut.io.regBusData #= d; dut.io.regBusEnable #= true
        dut.clockDomain.waitSampling(); dut.io.regBusEnable #= false; dut.clockDomain.waitSampling()
      }

      // BronzeGate's indexed2 sequence (firmware/esp32p4_qspi_proof/main/main.c:472-497).
      writeReg(0x0300, 0x0000)                       // disable layers while loading
      if (writeMode0) writeReg(0x0313, 0x0000)       // MODE_SELECT native Mode0 (the lead under test)
      writeReg(0x0351, BitmapBase & 0xFFFF);  writeReg(0x0352, (BitmapBase >> 16) & 0x7F)
      writeReg(0x0353, AttrBase   & 0xFFFF);  writeReg(0x0354, (AttrBase   >> 16) & 0x7F)
      writeReg(0x0355, RowStride);            writeReg(0x0356, RowStride)
      writeReg(0x0357, SrcH)
      // Per-line LINESTATE L0-enable (addr=line 0..479, data bit[11]=layer0Enable). Without
      // this, linestate.layer0Enable=0 -> effectiveL0Enable=0 -> L0 forced transparent -> black.
      // THIS is the step missing from BronzeGate's firmware sequence.
      for (line <- 0 until 480) writeReg(line, 0x0800)
      writeReg(0x0350, 0x0003)                       // enable + bpp=0b01 (2bpp indexed)
      writeReg(0x0300, 0x0001)                       // LAYER_ENABLE = L0

      var t = 200000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
      println(s"[sim] mode0write=$writeMode0 bootDone=${dut.io.bootDone.toBoolean}")
      dut.clockDomain.waitSampling(800 * 525 * 3)

      // Capture one composited frame, then locate the bar boundary per row.
      val gotFrame = Array.fill(480, 640)(-1)
      val sampleCycles = 800 * 525 * 2
      for (_ <- 0 until sampleCycles) {
        if (dut.io.de.toBoolean) {
          val dx = dut.io.x.toInt; val dy = dut.io.y.toInt
          if (dx < 640 && dy < 480) gotFrame(dy)(dx) = dut.video.bgOrDirectRgb.toInt & 0xFFFFFF
        }
        dut.clockDomain.waitSampling()
      }
      // Per row: leftmost column whose colour differs from column 0 (= the value-1→2 boundary).
      // Identical source rows ⇒ a constant boundary column; a spread ⇒ real RTL horizontal shear.
      val trans = mutable.ArrayBuffer[Int]()
      var nonBlackRows = 0L
      for (dy <- 0 until 480) {
        val c0 = gotFrame(dy)(0)
        if (gotFrame(dy)(320) != 0x000000) nonBlackRows += 1
        var col = -1; var dx = 1
        while (dx < 640 && col < 0) { val g = gotFrame(dy)(dx); if (g >= 0 && g != c0) col = dx; dx += 1 }
        if (col >= 0) trans += col
      }
      if (trans.nonEmpty) {
        val srt = trans.toSeq.sorted; val mn = srt.head; val mx = srt.last; val md = srt(srt.size/2)
        nonBlack = trans.size.toLong; black = (mx - mn).toLong   // black repurposed = shear span (px)
        println(f"[sim] mode0write=$writeMode0: bar-boundary col over ${trans.size} rows: min=$mn max=$mx median=$md SHEAR_SPAN=${mx-mn}px (nonBlackRows=$nonBlackRows)")
      } else { nonBlack = 0; black = -1; println(f"[sim] mode0write=$writeMode0: NO transition found (uniform/black frame)") }
    }
    (nonBlack, black)
  }

  def main(args: Array[String]): Unit = {
    println("=== Indexed2bppFrameCoSim: vertical-bar 2bpp → per-row boundary-drift (shear) test ===")
    val (rows1, span1) = runOne(writeMode0 = true)   // WITH 0x0313=0 (BronzeGate's exact sequence)
    val (rows2, span2) = runOne(writeMode0 = false)  // WITHOUT 0x0313
    println(f"[sim] WITH 0x0313=0:  rows-with-boundary=$rows1 SHEAR_SPAN=$span1 px")
    println(f"[sim] WITHOUT 0x0313: rows-with-boundary=$rows2 SHEAR_SPAN=$span2 px")
    if (rows1 < 100)
      println(f"[sim] Indexed2bppFrameCoSim: FAIL — bars not rendering (rows=$rows1); linestate/compositing regression.")
    else if (span1 <= 6)
      println(f"[sim] Indexed2bppFrameCoSim: bars render + boundary STABLE (shear span=$span1 px) in idealized-SDRAM sim ⇒ the bench banding is REAL-SDRAM-TIMING (fetch/bank cadence under refresh/bank-conflict), NOT a logic addressing bug.")
    else
      println(f"[sim] Indexed2bppFrameCoSim: SHEAR REPRODUCED in sim (span=$span1 px) ⇒ a LOGIC addressing/bank bug in the indexed fetch/line-buffer, independent of SDRAM timing — drill into fetchBank/lineReg.")
  }
}
```

## File: hw/spinal/spinalhdlvdp/Indexed2bppBwCosim.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** HAM6-shelve #14235/#14237 follow-on — 2bpp INDEXED fetch timing under the REAL SDRAM IP.
  *
  * Context: BronzeGate's native 720x480 YUYV capture shows the horizontal banding PERSISTS
  * after the 1080p-MJPEG/vertical-scaling is removed, so the shear is real-SDRAM-timing, not
  * capture. `Indexed2bppFrameCoSim` already proved the display LOGIC is correct (boundary
  * bit-stable) under an IDEALIZED SDRAM. This sim closes the loop: it runs the INDEXED fetch
  * (`directColor=false` -> 160 SINGLE-word reads/row, `burstWords=1`) through the REAL
  * `SdramArbiter` + REAL `sdram.v` (`SdramWithModel`) + auto-refresh -- the same harness as
  * `BitmapConcurrentBwCosim` -- and measures per-display-row fetch duration vs the ~1286-cyc
  * per-line budget (800 px * 40.5/25.2). If the indexed fetch exceeds the budget, the fetch
  * falls behind the scanout -> the display reads a partially-filled / stale line buffer ->
  * the observed horizontal banding.
  *
  * Refresh-only (no upload contention): the 2bpp reference is a STATIC image, so the only
  * bus competitor during display is auto-refresh. INDEXED (single reads) is compared to
  * DIRECTCOLOR (bursts) to quantify the single-read penalty.
  *
  * Run: sbt "runMain spinalhdlvdp.Indexed2bppBwCosim"
  */
object Indexed2bppBwCosim extends App {
  val hTotal = 800
  val lineBudgetSdram = scala.math.round(800.0 * 40.5 / 25.2).toInt   // ≈ 1286 SDRAM cyc / display line

  def run(directColorMode: Boolean, label: String): Unit = {
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, uploadMinGap = 0)
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)   // 25.2 MHz pixel
      dut.sdramCd.forkStimulus(period = 10)       // 40.5 MHz sdram
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= 0x100000; dut.io.attrBase #= 0x200000
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= false; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }

      dut.io.enable #= true; dut.io.directColor #= directColorMode; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      assert(t > 0, s"$label: bootDone timeout")
      dut.io.uploadActive #= false   // static reference image ⇒ refresh is the only bus competitor

      val nRows = 80; val warmup = 16
      var sumDur = 0L; var lateRows = 0; var measured = 0; var maxDur = 0L; var firstLate = -1
      for (row <- 0 until nRows) {
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1) { dut.io.fetchLine #= (row + 2); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
        }
        // Measure how many SDRAM cycles fetchActive stays high for this row.
        var guard = 0; var dur = 0L; var seenActive = false
        while (guard < 20000) {
          val act = dut.io.fetchActive.toBoolean
          if (act) { seenActive = true; dur += 1 }
          else if (seenActive) { guard = 999999 }   // fell -> row fetch done
          dut.sdramCd.waitSampling(); guard += 1
        }
        if (row >= warmup) {
          sumDur += dur; measured += 1; if (dur > maxDur) maxDur = dur
          if (dur > lineBudgetSdram) { lateRows += 1; if (firstLate < 0) firstLate = row }
        }
      }
      val avgDur = sumDur.toDouble / measured
      val util = 100.0 * avgDur / lineBudgetSdram
      val onset = if (firstLate < 0) "none" else s"row $firstLate"
      println(f"[sim] $label%-24s avgFetch=$avgDur%6.0f max=$maxDur%6d cyc/row (budget=$lineBudgetSdram, util=$util%.0f%%) lateRows=$lateRows/$measured onset=$onset")
    }
  }

  // ---- DATA-correctness check: preload a known signature, verify the INDEXED single-read
  // fetch reads it byte-perfect through the real sdram.v + arbiter + refresh (no upload
  // contention — the 2bpp reference is static). Splits "fetch/controller corrupts data"
  // (RTL bug) from "SDRAM content is wrong" (upload path). Mirrors BitmapConcurrentBwCosim.runContent
  // but for the indexed geometry (hardwired 128-byte row stride, byte index = col/8).
  def sig(a: Int): Int = ((a ^ (a >> 8) ^ (a >> 16)) & 0xFF)
  def runContent(): Unit = {
    val base = 0x100000; val attrBase = 0x200000; val idxStride = 128
    Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
      .compile {
        val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
        BitmapBwDut(sdramCd, 0)
      }.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 16)
      dut.sdramCd.forkStimulus(period = 10)
      dut.io.col #= 0; dut.io.fetchLine #= 0; dut.io.fetchGrant #= false
      dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
      dut.io.bitmapBase #= base; dut.io.attrBase #= attrBase
      dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240
      dut.io.resetn #= false; dut.io.uploadActive #= false
      dut.io.uploadMode #= true; dut.io.uplWr #= false; dut.io.uplRd #= false; dut.io.uplAddr #= 0; dut.io.uplDin #= 0
      dut.sdramCd.waitSampling(4); dut.io.resetn #= true
      var i = 0
      while (dut.io.ctrlBusy.toBoolean && i < 20000) { dut.sdramCd.waitSampling(); i += 1 }
      def wrByte(addr: Int, data: Int): Unit = {
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
        dut.io.uplAddr #= addr; dut.io.uplDin #= data; dut.io.uplWr #= true
        var g = 20; while (!dut.io.ctrlBusy.toBoolean && g > 0) { dut.sdramCd.waitSampling(); g -= 1 }
        dut.io.uplWr #= false
        while (dut.io.ctrlBusy.toBoolean) dut.sdramCd.waitSampling()
      }
      val nLines = 24
      for (row <- 0 until nLines; j <- 0 until 80) wrByte(base     + row*idxStride + j, sig(base     + row*idxStride + j))
      for (row <- 0 until nLines; j <- 0 until 80) wrByte(attrBase + row*idxStride + j, sig(attrBase + row*idxStride + j))

      dut.io.uploadMode #= false
      dut.sdramCd.waitSampling(4); dut.clockDomain.waitSampling(4)
      dut.io.enable #= true; dut.io.directColor #= false; dut.io.tileBootDone #= true
      var t = 8000
      while (!dut.io.bootDone.toBoolean && t > 0) { dut.sdramCd.waitSampling(); t -= 1 }
      dut.io.uploadActive #= false

      var mism = 0; var attrMism = 0; var checks = 0
      val firsts = scala.collection.mutable.ArrayBuffer[String]()
      val warmup = 8; val nScreen = warmup + 8
      for (screenLine <- 0 until nScreen) {
        val srcRow = screenLine >> 1
        for (h <- 0 until hTotal) {
          dut.io.col #= h
          if (h == 4) dut.io.fetchGrant #= false
          if (h == hTotal - 1 && (screenLine % 2 == 1)) { dut.io.fetchLine #= (screenLine + 5); dut.io.fetchGrant #= true }
          dut.clockDomain.waitSampling()
          if (screenLine >= warmup && h < 640 && (h % 8 == 0)) {
            sleep(1)
            val byteIdx = h / 8
            val got  = dut.io.bitmapByte.toInt & 0xFF; val gotA = dut.io.attrByte.toInt & 0xFF
            val exp  = sig(base     + srcRow*idxStride + byteIdx)
            val expA = sig(attrBase + srcRow*idxStride + byteIdx)
            checks += 1
            if (got  != exp)  { mism     += 1; if (firsts.size < 10) firsts += f"BMP scr=$screenLine srcRow=$srcRow byte=$byteIdx got=0x$got%02X exp=0x$exp%02X" }
            if (gotA != expA) { attrMism += 1 }
          }
        }
      }
      println(f"[sim] INDEXED CONTENT (real sdram.v+refresh): checks=$checks bitmapMismatch=$mism attrMismatch=$attrMism")
      firsts.foreach(m => println(s"[sim]   $m"))
      if (mism == 0 && attrMism == 0)
        println("[sim] INDEXED CONTENT PASS — indexed single-read fetch reads SDRAM byte-perfect under real sdram.v+refresh => fetch/controller data path is CLEAN; the bench speckle is UPLOAD (bad SDRAM content) or downstream, not the read path.")
      else
        println("[sim] INDEXED CONTENT FAIL — the indexed single-read fetch CORRUPTS data under real sdram.v => real RTL/controller data bug (matches the bench speckle).")
    }
  }

  println("=== Indexed2bppBwCosim: INDEXED(single) vs DIRECTCOLOR(burst) fetch timing under REAL sdram.v + arbiter + refresh ===")
  run(directColorMode = false, "INDEXED(2bpp,single)")
  run(directColorMode = true,  "DIRECTCOLOR(burst)")
  println("=== DATA-correctness (the bench artifact is REAL per operator; timing was clean, so check the values) ===")
  runContent()
}
```

## File: hw/spinal/spinalhdlvdp/Qspi2bppReadbackSim.scala

```scala
package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** HAM6-2bpp #14246 — proof that READ_STATUS sel=8 surfaces `debug_sdram_data` through the
  * word-drain `QspiTransportCore` SCLK read responder.
  *
  * Re-enables the SDRAM-content readback the host needs to split QSPI-upload corruption from
  * downstream defects on the 2bpp banding. The read PATH (dummy turnaround + falling-edge
  * nibble launch) is already hardware-proven by the working sel=0 magic and sel=10 health
  * reads; this sim adds the new sel=8 arm and proves it returns the driven word.
  *
  * SELF-VALIDATING: reads sel=0 (magic 0x51560002, known-good) with the SAME helper as sel=8.
  * If magic reconstructs correctly, the read helper is proven, so a correct sel=8 (0xDEADBEEF)
  * is airtight.
  *
  * Run: sbt "runMain spinalhdlvdp.Qspi2bppReadbackSim"
  */
object Qspi2bppReadbackSim extends App {
  Config.sim.compile(QspiTransportCore(fifoDepth = 512, dummyCycles = 2)).doSim { dut =>
    val sysPeriod = 37
    val sclkPeriod = 40

    dut.io.clk #= false
    dut.io.sclk #= false; dut.io.csn #= true; dut.io.ioIn #= 0
    dut.io.debug_sdram_data #= 0

    fork {
      while (true) {
        dut.io.clk #= true;  sleep(sysPeriod / 2)
        dut.io.clk #= false; sleep(sysPeriod - sysPeriod / 2)
      }
    }

    // --- SCLK bit-bang (mode 0): drive on the half-cycle before the rising edge. ---
    def clkRise(): Unit = { dut.io.sclk #= true; sleep(sclkPeriod / 2) }
    def clkFall(): Unit = { dut.io.sclk #= false; sleep(sclkPeriod - sclkPeriod / 2) }
    def sendSingle(v: BigInt, bits: Int): Unit =
      for (i <- (bits - 1) to 0 by -1) { dut.io.ioIn #= (((v >> i) & 1).toInt); sleep(sclkPeriod / 2); clkRise(); clkFall() }
    def startTxn(): Unit = { dut.io.csn #= false; sleep(2 * sclkPeriod) }
    def endTxn():   Unit = { dut.io.sclk #= false; dut.io.csn #= true; sleep(8 * sclkPeriod) }

    /** READ_STATUS(sel): CMD=0x04 (single, MSB-first), 24-bit ADDR whose low byte = sel,
      * dummyCycles turnaround, then 8 nibbles the FPGA launches on FALLING edges (byte0
      * first, high nibble first). Sample ioOut just after each falling edge (post-launch),
      * reconstruct the 32-bit word. */
    def readStatus(sel: Int): BigInt = {
      startTxn()
      sendSingle(0x04, 8)          // CMD
      sendSingle(sel & 0xFF, 24)   // ADDR (low byte = sel)
      // dummy turnaround: the FPGA launches the first RDATA nibble one edge into the
      // dummy window (empirically, sel=0 magic self-check), so pre-clock ONE edge then
      // sample 8 nibbles.
      for (_ <- 0 until 1) { clkRise(); clkFall() }
      val nibs = ArrayBuffer[Int]()
      for (_ <- 0 until 8) {
        clkRise()
        clkFall()
        // FPGA launched this nibble on the falling edge; sample now (stable for the
        // master's next rising edge). Only meaningful while ioOe is asserted.
        if (dut.io.ioOe.toBoolean) nibs += (dut.io.ioOut.toInt & 0xF) else nibs += -1
      }
      endTxn()
      // Reconstruct: nibble k -> byte(k/2), high nibble first. word byte order = LSB..MSB
      // (magic sel=0 is B"32'h51560002", byte0=0x02 emitted first).
      var w = BigInt(0)
      for (byteIdx <- 0 until 4) {
        val hi = nibs(byteIdx * 2); val lo = nibs(byteIdx * 2 + 1)
        val b = ((hi & 0xF) << 4) | (lo & 0xF)
        w = w | (BigInt(b) << (byteIdx * 8))
      }
      println(f"  sel=$sel%-2d nibbles=${nibs.map(n => if (n < 0) "z" else n.toHexString).mkString(",")}  word=0x$w%08X")
      w
    }

    sleep(20 * sclkPeriod)
    println("=== Qspi2bppReadbackSim (sel=8 SDRAM readback via word-drain responder) ===")

    var failures = 0
    def check(c: Boolean, m: String): Unit = { if (!c) { failures += 1; println(s"  [FAIL] $m") } else println(s"  [PASS] $m") }

    // Control: sel=0 magic with the SAME helper (validates the read sampling itself).
    val magic = readStatus(0)
    check(magic == BigInt("51560002", 16), f"sel=0 magic reads 0x51560002 (got 0x$magic%08X) — read helper validated")

    // Under test: drive a known SDRAM debug word, read it back via sel=8.
    val exp1 = BigInt("DEADBEEF", 16)
    dut.io.debug_sdram_data #= exp1
    sleep(20 * sysPeriod)   // let the 2FF BufferCC settle into the SCLK domain
    val got1 = readStatus(8)
    check(got1 == exp1, f"sel=8 returns driven debug_sdram_data 0x$exp1%08X (got 0x$got1%08X)")

    // Second value — proves it tracks the input, not a constant.
    val exp2 = BigInt("0BADF00D", 16)
    dut.io.debug_sdram_data #= exp2
    sleep(20 * sysPeriod)
    val got2 = readStatus(8)
    check(got2 == exp2, f"sel=8 tracks a second word 0x$exp2%08X (got 0x$got2%08X)")

    // Magic still correct after (no state corruption).
    val magic2 = readStatus(0)
    check(magic2 == BigInt("51560002", 16), f"sel=0 magic still 0x51560002 after sel=8 reads (got 0x$magic2%08X)")

    println(if (failures == 0) "=== Qspi2bppReadbackSim: ALL PASS — sel=8 SDRAM readback surfaced ==="
            else s"=== Qspi2bppReadbackSim: $failures FAIL ===")
    assert(failures == 0, s"Qspi2bppReadbackSim: $failures checks failed")
  }
}
```
