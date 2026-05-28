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

## Host Platform Fidelity

### FIDELITY-1: Authoritative vs Functional Host

- **Authoritative:** Pico 2 (RP2350). Native PIO QSPI @ 2 MHz. Deterministic timing. **Required for audit sign-off.**
- **Functional:** ESP32, ESP8266. Bit-bang QSPI @ ~500 kHz. Acceptable for functional regression only.

### FIDELITY-2: QSPI_ERROR == 0 Trust Requirement

Visual output is only valid if `QSPI_ERROR` (sticky bit 3) remains clear.
**Procedure:** Poll `last_error` (sel=4) after bursts. Clear error bit if set. Retrust only when `last_error == 0` throughout setup.

### FIDELITY-3: Pico 2 / RP2350 Authority Notes

- **PIO Determinism:** Exactly 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.
- **No Pin Hazard:** PIO `set_pindirs` eliminates the ESP output-enable hazard.
- **Toolchain:** Pico SDK 2.2.0, `-DPICO_PLATFORM=rp2350-arm-s`.

### FIDELITY-4: Artifact Stewardship

**Match Rule:** Every bench test must prove artifact freshness.
1. Verify FPGA bitstream and firmware commits match intended source.
2. Rebuild/reflash if match cannot be proven.
3. Record commit hashes and `last_error` status in every proof packet.
4. **Tang Nano 20K:** Rebuild bitstream from Scala source before every proof session.

### FIDELITY-5: Copper Timing Latency

- **Fact:** Copper script FIFO introduces ~1-line vertical lag.
- **Rule:** Use `y-1` compensation for single-shot effects.
- **Exception:** Do not apply `y-1` to looping programs spanning the active area.
- **Requirement:** State program shape (single-shot/looping) and timing accuracy in reports.

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

---

### GOTCHA-7: ESP8266 GPIO 16 (D0) has no internal pull-up

**Fact:** IO3 on the ESP8266 NodeMCU maps to GPIO 16 (D0), which has no
internal pull-up. If the VDP ever tri-states IO3, the ESP8266 side may float.

**Current status:** The VDP QSPI implementation always drives IO3 during
transactions, so this is not a live issue. Documented for future reference if
the VDP side ever adds high-Z states.

---

### GOTCHA-8: Barebones 40-bit QSPI protocol (Stage 2+)

**Deviation:** The "barebones" rebuild branch (`mode0t20-barebones-rebuild`) uses a simplified 1-bit SPI protocol instead of the full 6-byte header QSPI contract.

**Protocol:**
- **Width:** 1-bit (SCK, CS_N, MOSI only; no MISO/IO2/IO3)
- **Frame:** 40 bits = `[CMD:8] [ADDR:16] [DATA:16]`
- **Command:** Only `0x01` (REG_WRITE) is supported.
- **Timing:** Same 2 MHz SCK and 10 µs CS hold invariants as the main contract.

**Why it exists:** To provide a truly-minimal bring-up path on Tang Nano 20K that fits in low LUT counts and doesn't require the full SDRAM/QSPI infrastructure.

**Fix status:** Documented. Host sketches `esp8266_barebones_scroll`, `esp32_barebones_scroll`, and `test_barebones_scroll` implement this protocol. Main `libvdp` DOES NOT support this protocol; it remains locked to the 6-byte header QSPI contract.

---

### GOTCHA-9: API Naming and Register Map Conflict (Mode0 vs Barebones)

**Fact:** The `TopTang20kBarebones` build uses a custom register map (`0x0000..0x0005`) that conflicts with the standard `VDP_MODE0_REG_LINESTATE_BASE` (also `0x0000`).

**Implication:**
- `vdp_mode0_*` helpers must NOT be used with barebones builds.
- Use `vdp_barebones_*` for any future helpers targeting the barebones registers.
- The `libvdp` documentation in `kb/libvdp/README.md` is the source of truth for these classifications.

