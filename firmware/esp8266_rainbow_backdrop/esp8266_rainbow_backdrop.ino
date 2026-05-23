/**
 * esp8266_rainbow_backdrop.ino
 *
 * Backdrop-rainbow demo: copper rewrites palette[0] every kBandLines source
 * scanlines so the rainbow appears as the *background*. Any layer or sprite
 * with non-zero palette indices renders in *front* of the backdrop — the
 * inverse of esp8266_rainbow_background.ino which uses the BORDER override
 * (final HDMI mux stage) and paints over everything.
 *
 * Compositor flow exploited here:
 *
 *   Layers L0..L3 (all disabled here)        → palette index 0
 *   Sprites      (all disabled here)         → palette index 0
 *   Border       (disabled here)             → no override
 *   ──────────────────────────────────────────────────────────
 *   Final pixel = palette[ layer0Bank * 16 + layer0Pixel ]
 *
 *   At POR, layer0Bank is sourced from uninitialized SDRAM attribute
 *   memory, which typically defaults to Bank 4.  The copper FSM
 *   rewrites palette[0] per band, but the active area reads from
 *   palette[64] (Bank 4, entry 0) unless attribute memory has been
 *   initialised to Bank 0.
 *
 *   TODO(BronzeGate): initialise attribute memory to Bank 0 in
 *   make_clean_state(), or retarget the copper program to update
 *   all eight bank-0 slots (0, 16, 32, 48, 64, 80, 96, 112).
 *
 * Copper program structure per band (7 words):
 *   WAIT y = i * kBandLines           ; 1 word  (legacy 1-word WAIT)
 *   WRITE PALETTE_PTR  = 0            ; 2 words (point at palette entry 0,
 *                                                half 0)
 *   WRITE PALETTE_DATA = (g << 8)|b   ; 2 words (commits half 0 - GB pair)
 *   WRITE PALETTE_DATA = r            ; 2 words (commits half 1 - R byte,
 *                                                auto-increments PTR which
 *                                                we reset above next band)
 *
 * Budget: 120 × 7 + 1 (JUMP 0) = 841 words, well inside the 1024-word
 * copper `prog` Mem on the eedf617+ baseline.
 */

#include <Arduino.h>
#include <vdp_copper.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>

namespace {

// NOTE: libvdp's `vdp_copper_upload` currently caps `nwords <= 512` (stale
// limit predating the R5.4-era doubling of copper `prog` Mem to 1024 words).
// Until that's bumped, the program must fit in 512 words. 60 × 8 = 480-line
// full coverage at 421 words — comfortably inside both 512 and 1024.
constexpr uint16_t kBands      = 60u;
constexpr uint16_t kBandLines  = 8u;     // 60 × 8 = 480 source lines (full)
constexpr uint16_t kWordsPerBand = 7u;
constexpr uint16_t kProgWords    = kBands * kWordsPerBand + 1u;  // + JUMP 0
static_assert(kProgWords <= 512u, "vdp_copper_upload nwords cap is 512");

uint16_t copper_prog[1024];

void hue_to_rgb(uint16_t hue, uint8_t *r, uint8_t *g, uint8_t *b)
{
    const uint8_t  region = (uint8_t)((hue / 60u) % 6u);
    const uint16_t rem    = (uint16_t)((hue % 60u) * 255u / 60u);
    const uint8_t  q      = (uint8_t)(255u - rem);
    const uint8_t  t      = (uint8_t)rem;

    switch (region) {
    case 0: *r = 255u; *g = t;    *b = 0u;   break;
    case 1: *r = q;    *g = 255u; *b = 0u;   break;
    case 2: *r = 0u;   *g = 255u; *b = t;    break;
    case 3: *r = 0u;   *g = q;    *b = 255u; break;
    case 4: *r = t;    *g = 0u;   *b = 255u; break;
    default:*r = 255u; *g = 0u;   *b = q;    break;
    }
}

void make_clean_state(void)
{
    // Border disabled — we want the backdrop visible, not the border-override
    // stage. Pass a zero-sized rect with enable=false.
    const vdp_mode0_rect_t empty = { 0u, 0u, 0u, 0u };
    vdp_mode0_set_border_window(&empty, vdp_mode0_border_ctrl(false, 0u));

    // Park the copper before re-uploading and re-enabling.
    vdp_copper_enable(false);

    // Disable every layer + sprites (bit 2).  When all layers are off the
    // compositor falls through to layer0Bank*16 + layer0Pixel, NOT palette[0]
    // (see VDP_PROGRAMMING_GUIDE.md §"Disabled-Layer Backdrop").
    // TODO(BronzeGate): initialise SDRAM attribute memory to Bank 0 here so
    // the compositor reads from palette[0] as expected.
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);
}

uint16_t build_program(void)
{
    uint16_t pc = 0u;

    for (uint16_t i = 0u; i < kBands; ++i) {
        const uint16_t hue = (uint16_t)((uint32_t)i * 360u / kBands);
        uint8_t r = 0u, g = 0u, b = 0u;
        hue_to_rgb(hue, &r, &g, &b);

        // 1) WAIT y = i * kBandLines.
        copper_prog[pc++] = vdp_copper_wait((uint16_t)(i * kBandLines));

        // 2) WRITE PALETTE_PTR = 0 (always entry 0, half 0).
        //    PTR auto-increments after each entry commit (after the second
        //    PALETTE_DATA write below), so we explicitly snap it back to 0
        //    every band — see vdp_mode0_palette_write_rgb888() in libvdp.
        copper_prog[pc++] = vdp_copper_write_op(VDP_MODE0_REG_PALETTE_PTR);
        copper_prog[pc++] = 0u;

        // 3) WRITE PALETTE_DATA half 0 = (G << 8) | B.
        copper_prog[pc++] = vdp_copper_write_op(VDP_MODE0_REG_PALETTE_DATA);
        copper_prog[pc++] = (uint16_t)(((uint16_t)g << 8) | (uint16_t)b);

        // 4) WRITE PALETTE_DATA half 1 = R. This commits palette[0] = (R,G,B)
        //    and auto-advances PTR to entry 1 / half 0 — harmless, the next
        //    band resets PTR back to 0 before any further DATA writes.
        copper_prog[pc++] = vdp_copper_write_op(VDP_MODE0_REG_PALETTE_DATA);
        copper_prog[pc++] = (uint16_t)r;
    }

    // Loop forever.
    copper_prog[pc++] = vdp_copper_jump(0u);
    return pc;
}

}  // namespace

void setup(void)
{
    Serial.begin(115200);
    delay(200);

    vdp_qspi_init();
    delay(200);

    make_clean_state();

    // NB: deliberately do NOT pre-seed palette[0] here. If a prior sketch
    // (e.g. esp8266_rainbow_background) populated palette entries 0..23
    // and its program is still in copper RAM, an explicit pre-seed of
    // palette[0]=black would render as a visible black band on band 0
    // of the OLD program until our upload + enable fully takes effect.

    const uint16_t prog_len = build_program();
    vdp_copper_upload(copper_prog, prog_len);
    vdp_copper_enable(true);

    Serial.print("Backdrop rainbow active: ");
    Serial.print(kBands);
    Serial.print(" bands × ");
    Serial.print(kBandLines);
    Serial.print(" source lines, copper prog ");
    Serial.print(prog_len);
    Serial.println(" words");
}

void loop(void)
{
    delay(1000);
}
