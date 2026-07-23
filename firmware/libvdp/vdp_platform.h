/**
 * vdp_platform.h — Board-specific pin map and constants for the VDP host
 *                  driver library (Task 39).
 *
 * Isolates Raspberry Pi Pico 2 (RP2350) + Tang Nano 20K specifics from
 * the rest of the library. Future multi-MCU support would provide an
 * alternate platform header without touching vdp_host / vdp_status /
 * vdp_upload bodies.
 */
#ifndef VDP_PLATFORM_H
#define VDP_PLATFORM_H

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "hardware/pio.h"

/* Pico 2 GPIO → Tang Nano 20K pin mapping (Task 27 full-quad-fidelity) */
#define VDP_PIN_SPI_SCK   8   /* Tang pin 41 */
#define VDP_PIN_SPI_CS_N  9   /* Tang pin 42 */
#define VDP_PIN_SPI_IO0  10   /* Tang pin 48 */
#define VDP_PIN_SPI_IO1  11   /* Tang pin 49 */
#define VDP_PIN_SPI_IO2  12   /* Tang pin 51 */
#define VDP_PIN_SPI_IO3  13   /* Tang pin 54 */

/* PIO unit + state-machine indices reserved for the legacy VDP SPI transport. */
#define VDP_SPI_PIO       pio0
#define VDP_SPI_SM_TX     0

#elif defined(CONFIG_IDF_TARGET_ESP32S3) || defined(ARDUINO_ESP32S3_DEV) || defined(ARDUINO_ESP32S3_DEV_KIT_C_1)
#include <Arduino.h>

#if defined(VDP_SPI_BACKEND_SPI2)

/* ESP32-S3 FSPI/IOMUX SPI host harness.
 *
 * This path is selected explicitly by including `vdp_host.h` before the
 * platform header. The default ESP32-S3 host remains i80.
 */
#define VDP_PIN_SPI_CS_N  10
#define VDP_PIN_SPI_SCK   12
#define VDP_PIN_SPI_IO0   11
#define VDP_PIN_SPI_IO1   13
#define VDP_PIN_SPI_IO2   14
#define VDP_PIN_SPI_IO3    9

#else

/* ESP32-S3-DevKitC-1 8-bit i80 host harness.
 *
 * i80 is the active ESP32-S3 backend. Include `vdp_i80.h` instead of
 * `vdp_host.h` only when intentionally building an i80 sketch.
 *
 *   D0..D7 GPIO4..11 -> Tang pins 25/26/27/28/29/30/31/41
 *   DC     GPIO15    -> Tang pin 85
 *   CS#    GPIO16    -> Tang pin 76
 *   WR#    GPIO17    -> Tang pin 77
 *   RD#    GPIO18    -> Tang pin 80
 */
#define VDP_PIN_I80_D0     4
#define VDP_PIN_I80_D1     5
#define VDP_PIN_I80_D2     6
#define VDP_PIN_I80_D3     7
#define VDP_PIN_I80_D4     8
#define VDP_PIN_I80_D5     9
#define VDP_PIN_I80_D6    10
#define VDP_PIN_I80_D7    11
#define VDP_PIN_I80_DC    15
#define VDP_PIN_I80_CS_N  16
#define VDP_PIN_I80_WR_N  17
#define VDP_PIN_I80_RD_N  18

#ifndef VDP_HOST_BACKEND_I80_GPIO
#define VDP_HOST_BACKEND_I80_GPIO 1
#endif

#endif

#elif defined(ESP32)
#include <Arduino.h>

/* ESP32 dev1 GPIO → Tang Nano 20K pin mapping (BronzeGate #8987) */
#define VDP_PIN_SPI_SCK   18
#define VDP_PIN_SPI_CS_N  19
#define VDP_PIN_SPI_IO0   23
#define VDP_PIN_SPI_IO1   22
#define VDP_PIN_SPI_IO2   25
#define VDP_PIN_SPI_IO3   27

#elif defined(ESP8266)
#include <Arduino.h>

/* ESP8266 NodeMCU 1.0 GPIO → Tang Nano 20K pin mapping (BronzeGate #9123) */
#define VDP_PIN_SPI_SCK   14   /* D5 */
#define VDP_PIN_SPI_CS_N  12   /* D6 */
#define VDP_PIN_SPI_IO0   13   /* D7 */
#define VDP_PIN_SPI_IO1    5   /* D1 */
#define VDP_PIN_SPI_IO2    4   /* D2 */
#define VDP_PIN_SPI_IO3   16   /* D0 - RTC pad, needs digitalWrite */

