/*
 * ESP32-P4 minimal checkerboard test firmware for spinalhdlVDP.
 *
 * Scenario: generate a static 320x240 2bpp indexed checkerboard in SDRAM
 * and display it at 640x480 through the Tang Nano 20K VDP.
 *
 * This firmware is intentionally small and self-contained. It borrows only
 * the proven QSPI transport primitives from esp32p4_qspi_proof and contains
 * no stress tests, no campaign modes, and no HAM6 code paths.
 *
 * Expected output: a static checkerboard of 32x32 source-pixel squares
 * alternating between palette entry 0 (black) and palette entry 1 (white).
 *
 * Pin map (ESP32-P4 GPIO matrix -> Tang Nano 20K):
 *   SCLK = GPIO21
 *   CS#  = GPIO20
 *   IO0  = GPIO32
 *   IO1  = GPIO33
 *   IO2  = GPIO22
 *   IO3  = GPIO23
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
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "p4_checkerboard";

/* --------------------------------------------------------------------------
 * Constants
 * -------------------------------------------------------------------------- */

enum {
    PIN_SCLK = 21,
    PIN_CS = 20,
    PIN_IO0 = 32,
    PIN_IO1 = 33,
    PIN_IO2 = 22,
    PIN_IO3 = 23,
};

/* QSPI opcodes */
enum {
    CMD_READ_STATUS = 0x04,
    CMD_REG_WRITE = 0x01,
    CMD_SDRAM_WRITE = 0x02,
};

/* READ_STATUS selectors */
enum {
    SEL_MAGIC = 0x00,
    SEL_SDRAM = 0x08,
    SEL_LOOPBACK = 0x09,
    SEL_TRANSPORT_HEALTH = 0x0A,
};

/* Mode0 register addresses */
enum {
    REG_LAYER_ENABLE = 0x0300,
    REG_MODE_SELECT = 0x0313,
    REG_BITMAP_CTRL = 0x0350,
    REG_BITMAP_BASE_LO = 0x0351,
    REG_BITMAP_BASE_HI = 0x0352,
    REG_ATTR_BASE_LO = 0x0353,
    REG_ATTR_BASE_HI = 0x0354,
    REG_BITMAP_STRIDE = 0x0355,
    REG_ATTR_STRIDE = 0x0356,
    REG_BITMAP_HEIGHT = 0x0357,
    REG_PALETTE_DATA = 0x0600,
    REG_PALETTE_PTR = 0x0601,
    REG_SDRAM_READ_ADDR_LO = 0x0326,
    REG_SDRAM_READ_ADDR_HI = 0x0327,
};

/* Image geometry */
enum {
    WIDTH = 320,
    HEIGHT = 240,
    ROW_STRIDE = 128,        /* hardware hardwired stride in bytes */
    ROW_DATA_BYTES = WIDTH / 4,
    IMAGE_BYTES = HEIGHT * ROW_STRIDE,
    CHECKER_SQUARE = 32,     /* source pixels per checkerboard square */
};

/* Clocking */
static const uint32_t QSPI_DEFAULT_CLOCK_HZ = 20u * 1000u * 1000u;
static const uint32_t QSPI_FUNCTIONAL_CLOCK_HZ = 40u * 1000u * 1000u;
static const uint32_t QSPI_SDRAM_CLOCK_HZ = 8u * 1000u * 1000u;
static const spi_clock_source_t QSPI_CLOCK_SOURCE = SPI_CLK_SRC_SPLL;

/* Transaction limits */
#define DMA_BUF_SIZE 65536u
#define QSPI_MAX_TX_BYTES 32767u

static const uint32_t EXPECTED_MAGIC = 0x51560002u;
static const uint32_t BITMAP_BASE = 0x100000u;
static const uint32_t ATTR_BASE = 0x110000u;
static const uint32_t ROW200_BASE = BITMAP_BASE + (200u * ROW_STRIDE);
static const uint32_t ROW201_BASE = BITMAP_BASE + (201u * ROW_STRIDE);

