/*
 * esp8266_copper_bounce_demo.ino — Double-buffered bouncing copper bars (3b CP-E).
 *
 * Uses R5.4 double-buffered Copper RAM with atomic bank swap.
 * Three colored bars bounce vertically via per-frame program upload
 * to the inactive bank, followed by COPPER_SWAP_REQUEST.
 *
 * Task reference: BrightForge #10236 (CP-E firmware handoff)
 * RTL commit:    d32616d on mode0t20-barebones-rebuild
 *
 * Pin map (NodeMCU 1.0 — BronzeGate-approved):
 *   GPIO14 (D5) -> SCK
 *   GPIO13 (D7) -> MOSI
 *   GPIO12 (D6) -> MISO
 *   GPIO15 (D8) -> CS_N
 *
 * Boot sequence:
 *   1. QSPI init, 200 ms settle
 *   2. Disable layers, bitmap, color math (border-only output)
 *   3. Upload 32-entry rainbow palette
 *   4. Set empty border rect (border active everywhere)
 *   5. Build initial copper program, upload to bank 0, enable copper
 *   6. Per-frame in loop():
 *        a. Compute new bar Y positions (sinusoidal motion)
 *        b. Build copper program for inactive bank
 *        c. Burst-upload to COPPER_RAM_BASE @ 0x0400 (enabled → inactive bank)
 *        d. Request atomic swap via vdp_copper_swap_request()
 *
 * Expected on-screen result:
 *   Three horizontal bars (red, green, blue) bouncing smoothly up and down
 *   against a black background. No tearing or glitching during swap.
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>
#include <vdp_copper.h>

#define NUM_BARS      3
#define BAR_THICKNESS 24
#define AMPLITUDE     180
#define CENTER_Y      240
#define PERIOD_F      180.0f

static uint16_t copper_prog[32];
static float    phase[NUM_BARS] = { 0.0f, 2.094f, 4.189f }; /* 0, 120°, 240° */
static uint8_t  palette_idx[NUM_BARS] = { 8, 16, 24 };      /* orange, green, purple */
static uint16_t frame = 0;

static void upload_rainbow_palette(void)
{
    /* Entry 0 = black (background) */
    vdp_mode0_palette_write_rgb888(0, 0, 0, 0);

    for (uint8_t i = 1; i < 32; ++i) {
        uint16_t hue = i * 360 / 32;
        uint8_t r, g, b;
        uint8_t hi = hue / 60;
        uint8_t f  = (((hue % 60) * 255) / 60);
        uint8_t p = 0, q = 255 - f, t = f;
        switch (hi % 6) {
            case 0: r = 255; g = t;   b = p;   break;
            case 1: r = q;   g = 255; b = p;   break;
            case 2: r = p;   g = 255; b = t;   break;
            case 3: r = p;   g = q;   b = 255; break;
            case 4: r = t;   g = p;   b = 255; break;
            case 5: r = 255; g = p;   b = q;   break;
        }
        vdp_mode0_palette_write_rgb888(i, r, g, b);
    }
}

static uint16_t build_bounce_program(uint16_t *prog, const uint16_t *ys,
                                      const uint8_t *idxs, uint8_t n)
{
    uint16_t pc = 0;

    /* Ensure screen starts black before first bar */
    prog[pc++] = vdp_copper_wait(0);
    prog[pc++] = (uint16_t)(0x4000u | 0x0347u);
    prog[pc++] = vdp_mode0_border_ctrl(true, 0); /* black */

    for (uint8_t i = 0; i < n; ++i) {
        uint16_t y0 = ys[i];
        uint16_t y1 = y0 + BAR_THICKNESS;
        if (y0 > 480u) y0 = 480u;
        if (y1 > 480u) y1 = 480u;

        prog[pc++] = vdp_copper_wait(y0);         /* WAIT(Y) — bar top */
        prog[pc++] = (uint16_t)(0x4000u | 0x0347u); /* WRITE BORDER_CTRL */
        prog[pc++] = vdp_mode0_border_ctrl(true, idxs[i]);

        prog[pc++] = vdp_copper_wait(y1);         /* WAIT(Y+thickness) — bar bottom */
        prog[pc++] = (uint16_t)(0x4000u | 0x0347u); /* WRITE BORDER_CTRL */
        prog[pc++] = vdp_mode0_border_ctrl(true, 0); /* black */
    }
    prog[pc++] = vdp_copper_jump(0);             /* loop */
    return pc;
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println(F("\n=== Copper Bounce Demo (3b CP-E) ===\n"));

    vdp_qspi_init();
    delay(200);

    upload_rainbow_palette();

    /* Disable all rendering sources so only border shows */
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_reg_write(0x0350u, 0x0000u);
    vdp_reg_write(0x0334u, 0x0000u);

    /* Border everywhere: empty inner rect */
    vdp_mode0_rect_t rect = { 0, 1280, 0, 0 };
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));

    /* Initial program: all bars at center */
    uint16_t ys[NUM_BARS];
    for (uint8_t i = 0; i < NUM_BARS; ++i) ys[i] = CENTER_Y;
    uint16_t prog_len = build_bounce_program(copper_prog, ys, palette_idx, NUM_BARS);
    vdp_copper_upload(copper_prog, prog_len);
    vdp_copper_enable(true);

    Serial.printf("Copper: initial program = %u words, %u bars enabled.\n",
                  prog_len, NUM_BARS);
    Serial.println(F("Starting bounce loop..."));
}

void loop(void)
{
    /* Compute new Y positions (sinusoidal bounce) */
    uint16_t ys[NUM_BARS];
    for (uint8_t i = 0; i < NUM_BARS; ++i) {
        float rad = (2.0f * 3.14159265f * (float)frame / PERIOD_F) + phase[i];
        float yf = (float)CENTER_Y + (float)AMPLITUDE * sinf(rad);
        int16_t y = (int16_t)yf;
        if (y < 0) y = 0;
        ys[i] = (uint16_t)y;
    }

    /* Build program for inactive bank */
    uint16_t prog_len = build_bounce_program(copper_prog, ys, palette_idx, NUM_BARS);

    /* Upload to inactive bank (copper enabled → writes route to inactive bank) */
    vdp_reg_write_burst(0x0400u, copper_prog, prog_len);

    /* Request atomic swap at next vSyncStart */
    vdp_copper_swap_request();

    frame++;
    delay(33);  /* ~30 fps */
}
