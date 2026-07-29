# ESP32-P4-WIFI6 Host Notes

Documentation and pin-planning for the Waveshare ESP32-P4-WIFI6 board used as the i80 host for spinalhdlVDP prototype validation.

## Board

- **Model:** Waveshare ESP32-P4-WIFI6
- **SoC:** ESP32-P4NRW32 (dual-core RISC-V HP @ up to 360 MHz, LP RISC-V @ 40 MHz)
- **Wireless:** ESP32-C6-MINI-1 co-processor (Wi-Fi 6 / BLE 5) via SDIO
- **Flash/PSRAM:** 32 MB NOR Flash, 32 MB PSRAM
- **Programming:** Type-C USB (UART + power)
- **Dimensions:** 71.05 mm × 21.00 mm
- **Header:** 2 × 20, 2.54 mm pitch, 27 remaining programmable GPIOs
- **Framework:** ESP-IDF (owner-directed, to use full P4 LCD/i80 peripheral functionality)

## Docs cached in this directory

| File | Source | Description |
|------|--------|-------------|
| `waveshare_docs/ESP32-P4-WIFI6-pinout.webp` | docs.waveshare.com | Board pinout diagram |
| `waveshare_docs/ESP32-P4-WIFI6-dimensions.webp` | docs.waveshare.com | Board mechanical dimensions |
| `waveshare_docs/ESP32-P4-WIFI6-schematic.pdf` | files.waveshare.com | Board schematic / mechanical PDF |
| `waveshare_docs/ESP32-P4-datasheet.pdf` | Espressif via Waveshare | ESP32-P4 chip datasheet |
| `waveshare_docs/ESP32-P4-technical_reference_manual.pdf` | Espressif via Waveshare | ESP32-P4 TRM (LCD/peripheral details) |

## i80 pin map (verified by BronzeGate + BrightForge)

The Tang Nano 20K i80 link needs 8 data lines + 4 control lines. This map is optimized for the shortest breadboard wires with the **Tang Nano 20K on the RIGHT and the P4 on the LEFT**, USB ports adjacent.

| Function | P4 GPIO | P4 physical header | ESP-IDF / LCD_CAM validity |
|---|---:|---|---|
| D0 | GPIO32 | Right header R15 | LCD data out via GPIO matrix (`data_gpio_nums[0]`) |
| D1 | GPIO33 | Right header R16 | LCD data out via GPIO matrix (`data_gpio_nums[1]`) |
| D2 | GPIO22 | Right header R9 | LCD data out via GPIO matrix (`data_gpio_nums[2]`) |
| D3 | GPIO23 | Right header R10 | LCD data out via GPIO matrix (`data_gpio_nums[3]`) |
| D4 | GPIO46 | Right header R17 | LCD data out via GPIO matrix (`data_gpio_nums[4]`) |
| D5 | GPIO47 | Right header R19 | LCD data out via GPIO matrix (`data_gpio_nums[5]`) |
| D6 | GPIO48 | Right header R20 | LCD data out via GPIO matrix (`data_gpio_nums[6]`) |
| D7 | GPIO29 | Left header L6 | LCD data out via GPIO matrix (`data_gpio_nums[7]`) |
| DC | GPIO20 | Right header R6 | LCD DC via GPIO matrix (`dc_gpio_num`) |
| CS# | GPIO31 | Left header L4 | ESP-IDF panel IO `cs_gpio_num` as GPIO |
| WR# | GPIO21 | Right header R7 | LCD WR/PCLK via GPIO matrix (`wr_gpio_num`) |
| RD# | GPIO30 | Left header L5 | GPIO-safe; driven manually (stock ESP-IDF i80 API has no `rd_gpio_num`) |

### Pins to avoid

| GPIO | Reason |
|------|--------|
| GPIO7, GPIO8 | I2C SDA/SCL for the on-board ES7210/ES8311 audio codec. Board has pull-ups; usable as GPIO only if audio is disabled and pull-ups don't conflict with i80 drive. |
| GPIO24, GPIO25 | USB Serial/JTAG DM/DP. Required for flashing/serial; keep free. |
| GPIO26, GPIO27 | USB1P1_N/P-capable pins. Avoided to remove USB-function ambiguity. |
| GPIO34–GPIO38 | Strapping pins (boot mode / JTAG source / ROM printing). **Not exposed on this board's 2×20 header.** |

### Notes

- Default IO drive strength is 20 mA; GPIO24/GPIO25 are 40 mA.
- All selected pins are ordinary output-capable GPIOs routed through the GPIO matrix.
- This map deliberately avoids GPIO7/8 (audio I2C), GPIO24/25 (USB Serial/JTAG), GPIO26/27 (USB-capable), and GPIO34–GPIO38 (strapping).
- RD# is not a stock ESP-IDF LCD_CAM/i80 signal; BronzeGate will drive it as a GPIO for the loopback readback phases.
- **RD# readback caveat:** Tri-stating D0–D7 with the public `gpio_set_direction()` API disconnects the LCD_CAM GPIO-matrix routing. The firmware must restore the LCD/I80 output matrix (or release/recreate the i80 bus) before resuming hardware-i80 writes.

## Wiring diagram

| Signal | P4 GPIO | Tang Nano 20K pin | FPGA ball | Direction |
|---|---|---|---|---|
| D0 | GPIO32 | Pin 25 | IOB6A | Bidir |
| D1 | GPIO33 | Pin 26 | IOB6B | Bidir |
| D2 | GPIO22 | Pin 27 | IOB8A | Bidir |
| D3 | GPIO23 | Pin 28 | IOB8B | Bidir |
| D4 | GPIO46 | Pin 29 | IOB14A | Bidir |
| D5 | GPIO47 | Pin 30 | IOB14B | Bidir |
| D6 | GPIO48 | Pin 31 | IOB29A | Bidir |
| D7 | GPIO29 | Pin 41 | IOB43A | Bidir |
| DC | GPIO20 | Pin 85 | IOT4B | P4 → FPGA |
| CS# | GPIO31 | Pin 76 | IOT30B | P4 → FPGA, active-low |
| WR# | GPIO21 | Pin 77 | IOT30A | P4 → FPGA |
| RD# | GPIO30 | Pin 80 | IOT27A | P4 → FPGA |
| 3.3 V | Right R5 | 3.3 V | — | Common rail |
| GND | Any GND | GND | — | Common ground, star-tied |

**Physical layout:** P4 on the left, Tang Nano 20K on the right, both USB ports at the same end. This puts the P4 right header facing the Tang left header, and the P4 left header facing the Tang right header, for the shortest jumper runs.

**SI / wiring rules (BrightForge):**
- Keep D0–D7 roughly length-matched to each other.
- Do not make WR# dramatically shorter/faster than the data bundle; if it is, add a small service loop to match.
- Use at least **two short ground returns** between the boards, with one adjacent to the WR#/data bundle.

**Tang Nano 20K reference:** `hardware/esp32s3_tang20k_i80_bridge/pinout_diagram.md`

## Related

- Active lane: `i80-link-hardening` / `i80-si-scope`
- Target FPGA artifact: `.worktrees/native-640-bitmap-148/fpga/tang20k/captures/native640_8bpp_cpd/native640_optionC_091c7db.fs`
- Mail approvals: BronzeGate #13575, BrightForge #13574
