/**
 * vdp_platform.h — Board-specific pin map and constants for the VDP host
 *                  driver library (Task 39).
 *
 * Isolates Raspberry Pi Pico 2 (RP2350) + Tang Nano 20K specifics from
 * the rest of the library. Future multi-MCU support would provide an
 * alternate platform header without touching vdp_qspi / vdp_status /
 * vdp_upload bodies.
 */
#ifndef VDP_PLATFORM_H
#define VDP_PLATFORM_H

#include "hardware/pio.h"

/* Pico 2 GPIO → Tang Nano 20K pin mapping (Task 27 full-quad-fidelity) */
#define VDP_PIN_QSPI_SCK   8   /* Tang pin 41 */
#define VDP_PIN_QSPI_CS_N  9   /* Tang pin 42 */
#define VDP_PIN_QSPI_IO0  10   /* Tang pin 48 */
#define VDP_PIN_QSPI_IO1  11   /* Tang pin 49 */
#define VDP_PIN_QSPI_IO2  12   /* Tang pin 51 */
#define VDP_PIN_QSPI_IO3  13   /* Tang pin 54 */

/* SCK frequency — 2 MHz matches the proven Task 34/35/38 cadence.
 * Higher rates require re-validating PIO OSR drain + SDRAM CDC margin. */
#define VDP_QSPI_SCK_HZ    2000000u

/* PIO unit + state-machine indices reserved for the VDP QSPI transport. */
#define VDP_QSPI_PIO       pio0
#define VDP_QSPI_SM_TX     0

#endif /* VDP_PLATFORM_H */
