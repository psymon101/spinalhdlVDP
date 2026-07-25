#ifndef VDP_CRC8_H
#define VDP_CRC8_H

#include <stddef.h>
#include <stdint.h>

/* QSPI-CRC8-185 host contract: CRC-8-CCITT, poly 0x07, init 0x00,
 * MSB-first, no reflection, no final XOR. */
static inline uint8_t vdp_crc8_ccitt_update(uint8_t crc, uint8_t data)
{
    crc ^= data;
    for (unsigned bit = 0; bit < 8u; ++bit) {
        crc = (crc & 0x80u) ? (uint8_t)((crc << 1) ^ 0x07u)
                            : (uint8_t)(crc << 1);
    }
    return crc;
}

/* Compute the CRC over one wire-order write frame after CMD and ADDR have
 * already been separated by the SPI controller. `frame` starts with the
 * two wire LEN bytes (LEN_lo, LEN_hi), followed by the payload bytes. The
 * address must be the encoded 24-bit address actually sent on the wire,
 * including the header-parity bit when that mode is enabled. */
static inline uint8_t vdp_crc8_qspi_write_frame(uint8_t cmd, uint32_t wire_addr,
                                                 const uint8_t *frame, size_t frame_len)
{
    uint8_t crc = 0u;

    crc = vdp_crc8_ccitt_update(crc, cmd);
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)((wire_addr >> 16) & 0xFFu));
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)((wire_addr >> 8) & 0xFFu));
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)(wire_addr & 0xFFu));
    for (size_t i = 0; i < frame_len; ++i) {
        crc = vdp_crc8_ccitt_update(crc, frame[i]);
    }
    return crc;
}

#endif /* VDP_CRC8_H */
