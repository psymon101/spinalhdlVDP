# ESP32-P4-WIFI6 ↔ Tang Nano 20K i80 Pinout

Approved wiring for the spinalhdlVDP i80 host-bridge prototype.

## Physical layout

Place the **P4 on the LEFT** and the **Tang Nano 20K on the RIGHT**, with both USB ports at the same end. This orients the inner headers toward each other and gives the shortest jumper runs.

## Full pinout table

| Signal | P4 GPIO (name) | P4 header | Tang pin # | Tang FPGA ball | Direction | Notes |
|---|---|---|---|---|---|---|
| D0 | GPIO32 | Right R15 | 25 | IOB6A | P4 ↔ Tang | Data bus |
| D1 | GPIO33 | Right R16 | 26 | IOB6B | P4 ↔ Tang | Data bus |
| D2 | GPIO22 | Right R9 | 27 | IOB8A | P4 ↔ Tang | Data bus |
| D3 | GPIO23 | Right R10 | 28 | IOB8B | P4 ↔ Tang | Data bus |
| D4 | GPIO46 | Right R17 | 29 | IOB14A | P4 ↔ Tang | Data bus |
| D5 | GPIO47 | Right R19 | 30 | IOB14B | P4 ↔ Tang | Data bus |
| D6 | GPIO48 | Right R20 | 31 | IOB29A | P4 ↔ Tang | Data bus |
| D7 | GPIO29 | Left L6 | 41 | IOB43A | P4 ↔ Tang | Data bus |
| DC | GPIO20 | Right R6 | 85 | IOT4B | P4 → Tang | Data / Command |
| CS# | GPIO31 | Left L4 | 76 | IOT30B | P4 → Tang | Active-low chip select |
| WR# | GPIO21 | Right R7 | 77 | IOT30A | P4 → Tang | Active-low write strobe |
| RD# | GPIO30 | Left L5 | 80 | IOT27A | P4 → Tang | Active-low read strobe |

## Power / ground (required)

| Signal | P4 header | Tang pin # |
|---|---|---|
| 3V3 | Right R5 | 3.3 V (left side, near bottom) |
| GND | Any GND | GND (multiple) |

## ESP-IDF i80 bus config reference

```c
.data_gpio_nums = {32, 33, 22, 23, 46, 47, 48, 29},
.dc_gpio_num    = 20,
.wr_gpio_num    = 21,
```

Panel / manual controls:

```c
.cs_gpio_num = 31,   // manual or panel IO
.rd_gpio_num = 30,   // manual GPIO — not a stock esp_lcd i80 field
```

## Wiring-pair cheat sheet

Read left-to-right when connecting the two boards.

```text
P4  GPIO20 (R6)  → Tang pin 85  (IOT4B)   DC
P4  GPIO21 (R7)  → Tang pin 77  (IOT30A)  WR#
P4  GPIO22 (R9)  → Tang pin 27  (IOB8A)   D2
P4  GPIO23 (R10) → Tang pin 28  (IOB8B)   D3
P4  GPIO29 (L6)  → Tang pin 41  (IOB43A)  D7
P4  GPIO30 (L5)  → Tang pin 80  (IOT27A)  RD#
P4  GPIO31 (L4)  → Tang pin 76  (IOT30B)  CS#
P4  GPIO32 (R15) → Tang pin 25  (IOB6A)   D0
P4  GPIO33 (R16) → Tang pin 26  (IOB6B)   D1
P4  GPIO46 (R17) → Tang pin 29  (IOB14A)  D4
P4  GPIO47 (R19) → Tang pin 30  (IOB14B)  D5
P4  GPIO48 (R20) → Tang pin 31  (IOB29A)  D6
P4  3V3    (R5)  → Tang pin 3.3V           3V3
P4  GND    (any) → Tang pin GND            GND
```

## SI / wiring rules

- **Length-match D0–D7** to each other as closely as practical.
- **WR# must not be shorter/faster than the data bundle.** If the GPIO21 run is shorter, add a small service loop so WR# does not arrive before data has settled.
- Use **at least two short ground returns** between the boards, with one routed adjacent to the WR#/data bundle.

## Important notes

- **P4 "pin name":** The P4 uses a GPIO matrix, so the functional names are `GPIOxx`. For the i80 peripheral these are routed through the GPIO matrix to the LCD_CAM/I80 block.
- **RD# readback protocol:** Before asserting RD# low, set D0–D7 on the P4 to **input mode** so the FPGA can drive the bus. After sampling, release RD# high and return D0–D7 to output mode. This avoids bus contention.
- **RD# matrix-restore caveat:** Tri-stating D0–D7 via the public `gpio_set_direction()` API disconnects the LCD_CAM GPIO-matrix routing. The firmware must restore the LCD/I80 output matrix (or release/recreate the i80 bus/panel IO) before resuming hardware-i80 writes.
- **Avoided pins:** This map does not use GPIO7/8 (audio I2C), GPIO24/25 (USB Serial/JTAG), GPIO26/27 (USB-capable), or GPIO34–GPIO38 (strapping).
- **Tang pin numbers** are the physical pin numbers on the Tang Nano 20K headers.
- This matches bitstream `native640_optionC_091c7db.fs` confirmed by BrightForge.

## Related

- `kb/ESP32-P4/README.md` — host notes, pin-selection rationale, and avoided pins
- `hardware/esp32s3_tang20k_i80_bridge/pinout_diagram.md` — original S3 bridge Tang pinout (unchanged for P4)
- Mail approvals: BronzeGate #13575, BrightForge #13574
