/*
 * ESP32-P4 scaler hardware-proof host.
 *
 * SCALER_PROOF_MODE=0: 1x checkerboard regression with CS#-high pre-flight
 * SCALER_PROOF_MODE=2: 2x centered checkerboard, logic 300x220
 * SCALER_PROOF_MODE=3: 3x centered checkerboard, logic 200x150
 * SCALER_PROOF_MODE=4: QSPI write-vs-readback discriminator (proof only)
 * SCALER_PROOF_MODE=5: sel=8 readback SCLK sweep (proof only)
 * SCALER_PROOF_MODE=6: full readback_word double-read lag confirmation (proof only)
 * SCALER_PROOF_MODE=7: display-indirect target-word color discriminator (proof only)
 * SCALER_PROOF_MODE=8: READ_DONE completion-poll readback (proof only)
 * SCALER_PROOF_MODE=9: CS# high-before-SPI reset/settle diagnostic (proof only)
 */
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "driver/gpio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "vdp_host.h"
#include "vdp_mode0.h"

#ifndef SCALER_PROOF_MODE
#define SCALER_PROOF_MODE 0
#endif

enum {
    WIDTH = 320,
    HEIGHT = 240,
    ROW_STRIDE = 128,
    IMAGE_BYTES = HEIGHT * ROW_STRIDE,
    IMAGE_WORDS = IMAGE_BYTES / 2,
    CHECKER_SQUARE = 32,
    MAX_CHUNK_WORDS = 253,
    SEL_MAGIC = 0x00,
    SEL_SDRAM = 0x08,
    SEL_TRANSPORT_HEALTH = 0x0A,
    SEL_CRC8_STATUS = 0x0B,
    SEL_READ_DONE = 0x0C,
    BITMAP_BASE = 0x100000,
    ATTR_BASE = 0x110000,
    REG_SDRAM_READ_ADDR_LO = 0x0326,
    REG_SDRAM_READ_ADDR_HI = 0x0327,
    SWEEP_CYCLES = 30,
    READ_DONE_POLL_LIMIT = 100,
};

static const char *TAG = "p4_scaler_proof";
static uint16_t s_bitmap[IMAGE_WORDS];
static uint16_t s_attr[IMAGE_WORDS];

#if SCALER_PROOF_MODE == 0 || SCALER_PROOF_MODE == 9
static void hold_qspi_cs_high(void)
{
    /* Set the output latch before the settle delay; SPI2 takes ownership later. */
    (void)gpio_set_direction(GPIO_NUM_20, GPIO_MODE_OUTPUT);
    (void)gpio_set_level(GPIO_NUM_20, 1u);
    (void)gpio_set_pull_mode(GPIO_NUM_20, GPIO_PULLUP_ONLY);
    ESP_LOGI(TAG, "CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200");
    vTaskDelay(pdMS_TO_TICKS(1200));
}
#endif

static void build_checkerboard(void)
{
    uint8_t *bitmap = (uint8_t *)s_bitmap;
    uint8_t *attr = (uint8_t *)s_attr;
    memset(bitmap, 0, IMAGE_BYTES);
    memset(attr, 0xE4, IMAGE_BYTES);
    for (unsigned y = 0; y < HEIGHT; ++y) {
        for (unsigned x = 0; x < WIDTH; ++x) {
            const uint8_t color = (uint8_t)(((x / CHECKER_SQUARE) ^
                                             (y / CHECKER_SQUARE)) & 1u);
            const unsigned byte_index = y * ROW_STRIDE + (x / 4u);
            const unsigned shift = 6u - ((x & 3u) * 2u);
            bitmap[byte_index] |= (uint8_t)(color << shift);
        }
    }
}

static bool health(const char *label)
{
    const uint32_t raw = vdp_read_status(SEL_TRANSPORT_HEALTH);
    const bool overflow = (raw & 0x1u) != 0u;
    const bool malformed = (raw & 0x2u) != 0u;
    ESP_LOGI(TAG, "%s raw=0x%08" PRIX32 " overflow=%u malformed=%u",
             label, raw, overflow ? 1u : 0u, malformed ? 1u : 0u);
    return vdp_last_error() == VDP_HOST_ERR_NONE && !overflow && !malformed;
}

