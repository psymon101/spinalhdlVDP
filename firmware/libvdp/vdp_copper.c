/**
 * vdp_copper.c — Copper program upload and control.
 */
#include "vdp_copper.h"
#include "vdp_qspi.h"

#define COPPER_RAM_BASE 0x0400u

void vdp_copper_upload(const uint16_t *prog, uint16_t nwords)
{
    if (!prog || nwords == 0 || nwords > 512u) return;
    /* Program RAM is only writable while copper is disabled.
     * copperCtrlReg commits at hCounter==0 (up to ~32 µs delay).
     * Wait 2 ms to guarantee the disable has landed. */
    vdp_reg_write(0x0310u, 0x0000u);
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
    sleep_us(2000);
#else
    delayMicroseconds(2000);
#endif
    vdp_reg_write_burst(COPPER_RAM_BASE, prog, nwords);
}

void vdp_copper_enable(bool en)
{
    /* Read-modify-write VDP_CTRL @ 0x0310 bit[0] */
    /* For a demo sketch we know the initial state; just write directly. */
    vdp_reg_write(0x0310u, en ? 0x0001u : 0x0000u);
}
