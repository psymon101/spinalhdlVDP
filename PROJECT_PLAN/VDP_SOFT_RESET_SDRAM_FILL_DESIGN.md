# VDP Soft-Reset #3 — SDRAM Occupied-Region Zero-Fill (revised for CyanPeak review)

Lane **VDP-SOFT-RESET-135**, Stage 3. Revised per TopazCliff #12565 (occupied-only
scope, auto-derive from geometry registers) + #12576 (cover tile/attr/planar, not
just bitmap). Supersedes the original whole-chip design.

## Goal
Zero only the **occupied/configured SDRAM regions** a layer reads for display — so
no *displayed* artifacts survive a reset — instead of all 8 MB. Stages 1+2 (handshake
+ on-chip Mem clear) are done + sim-proven; Stage 3 part 1 (controller fill-stage +
`sdramFillStart`/`sdramFillDone` ports) is committed (`a3e0893`).

## Active-layer SDRAM sources to clear (verified against RTL)
The fill FSM walks this fixed candidate list; each range is cleared **only if its
active-gate is set** (read from the live config, which is still valid in Stage 3 —
it runs before the Stage 4 register reset).

| Source | Base | Size (bytes) | Host-programmable? | Active gate |
|--------|------|--------------|--------------------|-------------|
| Bitmap plane (direct-color low) | `bitmapBase` = `0x0352`HI`##0x0351`LO (init 0x3000) | `bitmapStride`(0x0355) × `bitmapHeight`(0x0357) | **yes** | `BITMAP_CTRL[0]` (0x0350) = bitmapModeActive |
| Attr / high plane | `attrBase` = `0x0354`HI`##0x0353`LO (init 0x4000) | `attrStride`(0x0356) × `bitmapHeight`(0x0357) | **yes** | `BITMAP_CTRL[0]` |
| Tile map L0 | `TileAttributeAssets.TileMapBase` = `0x6000` (**fixed const**) | `MapEntries` = 1200 | no | `layer0UseSdram` |
| Tile rows L0 | `TileRowBase` = `0x8000` (**fixed const**) | `TileCount·TileHeight·TileRowStride` = 512 | no | `layer0UseSdram` |
| Tile map L1 | `L1TileMapBase` = `0xC000` (**fixed const**) | 1200 | no | `layer1UseSdram` |
| Tile rows L1 | `L1TileRowBase` = `0xE000` (**fixed const**) | 512 | no | `layer1UseSdram` |
| Planar planes 0-4 | `planeBaseAddr(p)` (host reg, exposed by VdpTop in 2c) | `PLANE_PIXELS/8` = **40** /plane (BitplaneRowFetch reads `base+readIdx·4`, `readsPerPlane=320/32`; no line offset) | **yes** | `planarFetchEnable` |
| **Sprite patterns** | — | — | — | **EXCLUDED** — verified **no SDRAM-backed sprite-pattern path** exists; patterns live only in on-chip `spritePatternRams`, already zeroed in Stage 2. |

Also excluded (unchanged): `affineTexture` (immutable ROM, no write port), per-line
render line-buffers (transient), and any source whose gate is clear.

> **Correction (part 2c):** the *active* tile fetch is `SdramTileAttributeFetch`,
> whose SDRAM bases are `TileAttributeAssets` consts **0x6000 / 0x8000** (L0) and
> **0xC000 / 0xE000** (L1) — not the 0x4000/0x5000 of the older `SdramTileFetch`.
> All 11 regions are wired in TopTang (`sdramFillArea`); the FSM skips any whose
> gate is clear. `layer1UseSdram` is currently driven False, so the L1 tile
> regions are inert until L1 is enabled.

## Fill algorithm (geometry-derived, no multiplier)
For each active source, clear `[base, base + stride·height)` using **row-accumulator
addressing** (CyanPeak's no-runtime-multiplier rule, as in the blitter): outer loop
over `height` rows, inner loop writes `stride` zero bytes, `rowBase += stride` each
row. Fixed-size sources (tile map/rows) clear `[base, base+constLen)` directly.
Sources are swept **sequentially**; bitmap-low + attr-high are simply two entries in
the list (handles the "non-contiguous planes" case naturally). All writes are
`din=0`, gated on controller `!busy`, in `sdramClockDomain`.

## CDC + mux (unchanged, TopazCliff Q3/Q4 approved)
- `sdramFillStart`/`sdramFillDone` levels crossed via `BufferCC` (2-FF) between the
  pixel-domain controller and the sdram-domain fill FSM. FSM waits for stable start,
  holds done until start deasserts.
- During fill, a mux routes the fill FSM's `wr/addr/din` to the SDRAM controller,
  bypassing the arbiter (no client granted; display quiescent). Arbiter must re-enter
  cleanly on exit (cosim assertion).

## Refresh decision — RETAINED (ruled mandatory: CyanPeak #12589 / TopazCliff #12587)
The Stage 3 fill FSM **retains a lightweight interleaved auto-refresh** (~1 per 15 µs,
one counter threaded through the fill loop). Rationale: dropping refresh was only safe
if the clear finished under the 64 ms retention window, which holds for small configs
but **not all**:
- 320×240 RGB565 dual-plane ≈ 246 KB → **~18 ms** ✓
- **640×480 RGB565 dual-plane ≈ 1.2 MB → ~60–90 ms** (write-throughput dependent) — **at/over 64 ms** ✗

Without refresh, early-zeroed cells decay back to garbage **before the sweep finishes**
— the exact artifact this reset must eliminate. Interleaved refresh makes the clear
**bit-perfect and size-independent** (also covers future 720p scaling). The cosim
asserts refresh commands issue at cadence.

## Cosim PASS criteria
SDRAM-model cosim: preset occupied cells (+ one untouched region) non-zero → trigger
reset → assert occupied cells read **0**, untouched region unchanged, `softResetBusy`
clears within the expected window, refresh commands issue at cadence (if kept),
arbiter re-enters with no deadlock. `SoftResetHandshakeSim` + bitmap/SDRAM regression
sims must still pass. Then i80 top elaboration + STA clean.
