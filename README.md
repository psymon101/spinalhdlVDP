# spinalhdlVDP

Fresh SpinalHDL-based Tang Nano 20K HDMI VDP development repository.

Project identity: `spinalhdlVDP`.

## Team Roles

| Agent | Model | Role | Core Focus |
|---|---|---|---|
| `BrightForge` | Claude | FPGA RTL Engineer | Structural HDL, state machines, timing-sensitive logic, FPGA proof |
| `BronzeGate` | Codex | MCU Firmware Engineer | Bare-metal C/C++, register manipulation, transport and hardware drivers |
| `CyanPeak` | Gemini | Datasheet Parser & Reviewer | Large manual ingestion, code-to-spec review, hardware-accuracy checks |
| `TopazCliff` | Kimi (Inst. 1) | Technical Project Manager | Feature tickets, HW/SW interface definition, sequencing, timelines |
| `CoralReef` | Kimi (Inst. 2) | Compliance & Documentation | Static-ruleset audit, compliance checks, README/doc generation |

## Repository layout

- `hw/spinal/spinalhdlvdp/` Scala / SpinalHDL sources
- `hw/gen/` generated HDL output
- `fpga/tang20k/` Tang Nano 20K HDMI build files
- `kb/` local hardware and Gowin documentation
- `scripts/assets/` host-side asset conversion helpers for PNG → VDP data
- `project/` SBT project metadata

The Scala package for this repository is `spinalhdlvdp`.

## Toolchain

- **Scala:** Java 11+, `sbt`
- **FPGA:** Gowin IDE CLI `gw_sh`, `openFPGALoader`
- **Firmware:** `arduino-cli` (ESP), CMake & Pico SDK 2.2.0 (Pico 2)
- **Assets:** Python 3.8+ (PNG → VDP)

## Mode0 Architecture

`Mode0` is a foundational rendering substrate providing generic primitives: raster timing, fetch, composition, palette, sprites, scrolling, Copper, and HDMA.

**Principles:**
1. **Generic Core:** The VDP RTL is a purely generic graphics IP. It grows universal capabilities needed by multiple platforms but contains zero platform-specific logic.
2. **Firmware Personality:** Platform-specific personality (register shims, initialization sequences, asset management) resides entirely in `libvdp` or host-side firmware.
3. **Quirk Isolation:** Platform-specific quirks are handled by the host library translating to generic Mode0 register writes.

Roadmap: [`PROJECT_PLAN/MODE0_PLANNING.md`](PROJECT_PLAN/MODE0_PLANNING.md).
User guide: [`VDP_PROGRAMMING_GUIDE.md`](VDP_PROGRAMMING_GUIDE.md).
