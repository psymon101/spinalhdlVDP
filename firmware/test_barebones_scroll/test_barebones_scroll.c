/**
 * PM #10051 barebones stage 4 — Pico 2-layer scroll proof.
 * 
 * Hardware: Tang Nano 20K running branch mode0t20-barebones-rebuild.
 * Pico Pins (Authoritative Host):
 *   SCK:  GP8
 *   CS_N: GP9
 *   MOSI: GP10
 * 
 * Protocol: 40-bit frame [CMD:8][ADDR:16][DATA:16], MSB first.
 * REG_WRITE command is 0x01.
 */

#include <stdio.h>
#include <math.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"

const uint PIN_SCK  = 8;
const uint PIN_CS   = 9;
const uint PIN_MOSI = 10;

// Mode0-T20 Barebones Register Map (Stage 4)
const uint16_t REG_SCROLL_X0 = 0x0000;
const uint16_t REG_SCROLL_Y0 = 0x0001;
const uint16_t REG_SCROLL_X1 = 0x0002;
const uint16_t REG_SCROLL_Y1 = 0x0003;
const uint16_t REG_SPRITE_X  = 0x0004;
const uint16_t REG_SPRITE_Y  = 0x0005;

void bb_send_byte(uint8_t b) {
    for (int i = 7; i >= 0; i--) {
        gpio_put(PIN_MOSI, (b >> i) & 1);
        sleep_us(5);
        gpio_put(PIN_SCK, 1);
        sleep_us(10);
        gpio_put(PIN_SCK, 0);
        sleep_us(5);
    }
}

void vdp_reg_write(uint16_t addr, uint16_t data) {
    gpio_put(PIN_CS, 0);
    sleep_us(10);
    
    bb_send_byte(0x01);           // CMD = REG_WRITE
    bb_send_byte(addr >> 8);      // ADDR H
    bb_send_byte(addr & 0xFF);    // ADDR L
    bb_send_byte(data >> 8);      // DATA H
    bb_send_byte(data & 0xFF);    // DATA L
    
    sleep_us(10);
    gpio_put(PIN_CS, 1);          // Commit on rising edge
}

int main() {
    stdio_init_all();
    
    gpio_init(PIN_SCK);
    gpio_set_dir(PIN_SCK, GPIO_OUT);
    gpio_put(PIN_SCK, 0);
    
    gpio_init(PIN_CS);
    gpio_set_dir(PIN_CS, GPIO_OUT);
    gpio_put(PIN_CS, 1);
    
    gpio_init(PIN_MOSI);
    gpio_set_dir(PIN_MOSI, GPIO_OUT);
    gpio_put(PIN_MOSI, 0);

    printf("VDP Mode0-T20 Barebones 2-LAYER Scroll Starting...\n");
    sleep_ms(200);

    float phase0 = 0.0f;
    float phase1 = 0.0f;
    float phaseS = 0.0f;

    while (true) {
        // Layer 0: clockwise circle
        uint16_t x0 = (uint16_t)(320.0f + 160.0f * cosf(phase0));
        uint16_t y0 = (uint16_t)(240.0f + 120.0f * sinf(phase0));
        vdp_reg_write(REG_SCROLL_X0, x0);
        vdp_reg_write(REG_SCROLL_Y0, y0);

        // Layer 1: counter-clockwise circle, different speed
        uint16_t x1 = (uint16_t)(320.0f + 120.0f * cosf(-phase1));
        uint16_t y1 = (uint16_t)(240.0f + 80.0f * sinf(-phase1));
        vdp_reg_write(REG_SCROLL_X1, x1);
        vdp_reg_write(REG_SCROLL_Y1, y1);

        // Sprite: figure-8 path
        uint16_t xs = (uint16_t)(320.0f + 200.0f * sinf(phaseS));
        uint16_t ys = (uint16_t)(240.0f + 100.0f * sinf(2.0f * phaseS));
        vdp_reg_write(REG_SPRITE_X, xs);
        vdp_reg_write(REG_SPRITE_Y, ys);

        phase0 += 0.05f;
        phase1 += 0.08f;
        phaseS += 0.03f;
        
        if (phase0 >= 6.2831853f) phase0 -= 6.2831853f;
        if (phase1 >= 6.2831853f) phase1 -= 6.2831853f;
        if (phaseS >= 6.2831853f) phaseS -= 6.2831853f;

        // Roughly 60 FPS
        sleep_ms(16);
    }
}
