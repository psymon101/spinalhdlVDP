/*
 * ESP32-P4 rainbow direct-color sketch.
 *
 * This file is a standalone implementation. It intentionally does not
 * include or link source from the other VDP sketches. The transport and
 * image generator below are written against the documented active QSPI
 * word-drain contract.
 *
 * The VDP's direct-color fetcher consumes one low byte and one high byte for
 * every 320-pixel source row. The current 640x480 raster presents each source
 * pixel twice horizontally and each source row twice vertically.
 */

#include <inttypes.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "driver/spi_common.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_rom_sys.h"

static const char *TAG = "rainbow_directcolor";

enum {
    PIN_SCLK = 21,
    PIN_CS = 20,
    PIN_IO0 = 32,
    PIN_IO1 = 33,
    PIN_IO2 = 22,
    PIN_IO3 = 23,
};

enum {
    OP_REG_WRITE = 0x01,
    OP_SDRAM_WRITE = 0x02,
    OP_READ_STATUS = 0x04,
};

enum {
    STATUS_MAGIC = 0x00,
    STATUS_SDRAM_WORD = 0x08,
    STATUS_HEALTH = 0x0A,
    STATUS_CRC8 = 0x0B,
};

enum {
    REG_LAYER_ENABLE = 0x0300,
    REG_VDP_CTRL = 0x0310,
    REG_MODE_SELECT = 0x0313,
    REG_SDRAM_WORD_LO = 0x0326,
    REG_SDRAM_WORD_HI = 0x0327,
    REG_BITMAP_CTRL = 0x0350,
    REG_BITMAP_BASE_LO = 0x0351,
    REG_BITMAP_BASE_HI = 0x0352,
    REG_ATTR_BASE_LO = 0x0353,
    REG_ATTR_BASE_HI = 0x0354,
    REG_BITMAP_STRIDE = 0x0355,
    REG_ATTR_STRIDE = 0x0356,
    REG_BITMAP_HEIGHT = 0x0357,
};

enum {
    SOURCE_WIDTH = 320,
    SOURCE_HEIGHT = 240,
    VIEWPORT_X = 80,
    VIEWPORT_Y = 60,
    VIEWPORT_WIDTH = 160,
    VIEWPORT_HEIGHT = 120,
    PLANE_STRIDE = 512,
    PLANE_ROW_BYTES = SOURCE_WIDTH,
    CHUNK_ROWS = 62,
    CHUNK_BYTES = CHUNK_ROWS * PLANE_STRIDE,
    MAX_TRANSACTION_BYTES = 32767,
    DMA_BYTES = 32768,
};

static const uint32_t LOW_PLANE_BASE = 0x100000u;
static const uint32_t HIGH_PLANE_BASE = 0x200000u;
static const uint32_t EXPECTED_MAGIC = 0x51560002u;
static const uint32_t FUNCTIONAL_CLOCK_HZ = 2u * 1000u * 1000u;
static const uint32_t BULK_CLOCK_HZ = 4u * 1000u * 1000u;
static const spi_clock_source_t CLOCK_SOURCE = SPI_CLK_SRC_SPLL;

static spi_device_handle_t s_device;
static uint8_t *s_tx;
static uint8_t *s_rx;
static uint8_t *s_plane_chunk;
static uint32_t s_input_delay_ns;
static bool s_header_parity = true;

static uint32_t read_le32(const uint8_t *bytes)
{
    return (uint32_t)bytes[0] |
           ((uint32_t)bytes[1] << 8) |
           ((uint32_t)bytes[2] << 16) |
           ((uint32_t)bytes[3] << 24);
}

static uint8_t crc8_step(uint8_t crc, uint8_t data)
{
    crc ^= data;
    for (unsigned bit = 0; bit < 8u; ++bit) {
        crc = (crc & 0x80u) ? (uint8_t)((crc << 1) ^ 0x07u)
                            : (uint8_t)(crc << 1);
    }
    return crc;
}

static uint8_t frame_crc(uint8_t command, uint32_t wire_address,
                         const uint8_t *frame, size_t frame_bytes)
{
    uint8_t crc = 0u;

    crc = crc8_step(crc, command);
    crc = crc8_step(crc, (uint8_t)(wire_address >> 16));
    crc = crc8_step(crc, (uint8_t)(wire_address >> 8));
    crc = crc8_step(crc, (uint8_t)wire_address);
    for (size_t index = 0; index < frame_bytes; ++index) {
        crc = crc8_step(crc, frame[index]);
    }
    return crc;
}

