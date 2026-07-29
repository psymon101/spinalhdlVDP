/*
 * esp8266_copper_bounce_demo.ino — Double-buffered bouncing copper bars (3b CP-E).
 *
 * Uses R5.4 double-buffered Copper RAM with atomic bank swap.
 * Three gradient bars (red, green, blue) bounce vertically via per-frame
 * program upload to the inactive bank, followed by COPPER_SWAP_REQUEST.
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
 *   3. Upload gradient palettes (24 entries, slots 1..24)
 *   4. Set empty border rect (border active everywhere)
 *   5. Build initial copper program, upload to bank 0, enable copper
 *   6. Per-frame in loop():
 *        a. Compute new bar Y positions (sinusoidal motion)
 *        b. Build gradient copper program for inactive bank
 *        c. Burst-upload to COPPER_RAM_BASE @ 0x0400 (enabled → inactive bank)
 *        d. Request atomic swap via vdp_copper_swap_request()
 *
 * Expected on-screen result:
 *   Three gradient-shaded horizontal bars (red, green, blue) bouncing smoothly
 *   up and down against a black background. Each bar dark→bright→dark like a
 *   3D tube. No tearing or glitching during swap.
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>
#include <vdp_copper.h>

#define NUM_BARS          3
#define BAR_THICKNESS     24
#define GRADIENT_STEPS    8
#define LINES_PER_STEP    (BAR_THICKNESS / GRADIENT_STEPS)   /* = 3 */
#define BAR_PALETTE_START 1                                   /* bar i: [1+i*8 .. 8+i*8] */
#define AMPLITUDE         180
#define CENTER_Y          240
#define PERIOD_F          180.0f

static uint16_t copper_prog[160];
static float    phase[NUM_BARS] = { 0.0f, 2.094f, 4.189f }; /* 0, 120°, 240° */
static uint16_t frame = 0;

static const uint8_t bar_base_rgb[NUM_BARS][3] = {
    { 255,   0,   0 },   /* bar 0: red    */
    {   0, 255,   0 },   /* bar 1: green  */
    {   0,   0, 255 },   /* bar 2: blue   */
};

static void upload_bar_gradient_palette(uint8_t bar,
                                         uint8_t r, uint8_t g, uint8_t b)
{
    uint8_t base = BAR_PALETTE_START + bar * GRADIENT_STEPS;
    for (uint8_t step = 0; step < GRADIENT_STEPS; ++step) {
        /* Half-sine brightness ramp: dim → peak → dim */
        float t = ((float)step + 0.5f) / (float)GRADIENT_STEPS;
        float scale = 0.2f + 0.8f * sinf(3.14159265f * t);
        vdp_mode0_palette_write_rgb888(base + step,
            (uint8_t)((float)r * scale),
            (uint8_t)((float)g * scale),
            (uint8_t)((float)b * scale));
    }
}

static void upload_all_bar_palettes(void)
{
    vdp_mode0_palette_write_rgb888(0, 0, 0, 0);   /* black background */
    for (uint8_t i = 0; i < NUM_BARS; ++i) {
        upload_bar_gradient_palette(i,
            bar_base_rgb[i][0], bar_base_rgb[i][1], bar_base_rgb[i][2]);
    }
}

static uint16_t build_bounce_program(uint16_t *prog, const uint16_t *ys,
                                      uint8_t n)
{
    uint16_t pc = 0;

    /* Each bar contributes GRADIENT_STEPS sub-bars = 2 events each. */
    typedef struct { uint16_t y; uint8_t bar; uint8_t step; uint8_t is_enter; } event_t;
    event_t events[NUM_BARS * GRADIENT_STEPS * 2];
    uint16_t n_events = 0;

    for (uint8_t i = 0; i < n; ++i) {
        for (uint8_t s = 0; s < GRADIENT_STEPS; ++s) {
            uint16_t y_step = ys[i] + (uint16_t)(s * LINES_PER_STEP);
            uint16_t y_end  = y_step + LINES_PER_STEP;
            if (y_step > 480u) y_step = 480u;
            if (y_end  > 480u) y_end  = 480u;
            events[n_events++] = (event_t){ y_step, i, s, 1 };
            events[n_events++] = (event_t){ y_end,  i, s, 0 };
        }
    }

    /* Insertion sort events by Y. */
    for (uint16_t k = 1; k < n_events; ++k) {
        event_t cur = events[k];
        int16_t j = (int16_t)(k - 1);
        while (j >= 0 && events[(uint16_t)j].y > cur.y) {
            events[(uint16_t)(j + 1)] = events[(uint16_t)j];
            j--;
        }
        events[(uint16_t)(j + 1)] = cur;
    }

    /* Stack of active (bar, step); top = front. */
    struct { uint8_t bar; uint8_t step; } stack[NUM_BARS * GRADIENT_STEPS];
    uint8_t depth = 0;
    uint8_t prev_color = 0;

    /* Initial black anchor at line 0. */
    prog[pc++] = vdp_copper_wait(0);
    prog[pc++] = vdp_copper_write_op(0x0347u);
    prog[pc++] = vdp_mode0_border_ctrl(true, 0);

    /* Walk events; coalesce same-Y before emitting (per #10254). */
    uint16_t e = 0;
    while (e < n_events) {
        uint16_t curY = events[e].y;
        while (e < n_events && events[e].y == curY) {
            if (events[e].is_enter) {
                stack[depth].bar  = events[e].bar;
                stack[depth].step = events[e].step;
                depth++;
            } else {
                for (uint8_t s = 0; s < depth; ++s) {
                    if (stack[s].bar == events[e].bar &&
                        stack[s].step == events[e].step) {
                        for (uint8_t t = s; t + 1 < depth; ++t)
                            stack[t] = stack[t + 1];
                        depth--;
                        break;
                    }
                }
            }
            e++;
        }
        uint8_t new_color = 0;
        if (depth > 0) {
            new_color = BAR_PALETTE_START
                      + stack[depth - 1].bar  * GRADIENT_STEPS
                      + stack[depth - 1].step;
        }
        if (new_color != prev_color) {
            prog[pc++] = vdp_copper_wait(curY);
            prog[pc++] = vdp_copper_write_op(0x0347u);
            prog[pc++] = vdp_mode0_border_ctrl(true, new_color);
            prev_color = new_color;
        }
    }

    prog[pc++] = vdp_copper_jump(0);
    return pc;
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println(F("\n=== Copper Bounce Demo (3b CP-E, gradient) ===\n"));

    vdp_qspi_init();
    delay(200);

    upload_all_bar_palettes();

    /* Disable all rendering sources so only border shows */
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);

    /* Border everywhere: empty inner rect */
    vdp_mode0_rect_t rect = { 0, 1280, 0, 0 };
    vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));

    /* Initial program: all bars at center */
    uint16_t ys[NUM_BARS];
    for (uint8_t i = 0; i < NUM_BARS; ++i) ys[i] = CENTER_Y;
    uint16_t prog_len = build_bounce_program(copper_prog, ys, NUM_BARS);
    vdp_copper_upload(copper_prog, prog_len);
    vdp_copper_enable(true);

    Serial.printf("Copper: initial program = %u words, %u bars, %u gradient steps.\n",
                  prog_len, NUM_BARS, GRADIENT_STEPS);
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
    uint16_t prog_len = build_bounce_program(copper_prog, ys, NUM_BARS);

    /* Upload to inactive bank and request atomic swap */
    vdp_copper_upload_and_swap(copper_prog, prog_len);

    frame++;
    delay(33);  /* ~30 fps */
}
