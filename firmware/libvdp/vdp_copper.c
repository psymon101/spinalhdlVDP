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
    if (!prog || nwords == 0 || nwords > 1024u) return;

    /* Program RAM is only writable while copper is disabled.
     * Issue the disable first.
     */
    vdp_reg_write(0x0310u, 0x0000u);

    /* copperCtrlReg commits at hCounter==0 (once per scanline, ~tens of us).
     * Wait briefly to ensure the disable has latched.
     */
    vdp_copper_delay_us(2000);

    /* Direct upload: HostInterface is absent in the current top, so the QSPI
     * transport writes directly to the Copper Program RAM without buffering.
     * Chunk only because the host-side vdp_reg_write_burst caps at 253 words
     * (frame buffer is 512 bytes = 6 header + 506 payload). The FPGA's
     * QspiDecoder auto-increments writeAddr (see writeAddr := writeAddr + 1
     * in QspiDecoder.scala), so consecutive bursts to base + offset are
     * equivalent to one logical upload. No inter-chunk delay needed.
     */
    const uint16_t CHUNK = 253u;
    uint16_t offset = 0;
    while (offset < nwords) {
        const uint16_t remaining = (uint16_t)(nwords - offset);
        const uint16_t chunk = (remaining > CHUNK) ? CHUNK : remaining;
        vdp_reg_write_burst((uint32_t)(COPPER_RAM_BASE + offset),
                            prog + offset, chunk);
        offset = (uint16_t)(offset + chunk);
    }
}

void vdp_copper_enable(bool en)
{
    /* Read-modify-write VDP_CTRL @ 0x0310 bit[0] */
    /* For a demo sketch we know the initial state; just write directly. */
    vdp_reg_write(0x0310u, en ? 0x0001u : 0x0000u);
}

void vdp_copper_swap_request(void)
{
    /* VDP_CTRL @ 0x0310: bit[0]=COPPER_ENABLE, bit[1]=COPPER_SWAP_REQUEST.
     * Writing 0x0003 keeps copper enabled and requests the swap.
     * HW commits at next vSyncStart and auto-clears bit[1].
     *
     * Sequencing rule: always upload the next frame's program to the
     * inactive bank (burst to 0x0400 while copper is enabled) BEFORE
     * calling this function. Requesting a swap without first uploading
     * promotes uninitialized bank content — see CopperSim case 11.
     */
    vdp_reg_write(0x0310u, 0x0003u);
}

void vdp_copper_upload_and_swap(const uint16_t *prog, uint16_t nwords)
{
    if (!prog || nwords == 0 || nwords > 1024u) return;

    /* Precondition: copper must be enabled so burst writes to 0x0400
     * route to the inactive bank rather than corrupting the active one.
     * Chunk in 253-word pieces; host-side burst cap (see vdp_copper_upload). */
    const uint16_t CHUNK = 253u;
    uint16_t offset = 0;
    while (offset < nwords) {
        const uint16_t remaining = (uint16_t)(nwords - offset);
        const uint16_t chunk = (remaining > CHUNK) ? CHUNK : remaining;
        vdp_reg_write_burst((uint32_t)(COPPER_RAM_BASE + offset),
                            prog + offset, chunk);
        offset = (uint16_t)(offset + chunk);
    }
    vdp_copper_swap_request();
}
