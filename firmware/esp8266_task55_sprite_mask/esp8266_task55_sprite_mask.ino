/**
 * esp8266_task55_sprite_mask.ino — Task 55 Checkpoint C HW proof sketch.
 *
 * Standalone, single-purpose firmware per `feedback_firmware_per_test`.
 * Drives a Tang Nano 20K running the Sc55 bitstream
 * (`TopTang20kHdmiScenario55Verilog`) so we can capture HDMI evidence
 * that:
 *
 *   1. Genesis sprite masking (word 8 bit [4]) actually suppresses
 *      lower-priority sprites on the masked scanline. Slot 0 carries
 *      mask=1 at Y=200 alongside opaque slots 1, 2 in the same Y-band;
 *      slots 1, 2 must NOT appear on screen between the mask sprite's
 *      Y range. A reference sprite (slot 3) sits on a different Y-band
 *      and remains visible (proves Y-band isolation per #9466).
 *
 *   2. SNES tile-fetch budget overflow (TileBudget=34) trips when a
 *      single line carries 35 tiles. The Sc55 bitstream wires
 *      `sc55Canary` (top-right 40×40 RED block) to STATUS_STICKY[1]
 *      per CyanPeak DECISION #9470, so an overflow latch is visible
 *      in-frame.
 *
 * Scene layout:
 *
 *   Mask band — Y=200..215 (sizeSel=01, 16×16 sprites):
 *     slot 0 @ x= 80, mask=1, patIdx=0   (mask sprite — visible)
 *     slot 1 @ x=200, mask=0, patIdx=0   (opaque, suppressed)
 *     slot 2 @ x=400, mask=0, patIdx=0   (opaque, suppressed)
 *
 *   Reference band — Y=100..115 (different Y → mask doesn't apply):
 *     slot 3 @ x=320, mask=0, patIdx=0   (visible reference)
 *
 *   Overflow band — Y=300..331 inclusive (1×32 + 4×16 + 3×8 = 35 tiles):
 *     slot 4 @ x=  0, sizeSel=10 (32×32), Y=300       — 16 tiles
 *     slot 5 @ x= 64, sizeSel=01 (16×16), Y=300       —  4 tiles
 *     slot 6 @ x= 96, sizeSel=01 (16×16), Y=300       —  4 tiles
 *     slot 7 @ x=128, sizeSel=01 (16×16), Y=300       —  4 tiles
 *     slot 8 @ x=160, sizeSel=01 (16×16), Y=300       —  4 tiles
 *     slot 9 @ x=200, sizeSel=00 ( 8× 8), Y=300       —  1 tile
 *     slot10 @ x=216, sizeSel=00 ( 8× 8), Y=300       —  1 tile
 *     slot11 @ x=232, sizeSel=00 ( 8× 8), Y=300       —  1 tile
 *                                                  total: 35 tiles
 *
 * Expected on-screen result:
 *   - Mask band Y≈200: ONE diamond at x=80 only (slots 1, 2 suppressed)
 *   - Reference band Y≈100: ONE diamond at x=320
 *   - Overflow band Y≈300: 12 sprites visible (capacity > 35 tiles, all
 *     fit) — but `STATUS_STICKY[1]` latches once the 35-tile line is
 *     evaluated, lighting the top-right RED canary.
 *
 * Pin map: BronzeGate #9123; `reference_esp8266_qspi_wiring.md`.
 *
 * BronzeGate #9440 (lane open), CoralReef #9466 (claim), CyanPeak #9470
 * (canary DECISION).
 */

#include <Arduino.h>

static constexpr uint8_t PIN_SCK   = 14;   // D5
static constexpr uint8_t PIN_CS_N  = 12;   // D6
static constexpr uint8_t PIN_IO0   = 13;   // D7
static constexpr uint8_t PIN_IO1   =  5;   // D1
static constexpr uint8_t PIN_IO2   =  4;   // D2
static constexpr uint8_t PIN_IO3   = 16;   // D0 — RTC pad

static constexpr uint32_t MASK_SCK  = 1u << PIN_SCK;
static constexpr uint32_t MASK_CS_N = 1u << PIN_CS_N;
static constexpr uint32_t MASK_IO0  = 1u << PIN_IO0;
static constexpr uint32_t MASK_IO1  = 1u << PIN_IO1;
static constexpr uint32_t MASK_IO2  = 1u << PIN_IO2;
static constexpr uint32_t MASK_IO_LOW = MASK_IO0 | MASK_IO1 | MASK_IO2;

