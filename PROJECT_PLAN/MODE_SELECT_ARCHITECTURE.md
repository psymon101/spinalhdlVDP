# MODE_SELECT Architecture — Runtime Platform Adapter Selection

**Status:** **HISTORICAL / DEPRECATED** (2026-05-24)  
**Reason:** The runtime adapter-selection mux has been superseded by the **RTL Platform-Agnosticism Purge (#10567)**. Platform-specific adapters have been removed from the RTL tree; platform personality is now managed entirely in `libvdp`. This document remains for architectural archaeology only.

> **Host-path note:** References in this document to QSPI as the host path are historical. The current canonical Tang Nano 20K host interface is **i80/ESP32-S3** (see `PLATFORM.md`). QSPI remains supported as a legacy path on Pico 2 and older ESP boards.

---

## 1. Executive Summary

The current `spinalhdlVDP` build model uses **compile-time scenario selection**: `TopTang20kHdmi(scenarioId)` selects a complete bootstrap configuration at elaboration, and each scenario bitstream contains exactly one adapter (or none). The MCU cannot switch platforms without re-flashing.

This document defines **`MODE_SELECT`**: a runtime register that lets the MCU choose which platform adapter is active, so that a **single bitstream can contain multiple adapters** and the host can switch between them without FPGA re-synthesis.

**Key decision:** Use the existing `PROJECT_PLAN/` structure (BronzeGate #8680). No new `project_spec/` directory.

---

## 2. Current State Analysis

### 2.1 Compile-time scenario model

| Aspect | Current behavior |
|---|---|
| Scenario ID | `TopTang20kHdmi(scenarioId: Int)` — Scala `if`/`match` at elaboration |
| Adapter instantiation | Conditional: `if (scenarioId == 20) C64DemoAnimator()`; `if (scenarioId == 50) ZXSpectrumDemo()` |
| Bus master slot | Master 2 of `RegBusArbiter(3)` is the "animator" slot. Only the active scenario's adapter drives it. Others are compiled out. |
| Host control | QSPI `REG_WRITE` (cmd=0x01) writes to Mode0 register addresses only. There is **no path** from QSPI to adapter-local registers. |
| Direct outputs | Adapter raster-trigger and sprite IO pins are wired directly to `VdpTop.io` via compile-time `if` expressions. |

### 2.2 Adapter pattern (proven)

Both Task 40 (C64) and Task 50 (ZX Spectrum) follow the same architecture:

```
Host / Demo-FSM
       │  regAddr / regData / regWr
       ▼
   ┌─────────────┐
   │   Adapter   │  ← thin translation layer; shadow register file
   │  (outside   │    converts platform semantics → Mode0 bus writes
   │   VdpTop)   │    + direct outputs for pins not yet bus-mapped
   └──────┬──────┘
          │ busAddr / busData / busWr  ──► RegBusArbiter master 2
          │ rasterTriggerLine, spriteX/Y ──► VdpTop.io (direct)
```

This pattern is **architecturally sound** and must be preserved. `MODE_SELECT` adds runtime gating and routing; it does not change the adapter translation model.

### 2.3 Existing register-bus address space

Relevant allocations from `MODE0_REGISTER_BUS_SPEC.md`:

| Range | Current use | Notes |
|---|---|---|
| `0x0300..0x031F` | Global control (`LAYER_ENABLE`, `VDP_CTRL`, `VDP_TILE_MODE`, `VDP_ATTR_MODE`) | `0x0313..0x031F` reserved for expansion |
| `0x0E00..0x0EFF` | **C64 adapter shadow** (Task 40 artifact §3.5) | Already reserved; adapter-internal read-back |
| `0x0F00..0x0FFF` | **Unallocated** | Natural neighbor for ZX Spectrum adapter |
| `0x1000..0x7FFF` | Reserved — future Mode0 expansion | Can be carved for additional adapters |

---

## 3. Architectural Options Considered

| Option | Model | Switch | Cost | Verdict |
|--------|-------|--------|------|---------|
| A | Compile-time only | Rebuild | Zero runtime flexibility | Rejected — user requested runtime selection |
| B | Runtime shared bitstream | Warm reboot | One extra arbiter master; all adapters present | **Recommended** |
| C | Multi-adapter concurrent | Instant | Complex bus contention; breaks 1-adapter-1-platform model | Rejected for baseline |
| D | Build-time subset + runtime switch | Warm reboot | Build-parameter complexity | Fallback if Option B exceeds resources |

---

## 4. Recommended Architecture (Option B)

### 4.1 High-level model

```
All writers (QSPI, Copper, HDMA, Bootstrap)
   │ Mode0RegBus(addr, data, enable)
   ▼
┌──────────────────────────────────────────┐
│  RegBusArbiter (masters 0..2)            │
│   master 0 = bootstrap                   │
│   master 1 = QSPI                        │
│   master 2 = animator (future: adapter)  │
└──────────────────┬───────────────────────┘
                   │ mixed Mode0RegBus
                   ▼
┌──────────────────────────────────────────┐
│  VdpTop.io.regBus                        │
│   ├─ Global registers → consumed inside  │
│   │  VdpTop (safe-boundary commit)       │
│   └─ AdapterRegRouter (NEW)              │
│      ├─ Mode0 global regs → pass through │
│      └─ Adapter-local regs → active      │
│         adapter regAddr/regData/regWr    │
└──────────────────┬───────────────────────┘
                   │
        ┌─────────┴──────────┐
        ▼                    ▼
   VdpTop logic      ┌─────────────┐
                      │   Adapter   │
                      │   Bus Mux   │ ← NEW
                      │  (modeSel)  │
                      └──────┬──────┘
                             │ active adapter bus
                             ▼
                       RegBusArbiter master 2
                              (future:
                               replaces animator)
```

**Critical correction (BrightForge #8685 §2.1):** `AdapterRegRouter` lives **inside `VdpTop` scope on the unified post-arbitration bus**, not as a QSPI-only splitter. Copper, HDMA, and bootstrap can all generate writes that fall in adapter-local address ranges; the router must be mode-aware for **all** writers, or the quiescence claim is false.

### 4.2 MODE_SELECT register

| Field | Address | Bits | Encoding |
|---|---|---|---|
| `MODE_SELECT` | `0x0313` | `[3:0]` | `0x0` = Native Mode0 (no adapter)<br>`0x1` = C64 adapter<br>`0x2` = ZX Spectrum adapter<br>`0x3..0xF` = reserved |
| `MODE_SELECT` | `0x0313` | `[7:4]` | reserved (write 0) |
| `MODE_SELECT` | `0x0313` | `[15:8]` | `MODE_FLAGS` — see §4.6 |

**Commit boundary:** `V = 0` (vertical blanking start / vsync rising edge). Frame-atomic only for v1.  
**Write authority:** Host/QSPI-only for v1. Copper/HDMA writes to `0x0313` are silently dropped by `AdapterRegRouter`.  
**Default after reset:** `0x0000` (Native Mode0).

**Host observability (CyanPeak #8684 / BronzeGate #8687 §3):**
- `LIVE_MODE` — live readback of the currently committed mode ID (e.g., mapped into a `READ_STATUS` response slot or exported as a `VdpTop.io` field).
- `MODE_SELECT_CHANGED` — sticky status bit in `STATUS_STICKY` (`0x0320`), set when `MODE_SELECT` commits at `V=0`. Write-1-to-clear. Lets the host poll for commit completion before issuing platform-specific traffic.

### 4.3 Adapter-local register address map

Adapter registers are accessed through **Mode0 REG_WRITE** at the addresses below. The `AdapterRegRouter` intercepts these writes and translates them into adapter `regAddr/regData/regWr` pulses.

| Adapter | Mode0 Address Range | Size | Maps to adapter `regAddr` |
|---|---|---|---|
| C64 | `0x0E00..0x0EFF` | 256 bytes | `addr[7:0]` |
| ZX Spectrum | `0x0F00..0x0FFF` | 256 bytes | `addr[7:0]` |
| (future) NES | `0x1000..0x10FF` | 256 bytes | `addr[7:0]` |
| (future) SMS | `0x1100..0x11FF` | 256 bytes | `addr[7:0]` |
| (future) Genesis | `0x1200..0x12FF` | 256 bytes | `addr[7:0]` |
| (future) SNES | `0x1300..0x13FF` | 256 bytes | `addr[7:0]` |
| (future) Amiga | `0x1400..0x14FF` | 256 bytes | `addr[7:0]` |
| (future) Atari ST | `0x1500..0x15FF` | 256 bytes | `addr[7:0]` |

**Palette slot reservation (BrightForge #8685 §2.6):**

Adapters may need dedicated palette entries for "border" or spare colors that do not collide with the platform's normal palette usage. Reserve slots 24..31:

| Slot | Reserved for |
|---|---|
| 24 | ZX Spectrum border |
| 25 | C64 border / spare |
| 26 | NES border / spare |
| 27..31 | Future adapters |

**Rules:**
- Writes to an adapter range when that adapter is **inactive** are silently dropped (router does not assert `regWr`).
- Writes to an unallocated adapter range are silently dropped.
- Adapter read-back is NOT supported through this path. Mode0 bus is write-only. If read-back is needed, the host must shadow the values in MCU RAM.

### 4.4 Adapter output gating (quiescence)

Every adapter **must** gate its outputs based on `io.modeSelect`:

```scala
// Inside each adapter
val active = io.modeSelect === U(myModeId, 4 bits)
io.busAddr := active ? busAddrInternal | U(0, 15 bits)
io.busData := active ? busDataInternal | B(0, 16 bits)
io.busWr   := active && busWrInternal
```

Direct outputs must also be gated. The **full inventory** of `VdpTop.io` inputs that adapters may drive (BrightForge #8685 §2.4):

| Signal | Width | Default when inactive |
|---|---|---|
| `sprite0X..sprite3X` | 10 bits each | `0` |
| `sprite0Y..sprite3Y` | 10 bits each | `0` |
| `sprite0Enabled..sprite3Enabled` | 1 bit each | `False` |
| `sprite0PatternIdx..sprite3PatternIdx` | 1 bit each | `0` |
| `rasterTriggerLine` | 10 bits | `0` |
| `rasterTriggerEnable` | 1 bit | `False` |
| `rasterTriggerClear` | 1 bit | `False` |
| `layer0UseSdram` | 1 bit | `False` |
| `layer0SdramPixel` | 4 bits | `0` |
| `layer0SdramBank` | 3 bits | `0` |
| `layer0SdramPriority` | 1 bit | `False` |
| `layer0TestPatternEnable` | 1 bit | `False` |
| `layer0TestPatternSelect` | 3 bits | `0` |
| `layer0ScrollX/Y..layer3ScrollX/Y` | 10 bits each | `0` |

**Critical:** If an adapter does not drive a given `VdpTop.io` input, that input must still receive a defined default when the adapter is inactive. Do not leave inputs floating or driven by stale state.

This guarantees that an inactive adapter cannot:
- Emit spurious bus writes
- Corrupt `VdpTop` direct inputs
- Consume arbitration bandwidth

### 4.5 RegBusArbiter wiring change

Current: `RegBusArbiter(3)` — masters 0=bootstrap, 1=qspi, 2=animator.

Future: `RegBusArbiter(3)` stays at 3 masters. Master 2 becomes the **adapter bus**, fed by an `AdapterBusMux` that selects the active adapter's gated bus output.

```scala
val adapterBusMux = Mode0RegBus()
adapterBusMux.addr := MuxCase(U(0, 15 bits), Seq(
  (modeSelect === 1) -> c64Adapter.io.busAddr,
  (modeSelect === 2) -> zxAdapter.io.busAddr
))
adapterBusMux.data := MuxCase(B(0, 16 bits), Seq(...))
adapterBusMux.enable := MuxCase(False, Seq(...))

regBusArbiter.io.masters(2) <> adapterBusMux
```

The old per-scenario `c64Demo`/`zxDemo` animator paths are replaced by always-instantiated adapters.

**Demo wrapper deprecation (BrightForge #8685 §2.5 / BronzeGate #8687 §6):**
`C64DemoAnimator` and `ZXSpectrumDemo` are temporary proof infrastructure, not the long-term runtime-control model. They synthesize host-style register writes from hardcoded programs for scenario-based hardware proof. With runtime `MODE_SELECT`, the long-term expectation is:
- MCU firmware drives adapter-local registers via `AdapterRegRouter`.
- Mode switching does not depend on permanent scenario-specific HDL demo animators.
- Demo wrappers may be retained as optional compile-time test helpers, but they are **not** part of the production runtime model.

### 4.6 Mode-switch lifecycle

**Frame-atomic commit at `V=0` (BronzeGate #8687 §1 / CyanPeak #8684):**

1. **Host issues register write** to `0x0313` (MODE_SELECT).
2. **Shadow register latches** the new mode immediately in `VdpTop`.
3. **Frame-atomic commit** transfers the shadow to the live register at the next `V=0` (vsync rising edge). `MODE_SELECT_CHANGED` sticky bit sets at this moment.
4. **Copper auto-disable:** `copperEnable` is forced to `0` at `V=0` commit (mode switch stops the old copper program). The host must upload a new copper program before re-enabling.
5. **Optional `MODE_FLAGS[0]` — auto-reset:** If set, the mode switch also triggers automatic `LAYER_ENABLE=0` at `V=0`. This gives the host a clean slate.
6. **Host re-initializes** the target platform after observing `MODE_SELECT_CHANGED=1` (or polling `LIVE_MODE`):
   - Upload assets to SDRAM (tilemaps, patterns, bitmaps, attributes)
   - Write adapter-local registers (e.g., C64 $D011, ZX `ZX_BORDER`)
   - Write global Mode0 registers (scroll, layer enable, window)
   - Upload new copper program to `0x0400..0x05FF` and re-enable copper

**State preservation rule:**
- Adapter shadow RAMs are **preserved** across mode switches. If the host switches from C64 to ZX and back to C64, the C64 shadow registers retain their last values. This is cheap (no reset logic) and harmless because the adapter is gated.
- `VdpTop` internal state (palette RAM, linestate, scroll) is **NOT** auto-cleared unless `MODE_FLAGS[0]` is set. The host must reconfigure or rely on the auto-reset flag.

**Copper program ownership (BrightForge #8685 §2.3):**
- Each mode owns its copper program. The old mode's program is NOT automatically preserved or migrated.
- On mode switch, copper stops (`copperEnable=0`). The host must upload the new program before re-enabling.
- A `COPPER_RAM_CLEAR` flag in `MODE_FLAGS[1]` may be added in a future revision to zero the copper RAM before upload.

### 4.8 Scenario/bootstrap caveat (BronzeGate #8687 §7)

**Runtime mode select is NOT the same as automatic runtime scene migration.**

`TopTang20kHdmi` contains dozens of compile-time `case scenarioId` blocks that drive bootstrap defaults: `layerData`, `tileModeData`, `attrModeData`, `copperProgram`, `winX0Data..winY1Data`, `colorMathData`, sprite defaults, animator selection, etc. These are baked at synthesis time.

Migrating to genuine runtime mode-switch requires the host to take over the bootstrap role:
- The host must upload the copper program, set scroll values, configure layers, and load assets **after** selecting a mode.
- The FPGA bitstream provides the adapters and substrate; it does **not** provide pre-baked scenes for every mode.
- A mode-switch without host re-initialization produces undefined rendering (stale palette, wrong tile mode, missing assets).

**Implication:** The first hardware proof of runtime mode-switch needs either:
- (a) Pico firmware that performs the full host re-init sequence, or
- (b) a compile-time "dual-scenario stub" that pre-loads two minimal scenes so the mode switch is visible without full host firmware.

Option (b) is acceptable for the initial Task 51 proof. Option (a) is the long-term target.

### 4.7 Shared-vs-adapter-local register policy

| Register class | Ownership | Examples |
|---|---|---|
| **Global Mode0** | Substrate — always present, never adapter-local | `LAYER_ENABLE`, scroll, window, color-math, affine, DMA/blit ctrl |
| **Adapter-local** | Adapter shadow — translated to Mode0 bus writes or direct outputs | C64 `$D000..$D02F`, ZX `ZX_BORDER..ZX_PAL_LOAD` |
| **Copper program** | Host-loaded — mode-specific | Each mode's bootstrap copper program is different |

Adapters must NOT claim global Mode0 registers as their own. They translate platform semantics into global register writes.

---

## 5. Per-Platform Research and Mapping

### 5.1 Platform readiness summary

| Platform | Min substrate | Adapter complexity | Mode0 primitives used | Est. LUT cost | Coexist OK? |
|---|---|---|---|---|---|
| **ZX Spectrum** | R7.2 | Low | Bitmap+attr fetch, indexed palette, window (border) | ~150 | ✅ Yes |
| **Commodore 64** | R3 | Medium | Raster IRQ, sprite eval, tile/bitmap fetch, palette | ~200 | ✅ Yes |
| **NES / Famicom** | R4 | Medium | Tile+attr fetch, 2-pass sprite eval, sprite-0 hit | ~250 | ✅ Yes |
| **TMS9918-family** | R4 | Low | Tile+attr fetch, sprite eval | ~150 | ✅ Yes |
| **Master System / GG** | R4 | Medium | Tile+attr fetch, sprite eval, palette banks | ~200 | ✅ Yes |
| **MSX2** | R5 | Medium-High | Tile+attr, palette, beam-driven line effects | ~300 | ⚠️ Marginal |
| **PC Engine** | R5 | Medium | Tile+attr, sprite eval, beam automation | ~250 | ✅ Yes |
| **Genesis / MD** | R6 + scroll | High | Multi-layer tile, scroll tables, window, shadow/highlight, linked sprites | ~400 | ⚠️ Marginal |
| **SNES** | R6 + R8 | Very High | 4-layer, window, color-math, affine (Mode 7), HDMA | ~600 | ❌ Unlikely |
| **Amiga** | R7 + R5 | Very High | Planar fetch, Copper, blitter, sprite priority | ~700 | ❌ Unlikely |
| **Atari ST** | R7 | High | Interleaved planar, raster hooks | ~350 | ⚠️ Marginal |
| **Neo Geo** | R7 | High | Large sprites, planar, linked list | ~400 | ⚠️ Marginal |

### 5.2 Detailed platform mapping

#### ZX Spectrum (mode 0x2) — IN-PROGRESS

| Platform function | Mode0 primitive | Adapter responsibility | Status |
|---|---|---|---|
| 256×192 bitmap | BitmapRowFetch (1bpp) + BitmapFetch | Set `BITMAP_CTRL`, `BITMAP_BASE_*` | ✅ Proven v1/v2 |
| 8×8 color attributes | BitmapFetch attr decode | None — substrate handles it | ✅ Proven v1/v2 |
| 15-color palette | CW-1 runtime palette RAM | Bootstrap loads palette via copper | ✅ Proven v1/v2 |
| Border color | CW-5 dual-window + palette slot 24 | `ZX_BORDER` → palette emitter | ✅ Proven v2 |
| FLASH attribute | Host-driven palette swap | Shadow only; counter is gap | ⚠️ v3 or later |
| Non-linear screen RAM | Host pre-shuffles before upload | Documented in artifact §6 | ✅ Option A selected |

**Honest gaps:** FLASH counter HDL, on-the-fly bitmap shuffle, bright-variant full showcase.

#### Commodore 64 (mode 0x1) — DONE (smoke test)

| Platform function | Mode0 primitive | Adapter responsibility | Status |
|---|---|---|---|
| 320×200 text/bitmap | Tile fetch / bitmap fetch | Set `VDP_TILE_MODE`, scroll | ✅ Proven Task 40 |
| Sprites (8 slots) | SpriteEvaluator (32 desc, 8/line) | Map $D000..$D015 to desc words | ✅ Proven Task 40 |
| Raster IRQ | RasterTriggerUnit | Map $D012 → triggerLine | ✅ Proven Task 40 |
| Border/BG colors | Palette RAM | Map $D020/$D021 → palette[0/1] | ✅ Proven Task 40 |
| Sprite collisions | STATUS_STICKY bits | Not routed to C64 $D019 format | ⚠️ Gap (Task 40b) |
| $D018 bank switch | Dynamic tileMap/pattern base | Not implemented | ⚠️ Gap |
| Badline / DMA steal | — | Out of scope for all adapters | ❌ Never |

#### NES / Famicom (mode 0x3 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 256×240 tile background | Tile+attr fetch (R4.1a/b) | Map PPU $2000-$23FF → tileMap base | Low |
| 2bpp planar tiles | `VDP_TILE_MODE=0x01` (NES planar) | Set tile decode mode | Low — proven R4.1b |
| 2×2 attribute packing | `VDP_ATTR_MODE=1` | Set attribute mode | Low — proven R4.1c |
| 64 sprites, 8/line | SpriteEvaluator (32 desc) | **Gap:** needs 64 desc, not 32 | Medium — substrate expansion |
| Sprite-0 hit | Sprite-0 hit sticky bit | Map to adapter status reg | Low — Task 29 proven |
| Scroll split (status bar) | RasterTrigger + linestate scroll | Program trigger → swap scrollY | Low |
| 4-screen mirroring | Scroll wrap + tileMap base | Adapter sets base per quadrant | Low |

**Readiness verdict:** Ready to spec. The 32→64 sprite descriptor expansion is the only substrate gap.

#### Master System / Game Gear (mode 0x4 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 256×192/256×224 tile bg | Tile+attr fetch | Map VDP tilemap | Low |
| 4bpp tiles | Tile fetch with 4bpp decode | `VDP_TILE_MODE` may need new encoding | Medium |
| 64 sprites, 8/line | SpriteEvaluator | Same 32→64 gap as NES | Medium |
| Palette (32 colors, 2 banks) | Palette RAM banks | Map CRAM → palette bank select | Low |
| V-scroll disable / column scroll | ScrollTable | Adapter sets per-column offsets | Low — proven Task 46 |

#### Genesis / Mega Drive (mode 0x5 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 2× tilemap layers (A, B) + window | 4-layer compositor | Map VDP layers → L0/L1/L2 | Low — Task 48 proven |
| Per-line H-scroll | ScrollTable | Map VSRAM → scroll table | Low — proven |
| Per-column V-scroll | ScrollTable | **Gap:** needs per-column entry, not just per-line | Medium |
| Shadow / highlight | ColorMath stage | Map priority bit → color-math enable | Low — proven R6 |
| 80 sprites, 20/line | SpriteEvaluator | **Gap:** needs 80 desc, 20/line | High — major substrate expansion |
| Linked-list sprites | SpriteEvaluator | **Gap:** current eval uses fixed array | High |

**Readiness verdict:** Can be specced, but honest implementation needs sprite-capacity expansion first. Coexistence with 3+ other adapters may strain LUT budget.

#### SNES / Super Famicom (mode 0x6 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 4 BG layers | 4-layer compositor | Map SNES modes → layer enables | Low |
| Mode 7 (affine bg) | AffineStepper | Map Mode 7 regs → affine matrix | Low — Task 19 proven |
| 128 sprites, 32/line | SpriteEvaluator | **Gap:** 32→128 desc expansion | High |
| Window masks (OBJ, BG1..4) | WindowUnit | Map SNES window regs → WIN0/1 | Low — proven R6 |
| Color math (add/sub/half) | ColorMath | Map color-math regs → stage ctrl | Low — proven R6 |
| HDMA per-line updates | Copper/HDMA | Map HDMA table → existing HDMA | Low — proven R5 |

**Readiness verdict:** SNES needs the largest substrate expansion (128 sprites). Even if specced, it is unlikely to coexist cleanly with Amiga/Genesis in one bitstream on Tang Nano 20K. **Recommend separate bitstream or Option D build subset.**

#### Amiga (OCS/ECS) (mode 0x7 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 1-6 bitplane fetch | PlanarLineFetch | Map BPLxPT → plane pointers | Low — proven R7.1 |
| Copper display list | Copper/HDMA | Map Copper instructions → existing copper | Low — proven R5 |
| Blitter | BlitterEngine | Map BLTxPT/BLTSIZE → blitter regs | Low — Task 49 proven |
| Sprites (8 DMA channels) | SpriteEvaluator | Map sprite DMA → desc words | Medium |
| Modulo / bitplane shift | PlanarLineFetch | **Gap:** modulo not yet in planar fetch | Medium |
| Dual-playfield | 2-layer compositor + priority | Map PF1/PF2 → L0/L1 | Low |

**Readiness verdict:** Amiga adapter is the most complex. Planar fetch and blitter exist, but honest Amiga behavior needs modulo, sprite DMA timing, and display-window hardening. **Recommend separate bitstream or Option D subset.**

#### Atari ST (mode 0x8 — proposed)

| Platform function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 320×200 4bpp interleaved planar | PlanarLineFetch | Map screen base + plane offsets | Low |
| Border/raster effects | RasterTriggerUnit | Map timer-B → raster trigger | Low |
| No hardware sprites | — | None | — |

**Readiness verdict:** Atari ST is surprisingly thin as an adapter — mostly planar fetch + raster triggers. **Very coexistence-friendly.** Good candidate for early implementation after ZX/C64/NES.

### 5.3 Runtime-selection judgment

| Tier | Platforms | Coexistence in one bitstream |
|---|---|---|
| **Tier 1 — lightweight** | ZX Spectrum, C64, TMS9918, Master System, Atari ST | ✅ Yes — combined adapter cost < 1,000 LUT |
| **Tier 2 — medium** | NES, PC Engine, MSX2 | ✅ Yes — but need sprite expansion (32→64/80) |
| **Tier 3 — heavy** | Genesis, Neo Geo | ❌ **Excluded** from default-image coexistence until sprite-capacity expansion (32→80/128) lands as a proven substrate task. BrightForge #8685 §4: current 32-descriptor evaluator already stressed LUT budget during prior bumps; doubling is not free. |
| **Tier 4 — massive** | SNES, Amiga | ❌ No — likely needs dedicated bitstream or Option D build |

**Recommendation:** Default `MODE_SELECT` bitstream includes Tier 1 + Tier 2. Tier 3 and 4 are either Option D build variants or future separate bitstreams.

---

## 6. Resource / Architecture Implications

### 6.1 Tang Nano 20K headroom

| Resource | Current (v2 sc50) | Limit | Headroom |
|---|---|---|---|
| LUT/ALU/ROM16 | 9,725 | 20,736 | ~11,000 |
| Register | 6,308 | 15,552 | ~9,200 |
| BSRAM | 17 / 46 | 46 | 29 |
| DSP | 18 / 24 | 24 | 6 |

### 6.2 Estimated adapter costs

| Adapter | LUT | FF | BSRAM |
|---|---|---|---|
| ZX Spectrum (complete v3) | ~150 | ~80 | 0 |
| C64 (complete) | ~200 | ~150 | 0–1 (font ROM) |
| NES | ~250 | ~200 | 0 |
| SMS | ~200 | ~150 | 0 |
| Atari ST | ~100 | ~50 | 0 |
| **Tier 1+2 total** | **~900** | **~630** | **0–1** |

Even with all Tier 1+2 adapters instantiated simultaneously, total cost is well under 1,000 LUT — leaving ~10,000 LUT for substrate, leaving plenty of room.

### 6.3 Router + mux overhead

- `AdapterRegRouter`: ~20 LUT (address decode + demux)
- `AdapterBusMux`: ~30 LUT (priority mux across 5–6 adapters)
- Output gating per adapter: ~10 LUT each

Total MODE_SELECT infrastructure: **~100 LUT**.

---

## 7. Files to Create / Modify

### New files

| File | Purpose |
|---|---|
| `hw/spinal/spinalhdlvdp/AdapterRegRouter.scala` | Routes QSPI writes to active adapter or Mode0 arbiter |
| `hw/spinal/spinalhdlvdp/AdapterBusMux.scala` | Muxes gated adapter bus outputs into RegBusArbiter master 2 |
| `hw/spinal/spinalhdlvdp/ModeSelectSim.scala` | Unit sim: mode switch, quiescence, router decode |
| `kb/NES/README.md` | NES adapter canonical knowledge file (Tier 2) |
| `kb/AtariST/README.md` | Atari ST adapter canonical knowledge file (Tier 1) |

### Modified files

| File | Change |
|---|---|
| `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` | Always instantiate adapters; wire router + mux; replace scenario-conditional with mode-conditional |
| `hw/spinal/spinalhdlvdp/C64Adapter.scala` | Add `modeSelect` input; gate all outputs |
| `hw/spinal/spinalhdlvdp/ZXSpectrumAdapter.scala` | Add `modeSelect` input; gate all outputs |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | Add `MODE_SELECT` safe-boundary register at `0x0313`; export latched value |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` | Claim `0x0313` (MODE_SELECT), `0x0F00..0x0FFF` (ZX adapter), `0x1000..0x15FF` (future adapters) |
| `PROJECT_PLAN/TASKS.md` | Add MODE_SELECT task entry; update adapter readiness table |

---

## 8. Validation Plan

### 8.1 Unit simulation (`ModeSelectSim`)

| Case | What it proves | Expected |
|---|---|---|
| 1 | Write `MODE_SELECT=0x2` → ZX adapter bus becomes active, C64 bus gated to 0 | PASS |
| 2 | Write adapter reg to `0x0F05` while mode=0x2 → ZX adapter receives `regAddr=0x05` pulse | PASS |
| 3 | Write adapter reg to `0x0E05` while mode=0x2 → no pulse to C64 adapter (dropped) | PASS |
| 4 | Mode switch 0x2→0x1 mid-frame → commit delayed to next `V=0`; no split-frame glitches | PASS |
| 5 | All inactive adapters assert `busWr=False` simultaneously → arbiter sees only active adapter | PASS |

### 8.2 Regression simulation

- `VdpTopSim`: unchanged behavior when `MODE_SELECT=0x0`
- `C64AdapterSim`: unchanged when `modeSelect=0x1`
- `ZXSpectrumAdapterSim`: unchanged when `modeSelect=0x2`

### 8.3 Hardware proof

- Build a single bitstream with C64 + ZX Spectrum adapters.
- Host writes `MODE_SELECT=0x1`, uploads C64 assets, verifies C64 scene.
- Host writes `MODE_SELECT=0x2`, uploads ZX assets, verifies ZX scene.
- 30s capture per mode, `analyze.py` reports `freeze=0`.

---

## 9. Honest Gaps and Blockers

| Gap | Impact | Mitigation |
|---|---|---|
| **No QSPI → adapter register path today** | Host cannot write adapter registers at runtime | `AdapterRegRouter` is required (§4.1) |
| **Adapter direct outputs not yet bus-mapped** | Raster trigger, sprite pins bypass bus; requires direct gating | Gate in adapter + mux at `TopTang20kHdmi` |
| **Sprite descriptor count (32)** | NES needs 64, Genesis 80, SNES 128 | Substrate expansion task required before Tier 2/3 adapters |
| **Mode7 / affine is proven but not adapter-wrapped** | SNES Mode 7 would need adapter-level matrix upload | Straightforward mapping once adapter exists |
| **Runtime scene migration** | `TopTang20kHdmi` bootstrap is compile-time scenarioId-dependent; runtime mode switch needs host-side re-init | Documented in §4.8; accept dual-scenario stub for initial proof, Pico firmware for long-term |
| **Tier 3/4 adapter honesty** | Genesis/SNES/Amiga adapters need more substrate than currently proven | Do not claim these as runtime-selectable until substrate gaps close |

---

## 10. Open Questions

1. ~~Should `MODE_SELECT` be writeable from Copper/HDMA?~~ **DECIDED: NO for v1.** Host/QSPI-only. Copper/HDMA writes to `0x0313` are silently dropped.
2. ~~Should we add a `MODE_SELECT_CHANGED` sticky status bit?~~ **DECIDED: YES.** Added to `STATUS_STICKY` (`0x0320`).
3. ~~Should adapter-local registers be readable via READ_STATUS?~~ **DECIDED: NO for v1.** Host shadows in MCU RAM.
4. **Tier 2 ordering:** After MODE_SELECT infrastructure is proven, which Tier 2 adapter should be implemented first? BrightForge recommends **Atari ST** (lowest cost, minimum substrate risk). NES second (highest leverage but needs sprite expansion).
5. **Should `MODE_FLAGS[1]` add `COPPER_RAM_CLEAR`?** Proposed by BrightForge #8685 §2.3. Worth adding in v1.1 or defer to v2?
6. **Should `LIVE_MODE` live in `READ_STATUS` or as a dedicated `VdpTop.io` output?** `READ_STATUS` is more host-friendly; `io` output is easier for on-FPGA test logic.

---

## 11. Recommendation

1. **Approve this architecture** (Option B with Tier 1+2 default, Tier 3/4 excluded from default bitstream).
2. **Assign CyanPeak** to audit this corrected spec (v1.1) before HDL work begins.
3. **Assign BrightForge** to implement `MODE_SELECT` infrastructure ONLY after corrected spec audit PASS. Implementation order: register+V=0 commit → adapter gating → bus mux → unified-path router → pilot proof.
4. **After MODE_SELECT infra is proven**, open adapter lanes for Tier 1 platforms. **Atari ST first** (BrightForge #8685 recommendation) due to lowest substrate risk.
5. **Do NOT** claim Genesis/SNES/Amiga as runtime-selectable in the default bitstream until sprite-capacity expansion and other substrate gaps are closed.
