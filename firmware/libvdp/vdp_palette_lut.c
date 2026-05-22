/**
 * vdp_palette_lut.c — Per-platform palette LUT helpers.
 *
 * Each helper converts a platform-native palette value to RGB888 and
 * writes it through vdp_mode0_palette_write_rgb888().
 */
#include "vdp_palette_lut.h"
#include "vdp_mode0.h"

/* ------------------------------------------------------------------
 *  TMS9918A fixed palette
 *  Source: EP994A VHDL reference (kb/TMS9918/references/EP994A/tms9918.vhd)
 *  citing MSX.org forum consensus values.
 * ------------------------------------------------------------------ */
static const uint8_t tms9918_palette[16][3] = {
    /* 0  transparent */ {0x00, 0x00, 0x00},
    /* 1  black       */ {0x00, 0x00, 0x00},
    /* 2  medium green*/ {0x00, 0xF1, 0x14},
    /* 3  light green */ {0x44, 0xF9, 0x56},
    /* 4  dark blue   */ {0x55, 0x4F, 0xFF},
    /* 5  light blue  */ {0x80, 0x6F, 0xFF},
    /* 6  dark red    */ {0xFA, 0x50, 0x33},
    /* 7  cyan        */ {0x0C, 0xFF, 0xFF},
    /* 8  medium red  */ {0xFF, 0x51, 0x34},
    /* 9  light red   */ {0xFF, 0x73, 0x56},
    /* A  dark yellow */ {0xE2, 0xD2, 0x04},
    /* B  light yellow*/ {0xF2, 0xD9, 0x47},
    /* C  dark green  */ {0x04, 0xD4, 0x13},
    /* D  magenta     */ {0xE7, 0x50, 0xE5},
    /* E  gray        */ {0xD0, 0xD0, 0xD0},
    /* F  white       */ {0xFF, 0xFF, 0xFF},
};

void vdp_tms9918_load_palette(void)
{
    for (uint8_t i = 0; i < 16u; ++i) {
        vdp_mode0_palette_write_rgb888(i,
            tms9918_palette[i][0],
            tms9918_palette[i][1],
            tms9918_palette[i][2]);
    }
}

/* ------------------------------------------------------------------
 *  SMS 6-bit: --BBGGRR  (2 bits per channel)
 *  Expansion policy: replicate bits to fill 8 bits.
 *    2-bit -> 8-bit:  (v << 6) | (v << 4) | (v << 2) | v
 *    e.g. 0 -> 0, 1 -> 0x55, 2 -> 0xAA, 3 -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_sms_palette_write(uint8_t idx, uint8_t native_val)
{
    uint8_t r = (native_val >> 0) & 0x03u;
    uint8_t g = (native_val >> 2) & 0x03u;
    uint8_t b = (native_val >> 4) & 0x03u;

    r = (r << 6) | (r << 4) | (r << 2) | r;
    g = (g << 6) | (g << 4) | (g << 2) | g;
    b = (b << 6) | (b << 4) | (b << 2) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Game Gear 12-bit: --------BBBBGGGGRRRR  (4 bits per channel)
 *  Expansion policy: replicate nibble to fill 8 bits.
 *    4-bit -> 8-bit:  (v << 4) | v
 *    e.g. 0 -> 0, 0xF -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_gg_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 0)  & 0x0Fu;
    uint8_t g = (uint8_t)(native_val >> 4)  & 0x0Fu;
    uint8_t b = (uint8_t)(native_val >> 8)  & 0x0Fu;

    r = (r << 4) | r;
    g = (g << 4) | g;
    b = (b << 4) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Atari ST 9-bit: 0000 0RRR 0GGG 0BBB  (3 bits per channel)
 *  Expansion policy: bit-replication to fill 8 bits.
 *    3-bit -> 8-bit:  (v << 5) | (v << 2) | (v >> 1)
 *    e.g. 0 -> 0, 7 -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_atarist_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 8) & 0x07u;
    uint8_t g = (uint8_t)(native_val >> 4) & 0x07u;
    uint8_t b = (uint8_t)(native_val >> 0) & 0x07u;

    r = (r << 5) | (r << 2) | (r >> 1);
    g = (g << 5) | (g << 2) | (g >> 1);
    b = (b << 5) | (b << 2) | (b >> 1);

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Atari STE 12-bit: 0000 Rrrr Gggg Bbbb  (4 bits per channel)
 *  Expansion policy: replicate nibble to fill 8 bits.
 * ------------------------------------------------------------------ */
void vdp_atariste_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 8) & 0x0Fu;
    uint8_t g = (uint8_t)(native_val >> 4) & 0x0Fu;
    uint8_t b = (uint8_t)(native_val >> 0) & 0x0Fu;

    r = (r << 4) | r;
    g = (g << 4) | g;
    b = (b << 4) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}