static bool write_linestate(void)
{
    uint16_t words[480];
    for (unsigned i = 0; i < 480u; ++i) words[i] = 0x0800u;
    for (unsigned offset = 0; offset < 480u; offset += MAX_CHUNK_WORDS) {
        const uint16_t count = (uint16_t)(((480u - offset) < MAX_CHUNK_WORDS) ?
                                          (480u - offset) : MAX_CHUNK_WORDS);
        vdp_reg_write_burst(0u + offset, words + offset, count);
        if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    }
    ESP_LOGI(TAG, "LINESTATE PASS lines=480 chunks=2");
    return true;
}

static bool load_palette(void)
{
    vdp_mode0_palette_write_rgb888(0u, 0u, 0u, 0u);
    vdp_mode0_palette_write_rgb888(1u, 255u, 255u, 255u);
    vdp_mode0_palette_write_rgb888(2u, 255u, 0u, 0u);
    vdp_mode0_palette_write_rgb888(3u, 0u, 0u, 255u);
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

static bool upload_plane(uint32_t base, const uint16_t *words, const char *name)
{
    vdp_host_set_speed_hz(4000000u);
    for (unsigned offset = 0; offset < IMAGE_WORDS; offset += MAX_CHUNK_WORDS) {
        const uint16_t count = (uint16_t)(((IMAGE_WORDS - offset) < MAX_CHUNK_WORDS) ?
                                          (IMAGE_WORDS - offset) : MAX_CHUNK_WORDS);
        vdp_sdram_write(base + (offset * 2u), words + offset, count);
        if (vdp_last_error() != VDP_HOST_ERR_NONE) {
            ESP_LOGE(TAG, "%s upload failed offset=%u err=%d", name, offset,
                     vdp_last_error());
            return false;
        }
    }
    ESP_LOGI(TAG, "%s uploaded bytes=%u clock=4000000", name, IMAGE_BYTES);
    return true;
}

static bool upload_plane_diagnostic(uint32_t base, const uint16_t *words,
                                    const char *name)
{
    const unsigned frame_count = (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) /
                                 MAX_CHUNK_WORDS;
    for (unsigned frame = 0; frame < frame_count; ++frame) {
        const unsigned offset = frame * MAX_CHUNK_WORDS;
        const uint16_t count = (uint16_t)(((IMAGE_WORDS - offset) < MAX_CHUNK_WORDS) ?
                                          (IMAGE_WORDS - offset) : MAX_CHUNK_WORDS);
        const uint32_t frame_addr = base + offset * 2u;
        vdp_host_set_speed_hz(2000000u);
        const uint32_t crc_before = vdp_read_status(SEL_CRC8_STATUS);
        const int crc_before_err = vdp_last_error();
        vdp_host_set_speed_hz(4000000u);
        vdp_sdram_write(frame_addr, words + offset, count);
        const int write_err = vdp_last_error();
        vdp_host_set_speed_hz(2000000u);
        const uint32_t crc_after = vdp_read_status(SEL_CRC8_STATUS);
        const int crc_after_err = vdp_last_error();
        ESP_LOGI(TAG,
                 "DIAG_FRAME plane=%s frame=%u addr=0x%06" PRIX32
                 " words=%u bytes=%u crc_before=0x%08" PRIX32
                 " crc_after=0x%08" PRIX32 " crc_err=%d/%d write_err=%d",
                 name, frame, frame_addr, count, (unsigned)count * 2u,
                 crc_before, crc_after, crc_before_err, crc_after_err,
                 write_err);
        if (write_err != VDP_HOST_ERR_NONE ||
            crc_before_err != VDP_HOST_ERR_NONE ||
            crc_after_err != VDP_HOST_ERR_NONE) {
            return false;
        }
    }
    ESP_LOGI(TAG, "DIAG_UPLOAD plane=%s frames=%u chunk_words=%u chunk_bytes=%u",
             name, frame_count, MAX_CHUNK_WORDS, MAX_CHUNK_WORDS * 2u);
    return true;
}

static bool readback_word(uint32_t addr, uint32_t *value)
{
    vdp_host_set_speed_hz(2000000u);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    *value = vdp_read_status(SEL_SDRAM);
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

static bool readback_word_wait_done(uint32_t addr, uint32_t *value,
                                    unsigned *poll_count)
{
    vdp_host_set_speed_hz(2000000u);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;

    for (unsigned poll = 1u; poll <= READ_DONE_POLL_LIMIT; ++poll) {
        const uint32_t status = vdp_read_status(SEL_READ_DONE);
        const int error = vdp_last_error();
        const bool done = (status & 0x1u) != 0u;
        const bool reserved_zero = (status & ~0x1u) == 0u;
        ESP_LOGI(TAG,
                 "READ_DONE_POLL addr=0x%06" PRIX32 " poll=%u raw=0x%08" PRIX32
                 " done=%u reserved_zero=%u err=%d",
                 addr, poll, status, done ? 1u : 0u,
                 reserved_zero ? 1u : 0u, error);
        if (poll_count != NULL) *poll_count = poll;
        if (error != VDP_HOST_ERR_NONE || !reserved_zero) return false;
        if (done) {
            *value = vdp_read_status(SEL_SDRAM);
            return vdp_last_error() == VDP_HOST_ERR_NONE;
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }

    ESP_LOGE(TAG, "READ_DONE_TIMEOUT addr=0x%06" PRIX32 " polls=%u",
             addr, READ_DONE_POLL_LIMIT);
    if (poll_count != NULL) *poll_count = READ_DONE_POLL_LIMIT;
    return false;
}

static bool readback_word_twice(uint32_t addr, uint32_t *first,
                                uint32_t *second)
{
    /* Each full call rewrites REG_SDRAM_READ_ADDR_HI and arms a new read. */
    if (!readback_word(addr, first)) return false;
    return readback_word(addr, second);
}

static uint32_t bitmap_expected_word(uint32_t addr);

static bool readback_word_at_rate(uint32_t addr, uint32_t rate_hz,
                                  uint32_t *value, int *error)
{
    vdp_host_set_speed_hz(rate_hz);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        *error = vdp_last_error();
        return false;
    }
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        *error = vdp_last_error();
        return false;
    }
    *value = vdp_read_status(SEL_SDRAM);
    *error = vdp_last_error();
    return *error == VDP_HOST_ERR_NONE;
}

static bool sweep_readback(void)
{
    static const uint32_t rates_hz[] = {
        2000000u, 1000000u, 500000u, 250000u,
    };
    static const uint32_t addresses[] = {
        0x100004u, 0x100008u, 0x10000Cu,
        0x100FFCu, 0x101000u, 0x101004u,
    };
    bool pass = true;

    ESP_LOGI(TAG,
             "SWEEP_START rates=2000000,1000000,500000,250000 cycles=%u"
             " cs_post=8 targets=0x100008,0x101000 neighbors=word+-1",
             SWEEP_CYCLES);
    for (unsigned rate_index = 0;
         rate_index < sizeof(rates_hz) / sizeof(rates_hz[0]); ++rate_index) {
        const uint32_t rate_hz = rates_hz[rate_index];
        unsigned reads = 0u;
        unsigned value_pass = 0u;
        unsigned zero_values = 0u;
        unsigned errors = 0u;
        ESP_LOGI(TAG, "SWEEP_RATE_BEGIN hz=%" PRIu32, rate_hz);
        for (unsigned cycle = 0; cycle < SWEEP_CYCLES; ++cycle) {
            for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
                const uint32_t addr = addresses[i];
                const uint32_t expected = bitmap_expected_word(addr);
                uint32_t actual = 0u;
                int error = VDP_HOST_ERR_NONE;
                const bool read_ok = readback_word_at_rate(addr, rate_hz,
                                                           &actual, &error);
                const bool word_pass = read_ok && actual == expected;
                ++reads;
                if (word_pass) {
                    ++value_pass;
                } else {
                    pass = false;
                }
                if (actual == 0u) ++zero_values;
                if (error != VDP_HOST_ERR_NONE) ++errors;
                ESP_LOGI(TAG,
                         "SWEEP_READ hz=%" PRIu32 " cycle=%u addr=0x%06" PRIX32
                         " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                         " ok=%u err=%d",
                         rate_hz, cycle, addr, expected, actual,
                         word_pass ? 1u : 0u, error);
            }
            const uint32_t health_raw = vdp_read_status(SEL_TRANSPORT_HEALTH);
            const int health_error = vdp_last_error();
            ESP_LOGI(TAG,
                     "SWEEP_HEALTH hz=%" PRIu32 " cycle=%u raw=0x%08" PRIX32
                     " overflow=%u malformed=%u err=%d",
                     rate_hz, cycle, health_raw, health_raw & 1u,
                     (health_raw >> 1) & 1u, health_error);
            if (health_error != VDP_HOST_ERR_NONE || (health_raw & 3u) != 0u) {
                pass = false;
            }
        }
        ESP_LOGI(TAG,
                 "SWEEP_SUMMARY hz=%" PRIu32 " reads=%u pass=%u zeros=%u errors=%u",
                 rate_hz, reads, value_pass, zero_values, errors);
    }
    ESP_LOGI(TAG, "SWEEP_RESULT pass=%u", pass ? 1u : 0u);
    return pass;
}

