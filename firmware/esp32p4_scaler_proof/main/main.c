/*
 * ESP32-P4 scaler hardware-proof host.
 *
 * SCALER_PROOF_MODE=0: 1x checkerboard regression (explicit 640x480 / 1x reset)
 * SCALER_PROOF_MODE=2: 2x centered checkerboard, logic 300x220
 * SCALER_PROOF_MODE=3: 3x centered checkerboard, logic 200x150
 * SCALER_PROOF_MODE=4: QSPI write-vs-readback discriminator (proof only)
 */
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

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
    BITMAP_BASE = 0x100000,
    ATTR_BASE = 0x110000,
    REG_SDRAM_READ_ADDR_LO = 0x0326,
    REG_SDRAM_READ_ADDR_HI = 0x0327,
};

static const char *TAG = "p4_scaler_proof";
static uint16_t s_bitmap[IMAGE_WORDS];
static uint16_t s_attr[IMAGE_WORDS];

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
    vdp_host_init();
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        ESP_LOGE(TAG, "host init failed err=%d", vdp_last_error());
        return;
    }
    ESP_LOGI(TAG, "scaler proof mode=%d magic=0x%08" PRIX32,
             SCALER_PROOF_MODE, vdp_read_status(SEL_MAGIC));

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
