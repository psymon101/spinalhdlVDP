/**
 * vdp_copper.h — Minimal Copper program helpers for libvdp.
 *
 * Encodes Copper opcodes per Copper.scala BH-1 contract.
 * All opcodes are little-endian 16-bit words.
 */
#ifndef VDP_COPPER_H
#define VDP_COPPER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Encode a legacy WAIT(Y) opcode (1 word).
 * Stalls copper until vCounter == Y AND hCounter == 0 (single-cycle match
 * window per frame, per Copper.scala sWaitStall). If the FSM misses the
 * match cycle, the WAIT waits a full frame.
 */
static inline uint16_t vdp_copper_wait(uint16_t y)
{
    return (uint16_t)(y & 0x3FFu);
}

/**
 * Encode a pixel-precise WAIT(X,Y) opcode header (2 words).
 * Returns the first word; caller must append Y as second word.
 */
static inline uint16_t vdp_copper_wait_xy(uint16_t x)
{
    return (uint16_t)(0x2000u | (x & 0x3FFu));
}

/**
 * Encode a WRITE_SEQ header word.
 * @param addr       11-bit register address
 * @param count_m1   N-1 where N = number of data words (0..7)
 * @return header word; caller must append data words after this.
 */
static inline uint16_t vdp_copper_write_seq_hdr(uint16_t addr, uint8_t count_m1)
{
    return (uint16_t)(0x8000u | (((uint16_t)(count_m1 & 0x7u)) << 11) | (addr & 0x7FFu));
}

/**
 * Encode a JUMP opcode (1 word).
 */
static inline uint16_t vdp_copper_jump(uint16_t target_pc)
{
    return (uint16_t)(0xC000u | (target_pc & 0x1FFu));
}

/**
 * Upload a copper program into FPGA copper RAM starting at 0x0400.
 * Uses vdp_reg_write_burst() for efficient contiguous writes.
 * @param prog     pointer to little-endian 16-bit opcode array
 * @param nwords   number of words (max 512)
 */
void vdp_copper_upload(const uint16_t *prog, uint16_t nwords);

/**
 * Enable or disable the copper via VDP_CTRL @ 0x0310 bit[0].
 */
void vdp_copper_enable(bool en);

/**
 * Request an atomic bank swap on the next vSyncStart.
 * Copper must already be enabled. The swap promotes the inactive bank to
 * active and resets pc to 0. HW auto-clears the request bit after commit.
 * Writes 0x0003 to VDP_CTRL @ 0x0310 (keeps COPPER_ENABLE set).
 */
void vdp_copper_swap_request(void);

#ifdef __cplusplus
}
#endif

#endif /* VDP_COPPER_H */
