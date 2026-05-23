/**
 * esp32s3_red_screen.ino — minimal "can the host write to the FPGA?" test.
 *
 * Does nothing but disable layers/sprites/border (composite index → 0) and
 * write palette[0] = pure red. If the screen turns red, the write path
 * works end-to-end; if it stays black, the issue is host-side write
 * integrity or a bitstream/register-map mismatch.
 *
 * No copper, no SDRAM, no bursts >2 words.
 */
#include <Arduino.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.println("red_screen: init");

    vdp_qspi_init();
    delay(100);

    // Disable everything so composite index = 0 → screen = palette[0].
    const vdp_mode0_rect_t empty = { 0u, 0u, 0u, 0u };
    vdp_mode0_set_border_window(&empty, vdp_mode0_border_ctrl(false, 0u));
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);

    // Park copper so it can't be rewriting palette behind us.
    vdp_reg_write(0x0310u, 0x0000u);
    delay(20);

    // DEBUG: ROOT CAUSE FOUND in VdpTop.scala:1340 — compositor `.otherwise`
    // branch uses layer0Bank (=4 grayscale at POR from attribute Mem) even
    // when all layers are disabled. So composite = palette[bank*16 + 0]
    // = palette[64] = bank-4-entry-0 = grayscale ramp[0] = BLACK.
    // Write palette[64] = red; if active area turns red, theory confirmed.
    vdp_mode0_palette_write_rgb888(0u,  0u, 255u, 0u);   // palette[0]  = green (strip canary)
    vdp_mode0_palette_write_rgb888(64u, 255u, 0u, 0u);   // palette[64] = red   (active-area test)

    Serial.println("red_screen: palette[0] = (255,0,0) written");

    // Verify QSPI link is still alive after writes.
    const uint32_t magic = vdp_read_status(0);
    Serial.print("red_screen: post-write magic = 0x");
    Serial.println(magic, HEX);
}

void loop(void)
{
    // DEBUG: BronzeGate hypothesis (#10531) — scenarioId=0 bootstrap loads
    // a default copper program that rewrites 0x0300 at y=160 and y=320 per
    // frame. If our setup-time 0x0310=0 didn't land, copper keeps running
    // and re-enables layers in two horizontal bands every frame. Hammer
    // copper-disable + layer-disable in the loop to test.
    vdp_reg_write(0x0310u, 0x0000u);       // copper disable
    vdp_mode0_set_layer_enable(0x0000u);   // 0x0300 = 0
    delay(50);
}
