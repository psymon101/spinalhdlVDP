# spinalhdlVDP

Fresh SpinalHDL-based Tang Nano 20K HDMI VDP development repository.

Project identity: `spinalhdlVDP`. Any CyanPeak bitstream work referenced in mail or notes is a build lane for this repository, including the Tang Nano 20K + ESP8266 MCU host bitstream effort.

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
1. **Generic Core:** `Mode0` grows universal capabilities needed by multiple platforms.
2. **Semantic Adapters:** Platform-specific modes (C64, NES, Amiga, etc.) sit on top as adapters.
3. **Quirk Isolation:** Platform-specific registers and logic belong in adapters, not the core substrate.

Roadmap: [`PROJECT_PLAN/MODE0_PLANNING.md`](PROJECT_PLAN/MODE0_PLANNING.md).
Detailed adapter specs: [`PROJECT_PLAN/PLATFORM_ADAPTERS.md`](PROJECT_PLAN/PLATFORM_ADAPTERS.md).
User guide: [`VDP_PROGRAMMING_GUIDE.md`](VDP_PROGRAMMING_GUIDE.md).
