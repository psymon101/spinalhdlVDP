# spinalhdlVDP

Fresh SpinalHDL-based Tang Nano 20K HDMI VDP development repository.

Project identity: `spinalhdlVDP`.

## Team Roles

| Agent | Model | Role | Core Focus |
|---|---|---|---|
| `BrightForge` | Claude | FPGA RTL Engineer | Structural HDL, state machines, timing-sensitive logic, FPGA proof |
| `BronzeGate` | Codex | MCU Firmware Engineer | Bare-metal C/C++, register manipulation, transport and hardware drivers |
| `CyanPeak` | Antigravity CLI (`agy`) | Datasheet Parser & Reviewer | Large manual ingestion, code-to-spec review, hardware-accuracy checks |
| `TopazCliff` | Kimi (Inst. 2) | Technical Project Manager | Feature tickets, HW/SW interface definition, sequencing, timelines |
| `CoralReef` | Kimi | Compliance & Documentation | Static-ruleset audit, compliance checks, README/doc generation |

## Repository layout

- `hw/spinal/spinalhdlvdp/` Scala / SpinalHDL sources
- `hw/gen/` generated HDL output
- `fpga/tang20k/` Tang Nano 20K HDMI build files
- `firmware/libvdp/` host driver library (C/C++): QSPI (active) / i80 (retired) / legacy SPI transports, Mode0 helpers, register map
- `PROJECT_PLAN/archive/firmware_tests/` historical example / proof-of-concept sketches (retired from the canonical path)
- `kb/` local hardware and Gowin documentation
- `scripts/assets/` host-side asset conversion helpers for PNG → VDP data
- `scripts/gen_reg_docs.py` register-spec generator from `firmware/libvdp/mode0_regs.json`
- `project/` SBT project metadata

The Scala package for this repository is `spinalhdlvdp`.

## Toolchain

- **Scala:** Java 11+, `sbt`
- **FPGA:** Gowin IDE CLI `gw_sh`, `openFPGALoader`
- **Firmware:** `idf.py` / ESP-IDF v6.0.2 (ESP32-P4 canonical); historical sketches are archived
- **Assets:** Python 3.8+ (PNG → VDP)

## Host Interface

The current Tang Nano 20K deployment uses a **1-1-4 quad-SPI (QSPI) bus** driven by an **ESP32-P4** as the canonical host path. The active RTL front-end is `QspiSlave` → `QspiDecoder` → `QspiSdramBridge` in `hw/spinal/spinalhdlvdp/`. `firmware/libvdp/vdp_host.h` exposes host-neutral register and SDRAM upload calls over this interface.

- **QSPI protocol:** opcode `0x01` register write, `0x02` SDRAM write, `0x04` read status; CS#/SCK control with quad I/O on `spi_io[3:0]`.
- **Readback semantics:** `READ_STATUS` selectors return live transport/SDRAM status. Most other register reads return the last-written value (loopback) or are transport-dependent.
- **i80 (retired):** the 8-bit parallel i80 path driven by ESP32-S3 is **retired from the canonical path**. It remains in the tree as historical reference only.
- **Legacy SPI:** the 4-wire legacy SPI path remains supported for Raspberry Pi Pico 2 and earlier ESP32/ESP8266 bench setups, but it is **retired from the canonical ESP32-P4 path**. See `PROJECT_PLAN/PLATFORM.md` for pinouts and `PROJECT_PLAN/archive/deleted legacy host control plan` for historical legacy SPI details.

## Current Development Focus

The active lane is **QSPI word-drain transport + 2bpp indexed bitmap display** on Tang Nano 20K with an ESP32-P4 host. The previous HAM6 render mode has been **shelved** from the critical path and `bpp=0b11` is reserved for future work. See `VDP_PROGRAMMING_GUIDE.md` §12 for the 2bpp indexed reference-mode programming sequence and `PROJECT_PLAN/STATUS.md` for lane state.

## Mode0 Architecture

`Mode0` is a foundational rendering substrate providing generic primitives: raster timing, fetch, composition, palette, sprites, scrolling, Copper, and HDMA.

**Principles:**
1. **Generic Core:** The VDP RTL is a purely generic graphics IP. It grows universal capabilities needed by multiple platforms but contains zero platform-specific logic.
2. **Firmware Personality:** Platform-specific personality (register shims, initialization sequences, asset management) resides entirely in `libvdp` or host-side firmware.
3. **Quirk Isolation:** Platform-specific quirks are handled by the host library translating to generic Mode0 register writes.

Roadmap: [`PROJECT_PLAN/archive/planning/MODE0_PLANNING.md`](PROJECT_PLAN/archive/planning/MODE0_PLANNING.md).
User guide: [`VDP_PROGRAMMING_GUIDE.md`](VDP_PROGRAMMING_GUIDE.md).
