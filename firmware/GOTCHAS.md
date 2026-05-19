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

### FIDELITY-1: Authoritative Host vs Functional Host

| Role | Platform | Why |
|------|----------|-----|
| **Authoritative** | Pico 2 (RP2350) | Native PIO QSPI at 2 MHz contract speed; deterministic timing; no bit-bang jitter |
| **Functional** | ESP32, ESP8266 | Bit-bang QSPI at ~500 kHz (GOTCHA-1); correct for functional demos but not for timing-margin or visual-proof authority |

**Rule:** Visual proofs used for audit sign-off must be captured on the **authoritative host** (Pico 2). ESP-based captures are acceptable for functional regression and developer sanity checks, but they do not carry audit authority.

**Rationale:** The ESP bit-bang path has platform-specific hazards (GPIO16/RTC pad on ESP8266, pinMode turnaround timing) that can corrupt transactions without visibly corrupting the HDMI output. A "correct-looking" frame may have been produced by a partially-corrupted register setup. Only the Pico PIO path has deterministic, validated timing that matches the locked QSPI contract.

---

### FIDELITY-2: QSPI_ERROR == 0 Trust Requirement

Before any visual output is accepted as proof, verify `QSPI_ERROR == 0` (sticky bit 3, `VDP_STICKY_QSPI_ERROR` in `vdp_status.h`).

**Procedure:**
1. After every register write or SDRAM burst, poll `vdp_read_status(4)` (`last_error`).
2. If `last_error != 0`, the transaction was corrupted. Do not trust the visual result.
3. Clear the sticky error bit with `vdp_clear_sticky(VDP_STICKY_QSPI_ERROR)` and retry.
4. Only accept visual proof when `last_error == 0` and sticky `QSPI_ERROR` remains clear through the entire setup sequence.

**Why:** A non-zero `last_error` means the FPGA received an unrecognized opcode (e.g., `0x22` from floating IO pins after an un-restored pinMode turnaround). The VDP state machine may have dropped the write, leaving registers or SDRAM in an undefined state. The HDMI output may still show a pattern (default checkerboard or stale frame), creating a false-positive proof.

**Reference:** Commit `878e862` documents the ESP8266 root cause and fix.

---

### FIDELITY-3: Pico 2 / RP2350 Authority Notes

- **PIO determinism:** The RP2350 PIO state machine emits SCK edges at exactly 2 MHz with microsecond-accurate CS hold and OSR drain timing. This matches the locked QSPI contract.
- **No pinMode turnaround hazard:** Unlike ESP8266/ESP32, the Pico PIO does not use `pinMode(INPUT)` turnaround; it uses `set_pindirs` in the PIO program, eliminating the output-enable hazard that causes `0x22` corruption.
- **RP2350 toolchain:** Use Pico SDK 2.2.0 with `-DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2`. See GOTCHA-6 (UF2 family-ID mismatch) for the silent bootrom failure mode.

---

### FIDELITY-4: Artifact Stewardship Guidance

Per the Artifact Match Rule (AGENTS.md / commit `07e00c5`), every bench test must prove artifact freshness.

**Before testing:**
1. Verify the flashed FPGA bitstream was built from the intended source commit (`git describe --always`).
2. Verify the flashed firmware sketch matches the intended source commit.
3. If either match cannot be proven, rebuild and reflash.

**After testing:**
1. Record the exact commit hash of both bitstream and firmware in the proof packet.
2. Record the `last_error` value and sticky status at the end of the test sequence.
3. Archive the proof image with a filename that includes the commit hashes (e.g., `zx_proof_fae0585_878e862_v1.png`).

**Tang Nano 20K specific:** Do not treat `fpga/tang20k/impl/pnr/project.fs` as current when it is older than `hw/gen/top_tang20k.v`. Always rebuild the bitstream from the intended Scala commit before a proof session.

---

### FIDELITY-5: Copper timing depends on program shape

**Fact:** Copper timing fixes are not universally reusable across all Copper
programs.

**Rule:**
- `y-1` compensation is appropriate for single-shot effects that must land on a
  specific target line after the FIFO drain latency.
- Looping Copper programs must be bounded to the active area and timed from the
  actual program geometry, not from a global `y-1` rule.

**Why:** The Copper script FIFO drains at most once per scanline. If a program
already spans the full active area, subtracting one from every wait target can
move the first effect into blanking and push later effects past the frame
boundary.

**Proof expectation:** For Copper demos, keep the bench report explicit about
whether the program is:
- single-shot or looping
- line-accurate or pixel-accurate
- using raw `WAIT(Y)` or `WAIT(X,Y)`

Do not claim the latency compensation is a general fix unless the exact Copper
program shape has been validated on hardware.

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