#else
#error "Unsupported platform for libvdp"
#endif

/* SCK frequency policy on legacy SPI/SPI2 backends (bench-validated
 * 2026-05-23 via the throughput sweep sketch on FSPI IOMUX pins 9..14):
 *
 *   - Reads (READ_STATUS, sticky status, etc.): FPGA QspiSlave response FSM
 *     caps cleanly at 3 MHz. Above 3 MHz, reads fail 100% binary (likely
 *     pixel-clock bound on the FPGA side). Read throughput is CPU-overhead-
 *     bound anyway (~103 µs per call) so SCK rate doesn't matter for reads.
 *
 *   - Writes (REG_WRITE, SDRAM_WRITE): bench-clean at 80 MHz (IOMUX max).
 *     However, Phase 1A physical constraints (25.2 MHz oversampler) dictate
 *     a strict Nyquist ceiling of 12.6 MHz, and the current wiring shows
 *     intermittent byte/nibble corruption at 8 MHz (QSPI-SI-CEILING-183).
 *     The maximum stable production write speed is therefore capped at 4 MHz.
 *
 * Default = 3 MHz so first-call READ_STATUS magic works out of the box.
 * Sketches doing bulk uploads should call:
 *
 *     vdp_host_set_speed_hz(4000000u);   // before write-heavy section
 *     ...
 *     vdp_host_set_speed_hz( 3000000u);   // before next read
 *
 * Bit-bang platforms (ESP8266 / legacy ESP32) keep their canonical 2 MHz
 * cadence — the set_speed_hz call is a no-op there. */
#if defined(VDP_SPI_BACKEND_SPI2)
#define VDP_SPI_SCK_HZ    3000000u    /* boot/read default */
#define VDP_SPI_SCK_WRITE_HZ 4000000u  /* firmware physical cap */
#else
#define VDP_SPI_SCK_HZ    2000000u
#endif

/* Host-neutral aliases. The VDP_SPI_* names remain ABI/source-compatible
 * for legacy sketches and platform branches. New code should prefer these
 * VDP_HOST_* names unless it explicitly targets the SPI backend. */
#if defined(VDP_PIN_SPI_SCK) && !defined(VDP_PIN_HOST_SCK)
#define VDP_PIN_HOST_SCK   VDP_PIN_SPI_SCK
#endif
#if defined(VDP_PIN_SPI_CS_N) && !defined(VDP_PIN_HOST_CS_N)
#define VDP_PIN_HOST_CS_N  VDP_PIN_SPI_CS_N
#endif
#if defined(VDP_PIN_SPI_IO0) && !defined(VDP_PIN_HOST_IO0)
#define VDP_PIN_HOST_IO0   VDP_PIN_SPI_IO0
#endif
#if defined(VDP_PIN_SPI_IO1) && !defined(VDP_PIN_HOST_IO1)
#define VDP_PIN_HOST_IO1   VDP_PIN_SPI_IO1
#endif
#if defined(VDP_PIN_SPI_IO2) && !defined(VDP_PIN_HOST_IO2)
#define VDP_PIN_HOST_IO2   VDP_PIN_SPI_IO2
#endif
#if defined(VDP_PIN_SPI_IO3) && !defined(VDP_PIN_HOST_IO3)
#define VDP_PIN_HOST_IO3   VDP_PIN_SPI_IO3
#endif
#if defined(VDP_SPI_PIO) && !defined(VDP_HOST_PIO)
#define VDP_HOST_PIO       VDP_SPI_PIO
#endif
#if defined(VDP_SPI_SM_TX) && !defined(VDP_HOST_SM_TX)
#define VDP_HOST_SM_TX     VDP_SPI_SM_TX
#endif
#if defined(VDP_SPI_SCK_HZ) && !defined(VDP_HOST_SCK_HZ)
#define VDP_HOST_SCK_HZ    VDP_SPI_SCK_HZ
#endif
#if defined(VDP_SPI_SCK_WRITE_HZ) && !defined(VDP_HOST_SCK_WRITE_HZ)
#define VDP_HOST_SCK_WRITE_HZ VDP_SPI_SCK_WRITE_HZ
#endif

#endif /* VDP_PLATFORM_H */