static bool verify_sample(uint32_t addr)
{
    const uint8_t *bitmap = (const uint8_t *)s_bitmap;
    const uint32_t expected = (uint32_t)bitmap[addr - BITMAP_BASE] |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 1u] << 8) |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 2u] << 16) |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 3u] << 24);
    uint32_t actual = 0;
    if (!readback_word(addr, &actual) || actual != expected) {
        ESP_LOGE(TAG, "READBACK FAIL addr=0x%06" PRIX32
                 " expected=0x%08" PRIX32 " got=0x%08" PRIX32,
                 addr, expected, actual);
        return false;
    }
    ESP_LOGI(TAG, "READBACK PASS addr=0x%06" PRIX32 " value=0x%08" PRIX32,
             addr, actual);
    return true;
}

static bool verify_readback(void)
{
    static const uint32_t offsets[] = { 0u, 8u, 16u, 32u * ROW_STRIDE,
                                        200u * ROW_STRIDE, 201u * ROW_STRIDE };
    bool pass = true;
    for (unsigned i = 0; i < sizeof(offsets) / sizeof(offsets[0]); ++i) {
        pass &= verify_sample(BITMAP_BASE + offsets[i]);
    }
    return pass;
}

static uint32_t bitmap_expected_word(uint32_t addr)
{
    const uint8_t *bitmap = (const uint8_t *)s_bitmap;
    const unsigned offset = (unsigned)(addr - BITMAP_BASE);
    return (uint32_t)bitmap[offset] |
           ((uint32_t)bitmap[offset + 1u] << 8) |
           ((uint32_t)bitmap[offset + 2u] << 16) |
           ((uint32_t)bitmap[offset + 3u] << 24);
}

