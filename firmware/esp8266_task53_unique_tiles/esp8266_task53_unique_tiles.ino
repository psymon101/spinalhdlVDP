/**
 * esp8266_task53_unique_tiles.ino — Task 53 Checkpoint C HW proof sketch.
 *
 * Standalone, single-purpose firmware per `feedback_firmware_per_test`.
 * Drives the Tang Nano 20K with a Task-53 (PatIdxWidth=6) bitstream so
 * we can capture HDMI evidence that:
 *
 *   1. Pattern RAM slots ≥ 16 are addressable via the widened
 *      14-bit `patternRamPtr` (bus address 0x0D11) and the 4-bit
 *      data port at 0x0D10.
 *   2. Sprite descriptors carry the 6-bit patIdx via the word-0
 *      [14:11] low nibble + word-8 [1:0] high pair (per CyanPeak
 *      audit #9427).
 *   3. The sprite rasterizer fetches from the new high-band patterns
 *      end-to-end on hardware.
 *
 * Scene:
 *   - Slot 4: 32×32 sprite at patIdx=16 (uses uploaded "T" tile)
 *   - Slot 5: 32×32 sprite at patIdx=17 (uses uploaded "L" tile)
 *   - Slot 6: 16×16 sprite at patIdx=0  (default diamond — legacy
 *             non-interference proof)
 *
 * Each new pattern (slot 16 + slot 17) is a distinct 16×16 4 bpp
 * pattern uploaded over QSPI. The "T" and "L" letterforms are
 * unambiguous on direct visual review and impossible to confuse with
 * the default diamond/cross patterns at slots 0/1.
 *
 * Pin map: identical to sc62/task2b ESP8266 port (BronzeGate #9123;
 * `reference_esp8266_qspi_wiring.md`).
 *
 * Wire protocol: 8-byte REG_WRITE @ ~500 kHz SCK, FPGA samples on
 * rising edge.
 *
 * Expected on-screen result:
 *   - One 32×32 white "T" letter at left.
 *   - One 32×32 white "L" letter at centre.
 *   - One 16×16 default-diamond sprite at right (legacy reference).
 *   - Static scene; no motion.
 *
 * BronzeGate #9419, CyanPeak #9427/#9430.
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

// ---- QSPI primitives (matches sc62/sc45/task2b sketches) -------------------

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

// ---- Pattern RAM upload (Task 53 #9419 — pointer 14 bits) -----------------

#define VDP_PAT_PTR_ADDR  0x0D11u   // patternRamPtr write
#define VDP_PAT_DATA_ADDR 0x0D10u   // patternRamData write (4 bpp nibble)

// Upload a 16×16 4 bpp pattern to slot `patIdx`. Each entry is one nibble
// (0x0..0xF) representing the palette index for that pixel. Layout: row-
// major, MSB-first within a 32-bit fetch word per
// `BitplaneRowFetch.scala` / sprite pattern Mem semantics. The host
// programs each nibble individually via 256 successive data writes; the
// FPGA-side `patternRamPtr` auto-increments.
static void upload_pattern(uint8_t patIdx, const uint8_t *nibbles256)
{
    const uint16_t base = (uint16_t)patIdx * 256u;     // 64 slots × 256 entries
    vdp_reg_write(VDP_PAT_PTR_ADDR, base);
    for (uint16_t i = 0; i < 256; ++i) {
        vdp_reg_write(VDP_PAT_DATA_ADDR, (uint16_t)(nibbles256[i] & 0x0F));
    }
}

// ---- Pattern data — distinct letterforms easily readable on capture --------

// "T" pattern at slot 16: 16×16, palette index F (white) for the letter,
// 0 (transparent) for background.
static const uint8_t TILE_T_16x16[256] PROGMEM = {
    // row 0 — top horizontal bar
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    // row 1
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    // row 2
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    // row 3
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    // rows 4..15 — vertical stem in centre (4 px wide, centred at cols 6..9)
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
    0,0,0,0,0,0,0xF,0xF,0xF,0xF,0,0,0,0,0,0,
};

// "L" pattern at slot 17: 16×16, palette index F.
static const uint8_t TILE_L_16x16[256] PROGMEM = {
    // rows 0..11 — vertical stem at left (cols 0..3)
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    0xF,0xF,0xF,0xF,0,0,0,0,0,0,0,0,0,0,0,0,
    // rows 12..15 — horizontal foot
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
    0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,0xF,
};

// ---- Descriptor helpers — Task 53 word 0 + word 8 split -------------------

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

// Word 8: sizeSel[15:14], paletteBank[13:11], priority[10:9],
//         flipH[8], flipV[7], bppSel[6:5], _[4:2], patIdx[5:4]@[1:0]
static inline uint16_t sprite_word8(uint8_t sizeSel, uint8_t paletteBank,
                                    uint8_t priority, bool flipH, bool flipV,
                                    uint8_t bppSel, uint8_t patIdxHigh)
{
    return ((uint16_t)(sizeSel     & 0x3) << 14)
         | ((uint16_t)(paletteBank & 0x7) << 11)
         | ((uint16_t)(priority    & 0x3) <<  9)
         | (flipH ? (1u << 8) : 0u)
         | (flipV ? (1u << 7) : 0u)
         | ((uint16_t)(bppSel      & 0x3) <<  5)
         | ((uint16_t)(patIdxHigh  & 0x3));
}

// Convenience: write word 0 + word 8 for a sprite carrying a possibly-high
// 6-bit patIdx. Order: word 0 first (low nibble), then word 8 (high pair),
// validating field-independence per `SpriteHighPatIdxBusSim` Phase A.
static void program_sprite(uint8_t slot, uint16_t x, uint16_t y,
                           uint8_t patIdx6, uint8_t sizeSel,
                           uint8_t paletteBank, uint8_t priority)
{
    const uint8_t low  = patIdx6 & 0x0F;
    const uint8_t high = (patIdx6 >> 4) & 0x03;

    vdp_reg_write(SPRITE_BASE(slot) + 0u, sprite_word0(true, low, y));
    vdp_reg_write(SPRITE_BASE(slot) + 1u, sprite_word1(x));
    vdp_reg_write(SPRITE_W8_BASE + slot,
                  sprite_word8(sizeSel, paletteBank, priority,
                               /*flipH*/false, /*flipV*/false,
                               /*bppSel*/0, high));
}

