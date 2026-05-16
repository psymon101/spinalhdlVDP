/**
 * PM #10051 barebones stage 4 — ESP8266 2-layer scroll proof.
 * 
 * Hardware: Tang Nano 20K running branch mode0t20-barebones-rebuild.
 * ESP8266 Pins:
 *   SCK:  GPIO 14 (D5)
 *   MOSI: GPIO 13 (D7)
 *   CS:   GPIO 12 (D6)
 * 
 * Protocol: 40-bit frame [CMD:8][ADDR:16][DATA:16], MSB first.
 * REG_WRITE command is 0x01.
 */

#include <math.h>

const int PIN_SCK  = 14; // D5
const int PIN_MOSI = 13; // D7
const int PIN_CS   = 12; // D6
const int PIN_LED  = 2;

// Mode0-T20 Barebones Register Map (Stage 4)
const uint16_t REG_SCROLL_X0 = 0x0000;
const uint16_t REG_SCROLL_Y0 = 0x0001;
const uint16_t REG_SCROLL_X1 = 0x0002;
const uint16_t REG_SCROLL_Y1 = 0x0003;

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("VDP Mode0-T20 Barebones 2-LAYER Scroll Starting...");

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

float phase0 = 0.0f;
float phase1 = 0.0f;

void loop() {
  digitalWrite(PIN_LED, LOW);
  
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

  digitalWrite(PIN_LED, HIGH);

  phase0 += 0.05f;
  phase1 += 0.08f;
  
  if (phase0 >= 6.2831853f) phase0 -= 6.2831853f;
  if (phase1 >= 6.2831853f) phase1 -= 6.2831853f;

  if (((int)(phase0 * 10.0f)) % 20 == 0) {
    Serial.printf("Dual Scroll: L0=(%d,%d) L1=(%d,%d)\n", x0, y0, x1, y1);
  }

  delay(16); 
}
