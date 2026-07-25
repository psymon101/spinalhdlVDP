/**
 * PM #10080 barebones simple-sprite slice — ESP8266 sprite host proof.
 *
 * Scenario: Barebones Checkpoint C — one visible sprite moving over a
 * scrolling two-layer background.
 *
 * Hardware: Tang Nano 20K running branch mode0t20-barebones-rebuild with
 * the PM #10080 sprite extension (regs 0x0004 SPRITE_X, 0x0005 SPRITE_Y).
 *
 * Pin map (ESP8266 NodeMCU 1.0):
 *   SCK:  GPIO 14 (D5)
 *   MOSI: GPIO 13 (D7)
 *   CS:   GPIO 12 (D6)
 *   LED:  GPIO 2  (on-board)
 *
 * Boot sequence:
 *   1. GPIO init, CS high, SCK low.
 *   2. 1 s settle delay.
 *   3. Upload loop drives L0/L1 scroll + sprite X/Y.
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

#include <math.h>

const int PIN_SCK  = 14; // D5
const int PIN_MOSI = 13; // D7
const int PIN_CS   = 12; // D6
const int PIN_LED  = 2;

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

  pinMode(PIN_CS, OUTPUT);
  digitalWrite(PIN_CS, HIGH);
  pinMode(PIN_SCK, OUTPUT);
  digitalWrite(PIN_SCK, LOW);
  pinMode(PIN_MOSI, OUTPUT);
  digitalWrite(PIN_MOSI, LOW);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
}

void bb_send_byte(uint8_t b) {
  for (int i = 7; i >= 0; i--) {
    digitalWrite(PIN_MOSI, (b >> i) & 1);
    delayMicroseconds(5);
    digitalWrite(PIN_SCK, HIGH);
    delayMicroseconds(10);
    digitalWrite(PIN_SCK, LOW);
    delayMicroseconds(5);
  }
}

void vdp_reg_write(uint16_t addr, uint16_t data) {
  digitalWrite(PIN_CS, LOW);
  delayMicroseconds(10);

  bb_send_byte(0x01);           // CMD = REG_WRITE
  bb_send_byte(addr >> 8);      // ADDR H
  bb_send_byte(addr & 0xFF);    // ADDR L
  bb_send_byte(data >> 8);      // DATA H
  bb_send_byte(data & 0xFF);    // DATA L

  delayMicroseconds(10);
  digitalWrite(PIN_CS, HIGH);   // Commit on rising edge
}

float bgPhase0  = 0.0f;
float bgPhase1  = 0.0f;
float sprPhase  = 0.0f;

void loop() {
  digitalWrite(PIN_LED, LOW);

  // Layer 0: clockwise circle (same as scroll proof)
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

  digitalWrite(PIN_LED, HIGH);

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

  delay(16);
}
