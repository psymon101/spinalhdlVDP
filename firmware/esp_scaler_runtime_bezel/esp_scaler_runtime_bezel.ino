/**
 * esp_scaler_runtime_bezel.ino — CP-B runtime scaler exercise sketch.
 *
 * Purpose:
 *   Drive the proven scaler register presets from libvdp so BrightForge can
 *   bench-compare runtime writes against the Step 2 POR-init captures.
 *
 * Targets:
 *   - ESP32 family via Arduino core (`esp32:esp32:*`)
 *   - ESP8266 NodeMCU parity compile-check (`esp8266:esp8266:nodemcuv2`)
 *
 * Mode sequence:
 *   1. 1x bypass      — logic 640x480, no auto-center
 *   2. 2x repeat      — logic 320x240, no auto-center
 *   3. 3x auto-center — logic 160x160, white bezel
 *   4. 4x auto-center — logic 128x120, white bezel
 *
 * Notes:
 *   - The sketch keeps the scaler presets bench-faithful to the proven
 *     scenario tops. That means 1x and 2x are intentionally all-black in the
 *     active area; 3x and 4x produce the measurable white bezel.
 *   - Border is enabled with palette index 1. The manual border window is set
 *     to full-frame so 1x/2x remain visually equivalent to the proven POR
 *     states until auto-center takes over.
 */

#include <Arduino.h>

#include <vdp_mode0.h>
#include <vdp_qspi.h>

namespace {

constexpr uint32_t kExpectedMagic = 0x51560002u;
constexpr uint16_t kHoldMs = 3500u;
constexpr uint16_t kBorderWhite = 0x0101u;

struct ScaleMode {
    const char *name;
    uint8_t scale_x;
    uint8_t scale_y;
    bool auto_center;
    uint16_t logic_width;
    uint16_t logic_height;
};

constexpr ScaleMode kModes[] = {
    { "1x bypass",      1u, 1u, false, 640u, 480u },
    { "2x repeat",      2u, 2u, false, 320u, 240u },
    { "3x auto-center", 3u, 3u, true,  160u, 160u },
    { "4x auto-center", 4u, 4u, true,  128u, 120u },
};

void write_backdrop_black_all_banks(void)
{
    for (uint8_t bank = 0; bank < 8; ++bank) {
        vdp_mode0_palette_write_rgb888((uint8_t)(bank * 16u), 0u, 0u, 0u);
    }
}

void init_scene(void)
{
    const vdp_mode0_rect_t full_frame = { 0u, 640u, 0u, 480u };

    vdp_reg_write(VDP_MODE0_REG_VDP_CTRL, 0x0000u);
    vdp_mode0_set_layer_enable(0x0000u);
    vdp_mode0_set_bitmap_ctrl(0x0000u);
    vdp_mode0_set_color_math(0x0000u);
    vdp_mode0_set_border_window(&full_frame, kBorderWhite);

    write_backdrop_black_all_banks();
    vdp_mode0_palette_write_rgb888(1u, 255u, 255u, 255u);
}

void print_status(const ScaleMode &mode, uint16_t ctrl)
{
    const uint32_t magic = vdp_read_status(0);
    const uint32_t last_error = vdp_read_status(4);
    const uint32_t sticky = vdp_read_status(5);

    Serial.print("mode=");
    Serial.print(mode.name);
    Serial.print(" ctrl=0x");
    Serial.print(ctrl, HEX);
    Serial.print(" logic=");
    Serial.print(mode.logic_width);
    Serial.print("x");
    Serial.print(mode.logic_height);
    Serial.print(" magic=0x");
    Serial.print(magic, HEX);
    Serial.print(magic == kExpectedMagic ? " [PASS]" : " [FAIL]");
    Serial.print(" last_error=0x");
    Serial.print(last_error, HEX);
    Serial.print(" sticky=0x");
    Serial.println(sticky, HEX);
}

void apply_mode(const ScaleMode &mode)
{
    const uint16_t ctrl = vdp_mode0_scale_ctrl(mode.scale_x, mode.scale_y, mode.auto_center);
    vdp_mode0_set_border_ctrl(kBorderWhite);
    vdp_mode0_set_scale_mode(mode.scale_x, mode.scale_y, mode.auto_center,
                             mode.logic_width, mode.logic_height);
    delay(50);
    print_status(mode, ctrl);
}

}  // namespace

void setup(void)
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("=== scaler runtime bezel ===");
#if defined(ESP8266)
    Serial.println(F("Target: ESP8266 NodeMCU parity build"));
#elif defined(CONFIG_IDF_TARGET_ESP32S3) || defined(ARDUINO_ESP32S3_DEV) || defined(ARDUINO_ESP32S3_DEV_KIT_C_1)
    Serial.println("Target: ESP32-S3 bench path");
#elif defined(ESP32)
    Serial.println("Target: ESP32 family");
#endif

    vdp_qspi_init();
    delay(200);
    init_scene();
    Serial.println("Scene initialized: white border palette + black backdrop.");
}

void loop(void)
{
    for (const ScaleMode &mode : kModes) {
        apply_mode(mode);
        delay(kHoldMs);
    }
}
