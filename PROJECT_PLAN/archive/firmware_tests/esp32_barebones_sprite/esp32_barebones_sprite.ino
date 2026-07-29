/**
 * PM #10080 barebones simple-sprite slice — ESP32 sprite host proof.
 *
 * Scenario: Barebones Checkpoint C — one visible sprite moving over a
 * scrolling two-layer background.
 *
 * Hardware: Tang Nano 20K running branch mode0t20-barebones-rebuild with
 * the PM #10080 sprite extension (regs 0x0004 SPRITE_X, 0x0005 SPRITE_Y).
 *
 * Pin map (ESP32 DevKit V1):
 *   SCK:  GPIO 18
 *   MOSI: GPIO 23
 *   CS:   GPIO 5
 *
 * Boot sequence:
 *   1. GPIO init, CS high.
 *   2. SPI.begin() at 2 MHz, MSB-first, MODE0.
 *   3. 1 s settle delay.
 *   4. Upload loop drives L0/L1 scroll + sprite X/Y.
 *
 * Expected on-screen result:
 *   Two tile layers scroll in opposite circular paths (L0 clockwise,
 *   L1 counter-clockwise). A 16x16 white sprite moves in a slower
 *   independent circle centred on screen. The sprite is always fully
 *   on-screen and clearly visible above both layers.
 *
 * Protocol: 40-bit frame [CMD:8][ADDR:16][DATA:16], MSB first.
 * REG_WRITE command is 0x01.
 */

#include <SPI.h>
#include <math.h>

// VSPI default pins on ESP32 DevKit V1
const int PIN_SCK  = 18;
const int PIN_MOSI = 23;
const int PIN_CS   = 5;

// Mode0-T20 Barebones Register Map (Stage 4 + sprite slice)
const uint16_t REG_SCROLL_X0 = 0x0000;
const uint16_t REG_SCROLL_Y0 = 0x0001;
const uint16_t REG_SCROLL_X1 = 0x0002;
const uint16_t REG_SCROLL_Y1 = 0x0003;
const uint16_t REG_SPRITE_X  = 0x0004;
const uint16_t REG_SPRITE_Y  = 0x0005;

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("VDP Mode0-T20 Barebones SPRITE proof starting...");

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
  
  SPI.transfer(0x01);           // CMD = REG_WRITE
  SPI.transfer(addr >> 8);      // ADDR high byte
  SPI.transfer(addr & 0xFF);    // ADDR low byte
  SPI.transfer(data >> 8);      // DATA high byte
  SPI.transfer(data & 0xFF);    // DATA low byte
  
  digitalWrite(PIN_CS, HIGH);   // Commit on rising edge
}

float bgPhase0 = 0.0f;
float bgPhase1 = 0.0f;
float sprPhase = 0.0f;

void loop() {
  // Layer 0: clockwise circle
  uint16_t x0 = (uint16_t)(320.0f + 160.0f * cosf(bgPhase0));
  uint16_t y0 = (uint16_t)(240.0f + 120.0f * sinf(bgPhase0));
  vdp_reg_write(REG_SCROLL_X0, x0);
  vdp_reg_write(REG_SCROLL_Y0, y0);

  // Layer 1: counter-clockwise circle, different speed
  uint16_t x1 = (uint16_t)(320.0f + 120.0f * cosf(-bgPhase1));
  uint16_t y1 = (uint16_t)(240.0f + 80.0f * sinf(-bgPhase1));
  vdp_reg_write(REG_SCROLL_X1, x1);
  vdp_reg_write(REG_SCROLL_Y1, y1);

  // Sprite: slower independent circle so motion is unambiguous
  uint16_t sx = (uint16_t)(312.0f + 120.0f * cosf(sprPhase));
  uint16_t sy = (uint16_t)(232.0f + 80.0f * sinf(sprPhase));
  vdp_reg_write(REG_SPRITE_X, sx);
  vdp_reg_write(REG_SPRITE_Y, sy);

  bgPhase0 += 0.05f;
  bgPhase1 += 0.08f;
  sprPhase += 0.04f;
  
  if (bgPhase0 >= 6.2831853f) bgPhase0 -= 6.2831853f;
  if (bgPhase1 >= 6.2831853f) bgPhase1 -= 6.2831853f;
  if (sprPhase  >= 6.2831853f) sprPhase  -= 6.2831853f;

  if (((int)(sprPhase * 10.0f)) % 20 == 0) {
    Serial.printf("Sprite: (%d, %d)  BG0: (%d, %d)  BG1: (%d, %d)\n",
                  sx, sy, x0, y0, x1, y1);
  }

  // ~60 FPS update rate
  delay(16); 
}
