/**
 * test_qspi_smoke.c — Task 28 CP-C minimal command-sender firmware.
 *
 * Per BronzeGate #7851 / architectural direction in #7850, this
 * firmware performs only what a production Pico would do in the
 * host-controller role: QSPI init, a transport-proof register
 * write, sprite descriptor programming via Mode0 bus, and idle.
 *
 *   1. vdp_qspi_init()                     — bring up PIO + pins
 *   2. vdp_reg_write(0x0300, 0x0007)       — enable L0+L1+sprite layers
 *                                             (proves transport is live)
 *   3. Program evaluator slots 4..8 via    — five bus sprites at y=250,
 *      bus writes 0x0808..0x0811             x = {60,140,220,300,500},
 *                                             alternating pattern index
 *   4. tight_loop_contents() forever       — no printf, no status polling,
 *                                             no sleeps beyond a single
 *                                             settle delay after init
 *
 * No SDRAM upload, no sticky-status polling, no steady-state loop —
 * those were over-scoped artifacts of the wire-hardening harness.
 */
#include <stdint.h>
#include <stddef.h>
#include "pico/stdlib.h"
#include "vdp_qspi.h"

/* Sprite descriptor bus block (Task 28, SpriteEvaluator):
 *   base = 0x0800 + slot * 2
 *   word 0 @ base+0 : {enabled[15], patternIdx[14:11], reserved[10], y[9:0]}
 *   word 1 @ base+1 : {reserved[15:10], x[9:0]}
 */
#define SPRITE_BASE(slot)          (0x0800u + (slot) * 2u)
#define SPRITE_W0(enabled, pat, y) ( ((enabled) ? 0x8000u : 0x0000u) \
                                   | (((pat) & 0xFu) << 11)         \
                                   | ((y) & 0x3FFu) )

int main(void)
{
    stdio_init_all();   /* init clocks/USB even though we don't print */
    vdp_qspi_init();
    sleep_ms(100);  /* QSPI + Tang bitstream settle */

    /* Task 28 CP-C diagnostic A: do TWO writes only — first enables all
     * layers (tile region should render), second disables sprite layer
     * (legacy bouncers should disappear). If the second write takes
     * effect, the PIO TX path handles back-to-back writes; if only the
     * first fires, the QSPI/PIO path has a persistent state issue after
     * the first write. */
    vdp_reg_write(0x0300u, 0x0007u);
    sleep_ms(1000);   /* large inter-write sleep to rule out PIO re-init timing */
    vdp_reg_write(0x0300u, 0x0003u);

    /* Idle forever — production Pico will do input reads / higher-level
     * command dispatch here. Nothing display-related. */
    while (true) {
        tight_loop_contents();
    }
}
