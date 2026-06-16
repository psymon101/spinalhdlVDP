/**
 * vdp_qspi.h — Deprecated compatibility header.
 *
 * New firmware should include `vdp_host.h` and call `vdp_host_init()`.
 * This shim preserves legacy sketches that still include `vdp_qspi.h`
 * or call `vdp_qspi_init()`.
 */
#ifndef VDP_QSPI_H
#define VDP_QSPI_H

#include "vdp_host.h"

#endif /* VDP_QSPI_H */
