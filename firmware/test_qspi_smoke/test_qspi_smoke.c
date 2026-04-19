/**
 * test_qspi_smoke.c — Smoke test exercising the libvdp host driver
 *                     library (Task 39).
 *
 * Replaces the previous monolithic ~350-line firmware with a thin
 * application layer that calls libvdp. The sequence of proofs is
 * unchanged — the same writes, reads, and uploads happen in the same
 * order, just through `vdp_*` API instead of bespoke helpers.
 *
 * Expected serial (`/dev/ttyACM0`) output on boot:
 *   [smoke] boot
 *   [smoke] init-reads: magic=0x51560002 cmd_cnt=<...> last_addr=<...> ...
 *   [smoke] sdram-probe: attr+tile upload launched
 *   [smoke] loop on=1 last_data=0x00000088 sticky=0x00000005
 *   [smoke] loop on=0 last_data=0x00000000 sticky=0x00000005
 *   ...
 *
 * HDMI-side proofs reproduced by this test:
 *   - LAYER_ENABLE = 0x0007 drives all three layers on (Task 27)
 *   - SDRAM upload to AttributeMapBase + TileRowBase produces 14.2%
 *     pixel delta vs baseline per Task 34 CP-C methodology
 *   - LED(3) tracks IRQ driven by sticky status (Task 35)
 */
#include <stdio.h>
#include <stdint.h>

#include "pico/stdlib.h"

#include "vdp_qspi.h"
#include "vdp_status.h"
#include "vdp_upload.h"

/* SDRAM asset target addresses (TileAttributeAssets.scala:37-39). */
#define SMOKE_ATTR_MAP_BASE  0x00007000u
#define SMOKE_TILE_ROW_BASE  0x00008000u
#define SMOKE_PROBE_WORDS    32

static uint16_t smoke_probe_payload[SMOKE_PROBE_WORDS];

int main(void)
{
    stdio_init_all();
    vdp_qspi_init();
    sleep_ms(2000);   /* USB CDC + Tang bitstream settle */

    printf("[smoke] boot\n");

    /* Init-reads — prove the full READ_STATUS surface is reachable. */
    uint32_t magic   = vdp_read_status(0);
    uint32_t cmd_cnt = vdp_read_status(1);
    uint32_t addr    = vdp_read_status(2);
    uint32_t data    = vdp_read_status(3);
    uint32_t err     = vdp_read_status(4);
    printf("[smoke] init-reads: magic=0x%08lx cmd_cnt=0x%08lx last_addr=0x%08lx last_data=0x%08lx last_error=0x%08lx\n",
           (unsigned long)magic, (unsigned long)cmd_cnt,
           (unsigned long)addr,  (unsigned long)data, (unsigned long)err);

    /* Prime last_data for bit-3 proof (Task 38c) — write to reserved
     * register 0x0313 so LAYER_ENABLE stays free. */
    vdp_reg_write(0x0313u, 0x0088u);
    sleep_ms(10);

    /* Force all layers on so SDRAM tile data actually renders. */
    vdp_reg_write(0x0300u, 0x0007u);
    sleep_ms(10);

    /* Enable RASTER_MATCH so vblank sync works (Task 35 sticky bit 0). */
    vdp_reg_write(0x0321u, VDP_STICKY_RASTER_MATCH);
    sleep_ms(10);

    /* 20 s baseline window so HDMI captures see a clean pre-upload
     * section before the probe fires. */
    printf("[smoke] 20-second pre-upload baseline window starts\n");
    sleep_ms(20000);

    /* Build the probe payload (all 0xFFFF — forces bright pixels / max
     * palette bank). */
    for (uint16_t i = 0; i < SMOKE_PROBE_WORDS; i++) {
        smoke_probe_payload[i] = 0xFFFFu;
    }

    printf("[smoke] sdram-probe: attr+tile upload launched\n");
    bool ok1 = vdp_upload_asset(SMOKE_ATTR_MAP_BASE, smoke_probe_payload,
                                SMOKE_PROBE_WORDS, NULL);
    bool ok2 = vdp_upload_asset(SMOKE_TILE_ROW_BASE, smoke_probe_payload,
                                SMOKE_PROBE_WORDS, NULL);
    sleep_ms(50);
    uint32_t post_cnt = vdp_read_status(1) & 0xFFu;
    uint32_t post_err = vdp_read_status(4) & 0xFFu;
    uint32_t up6      = vdp_read_status(6);
    printf("[smoke] sdram-probe: attr-ok=%d tile-ok=%d cmd_cnt=%lu err=%lu sel6=0x%08lx\n",
           (int)ok1, (int)ok2,
           (unsigned long)post_cnt, (unsigned long)post_err, (unsigned long)up6);

    /* Restore Task 35 IRQ mask (bits 2+3). */
    vdp_reg_write(0x0321u, VDP_STICKY_QSPI_READY | VDP_STICKY_QSPI_ERROR);
    sleep_ms(10);

    /* Steady-state loop: toggle last_data echo, every 4th iteration clear
     * the sticky register to exercise the clear-then-read contract. */
    bool on = true;
    int iter = 0;
    while (true) {
        vdp_reg_write(0x0313u, on ? 0x0088u : 0x0000u);
        sleep_ms(10);
        uint32_t m    = vdp_read_status(0);
        uint32_t d    = vdp_read_status(3);
        uint32_t stky = vdp_read_status(5);
        printf("[smoke] loop on=%d magic=0x%08lx last_data=0x%08lx sticky=0x%08lx\n",
               (int)on, (unsigned long)m, (unsigned long)d, (unsigned long)stky);

        if ((iter & 3) == 3) {
            vdp_clear_sticky(0x000Fu);
            sleep_ms(5);
            uint32_t post = vdp_read_status(5);
            printf("[smoke] CLEAR: post-clear sticky=0x%08lx\n", (unsigned long)post);
        }

        on = !on;
        iter++;
        sleep_ms(500);
    }
}