static uint8_t address_parity(uint8_t command, uint32_t address)
{
    uint32_t header = ((uint32_t)command << 23) | (address & 0x7FFFFFu);
    uint8_t parity = 0u;

    while (header != 0u) {
        parity ^= (uint8_t)(header & 1u);
        header >>= 1;
    }
    return parity;
}

static uint32_t wire_address(uint8_t command, uint32_t address)
{
    uint32_t result = address & 0x7FFFFFu;
    if (s_header_parity) {
        result |= (uint32_t)address_parity(command, address) << 23;
    }
    return result;
}

static uint32_t actual_clock_hz(void)
{
    int frequency_khz = 0;
    if (s_device == NULL || spi_device_get_actual_freq(s_device, &frequency_khz) != ESP_OK) {
        return 0u;
    }
    return frequency_khz > 0 ? (uint32_t)frequency_khz * 1000u : 0u;
}

static esp_err_t add_spi_device(uint32_t clock_hz, uint32_t input_delay_ns)
{
    spi_device_interface_config_t config = {
        .clock_speed_hz = (int)clock_hz,
        .clock_source = CLOCK_SOURCE,
        .mode = 0,
        .spics_io_num = PIN_CS,
        .queue_size = 2,
        .command_bits = 8,
        .address_bits = 24,
        .dummy_bits = 2,
        .input_delay_ns = (int)input_delay_ns,
        .cs_ena_pretrans = 2,
        .cs_ena_posttrans = 8,
        .flags = SPI_DEVICE_HALFDUPLEX | SPI_DEVICE_NO_DUMMY,
    };

    s_input_delay_ns = input_delay_ns;
    return spi_bus_add_device(SPI2_HOST, &config, &s_device);
}

static esp_err_t configure_spi(uint32_t clock_hz)
{
    if (s_device != NULL) {
        ESP_RETURN_ON_ERROR(spi_bus_remove_device(s_device), TAG,
                            "SPI device remove failed");
        s_device = NULL;
    }
    return add_spi_device(clock_hz, s_input_delay_ns);
}

static esp_err_t initialize_spi(void)
{
    spi_bus_config_t bus = {
        .data0_io_num = PIN_IO0,
        .data1_io_num = PIN_IO1,
        .sclk_io_num = PIN_SCLK,
        .data2_io_num = PIN_IO2,
        .data3_io_num = PIN_IO3,
        .data4_io_num = -1,
        .data5_io_num = -1,
        .data6_io_num = -1,
        .data7_io_num = -1,
        .max_transfer_sz = DMA_BYTES,
        .flags = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD,
    };

    ESP_RETURN_ON_ERROR(spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO), TAG,
                        "SPI bus initialize failed");
    return add_spi_device(FUNCTIONAL_CLOCK_HZ, 0u);
}

static esp_err_t transmit_write(uint8_t command, uint32_t address,
                                uint8_t *frame, size_t frame_bytes)
{
    spi_transaction_ext_t transaction = {0};

    if (frame_bytes + 1u > MAX_TRANSACTION_BYTES) {
        return ESP_ERR_INVALID_SIZE;
    }
    transaction.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    transaction.base.cmd = command;
    transaction.base.addr = wire_address(command, address);
    transaction.base.length = (frame_bytes + 1u) * 8u;
    transaction.base.tx_buffer = frame;
    transaction.dummy_bits = 0;
    return spi_device_polling_transmit(s_device,
                                       (spi_transaction_t *)&transaction);
}

