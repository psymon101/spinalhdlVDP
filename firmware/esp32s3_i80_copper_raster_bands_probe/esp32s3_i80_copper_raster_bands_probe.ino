/**
 * ESP32-S3 i80 copper raster-bands probe.
 *
 * WHOLE-VDP-134 documentation-derived copper test. This sketch is deliberately
 * self-contained: it uses Arduino GPIO writes for the i80 bus and encodes the
 * copper instructions locally from the documented WAIT/WRITE/JUMP contract.
 *
 * Pin map:
 * - D0..D7 GPIO4..11 -> Tang Nano i80 data bus
 * - DC     GPIO15    -> Tang Nano i80 DC
 * - CS#    GPIO16    -> Tang Nano i80 CS#
 * - WR#    GPIO17    -> Tang Nano i80 WR#
 * - RD#    GPIO18    -> Tang Nano i80 RD#
 *
 * Boot sequence:
 * 1. Initialize the i80 GPIO bus.
 * 2. Reset visible Mode0 state: copper off, layers off, bitmap off, border off.
 * 3. Load palette entries 0..3 as red, green, blue, white.
 * 4. Direct-control phase: host writes solid red, then solid green BORDER_CTRL.
 * 5. Copper phase: upload a WAIT/WRITE/JUMP program to 0x0400 and enable copper.
 *
 * Expected on-screen result:
 * - Direct phases: solid red, then solid green.
 * - Copper phase: horizontal red/green/blue/white bands. Remaining solid green
 *   means direct host border writes work, but copper did not visibly execute.
 */
#include <Arduino.h>

