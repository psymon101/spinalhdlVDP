# Task 42 — Firmware + Platform Docs Hardening

**Status:** DONE — Firmware platform docs completed
**depends_on:** [27]
**scope_boundary:** Documentation and small firmware helpers only. No HDL changes. No new rendering primitives. No protocol changes.
**delivers:**

- Six-wire Pico↔Tang jumper map captured in `PLATFORM.md` and `firmware/README.md`
- Reusable `vdp_pio_wait_sm_idle()` exposed in `vdp_qspi.h` (or documented pattern if kept static)
- Documented QSPI firmware gotchas and workarounds
- PIO timing constraints and verified SCK rates documented
- `PLATFORM.md` updated to reflect current validated state (QSPI active, SDRAM active, 6-wire full-quad)

**validation:**

- Doc review: `PLATFORM.md` and `firmware/README.md` are complete and accurate for current hardware setup
- Firmware: `vdp_pio_wait_sm_idle()` compiles and passes smoke-test integration

---

## 1. Goal

The platform documentation and firmware library have drifted from the current validated hardware state. Task 42 closes the gap by:
1. Updating `PLATFORM.md` to reflect the post-Task 38a 6-wire full-quad QSPI and post-Task 15/34 SDRAM reality.
2. Updating `firmware/README.md` with the corrected pin map and build instructions.
3. Hardening the `libvdp` API surface so the proven `vdp_pio_wait_sm_idle()` helper is reusable.
4. Capturing firmware gotchas that have cost debug time (PIO state restore, literal-cache bug, CS hold timing).

## 2. Scope

### 2.1 In scope

1. **PLATFORM.md refresh** — update sections that claim QSPI/SDRAM are "not yet validated"
2. **firmware/README.md refresh** — 6-wire pin map, `libvdp` build instructions, test-app tree
3. **libvdp API hardening** — expose or document `vdp_pio_wait_sm_idle()`
4. **Gotchas doc** — new `firmware/GOTCHAS.md` or inline comments capturing proven pitfalls
5. **PIO timing table** — verified SCK rates, OSR drain margins, bit-bang turnaround timing

### 2.2 Out of scope (deferred)

- New PIO programs (existing `qspi_quad.pio` is proven baseline)
- New transport protocols (QSPI framing is frozen per Task 38c)
- New host-driver features (upload/status already in `libvdp` from Task 39)
- CMake / build-system refactor beyond adding `libvdp` to existing test apps

## 3. Current State Audit

### 3.1 PLATFORM.md gaps

| Section | Current claim | Actual validated state | Fix needed |
|---|---|---|---|
| SDRAM | "not actively used yet" | Task 15 embeds SiP SDRAM; Task 34 validates SDRAM_WRITE upload; `SdramTileFetch` actively drives L0 pixels | Update status + add pin table |
| QSPI | "not actively used yet" | Task 26/27/38a–c validate full 4-wire quad QSPI; Task 36 stress-proven | Update status + add 6-wire pin table |
| Pin Assignments | Missing QSPI, SDRAM, capture device | Tang pins 41/42/48/49/51/54 for QSPI; magic SDRAM pads | Add complete pin table |

### 3.2 firmware/README.md gaps

| Item | Current | Actual | Fix needed |
|---|---|---|---|
| Pin map | 4-wire (IO2/IO3 "unconnected") | 6-wire full-quad (Task 38a IOBUF) | Update table |
| Test apps | Only `test_qspi_smoke` listed | Also `test_qspi_wire`, `test_qspi_wire_read`, `test_qspi_smoke` with `libvdp` | Expand tree |
| Build instructions | Manual cmake for smoke test | `libvdp` CMake integration pattern | Add libvdp build example |

### 3.3 libvdp gaps

| Item | Current | Needed |
|---|---|---|
| `vdp_pio_wait_sm_idle()` | `static` in `vdp_qspi.c` (internal) | Expose in `vdp_qspi.h` or document as required pattern for custom PIO TX |
| `VDP_QSPI_SCK_HZ` | 2 MHz in `vdp_platform.h` | Document why 2 MHz is the proven ceiling; note headroom for future increase |

## 4. Work Plan

### 4.1 PLATFORM.md update

**SDRAM section rewrite:**
```markdown
## SDRAM

The SiP SDRAM is actively used for L0 tile and attribute fetch.

| Property | Value |
|---|---|
| Type | embedded SDR SDRAM (SiP) |
| Capacity | 64 Mbit (8 MB) |
| Bus width | 32-bit |
| Validation status | **Task 15** validated controller integration; **Task 34** validated host-driven SDRAM_WRITE upload path; **Task 36** validated stability under concurrent bus load |
```

