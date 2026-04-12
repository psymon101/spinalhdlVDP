# spinalhdlVDP Education Series

A comprehensive guide to understanding the Video Display Processor (VDP) implementation in SpinalHDL.

## Document Index

| # | Document | Description | Status |
|---|----------|-------------|--------|
| 01 | [Tasks 1-7 Walkthrough](./01_TASKS_1-7_WALKTHROUGH.md) | Foundation: output bring-up, tiles, scrolling | Complete |
| 02 | [Tasks 8-10 Walkthrough](./07_TASKS_8-10_WALKTHROUGH.md) | Advanced: wraparound, line buffers, palette | Complete |
| 03 | [SpinalHDL Primer](./02_SPINALHDL_PRIMER.md) | Language constructs and hardware mapping | TODO |
| 04 | [Video Timing Deep Dive](./03_VIDEO_TIMING.md) | VGA/HDMI timing standards and implementation | TODO |
| 05 | [Tile-Based Graphics](./04_TILE_GRAPHICS.md) | Understanding tile maps, patterns, and scrolling | TODO |
| 06 | [FPGA Architecture](./05_FPGA_ARCHITECTURE.md) | How code maps to LUTs, FFs, BRAM, and routing | TODO |
| 07 | [Deep Dive: Hardware Internals](./06_DEEP_DIVE_HARDWARE.md) | Behind the scenes - RTL, gates, timing, power | Complete |

## Quick Reference

### Repository Structure
```
spinalhdlVDP/
├── hw/spinal/spinalhdlvdp/    # SpinalHDL source code
├── hw/gen/                     # Generated Verilog output
├── fpga/tang20k/              # Board-specific constraints/build
├── PROJECT_PLAN/              # Task definitions and planning
└── EDUCATION/                 # This educational series
```

### Task Status Summary

| Task | Description | Status | Owner |
|------|-------------|--------|-------|
| 1-5 | Output bring-up (timing, HDMI) | ✅ DONE | BrightForge |
| 6 | Basic Pattern Source (tiles) | ✅ DONE | BrightForge |
| 7 | Scroll Path | ✅ APPROVED | BrightForge |
| 8 | Wraparound / Seam | 🆕 ASSIGNED | BrightForge |
| 9 | Line Buffer | 🔧 CORRECTIONS | BrightForge |
| 10 | Palette Path | 🆕 ASSIGNED | BrightForge |

### Key Concepts Glossary
- **SpinalHDL**: Hardware description language embedded in Scala
- **TMDS**: Transition Minimized Differential Signaling (HDMI encoding)
- **Tile**: Reusable 16x16 pixel pattern
- **Tile Map**: Screen layout referencing tiles by index
- **Raster**: The scanning beam/position on a display
- **PLL**: Phase-Locked Loop (clock generation)
- **BRAM**: Block RAM (on-chip memory in FPGA)
- **Line Buffer**: Double-buffered scanline storage
- **Palette**: Programmable color lookup table

---

**Last Updated**: 2026-04-11  
**Maintainer**: CoralReef (Teach-back lane)
