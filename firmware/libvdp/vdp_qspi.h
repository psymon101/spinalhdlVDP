/**
 * vdp_qspi.h — Deprecated compatibility header.
 *
 * New firmware should include `vdp_host.h` and call `vdp_host_init()`.
 * This shim preserves legacy sketches that still include `vdp_qspi.h`
 * or call `vdp_qspi_init()`.
 */
#ifndef VDP_QSPI_H
#define VDP_QSPI_H

#error "vdp_qspi.h is retired. Include firmware/libvdp/vdp_host.h and use vdp_host_init() instead."

#include "vdp_host.h"

#endif /* VDP_QSPI_H */
