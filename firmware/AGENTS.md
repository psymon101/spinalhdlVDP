# AGENTS.md — spinalhdlVDP/firmware

Local rules for the `firmware/` subtree.

**For TopazCliff:** This file governs your work inside `firmware/`. It
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

Use the same repo-root mail project for all firmware work. TopazCliff must send
lane packets, replies, acknowledgements, and coordination mail through
`/home/itadmin/github/spinalhdlVDP`, not through a `firmware/` mailbox, a
subdirectory mailbox, or any external workspace mailbox.

TopazCliff is the canonical firmware identity for this repository by PM
override. Sign firmware messages as "TopazCliff" so the team knows who owns
the lane.

TopazCliff does not edit FPGA / HDL / `hw/spinal/` sources from this subtree
unless BronzeGate explicitly reassigns that lane in mail or task docs. If a
firmware task would require crossing into FPGA code, stop and hand it to
BrightForge instead of bridging the gap yourself.

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

The 6-byte header QSPI framing is **proven and locked**. TopazCliff does not modify it.

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

Mode0 rule:
- When a new Mode0 register block or control path is added in FPGA, add the
  matching `vdp_mode0_*` helper(s) and update `kb/libvdp/README.md` in the
  same change unless BronzeGate explicitly approves a raw-only exception.
- Do not leave new Mode0 functionality reachable only through ad hoc
  `vdp_reg_write(...)` calls in sketches.

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

## Coordination

- Use the repo-root MCP mail project key `/home/itadmin/github/spinalhdlVDP`
  for firmware packets, replies, acknowledgements, and closeout.
- Before asking another agent a question, search MCP memory / workspace memory
  first for an existing answer. If you learn a durable firmware fact, write it
  back to MCP memory with a short summary and commit/mail tie-back.
- Before starting a firmware lane, read `PROJECT_PLAN/TASKS.md`, confirm the
  register contract with BrightForge, and confirm lane authorization with
  BronzeGate.
- Keep firmware work as a background lane and keep mail replies factual:
  verified result, action taken, proof status, next step.

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

## Audit / Doc-Sync Checklist

When closing a lane, submitting a pull request, or performing an audit:

- [ ] **libvdp sync:** If this lane touched libvdp API surface (`.h`), semantics (`.c`), RTL registers, or programming patterns, verify that `kb/libvdp/README.md` is current.

---

## Preventive Rules (firmware-specific)

### QSPI Contract Deviation Documentation

The canonical QSPI contract is locked at **2 MHz SCK, 10 µs CS hold, 20 µs OSR drain**.
Any host-side deviation above 25% must be documented in `firmware/GOTCHAS.md`
before the sketch or library change is considered complete.

Current documented deviation:
- **ESP32 / ESP8266 bit-bang:** ~500 kHz SCK (4× slower than 2 MHz contract)
- **Status:** Tolerated — CS hold and OSR drain timing are maintained in
  absolute microseconds, not clock cycles. Verified by bench test for Sc45,
  Sc62, and Task 55 scenarios.
- **Risk:** Very long transactions (>1 ms total burst) may interact poorly with
  VDP scanline deadlines. Keep individual QSPI bursts under 256 bytes unless
  explicitly validated.

### Signoff Consistency

TopazCliff signs all firmware mail as `— TopazCliff`. Do not use mixed
signatures.

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
