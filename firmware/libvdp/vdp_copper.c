/**
 * vdp_copper.c — Copper program upload and control.
 */
#include "vdp_copper.h"
#include "vdp_qspi.h"

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
#include "pico/stdlib.h"
#elif defined(ARDUINO)
#include <Arduino.h>
#endif

#define COPPER_RAM_BASE 0x0400u
#define UPLOAD_CHUNK    16u   /* HostInterface FIFO depth */

static inline void vdp_copper_delay_us(uint32_t us)
{
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
    sleep_us(us);
#else
    delayMicroseconds(us);
#endif
}

void vdp_copper_upload(const uint16_t *prog, uint16_t nwords)
{
    if (!prog || nwords == 0 || nwords > 512u) return;
    /* Program RAM is only writable while copper is disabled.
     * Issue the disable first, then wait for the next safe boundary.
     */
    vdp_reg_write(0x0310u, 0x0000u);
    /* copperCtrlReg commits at hCounter==0 (once per frame, ~16.7 ms).
     * A 2 ms delay is insufficient if the write lands just after hCounter==0.
     * Wait 20 ms to guarantee disable has landed. */
    vdp_copper_delay_us(20000);

    /* Chunked upload: HostInterface has a 16-entry FIFO.
     * Bursts longer than 16 words silently drop writes.
     * Upload in ≤16 word chunks with inter-chunk delay for drain. */
    uint16_t offset = 0;
    while (offset < nwords) {
        uint16_t chunk = nwords - offset;
        if (chunk > UPLOAD_CHUNK) chunk = UPLOAD_CHUNK;
        vdp_reg_write_burst(COPPER_RAM_BASE + offset, prog + offset, chunk);
        offset += chunk;
        vdp_copper_delay_us(500);
    }
}

void vdp_copper_enable(bool en)
{
    /* Read-modify-write VDP_CTRL @ 0x0310 bit[0] */
    /* For a demo sketch we know the initial state; just write directly. */
    vdp_reg_write(0x0310u, en ? 0x0001u : 0x0000u);
}