/* --------------------------------------------------------------------------
 * State
 * -------------------------------------------------------------------------- */

static spi_device_handle_t s_spi = NULL;
static uint8_t *s_tx_buf = NULL;
static uint8_t *s_rx_buf = NULL;
static uint8_t s_bitmap[IMAGE_BYTES];
static uint8_t s_attr[IMAGE_BYTES];
static uint32_t s_input_delay_ns = 0;
static uint32_t s_configured_freq_hz = 0;
static bool s_use_header_parity = true;

/* --------------------------------------------------------------------------
 * Utilities
 * -------------------------------------------------------------------------- */

static uint32_t bytes_to_u32_le(const uint8_t *buf)
{
    return (uint32_t)buf[0] |
           ((uint32_t)buf[1] << 8) |
           ((uint32_t)buf[2] << 16) |
           ((uint32_t)buf[3] << 24);
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
    if (!s_use_header_parity) {
        return addr;
    }
    return addr | ((uint32_t)parity31(cmd, addr) << 23);
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

/* --------------------------------------------------------------------------
 * QSPI transport
 * -------------------------------------------------------------------------- */

static esp_err_t qspi_add_device(uint32_t clock_hz, uint32_t input_delay_ns)
{
    ESP_RETURN_ON_FALSE(clock_hz != 0u, ESP_ERR_INVALID_ARG, TAG, "zero QSPI clock");

    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = (int)clock_hz,
        .clock_source = QSPI_CLOCK_SOURCE,
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
    return qspi_add_device(QSPI_DEFAULT_CLOCK_HZ, 0);
}

static esp_err_t qspi_reconfigure_device(uint32_t clock_hz, uint32_t input_delay_ns)
{
    if (s_spi != NULL) {
        ESP_RETURN_ON_ERROR(spi_bus_remove_device(s_spi), TAG, "spi remove device failed");
        s_spi = NULL;
    }
    return qspi_add_device(clock_hz, input_delay_ns);
}

static esp_err_t qspi_tx(uint8_t cmd, uint64_t addr, const uint8_t *tx, size_t len,
                         uint8_t dummy_bits)
{
    spi_transaction_ext_t t = {0};
    uint64_t max_addr = s_use_header_parity ? 0x7FFFFFu : 0xFFFFFFu;

    ESP_RETURN_ON_FALSE(s_spi != NULL, ESP_ERR_INVALID_STATE, TAG, "QSPI device unavailable");
    ESP_RETURN_ON_FALSE(addr <= max_addr, ESP_ERR_INVALID_ARG, TAG, "QSPI address out of range");
    ESP_RETURN_ON_FALSE(tx != NULL, ESP_ERR_INVALID_ARG, TAG, "null QSPI TX buffer");
    ESP_RETURN_ON_FALSE(len != 0u, ESP_ERR_INVALID_ARG, TAG, "zero-length QSPI TX");
    ESP_RETURN_ON_FALSE(len <= QSPI_MAX_TX_BYTES, ESP_ERR_INVALID_ARG, TAG,
                        "qspi tx exceeds P4 transaction limit");

    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.length = len * 8u;
    t.base.tx_buffer = tx;
    t.dummy_bits = dummy_bits;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_rx(uint8_t cmd, uint64_t addr, uint8_t *rx, size_t len)
{
    spi_transaction_ext_t t = {0};
    uint64_t max_addr = s_use_header_parity ? 0x7FFFFFu : 0xFFFFFFu;

    ESP_RETURN_ON_FALSE(s_spi != NULL, ESP_ERR_INVALID_STATE, TAG, "QSPI device unavailable");
    ESP_RETURN_ON_FALSE(addr <= max_addr, ESP_ERR_INVALID_ARG, TAG, "QSPI address out of range");
    ESP_RETURN_ON_FALSE(rx != NULL, ESP_ERR_INVALID_ARG, TAG, "null QSPI RX buffer");
    ESP_RETURN_ON_FALSE(len != 0u, ESP_ERR_INVALID_ARG, TAG, "zero-length QSPI RX");

    t.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    t.base.cmd = cmd;
    t.base.addr = qspi_encode_addr(cmd, (uint32_t)addr);
    t.base.rxlength = len * 8u;
    t.base.rx_buffer = rx;
    t.dummy_bits = 2;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&t);
}

static esp_err_t qspi_read_status(uint8_t sel, uint32_t *out_value)
{
    ESP_RETURN_ON_FALSE(out_value != NULL, ESP_ERR_INVALID_ARG, TAG, "null status output");
    ESP_RETURN_ON_FALSE(s_rx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "RX DMA buffer unavailable");

    esp_err_t err = qspi_rx(CMD_READ_STATUS, sel, s_rx_buf, 4);
    if (err != ESP_OK) {
        return err;
    }
    *out_value = bytes_to_u32_le(s_rx_buf);
    return ESP_OK;
}

static esp_err_t qspi_reg_write(uint32_t reg_addr, uint16_t value)
{
    const uint8_t payload[4] = {
        0x01u, 0x00u,
        (uint8_t)(value & 0xFFu),
        (uint8_t)((value >> 8) & 0xFFu),
    };

    ESP_RETURN_ON_FALSE(s_tx_buf != NULL, ESP_ERR_INVALID_STATE, TAG, "TX DMA buffer unavailable");
    memcpy(s_tx_buf, payload, sizeof(payload));
    return qspi_tx(CMD_REG_WRITE, reg_addr, s_tx_buf, sizeof(payload), 0);
}

static esp_err_t qspi_sdram_write(uint32_t sdram_addr, const uint8_t *payload, size_t len)
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
    ESP_RETURN_ON_FALSE(total_len <= DMA_BUF_SIZE, ESP_ERR_INVALID_ARG, TAG, "sdram write too large");

    len_words = (uint16_t)(len / 2u);
    s_tx_buf[0] = (uint8_t)(len_words & 0xFFu);
    s_tx_buf[1] = (uint8_t)((len_words >> 8) & 0xFFu);
    memcpy(s_tx_buf + 2u, payload, len);
    return qspi_tx(CMD_SDRAM_WRITE, sdram_addr, s_tx_buf, total_len, 0);
}