static bool diagnostic_neighbor_reads(void)
{
    static const uint32_t first_window[] = {
        0x100000u, 0x100004u, 0x100008u, 0x10000Cu,
        0x100010u, 0x100014u, 0x100018u, 0x10001Cu,
    };
    static const uint32_t second_window[] = {
        0x100FF8u, 0x100FFCu, 0x101000u, 0x101004u, 0x101008u,
    };
    bool pass = true;
    ESP_LOGI(TAG, "DIAG_GEOMETRY sdram_row_bytes=1024 addr=bank[22:21],row[20:10],col[9:2],lane[1:0]");
    ESP_LOGI(TAG, "DIAG_SAMPLE_LIST first=0x100000,0x100004,0x100008,0x10000C,0x100010,0x100014,0x100018,0x10001C");
    ESP_LOGI(TAG, "DIAG_SAMPLE_LIST second=0x100FF8,0x100FFC,0x101000,0x101004,0x101008");
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        const uint32_t *windows[] = { first_window, second_window };
        const unsigned counts[] = {
            sizeof(first_window) / sizeof(first_window[0]),
            sizeof(second_window) / sizeof(second_window[0]),
        };
        for (unsigned window = 0; window < 2u; ++window) {
            for (unsigned i = 0; i < counts[window]; ++i) {
                const uint32_t addr = windows[window][i];
                uint32_t actual = 0;
                const bool read_ok = readback_word(addr, &actual);
                const uint32_t expected = bitmap_expected_word(addr);
                ESP_LOGI(TAG,
                         "DIAG_READ repeat=%u addr=0x%06" PRIX32
                         " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                         " read_ok=%u err=%d",
                         repeat, addr, expected, actual, read_ok ? 1u : 0u,
                         vdp_last_error());
                if (!read_ok || actual != expected) pass = false;
            }
        }
    }
    ESP_LOGI(TAG, "DIAG_READ_RESULT pass=%u repeats=8 addresses=13",
             pass ? 1u : 0u);
    return pass;
}