static constexpr uint32_t HALF_PERIOD_US = 1;   // ~500 kHz SCK

// ---- QSPI primitives (matches sc62/sc45/task2b/task53 sketches) ------------

static inline void drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW;
    if (set) GPOS = set;
    digitalWrite(PIN_IO3, (n & 0x8) ? HIGH : LOW);
}

static inline void send_nibble(uint8_t n)
{
    drive_nibble(n);
    GPOC = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
    GPOS = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
}

static inline void send_byte(uint8_t b)
{
    send_nibble((b >> 4) & 0x0F);
    send_nibble( b       & 0x0F);
}

static void vdp_qspi_init(void)
{
    pinMode(PIN_SCK,  OUTPUT);
    pinMode(PIN_CS_N, OUTPUT);
    pinMode(PIN_IO0,  OUTPUT);
    pinMode(PIN_IO1,  OUTPUT);
    pinMode(PIN_IO2,  OUTPUT);
    pinMode(PIN_IO3,  OUTPUT);
    GPOS = MASK_CS_N;
    GPOC = MASK_SCK | MASK_IO_LOW;
    digitalWrite(PIN_IO3, LOW);
}

static void vdp_reg_write(uint32_t addr, uint16_t data)
{
    uint8_t frame[8];
    frame[0] = 0x01;
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >>  8) & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = 0x01;
    frame[5] = 0x00;
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);

    GPOC = MASK_CS_N;
    delayMicroseconds(1);
    for (size_t i = 0; i < sizeof(frame); ++i) send_byte(frame[i]);
    GPOC = MASK_SCK;
    delayMicroseconds(1);
    GPOS = MASK_CS_N;
    delayMicroseconds(10);
}

// ---- Descriptor helpers — Task 55 word 0/1 + word 8 (mask bit [4]) --------

#define SPRITE_BASE(slot)  (0x0800u + (uint32_t)(slot) * 8u)
#define SPRITE_W8_BASE     0x0D20u

// Word 0: enabled[15], patIdx[3:0]@[14:11], affineEnable[10], y[9:0]
static inline uint16_t sprite_word0(bool enabled, uint8_t patIdxLow, uint16_t y)
{
    return (enabled ? 0x8000u : 0x0000u)
         | ((uint16_t)(patIdxLow & 0xF) << 11)
         | (y & 0x3FFu);
}

// Word 1: x[9:0]
static inline uint16_t sprite_word1(uint16_t x) { return x & 0x3FFu; }

// Word 8 (Task 55 layout):
//   sizeSel[15:14], paletteBank[13:11], priority[10:9],
//   flipH[8], flipV[7], bppSel[6:5], mask[4], _[3:2], patIdx[1:0]
static inline uint16_t sprite_word8(uint8_t sizeSel, uint8_t paletteBank,
                                    uint8_t priority, bool flipH, bool flipV,
                                    uint8_t bppSel, bool mask, uint8_t patIdxHigh)
{
    return ((uint16_t)(sizeSel     & 0x3) << 14)
         | ((uint16_t)(paletteBank & 0x7) << 11)
         | ((uint16_t)(priority    & 0x3) <<  9)
         | (flipH ? (1u << 8) : 0u)
         | (flipV ? (1u << 7) : 0u)
         | ((uint16_t)(bppSel      & 0x3) <<  5)
         | (mask  ? (1u << 4) : 0u)
         | ((uint16_t)(patIdxHigh  & 0x3));
}

// Program one sprite slot (word 0 + word 1 + word 8).
static void program_sprite(uint8_t slot, uint16_t x, uint16_t y,
                           uint8_t patIdx6, uint8_t sizeSel,
                           uint8_t paletteBank, uint8_t priority,
                           bool mask)
{
    const uint8_t low  = patIdx6 & 0x0F;
    const uint8_t high = (patIdx6 >> 4) & 0x03;

    vdp_reg_write(SPRITE_BASE(slot) + 0u, sprite_word0(true, low, y));
    vdp_reg_write(SPRITE_BASE(slot) + 1u, sprite_word1(x));
    vdp_reg_write(SPRITE_W8_BASE + slot,
                  sprite_word8(sizeSel, paletteBank, priority,
                               /*flipH*/false, /*flipV*/false,
                               /*bppSel*/0, mask, high));
}

