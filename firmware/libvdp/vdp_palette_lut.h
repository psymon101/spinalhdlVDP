/**
 * vdp_palette_lut.h — Per-platform palette LUT helpers.
 *
 * Converts platform-native palette values into RGB888 and writes them
 * through the generic Mode0 palette primitives. No FPGA changes needed.
 */
#ifndef VDP_PALETTE_LUT_H
#define VDP_PALETTE_LUT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------
 *  TMS9918A — fixed 16-color palette
 * ------------------------------------------------------------------ */

/** Load the canonical TMS9918A fixed palette into Mode0 palette RAM.
 *  Fills entries 0..15. Entry 0 is transparent (black).
 *  Source: EP994A VHDL reference (kb/TMS9918/references/EP994A/tms9918.vhd)
 *          citing MSX.org forum consensus values.
 */
void vdp_tms9918_load_palette(void);

/* ------------------------------------------------------------------
 *  Sega Master System — 6-bit CRAM  (--BBGGRR)
 * ------------------------------------------------------------------ */

/** Write one SMS palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   SMS CRAM byte: --BBGGRR (2 bits per channel)
 */
void vdp_sms_palette_write(uint8_t idx, uint8_t native_val);

/* ------------------------------------------------------------------
 *  Game Gear — 12-bit CRAM  (--------BBBBGGGGRRRR)
 * ------------------------------------------------------------------ */

/** Write one Game Gear palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   GG CRAM word: --------BBBBGGGGRRRR (4 bits per channel)
 */
void vdp_gg_palette_write(uint8_t idx, uint16_t native_val);

/* ------------------------------------------------------------------
 *  Atari ST  — 9-bit palette  (0000 0RRR 0GGG 0BBB)
 * ------------------------------------------------------------------ */

/** Write one Atari ST palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   ST palette word: 0000 0RRR 0GGG 0BBB (3 bits per channel)
 */
void vdp_atarist_palette_write(uint8_t idx, uint16_t native_val);

/* ------------------------------------------------------------------
 *  Atari STE — 12-bit palette  (0000 Rrrr Gggg Bbbb)
 * ------------------------------------------------------------------ */

/** Write one Atari STE palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   STE palette word: 0000 Rrrr Gggg Bbbb (4 bits per channel)
 */
void vdp_atariste_palette_write(uint8_t idx, uint16_t native_val);

#ifdef __cplusplus
}
#endif

#endif /* VDP_PALETTE_LUT_H */
