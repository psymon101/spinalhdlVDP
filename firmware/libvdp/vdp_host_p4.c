/**
 * vdp_host_p4.c — ESP32-P4 QSPI backend for libvdp.
 *
 * The P4 host is the canonical Tang Nano 20K transport.  This backend keeps
 * the SPI framing in libvdp while allowing P4 applications to use the same
 * vdp_mode0_* helpers as the Arduino/Pico ports.
 */
#include "vdp_host.h"
#include "vdp_crc8.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_rom_sys.h"

enum {
    PIN_SCLK = 21,
    PIN_CS = 20,
    PIN_IO0 = 32,
    PIN_IO1 = 33,
    PIN_IO2 = 22,
    PIN_IO3 = 23,
    CMD_READ_STATUS = 0x04,
    CMD_REG_WRITE = 0x01,
    CMD_SDRAM_WRITE = 0x02,
    SEL_CRC8_STATUS = 0x0Bu,
    DMA_BUF_SIZE = 65536,
    MAX_WRITE_WORDS = 253,
};

static spi_device_handle_t s_spi;
static uint8_t *s_tx_buf;
static uint8_t *s_rx_buf;
static bool s_initialized;
static int s_last_error;
static uint32_t s_clock_hz;

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

static uint32_t wire_addr(uint8_t cmd, uint32_t addr)
{
    (void)cmd;
    return addr | ((uint32_t)parity31(cmd, addr) << 23);
}

static esp_err_t add_device(uint32_t clock_hz)
{
    spi_device_interface_config_t cfg = {
        .clock_speed_hz = (int)clock_hz,
        .clock_source = SPI_CLK_SRC_SPLL,
        .mode = 0,
        .spics_io_num = PIN_CS,
        .queue_size = 4,
        .command_bits = 8,
        .address_bits = 24,
        .dummy_bits = 2,
        .input_delay_ns = 0,
        .cs_ena_pretrans = 2,
        .cs_ena_posttrans = 8,
        .flags = SPI_DEVICE_HALFDUPLEX | SPI_DEVICE_NO_DUMMY,
    };
    esp_err_t err = spi_bus_add_device(SPI2_HOST, &cfg, &s_spi);
    if (err == ESP_OK) s_clock_hz = clock_hz;
    return err;
}

static esp_err_t tx_frame(uint8_t cmd, uint32_t addr, const uint8_t *payload,
                          size_t payload_len)
{
    if (!s_spi || !payload || payload_len == 0u || payload_len > DMA_BUF_SIZE) {
        return ESP_ERR_INVALID_ARG;
    }
    spi_transaction_ext_t tx = {0};
    tx.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    tx.base.cmd = cmd;
    tx.base.addr = wire_addr(cmd, addr);
    tx.base.length = payload_len * 8u;
    tx.base.tx_buffer = payload;
    tx.dummy_bits = 0;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&tx);
}

static esp_err_t rx_status(uint8_t sel, uint32_t *value)
{
    if (!s_spi || !s_rx_buf || !value) return ESP_ERR_INVALID_ARG;
    spi_transaction_ext_t rx = {0};
    rx.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    rx.base.cmd = CMD_READ_STATUS;
    rx.base.addr = wire_addr(CMD_READ_STATUS, sel);
    rx.base.rxlength = 32;
    rx.base.rx_buffer = s_rx_buf;
    rx.dummy_bits = 2;
    esp_err_t err = spi_device_polling_transmit(s_spi, (spi_transaction_t *)&rx);
    if (err != ESP_OK) return err;
    *value = (uint32_t)s_rx_buf[0] |
             ((uint32_t)s_rx_buf[1] << 8) |
             ((uint32_t)s_rx_buf[2] << 16) |
             ((uint32_t)s_rx_buf[3] << 24);
    return ESP_OK;
}