static bool diagnostic_double_reads(void)
{
    static const uint32_t addresses[] = {
        0x100004u, 0x100008u, 0x10000Cu,
        0x100FFCu, 0x101000u, 0x101004u,
    };
    bool pass = true;
    ESP_LOGI(TAG,
             "DOUBLE_READ_START addresses=0x100004,0x100008,0x10000C"
             ",0x100FFC,0x101000,0x101004 repeats=8");
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
            const uint32_t addr = addresses[i];
            const uint32_t expected = bitmap_expected_word(addr);
            uint32_t first = 0u;
            uint32_t second = 0u;
            const bool read_ok = readback_word_twice(addr, &first, &second);
            const bool second_pass = read_ok && second == expected;
            ESP_LOGI(TAG,
                     "DOUBLE_READ repeat=%u addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " first=0x%08" PRIX32
                     " second=0x%08" PRIX32 " ok=%u err=%d",
                     repeat, addr, expected, first, second,
                     second_pass ? 1u : 0u, vdp_last_error());
            if (!second_pass) pass = false;
        }
    }
    ESP_LOGI(TAG, "DOUBLE_READ_RESULT pass=%u repeats=8 addresses=6",
             pass ? 1u : 0u);
    return pass;
}

static void diagnostic_dummy_then_target(void)
{
    static const struct {
        uint32_t dummy;
        uint32_t target;
    } pairs[] = {
        { 0x100004u, 0x100008u },
        { 0x100FFCu, 0x101000u },
    };
    unsigned lag_matches = 0u;
    unsigned target_matches = 0u;
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(pairs) / sizeof(pairs[0]); ++i) {
            uint32_t dummy_value = 0u;
            uint32_t target_value = 0u;
            const uint32_t dummy_expected = bitmap_expected_word(pairs[i].dummy);
            const uint32_t target_expected = bitmap_expected_word(pairs[i].target);
            const bool dummy_ok = readback_word(pairs[i].dummy, &dummy_value);
            const bool target_ok = readback_word(pairs[i].target, &target_value);
            const bool lag_match = target_ok && target_value == dummy_expected;
            const bool target_match = target_ok && target_value == target_expected;
            if (lag_match) ++lag_matches;
            if (target_match) ++target_matches;
            ESP_LOGI(TAG,
                     "DUMMY_TARGET repeat=%u dummy=0x%06" PRIX32
                     " target=0x%06" PRIX32 " dummy_expected=0x%08" PRIX32
                     " dummy_got=0x%08" PRIX32 " target_expected=0x%08" PRIX32
                     " target_got=0x%08" PRIX32 " lag_match=%u target_match=%u"
                     " ok=%u/%u err=%d",
                     repeat, pairs[i].dummy, pairs[i].target, dummy_expected,
                     dummy_value, target_expected, target_value,
                     lag_match ? 1u : 0u, target_match ? 1u : 0u,
                     dummy_ok ? 1u : 0u, target_ok ? 1u : 0u,
                     vdp_last_error());
        }
    }
    ESP_LOGI(TAG,
             "DUMMY_TARGET_RESULT repeats=8 pairs=2 lag_matches=%u"
             " target_matches=%u",
             lag_matches, target_matches);
}

static void build_display_indirect_pattern(void)
{
    uint8_t *bitmap = (uint8_t *)s_bitmap;
    static const unsigned words[] = {
        0x100004u - BITMAP_BASE, 0x100008u - BITMAP_BASE,
        0x10000Cu - BITMAP_BASE, 0x100FFCu - BITMAP_BASE,
        0x101000u - BITMAP_BASE, 0x101004u - BITMAP_BASE,
    };
    for (unsigned i = 0; i < sizeof(words) / sizeof(words[0]); ++i) {
        const unsigned offset = words[i];
        memset(bitmap + offset, 0xAA, 4u);
    }
    ESP_LOGI(TAG,
             "INDIRECT_ASSET targets=0x100008,0x101000 color=palette2"
             " byte_pattern=0xAA neighbors=word+-1");
}

