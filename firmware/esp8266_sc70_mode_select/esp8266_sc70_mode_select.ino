/**
 * esp8266_sc70_mode_select.ino — Task 1 (#9154) Phase 5d HW proof.
 *
 * Standalone sketch per per-test firmware policy (#9101 /
 * memory:feedback_firmware_per_test). Drives the MODE_SELECT
 * register (0x0313) on the sc70 dual-adapter pilot bitstream so
 * HDMI capture can prove:
 *
 *   - Mode A (MODE_SELECT=0x1, C64 active)  : V=0 commit succeeds,
 *                                              HDMI stays locked
 *   - Mode B (MODE_SELECT=0x2, ZX active)   : V=0 commit succeeds,
 *                                              HDMI stays locked
 *   - Switch (mode 0→1→2→0)                 : every write is V=0
 *                                              frame-atomic; the
 *                                              substrate does not
 *                                              glitch the TMDS lock
 *
 * IMPORTANT: sc70 has NO bootstrap pre-load of C64 / ZX scenes (per
 * arch §4.8(b) the dual-scenario stub is deferred). This sketch
 * therefore demonstrates the **mode-switch register infrastructure**
 * (V=0 commit, MODE_FLAGS auto-reset, STATUS_STICKY/LIVE_MODE
 * observability) on real hardware. Visual scene change between
 * modes is NOT expected from this firmware alone — that requires
 * either the bootstrap stub or a full per-mode host-init sketch
 * (deferred to a follow-on lane).
 *
 * What is exercised:
 *   - Three QSPI REG_WRITE packets to 0x0313 over ~10s cycle
 *   - MODE_FLAGS bit 0 (auto-reset LAYER_ENABLE) is NOT set, so
 *     the bootstrap-loaded LAYER_ENABLE is preserved across
 *     mode switches.
 *
 * Board:  NodeMCU 1.0 (ESP-12E)
 * FQBN:   esp8266:esp8266:nodemcuv2
 *
 * Pin map (memory: reference_esp8266_qspi_wiring):
 *   QSPI signal | NodeMCU pin | GPIO
 *   ------------|-------------|-----
 *   SCK         | D5          | 14
 *   CS_N        | D6          | 12
 *   IO0         | D7          | 13
 *   IO1         | D1          |  5
 *   IO2         | D2          |  4
 *   IO3         | D0          | 16   <- needs digitalWrite (RTC pad)
 *   GND         | GND         |
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

// ---- QSPI primitives (verbatim from esp8266_sc45_host_init) ----------------

static inline void drive_nibble(uint8_t n) {
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW;
    if (set) GPOS = set;
    digitalWrite(PIN_IO3, (n & 0x8) ? HIGH : LOW);
}
static inline void send_nibble(uint8_t n) {
    drive_nibble(n);
    GPOC = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
    GPOS = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
}
static inline void send_byte(uint8_t b) {
    send_nibble((b >> 4) & 0x0F);
    send_nibble( b       & 0x0F);
}

static void vdp_qspi_init(void) {
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

static void vdp_reg_write(uint32_t addr, uint16_t data) {
    uint8_t frame[8];
    frame[0] = 0x01;                            // CMD = REG_WRITE
    frame[1] = (uint8_t)( addr        & 0xFF);  // ADDR low
    frame[2] = (uint8_t)((addr >>  8) & 0xFF);  // ADDR mid
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);  // ADDR high
    frame[4] = 0x01;                            // LEN = 1 word
    frame[5] = 0x00;
    frame[6] = (uint8_t)( data       & 0xFF);   // data low byte
    frame[7] = (uint8_t)((data >> 8) & 0xFF);   // data high byte

    GPOC = MASK_CS_N;
    delayMicroseconds(1);
    for (size_t i = 0; i < sizeof(frame); ++i) send_byte(frame[i]);
    GPOC = MASK_SCK;
    delayMicroseconds(1);
    GPOS = MASK_CS_N;
    delayMicroseconds(10);
}

// ---- MODE_SELECT cycle ------------------------------------------------------

static constexpr uint32_t REG_MODE_SELECT = 0x0313u;
static constexpr uint16_t MODE_NATIVE     = 0x0000u;  // [3:0]=0
static constexpr uint16_t MODE_C64        = 0x0001u;  // [3:0]=1
static constexpr uint16_t MODE_ZX         = 0x0002u;  // [3:0]=2

static constexpr uint32_t HOLD_MS         = 4000;     // dwell per mode

static void mode_select_write(uint16_t mode_word, const __FlashStringHelper* label) {
    Serial.print(F("[mode-select] write 0x0313 = 0x"));
    Serial.print(mode_word, HEX);
    Serial.print(F(" ("));
    Serial.print(label);
    Serial.println(F(") ; expect V=0 frame-atomic commit"));
    vdp_reg_write(REG_MODE_SELECT, mode_word);
}

void setup(void) {
    Serial.begin(115200);
    delay(150);
    Serial.println();
    Serial.println(F("=== sc70 MODE_SELECT HW proof (Task 1 Phase 5d) ==="));
    vdp_qspi_init();
    delay(200);  // FPGA + transport settle
    Serial.println(F("QSPI init done; entering mode-cycle loop"));
}

void loop(void) {
    mode_select_write(MODE_NATIVE, F("Native Mode0"));
    delay(HOLD_MS);
    mode_select_write(MODE_C64,    F("C64 adapter"));
    delay(HOLD_MS);
    mode_select_write(MODE_ZX,     F("ZX Spectrum adapter"));
    delay(HOLD_MS);
}
