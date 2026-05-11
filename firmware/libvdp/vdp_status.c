/**
 * vdp_status.c — Status polling + sticky bit helpers.
 */
#include "vdp_status.h"
#include "vdp_qspi.h"
#include "vdp_platform.h"

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "pico/stdlib.h"
#elif defined(ARDUINO)
#include <Arduino.h>
#endif

void vdp_clear_sticky(uint16_t mask)
{
    vdp_reg_write(0x0320u, mask);
}

bool vdp_wait_sticky(uint16_t bit_mask, uint32_t timeout_us)
{
    while (true) {
        uint32_t s = vdp_read_status(5);
        if ((s & bit_mask) == bit_mask) return true;
        if (timeout_us == 0) return false;
        uint32_t step = (timeout_us < 50u) ? timeout_us : 50u;
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
        busy_wait_us_32(step);
#else
        delayMicroseconds(step);
#endif
        timeout_us -= step;
    }
}

bool vdp_wait_vblank(uint32_t timeout_us)
{
    vdp_clear_sticky(VDP_STICKY_RASTER_MATCH);
    return vdp_wait_sticky(VDP_STICKY_RASTER_MATCH, timeout_us);
}