static bool diagnostic_completion_poll(void)
{
    static const uint32_t addresses[] = { 0x100008u, 0x101000u };
    bool pass = true;
    unsigned total_polls = 0u;
    unsigned max_polls = 0u;
    ESP_LOGI(TAG,
             "READ_DONE_START selector=0x%02X bit=0 polarity=high"
             " arm=0x0327 data_selector=0x%02X repeats=8",
             SEL_READ_DONE, SEL_SDRAM);
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
            const uint32_t addr = addresses[i];
            const uint32_t expected = bitmap_expected_word(addr);
            uint32_t actual = 0u;
            unsigned polls = 0u;
            const bool read_ok = readback_word_wait_done(addr, &actual, &polls);
            const bool word_pass = read_ok && actual == expected;
            total_polls += polls;
            if (polls > max_polls) max_polls = polls;
            ESP_LOGI(TAG,
                     "READ_DONE_READ repeat=%u addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                     " polls=%u pass=%u err=%d",
                     repeat, addr, expected, actual, polls,
                     word_pass ? 1u : 0u, vdp_last_error());
            if (!word_pass) pass = false;
        }
    }
    ESP_LOGI(TAG,
             "READ_DONE_RESULT pass=%u repeats=8 addresses=2 total_polls=%u"
             " max_polls=%u",
             pass ? 1u : 0u, total_polls, max_polls);
    return pass;
}

static bool configure_display(void)
{
    const vdp_mode0_bitmap_cfg_t bitmap = {
        .ctrl = 0x0002u,
        .bitmap_base = BITMAP_BASE,
        .attr_base = ATTR_BASE,
        .bitmap_stride = ROW_STRIDE,
        .attr_stride = ROW_STRIDE,
        .height = HEIGHT,
    };
    const vdp_mode0_rect_t full_frame = { 0u, 640u, 0u, 480u };

    vdp_mode0_set_layer_enable(0u);
    vdp_mode0_set_mode_select(0u);
    vdp_mode0_set_bitmap_cfg(&bitmap);
    vdp_mode0_set_border_window(&full_frame, 0x0101u);
    vdp_mode0_set_backdrop_index(0u);
    if (!load_palette() || vdp_last_error() != VDP_HOST_ERR_NONE) return false;

#if SCALER_PROOF_MODE == 2
    vdp_mode0_set_logic_size(300u, 220u);
    vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(2u, 2u, true));
    ESP_LOGI(TAG, "scale=2x logic=300x220 expected_bezel=20x20 ctrl=0x%02X",
             vdp_mode0_scale_ctrl(2u, 2u, true));
#elif SCALER_PROOF_MODE == 3
    vdp_mode0_set_logic_size(200u, 150u);
    vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(3u, 3u, true));
    ESP_LOGI(TAG, "scale=3x logic=200x150 expected_bezel=20x15 ctrl=0x%02X",
             vdp_mode0_scale_ctrl(3u, 3u, true));
#else
    /* SCALE_CTRL persists across MCU resets while the FPGA remains loaded. */
    vdp_mode0_set_logic_size(640u, 480u);
    vdp_mode0_set_scale_ctrl(0u);
    ESP_LOGI(TAG, "scale=1x explicit logic=640x480 ctrl=0x00");
#endif
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

void app_main(void)
{
    bool pass = true;
#if SCALER_PROOF_MODE == 0 || SCALER_PROOF_MODE == 9
    hold_qspi_cs_high();
#endif
    vdp_host_init();
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        ESP_LOGE(TAG, "host init failed err=%d", vdp_last_error());
        return;
    }
    /* Sample the GPIO input matrix after SPI claims the bus, before READ_STATUS. */
    const int cs_post_init_level = gpio_get_level(GPIO_NUM_20);
    ESP_LOGI(TAG, "CS_POST_INIT_PROBE cs_gpio=20 level=%d", cs_post_init_level);
    ESP_LOGI(TAG,
             "SPI_CONFIG cs_io_num=20 cs_ena_pretrans=2 cs_ena_posttrans=8"
             " mode=0 clock_hz=2000000 idle_policy=driver-default");
