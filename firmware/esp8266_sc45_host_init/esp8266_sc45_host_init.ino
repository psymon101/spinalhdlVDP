/**
 * esp8266_sc45_host_init.ino — #9026 sc45-host narrowed proof.
 *
 * Standalone, single-purpose firmware per #9101. Does ONLY what the
 * #9026 zero-footprint proof needs: drive the sc45 register/copper
 * sequence so the FPGA renders sc45 with the bitstream's
 * `useHostInit=true` boot bypass active.
 *
 * Reference: BronzeGate rulings #9080 / #9082 / #9084 / #9133;
 *            converged scope: SdramTileAttributeFetch ROM/init removal,
 *                             TopTang20kHdmi useHostInit wiring,
 *                             BitmapRowFetch skipSdramInit wiring,
 *                             sc45-host proof build/capture.
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 *
 * Pin map (BronzeGate #9123 — unchanged from sc62 sketch):
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
 * Boot sequence (sc45 mirror of TopTang20kHdmi.scala lines 568-602 +
 * 800-861, ported to host-driven init since the bitstream's
 * bootstrap copper is bypassed when useHostInit=true):
 *
 *   1. Init GPIOs.
 *   2. Wait 200 ms for FPGA + transport settle.
 *   3. Upload 220-word copper program to 0x0400..0x04DB:
 *      a. 0x0400+0   = 0x0000        (WAIT y=0)
 *      b. 0x0400+1   = 0x4350        (WRITE BITMAP_CTRL opcode)
 *      c. 0x0400+2   = 0x0081        (BITMAP_CTRL = en|1bpp|useSdram)
 *      d. for g in 0..23 (24 WSEQ groups, each 9 words):
 *         baseLine = g * 8
 *         opcodeWord = 0xB800 | (baseLine & 0x7FF)
 *         0x0400 + 3 + g*9 + 0 = opcodeWord
 *         0x0400 + 3 + g*9 + k = 0x0800  for k in 1..8 (l0en=1)
 *      e. 0x0400+219 = 0xC000        (JUMP 0)
 *   4. Write 9 control registers:
 *      0x0311 = 0x0000  (TILE_MODE = packed)
 *      0x0312 = 0x0000  (ATTR_MODE = linear)
 *      0x0310 = 0x0001  (VDP_CTRL  = copper enabled)
 *      0x0300 = 0x0001  (LAYER_ENABLE = L0 only)
 *      0x0330..0x0333 = 0     (WIN_X0/X1/Y0/Y1)
 *      0x0334 = 0x0000  (COLOR_MATH = passthrough)
 *   5. Idle. Bitmap region renders driven by the host-uploaded
 *      register/copper state; the FPGA's internal bootstrap is
 *      bypassed (useHostInit=true) and on-chip init ROMs are
 *      synthesized away (skipSdramInit=true).
 */

#include <Arduino.h>

// ---- Pin map ----------------------------------------------------------------
static constexpr uint8_t PIN_SCK   = 14;
static constexpr uint8_t PIN_CS_N  = 12;
static constexpr uint8_t PIN_IO0   = 13;
static constexpr uint8_t PIN_IO1   =  5;
static constexpr uint8_t PIN_IO2   =  4;
static constexpr uint8_t PIN_IO3   = 16;

static constexpr uint32_t MASK_SCK    = 1u << PIN_SCK;
static constexpr uint32_t MASK_CS_N   = 1u << PIN_CS_N;
static constexpr uint32_t MASK_IO0    = 1u << PIN_IO0;
static constexpr uint32_t MASK_IO1    = 1u << PIN_IO1;
static constexpr uint32_t MASK_IO2    = 1u << PIN_IO2;
static constexpr uint32_t MASK_IO_LOW = MASK_IO0 | MASK_IO1 | MASK_IO2;

static constexpr uint32_t HALF_PERIOD_US = 1;   // ~500 kHz SCK

// ---- QSPI primitives --------------------------------------------------------

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

// ---- sc45 host-init sequence ------------------------------------------------

static void sc45_host_init(void)
{
    Serial.println(F("sc45-host: uploading copper program (220 words)..."));

    vdp_reg_write(0x0400u, 0x0000u);         // WAIT y=0
    vdp_reg_write(0x0401u, 0x4350u);         // WRITE BITMAP_CTRL opcode
    vdp_reg_write(0x0402u, 0x0081u);         // BITMAP_CTRL = en|1bpp|useSdram

    for (int g = 0; g < 24; ++g) {
        uint16_t opcodeWord = 0xB800u | (uint16_t)(g * 8);  // WSEQ | (7<<11) | baseLine
        uint32_t base = 0x0400u + 3u + (uint32_t)(g * 9);
        vdp_reg_write(base + 0, opcodeWord);
        for (int k = 1; k <= 8; ++k) {
            vdp_reg_write(base + k, 0x0800u);  // l0en=1, l1en=0, scrollX=0
        }
    }

    vdp_reg_write(0x0400u + 219u, 0xC000u);  // JUMP 0

    Serial.println(F("sc45-host: copper program uploaded; writing control regs..."));

    vdp_reg_write(0x0311u, 0x0000u);         // TILE_MODE = packed
    vdp_reg_write(0x0312u, 0x0000u);         // ATTR_MODE = linear
    vdp_reg_write(0x0310u, 0x0001u);         // VDP_CTRL  = copper enabled
    vdp_reg_write(0x0300u, 0x0001u);         // LAYER_ENABLE = L0 only
    vdp_reg_write(0x0330u, 0x0000u);         // WIN_X0
    vdp_reg_write(0x0331u, 0x0000u);         // WIN_X1
    vdp_reg_write(0x0332u, 0x0000u);         // WIN_Y0
    vdp_reg_write(0x0333u, 0x0000u);         // WIN_Y1
    vdp_reg_write(0x0334u, 0x0000u);         // COLOR_MATH = passthrough

    Serial.println(F("sc45-host: control regs written; init complete"));
}

// ---- Sketch entry points ----------------------------------------------------

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println();
    Serial.println(F("ESP8266 sc45-host (#9026 zero-footprint, BronzeGate #9133) — booting"));

    vdp_qspi_init();
    delay(200);                              // FPGA + transport settle
    sc45_host_init();
    Serial.println(F("Host-init done; idling (bitmap driven by copper running on FPGA)"));
}

void loop(void)
{
    delay(1000);
}
