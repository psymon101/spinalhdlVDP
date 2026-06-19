/**
 * vdp_host.h — Transport layer for the VDP host driver library.
 *
 * Encapsulates the active host transport so application code never
 * hand-frames packets. The current Tang Nano 20K deployment uses i80;
 * legacy QSPI backends remain available through deprecated aliases.
 *
 * All functions are synchronous / blocking. Errors are reported via
 * `vdp_last_error()`; return value of `bool` APIs is `true` on success.
 */
#ifndef VDP_HOST_H
#define VDP_HOST_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VDP_UPLOAD_STATUS_CLEAR_REG 0x0323u
#define VDP_UPLOAD_STATUS_BUSY      0x0001u
#define VDP_UPLOAD_STATUS_DONE      0x0002u
#define VDP_UPLOAD_STATUS_ERROR     0x0004u
#define VDP_UPLOAD_STATUS_OVERFLOW  0x0008u
#define VDP_UPLOAD_STATUS_TXN_DROPPED 0x0010u
#define VDP_UPLOAD_STATUS_CLEAR_MASK \
    (VDP_UPLOAD_STATUS_ERROR | VDP_UPLOAD_STATUS_OVERFLOW | \
     VDP_UPLOAD_STATUS_TXN_DROPPED)

enum {
    VDP_HOST_ERR_NONE = 0,
    VDP_HOST_ERR_INVALID_ARG = 2,
    VDP_HOST_ERR_BUS_INIT = 3,
    VDP_HOST_ERR_DEVICE = 4,
    VDP_HOST_ERR_TX = 5,
    VDP_HOST_ERR_RX = 6,
    VDP_HOST_ERR_NOT_INITIALIZED = 7,
    VDP_HOST_ERR_INVALID_SELECTOR = 8,
};

#define VDP_QSPI_ERR_NONE             VDP_HOST_ERR_NONE
#define VDP_QSPI_ERR_INVALID_ARG      VDP_HOST_ERR_INVALID_ARG
#define VDP_QSPI_ERR_BUS_INIT         VDP_HOST_ERR_BUS_INIT
#define VDP_QSPI_ERR_DEVICE           VDP_HOST_ERR_DEVICE
#define VDP_QSPI_ERR_TX               VDP_HOST_ERR_TX
#define VDP_QSPI_ERR_RX               VDP_HOST_ERR_RX
#define VDP_QSPI_ERR_NOT_INITIALIZED  VDP_HOST_ERR_NOT_INITIALIZED
#define VDP_QSPI_ERR_INVALID_SELECTOR VDP_HOST_ERR_INVALID_SELECTOR

/**
 * One-time bring-up of the active host pins/peripheral.
 * Must be called once after `stdio_init_all()` and before any other
 * library call. Idempotent after first call (subsequent calls no-op).
 */
void vdp_host_init(void);
void vdp_qspi_init(void);

/**
 * Issue a REG_WRITE (CMD=0x01) transaction writing a single 16-bit
 * word to the specified 15-bit VDP register address.
 * @param addr 15-bit register address (e.g. 0x0300 LAYER_ENABLE)
 * @param data little-endian 16-bit payload
 */
void vdp_reg_write(uint32_t addr, uint16_t data);

/**
 * Issue a REG_WRITE burst (CMD=0x01) writing `num_words` consecutive
 * 16-bit words starting at the specified register address.
 *
 * The FPGA decoder auto-increments the register address once per word.
 * Use this for contiguous register blocks to amortize header and CS
 * overhead. The payload is little-endian 16-bit words.
 *
 * @param num_words 1..253 (capped by the 253-word local frame buffer)
 */
void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words);

/**
 * Clear upload-status sticky bits using the RTL W1C register at 0x0323.
 *
 * Valid Fix B bits are VDP_UPLOAD_STATUS_ERROR (bit 2) and
 * VDP_UPLOAD_STATUS_OVERFLOW (bit 3). Pass only bits intended to clear.
 *
 * Note: the 0x0323 clear decode is not yet implemented in the current
 * bitstream; the helper issues the write, but hardware ignores it until
 * the RTL change lands (FULL-DOC-AUDIT-151).
 */
void vdp_clear_upload_status(uint16_t mask);

/**
 * Issue a READ_STATUS (CMD=0x04) transaction and return the 32-bit
 * little-endian response word for the requested selector.
 *
 * Note: READ_STATUS is implemented only on legacy QSPI builds. The i80
 * RTL decoder does not currently decode opcode 0x04, so this function
 * returns undefined data on i80 hosts (use normal register reads instead).
 *
 * @param sel   0 = magic 0x51560002, 1 = rx_cmd_cnt, 2 = last_addr,
 *              3 = last_data, 4 = last_error, 5 = status sticky,
 *              6 = upload status (busy/done bits), 7 = live mode,
 *              8 = diagnostic SDRAM dword readback
 * @return 32-bit response assembled from 4 bit-banged bytes (byte 0 = LSB)
 */
uint32_t vdp_read_status(uint8_t sel);
uint16_t vdp_reg_read(uint32_t addr);

/**
 * Issue an SDRAM_WRITE (CMD=0x02) transaction streaming `num_words`
 * 16-bit little-endian words into the FPGA's SDRAM starting at the
 * 24-bit byte address `addr`. Host must paced bursts to vblank via
 * `vdp_upload_asset()` for clean visible-render results; this low-level
 * call fires the entire transaction in one PIO stream.
 * @param addr      target SDRAM byte address (24-bit low)
 * @param words     pointer to little-endian 16-bit words
 * @param num_words LEN field (max 65535, capped by local frame buffer)
 */
void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words);

/**
 * Last error code (0 = none). Cleared by vdp_host_init(); otherwise
 * sticky across calls. Currently only used by helpers that return
 * bool; the blocking write/read calls cannot fail in library-visible
 * ways beyond an upstream FPGA HOST_ERROR which must be polled via
 * READ_STATUS sel=4.
 */
int vdp_last_error(void);

/**
 * Change the host transport speed at runtime. Currently only effective on
 * legacy ESP32-S3 hardware SPI2/QSPI compatibility builds. On i80 and
 * bit-bang platforms this is a no-op. Pass a frequency in Hz; the actual
 * rate may be rounded to the nearest divisor of the bus clock.
 *
 * Safe to call between transactions; do not call mid-transaction. The
 * SPI2/QSPI compatibility builds clamp requests to `VDP_HOST_SCK_WRITE_HZ`
 * (`VDP_QSPI_SCK_WRITE_HZ` legacy alias).
 */
void vdp_host_set_speed_hz(uint32_t hz);
void vdp_qspi_set_speed_hz(uint32_t hz);

/**
 * Wait for the PIO TX FIFO to drain + a proven 20 µs OSR margin.
 *
 * MUST be called after any PIO TX burst before:
 *   - deasserting CS_N
 *   - switching pin function (PIO → SIO for bit-bang read)
 *   - beginning an unrelated PIO sequence
 *
 * The wait is two phases: spin on `pio_sm_is_tx_fifo_empty()`, then
 * `sleep_us(20)` for the final nibble to shift out of the OSR. At the
 * proven 2 MHz SCK the final nibble needs ~5 µs, so 20 µs is a 4×
 * margin (Task 38c). Do not reduce without re-validating on hardware.
 */
void vdp_pio_wait_sm_idle(void);

#ifdef __cplusplus
}
#endif

#endif /* VDP_HOST_H */
