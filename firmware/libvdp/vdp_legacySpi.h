/**
 * vdp_legacySpi.h — Deprecated compatibility header for legacy SPI sketches.
 *
 * New firmware should include `vdp_host.h` and call `vdp_host_init()`.
 * This shim preserves older sketches that still include `vdp_legacySpi.h`
 * or call the legacy `vdp_legacy_spi_*` aliases.
 */
#ifndef VDP_LEGACY_SPI_H
#define VDP_LEGACY_SPI_H

#include "vdp_host.h"

#ifdef __cplusplus
extern "C" {
#endif

void vdp_legacy_spi_init(void);
void vdp_legacy_spi_set_speed_hz(uint32_t hz);

#ifdef __cplusplus
}
#endif

#endif /* VDP_LEGACY_SPI_H */