// Disable a sprite slot (clear enable + park off-screen).
static void disable_sprite(uint8_t slot)
{
    vdp_reg_write(SPRITE_BASE(slot) + 0u, sprite_word0(false, 0, 1023));
    vdp_reg_write(SPRITE_BASE(slot) + 1u, sprite_word1(1023));
    vdp_reg_write(SPRITE_W8_BASE + slot, 0);
}

// ---- Sketch ----------------------------------------------------------------

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println(F("\n\nesp8266_task55_sprite_mask: boot"));
    Serial.println(F("Task 55 Checkpoint C — sprite mask + tile-budget HW proof"));

    vdp_qspi_init();
    delay(200);   // settle

    // Disable all bus-resident slots so we start from a clean state.
    Serial.println(F("disabling slots 4..47..."));
    for (uint8_t s = 4; s < 48; ++s) {
        disable_sprite(s);
    }

    // ---- Mask band @ Y=200 (slots 0..2) — slot 0 carries mask=1 ----
    // visiblePerLine slot ordering: slot 0 = lowest descriptor index
    // among the active list. Mask sprite at slot 0 means
    // firstMaskSlot=0; rasterizer suppresses every higher-index slot.
    //
    // NOTE: slots 0..3 in the descriptor list are LEGACY IO slots
    // driven by Tang inputs and have no bus-writable mask bit. To get
    // a bus-programmable slot 0 in the *active* list we must keep all
    // legacy IO slots disabled and use the lowest bus-programmable
    // slots (4..6). The evaluator's slot ordering is by descriptor
    // index ascending, so bus slot 4 = active slot 0, bus slot 5 =
    // active slot 1, etc. (same as `SpriteCapacitySim` Case B).
    Serial.println(F("programming mask band @ Y=200 — slot 4 mask=1, slots 5/6 opaque"));
    program_sprite(/*slot*/4, /*x*/ 80, /*y*/200, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/true);
    program_sprite(/*slot*/5, /*x*/200, /*y*/200, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/6, /*x*/400, /*y*/200, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);

    // ---- Reference band @ Y=100 — bus slot 7 (different Y, no mask) ----
    Serial.println(F("programming reference band @ Y=100 — slot 7 (no mask)"));
    program_sprite(/*slot*/7, /*x*/320, /*y*/100, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);

    // ---- Overflow band @ Y=300 — 35 tiles in one line ----
    // 1×32 (slot 8) + 4×16 (slots 9..12) + 3×8 (slots 13..15) = 35 tiles
    Serial.println(F("programming overflow band @ Y=300 — 35 tiles"));
    // 32×32 sprite (16 tiles)
    program_sprite(/*slot*/ 8, /*x*/  0, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/2, /*pal*/0, /*prio*/2, /*mask*/false);
    // 4× 16×16 sprites (4 tiles each = 16)
    program_sprite(/*slot*/ 9, /*x*/ 64, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/10, /*x*/ 96, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/11, /*x*/128, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/12, /*x*/160, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/1, /*pal*/0, /*prio*/2, /*mask*/false);
    // 3× 8×8 sprites (1 tile each = 3)
    program_sprite(/*slot*/13, /*x*/200, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/0, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/14, /*x*/216, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/0, /*pal*/0, /*prio*/2, /*mask*/false);
    program_sprite(/*slot*/15, /*x*/232, /*y*/300, /*patIdx*/0,
                   /*sizeSel*/0, /*pal*/0, /*prio*/2, /*mask*/false);

    Serial.println(F("setup complete; static scene running"));
    Serial.println(F("expected: mask band Y~200 shows ONE sprite (x=80);"));
    Serial.println(F("          reference Y~100 shows ONE sprite (x=320);"));
    Serial.println(F("          overflow Y~300 shows 8 sprites + RED canary top-right (35 tiles)"));
}

void loop(void)
{
    // Static scene — descriptors stay programmed, FPGA renders each
    // frame from on-chip default pattern at patIdx=0 (diamond). No
    // host-side animation.
    delay(1000);
}