/* --------------------------------------------------------------------------
 * VDP helpers
 * -------------------------------------------------------------------------- */

static bool reg_write(uint32_t addr, uint16_t value)
{
    esp_err_t err = qspi_reg_write(addr, value);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "REG_WRITE addr=0x%04" PRIX32 " value=0x%04X err=%s",
                 addr, value, esp_err_to_name(err));
        return false;
    }
    return true;
}

static bool write_linestate_l0(void)
{
    /* Linestate format: {l0en[11], l1en[10], l0scrollX[9:0]}.
     * Enable L0, disable L1, zero scroll for every active display line. */
    for (uint16_t line = 0; line < 480u; ++line) {
        if (!reg_write(line, 0x0800u)) {
            ESP_LOGE(TAG, "LINESTATE write failed line=%u", line);
            return false;
        }
    }
    ESP_LOGI(TAG, "LINESTATE L0 enabled lines=480");
    return true;
}

static bool load_palette(void)
{
    /* Palette entries 0..3. RGB888, written as two 16-bit halves per entry. */
    static const uint32_t palette[4] = {
        0x00000000, /* 0: black */
        0x00FFFFFF, /* 1: white */
        0x00FF0000, /* 2: red (unused) */
        0x000000FF, /* 3: blue (unused) */
    };

    if (!reg_write(REG_PALETTE_PTR, 0u)) {
        return false;
    }
    for (size_t i = 0; i < 4u; ++i) {
        uint8_t r = (uint8_t)((palette[i] >> 16) & 0xFFu);
        uint8_t g = (uint8_t)((palette[i] >> 8) & 0xFFu);
        uint8_t b = (uint8_t)(palette[i] & 0xFFu);
        if (!reg_write(REG_PALETTE_DATA, (uint16_t)(((uint16_t)g << 8) | b)) ||
            !reg_write(REG_PALETTE_DATA, (uint16_t)r)) {
            return false;
        }
    }
    ESP_LOGI(TAG, "palette loaded entries=4");
    return true;
}

