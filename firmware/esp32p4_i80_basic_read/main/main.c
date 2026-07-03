/*
 * ESP32-P4 i80 basic register readback discriminator.
 *
 * Scenario: P4 hardware i80 writes one known 16-bit register value to the
 * Tang Nano 20K VDP, releases D0-D7, manually pulses RD#, samples the two
 * readback bytes, then recreates the i80 bus before any further write. This
 * deliberately proves the write/read/restore path once before any sweep.
 *
 * Approved pin map:
 *   D0=GPIO32, D1=GPIO33, D2=GPIO22, D3=GPIO23,
 *   D4=GPIO46, D5=GPIO47, D6=GPIO48, D7=GPIO29,
 *   DC=GPIO20, CS#=GPIO31, WR#=GPIO21, RD#=GPIO30.
 *
 * Boot sequence:
 *   1. Initialize ESP-IDF i80 bus at 2 MHz, CPU 360 MHz for rev v1.3.
 *   2. Write BORDER_CTRL once and read it back with RD# manual GPIO.
 *   3. If single transaction passes, run a bounded 512-transaction burst.
 *
 * Expected serial result:
 *   P4_I80_SINGLE result=PASS ...
 */
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

#include "driver/gpio.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_lcd_io_i80.h"
#include "esp_lcd_panel_io.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "p4_i80_basic";

enum {
    PIN_D0 = 32,
    PIN_D1 = 33,
    PIN_D2 = 22,
    PIN_D3 = 23,
    PIN_D4 = 46,
    PIN_D5 = 47,
    PIN_D6 = 48,
    PIN_D7 = 29,
    PIN_DC = 20,
    PIN_CS = 31,
    PIN_WR = 21,
    PIN_RD = 30,
};

static const gpio_num_t DATA_PINS[8] = {
    PIN_D0, PIN_D1, PIN_D2, PIN_D3, PIN_D4, PIN_D5, PIN_D6, PIN_D7,
};

static const uint16_t TEST_REG = 0x0305;        /* VDP_MODE0_REG_BORDER_CTRL */
static const uint32_t I80_PCLK_HZ = 2000000u;  /* first bounded gate */
static const uint16_t BURST_ROUNDS = 512u;

static esp_lcd_i80_bus_handle_t s_i80_bus;
static esp_lcd_panel_io_handle_t s_i80_setup_io;
static esp_lcd_panel_io_handle_t s_i80_data_io;

static void idle_manual_lines(void)
{
    gpio_set_level(PIN_CS, 1);
    gpio_set_level(PIN_WR, 1);
    gpio_set_level(PIN_RD, 1);
    gpio_set_level(PIN_DC, 0);
}

static void configure_manual_ctrl_pins(void)
{
    const uint64_t mask = (1ULL << PIN_CS) | (1ULL << PIN_RD) |
                          (1ULL << PIN_WR) | (1ULL << PIN_DC);
    gpio_config_t cfg = {
        .pin_bit_mask = mask,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&cfg));
    idle_manual_lines();
}

static void configure_manual_read_pins_preserve_cs(void)
{
    ESP_ERROR_CHECK(gpio_set_direction(PIN_RD, GPIO_MODE_OUTPUT));
    ESP_ERROR_CHECK(gpio_set_direction(PIN_DC, GPIO_MODE_OUTPUT));
    gpio_set_level(PIN_RD, 1);
    gpio_set_level(PIN_DC, 0);
}

static void set_data_inputs(void)
{
    for (size_t i = 0; i < 8; ++i) {
        ESP_ERROR_CHECK(gpio_reset_pin(DATA_PINS[i]));
        ESP_ERROR_CHECK(gpio_set_pull_mode(DATA_PINS[i], GPIO_PULLDOWN_ONLY));
        ESP_ERROR_CHECK(gpio_set_direction(DATA_PINS[i], GPIO_MODE_INPUT));
    }
}

static uint8_t sample_data_bus(void)
{
    uint8_t value = 0;
    for (size_t bit = 0; bit < 8; ++bit) {
        if (gpio_get_level(DATA_PINS[bit]) != 0) {
            value |= (uint8_t)(1u << bit);
        }
    }
    return value;
}

static uint8_t manual_rd_byte(void)
{
    gpio_set_level(PIN_DC, 1);
    esp_rom_delay_us(2);
    gpio_set_level(PIN_RD, 0);
    esp_rom_delay_us(2);
    const uint8_t value = sample_data_bus();
    gpio_set_level(PIN_RD, 1);
    esp_rom_delay_us(2);
    return value;
}

