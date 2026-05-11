# firmware/GOTCHAS.md

Firmware-specific pitfalls, proven fixes, and contract deviations for the
`spinalhdlVDP` host driver library.

## QSPI Transport

### GOTCHA-1: ESP32 / ESP8266 SCK speed is ~500 kHz (bit-bang)

**Deviation:** The locked QSPI contract specifies 2 MHz SCK. The Arduino
bit-bang implementation for ESP32 and ESP8266 achieves only ~500 kHz due to
digitalWrite overhead.

**Why it is tolerated:**
- The VDP-side state machine tracks absolute microseconds for CS hold (10 µs)
  and OSR drain (20 µs), not SCK edge counts.
- Bench testing with Sc45, Sc62, and Task 55 scenarios on both ESP32 and
  ESP8266 produced correct HDMI output.
- The VDP QSPI receiver is a shift-register with no minimum frequency spec
  other than "fast enough to complete before the next VDP operation."

**Risk:**
- Very long bursts (>1 ms total QSPI active time) may span multiple scanlines
  and interact with VDP scanline deadlines.
- Mitigation: keep individual QSPI bursts under 256 bytes unless explicitly
  validated on hardware.

**Fix status:** Documented. No code change required.

---

### GOTCHA-2: Pico PIO QSPI runs at 2 MHz (native)

**Fact:** The Pico RP2350 uses a PIO state machine for QSPI, achieving the full
2 MHz contract speed. This is the reference implementation.

**Implication:** When validating timing-sensitive scenarios, use the Pico as the
authoritative host. ESP32/ESP8266 bit-bang results are valid for functional
correctness but not for timing margin characterization.

---

### GOTCHA-3: CS hold time must be absolute, not cycle-counted

**Rule:** Always maintain CS_N low for at least 10 µs after the final SCK edge.
Do not compute this as "N clock cycles" because host SCK rates vary by platform.

**Implementation:**
- Pico: PIO program uses `delay` sideset for microsecond-level hold.
- ESP32/ESP8266: `vdp_qspi.c` uses `delayMicroseconds(10)` after the final bit.

---

## Build / Flash

### GOTCHA-4: Arduino CLI board identifiers

| Platform | Correct FQBN |
|----------|-------------|
| ESP8266 | `esp8266:esp8266:nodemcuv2` |
| ESP32 | `esp32:esp32:esp32` |

Using `arduino:avr:uno` or other generic boards will fail because the pin
mappings and SPI peripheral headers are platform-specific.

---

### GOTCHA-5: `library.properties` is required for Arduino IDE recognition

`firmware/libvdp/library.properties` must exist with at least:

```
name=libvdp
version=1.0.0
author=spinalhdlVDP team
maintainer=spinalhdlVDP team
sentence=VDP host driver library
paragraph=Cross-platform QSPI host driver for spinalhdlVDP
category=Device Control
url=
architectures=esp8266,esp32,rp2040
```

Without this file, the Arduino IDE and `arduino-cli` will not recognize
`libvdp` as a valid library and sketches will fail to compile.

---

## Platform-Specific

### GOTCHA-6: ESP32 GPIO 25/27 are safe for QSPI IO2/IO3

**Fact:** On the ESP32 dev1 board used in this project, GPIO 25 and 27 are
not strap pins and do not conflict with JTAG or flash access.

**Verification:** Checked against Espressif GPIO matrix and dev1 schematic.

**Caution:** Other ESP32 boards may map these differently. Always verify
against the specific board schematic before changing pin assignments.

---

### GOTCHA-7: ESP8266 GPIO 16 (D0) has no internal pull-up

**Fact:** IO3 on the ESP8266 NodeMCU maps to GPIO 16 (D0), which has no
internal pull-up. If the VDP ever tri-states IO3, the ESP8266 side may float.

**Current status:** The VDP QSPI implementation always drives IO3 during
transactions, so this is not a live issue. Documented for future reference if
the VDP side ever adds high-Z states.