/* --------------------------------------------------------------------------
 * Checkerboard generation
 * -------------------------------------------------------------------------- */

static void build_checkerboard(void)
{
    memset(s_bitmap, 0, sizeof(s_bitmap));
    memset(s_attr, 0xE4, sizeof(s_attr)); /* identity attribute mapping */

    for (size_t y = 0; y < HEIGHT; ++y) {
        uint8_t *row = s_bitmap + (y * ROW_STRIDE);
        size_t square_y = y / CHECKER_SQUARE;

        for (size_t x = 0; x < WIDTH; ++x) {
            size_t square_x = x / CHECKER_SQUARE;
            uint8_t color = ((square_x ^ square_y) & 1u) ? 1u : 0u;
            size_t byte_index = x / 4u;
            unsigned shift = 6u - (unsigned)((x & 3u) * 2u);

            row[byte_index] |= (uint8_t)(color << shift);
        }
    }

    ESP_LOGI(TAG, "checkerboard generated width=%u height=%u stride=%u square=%u",
             WIDTH, HEIGHT, ROW_STRIDE, CHECKER_SQUARE);
}

static bool upload_planes(void)
{
    esp_err_t err;

    /* Switch to the conservative SDRAM clock for the bulk upload. */
    err = qspi_reconfigure_device(QSPI_SDRAM_CLOCK_HZ, s_input_delay_ns);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SDRAM clock configure err=%s", esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG, "bitmap upload addr=0x%06" PRIX32 " bytes=%u tx_bytes=%u chunking=single",
             BITMAP_BASE, (unsigned)sizeof(s_bitmap), (unsigned)(sizeof(s_bitmap) + 2u));
    err = qspi_sdram_write(BITMAP_BASE, s_bitmap, sizeof(s_bitmap));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "bitmap upload err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "bitmap uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_bitmap), qspi_get_actual_freq_hz());

    err = qspi_sdram_write(ATTR_BASE, s_attr, sizeof(s_attr));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "attr upload err=%s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "attr uploaded bytes=%u actual_freq=%" PRIu32,
             (unsigned)sizeof(s_attr), qspi_get_actual_freq_hz());

    /* Return to functional clock for register polling and display. */
    err = qspi_reconfigure_device(QSPI_FUNCTIONAL_CLOCK_HZ, s_input_delay_ns);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "functional clock restore err=%s", esp_err_to_name(err));
        return false;
    }
    return true;
}

static void dump_upload_window(void)
{
    /* Dump the source words spanning bitmap byte offset 25,600..25,900
     * before any SDRAM transaction, so image-generation errors are separated
     * from transport corruption. */
    for (size_t offset = 25600u; offset <= 25896u; offset += 16u) {
        ESP_LOGI(TAG,
                 "UPLOAD_WINDOW offset=%u addr=0x%06" PRIX32
                 " words=0x%08" PRIX32 ",0x%08" PRIX32 ",0x%08" PRIX32 ",0x%08" PRIX32,
                 (unsigned)offset, BITMAP_BASE + (uint32_t)offset,
                 bytes_to_u32_le(s_bitmap + offset),
                 bytes_to_u32_le(s_bitmap + offset + 4u),
                 bytes_to_u32_le(s_bitmap + offset + 8u),
                 bytes_to_u32_le(s_bitmap + offset + 12u));
    }
}

static bool log_transport_health(const char *label)
{
    uint32_t raw = 0u;
    esp_err_t err = qspi_read_status(SEL_TRANSPORT_HEALTH, &raw);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "%s err=%s", label, esp_err_to_name(err));
        return false;
    }
    bool overflow = (raw & 0x1u) != 0u;
    bool malformed = (raw & 0x2u) != 0u;
    ESP_LOGI(TAG, "%s raw=0x%08" PRIX32 " overflow=%u malformed=%u",
             label, raw, overflow ? 1u : 0u, malformed ? 1u : 0u);
    return !overflow && !malformed;
}

