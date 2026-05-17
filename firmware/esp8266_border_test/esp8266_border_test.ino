#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>

void setup() {
  Serial.begin(115200);
  delay(1000);
  vdp_qspi_init();
  delay(200);
  
  // Upload rainbow palette
  for (uint8_t i = 0; i < 32; ++i) {
    uint16_t hue = i * 360 / 32;
    uint8_t r, g, b;
    uint8_t hi = hue / 60;
    uint8_t f = (((hue % 60) * 255) / 60);
    uint8_t p = 0, q = 255 - f, t = f;
    switch (hi % 6) {
      case 0: r=255; g=t; b=p; break;
      case 1: r=q; g=255; b=p; break;
      case 2: r=p; g=255; b=t; break;
      case 3: r=p; g=q; b=255; break;
      case 4: r=t; g=p; b=255; break;
      case 5: r=255; g=p; b=q; break;
    }
    vdp_mode0_palette_write_rgb888(i, r, g, b);
  }
  
  // Set border rect to full screen
  vdp_mode0_rect_t rect = {0, 1280, 0, 0};
  vdp_mode0_set_border_window(&rect, vdp_mode0_border_ctrl(true, 0));
  
  Serial.println("Border sweep starting...");
}

void loop() {
  static uint8_t idx = 0;
  vdp_reg_write(0x0347, vdp_mode0_border_ctrl(true, idx));
  Serial.printf("Border palette %u\n", idx);
  idx = (idx + 1) % 32;
  delay(200);
}