static esp_err_t i80_destroy(void)
{
    esp_err_t ret = ESP_OK;
    if (s_i80_data_io != NULL) {
        ret = esp_lcd_panel_io_del(s_i80_data_io);
        s_i80_data_io = NULL;
        ESP_RETURN_ON_ERROR(ret, TAG, "delete data panel IO failed");
    }
    if (s_i80_setup_io != NULL) {
        ret = esp_lcd_panel_io_del(s_i80_setup_io);
        s_i80_setup_io = NULL;
        ESP_RETURN_ON_ERROR(ret, TAG, "delete panel IO failed");
    }
    if (s_i80_bus != NULL) {
        ret = esp_lcd_del_i80_bus(s_i80_bus);
        s_i80_bus = NULL;
        ESP_RETURN_ON_ERROR(ret, TAG, "delete i80 bus failed");
    }
    return ESP_OK;
}

static esp_err_t create_panel_io(int dc_data_level, esp_lcd_panel_io_handle_t *out_io)
{
    esp_lcd_panel_io_i80_config_t io_config = {
        .cs_gpio_num = -1,
        .pclk_hz = I80_PCLK_HZ,
        .trans_queue_depth = 1,
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
        .dc_levels = {
            .dc_idle_level = 0,
            .dc_cmd_level = 0,
            .dc_dummy_level = 0,
            .dc_data_level = dc_data_level,
        },
        .flags = {
            .cs_active_high = 0,
            .pclk_active_neg = 0,
            .pclk_idle_low = 0,
        },
    };
    return esp_lcd_new_panel_io_i80(s_i80_bus, &io_config, out_io);
}

static esp_err_t i80_create(void)
{
    esp_lcd_i80_bus_config_t bus_config = {
        .dc_gpio_num = PIN_DC,
        .wr_gpio_num = PIN_WR,
        .clk_src = LCD_CLK_SRC_DEFAULT,
        .data_gpio_nums = {
            PIN_D0, PIN_D1, PIN_D2, PIN_D3,
            PIN_D4, PIN_D5, PIN_D6, PIN_D7,
        },
        .bus_width = 8,
        .max_transfer_bytes = 16,
        .dma_burst_size = 16,
    };
    ESP_RETURN_ON_ERROR(esp_lcd_new_i80_bus(&bus_config, &s_i80_bus),
                        TAG, "create i80 bus failed");

    ESP_RETURN_ON_ERROR(create_panel_io(0, &s_i80_setup_io),
                        TAG, "create setup panel IO failed");
    ESP_RETURN_ON_ERROR(create_panel_io(1, &s_i80_data_io),
                        TAG, "create data panel IO failed");
    return ESP_OK;
}

static esp_err_t reg_setup_hw(uint8_t opcode, uint16_t addr)
{
    const uint8_t addr_bytes[2] = {
        (uint8_t)(addr & 0xFFu),
        (uint8_t)((addr >> 8) & 0xFFu),
    };
    ESP_RETURN_ON_FALSE(s_i80_setup_io != NULL, ESP_ERR_INVALID_STATE,
                        TAG, "setup i80 not initialized");
    return esp_lcd_panel_io_tx_param(s_i80_setup_io, opcode,
                                     addr_bytes, sizeof(addr_bytes));
}

static esp_err_t reg_write_hw(uint16_t addr, uint16_t value)
{
    const uint8_t payload[2] = {
        (uint8_t)(value & 0xFFu),
        (uint8_t)((value >> 8) & 0xFFu),
    };
    ESP_RETURN_ON_FALSE(s_i80_data_io != NULL, ESP_ERR_INVALID_STATE,
                        TAG, "data i80 not initialized");
    gpio_set_level(PIN_CS, 0);
    esp_err_t ret = reg_setup_hw(0x00, addr);
    if (ret == ESP_OK) {
        ret = esp_lcd_panel_io_tx_param(s_i80_data_io, -1,
                                        payload, sizeof(payload));
    }
    gpio_set_level(PIN_CS, 1);
    return ret;
}

static esp_err_t reg_read_manual(uint16_t addr, uint16_t *out)
{
    ESP_RETURN_ON_FALSE(out != NULL, ESP_ERR_INVALID_ARG, TAG, "null read output");

    ESP_RETURN_ON_FALSE(s_i80_setup_io != NULL, ESP_ERR_INVALID_STATE,
                        TAG, "setup i80 not initialized");
    gpio_set_level(PIN_CS, 0);
    ESP_RETURN_ON_ERROR(reg_setup_hw(0x01, addr), TAG, "read setup write failed");

    ESP_RETURN_ON_ERROR(i80_destroy(), TAG, "destroy i80 before manual read failed");
    configure_manual_read_pins_preserve_cs();
    set_data_inputs();

    esp_rom_delay_us(5);
    const uint8_t lo = manual_rd_byte();
    const uint8_t hi = manual_rd_byte();
    gpio_set_level(PIN_CS, 1);
    gpio_set_level(PIN_DC, 0);
    *out = (uint16_t)lo | ((uint16_t)hi << 8);

    ESP_RETURN_ON_ERROR(i80_create(), TAG, "restore i80 after manual read failed");
    return ESP_OK;
}

