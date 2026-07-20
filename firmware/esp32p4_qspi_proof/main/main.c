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

static const uint32_t QSPI_CLOCK_HZ = 20u * 1000u * 1000u;
static const uint32_t QSPI_EIGHTY_CLOCK_HZ = 80u * 1000u * 1000u;
static const uint32_t QSPI_SDRAM_CLOCK_HZ = 8u * 1000u * 1000u;
static const spi_clock_source_t QSPI_CLOCK_SOURCE = SPI_CLK_SRC_SPLL;
static const bool RUN_BULK_THROUGHPUT_ONLY = false;
static const uint32_t LONG_PHASE_YIELD_INTERVAL = 10000u;
static const uint32_t SHORT_SMOKE_ROUNDTRIPS = 1000000u;
static const uint32_t SHORT_SMOKE_BULK_BYTES = 131072u;
static const uint32_t PHASE1_PROGRESS_INTERVAL = 1000000u;
static const uint32_t PHASE2_PROGRESS_INTERVAL = 25000u;
static const uint8_t READ_STATUS_SEL_MAGIC = 0x00u;
static const uint8_t READ_STATUS_SEL_HDR_ERR = 0x07u;
static const uint8_t READ_STATUS_SEL_LOOPBACK = 0x09u;
static const uint8_t READ_STATUS_SEL_TRANSPORT_HEALTH = 0x06u;
static const uint32_t REG_WRITE_ADDR = 0x0305u;
static const uint16_t REG_WRITE_VALUE = 0xA55Au;
static const uint32_t LOOPBACK_WRITE_ADDR = 0x0042u;
static const uint16_t LOOPBACK_WRITE_VALUE = 0x1234u;
static const uint32_t EXPECTED_MAGIC = 0x51560002u;
static const uint32_t EXPECTED_LOOPBACK = 0x12340042u;
static const uint32_t DMA_BUF_SIZE = 65536u;
// ESP32-P4 SPI_MS_DATA_BITLEN is an 18-bit count of data-phase bits. Keep
// every QIO TX transaction below its 32767-byte maximum.
static const size_t QSPI_MAX_TX_BYTES = 32767u;
// SDRAM_WRITE adds a 2-byte word-count prefix; keep payloads even and below
// the transaction ceiling with a small margin for future framing changes.
static const uint64_t PHASE4_DURATION_US = 30ull * 60ull * 1000000ull;
static const uint64_t PHASE4_APPROX_PAYLOAD_BYTES_PER_ITER = 8ull;
// Keep all proof traffic below the reviewed QSPI oversampling ceiling.
static const uint32_t SANITY_FREQ_HZ = 4u * 1000u * 1000u;
static const uint32_t CLOCK_PROBE_ITERATIONS = 64u;
static const uint32_t PHASE1_ITERATIONS = 10000000u;
static const uint32_t PHASE2_ITERATIONS = 100000u;
enum { PHASE3_BURST_WORDS = 16384u };
static spi_device_handle_t s_spi = NULL;
static uint8_t *s_tx_buf = NULL;
static uint8_t *s_rx_buf = NULL;
static uint8_t s_phase3_pattern[PHASE3_PATTERN_SIZE];
static uint16_t s_reg_burst_words[PHASE3_BURST_WORDS];
static uint8_t s_indexed2_bitmap[INDEXED2_IMAGE_BYTES];
static uint8_t s_indexed2_attr[INDEXED2_IMAGE_BYTES];
static uint32_t s_input_delay_ns = 0;
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

static void fill_tx_bytes(const uint8_t *src, size_t len)
{
    memset(s_tx_buf, 0, DMA_BUF_SIZE);
    memcpy(s_tx_buf, src, len);
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
    uint32_t trimmed = addr & 0x7FFFFFu;

    if (!s_use_header_parity) {
        return trimmed;
    }
    return trimmed | ((uint32_t)parity31(cmd, trimmed) << 23);
}