/* --------------------------------------------------------------------------
 * Readback verification
 * -------------------------------------------------------------------------- */

static bool readback_word(uint32_t addr, uint32_t *out_word)
{
    if (!reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)(addr & 0xFFFFu)) ||
        !reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)((addr >> 16) & 0x007Fu))) {
        return false;
    }
    esp_rom_delay_us(20u);
    return qspi_read_status(SEL_SDRAM, out_word) == ESP_OK;
}

static bool verify_checkerboard_samples(void)
{
    /* Pick a few words that are easy to predict from the checkerboard math.
     * Each sample is a 4-byte aligned word inside the packed bitmap plane. */
    static const uint32_t samples[] = {
        BITMAP_BASE,                                 /* row 0, byte 0: color 0 */
        BITMAP_BASE + 8u,                            /* row 0, byte 8: color 1 */
        BITMAP_BASE + 16u,                           /* row 0, byte 16: color 0 */
        BITMAP_BASE + (ROW_STRIDE * CHECKER_SQUARE), /* row 32, byte 0: color 1 */
    };

    bool pass = true;

    for (size_t i = 0; i < (sizeof(samples) / sizeof(samples[0])); ++i) {
        uint32_t addr = samples[i];
        uint32_t got = 0;
        uint32_t expected = bytes_to_u32_le(s_bitmap + (addr - BITMAP_BASE));

        if (!readback_word(addr, &got)) {
            ESP_LOGE(TAG, "READBACK sample=%zu addr=0x%06" PRIX32 " read failed", i, addr);
            pass = false;
            continue;
        }
        if (got != expected) {
            ESP_LOGE(TAG,
                     "READBACK FAIL sample=%zu addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " got=0x%08" PRIX32,
                     i, addr, expected, got);
            pass = false;
        } else {
            ESP_LOGI(TAG, "READBACK PASS sample=%zu addr=0x%06" PRIX32 " value=0x%08" PRIX32,
                     i, addr, got);
        }
    }
    return pass;
}

static bool verify_row200_samples(void)
{
    static const uint32_t offsets[] = {0u, 8u, 16u, 24u};
    bool pass = true;

    for (size_t row = 0; row < 2u; ++row) {
        uint32_t row_base = (row == 0u) ? ROW200_BASE : ROW201_BASE;
        for (size_t i = 0; i < (sizeof(offsets) / sizeof(offsets[0])); ++i) {
            uint32_t addr = row_base + offsets[i];
            uint32_t got = 0u;
            uint32_t expected = bytes_to_u32_le(s_bitmap + (addr - BITMAP_BASE));

            if (!readback_word(addr, &got)) {
                ESP_LOGE(TAG, "ROW200_READBACK FAIL addr=0x%06" PRIX32 " read failed", addr);
                pass = false;
            } else if (got != expected) {
                ESP_LOGE(TAG,
                         "ROW200_READBACK FAIL addr=0x%06" PRIX32
                         " expected=0x%08" PRIX32 " got=0x%08" PRIX32,
                         addr, expected, got);
                pass = false;
            } else {
                ESP_LOGI(TAG, "ROW200_READBACK PASS addr=0x%06" PRIX32 " value=0x%08" PRIX32,
                         addr, got);
            }
        }
    }
    return pass;
}

/* --------------------------------------------------------------------------
 * Main display bring-up
 * -------------------------------------------------------------------------- */

