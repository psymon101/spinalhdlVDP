/**
 * esp8266_sc62_sprite_flip.ino — Task 52 sprite-flip HW proof host sketch
 * (ESP8266 / NodeMCU port).
 *
 * Standalone, single-purpose firmware per the firmware-per-test
 * cleanliness rule (BronzeGate #9101). Does ONLY what sc62 needs:
 * upload one asymmetric 16×16 4bpp sprite pattern into pattern slot 0.
 * The sc62 case in TopTang20kHdmi.scala (scenarioId=62) handles all
 * sprite-descriptor configuration via its copper program.
 *
 * Reference: converged packet #9105, audit PASS #9107, PM GO #9109,
 * trim ruling #9113, ESP8266 port ruling #9123.
 *
 * Board:  NodeMCU 1.0 (ESP-12E module)
 * FQBN:   esp8266:esp8266:nodemcuv2
 *
 * Pin map (per BronzeGate #9123):
 *
 *   QSPI signal | NodeMCU pin | GPIO
 *   ------------|-------------|-----
 *   SCK         | D5          | 14
 *   CS_N        | D6          | 12
 *   IO0         | D7          | 13
 *   IO1         | D1          |  5
 *   IO2         | D2          |  4
 *   IO3         | D0          | 16   <- needs digitalWrite (RTC pad)
 *   GND         | GND         |
 *
 * ESP8266 GPIO write APIs differ from ESP32:
 *   - GPIO0-15: `GPOS = (1u<<pin)` set / `GPOC = (1u<<pin)` clear
 *   - GPIO16:   `digitalWrite(16, HIGH/LOW)` (RTC peripheral)
 * No cross-power between boards (pure signal + GND).
 *
 * Wire protocol matches the ESP32 sketch: 8-byte REG_WRITE frames at
 * ~500 kHz SCK. Each nibble drives IO[3:0] before the SCK rising edge
 * (FPGA samples on rising edge).
 *
 * Pattern definition — asymmetric "L" (col 0 vertical bar + row 15
 * horizontal bar). Asymmetric in BOTH axes so flipH ≠ identity AND
 * flipV ≠ identity AND the four flip combinations are visually
 * distinguishable in the captured frame.
 *
 * Expected on-screen result (sc62 places 4 sprites in a row at y=200):
 *   x= 80 : reference L (vert bar on LEFT,  horiz bar on BOTTOM)
 *   x=200 : flipH only  (vert bar on RIGHT, horiz bar on BOTTOM)
 *   x=320 : flipV only  (vert bar on LEFT,  horiz bar on TOP)
 *   x=440 : flipH+flipV (vert bar on RIGHT, horiz bar on TOP)
 *
 * Boot:
 *   1. Init GPIOs.
 *   2. Wait 200 ms for FPGA + transport settle.
 *   3. Upload pattern (1 pointer-set + 256 pixel writes).
 *   4. Idle. The bitstream's copper program does the rest.
 */

#include <Arduino.h>

// ---- Pin map (NodeMCU GPIO numbers, per #9123) ------------------------------
static constexpr uint8_t PIN_SCK   = 14;   // D5
static constexpr uint8_t PIN_CS_N  = 12;   // D6
static constexpr uint8_t PIN_IO0   = 13;   // D7
static constexpr uint8_t PIN_IO1   =  5;   // D1
static constexpr uint8_t PIN_IO2   =  4;   // D2
static constexpr uint8_t PIN_IO3   = 16;   // D0 — RTC pad, needs digitalWrite

// Bit masks for GPIO0-15 fast-register writes (GPOS/GPOC).
static constexpr uint32_t MASK_SCK  = 1u << PIN_SCK;
static constexpr uint32_t MASK_CS_N = 1u << PIN_CS_N;
static constexpr uint32_t MASK_IO0  = 1u << PIN_IO0;
static constexpr uint32_t MASK_IO1  = 1u << PIN_IO1;
static constexpr uint32_t MASK_IO2  = 1u << PIN_IO2;
// IO3 = GPIO16 is NOT in GPOS/GPOC; handled via digitalWrite.
static constexpr uint32_t MASK_IO_LOW = MASK_IO0 | MASK_IO1 | MASK_IO2;

static constexpr uint32_t HALF_PERIOD_US = 1;   // ~500 kHz SCK

// ---- Low-level helpers ------------------------------------------------------

// Drive IO[3:0] from a 4-bit nibble.
//   IO0..IO2 -> direct GPOS/GPOC writes (fast).
//   IO3 (GPIO16) -> digitalWrite (slower but only one bit).
static inline void drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW;          // clear IO0..IO2
    if (set) GPOS = set;         // set selected of IO0..IO2
    digitalWrite(PIN_IO3, (n & 0x8) ? HIGH : LOW);
}

// One nibble + one SCK rising edge (FPGA samples on rising edge).
static inline void send_nibble(uint8_t n)
{
    drive_nibble(n);
    GPOC = MASK_SCK;              // SCK low: data setup
    delayMicroseconds(HALF_PERIOD_US);
    GPOS = MASK_SCK;              // SCK high: FPGA samples
    delayMicroseconds(HALF_PERIOD_US);
}

// Send one byte high-nibble-first.
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
    GPOS = MASK_CS_N;             // CS_N idle high
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
    frame[4] = 0x01;                      // LEN = 1 word
    frame[5] = 0x00;
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);

    GPOC = MASK_CS_N;             // CS_N low
    delayMicroseconds(1);
    for (size_t i = 0; i < sizeof(frame); ++i) send_byte(frame[i]);
    GPOC = MASK_SCK;              // SCK low (idle)
    delayMicroseconds(1);
    GPOS = MASK_CS_N;             // CS_N high
    delayMicroseconds(10);
}

// ---- sc62 pattern upload ----------------------------------------------------

// Asymmetric L-shape: col 0 OR row 15 → palette index 1, elsewhere → 0.
static inline uint8_t pattern_pixel(uint8_t row, uint8_t col)
{
    return (col == 0 || row == 15) ? 0x1 : 0x0;
}

static void sc62_upload_pattern(void)
{
    Serial.println(F("sc62: uploading 16x16 4bpp asymmetric-L pattern to slot 0..."));

    vdp_reg_write(0x0D11u, 0x0000u);   // pointer = 0 (pattern slot 0, pixel 0)

    for (uint8_t row = 0; row < 16; ++row) {
        for (uint8_t col = 0; col < 16; ++col) {
            vdp_reg_write(0x0D10u, (uint16_t)pattern_pixel(row, col));
        }
    }

    Serial.println(F("sc62: pattern uploaded; sprite descriptors are configured by copper"));
}

// ---- Sketch entry points ----------------------------------------------------

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 sc62 sprite-flip host (Task 52 #9105/#9107/#9109/#9113/#9123) — booting"));

    vdp_qspi_init();
    delay(200);                   // FPGA + transport settle
    sc62_upload_pattern();
    Serial.println(F("sc62 init done; idling (sprite descriptors driven by copper)"));
}

void loop(void)
{
    delay(1000);
}