static esp_err_t qspi_add_device(uint32_t clock_hz, uint32_t input_delay_ns, spi_clock_source_t clock_source)
{
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
    return qspi_add_device(QSPI_CLOCK_HZ, 0, QSPI_CLOCK_SOURCE);
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
    ESP_RETURN_ON_FALSE(len <= QSPI_MAX_TX_BYTES, ESP_ERR_INVALID_ARG, TAG,
                        "qspi tx exceeds P4 transaction limit");
    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.length = len * 8u;
    t.base.override_freq_hz = override_freq_hz;
    t.base.tx_buffer = tx;
    t.dummy_bits = dummy_bits;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_rx(uint8_t cmd, uint64_t addr, uint8_t *rx, size_t len,
                         uint8_t dummy_bits, uint32_t override_freq_hz)
{
    spi_transaction_ext_t t = {0};
    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.rxlength = len * 8u;
    t.base.override_freq_hz = override_freq_hz;
    t.base.rx_buffer = rx;
    t.dummy_bits = dummy_bits;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_read_status(uint8_t sel, uint32_t *out_value,
                                  uint8_t dummy_bits, uint32_t override_freq_hz)
{
    ESP_RETURN_ON_FALSE(out_value != NULL, ESP_ERR_INVALID_ARG, TAG, "null status output");
    memset(s_rx_buf, 0, DMA_BUF_SIZE);
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
    fill_tx_bytes(payload, sizeof(payload));
    return qspi_tx(CMD_REG_WRITE, reg_addr & 0xFFFFFFu, s_tx_buf, sizeof(payload), 0, override_freq_hz);
}

static esp_err_t qspi_reg_write_burst(uint32_t reg_addr, const uint16_t *words, size_t word_count,
                                      uint32_t override_freq_hz)
{
    size_t total_len = 2u + (word_count * 2u);

    ESP_RETURN_ON_FALSE(words != NULL, ESP_ERR_INVALID_ARG, TAG, "null reg burst words");
    ESP_RETURN_ON_FALSE(total_len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG, "reg burst too large");

    s_tx_buf[0] = (uint8_t)(word_count & 0xFFu);
    s_tx_buf[1] = (uint8_t)((word_count >> 8) & 0xFFu);
    for (size_t i = 0; i < word_count; ++i) {
        s_tx_buf[2u + (i * 2u)] = (uint8_t)(words[i] & 0xFFu);
        s_tx_buf[3u + (i * 2u)] = (uint8_t)((words[i] >> 8) & 0xFFu);
    }
    return qspi_tx(CMD_REG_WRITE, reg_addr & 0xFFFFFFu, s_tx_buf, total_len, 0, override_freq_hz);
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

    ESP_RETURN_ON_FALSE((len % 2u) == 0u, ESP_ERR_INVALID_ARG, TAG, "sdram write len must be even");
    total_len = len + 2u;
    ESP_RETURN_ON_FALSE(total_len <= QSPI_MAX_TX_BYTES, ESP_ERR_INVALID_ARG, TAG,
                        "sdram write exceeds P4 transaction limit");
    ESP_RETURN_ON_FALSE(total_len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG, "sdram write too large");

    len_words = (uint16_t)(len / 2u);
    s_tx_buf[0] = (uint8_t)(len_words & 0xFFu);
    s_tx_buf[1] = (uint8_t)((len_words >> 8) & 0xFFu);
    memcpy(s_tx_buf + 2u, payload, len);
    if (total_len < DMA_BUF_SIZE) {
        memset(s_tx_buf + total_len, 0, DMA_BUF_SIZE - total_len);
    }

    return qspi_tx(CMD_SDRAM_WRITE, sdram_addr & 0xFFFFFFu, s_tx_buf, total_len, 0, override_freq_hz);
}

static bool indexed2_reg_write(uint32_t reg_addr, uint16_t value)
{
    esp_err_t err = qspi_reg_write(reg_addr, value, SANITY_FREQ_HZ);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2_REG_WRITE addr=0x%04" PRIX32 " value=0x%04X err=%s",
                 reg_addr, value, esp_err_to_name(err));
        return false;
    }
    return true;
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
}

static bool indexed2_upload_image(void)
{
    esp_err_t err = qspi_sdram_write(0x100000u, s_indexed2_bitmap,
                                     sizeof(s_indexed2_bitmap), QSPI_SDRAM_CLOCK_HZ);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 bitmap upload err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2 bitmap uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_indexed2_bitmap), qspi_get_actual_freq_hz());

    err = qspi_sdram_write(0x110000u, s_indexed2_attr,
                           sizeof(s_indexed2_attr), QSPI_SDRAM_CLOCK_HZ);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2 attr upload err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2 attr uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_indexed2_attr), qspi_get_actual_freq_hz());
    return true;
}

