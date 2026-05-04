/**
 * esp32_sc62_sprite_flip.ino — Task 52 sprite-flip HW proof host sketch.
 *
 * Standalone, single-purpose firmware per the firmware-per-test
 * cleanliness rule (BronzeGate #9101). Does ONLY what sc62 needs:
 * upload one asymmetric 16×16 4bpp sprite pattern into pattern slot 0.
 * The sc62 case in TopTang20kHdmi.scala (scenarioId=62) handles all
 * sprite-descriptor configuration via its copper program.
 *
 * Reference: converged packet #9105, audit PASS #9107, PM GO #9109,
 * trim ruling #9113.
 *
 * Pin map matches firmware/esp32_dev1_qspi_host (BronzeGate #8987 —
 * non-strap / non-JTAG / non-flash safe outputs in the low-half bank):
 *
 *   QSPI signal | ESP32 GPIO
 *   ------------|-----------
 *   SCK         | GPIO18
 *   CS_N        | GPIO19
 *   IO0         | GPIO23
 *   IO1         | GPIO22
 *   IO2         | GPIO25
 *   IO3         | GPIO27
 *
 * Wire protocol: 8-byte REG_WRITE frames at ~500 kHz.
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

static constexpr uint8_t PIN_SCK   = 18;
static constexpr uint8_t PIN_CS_N  = 19;
static constexpr uint8_t PIN_IO0   = 23;
static constexpr uint8_t PIN_IO1   = 22;
static constexpr uint8_t PIN_IO2   = 25;
static constexpr uint8_t PIN_IO3   = 27;

static constexpr uint32_t MASK_SCK    = 1u << PIN_SCK;
static constexpr uint32_t MASK_CS_N   = 1u << PIN_CS_N;
static constexpr uint32_t MASK_IO0    = 1u << PIN_IO0;
static constexpr uint32_t MASK_IO1    = 1u << PIN_IO1;
static constexpr uint32_t MASK_IO2    = 1u << PIN_IO2;
static constexpr uint32_t MASK_IO3    = 1u << PIN_IO3;
static constexpr uint32_t MASK_IO_ALL = MASK_IO0 | MASK_IO1 | MASK_IO2 | MASK_IO3;

static constexpr uint32_t HALF_PERIOD_US = 1;

static inline void drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    if (n & 0x8) set |= MASK_IO3;
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_IO_ALL);
    if (set) REG_WRITE(GPIO_OUT_W1TS_REG, set);
}

static inline void send_nibble(uint8_t n)
{
    drive_nibble(n);
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_SCK);
    delayMicroseconds(HALF_PERIOD_US);
    REG_WRITE(GPIO_OUT_W1TS_REG, MASK_SCK);
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
    REG_WRITE(GPIO_OUT_W1TS_REG, MASK_CS_N);
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_SCK | MASK_IO_ALL);
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

    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_CS_N);
    delayMicroseconds(1);
    for (size_t i = 0; i < sizeof(frame); ++i) send_byte(frame[i]);
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_SCK);
    delayMicroseconds(1);
    REG_WRITE(GPIO_OUT_W1TS_REG, MASK_CS_N);
    delayMicroseconds(10);
}

// Asymmetric L-shape: col 0 OR row 15 → palette index 1, elsewhere → 0.
static inline uint8_t pattern_pixel(uint8_t row, uint8_t col)
{
    return (col == 0 || row == 15) ? 0x1 : 0x0;
}

static void sc62_upload_pattern(void)
{
    Serial.println("sc62: uploading 16x16 4bpp asymmetric-L pattern to slot 0...");

    vdp_reg_write(0x0D11u, 0x0000u);   // pointer = 0 (pattern slot 0, pixel 0)

    for (uint8_t row = 0; row < 16; ++row) {
        for (uint8_t col = 0; col < 16; ++col) {
            vdp_reg_write(0x0D10u, (uint16_t)pattern_pixel(row, col));
        }
    }

    Serial.println("sc62: pattern uploaded; sprite descriptors are configured by copper");
}

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println("ESP32 sc62 sprite-flip host (Task 52 #9105/#9107/#9109/#9113) — booting");

    vdp_qspi_init();
    delay(200);
    sc62_upload_pattern();
    Serial.println("sc62 init done; idling (sprite descriptors driven by copper)");
}

void loop(void)
{
    delay(1000);
}
