/**
 * vdp_upload.c — vblank-paced SDRAM upload.
 *
 * Strategy proven by Task 34 Checkpoint C (#7704 / commit 222c1c0):
 * each burst is ~1 word of QSPI traffic (~32 µs), so 8 bursts fit
 * comfortably inside one vblank window. Between bursts we re-sync to
 * the next vblank via vdp_wait_vblank() to avoid the active-video
 * single-byte-latch race inside QspiSdramBridge.
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

        /* Fire chunk bursts as 1-word SDRAM_WRITEs so each transaction
         * fits cleanly in the remaining vblank window. Each is ~32 µs on
         * the wire at 2 MHz SCK. */
        for (uint16_t i = 0; i < chunk; i++) {
            uint32_t addr = sdram_addr + (uint32_t)(sent + i) * 2u;
            vdp_sdram_write(addr, &words[sent + i], 1);
        }

        sent += chunk;
        if (cb) cb(sent, num_words);
    }

    return true;
}
