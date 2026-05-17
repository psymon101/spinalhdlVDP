/**
 * vdp_upload.h — vblank-paced SDRAM asset upload.
 *
 * Chunks a word stream into bursts small enough to fit inside a single
 * ~1.4 ms vblank window, syncs each burst to the raster trigger, and
 * optionally calls back into application code for progress tracking.
 */
#ifndef VDP_UPLOAD_H
#define VDP_UPLOAD_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Progress callback signature. Invoked once after each burst completes.
 * Host code must NOT issue QSPI transactions from within the callback
 * (re-entrancy is not supported). Callback latency directly reduces the
 * usable vblank window — prefer lightweight logging only.
 */
typedef void (*vdp_upload_cb)(uint16_t words_sent, uint16_t words_total);

/**
 * Stream `num_words` 16-bit words into SDRAM starting at `sdram_addr`,
 * pacing the transfer to land each burst inside a vblank window.
 *
 *   default burst = VDP_UPLOAD_WORDS_PER_VBLANK (16, still comfortably
 *   within the 1.4 ms vblank budget while halving header overhead vs. the
 *   previous 8-word pacing).
 *
 * @param sdram_addr target SDRAM byte address (24-bit)
 * @param words      pointer to little-endian 16-bit words (host-owned,
 *                   must remain valid for the full call duration)
 * @param num_words  total word count
 * @param cb         optional progress callback (may be NULL)
 * @return true if all words transmitted (does NOT guarantee SDRAM
 *         commit — call vdp_wait_sticky for QSPI_ERROR to check);
 *         false if a vblank timeout occurred mid-upload
 */
bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words,
                      uint16_t num_words, vdp_upload_cb cb);

#define VDP_UPLOAD_WORDS_PER_VBLANK 16u

#ifdef __cplusplus
}
#endif

#endif /* VDP_UPLOAD_H */
