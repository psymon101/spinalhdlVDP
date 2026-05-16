# AGENTS.md — spinalhdlVDP/firmware

Local rules for the `firmware/` subtree.

**For FoggyWolf:** This file governs your work inside `firmware/`. It
overrides any conflicting rules in the root `AGENTS.md` for operations
within this directory. You may still read the root `AGENTS.md` for general
project identity, mail registration, and cross-agent coordination context,
but firmware-specific conventions, build rules, and QSPI contract rules
live here.

Examples and command snippets: `AGENTS_EXAMPLES.md`

---

## Registration

When joining this project, register in the mail system with:

- **project_key:** `/home/itadmin/github/spinalhdlVDP`
- **program:** your client name (e.g. `codex-cli`, `claude-code`)
- **model:** your actual model name

Use the same repo-root mail project for all firmware work. FoggyWolf must send
lane packets, replies, acknowledgements, and coordination mail through
`/home/itadmin/github/spinalhdlVDP`, not through a `firmware/` mailbox, a
subdirectory mailbox, or any external workspace mailbox.

The mail system will auto-generate an adjective+noun name (e.g., `FoggyWolf`).
**Accept it.** Your mail handle is your server-assigned name; your operational
identity is FoggyWolf. Sign messages as "FoggyWolf" so the team knows who
you are.

## Toolchain

| Platform | Language | Build Tool | Flash Tool |
|----------|----------|------------|------------|
| ESP8266 | Arduino C++ | Arduino CLI (`arduino-cli`) | `esptool.py` via `arduino-cli upload` |
| ESP32 | Arduino C++ | Arduino CLI | `esptool.py` via `arduino-cli upload` |
| Pico | C11 | CMake + `pico-sdk` | `picotool load` or UF2 drag-drop |

Do not introduce new platforms without BronzeGate authorization.

---

## Directory Layout Rule

Reference layout: `AGENTS_EXAMPLES.md`

**Naming:**
- Arduino sketches: `esp<platform>_<scenario>/<name>.ino`
- Pico tests: `test_<purpose>/test_<purpose>.c` with `CMakeLists.txt`

---

## QSPI Contract — Immutable

The 6-byte header QSPI framing is **proven and locked**. FoggyWolf does not modify it.

| Field | Size | Value |
|-------|------|-------|
| CMD | 1 byte | `0x01` REG_WRITE, `0x02` SDRAM_WRITE, `0x04` READ_STATUS |
| ADDR | 3 bytes | little-endian |
| LEN | 2 bytes | little-endian word count (SDRAM_WRITE) or `0x0001` (REG_WRITE) |

**Timing invariants (validated Tasks 26–39):**
- SCK: 2 MHz proven; 5 MHz theoretical ceiling requires re-validation
- CS_N hold after frame: **≥10 µs** (`sleep_us(10)`)
- PIO OSR drain wait: **≥20 µs** after FIFO-empty before CS deassert

Any sketch that bypasses `libvdp` and hand-frames QSPI must replicate these exact timings.

---

## libvdp Integration Rule

**Reusable logic belongs in `libvdp/`.** Scenario sketches should be thin wrappers.

Do:
- Call `vdp_qspi_init()`, `vdp_reg_write()`, `vdp_sdram_write()`, `vdp_read_status()`
- Add platform-specific `#ifdef` branches in `vdp_platform.h` for new boards
- Extend `libvdp/` when a new transport primitive is needed by multiple sketches

Do not:
- Duplicate QSPI bit-bang framing inside a sketch
- Inline `digitalWrite()` loops that replace `vdp_reg_write()`
- Hardcode pin numbers in a sketch when `vdp_platform.h` already defines them

---

## Arduino Sketch Template

Every scenario sketch must contain, in order:

1. **Header comment** with:
   - Scenario ID and task reference
   - Pin map table (BronzeGate-approved mapping)
   - Boot sequence numbered list
   - Expected on-screen result
2. `#include <Arduino.h>`
3. `setup()` — GPIO init, transport settle delay (≥200 ms), upload sequence, register writes
4. `loop()` — idle or polling (no busy-wait printf spam)

