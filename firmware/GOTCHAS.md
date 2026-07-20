# firmware/GOTCHAS.md

Firmware-specific pitfalls, proven fixes, and contract deviations for the
`spinalhdlVDP` host driver library.

## legacy SPI Transport

### GOTCHA-1: ESP32 / ESP8266 SCK speed is ~500 kHz (bit-bang)

**Deviation:** The locked legacy SPI contract specifies 2 MHz SCK. The Arduino
bit-bang implementation for ESP32 and ESP8266 achieves only ~500 kHz due to
digitalWrite overhead.

**Why it is tolerated:**
- The VDP-side state machine tracks absolute microseconds for CS hold (10 µs)
  and OSR drain (20 µs), not SCK edge counts.
- Bench testing with Sc45, Sc62, and Task 55 scenarios on both ESP32 and
  ESP8266 produced correct HDMI output.
- The VDP legacy SPI receiver is a shift-register with no minimum frequency spec
  other than "fast enough to complete before the next VDP operation."

**Risk:**
- Very long bursts (>1 ms total legacy SPI active time) may span multiple scanlines
  and interact with VDP scanline deadlines.
- Mitigation: keep individual legacy SPI bursts under 256 bytes unless explicitly
  validated on hardware.

**Fix status:** Documented. No code change required.

---

### GOTCHA-2: Pico PIO legacy SPI runs at 2 MHz (native)

**Fact:** The Pico RP2350 uses a PIO state machine for legacy SPI, achieving the full
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
- ESP32/ESP8266: `vdp_legacySpi.c` uses `delayMicroseconds(10)` after the final bit.

---

## Host Platform Fidelity

### FIDELITY-1: Authoritative vs Functional Host

- **Authoritative:** ESP32-S3 (i80 parallel). Native GPIO fast toggling. Deterministic timing. **Required for audit sign-off.**
- **Functional / Legacy:** Pico 2 (RP2350) PIO legacy SPI, ESP32, ESP8266. Bit-bang legacy SPI @ ~500 kHz. Acceptable for functional regression only; not authoritative for timing-sensitive proofs.

### FIDELITY-2: Upload Error Trust Requirement

Visual output is only valid if the upload bridge sticky error bits remain clear.
**Procedure:** Poll `vdp_last_error()` after bursts. On legacy SPI builds, clear error bits with `vdp_clear_upload_status()` if set and retrust only when `vdp_last_error() == 0` throughout setup (this corresponds to `LEGACY_SPI_ERROR` / sticky bit 3 / `sel=4`).

**Current limitation:** On the canonical i80 bitstream, `vdp_clear_upload_status()` issues the documented `0x0323` write, but the current RTL does not decode that address, so the sticky bits are not actually cleared until the RTL fix lands (see FIDELITY-6 and `MODE0_REGISTER_BUS_SPEC.md` §3.1.2).

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

### FIDELITY-6: `vdp_read_status()` is not supported on the i80 backend

**Fact:** The canonical ESP32-S3 host uses the i80 parallel interface. The i80 RTL decoder (`I80HostInterface.scala`) currently accepts only opcodes `0x00` (register write), `0x01` (register read), and `0x02` (SDRAM block write). It does **not** decode the `READ_STATUS` opcode (`0x04`).

**Implication:**
- `vdp_read_status()` works correctly only on legacy SPI builds.
- On i80/ESP32-S3 builds, `vdp_read_status()` returns undefined data and does not reflect VDP state.
- Several ESP32-S3 example sketches still call `vdp_read_status()` for debug prints; those prints are meaningful only when the sketch is built for the legacy SPI backend.

**Workaround:** On i80, poll status through normal register reads:
- Sticky status → `vdp_reg_read(0x0320)` (or write-1-to-clear with `vdp_reg_write(0x0320, mask)`).
- Upload status is not yet available over i80; `vdp_clear_upload_status()` issues the documented `0x0323` write, but the current bitstream does not decode that address (see `MODE0_REGISTER_BUS_SPEC.md` §3.1.2).