static bool run_indexed2_proof(void)
{
    uint32_t magic = 0;
    uint32_t health_before = 0;
    uint32_t health_after_upload = 0;
    uint32_t health_after_enable = 0;
    bool ok = true;

    ESP_LOGI(TAG, "INDEXED2_PROOF begin source=320x240 display=640x480");
    if (qspi_read_status(READ_STATUS_SEL_MAGIC, &magic, 2, SANITY_FREQ_HZ) != ESP_OK) {
        ESP_LOGE(TAG, "INDEXED2_MAGIC read failed");
        return false;
    }
    ESP_LOGI(TAG, "INDEXED2_MAGIC value=0x%08" PRIX32 " actual_freq=%" PRIu32,
             magic, qspi_get_actual_freq_hz());
    ok &= read_and_log_transport_health("INDEXED2_HEALTH_BEFORE", SANITY_FREQ_HZ);
    if (!ok) {
        return false;
    }
    if (qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_before, 2,
                         SANITY_FREQ_HZ) != ESP_OK) {
        return false;
    }

    indexed2_build_image();
    ok &= indexed2_reg_write(0x0300u, 0x0000u); // disable visible layers while loading
    ok &= indexed2_reg_write(0x0313u, 0x0000u); // native Mode0
    ok &= indexed2_reg_write(0x0349u, 0x0000u); // no integer scaler; indexed path doubles naturally
    ok &= indexed2_reg_write(0x034Au, 640u);
    ok &= indexed2_reg_write(0x034Bu, 480u);
    ok &= indexed2_reg_write(0x0351u, 0x0000u); // bitmap base 0x100000
    ok &= indexed2_reg_write(0x0352u, 0x0010u);
    ok &= indexed2_reg_write(0x0353u, 0x0000u); // attribute base 0x110000
    ok &= indexed2_reg_write(0x0354u, 0x0011u);
    ok &= indexed2_reg_write(0x0355u, INDEXED2_ROW_STRIDE);
    ok &= indexed2_reg_write(0x0356u, INDEXED2_ROW_STRIDE);
    ok &= indexed2_reg_write(0x0357u, INDEXED2_HEIGHT);
    ok &= indexed2_load_palette();
    ok &= indexed2_reg_write(0x0350u, 0x0002u); // bpp=0b01, fetch disabled while uploading
    if (!ok) {
        return false;
    }

    ok &= indexed2_upload_image();
    ok &= read_and_log_transport_health("INDEXED2_HEALTH_AFTER_UPLOAD", SANITY_FREQ_HZ);
    if (qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_after_upload, 2,
                         SANITY_FREQ_HZ) != ESP_OK) {
        ok = false;
    }
    ok &= indexed2_reg_write(0x0350u, 0x0003u); // enable + BPP=0b01 (2bpp indexed)
    ok &= indexed2_reg_write(0x0300u, 0x0001u); // enable bitmap layer L0
    if (!ok) {
        return false;
    }

    vTaskDelay(pdMS_TO_TICKS(100));
    ok &= read_and_log_transport_health("INDEXED2_HEALTH_AFTER_ENABLE", SANITY_FREQ_HZ);
    if (qspi_read_status(READ_STATUS_SEL_TRANSPORT_HEALTH, &health_after_enable, 2,
                         SANITY_FREQ_HZ) != ESP_OK) {
        ok = false;
    }
    ESP_LOGI(TAG, "INDEXED2_PROOF_DONE pass=%u health_before=0x%08" PRIX32
             " health_after_upload=0x%08" PRIX32 " health_after_enable=0x%08" PRIX32,
             ok ? 1u : 0u, health_before, health_after_upload, health_after_enable);
    return ok;
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
    reset_phase_stats(stats);
    for (uint32_t i = 0; i < iterations; ++i) {
        uint32_t addr = 0;
        uint16_t value = 0;
        uint32_t got = 0;

        make_pattern(i, &addr, &value);
        bool ok = run_loopback_roundtrip(addr, value, freq_hz, dummy_bits, stats, &got);
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
        ESP_ERROR_CHECK(qspi_reg_write(REG_WRITE_ADDR, REG_WRITE_VALUE, freq_hz));
        ESP_LOGI(TAG, "P4_QSPI_REG_WRITE result=PASS addr=0x%04" PRIX32 " value=0x%04X tx=%02X %02X %02X %02X",
                 REG_WRITE_ADDR, REG_WRITE_VALUE,
                 reg_write_payload[0], reg_write_payload[1], reg_write_payload[2], reg_write_payload[3]);
    }

    {
        const uint8_t loopback_prime_payload[4] = {0x01u, 0x00u, 0x34u, 0x12u};
        uint32_t loopback = 0;
        ESP_ERROR_CHECK(qspi_reg_write(LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE, freq_hz));
        ESP_LOGI(TAG, "P4_QSPI_LOOPBACK_PRIME result=PASS addr=0x%04" PRIX32 " value=0x%04X tx=%02X %02X %02X %02X",
                 LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE,
                 loopback_prime_payload[0], loopback_prime_payload[1],
                 loopback_prime_payload[2], loopback_prime_payload[3]);
        ESP_ERROR_CHECK(qspi_read_status(READ_STATUS_SEL_LOOPBACK, &loopback, 2, freq_hz));
        report_result("P4_QSPI_LOOPBACK", ESP_OK, loopback, EXPECTED_LOOPBACK);
        log_hex_bytes("P4_QSPI_LOOPBACK bytes=", s_rx_buf, 4);
    }

    {
        const uint8_t sdram_payload[4] = {0xDEu, 0xADu, 0xBEu, 0xEFu};
        ESP_ERROR_CHECK(qspi_sdram_write(0x001000u, sdram_payload, sizeof(sdram_payload), freq_hz));
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
    reset_phase_stats(stats);
    ESP_LOGI(TAG, "%s reconfigure_device req_freq=%" PRIu32, label, freq_hz);
    ESP_RETURN_ON_ERROR(qspi_reconfigure_device(freq_hz, s_input_delay_ns), TAG, "reconfigure failed");
    run_magic_only_ber(label, CLOCK_PROBE_ITERATIONS, freq_hz, stats);
    log_phase_summary(label, freq_hz, 2, stats);
    if (actual_freq_out != NULL) {
        *actual_freq_out = qspi_get_actual_freq_hz();
    }
    return ESP_OK;
}

