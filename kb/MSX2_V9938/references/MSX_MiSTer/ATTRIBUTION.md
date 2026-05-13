# MSX_MiSTer — Attribution

**Source:** https://github.com/MiSTer-devel/MSX_MiSTer  
**Author:** MiSTer team  
**License:** GNU GPL v2 (MiSTer project standard)

**Files included:**
- `vdp.vhd` — Top-level VDP
- `vdp_graphic4567.vhd` — Graphic modes 4/5/6/7
- `vdp_graphic123m.vhd` — Graphic modes 1/2/3/multicolor
- `vdp_text12.vhd` — Text modes 1/2
- `vdp_sprite.vhd` — Sprite engine
- `vdp_linebuf.vhd` — Line buffer
- `vdp_doublebuf.vhd` — Double buffering
- `vdp_hvcounter.vhd` — H/V counter
- `vdp_colordec.vhd` — Color decoder
- `vdp_register.vhd` — Register file
- `vdp_interrupt.vhd` — Interrupt logic
- `vdp_ssg.vhd` — Screen sync generator
- `vdp_vga.vhd` — VGA output
- `vdp_ntsc_pal.vhd` — NTSC/PAL timing
- `vdp_wait_control.vhd` — Wait state control
- `vdp_command.vhd` — VDP command engine
- `vdp_spinforam.vhd` — Sprite info RAM
- `vdp_package.vhd` — VDP package/types

**Description:** MiSTer FPGA core for MSX/MSX2/MSX2+ with V9938/V9958 VDP implementation.

**Project-local disclaimer:** These files are an external technical reference for study and comparison only. They are not the canonical Mode0 adapter contract and must not be silently incorporated into the project without independent license review.