**Transition:** As features migrate from barebones to rich-top, the barebones-specific wrappers will be replaced by standard Mode0 equivalents. No renaming of existing code is authorized until the documentation update is complete and reviewed.

---

### GOTCHA-10: Disabled-layer Backdrop Bank Fallthrough

**Fact:** When all layers and sprites are disabled (`LAYER_ENABLE = 0`), the VDP compositor falls through to a default color. However, it still uses the current **Layer 0 Palette Bank** for this lookup.

**Implication:** If you disable all layers to see a "pure" `palette[0]` backdrop, you may see **Black** or another color if Layer 0's bank is currently non-zero. At POR, Layer 0's bank is often **Bank 4** (Grayscale/Black) due to uninitialized SDRAM Attribute memory.

**Fix:** Either pre-initialize the SDRAM Attribute Map to Bank 0, or write your intended backdrop color to the first index of all 8 palette banks (`0, 16, 32, 48, 64, 80, 96, 112`).

### GOTCHA-11: ESP32-S3 QSPI SI Ceiling at 80 MHz

**Fact:** The ESP32-S3 hardware SPI2 peripheral supports up to 80 MHz when using the dedicated FSPI IOMUX pin group (GPIO 9..14).

**Implication:** At 80 MHz, signal integrity on breadboards or long unshielded wires is poor. Reflections can cause bit-flips in bulk register writes, leading to corrupted palette or SDRAM data.

**Fix:** Use **60 MHz** (`VDP_QSPI_SCK_WRITE_HZ`) as the production bulk-write speed. It provides nearly the same throughput (~6.8 MB/s) with significantly more SI margin. Interleaving data lines with multiple Ground wires on the ribbon cable is also recommended.

### GOTCHA-12: Scaler Register Ordering and Safe-Boundary Commit

**Fact:** The integer pixel-repetition scaler (lane #10590) uses safe-boundary commit logic. Register writes to `SCALE_CTRL`, `LOGIC_WIDTH`, and `LOGIC_HEIGHT` are staged in pending registers and committed only when `hCounter === 0`.

**Implication:**
1. **Set `LOGIC_WIDTH` and `LOGIC_HEIGHT` before enabling scale mode.** If you write `SCALE_CTRL` first with a non-1x scale factor while `LOGIC_WIDTH`/`LOGIC_HEIGHT` are still at POR defaults (640×480), the scaler may compute out-of-bounds line-buffer addresses. This was the root cause of the OOB-write bug caught by BronzeGate (#10697). The RTL now guards against this, but the ordering rule remains: size first, then scale mode.

2. **Register writes take effect at the next frame boundary**, not immediately. Do not expect visible changes mid-frame.

3. **Hardware silently clamps `scale × logicSize` to the active display dimensions.** A 4× scale of 320×240 on a 640×480 display will not crash; it will be clamped to the visible area. The auto-center bezel math computes offsets based on the clamped visible region.

**Fix:** Always set size before scale:
```c
vdp_mode0_set_logic_size(320, 240);   // size first
vdp_mode0_set_scale_ctrl(
    vdp_mode0_scale_ctrl(2, 2, true)  // then scale mode
);
```

---

### GOTCHA-13: Scaler Bezel Test as Canonical Hardware Discriminator

**Fact:** The scaler hardware proof uses a "bezel test" pattern: white bezel (palette[1]) + black scaled center (palette[64 default backdrop]). This is the unambiguous visual discriminator that the scaler is in the data path and auto-center math is working.

**Implication:** If the capture shows all-white or all-black, the scaler is either disconnected (see `7ff34f0` anomaly) or the border/backdrop configuration is wrong. The bezel test must show **structured** white-on-black (or chosen color-on-color) with measurable bezel width.

**Fix:** For hardware proof, always use the bezel test with `autoCenter=1`, `borderEnable=1`, and a high-contrast palette choice. Predict bezel width from `((hActive - scaleX*logicWidth) / 2)` and verify against capture mean.

