/**
 * esp32s3_throughput.ino — measure read + write throughput across SCK speeds.
 *
 * Sweeps SCK 1, 2, 3, 5, 10, 20, 40, 80 MHz. At each speed:
 *   - 1000 × READ_STATUS sel=0; counts magic mismatches (read errors)
 *   - 100 × max-burst REG_WRITE (253 words = 506 B payload + 6 B header)
 *     to copper RAM (benign while copper is parked)
 *   - 100 × max-burst SDRAM_WRITE (253 words) to a safe scratch SDRAM addr
 *
 * Reports per-speed:
 *   - Read MB/s, read error rate
 *   - REG_WRITE MB/s
 *   - SDRAM_WRITE MB/s
 *
 * Designed to stress the transport without depending on visual verification.
 * Active screen state is parked at palette[64] = red so we know writes are
 * effective even when we can't read back (visible-canary test before each
 * speed sweep).
 */
#include <Arduino.h>
#include <vdp_mode0.h>
#include <vdp_qspi.h>

namespace {
constexpr uint32_t kExpectedMagic = 0x51560002u;
constexpr uint16_t kCopperBase    = 0x0400u;    // copper prog RAM (benign while parked)
constexpr uint32_t kSdramScratch  = 0x400000u;  // 4 MB into SDRAM, well above bitmap area at 0x3000/0x4000
constexpr uint16_t kBurstWords    = 253u;

constexpr uint32_t kReadIters       = 1000u;
constexpr uint32_t kBurstIters      = 100u;

const uint32_t kSpeedTable[] = {
    1000000u, 2000000u, 3000000u, 5000000u,
    10000000u, 20000000u, 40000000u, 80000000u,
};
constexpr size_t kSpeedCount = sizeof(kSpeedTable) / sizeof(kSpeedTable[0]);

uint16_t big_buffer[256];

void run_speed(uint32_t hz)
{
    vdp_qspi_set_speed_hz(hz);
    delay(5);

    Serial.print("\n--- SCK = ");
    Serial.print(hz / 1000000u);
    Serial.println(" MHz ---");

    /* --- Read throughput --- */
    uint32_t read_errors = 0;
    uint32_t t0 = micros();
    for (uint32_t i = 0; i < kReadIters; ++i) {
        uint32_t m = vdp_read_status(0);
        if (m != kExpectedMagic) read_errors++;
    }
    uint32_t t1 = micros();
    uint32_t read_us = t1 - t0;
    /* Each READ_STATUS sends 6 B header + 2 dummy cycles + 4 B response = ~10 B */
    uint32_t read_bytes = kReadIters * 10u;
    float read_mbps = (float)read_bytes / (float)read_us;   /* bytes/µs == MB/s */
    Serial.print("reads:  ");
    Serial.print(kReadIters);
    Serial.print(" in ");
    Serial.print(read_us);
    Serial.print("µs = ");
    Serial.print(read_mbps, 3);
    Serial.print(" MB/s  errors=");
    Serial.print(read_errors);
    Serial.print("/");
    Serial.println(kReadIters);

    /* --- REG_WRITE burst throughput --- */
    t0 = micros();
    for (uint32_t i = 0; i < kBurstIters; ++i) {
        vdp_reg_write_burst((uint32_t)kCopperBase, big_buffer, kBurstWords);
    }
    t1 = micros();
    uint32_t regw_us = t1 - t0;
    /* Each burst payload = 253 words × 2 B = 506 B; header = 6 B; total wire = 512 B */
    uint32_t regw_bytes = kBurstIters * 506u;   /* effective data only */
    float regw_mbps = (float)regw_bytes / (float)regw_us;
    Serial.print("REG_W:  ");
    Serial.print(kBurstIters);
    Serial.print(" × 506 B in ");
    Serial.print(regw_us);
    Serial.print("µs = ");
    Serial.print(regw_mbps, 3);
    Serial.println(" MB/s (effective data)");

    /* --- SDRAM_WRITE burst throughput --- */
    /* Target 0x400000 (4 MB offset) — well above the 0x3000/0x4000 bitmap
     * area used by Mode0 scenarios, in unused SDRAM. */
    t0 = micros();
    for (uint32_t i = 0; i < kBurstIters; ++i) {
        vdp_sdram_write(kSdramScratch, big_buffer, kBurstWords);
    }
    t1 = micros();
    uint32_t sdw_us = t1 - t0;
    uint32_t sdw_bytes = kBurstIters * 506u;
    float sdw_mbps = (float)sdw_bytes / (float)sdw_us;
    Serial.print("SDRAM:  ");
    Serial.print(kBurstIters);
    Serial.print(" × 506 B in ");
    Serial.print(sdw_us);
    Serial.print("µs = ");
    Serial.print(sdw_mbps, 3);
    Serial.println(" MB/s (effective data)");
}
}

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("=== esp32s3 THROUGHPUT SWEEP (init) ===");

    vdp_qspi_init();
    delay(50);

    /* Park copper and make active area visible (canary). */
    vdp_reg_write(0x0310u, 0x0000u);
    delay(20);
    vdp_mode0_palette_write_rgb888(64u, 255u, 0u, 0u);

    /* Pre-fill burst buffer with a recognisable pattern (harmless copper NOPs). */
    for (uint16_t i = 0; i < 256; ++i) {
        big_buffer[i] = (uint16_t)(0xC000u | i);  /* JUMP-pattern, ignored when copper parked */
    }
    Serial.println("setup done — sweeping in loop()");
}

void loop(void)
{
    Serial.println("\n=== SWEEP START ===");
    for (size_t i = 0; i < kSpeedCount; ++i) {
        run_speed(kSpeedTable[i]);
    }
    Serial.println("\n=== SWEEP COMPLETE (next in 5s) ===");
    vdp_qspi_set_speed_hz(3000000u);  /* safe between sweeps */
    delay(5000);
}