**Fix status:** Documented. RTL implementation tracked under `FULL-DOC-AUDIT-151` / escalated to BrightForge.

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
author=SignalWire
maintainer=SignalWire
sentence=Shared host driver library for VDP Mode0.
paragraph=Encapsulates host transport, register writes, and SDRAM uploads.
category=Display
url=https://github.com/spinalhdlVDP
architectures=*
```

Without this file, the Arduino IDE and `arduino-cli` will not recognize
`libvdp` as a valid library and sketches will fail to compile.

---

## Platform-Specific

### GOTCHA-6: ESP32 GPIO 25/27 are safe for legacy SPI IO2/IO3

**Fact:** On the ESP32 dev1 board used in this project, GPIO 25 and 27 are
not strap pins and do not conflict with JTAG or flash access.

**Verification:** Checked against Espressif GPIO matrix and dev1 schematic.

---

### GOTCHA-7: ESP8266 GPIO 16 (D0) has no internal pull-up

**Fact:** IO3 on the ESP8266 NodeMCU maps to GPIO 16 (D0), which has no
internal pull-up. If the VDP ever tri-states IO3, the ESP8266 side may float.

**Current status:** The VDP legacy SPI implementation always drives IO3 during
transactions, so this is not a live issue. Documented for future reference if
the VDP side ever adds high-Z states.

---

### GOTCHA-8: Barebones 40-bit legacy SPI protocol (Stage 2+)

**Deviation:** The "barebones" rebuild branch (`mode0t20-barebones-rebuild`) uses a simplified 1-bit SPI protocol instead of the full 6-byte header legacy SPI contract.

**Protocol:**
- **Width:** 1-bit (SCK, CS_N, MOSI only; no MISO/IO2/IO3)
- **Frame:** 40 bits = `[CMD:8] [ADDR:16] [DATA:16]`
- **Command:** Only `0x01` (REG_WRITE) is supported.
- **Timing:** Same 2 MHz SCK and 10 µs CS hold invariants as the main contract.

**Why it exists:** To provide a truly-minimal bring-up path on Tang Nano 20K that fits in low LUT counts and doesn't require the full SDRAM/legacy SPI infrastructure.

**Fix status:** Documented. Host sketches `esp8266_barebones_scroll`, `esp32_barebones_scroll`, and `test_barebones_scroll` implement this protocol. Main `libvdp` DOES NOT support this protocol; it remains locked to the 6-byte header legacy SPI contract.

---

### GOTCHA-9: API Naming and Register Map Conflict (Mode0 vs Barebones)

**Fact:** The `TopTang20kBarebones` build uses a custom register map (`0x0000..0x0005`) that conflicts with the standard `VDP_MODE0_REG_LINESTATE_BASE` (also `0x0000`).

**Implication:**
- `vdp_mode0_*` helpers must NOT be used with barebones builds.
- Use `vdp_barebones_*` for any future helpers targeting the barebones registers.
- The `libvdp` documentation in `kb/libvdp/README.md` is the source of truth for these classifications.

**Transition:** As features migrate from barebones to rich-top, the barebones-specific wrappers will be replaced by standard Mode0 equivalents. No renaming of existing code is authorized until the documentation update is complete and reviewed.

---

### GOTCHA-10: Disabled-Layer Backdrop Index

**Fact:** When all layers and sprites are disabled (`LAYER_ENABLE = 0`), the VDP compositor falls through to the **backdrop color** indexed by `BACKDROP_INDEX` (`0x0348`). This is a 7-bit absolute palette index and is independent of any layer's palette bank.

**Implication:** At power-on, `BACKDROP_INDEX` defaults to `0`, so the backdrop is `palette[0]`. If you disable all layers and see an unexpected color, it is because `BACKDROP_INDEX` points to an entry you did not expect, not because of Layer 0's bank.

**Fix:** Write your intended backdrop palette entry (0..127) to `BACKDROP_INDEX` (`0x0348`), then write the RGB color to that palette entry. Do not rely on Layer 0's palette bank for the disabled-layer backdrop.

### GOTCHA-11: ESP32-S3 legacy SPI SI Ceiling at 80 MHz

**Fact:** The ESP32-S3 hardware SPI2 peripheral supports up to 80 MHz when using the dedicated FSPI IOMUX pin group (GPIO 9..14).

**Implication:** At 80 MHz, signal integrity on breadboards or long unshielded wires is poor. Reflections can cause bit-flips in bulk register writes, leading to corrupted palette or SDRAM data.

**Fix:** Use **60 MHz** (`VDP_SPI_SCK_WRITE_HZ`) as the production bulk-write speed. It provides nearly the same throughput (~6.8 MB/s) with significantly more SI margin. Interleaving data lines with multiple Ground wires on the ribbon cable is also recommended.

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

---

### GOTCHA-030: Tang Nano 20K (GW2AR-18) Embedded SDRAM Pins are SiP

**Fact:** The GW2AR-18 used on the Tang Nano 20K features a System-in-Package (SiP) 64 Mbit SDRAM. These connections are die-to-die internal to the chip.

**Implication:**
- The SDRAM Address bus (A[10:0]), Bank Address (BA[1:0]), and Data bus (DQ[31:0]) are NOT routed to external FPGA pins.
- You cannot probe these signals with an oscilloscope or logic analyzer.
- Signal integrity is fixed by the package substrate; no external termination or board-level tuning is possible.

---

### GOTCHA-031: Embedded SDRAM Address Margin Ceiling (64.8 MHz retired)

**Fact:** Initial bring-up attempts at the retired 64.8 MHz SDRAM clock (Phase 1A) showed non-deterministic Row Address Aliasing (e.g., Row 0x28 overwriting Row 0x2C). The current stable baseline is **40.5 MHz**.

**Why it occurred:** The physical capture window at 180° phase (7.7 ns) is marginal for the combined SiP substrate skew and chip-internal latch requirements. Timing artifacts in the EDA tool hid this marginality until hardware verification.

**Fix:** Lower the SDRAM clock to **40.5 MHz**. This widens the capture window to **12.35 ns**, providing ~60% more setup/hold margin. This is the mandatory stable baseline for Tang Nano 20K Phase 1A. All live documentation and new designs must use 40.5 MHz; 64.8 MHz references are historical/retired.

**Simulation Note:** At 40.5 MHz (RefreshPeriodCycles=593), the `PlanarRefreshStallSim` canary may trip deterministically due to the tighter refresh cadence shifting `memtestPassR` into phase with the artificial testbench stimulus. This is a **benign sim-stimulus artifact** and does not indicate a hardware bug. Do not relax the RTL assert contract.

---

### GOTCHA-032: Multi-bit Clock Domain Crossing (CDC) Value Corruption

**Fact:** Using `BufferCC` (a simple 2-stage synchronizer) on multi-bit vectors (e.g., 23-bit addresses, 10-bit line indices) is a critical integrity failure.

**Why it occurs:** Independent bit-skew during the domain crossing allows the capture domain to sample the vector while only some bits have transitioned. This results in "torn" or "mangled" values that never existed in the source domain.

**Impact:**
- Random jumps in SDRAM fetch addresses (leading to "ghost" tiles).
- Corruption of grant IDs in the arbiter (leading to bus-ownership confusion).
- Mangled status readbacks (e.g., `READ_STATUS` returning invalid dwords).

**Fix:** Replace `BufferCC` for vectors with proper coherent crossings: `PulseSync` + Latch-stable data, or Gray-code encoding for counters.

---

### GOTCHA-033: legacy SPI Physical SCK Ceiling (25.2 MHz Oversampling)

**Fact:** The `QspiSlave.scala` oversamples the asynchronous SCK pin using the 25.2 MHz pixel clock.

**Logic Ceiling:** Per Nyquist-Shannon, the SCK frequency MUST be less than 12.6 MHz (half the oversampling rate). In practice, with routing jitter and setup/hold requirements, the stable ceiling is ~8 MHz.

**Implication:**
- The 60 MHz write speed recommended in earlier firmware versions is **physically invalid**.
- At 60 MHz, the FPGA sees aliased/random transitions, causing protocol collapse and non-deterministic register/SDRAM write failures.

**Fix:** Cap the legacy SPI write clock to **8 MHz** max. Adjust `VDP_SPI_SCK_WRITE_HZ` in `vdp_platform.h` and the ESP32 probe firmware.

---

### GOTCHA-034: Persistent FPGA flash does not prove active SRAM configuration

**Fact:** On the Tang Nano 20K bench, `openFPGALoader --write-flash --verify`
successfully erased, programmed, and verified the persistent bitstream, but its
completion left FPGA SRAM unconfigured. The ESP32-P4 QSPI proof then read
`0xFFFFFFFF` for the magic/status values. A separate
`openFPGALoader --board tangnano20k --bitstream project.fs` SRAM load restored
the active design; the same firmware immediately produced magic `0x51560002`,
health `0x00000000`, and the display-pass marker (`HAM6_PROOF_DONE` at the time;
now the 2bpp indexed reference-mode marker).

**Implication:** A flash hash/verify result is not sufficient for a live host
proof. Load SRAM explicitly for the current session, or power-cycle and verify
the device's configure-from-flash path before interpreting all-ones QSPI reads
as a transport or pin failure.

**Related pin distinction:** The Tang CST numbers (`CS=85`, `SCK=77`,
`IO0..3=25..28`) are FPGA package pins, not ESP32-P4 GPIO numbers. The P4
adapter uses `SCLK=21`, `CS=20`, `IO0=32`, `IO1=33`, `IO2=22`, `IO3=23`.