namespace {

constexpr uint8_t kPinD0 = 4u;
constexpr uint8_t kPinD1 = 5u;
constexpr uint8_t kPinD2 = 6u;
constexpr uint8_t kPinD3 = 7u;
constexpr uint8_t kPinD4 = 8u;
constexpr uint8_t kPinD5 = 9u;
constexpr uint8_t kPinD6 = 10u;
constexpr uint8_t kPinD7 = 11u;
constexpr uint8_t kPinDc = 15u;
constexpr uint8_t kPinCsN = 16u;
constexpr uint8_t kPinWrN = 17u;
constexpr uint8_t kPinRdN = 18u;

const uint8_t kDataPins[8] = {
    kPinD0, kPinD1, kPinD2, kPinD3, kPinD4, kPinD5, kPinD6, kPinD7,
};

constexpr uint16_t kRegLayerEnable = 0x0300u;
constexpr uint16_t kRegVdpCtrl = 0x0310u;
constexpr uint16_t kRegModeSelect = 0x0313u;
constexpr uint16_t kRegColorMathCtrl = 0x0334u;
constexpr uint16_t kRegBorderX0 = 0x033Cu;
constexpr uint16_t kRegBorderX1 = 0x033Du;
constexpr uint16_t kRegBorderY0 = 0x033Eu;
constexpr uint16_t kRegBorderY1 = 0x033Fu;
constexpr uint16_t kRegBorderCtrl = 0x0347u;
constexpr uint16_t kRegBackdropIndex = 0x0348u;
constexpr uint16_t kRegBitmapCtrl = 0x0350u;
constexpr uint16_t kRegPaletteData = 0x0600u;
constexpr uint16_t kRegPalettePtr = 0x0601u;
constexpr uint16_t kRegPlanarCtrl = 0x0D4Au;
constexpr uint16_t kCopperRamBase = 0x0400u;

constexpr uint16_t kBorderRed = 0x0001u;
constexpr uint16_t kBorderGreen = 0x0101u;
constexpr uint16_t kBorderBlue = 0x0201u;
constexpr uint16_t kBorderWhite = 0x0301u;

constexpr uint16_t copper_wait(uint16_t y)
{
    return (uint16_t)(y & 0x03FFu);
}

constexpr uint16_t copper_write(uint16_t addr)
{
    return (uint16_t)(0x4000u | (addr & 0x07FFu));
}

constexpr uint16_t copper_jump(uint16_t pc)
{
    return (uint16_t)(0xC000u | (pc & 0x01FFu));
}

const uint16_t kCopperBands[] = {
    copper_wait(0u),
    copper_write(kRegBorderCtrl),
    kBorderRed,
    copper_wait(120u),
    copper_write(kRegBorderCtrl),
    kBorderGreen,
    copper_wait(240u),
    copper_write(kRegBorderCtrl),
    kBorderBlue,
    copper_wait(360u),
    copper_write(kRegBorderCtrl),
    kBorderWhite,
    copper_jump(0u),
};

void data_bus_output()
{
    for (uint8_t i = 0u; i < 8u; ++i) {
        pinMode(kDataPins[i], OUTPUT);
    }
}

void data_bus_input()
{
    for (uint8_t i = 0u; i < 8u; ++i) {
        pinMode(kDataPins[i], INPUT);
    }
}

void write_data_bus(uint8_t value)
{
    for (uint8_t bit = 0u; bit < 8u; ++bit) {
        digitalWrite(kDataPins[bit], (value & (uint8_t)(1u << bit)) ? HIGH : LOW);
    }
}

uint8_t read_data_bus()
{
    uint8_t value = 0u;
    for (uint8_t bit = 0u; bit < 8u; ++bit) {
        if (digitalRead(kDataPins[bit]) != LOW) {
            value |= (uint8_t)(1u << bit);
        }
    }
    return value;
}

void pulse_wr()
{
    delayMicroseconds(2);
    digitalWrite(kPinWrN, LOW);
    delayMicroseconds(2);
    digitalWrite(kPinWrN, HIGH);
    delayMicroseconds(2);
}

void write_i80_byte(bool data_phase, uint8_t value)
{
    digitalWrite(kPinDc, data_phase ? HIGH : LOW);
    write_data_bus(value);
    pulse_wr();
}

uint8_t read_i80_byte()
{
    digitalWrite(kPinDc, HIGH);
    delayMicroseconds(2);
    digitalWrite(kPinRdN, LOW);
    delayMicroseconds(2);
    const uint8_t value = read_data_bus();
    digitalWrite(kPinRdN, HIGH);
    delayMicroseconds(2);
    return value;
}

void i80_init()
{
    data_bus_output();
    pinMode(kPinDc, OUTPUT);
    pinMode(kPinCsN, OUTPUT);
    pinMode(kPinWrN, OUTPUT);
    pinMode(kPinRdN, OUTPUT);
    digitalWrite(kPinCsN, HIGH);
    digitalWrite(kPinWrN, HIGH);
    digitalWrite(kPinRdN, HIGH);
    digitalWrite(kPinDc, LOW);
    write_data_bus(0u);
}

void reg_write(uint16_t addr, uint16_t data)
{
    data_bus_output();
    digitalWrite(kPinRdN, HIGH);
    digitalWrite(kPinWrN, HIGH);
    digitalWrite(kPinCsN, LOW);
    delayMicroseconds(5);
    write_i80_byte(false, 0x00u);
    write_i80_byte(false, (uint8_t)(addr & 0xFFu));
    write_i80_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    write_i80_byte(true, (uint8_t)(data & 0xFFu));
    write_i80_byte(true, (uint8_t)((data >> 8) & 0xFFu));
    digitalWrite(kPinCsN, HIGH);
    digitalWrite(kPinDc, LOW);
    write_data_bus(0u);
}

uint16_t reg_read(uint16_t addr)
{
    data_bus_output();
    digitalWrite(kPinRdN, HIGH);
    digitalWrite(kPinWrN, HIGH);
    digitalWrite(kPinCsN, LOW);
    delayMicroseconds(5);
    write_i80_byte(false, 0x01u);
    write_i80_byte(false, (uint8_t)(addr & 0xFFu));
    write_i80_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    data_bus_input();
    delayMicroseconds(5);
    const uint8_t lo = read_i80_byte();
    const uint8_t hi = read_i80_byte();
    digitalWrite(kPinCsN, HIGH);
    data_bus_output();
    digitalWrite(kPinDc, LOW);
    write_data_bus(0u);
    return (uint16_t)lo | ((uint16_t)hi << 8);
}

bool write_check(uint16_t addr, uint16_t value, const char *label)
{
    reg_write(addr, value);
    const uint16_t got = reg_read(addr);
    const bool ok = (got == value);
    Serial.printf("%s reg[0x%04X]=0x%04X read=0x%04X %s\n",
                  label, addr, value, got, ok ? "PASS" : "FAIL");
    return ok;
}

void palette_rgb888(uint8_t index, uint8_t r, uint8_t g, uint8_t b)
{
    reg_write(kRegPalettePtr, (uint16_t)(index * 2u));
    reg_write(kRegPaletteData, (uint16_t)(((uint16_t)g << 8) | b));
    reg_write(kRegPaletteData, r);
}

bool reset_visible_state()
{
    bool ok = true;
    ok &= write_check(kRegVdpCtrl, 0x0000u, "reset");
    delay(4);
    ok &= write_check(kRegLayerEnable, 0x0000u, "reset");
    ok &= write_check(kRegBitmapCtrl, 0x0000u, "reset");
    ok &= write_check(kRegModeSelect, 0x0000u, "reset");
    ok &= write_check(kRegPlanarCtrl, 0x0000u, "reset");
    ok &= write_check(kRegColorMathCtrl, 0x0000u, "reset");
    ok &= write_check(kRegBorderCtrl, 0x0000u, "reset");
    ok &= write_check(kRegBackdropIndex, 0x0000u, "reset");
    ok &= write_check(kRegBorderX0, 0x0000u, "reset");
    ok &= write_check(kRegBorderX1, 0x0000u, "reset");
    ok &= write_check(kRegBorderY0, 0x0000u, "reset");
    ok &= write_check(kRegBorderY1, 0x0000u, "reset");
    return ok;
}

void load_palette()
{
    palette_rgb888(0u, 255u, 0u, 0u);
    palette_rgb888(1u, 0u, 255u, 0u);
    palette_rgb888(2u, 0u, 0u, 255u);
    palette_rgb888(3u, 255u, 255u, 255u);
}

void upload_copper_program()
{
    for (uint16_t i = 0u; i < (uint16_t)(sizeof(kCopperBands) / sizeof(kCopperBands[0])); ++i) {
        reg_write((uint16_t)(kCopperRamBase + i), kCopperBands[i]);
    }
}

void print_copper_program()
{
    Serial.printf("copper program words=%u:", (unsigned)(sizeof(kCopperBands) / sizeof(kCopperBands[0])));
    for (uint8_t i = 0u; i < (uint8_t)(sizeof(kCopperBands) / sizeof(kCopperBands[0])); ++i) {
        Serial.printf(" 0x%04X", kCopperBands[i]);
    }
    Serial.println();
}

}  // namespace

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("ESP32-S3 i80 copper raster-bands probe");

    i80_init();
    delay(50);

    bool ok = reset_visible_state();
    load_palette();

    Serial.println("PHASE direct_border_red");
    ok &= write_check(kRegBorderCtrl, kBorderRed, "direct-red");
    delay(3000);

    Serial.println("PHASE direct_border_green");
    ok &= write_check(kRegBorderCtrl, kBorderGreen, "direct-green");
    delay(3000);

    print_copper_program();
    Serial.println("PHASE copper_raster_bands");
    write_check(kRegVdpCtrl, 0x0000u, "copper-disable");
    delay(2);
    upload_copper_program();
    write_check(kRegVdpCtrl, 0x0001u, "copper-enable");
    delay(5000);

    const uint16_t vdp_ctrl = reg_read(kRegVdpCtrl);
    Serial.printf("i80_copper_raster_bands_probe vdp_ctrl=0x%04X %s\n",
                  vdp_ctrl, (ok && ((vdp_ctrl & 0x0001u) != 0u)) ? "PASS" : "FAIL");
    Serial.println("expected visuals: red, green, then copper-driven horizontal red/green/blue/white bands");
}

void loop()
{
    delay(1000);
}
