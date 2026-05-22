#include <stdio.h>
#include "pico/stdlib.h"
#include "vdp_palette_lut.h"

int main(void)
{
    stdio_init_all();
    sleep_ms(100);

    printf("Pico palette LUT smoke test\n");

    vdp_tms9918_load_palette();
    vdp_sms_palette_write(16, 0x3F);
    vdp_gg_palette_write(17, 0x0FFF);
    vdp_atarist_palette_write(18, 0x0777);
    vdp_atariste_palette_write(19, 0x0FFF);

    printf("Smoke test complete.\n");
    while (1) {
        tight_loop_contents();
    }
}