static bool bringup_display(void)
{
    bool proof_ok = true;

    if (!reg_write(REG_LAYER_ENABLE, 0x0000u) ||       /* disable all layers */
        !reg_write(REG_MODE_SELECT, 0x0000u) ||        /* native Mode0 */
        !reg_write(REG_BITMAP_BASE_LO, (uint16_t)(BITMAP_BASE & 0xFFFFu)) ||
        !reg_write(REG_BITMAP_BASE_HI, (uint16_t)((BITMAP_BASE >> 16) & 0x007Fu)) ||
        !reg_write(REG_ATTR_BASE_LO, (uint16_t)(ATTR_BASE & 0xFFFFu)) ||
        !reg_write(REG_ATTR_BASE_HI, (uint16_t)((ATTR_BASE >> 16) & 0x007Fu)) ||
        !reg_write(REG_BITMAP_STRIDE, ROW_STRIDE) ||
        !reg_write(REG_ATTR_STRIDE, ROW_STRIDE) ||
        !reg_write(REG_BITMAP_HEIGHT, HEIGHT)) {
        return false;
    }

    if (!load_palette()) {
        return false;
    }

    /* BPP=0b01 (2bpp indexed), fetch still disabled until upload completes. */
    if (!reg_write(REG_BITMAP_CTRL, 0x0002u)) {
        return false;
    }

    if (!upload_planes()) {
        return false;
    }

    proof_ok &= log_transport_health("HEALTH_AFTER_UPLOAD");
    bool basic_readback_pass = verify_checkerboard_samples();
    bool row200_readback_pass = verify_row200_samples();
    proof_ok &= basic_readback_pass && row200_readback_pass;
    if (!basic_readback_pass || !row200_readback_pass) {
        /* Continue to enable anyway so the failure is visible on HDMI too. */
        ESP_LOGW(TAG, "readback verification failed; continuing to enable display");
        if (!row200_readback_pass) {
            /* Capture the sticky transport state immediately and twice more
             * after a row-200 failure, per the upload investigation packet. */
            log_transport_health("HEALTH_AFTER_ROW200_FAIL_1");
            log_transport_health("HEALTH_AFTER_ROW200_FAIL_2");
            log_transport_health("HEALTH_AFTER_ROW200_FAIL_3");
        }
    }

    if (!write_linestate_l0()) {
        return false;
    }

    /* Enable fetch + 2bpp indexed, then enable layer 0 globally. */
    if (!reg_write(REG_BITMAP_CTRL, 0x0003u) ||
        !reg_write(REG_LAYER_ENABLE, 0x0001u)) {
        return false;
    }

    proof_ok &= log_transport_health("HEALTH_AFTER_ENABLE");
    ESP_LOGI(TAG, "display enabled");
    return proof_ok;
}

/* --------------------------------------------------------------------------
 * Entry point
 * -------------------------------------------------------------------------- */

void app_main(void)
{
    uint32_t magic = 0;
    bool ok = true;

    ESP_LOGI(TAG, "ESP32-P4 checkerboard test starting");

    s_tx_buf = heap_caps_malloc(DMA_BUF_SIZE, MALLOC_CAP_DMA);
    s_rx_buf = heap_caps_malloc(DMA_BUF_SIZE, MALLOC_CAP_DMA);
    if (s_tx_buf == NULL || s_rx_buf == NULL) {
        ESP_LOGE(TAG, "DMA buffer allocation failed");
        return;
    }

    if (qspi_init() != ESP_OK) {
        ESP_LOGE(TAG, "QSPI init failed");
        return;
    }

    if (qspi_read_status(SEL_MAGIC, &magic) != ESP_OK) {
        ESP_LOGE(TAG, "magic read failed");
        return;
    }
    if (magic != EXPECTED_MAGIC) {
        ESP_LOGE(TAG, "magic mismatch got=0x%08" PRIX32 " expect=0x%08" PRIX32,
                 magic, EXPECTED_MAGIC);
        return;
    }
    ESP_LOGI(TAG, "magic OK value=0x%08" PRIX32 " actual_freq=%" PRIu32,
             magic, qspi_get_actual_freq_hz());

    build_checkerboard();
    dump_upload_window();
    ok = bringup_display();

    if (ok) {
        ESP_LOGI(TAG, "CHECKERBOARD_TEST PASS");
    } else {
        ESP_LOGE(TAG, "CHECKERBOARD_TEST FAIL");
    }

    /* Idle forever; the VDP keeps scanning the framebuffer. */
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
