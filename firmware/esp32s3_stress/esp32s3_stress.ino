/**
 * esp32s3_stress.ino — intense mixed-traffic stress test for the S3 QSPI host.
 *
 * Goal: hammer the QSPI transport with a representative mix of:
 *   - Many small writes (1-word palette + single-register writes)
 *   - Long bursts (full 253-word REG_WRITE_BURST payloads to copper RAM)
 *   - Interleaved READ_STATUS magic reads (verifies link stays alive)
 *
 * Each iteration:
 *   1. 100 × READ_STATUS sel=0; verify magic = 0x51560002, count any mismatch.
 *   2. 100 × single palette[64] write (color cycling through R/G/B/W/K). The
 *      cycling makes the screen flash through colors — visually obvious if
 *      the host gets stuck.
 *   3. 4 × maximum-burst REG_WRITE (253 words = 506-byte payload + 6-byte
 *      header = 512 B frame) to copper RAM (benign address space, won't
 *      affect display since copper is parked).
 *   4. 1 final palette[64] write to set the per-iteration "heartbeat color"
 *      so you can see the iteration cadence.
 *
 * Every 1 second, prints to serial:
 *   - Total reads / writes / bursts
 *   - Read errors (magic mismatch)
 *   - Iterations/sec (proxy for effective transport throughput)
 *
 * If everything is clean: screen pulses through colors at 1+ Hz, serial
 * reports growing counters with err=0, no resets.
 *
 * If transport breaks: screen freezes, error count jumps, or sketch hangs.
 */
#include <Arduino.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>

namespace {
constexpr uint32_t kExpectedMagic = 0x51560002u;
constexpr uint16_t kCopperBase    = 0x0400u;   // copper RAM (benign while parked)
constexpr uint16_t kBurstWords    = 253u;      // host-side burst cap

uint16_t  big_buffer[256];
uint32_t  read_count   = 0;
uint32_t  read_errors  = 0;
uint32_t  write_count  = 0;
uint32_t  burst_count  = 0;
uint32_t  iter_count   = 0;
uint32_t  last_report_ms = 0;

void heartbeat_color(uint32_t step, uint8_t *r, uint8_t *g, uint8_t *b)
{
    switch (step & 0x7) {
    case 0: *r=255; *g=0;   *b=0;   break;   // red
    case 1: *r=255; *g=255; *b=0;   break;   // yellow
    case 2: *r=0;   *g=255; *b=0;   break;   // green
    case 3: *r=0;   *g=255; *b=255; break;   // cyan
    case 4: *r=0;   *g=0;   *b=255; break;   // blue
    case 5: *r=255; *g=0;   *b=255; break;   // magenta
    case 6: *r=255; *g=255; *b=255; break;   // white
    default: *r=64; *g=64;  *b=64;  break;   // dim gray
    }
}
}

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("=== esp32s3 STRESS TEST ===");
    Serial.print("Expected magic = 0x");
    Serial.println(kExpectedMagic, HEX);

    vdp_qspi_init();
    delay(100);

    // Park copper so we can scribble to copper RAM without side effects.
    vdp_reg_write(0x0310u, 0x0000u);
    delay(20);

    // Pre-seed palette[64] so the active area paints something we can see.
    vdp_mode0_palette_write_rgb888(64u, 255u, 0u, 0u);

    // Fill the big-burst buffer with a recognisable pattern.
    for (uint16_t i = 0; i < 256; ++i) {
        big_buffer[i] = (uint16_t)(0xC000u | i);   // copper JUMP-like opcode, harmless
    }

    Serial.println("stress: setup done, entering loop");
    last_report_ms = millis();
}

void loop(void)
{
    /* ----- 1. Many small reads ----- */
    for (int i = 0; i < 100; ++i) {
        uint32_t magic = vdp_read_status(0);
        if (magic != kExpectedMagic) read_errors++;
        read_count++;
    }

    /* ----- 2. Many small writes (palette[64] cycle through 8 colors) ----- */
    for (int i = 0; i < 100; ++i) {
        uint8_t r, g, b;
        heartbeat_color(iter_count + i, &r, &g, &b);
        vdp_mode0_palette_write_rgb888(64u, r, g, b);
        write_count++;
    }

    /* ----- 3. Long bursts (4 × max-size 253-word REG_WRITE to copper RAM) ----- */
    for (int i = 0; i < 4; ++i) {
        vdp_reg_write_burst((uint32_t)kCopperBase, big_buffer, kBurstWords);
        burst_count++;
    }

    /* ----- 4. Heartbeat color matching this iteration (visible flicker) ----- */
    uint8_t r, g, b;
    heartbeat_color(iter_count, &r, &g, &b);
    vdp_mode0_palette_write_rgb888(64u, r, g, b);
    write_count++;

    iter_count++;

    /* ----- 5. Stats once per second ----- */
    uint32_t now = millis();
    if (now - last_report_ms >= 1000u) {
        uint32_t dt = now - last_report_ms;
        Serial.print("iter=");      Serial.print(iter_count);
        Serial.print(" reads=");    Serial.print(read_count);
        Serial.print(" err=");      Serial.print(read_errors);
        Serial.print(" writes=");   Serial.print(write_count);
        Serial.print(" bursts=");   Serial.print(burst_count);
        Serial.print(" iter/s=");   Serial.print((iter_count * 1000UL) / (millis() + 1));
        Serial.print(" dt_ms=");    Serial.println(dt);
        last_report_ms = now;
    }
}
