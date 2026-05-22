/**
 * vdp_status.h — Status polling + sticky bit helpers.
 *
 * Builds on vdp_qspi.h (READ_STATUS sel=5 = sticky status bank,
 * write-to-0x0320 = clear-1-to-clear). Sticky bit mapping (low byte):
 *   bit 0 RASTER_MATCH   — fires at the raster trigger line
 *   bit 1 SPRITE_OVERFLOW
 *   bit 2 QSPI_READY     — pulses on every accepted cmd_valid
 *   bit 3 QSPI_ERROR     — level-high while last_error != 0
 */
#ifndef VDP_STATUS_H
#define VDP_STATUS_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VDP_STICKY_RASTER_MATCH    0x0001
#define VDP_STICKY_SPRITE_OVERFLOW 0x0002
#define VDP_STICKY_QSPI_READY      0x0004
#define VDP_STICKY_QSPI_ERROR      0x0008
/* Task 29 — sprite collision flags (write-1-to-clear @ 0x0320). */
#define VDP_STICKY_SPRITE_0_HIT    0x0010  /* slot-0 non-transparent over non-transparent BG */
#define VDP_STICKY_SPRITE_BG_HIT   0x0020  /* any sprite non-transparent over non-transparent BG */

/* Task 47/49 — DMA/Blitter done sticky bits */
#define VDP_STICKY_DMA_DONE        0x0100  /* DMA transfer complete */
#define VDP_STICKY_BLIT_DONE       0x0200  /* Blitter block transfer complete */

/* Task 51 — MODE_SELECT committed */
#define VDP_STICKY_MODE_SELECT_CHANGED 0x0800  /* MODE_SELECT latched at V=0 */

/**
 * Block until `bit_mask` bits are all set in the sticky register, or
 * timeout expires. Polls sel=5 with 50 µs wait between polls.
 * @param bit_mask    OR of VDP_STICKY_* flags to wait for
 * @param timeout_us  absolute wait limit; 0 = no wait, returns current state
 * @return true if all requested bits observed set within timeout
 */
bool vdp_wait_sticky(uint16_t bit_mask, uint32_t timeout_us);

/**
 * Convenience wrapper for vblank sync: clears RASTER_MATCH then waits
 * for it to re-assert (next vblank entry). Suitable for host-paced
 * SDRAM upload loops.
 * @param timeout_us absolute wait limit (one frame = ~16700 µs at 60 Hz)
 * @return true if vblank reached within timeout
 */
bool vdp_wait_vblank(uint32_t timeout_us);

/**
 * Write-1-to-clear the sticky register. Bits set in `mask` are cleared;
 * bits set to 0 in `mask` are preserved.
 */
void vdp_clear_sticky(uint16_t mask);

#ifdef __cplusplus
}
#endif

#endif /* VDP_STATUS_H */
