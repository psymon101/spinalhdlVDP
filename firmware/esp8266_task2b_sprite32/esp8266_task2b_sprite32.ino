/**
 * esp8266_task2b_sprite32.ino — Task 2b 32-sprite hardware-proof host sketch
 * (replacement for the background-only proof flagged by BronzeGate #9293).
 *
 * Standalone, single-purpose firmware per `feedback_firmware_per_test`.
 * Drives 28 simultaneously visible bus-programmable sprites (slots 4..31)
 * on a Tang Nano 20K running the Task 2b V=32/D=64 default bitstream
 * (TopTang20kHdmi(scenarioId=0)). Slots 0..3 are legacy IO-driven and not
 * bus-addressable; bus address decode covers slots 0..31 → effective
 * bus-programmable count = 28. Substantially more sprites than the prior
 * 8-visible limit of pre-Task-2a; demonstrates the V=32 substrate
 * carrying a host-driven scene.
 *
 * Architecture per project spec: MCU programs descriptors + per-frame X
 * updates; FPGA does scanline evaluation + rasterization.
 *
 * Boot:
 *   1. Init QSPI GPIO.
 *   2. Settle delay.
 *   3. For each slot s ∈ [4, 31]:
 *        - word 0: enabled=1, patternIdx = s % 2, y = row(s)
 *        - word 1: x = initial position
 *        - word 8 @ 0x0D20+s: sizeSel=1 (16×16), paletteBank=0, priority=1
 *   4. Loop: update word 1 (x) per slot at ~50 ms cadence with bouncing
 *      animation. Each slot has independent step + direction state.
 *
 * Pin map: identical to sc62 ESP8266 port (BronzeGate #9123).
 * Wire protocol: 8-byte REG_WRITE @ ~500 kHz SCK, FPGA samples on rising
 * edge.
 *
 * Expected on-screen result:
 *   - 28 sprites in a 4×7 grid arrangement (rows at y={ 80, 180, 280, 380 })
 *   - patterns alternating diamond/cross from default pattern Mem init
 *   - all sprites bouncing horizontally between x=16 and x=608
 *   - smooth motion, no glitches, no freezes
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

// ---- QSPI primitives (matches sc62/sc45 sketches) ---------------------------

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

// ---- Task 2b sprite descriptor layout helpers -------------------------------

// Word 0: {enabled[15], patternIdx[14:11], reserved[10], y[9:0]}
static inline uint16_t sprite_word0(bool enabled, uint8_t patIdx, uint16_t y)
{
    return (enabled ? 0x8000u : 0x0000u)
         | ((uint16_t)(patIdx & 0xF) << 11)
         | (y & 0x3FFu);
}

// Word 1: {reserved[15:10], x[9:0]}
static inline uint16_t sprite_word1(uint16_t x) { return x & 0x3FFu; }

// Word 8 @ 0x0D20+slot: {sizeSel[15:14], paletteBank[13:11], priority[10:9],
//                       reserved[8:6], bppSel[5:4], flipV[3], flipH[2],
//                       reserved[1:0]}  (per artifact pack — best-effort
//                       inferred from existing scenario word-8 packing in
//                       sc62 sketch)
// Conservative: sizeSel=1 (16×16) → bits[15:14]=01; paletteBank=0; priority=1
// (medium tier); rest 0.
static inline uint16_t sprite_word8(uint8_t sizeSel, uint8_t paletteBank,
                                    uint8_t priority, bool flipH, bool flipV,
                                    uint8_t bppSel)
{
    return ((uint16_t)(sizeSel     & 0x3) << 14)
         | ((uint16_t)(paletteBank & 0x7) << 11)
         | ((uint16_t)(priority    & 0x3) <<  9)
         | ((uint16_t)(bppSel      & 0x3) <<  4)
         | (flipV ? (1u << 3) : 0u)
         | (flipH ? (1u << 2) : 0u);
}

#define SPRITE_BASE(slot) (0x0800u + (uint32_t)(slot) * 8u)
#define SPRITE_W8_BASE    0x0D20u

// ---- Task 2b 32-sprite scene ------------------------------------------------

static constexpr uint8_t  FIRST_SLOT = 4;
static constexpr uint8_t  LAST_SLOT  = 32;     // exclusive; slots 4..31 = 28
static constexpr uint16_t X_MIN      = 16;
static constexpr uint16_t X_MAX      = 608;    // 16+16 = 32 sprite-px under 640

// Per-slot bouncer state (in RAM).
static uint16_t slot_x   [LAST_SLOT];
static int8_t   slot_step[LAST_SLOT];

static uint16_t row_y(uint8_t slot)
{
    // Arrange slots 4..31 in a 4-row grid (rows: 80, 180, 280, 380).
    // Row index = (slot - 4) / 7 (4 rows × 7 sprites = 28 slots).
    uint8_t rel = slot - FIRST_SLOT;
    uint8_t row = rel / 7;
    static const uint16_t Y[4] = { 80, 180, 280, 380 };
    return Y[row & 0x3];
}

static uint16_t initial_x(uint8_t slot)
{
    uint8_t rel = slot - FIRST_SLOT;
    uint8_t col = rel % 7;
    // Spread 7 sprites across X with even spacing, starting at 32.
    return 32 + col * 80;
}

static void program_sprites(void)
{
    Serial.println(F("task2b: programming 28 sprite descriptors (slots 4..31)..."));
    for (uint8_t s = FIRST_SLOT; s < LAST_SLOT; ++s) {
        uint16_t x = initial_x(s);
        uint16_t y = row_y(s);
        // Patterns alternate: even slots → patIdx 0 (diamond default),
        // odd slots → patIdx 1 (cross default).
        uint8_t pat = (s & 1) ? 1 : 0;

        vdp_reg_write(SPRITE_BASE(s) + 0u, sprite_word0(true, pat, y));
        vdp_reg_write(SPRITE_BASE(s) + 1u, sprite_word1(x));
        // Words 2..7 (matrixA..D, transX/Y) default to 0 — affine disabled.
        // Word 8: sizeSel=1 (16×16), paletteBank=0, priority=1 (medium).
        vdp_reg_write(SPRITE_W8_BASE + s, sprite_word8(1, 0, 1, false, false, 0));

        slot_x[s]    = x;
        slot_step[s] = (s & 1) ? -1 : +1;   // alternate initial direction
    }
    Serial.println(F("task2b: descriptor program complete; entering animation loop"));
}

// One animation step per slot — update word 1 (X) only.
static void animate_step(void)
{
    for (uint8_t s = FIRST_SLOT; s < LAST_SLOT; ++s) {
        int16_t nx = (int16_t)slot_x[s] + (int16_t)slot_step[s] * 2;  // 2 px/step
        if (nx <= (int16_t)X_MIN) { nx = X_MIN; slot_step[s] = +1; }
        if (nx >= (int16_t)X_MAX) { nx = X_MAX; slot_step[s] = -1; }
        slot_x[s] = (uint16_t)nx;
        vdp_reg_write(SPRITE_BASE(s) + 1u, sprite_word1(slot_x[s]));
    }
}

// ---- Sketch entry points ----------------------------------------------------

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 task2b 32-sprite host (Task 2b HW proof v2 per BronzeGate #9293) — booting"));

    vdp_qspi_init();
    delay(200);
    program_sprites();
}

void loop(void)
{
    animate_step();
    // ~50 ms cadence → ~20 frames/sec animation update rate.
    // QSPI bandwidth: 28 writes × 8 bytes × ~16 µs = ~3.6 ms per step,
    // well under 50 ms — leaves headroom.
    delay(45);
}