// ---- Sketch ----------------------------------------------------------------

static void copy_progmem(uint8_t *dst, const uint8_t *src, size_t n)
{
    for (size_t i = 0; i < n; ++i) {
        dst[i] = pgm_read_byte(src + i);
    }
}

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println(F("\n\nesp8266_task53_unique_tiles: boot"));
    Serial.println(F("Task 53 Checkpoint C — HW proof for PatIdxWidth=6"));

    vdp_qspi_init();
    delay(200);   // settle

    // 1. Upload distinct patterns to high slots 16 + 17.
    Serial.println(F("uploading T-tile to patIdx=16..."));
    uint8_t buf[256];
    copy_progmem(buf, TILE_T_16x16, 256);
    upload_pattern(16, buf);

    Serial.println(F("uploading L-tile to patIdx=17..."));
    copy_progmem(buf, TILE_L_16x16, 256);
    upload_pattern(17, buf);

    // 2. Program 3 sprites:
    //    slot 4 — 32×32 T at patIdx=16 (priority=2 — above bg)
    //    slot 5 — 32×32 L at patIdx=17
    //    slot 6 — 16×16 default diamond at patIdx=0 (legacy ref)
    Serial.println(F("programming sprites: slot4 T@16, slot5 L@17, slot6 diamond@0"));

    // sizeSel encoding: 0=8 px, 1=16 px, 2=32 px, 3=64 px (per
    // SpriteEvaluator.sizeForSel). priority=2 places the sprite above
    // the background tile layer.
    program_sprite(/*slot*/4, /*x*/100, /*y*/200, /*patIdx*/16, /*size*/2,
                   /*pal*/0, /*prio*/2);
    program_sprite(/*slot*/5, /*x*/280, /*y*/200, /*patIdx*/17, /*size*/2,
                   /*pal*/0, /*prio*/2);
    program_sprite(/*slot*/6, /*x*/460, /*y*/210, /*patIdx*/ 0, /*size*/1,
                   /*pal*/0, /*prio*/2);

    Serial.println(F("setup complete; static scene running"));
}

void loop(void)
{
    // Static scene — descriptors stay programmed, FPGA renders each
    // frame from SDRAM-free pattern RAM. No host-side animation.
    delay(1000);
}