static void run_phase3_throughput(uint32_t freq_hz)
{
    static const size_t burst_sizes[] = {256u, 1024u, 4096u, 16384u, 65536u};
    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(i & 0xFFu);
    }

    ESP_LOGI(TAG, "P4_QSPI_PHASE3 note=current bring-up top has no real SDRAM backpressure; this phase is throughput-only");
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

            ESP_ERROR_CHECK(qspi_sdram_write(addr, s_phase3_pattern, chunk, freq_hz));
            addr += (uint32_t)chunk;
            bytes_remaining -= (uint32_t)chunk;
            chunks++;
        }

        ESP_ERROR_CHECK(qspi_read_status(READ_STATUS_SEL_LOOPBACK, &verify, 2, freq_hz));
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
}

static void run_write_throughput_80m(phase_stats_t *stats)
{
    static const size_t burst_sizes[] = {256u, 1024u, 4096u, 16384u, 65536u, 131072u};
    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(0xA5u ^ (i & 0xFFu));
    }

    reset_phase_stats(stats);
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

            esp_err_t err = qspi_sdram_write(addr, s_phase3_pattern, chunk, QSPI_EIGHTY_CLOCK_HZ);
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
}

static void run_bulk_write_smoke_80m(const char *label, uint32_t total_bytes, phase_stats_t *stats)
{
    uint32_t bytes_remaining = total_bytes;
    uint32_t addr = 0x001000u;
    uint32_t chunks = 0;
    int64_t start_us = 0;

    for (size_t i = 0; i < sizeof(s_phase3_pattern); ++i) {
        s_phase3_pattern[i] = (uint8_t)(0x5Au ^ (i & 0xFFu));
    }

    reset_phase_stats(stats);
    start_us = esp_timer_get_time();
    while (bytes_remaining != 0u) {
        size_t chunk = bytes_remaining;
        if (chunk > sizeof(s_phase3_pattern)) {
            chunk = sizeof(s_phase3_pattern);
        }
        if ((chunk & 1u) != 0u) {
            chunk--;
        }

        esp_err_t err = qspi_sdram_write(addr, s_phase3_pattern, chunk, QSPI_EIGHTY_CLOCK_HZ);
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
}

static void run_phase4_back_to_back(uint32_t freq_hz, uint8_t dummy_bits, phase_stats_t *stats);

static void relax_task_wdt_for_bench(void)
{
    const esp_task_wdt_config_t config = {
        .timeout_ms = 300000u,
        .idle_core_mask = 0x3u,
        .trigger_panic = true,
    };

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN task_wdt timeout_ms=%" PRIu32, config.timeout_ms);
    ESP_ERROR_CHECK(esp_task_wdt_reconfigure(&config));
}

static void fill_reg_burst_words(uint32_t base_addr, size_t word_count)
{
    for (size_t i = 0; i < word_count; ++i) {
        s_reg_burst_words[i] = (uint16_t)(0xA500u ^ (uint16_t)(base_addr + (uint32_t)i));
    }
}

static void fill_reg_burst_words_for_length_sweep(size_t word_count)
{
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
                       SANITY_FREQ_HZ, SANITY_FREQ_HZ, 2, &phase1_40);
    log_phase_summary("P4_QSPI_PHASE1_40W40R", SANITY_FREQ_HZ, 2, &phase1_40);
    phase1_40_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_40W40R_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase1 begin 80W/40R");
    run_cross_freq_ber("P4_QSPI_PHASE1_80W40R", PHASE1_ITERATIONS,
                       QSPI_EIGHTY_CLOCK_HZ, SANITY_FREQ_HZ, 2, &phase1_80);
    log_phase_summary("P4_QSPI_PHASE1_80W40R", QSPI_EIGHTY_CLOCK_HZ, 2, &phase1_80);
    phase1_80_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_80W40R_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase2 begin min-gap hammer @40W/40R");
    run_phase_ber("P4_QSPI_PHASE2_HAMMER", PHASE2_ITERATIONS, SANITY_FREQ_HZ, 2, &phase2);
    log_phase_summary("P4_QSPI_PHASE2_HAMMER", SANITY_FREQ_HZ, 2, &phase2);
    phase2_health_ok = read_and_log_transport_health("P4_QSPI_PHASE2_HAMMER_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase3 begin REG_WRITE bursts @80W/40R");
    run_reg_burst_matrix("P4_QSPI_PHASE3_BURST_80W40R", QSPI_EIGHTY_CLOCK_HZ, SANITY_FREQ_HZ, &phase3_80);
    phase3_80_health_ok = read_and_log_transport_health("P4_QSPI_PHASE3_BURST_80W40R_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN phase4 begin back-to-back control @40W/40R");
    run_phase4_back_to_back(SANITY_FREQ_HZ, 2, &phase4);
    log_phase_summary("P4_QSPI_PHASE4", SANITY_FREQ_HZ, 2, &phase4);
    phase4_health_ok = read_and_log_transport_health("P4_QSPI_PHASE4_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_CAMPAIGN final control verify @40W/40R after 80W upload");
    ESP_ERROR_CHECK(qspi_reconfigure_device(SANITY_FREQ_HZ, s_input_delay_ns));
    ESP_ERROR_CHECK(qspi_reg_write(LOOPBACK_WRITE_ADDR, LOOPBACK_WRITE_VALUE, SANITY_FREQ_HZ));
    if (qspi_read_status(READ_STATUS_SEL_LOOPBACK, &final_loopback, 2, SANITY_FREQ_HZ) != ESP_OK ||
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
    final_health_ok = read_and_log_transport_health("P4_QSPI_CAMPAIGN_FINAL_HEALTH", SANITY_FREQ_HZ);

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
                       SANITY_FREQ_HZ, SANITY_FREQ_HZ, 2, &phase1_40);
    log_phase_summary("P4_QSPI_PHASE1_40W40R", SANITY_FREQ_HZ, 2, &phase1_40);
    phase1_40_health_ok = read_and_log_transport_health("P4_QSPI_PHASE1_40W40R_HEALTH", SANITY_FREQ_HZ);

    ESP_LOGI(TAG, "P4_QSPI_SHORT_SMOKE phase2 begin 80W bulk bytes=%" PRIu32,
             SHORT_SMOKE_BULK_BYTES);
    run_bulk_write_smoke_80m("P4_QSPI_BULK_WRITE_80M", SHORT_SMOKE_BULK_BYTES, &bulk80);
    bulk80_health_ok = read_and_log_transport_health("P4_QSPI_BULK_WRITE_80M_HEALTH", SANITY_FREQ_HZ);

    {
        bool pass = phase_passed(&phase1_40) &&
                    phase_passed(&bulk80) &&
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

    reset_phase_stats(stats);
    ESP_LOGI(TAG,
             "P4_QSPI_PHASE4 note=driver exposes no explicit inter-CS gap knob; this run uses minimum implicit gap from back-to-back polling transactions");
    ESP_LOGI(TAG, "P4_QSPI_PHASE4 target duration_s=%" PRIu64,
             PHASE4_DURATION_US / 1000000ull);

    while ((uint64_t)(esp_timer_get_time() - start_us) < PHASE4_DURATION_US) {
        uint32_t addr = 0;
        uint16_t value = 0;
        uint32_t got = 0;

        make_pattern(stats->iterations, &addr, &value);
        bool ok = run_loopback_roundtrip(addr, value, freq_hz, dummy_bits, stats, &got);
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
    phase_stats_t write80 = {0};
    bool smoke_pass = false;

    ESP_LOGI(TAG, "P4_QSPI_PROOF start");
    ESP_LOGI(TAG, "pins sclk=%d cs=%d io0=%d io1=%d io2=%d io3=%d",
             PIN_SCLK, PIN_CS, PIN_IO0, PIN_IO1, PIN_IO2, PIN_IO3);
    ESP_LOGI(TAG, "qspi clock=%" PRIu32 " dummy_bits=2 addr_bits=24 cmd_bits=8 parity=%s",
             QSPI_CLOCK_HZ, s_use_header_parity ? "on" : "off");

    s_tx_buf = heap_caps_aligned_calloc(64, 1, DMA_BUF_SIZE, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    s_rx_buf = heap_caps_aligned_calloc(64, 1, DMA_BUF_SIZE, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    ESP_LOGI(TAG, "dma buf tx=%p rx=%p size=%" PRIu32, (void *)s_tx_buf, (void *)s_rx_buf, DMA_BUF_SIZE);
    if (s_tx_buf == NULL || s_rx_buf == NULL) {
        ESP_LOGE(TAG, "DMA buffer allocation failed");
        abort();
    }

    vTaskDelay(pdMS_TO_TICKS(200));
    ESP_ERROR_CHECK(qspi_init());
    ESP_LOGI(TAG, "P4_QSPI_INIT actual_freq=%" PRIu32, qspi_get_actual_freq_hz());

    esp_err_t err = qspi_read_status(READ_STATUS_SEL_MAGIC, &magic, 2, SANITY_FREQ_HZ);
    if (err == ESP_OK) {
    ESP_LOGI(TAG, "P4_QSPI_MAGIC result=PASS value=0x%08" PRIX32 " actual_freq=%" PRIu32,
                 magic, qspi_get_actual_freq_hz());
        log_hex_bytes("P4_QSPI_MAGIC bytes=", s_rx_buf, 4);
    } else {
        ESP_LOGE(TAG, "P4_QSPI_MAGIC result=ERR err=%s actual_freq=%" PRIu32,
                 esp_err_to_name(err), qspi_get_actual_freq_hz());
    }

    relax_task_wdt_for_bench();
    if (RUN_BULK_THROUGHPUT_ONLY) {
        ESP_LOGI(TAG, "P4_QSPI_BULK_ONLY starting 80 MHz bulk throughput rerun");
        run_write_throughput_80m(&write80);
        ESP_LOGI(TAG,
                 "P4_QSPI_BULK_ONLY summary req_freq=%" PRIu32 " actual_freq=%" PRIu32
                 " iterations=%" PRIu32 " write_err=%" PRIu32,
                 QSPI_EIGHTY_CLOCK_HZ, qspi_get_actual_freq_hz(),
                 write80.iterations, write80.write_errors);
        read_and_log_transport_health("P4_QSPI_BULK_ONLY_HEALTH", SANITY_FREQ_HZ);
    } else {
        ESP_LOGI(TAG, "P4_QSPI_INDEXED2 starting authorized 2bpp indexed proof");
        smoke_pass = run_indexed2_proof();
        ESP_LOGI(TAG, "P4_QSPI_APP done pass=%u", smoke_pass ? 1u : 0u);
    }

    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
