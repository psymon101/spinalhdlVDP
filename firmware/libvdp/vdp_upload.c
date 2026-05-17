/**
 * vdp_upload.c — vblank-paced SDRAM upload.
 *
 * Strategy proven by Task 34 Checkpoint C (#7704 / commit 222c1c0):
 * each burst is one SDRAM_WRITE transaction paced to vblank, so we can
 * amortize the header cost across a small contiguous chunk. Between
 * bursts we re-sync to the next vblank via vdp_wait_vblank() to avoid
 * the active-video single-byte-latch race inside QspiSdramBridge.
 */
#include "vdp_upload.h"
#include "vdp_qspi.h"
#include "vdp_status.h"

bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words,
                      uint16_t num_words, vdp_upload_cb cb)
{
    const uint32_t vblank_timeout_us = 20000u;   /* one frame margin */
    uint16_t sent = 0;

    while (sent < num_words) {
        if (!vdp_wait_vblank(vblank_timeout_us)) return false;

        uint16_t chunk = VDP_UPLOAD_WORDS_PER_VBLANK;
        if ((uint32_t)sent + chunk > num_words) chunk = num_words - sent;

        /* Send each vblank slice as one contiguous SDRAM_WRITE burst.
         * This keeps the same pacing model while amortizing the command
         * header and CS turn-around across more payload bytes. */
        vdp_sdram_write(sdram_addr + (uint32_t)sent * 2u, &words[sent], chunk);

        sent += chunk;
        if (cb) cb(sent, num_words);
    }

    return true;
}