static esp_err_t receive_status(uint8_t selector, uint32_t *value)
{
    spi_transaction_ext_t transaction = {0};

    if (value == NULL || s_rx == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    transaction.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    transaction.base.cmd = OP_READ_STATUS;
    transaction.base.addr = wire_address(OP_READ_STATUS, selector);
    transaction.base.rxlength = 32;
    transaction.base.rx_buffer = s_rx;
    transaction.dummy_bits = 2;
    ESP_RETURN_ON_ERROR(spi_device_polling_transmit(
                            s_device, (spi_transaction_t *)&transaction),
                        TAG, "status read failed");
    *value = read_le32(s_rx);
    return ESP_OK;
}

static esp_err_t write_frame_with_retry(uint8_t command, uint32_t address,
                                        size_t frame_bytes)
{
    uint32_t before = 0u;
    uint32_t after = 0u;
    uint32_t encoded = wire_address(command, address);
    uint8_t crc;

    if (frame_bytes < 2u || frame_bytes + 1u > MAX_TRANSACTION_BYTES) {
        return ESP_ERR_INVALID_SIZE;
    }
    crc = frame_crc(command, encoded, s_tx, frame_bytes);
    for (unsigned attempt = 0; attempt < 3u; ++attempt) {
        ESP_RETURN_ON_ERROR(receive_status(STATUS_CRC8, &before), TAG,
                            "CRC status before write failed");
        s_tx[frame_bytes] = crc;
        ESP_RETURN_ON_ERROR(transmit_write(command, address, s_tx, frame_bytes), TAG,
                            "QSPI write failed");
        esp_rom_delay_us(10u);
        ESP_RETURN_ON_ERROR(receive_status(STATUS_CRC8, &after), TAG,
                            "CRC status after write failed");
        if ((uint16_t)before == (uint16_t)after) {
            return ESP_OK;
        }
        ESP_LOGW(TAG, "CRC retry command=0x%02X address=0x%06" PRIX32
                      " count=%u->%u attempt=%u",
                 command, address, (unsigned)(before & 0xFFFFu),
                 (unsigned)(after & 0xFFFFu), attempt + 1u);
    }
    return ESP_FAIL;
}

static esp_err_t register_write(uint32_t address, uint16_t value)
{
    s_tx[0] = 1u;
    s_tx[1] = 0u;
    s_tx[2] = (uint8_t)value;
    s_tx[3] = (uint8_t)(value >> 8);
    return write_frame_with_retry(OP_REG_WRITE, address, 4u);
}

static esp_err_t register_repeat(uint32_t address, uint16_t value, size_t count)
{
    if (count == 0u || count > UINT16_MAX || 2u + count * 2u + 1u > MAX_TRANSACTION_BYTES) {
        return ESP_ERR_INVALID_SIZE;
    }
    s_tx[0] = (uint8_t)count;
    s_tx[1] = (uint8_t)(count >> 8);
    for (size_t index = 0; index < count; ++index) {
        s_tx[2u + index * 2u] = (uint8_t)value;
        s_tx[3u + index * 2u] = (uint8_t)(value >> 8);
    }
    return write_frame_with_retry(OP_REG_WRITE, address, 2u + count * 2u);
}

static esp_err_t sdram_write(uint32_t address, const uint8_t *bytes, size_t byte_count)
{
    if (bytes == NULL || byte_count == 0u || (byte_count & 1u) != 0u ||
        byte_count + 3u > MAX_TRANSACTION_BYTES) {
        return ESP_ERR_INVALID_SIZE;
    }
    s_tx[0] = (uint8_t)(byte_count / 2u);
    s_tx[1] = (uint8_t)((byte_count / 2u) >> 8);
    memcpy(s_tx + 2u, bytes, byte_count);
    return write_frame_with_retry(OP_SDRAM_WRITE, address, byte_count + 2u);
}

static esp_err_t read_sdram_word(uint32_t address, uint32_t *value)
{
    ESP_RETURN_ON_ERROR(register_write(REG_SDRAM_WORD_LO, (uint16_t)address), TAG,
                        "SDRAM read low address failed");
    ESP_RETURN_ON_ERROR(register_write(REG_SDRAM_WORD_HI,
                                       (uint16_t)((address >> 16) & 0x7Fu)), TAG,
                        "SDRAM read high address failed");
    esp_rom_delay_us(20u);
    return receive_status(STATUS_SDRAM_WORD, value);
}

static uint16_t rainbow_rgb565(uint16_t x, uint16_t y)
{
    uint16_t local_x;
    uint16_t local_y;
    uint16_t hue;
    uint8_t region;
    uint8_t remainder;
    uint8_t descending;
    uint8_t red;
    uint8_t green;
    uint8_t blue;

    if (x < VIEWPORT_X || x >= VIEWPORT_X + VIEWPORT_WIDTH ||
        y < VIEWPORT_Y || y >= VIEWPORT_Y + VIEWPORT_HEIGHT) {
        return 0u;
    }
    local_x = (uint16_t)(x - VIEWPORT_X);
    local_y = (uint16_t)(y - VIEWPORT_Y);
    hue = (uint16_t)(((uint32_t)local_x * 360u) / (VIEWPORT_WIDTH - 1u));
    /* A small vertical hue drift makes the centered image two-dimensional. */
    hue = (uint16_t)((hue + ((uint32_t)local_y * 90u) /
                      (VIEWPORT_HEIGHT - 1u)) % 360u);
    region = (uint8_t)(hue / 60u);
    remainder = (uint8_t)(((uint32_t)(hue % 60u) * 255u) / 60u);
    descending = (uint8_t)(255u - remainder);
    switch (region) {
    case 0: red = 255u; green = remainder; blue = 0u; break;
    case 1: red = descending; green = 255u; blue = 0u; break;
    case 2: red = 0u; green = 255u; blue = remainder; break;
    case 3: red = 0u; green = descending; blue = 255u; break;
    case 4: red = remainder; green = 0u; blue = 255u; break;
    default: red = 255u; green = 0u; blue = descending; break;
    }
    return (uint16_t)(((uint16_t)(red & 0xF8u) << 8) |
                      ((uint16_t)(green & 0xFCu) << 3) |
                      ((uint16_t)blue >> 3));
}

static void generate_plane_rows(uint16_t first_row, uint16_t row_count, bool high_byte)
{
    for (uint16_t row = 0; row < row_count; ++row) {
        uint8_t *destination = s_plane_chunk + (size_t)row * PLANE_STRIDE;
        uint16_t y = (uint16_t)(first_row + row);

        memset(destination, 0, PLANE_STRIDE);
        for (uint16_t x = 0; x < SOURCE_WIDTH; ++x) {
            uint16_t pixel = rainbow_rgb565(x, y);
            destination[x] = high_byte ? (uint8_t)(pixel >> 8) : (uint8_t)pixel;
        }
    }
}

static esp_err_t upload_plane(uint32_t base, bool high_byte)
{
    for (uint16_t first_row = 0; first_row < SOURCE_HEIGHT;) {
        uint16_t remaining = (uint16_t)(SOURCE_HEIGHT - first_row);
        uint16_t rows = remaining < CHUNK_ROWS ? remaining : CHUNK_ROWS;
        size_t bytes = (size_t)rows * PLANE_STRIDE;
        uint32_t destination = base + (uint32_t)first_row * PLANE_STRIDE;

        generate_plane_rows(first_row, rows, high_byte);
        ESP_RETURN_ON_ERROR(sdram_write(destination, s_plane_chunk, bytes), TAG,
                            "direct-color plane upload failed");
        ESP_LOGI(TAG, "%s plane rows=%u..%u bytes=%u destination=0x%06" PRIX32,
                 high_byte ? "high" : "low", first_row,
                 (unsigned)(first_row + rows - 1u), (unsigned)bytes, destination);
        first_row = (uint16_t)(first_row + rows);
    }
    return ESP_OK;
}

static esp_err_t configure_direct_color(void)
{
    ESP_RETURN_ON_ERROR(register_write(REG_LAYER_ENABLE, 0u), TAG,
                        "layer disable failed");
    ESP_RETURN_ON_ERROR(register_write(REG_VDP_CTRL, 0u), TAG,
                        "copper disable failed");
    ESP_RETURN_ON_ERROR(register_write(REG_MODE_SELECT, 0u), TAG,
                        "Mode0 selection failed");
    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_CTRL, 0x0004u), TAG,
                        "direct-color fetch disable failed");
    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_BASE_LO, (uint16_t)LOW_PLANE_BASE), TAG,
                        "low base low write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_BASE_HI,
                                       (uint16_t)(LOW_PLANE_BASE >> 16)), TAG,
                        "low base high write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_ATTR_BASE_LO, (uint16_t)HIGH_PLANE_BASE), TAG,
                        "high base low write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_ATTR_BASE_HI,
                                       (uint16_t)(HIGH_PLANE_BASE >> 16)), TAG,
                        "high base high write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_STRIDE, PLANE_STRIDE), TAG,
                        "bitmap stride write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_ATTR_STRIDE, PLANE_STRIDE), TAG,
                        "high-plane stride write failed");
    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_HEIGHT, SOURCE_HEIGHT), TAG,
                        "bitmap height write failed");
    ESP_RETURN_ON_ERROR(register_repeat(0u, 0x0800u, 480u), TAG,
                        "line-state write failed");
    ESP_LOGI(TAG, "configured RGB565 direct color viewport=%ux%u output=640x480",
             VIEWPORT_WIDTH * 2u, VIEWPORT_HEIGHT * 2u);
    return ESP_OK;
}