Build / flash examples: `AGENTS_EXAMPLES.md`

---

## Pico C SDK Rules

- `CMakeLists.txt` must target `rp2350-arm-s` / `pico2` (not `rp2040`)
- Before debugging "firmware not running," run `picotool info` on the `.uf2` to verify family ID
- Suppress `printf` in tight stress loops (USB-CDC on Bus 002 contends with HDMI capture)
- Rebuild from clean `build/` dir when switching platforms (`rm -rf build && mkdir build && cd build && cmake ..`)

---

## Scenario Parity Checklist

When adding a new scenario sketch:

- [ ] ESP8266 sketch builds and produces expected HDMI output
- [ ] ESP32 sketch builds and produces **identical** output (or documented delta)
- [ ] Pico sketch builds and produces **identical** output (or documented delta)
- [ ] `firmware/GOTCHAS.md` updated if a new pitfall is found
- [ ] Register writes match `MODE0_REGISTER_BUS_SPEC.md`
- [ ] Pin map approved by BronzeGate (or reuses existing approved map)

A scenario is **not firmware-done** until all checked platforms are proven or the gap is explicitly task-tracked.

---

## Platform Pin Maps (Authoritative)

See `AGENTS_EXAMPLES.md`.

Do not change pin maps without FPGA-side verification that the new GPIOs are non-strap / non-JTAG / non-flash safe.

---

## Coordination Handoff

Before starting a firmware lane:

1. Read `PROJECT_PLAN/TASKS.md` Live Lane State
2. Confirm register contract / scenario definition with **BrightForge**
3. Confirm lane authorization with **BronzeGate**
4. Open firmware work as a **background lane** — it must not block the FPGA critical path

## Artifact Match Rule

Bench testing must use artifacts verified to match the intended source state.

- do not assume a flashed sketch or bitstream is current just because upload or
  programming succeeded
- before hardware proof, verify the flashed firmware matches the intended
  sketch/build
- when firmware depends on FPGA behavior, also verify the flashed FPGA
  bitstream matches the intended source/build
- if the match cannot be proven, rebuild and reflash before testing

---

## Preventive Rules (firmware-specific)

### QSPI Contract Deviation Documentation

The canonical QSPI contract is locked at: **2 MHz SCK, 10 µs CS hold, 20 µs OSR drain**.
Any host-side implementation that deviates from these values by more than 25 %
must be documented in `firmware/GOTCHAS.md` before the associated sketch or
library change is considered complete.

Current documented deviation:
- **ESP32 / ESP8266 bit-bang:** ~500 kHz SCK (4× slower than 2 MHz contract)
- **Status:** Tolerated — CS hold and OSR drain timing are maintained in
  absolute microseconds, not clock cycles. Verified by bench test for Sc45,
  Sc62, and Task 55 scenarios.
- **Risk:** Very long transactions (>1 ms total burst) may interact poorly with
  VDP scanline deadlines. Keep individual QSPI bursts under 256 bytes unless
  explicitly validated.

### Signoff Consistency

FoggyWolf signs all firmware mail as `— FoggyWolf`. Do not use retired aliases
(e.g., "SignalWire") or mixed signatures.

### AGENTS.md Immutability Rule

`AGENTS.md` files contain binding project policy. No agent may unilaterally
rewrite, truncate, remove, or materially alter rules in any `AGENTS.md` without:

- BronzeGate PM authorization, AND
- CyanPeak audit review, AND
- A diff review showing exactly what changed and why

Cosmetic edits (spelling, formatting) are allowed. Removing rules, adding
self-serving exceptions, or truncating sections to strip policy you disagree
with is **not** allowed and will be treated as a roster violation.

If you believe a rule is wrong, escalate to BronzeGate with a specific
amendment proposal. Do not edit the file directly.

## Memory Curation (firmware tags)

When storing findings to shared memory:

- Minimum tag: `firmware`
- Add: `esp8266`, `esp32`, `pico`, `qspi`, `gotcha`, `libvdp`
- Store short summaries with commit/mail tie-back, not raw build logs
