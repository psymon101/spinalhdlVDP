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
- `firmware/libvdp/` host driver library (C/C++): i80/QSPI transports, Mode0 helpers, register map
- `firmware/esp32s3_i80_*/` canonical ESP32-S3 i80 example sketches (smoke, RGB565 full-frame, scaler bezel, sprite mask, copper bars)
- `firmware/esp32s3_rgb565_fullframe/` canonical RGB565 full-frame example (ESP32-S3)
- `kb/` local hardware and Gowin documentation
- `scripts/assets/` host-side asset conversion helpers for PNG → VDP data
- `scripts/gen_reg_docs.py` register-spec generator from `firmware/libvdp/mode0_regs.json`
- `project/` SBT project metadata

The Scala package for this repository is `spinalhdlvdp`.

## Toolchain

- **Scala:** Java 11+, `sbt`
- **FPGA:** Gowin IDE CLI `gw_sh`, `openFPGALoader`
- **Firmware:** `arduino-cli` (ESP), CMake & Pico SDK 2.2.0 (Pico 2)
- **Assets:** Python 3.8+ (PNG → VDP)

## Host Interface

The current Tang Nano 20K deployment uses an **8-bit parallel i80 bus** driven by an **ESP32-S3** as the canonical host path. `firmware/libvdp/vdp_i80.h` exposes host-neutral register and SDRAM upload calls over this interface.

- **i80 protocol:** opcode `0x00` register write, `0x01` register read, `0x02` SDRAM block write; CS#/WR#/RD#/DC control.
- **Readback semantics:** most register reads return the last-written value (loopback). Special debug readback is available via `READ_STATUS` selectors.
- **Legacy QSPI:** the 4-wire QSPI path is still present for Raspberry Pi Pico 2 and earlier ESP32/ESP8266 bench setups, but it is **retired from the canonical ESP32-S3 path**. See `PROJECT_PLAN/PLATFORM.md` for pinouts and `PROJECT_PLAN/archive/QSPI_HOST_CONTROL_PLAN.md` for historical QSPI details.

## Mode0 Architecture

`Mode0` is a foundational rendering substrate providing generic primitives: raster timing, fetch, composition, palette, sprites, scrolling, Copper, and HDMA.

**Principles:**
1. **Generic Core:** The VDP RTL is a purely generic graphics IP. It grows universal capabilities needed by multiple platforms but contains zero platform-specific logic.
2. **Firmware Personality:** Platform-specific personality (register shims, initialization sequences, asset management) resides entirely in `libvdp` or host-side firmware.
3. **Quirk Isolation:** Platform-specific quirks are handled by the host library translating to generic Mode0 register writes.

Roadmap: [PROJECT_PLAN/archive/planning/MODE0_PLANNING.md](PROJECT_PLAN/archive/planning/MODE0_PLANNING.md).
User guide: [VDP_PROGRAMMING_GUIDE.md](VDP_PROGRAMMING_GUIDE.md).