static esp_err_t verify_plane_samples(void)
{
    uint32_t low_word = 0u;
    uint32_t high_word = 0u;
    uint32_t expected_low = 0u;
    uint32_t expected_high = 0u;
    const uint32_t low_sample_address = LOW_PLANE_BASE +
                                        (uint32_t)VIEWPORT_Y * PLANE_STRIDE +
                                        VIEWPORT_X;
    const uint32_t high_sample_address = HIGH_PLANE_BASE +
                                         (uint32_t)VIEWPORT_Y * PLANE_STRIDE +
                                         VIEWPORT_X;

    for (uint16_t lane = 0; lane < 4u; ++lane) {
        uint16_t pixel = rainbow_rgb565((uint16_t)(VIEWPORT_X + lane), VIEWPORT_Y);
        expected_low |= (uint32_t)(pixel & 0xFFu) << (8u * lane);
        expected_high |= (uint32_t)(pixel >> 8) << (8u * lane);
    }
    ESP_RETURN_ON_ERROR(read_sdram_word(low_sample_address, &low_word), TAG,
                        "low plane sample read failed");
    ESP_RETURN_ON_ERROR(read_sdram_word(high_sample_address, &high_word), TAG,
                        "high plane sample read failed");
    ESP_LOGI(TAG, "sample low=0x%08" PRIX32 " expected=0x%08" PRIX32
                  " high=0x%08" PRIX32 " expected=0x%08" PRIX32,
             low_word, expected_low, high_word, expected_high);
    return (low_word == expected_low && high_word == expected_high) ? ESP_OK : ESP_FAIL;
}

