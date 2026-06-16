# VDP Soft-Reset — On-Chip Memory & State Inventory

Lane **VDP-SOFT-RESET-135** (owner: BrightForge). Complete census of every on-chip
`Mem` and the state classes in the VDP, classified by reset disposition. This is
the authoritative scope list for the `VDP_CTRL[2]` soft reset (POR-equivalent
clean slate per TopazCliff Q1/Q2: zero all host-writable state).

> **Why this list exists:** the original contract named "copper RAM, palette,
> sprite descriptors, pattern RAM, linestate, scroll tables, affine texture." A
> full RTL census (don't-assume-the-list discipline) found that list was both
> too broad (affine texture is immutable) and **too narrow** (HDMA/blitter/DMA
> staging + sprite affine matrices are host-writable and were unlisted). Keep
> this updated as increments land.

## A. Host-writable Mems → ZEROED by the clear sweep
These have a host/copper bus write path; stale contents would survive a reset and
show as garbage. All are zeroed by the controller's shared-address clear sweep
(each Mem's existing single write port is MUXED to the sweep — never a 2nd port,
to preserve Gowin BSRAM inference).

| Mem | Module | Depth × width | Host write source | Increment |
|-----|--------|---------------|-------------------|-----------|
| `palette` (+ `paletteMirror` 32 regs) | VdpTop | 128 × 24 | `paletteCommitNow` | **#2a ✅** |
| `spritePatternRams` | VdpTop | 16384 × 4 | `patternRamDataWriteHit` (0x0D10) | **#2a ✅** |
| `scrollTable0/1` (H) | VdpTop / ScrollTable | 128 × 10 | `scrollTableRangeHit` (0x0900) | **#2b ✅** |
| `vScrollTable0/1` (V) | VdpTop / ScrollTable | 128 × 10 | `vScrollTableRangeHit` (0x0A00) | **#2b ✅** |
| `linestate.prepare` + `.commit` | LinestateStore | 512 × 12 (480 active) | `lsRangeHit` (0x0000-0x01DF) | **#2b ✅** (BH-6 collision clears both buffers) |
| `prog` (copper program, 2 banks) | Copper | 1024 × 16 | `progWr` (0x0400-0x05FF) | **#2c** |
| `hdmaDataArray` | Copper | 256 × 16 | `hdmaDataWriteHit` | **#2c** |
| `tbl` (HDMA channel table) | Copper | NUM_CH·NUM_ENT × 26 | `tblWrEn` | **#2c** |
| `staging` (DMA copy buffer) | DmaEngine | 64 × 16 | `stagingHit` (0x0B10-0x0B4F) | **#2d ✅** |
| `srcRam` (blitter source) | BlitterEngine | 512 × 16 | `srcRamHit` (bus) | **#2d ✅** |
| `infoMemW0/W1/W8` (**sprite descriptors**) | SpriteEvaluator | extCount(28) × … | `isExtBus` (0x0800-0x08FF) | **#2e ✅** |
| `matAMem/B/C/D`, `transXMem/transYMem` (sprite affine) + `regAffineEnable` DFFs | SpriteEvaluator | extCount(28) × 16 | `isExtBus` | **#2e ✅** |

## B. Non-host-writable Mems → EXCLUDED from the reset
Not reachable by any host write; clearing them is wrong (immutable assets) or
pointless (regenerated before use). **These are the "areas that aren't host
memory" — tracked here per the operator's request.**

| Mem | Module | Class | Why excluded |
|-----|--------|-------|--------------|
| `affineTexture` | VdpTop | immutable ROM | `.init()` + readAsync only — **no write port**. Always POR content; zeroing it is irrecoverable (no reload path). |
| `tileMapRom` / `tileRowRom` | SdramTileFetch | immutable ROM | init-only, no write port. |
| `tileMap` / `tileRows` | BasicPatternSource (layer0/1) | immutable ROM | init-only tile assets, no write port. |
| `lineBufferA/B` | SdramTileFetch | transient | per-line render scratch; rewritten each line before read. |
| `lineBufferA/B` | SdramTileAttributeFetch | transient | per-line render scratch. |
| `bufA/bufB` | LineBuffer | transient | double-buffered render scratch. |
| `lineBuf` | PixelRepeatScaler | transient | per-line scaler scratch. |
| `slbA/slbB` | SpriteRasterizer | transient | sprite line buffers (init 0), rewritten per line. |
| `activeListMem` | SpriteEvaluator | transient | per-line computed active-sprite list (not host state). |

## C. Registers → reset to `init` by the core ClockDomain soft-reset (#4)
The ~169 register-file commit pairs (BITMAP_CTRL, LAYER_ENABLE, per-layer scroll,
BORDER_CTRL, color-math, MODE_SELECT, AFFINE_CTRL, `copperCtrlReg`,
`patternRamPtr`, raster-trigger config, …) plus internal pipeline/FSM registers
(`hCounter`/`vCounter`/`fillLine`, copper `pc`/`activeBank`, etc.). These are
**registers, not Mems**, so they are restored to `init` by the partitioned
ClockDomain soft-reset in increment #4 — NOT by the memory sweep.

> Sprite legacy descriptors 0-3 (`io.sprite0X..sprite3PatternIdx`) are **input
> ports** driven outside VdpTop (TopTang demo logic / host), not internal state —
> nothing to reset inside VdpTop. The host-writable sprite descriptors that DO
> live in VdpTop state are SpriteEvaluator's `infoMem*` (section A, #2e).

## D. Host-write surface audit — "should be host-writable but isn't"
Before finalizing the reset scope, every excluded area was re-checked against the
register spec for *missing* host-write paths (a gap there would later become reset
scope). Result:

| Area | Current state | Host-writable today? | Verdict |
|------|---------------|----------------------|---------|
| `affineTexture` (16384×8 affine/Mode7 bg texture) | fixed `.init`, no write port | **NO** | **GAP** — `MODE0_REGISTER_BUS_SPEC` Task 34 "host asset upload" was intended to fill it but is not wired to this Mem. The affine background is a fixed demo asset. |
| sprite ext descriptors `infoMem*` + affine `matMem*` | bus-decoded at `0x0800..0x08FF` | **YES** | OK — implemented. (Spec table row "0x0800..0x0FFF Task 37 impl: —" is **STALE**; flag to CoralReef.) |
| legacy sprite 0-3 (`io.sprite0X..`) | TopTang demo io ports | NO (host uses the ext bus instead) | demo cruft, not a functional gap — the host sprite path is the ext bus (reset-scoped in #2e). |
| `BasicPatternSource` tileMap/tileRows; `SdramTileFetch` tileMapRom/tileRowRom | fixed `.init` ROMs | NO | test/demo stand-ins — the real host-loadable tile path is **SDRAM-backed** (host writes tiles to SDRAM, cleared by #3 SDRAM zero-fill). |

**Net:** the only on-chip Mem that arguably *should* be host-writable but isn't is
`affineTexture`. Reset implication: if Task 34 wires it, it's 16384×8 — it fits
the EXISTING 16384-cycle sweep length exactly, so adding it later is free (no
controller change). Until then it stays excluded (immutable, already POR).

## Open scope question for TopazCliff
Sections A rows for **HDMA (`hdmaDataArray`/`tbl`), DMA `staging`, blitter
`srcRam`, and sprite affine matrices** were NOT in the original contract list but
ARE host-writable. Recommend clearing them all for true POR-equivalence (Q2
"clean slate"). Awaiting confirmation; tracked as #2c-#2e.