**QSPI section rewrite:**
```markdown
## QSPI

Full 4-wire quad-mode QSPI host-control lane is validated.

| Property | Value |
|---|---|
| Mode | quad-output (TX) + bit-bang turnaround (RX) |
| SCK | 2 MHz (proven), 5 MHz (unverified ceiling) |
| Validation status | **Task 26/27** validated register write; **Task 38a** validated bidirectional IOBUF; **Task 38c** validated READ_STATUS bit-bang response; **Task 36** validated concurrent-load stability |
```

**Pin assignments addition:**
```markdown
| Signal | Tang pin | Pico GPIO | Notes |
|---|---|---|---|
| `I_qspi_sck` | 41 | GP8 | 2 MHz, LVCMOS33 |
| `I_qspi_cs`  | 42 | GP9 | active low, pull-up |
| `IO_qspi_io0`| 48 | GP10 | bidirectional (Task 38a IOBUF) |
| `IO_qspi_io1`| 49 | GP11 | bidirectional |
| `IO_qspi_io2`| 51 | GP12 | bidirectional |
| `IO_qspi_io3`| 54 | GP13 | bidirectional |
```

### 4.2 firmware/README.md update

- Replace 4-wire pin table with 6-wire full-quad table
- Add `libvdp` build example:
  ```sh
  cd firmware/test_qspi_smoke
  mkdir -p build && cd build
  cmake .. -DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2
  make -j$(nproc)
  ```
- Expand tree section to list all test apps and their purposes

### 4.3 libvdp hardening

**Option A: Expose the helper (recommended)**
Add to `vdp_qspi.h`:
```c
/** Wait for PIO TX FIFO empty + OSR drain + 20 µs margin.
 *  Required after any PIO burst before CS deassertion or pin function switch.
 *  Proven margin from Task 38c; do not reduce without re-validation.
 */
void vdp_pio_wait_sm_idle(void);
```
Remove `static` from definition in `vdp_qspi.c`.

**Option B: Keep static, document pattern**
If the helper is intentionally internal, add a code-comment block explaining the pattern so custom PIO apps can replicate it correctly.

### 4.4 GOTCHAS.md (new file)

Capture the following proven pitfalls:

1. **PIO pin function restore after bit-bang read**
   - `vdp_read_status()` switches pins to SIO for bit-bang, then must restore PIO function + pindirs before the next TX.
   - Missing the `pio_sm_set_consecutive_pindirs()` restore causes the next TX to drive nothing.

2. **SpinalHDL literal-cache bug**
   - Compiling `VdpTop` twice in one JVM triggers null Bits in `AffineAssets.textureInit`.
   - Mitigation: single-seed per sim invocation; restart sbt for a second seed.

3. **CS hold time**
   - `vdp_reg_write()` sleeps 10 µs after CS deassertion.
   - Removing this sleep caused intermittent SPI framing errors in early Task 26 debug.

4. **PIO OSR drain margin**
   - `vdp_pio_wait_sm_idle()` waits for TX FIFO empty then sleeps 20 µs.
   - At 2 MHz SCK with 10 clocks per nibble, the final nibble needs ~5 µs; 20 µs is 4× margin.

## 5. Validation Plan

### 5.1 Doc review

- `PLATFORM.md` accurately describes the current 6-wire QSPI, active SDRAM, and validated SCK rate.
- `firmware/README.md` pin map matches `vdp_platform.h` and `tang20k_hdmi.cst`.
- No section claims a feature is "not yet validated" when it has passed hardware proof.

### 5.2 Firmware compile test

- `vdp_pio_wait_sm_idle()` exposed in header compiles cleanly:
  ```sh
  cd firmware/test_qspi_smoke/build
  make clean && make -j$(nproc)
  ```
- Zero warnings at `-Wall`.

### 5.3 Smoke-test integration

- `test_qspi_smoke` still toggles `LAYER_ENABLE` correctly after the API change.
- 30-second capture confirms no regression in QSPI transport stability.

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** doc + firmware updates landed, compile test PASS
- **C:** smoke-test integration PASS on hardware

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `PLATFORM.md` +20 lines; `firmware/README.md` +15 lines; `vdp_qspi.h` +5 lines; `vdp_qspi.c` −1 line (remove static); new `firmware/GOTCHAS.md` ~40 lines |
| **Hardware target** | Tang Nano 20K + Pico 2 (RP2350) |
| **Dependencies** | Task 27 (QSPI hardening), Task 38a (bidirectional IOBUF), Task 39 (libvdp) |

## 8. Open Questions (for implementation to resolve)

1. **`vdp_pio_wait_sm_idle()` exposure:** Should this be a public API (Option A) or documented pattern (Option B)? Recommend Option A since custom test apps already need the same pattern.
2. **`GOTCHAS.md` location:** Standalone file under `firmware/` or inline comments only? Recommend standalone for discoverability, with inline references.
3. **SCK ceiling documentation:** Should we document the unverified 5 MHz theoretical ceiling, or stick to the proven 2 MHz only? Recommend documenting 2 MHz as proven, 5 MHz as "unverified theoretical ceiling — requires re-validation of PIO OSR drain + SDRAM CDC margin."
