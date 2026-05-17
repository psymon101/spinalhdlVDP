/**
 * vdp_qspi.h — Transport layer for the VDP host driver library.
 *
 * Encapsulates the proven 6-byte-header QSPI contract (plan §3.1) so
 * application code never hand-frames packets. Reuses the existing
 * qspi_quad.pio program for TX and bit-bangs the READ_STATUS response
 * path (matching the /home/itadmin/github/VDP reference pattern).
 *
 * All functions are synchronous / blocking. Errors are reported via
 * `vdp_last_error()`; return value of `bool` APIs is `true` on success.
 */
#ifndef VDP_QSPI_H
#define VDP_QSPI_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * One-time bring-up of the QSPI pins, PIO program, and clock divider.
 * Must be called once after `stdio_init_all()` and before any other
 * library call. Idempotent after first call (subsequent calls no-op).
 */
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
 */
void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words);

/**
 * Issue a READ_STATUS (CMD=0x04) transaction and return the 32-bit
 * little-endian response word for the requested selector.
 * @param sel   0 = magic 0x51560002, 1 = rx_cmd_cnt, 2 = last_addr,
 *              3 = last_data, 4 = last_error, 5 = status sticky,
 *              6 = upload status (busy/done bits), 7 = live mode
 * @return 32-bit response assembled from 4 bit-banged bytes (byte 0 = LSB)
 */
uint32_t vdp_read_status(uint8_t sel);

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
 * Last error code (0 = none). Cleared by vdp_qspi_init(); otherwise
 * sticky across calls. Currently only used by helpers that return
 * bool; the blocking write/read calls cannot fail in library-visible
 * ways beyond an upstream FPGA QSPI_ERROR which must be polled via
 * READ_STATUS sel=4.
 */
int vdp_last_error(void);

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

#endif /* VDP_QSPI_H */
