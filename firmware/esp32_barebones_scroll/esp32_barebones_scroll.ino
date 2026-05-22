#include <SPI.h>
#include <math.h>

/**
 * PM #10051 barebones stage 4 — ESP32 2-layer scroll proof.
 * 
 * Hardware: Tang Nano 20K running branch mode0t20-barebones-rebuild.
 * ESP32 Pins:
 *   SCK:  GPIO 18
 *   MOSI: GPIO 23
 *   CS:   GPIO 5
 * 
 * Protocol: 40-bit frame [CMD:8][ADDR:16][DATA:16], MSB first.
 * REG_WRITE command is 0x01.
 */

// VSPI default pins on ESP32 DevKit V1
const int PIN_SCK  = 18;
const int PIN_MOSI = 23;
const int PIN_CS   = 5;

// Mode0-T20 Barebones Register Map (Stage 4)
const uint16_t REG_SCROLL_X0 = 0x0000;
const uint16_t REG_SCROLL_Y0 = 0x0001;
const uint16_t REG_SCROLL_X1 = 0x0002;
const uint16_t REG_SCROLL_Y1 = 0x0003;
const uint16_t REG_SPRITE_X  = 0x0004;
const uint16_t REG_SPRITE_Y  = 0x0005;

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("VDP Mode0-T20 Barebones 2-LAYER Scroll Starting...");

  // Configure CS pin
  pinMode(PIN_CS, OUTPUT);
  digitalWrite(PIN_CS, HIGH);

  // Initialize SPI
  SPI.begin(PIN_SCK, -1, PIN_MOSI, PIN_CS);
  // Set frequency to 2 MHz (safe margin for pixel-clock sampling)
  SPI.beginTransaction(SPISettings(2000000, MSBFIRST, SPI_MODE0));
}

/**
 * Emits a single 40-bit frame over 1-bit SPI.
 * Frame = [CMD:8] [ADDR:16] [DATA:16]
 */
void vdp_reg_write(uint16_t addr, uint16_t data) {
  digitalWrite(PIN_CS, LOW);
  
  // SPI.transfer() sends 8 bits at a time.
  SPI.transfer(0x01);           // CMD = REG_WRITE
  SPI.transfer(addr >> 8);      // ADDR high byte
  SPI.transfer(addr & 0xFF);    // ADDR low byte
  SPI.transfer(data >> 8);      // DATA high byte
  SPI.transfer(data & 0xFF);    // DATA low byte
  
  digitalWrite(PIN_CS, HIGH);   // Commit on rising edge
}

float phase0 = 0.0f;
float phase1 = 0.0f;
float phaseS = 0.0f;

void loop() {
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

  if (((int)(phase0 * 10.0f)) % 20 == 0) {
    Serial.printf("Dual Scroll: L0=(%d,%d) L1=(%d,%d) Spr=(%d,%d)\n", x0, y0, x1, y1, xs, ys);
  }

  // ~60 FPS update rate
  delay(16); 
}