static esp_err_t enable_display(void)
{
    uint32_t health = 0u;

    ESP_RETURN_ON_ERROR(register_write(REG_BITMAP_CTRL, 0x0005u), TAG,
                        "direct-color enable failed");
    ESP_RETURN_ON_ERROR(register_write(REG_LAYER_ENABLE, 0x0001u), TAG,
                        "layer enable failed");
    ESP_RETURN_ON_ERROR(receive_status(STATUS_HEALTH, &health), TAG,
                        "final health read failed");
    return health == 0u ? ESP_OK : ESP_FAIL;
}

void app_main(void)
{
    uint32_t magic = 0u;
    uint32_t health = 0u;
    uint32_t crc_before = 0u;
    uint32_t crc_after = 0u;
    esp_err_t result;

    s_tx = heap_caps_malloc(DMA_BYTES, MALLOC_CAP_DMA | MALLOC_CAP_8BIT);
    s_rx = heap_caps_malloc(4u, MALLOC_CAP_DMA | MALLOC_CAP_8BIT);
    s_plane_chunk = heap_caps_malloc(CHUNK_BYTES, MALLOC_CAP_DMA | MALLOC_CAP_8BIT);
    if (s_tx == NULL || s_rx == NULL || s_plane_chunk == NULL) {
        ESP_LOGE(TAG, "DMA allocation failed");
        return;
    }

    result = initialize_spi();
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "SPI initialization failed: %s", esp_err_to_name(result));
        return;
    }
    ESP_LOGI(TAG, "rainbow direct-color start clock=%" PRIu32, actual_clock_hz());

    result = receive_status(STATUS_MAGIC, &magic);
    if (result != ESP_OK || magic != EXPECTED_MAGIC) {
        ESP_LOGE(TAG, "magic check failed value=0x%08" PRIX32, magic);
        return;
    }
    ESP_LOGI(TAG, "magic OK value=0x%08" PRIX32, magic);

    result = configure_direct_color();
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "direct-color configuration failed: %s", esp_err_to_name(result));
        return;
    }

    result = receive_status(STATUS_CRC8, &crc_before);
    if (result == ESP_OK) {
        ESP_LOGI(TAG, "CRC8 counter before upload=%u", (unsigned)(crc_before & 0xFFFFu));
    }

    result = configure_spi(BULK_CLOCK_HZ);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "bulk clock setup failed: %s", esp_err_to_name(result));
        return;
    }
    result = upload_plane(LOW_PLANE_BASE, false);
    if (result == ESP_OK) {
        result = upload_plane(HIGH_PLANE_BASE, true);
    }
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "rainbow plane upload failed: %s", esp_err_to_name(result));
        return;
    }
    ESP_LOGI(TAG, "plane uploads complete bulk_clock=%" PRIu32, actual_clock_hz());

    result = configure_spi(FUNCTIONAL_CLOCK_HZ);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "functional clock restore failed: %s", esp_err_to_name(result));
        return;
    }
    result = verify_plane_samples();
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "plane sample verification failed");
        return;
    }

    result = receive_status(STATUS_HEALTH, &health);
    if (result != ESP_OK || health != 0u) {
        ESP_LOGE(TAG, "health before display failed raw=0x%08" PRIX32, health);
        return;
    }
    result = enable_display();
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "display enable or final health failed");
        return;
    }
    result = receive_status(STATUS_CRC8, &crc_after);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "final CRC read failed: %s", esp_err_to_name(result));
        return;
    }

    ESP_LOGI(TAG, "RAINBOW_DIRECTCOLOR_PASS health=0x%08" PRIX32
                  " crc=%u->%u functional_clock=%" PRIu32,
             health, (unsigned)(crc_before & 0xFFFFu),
             (unsigned)(crc_after & 0xFFFFu), actual_clock_hz());
}