static esp_err_t write_frame(uint8_t cmd, uint32_t addr, const uint8_t *frame,
                             size_t frame_len)
{
    if (!frame || frame_len < 2u || frame_len + 1u > DMA_BUF_SIZE) {
        return ESP_ERR_INVALID_ARG;
    }
    const uint8_t crc = vdp_crc8_qspi_write_frame(cmd, wire_addr(cmd, addr),
                                                   frame, frame_len);
    for (unsigned attempt = 0; attempt < 2u; ++attempt) {
        uint32_t before = 0;
        uint32_t after = 0;
        esp_err_t err = rx_status(SEL_CRC8_STATUS, &before);
        if (err != ESP_OK) return err;
        /* vdp_*_write() builds frame in s_tx_buf and passes that same buffer. */
        memmove(s_tx_buf, frame, frame_len);
        s_tx_buf[frame_len] = crc;
        err = tx_frame(cmd, addr, s_tx_buf, frame_len + 1u);
        if (err != ESP_OK) return err;
        esp_rom_delay_us(10u);
        err = rx_status(SEL_CRC8_STATUS, &after);
        if (err != ESP_OK) return err;
        if ((uint16_t)before == (uint16_t)after) return ESP_OK;
        if (attempt != 0u) return ESP_FAIL;
    }
    return ESP_FAIL;
}

int vdp_last_error(void) { return s_last_error; }

void vdp_host_init(void)
{
    if (s_initialized) return;
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
        .max_transfer_sz = DMA_BUF_SIZE,
        .flags = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD,
    };
    if (spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
    s_tx_buf = heap_caps_malloc(DMA_BUF_SIZE, MALLOC_CAP_DMA);
    s_rx_buf = heap_caps_malloc(4u, MALLOC_CAP_DMA);
    if (!s_tx_buf || !s_rx_buf || add_device(2000000u) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
    s_last_error = VDP_HOST_ERR_NONE;
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_pio_wait_sm_idle(void) {}

void vdp_host_set_speed_hz(uint32_t hz)
{
    if (!s_initialized || hz == 0u || hz == s_clock_hz) return;
    if (spi_bus_remove_device(s_spi) != ESP_OK || add_device(hz) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
}

void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

uint32_t vdp_read_status(uint8_t sel)
{
    uint32_t value = 0;
    if (!s_initialized || rx_status(sel, &value) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_RX;
        return 0;
    }
    s_last_error = VDP_HOST_ERR_NONE;
    return value;
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t count)
{
    if (!s_initialized || !words || count == 0u || count > MAX_WRITE_WORDS) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }
    s_tx_buf[0] = (uint8_t)(count & 0xFFu);
    s_tx_buf[1] = (uint8_t)(count >> 8);
    for (uint16_t i = 0; i < count; ++i) {
        s_tx_buf[2u + 2u * i] = (uint8_t)words[i];
        s_tx_buf[3u + 2u * i] = (uint8_t)(words[i] >> 8);
    }
    s_last_error = write_frame(CMD_REG_WRITE, addr, s_tx_buf, 2u + 2u * count) == ESP_OK
                       ? VDP_HOST_ERR_NONE : VDP_HOST_ERR_TX;
}

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    vdp_reg_write_burst(addr, &data, 1u);
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t count)
{
    if (!s_initialized || !words || count == 0u || count > MAX_WRITE_WORDS) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }
    s_tx_buf[0] = (uint8_t)(count & 0xFFu);
    s_tx_buf[1] = (uint8_t)(count >> 8);
    for (uint16_t i = 0; i < count; ++i) {
        s_tx_buf[2u + 2u * i] = (uint8_t)words[i];
        s_tx_buf[3u + 2u * i] = (uint8_t)(words[i] >> 8);
    }
    s_last_error = write_frame(CMD_SDRAM_WRITE, addr, s_tx_buf, 2u + 2u * count) == ESP_OK
                       ? VDP_HOST_ERR_NONE : VDP_HOST_ERR_TX;
}

uint16_t vdp_reg_read(uint32_t addr)
{
    (void)addr;
    s_last_error = VDP_HOST_ERR_RX;
    return 0;
}

void vdp_clear_upload_status(uint16_t mask)
{
    vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, (uint16_t)(mask & VDP_UPLOAD_STATUS_CLEAR_MASK));
}