#if SCALER_PROOF_MODE == 0
    /* PM-authorized post-reconfigure prime: discard one responder read before
     * using the second magic read as the campaign gate. */
    const uint32_t prime_magic = vdp_read_status(SEL_MAGIC);
    ESP_LOGI(TAG, "LANE1_PRIME_DISCARD raw=0x%08" PRIX32
             " err=%d", prime_magic, vdp_last_error());
#endif
    const uint32_t magic = vdp_read_status(SEL_MAGIC);
    ESP_LOGI(TAG, "scaler proof mode=%d magic=0x%08" PRIX32,
             SCALER_PROOF_MODE, magic);

#if SCALER_PROOF_MODE == 9
    const uint32_t health_raw = vdp_read_status(SEL_TRANSPORT_HEALTH);
    const bool magic_ok = magic == 0x51560002u;
    const bool health_ok = health_raw == 0u &&
                           vdp_last_error() == VDP_HOST_ERR_NONE;
    ESP_LOGI(TAG, "CS_IDLE_PROOF magic_ok=%u health_raw=0x%08" PRIX32
             " health_ok=%u", magic_ok ? 1u : 0u, health_raw,
             health_ok ? 1u : 0u);
    ESP_LOGI(TAG, "CS_IDLE_PROOF_RESULT pass=%u", (magic_ok && health_ok) ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

    build_checkerboard();
    pass &= configure_display();

#if SCALER_PROOF_MODE == 4
    ESP_LOGI(TAG,
             "DIAG_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X image_words=%u"
             " chunk_words=%u chunk_bytes=%u bitmap_frames=%u attr_frames=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u,
             (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) / MAX_CHUNK_WORDS,
             (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) / MAX_CHUNK_WORDS);
    pass &= health("HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane_diagnostic(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane_diagnostic(ATTR_BASE, s_attr, "attr");
    pass &= health("HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_neighbor_reads();
    ESP_LOGI(TAG, "DIAG_RESULT pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 5
    ESP_LOGI(TAG,
             "SWEEP_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X image_words=%u"
             " chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("SWEEP_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("SWEEP_HEALTH_AFTER_UPLOAD");
    pass &= sweep_readback();
    ESP_LOGI(TAG, "SWEEP_DONE pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 6
    ESP_LOGI(TAG,
             "DOUBLE_READ_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("DOUBLE_READ_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("DOUBLE_READ_HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_double_reads();
    diagnostic_dummy_then_target();
    ESP_LOGI(TAG, "DOUBLE_READ_DONE pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 7
    build_display_indirect_pattern();
    ESP_LOGI(TAG,
             "INDIRECT_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("INDIRECT_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("INDIRECT_HEALTH_AFTER_UPLOAD");
    vdp_mode0_set_bitmap_ctrl(0x0003u);
    vdp_mode0_set_layer_enable(0x0001u);
    pass &= health("INDIRECT_HEALTH_AFTER_ENABLE");
    ESP_LOGI(TAG, "INDIRECT_DISPLAY_READY pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 8
    ESP_LOGI(TAG,
             "READ_DONE_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("READ_DONE_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("READ_DONE_HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_completion_poll();
    pass &= health("READ_DONE_HEALTH_AFTER_READ");
    ESP_LOGI(TAG, "READ_DONE_PROOF pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

    pass &= health("HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    vdp_host_set_speed_hz(2000000u);
    pass &= health("HEALTH_AFTER_UPLOAD");
    pass &= verify_readback();
    pass &= write_linestate();

    vdp_mode0_set_bitmap_ctrl(0x0003u);
    vdp_mode0_set_layer_enable(0x0001u);
    pass &= health("HEALTH_AFTER_ENABLE");
    ESP_LOGI(TAG, "SCALER_PROOF mode=%d pass=%u", SCALER_PROOF_MODE, pass ? 1u : 0u);

    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
}