static uint16_t stress_pattern(uint16_t index)
{
    static const uint16_t seeds[] = {
        0x1234u, 0xA55Au, 0x5AA5u, 0x00FFu, 0xFF00u, 0x0001u, 0x8000u,
    };
    if (index < (sizeof(seeds) / sizeof(seeds[0]))) {
        return seeds[index];
    }
    uint16_t x = (uint16_t)(0xACE1u ^ index);
    x ^= (uint16_t)(x << 7);
    x ^= (uint16_t)(x >> 9);
    x ^= (uint16_t)(x << 8);
    return x;
}

static const char *classify_failure(uint16_t expected, uint16_t got)
{
    if (got == expected) {
        return "pass";
    }
    if ((uint16_t)(((expected >> 8) & 0xFFu) | (expected << 8)) == got) {
        return "byte_swapped";
    }
    const uint16_t diff = (uint16_t)(expected ^ got);
    if (diff != 0u && (diff & (uint16_t)(diff - 1u)) == 0u) {
        return "single_bit_flip";
    }
    if (got == 0x0000u || got == 0xFFFFu) {
        return "stuck_constant";
    }
    return "other";
}

static bool run_single_transaction(void)
{
    const uint16_t expect = 0xA55Au;
    uint16_t got = 0;
    esp_err_t err = reg_write_hw(TEST_REG, expect);
    if (err == ESP_OK) {
        esp_rom_delay_us(5);
        err = reg_read_manual(TEST_REG, &got);
    }
    const bool pass = (err == ESP_OK) && (got == expect);
    printf("P4_I80_SINGLE result=%s reg=0x%04X expect=0x%04X got=0x%04X err=%s class=%s\n",
           pass ? "PASS" : "FAIL", TEST_REG, expect, got, esp_err_to_name(err),
           classify_failure(expect, got));
    return pass;
}

static void run_bounded_burst(void)
{
    uint16_t pass_count = 0;
    uint16_t fail_count = 0;
    bool have_first_fail = false;
    uint16_t first_i = 0;
    uint16_t first_expect = 0;
    uint16_t first_got = 0;
    esp_err_t first_err = ESP_OK;

    for (uint16_t i = 0; i < BURST_ROUNDS; ++i) {
        const uint16_t expect = stress_pattern(i);
        uint16_t got = 0;
        esp_err_t err = reg_write_hw(TEST_REG, expect);
        if (err == ESP_OK) {
            esp_rom_delay_us(5);
            err = reg_read_manual(TEST_REG, &got);
        }
        if (err == ESP_OK && got == expect) {
            ++pass_count;
        } else {
            ++fail_count;
            if (!have_first_fail) {
                have_first_fail = true;
                first_i = i;
                first_expect = expect;
                first_got = got;
                first_err = err;
            }
        }
    }

    printf("P4_I80_BURST rounds=%u pclk=%" PRIu32 " pass=%u fail=%u",
           BURST_ROUNDS, I80_PCLK_HZ, pass_count, fail_count);
    if (have_first_fail) {
        printf(" first_i=%u first_expect=0x%04X first_got=0x%04X first_err=%s class=%s",
               first_i, first_expect, first_got, esp_err_to_name(first_err),
               classify_failure(first_expect, first_got));
    } else {
        printf(" first_i=NONE first_expect=NONE first_got=NONE first_err=ESP_OK class=none");
    }
    printf("\n");
}

void app_main(void)
{
    printf("\nESP32-P4 i80 basic readback discriminator\n");
    printf("pins D={32,33,22,23,46,47,48,29} DC=20 CS=31 WR=21 RD=30 pclk=%" PRIu32 "\n",
           I80_PCLK_HZ);

    configure_manual_ctrl_pins();
    ESP_ERROR_CHECK(i80_create());
    vTaskDelay(pdMS_TO_TICKS(200));

    if (run_single_transaction()) {
        run_bounded_burst();
    } else {
        printf("P4_I80_BURST skipped=single_transaction_failed\n");
    }

    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
