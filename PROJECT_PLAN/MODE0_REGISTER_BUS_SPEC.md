# MODE0_REGISTER_BUS_SPEC.md

**Status:** Stable contract — locked by Task 32a (commit landing this file), extended to v1.1 by post-Task 32a register additions
**Governing task:** Task 32a — Mode0 Register Bus: Spec & Naming Lock
**Version:** v1.2 — deprecates RGB565 bitmap registers 0x0351..0x0356 and BITMAP_CTRL[7] per RTL cleanup `8b61a2e` (2026-05-23)
**Scope:** Write-path control surface for Mode0. The READ_STATUS response surface is defined by `QspiDecoder` sel mapping and is referenced here for completeness but is not part of the register bus itself.

This document is the authoritative naming and semantic contract for the Mode0 write-path register bus.
For high-level usage and examples, see the [**`VDP Programming Guide`**](../VDP_PROGRAMMING_GUIDE.md).
Tasks 33 (Copper-lite), 34 (QSPI asset upload), 35 (Host IRQ / Status Registers), and 37 (Affine Sprite Path) MUST target this contract without ad-hoc drift.
 Task 32b is the separate lane that will refactor the HDL so all masters reference a common bundle; 32a defines WHAT they target, 32b defines HOW.

---

## 1. Signal Contract

The register bus is a single-cycle pulse-based write contract. Every master drives one pulse per intended write; `VdpTop` and its sub-consumers sample on the pulse and commit on the safe boundary.

| Signal | Width | Direction (master → `VdpTop`) | Clock domain |
|---|---|---|---|
| `regWriteAddr` | `UInt(15 bits)` | in to `VdpTop.io.regWriteAddr` | pixel clock |
| `regWriteData` | `Bits(16 bits)` | in to `VdpTop.io.regWriteData` | pixel clock |
| `regWriteEnable` | `Bool()` | in to `VdpTop.io.regWriteEnable` | pixel clock |

- **Address width is 15 bits** — covers `0x0000..0x7FFF`. Larger spaces (bulk SDRAM asset upload per Task 34) use a different transport, not this bus.
- **Data width is 16 bits** — a single register slot. Wider registers use multiple consecutive addresses (e.g. `last_addr` in the QSPI READ_STATUS response is 16 bits of the 32-bit response word, reserved for Task 34 to extend if needed).
- **Enable is a one-cycle pulse**, not a level. A master asserts it for exactly one pixel-clock cycle when `regWriteAddr`/`regWriteData` carry a valid write.

The 3-tuple name pattern `regWrite{Addr,Data,Enable}` is the frozen naming. Future masters MUST use this exact naming at the top level of `VdpTop` integration. Internal module-level signals may use different names (e.g. `qspiDec.io.regWriteEnable`, `bootWrite`) as long as they fold into this 3-tuple at the mux boundary.

---

## 2. Masters

### 2.1 Current masters

| Master | Source | Active window | Notes |
|---|---|---|---|
| **Bootstrap** | `TopTang20kHdmi` `bootWrite` block | Power-on, ends when `bootDoneR=1` | Loads scenario-specific scene config; gates all other masters via `regWriteFromBoot` |
| **QSPI Decoder** | `QspiDecoder.io.regWrite*` | `bootDoneR=1` and host issues REG_WRITE | Bit-exact write of host-supplied data |
| **Animator** | `TopTang20kHdmi` `animWrite*` | Per-scenario, pixel-clock-periodic | In-FPGA register updates for Sc1..Sc17 animated scenes |

### 2.2 Master priority (mux at `TopTang20kHdmi.scala:534-539`)

Priority is **bootstrap > qspi > animator**, implemented as two nested `Mux`es feeding `video.io.regWriteAddr` and `video.io.regWriteData`. `regWriteEnable` is the OR of the three masters' enables.

```
regWriteFromBoot   : highest — bootstrap wins during boot window
qspiActive         : next — bootDoneR && qspiDec.regWriteEnable
animWriteActive    : lowest — in-FPGA animator
```

### 2.3 QSPI Transport Performance (Bench-validated 2026-05-23)
The QSPI transport performance varies by host platform and direction:

| Platform | Direction | Production SCK | Effective Throughput |
|---|---|---|---|
| ESP8266 / ESP32 | Bi-di | ~500 kHz (bit-bang) | ~15 KB/s |
| **ESP32-S3** | Writes | **60 MHz** (hardware) | **~6.8 MB/s** |
| **ESP32-S3** | Reads | **3 MHz** (hardware) | ~10k reads/s (~40 KB/s) |

Note: Reads are capped at 3 MHz by the FPGA response FSM; writes support higher rates with SI limits. See `firmware/GOTCHAS.md` and `kb/libvdp/README.md` for platform-specific policies.

### 2.4 Rules for new masters

Any new master added by Task 33 (Copper-lite), Task 34 (asset upload side-writes), or Task 37 (affine sprite registers) MUST:

1. Drive its own `regWriteAddr`/`regWriteData`/`regWriteEnable` signals with the same shape.
2. Be added to the `TopTang20kHdmi` mux tree with an explicit priority ranking.
3. Document its priority-vs-others in its artifact doc.
4. Not override bootstrap under any circumstances — bootstrap is always highest.

**Open question for Task 33:** Copper-lite is beam-synchronous and fires inside the active frame. Its priority relative to QSPI and animator is unspecified; the Task 33 artifact MUST decide. This spec does NOT predetermine that choice.

---

## 3. Address Map (current + reserved)

All addresses below are 15-bit; high bit is always 0 within current use.

### 3.1 Allocated

| Range | Purpose | Owning task | Reference |
|---|---|---|---|
| `0x0000..0x01DF` | Linestate prepare (480 lines × per-line `{l0en, l1en, l0scrollX[9:0]}`). **Required precondition** — a layer will NOT render on a line unless its linestate enable bit is set here, even if `LAYER_ENABLE` global bit is on. | Task 14 | `VdpTop.scala:43` |
| `0x01E0..0x02FF` | **Reserved** — linestate expansion buffer | — | — |
| `0x0300` | `LAYER_ENABLE` — `data[0]=L0, data[1]=L1, data[2]=sprite, data[3]=L2, data[4]=L3`. **Global override only** — each bit is ANDed with the per-line linestate enable. A layer is visible only when BOTH this global bit AND the linestate bit for that line are 1. | Task 13 / R5 / Task 48 | `VdpTop.scala:44,221` |
| `0x0301..0x030F` | **Reserved** — layer-group overrides | — | — |
| `0x0310` | `VDP_CTRL` — `data[0]=copperEnable` (R5.3), `data[1]=copperSwapRequest` (R5.4) | Task R5.3 / R5.4 | `VdpTop.scala:172,245` |
| `0x0311` | `VDP_TILE_MODE` — 2-bit packed/planar/shuffled | Task R4.1b/c/d | `VdpTop.scala:225,232` |
| `0x0312` | `VDP_ATTR_MODE` — 1-bit linear/packed-2×2 | Task R4.1c | `VdpTop.scala:61,240` |
| `0x0313` | `MODE_SELECT` — `[3:0]=adapter mode ID`, `[7:4]=reserved`, `[15:8]=MODE_FLAGS` | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.2 |
| `0x0314..0x031F` | **Reserved** — global-control expansion | — | — |
| `0x0320..0x032F` | **Task 35** — status registers, IRQ enables, sticky bits (see §3.1.1) | Task 35, 29 | `VdpTop.scala:878-921` |
| `0x0330..0x0334` | **Task 20** — Window 1 + Color Math (`WIN1_X0`, `WIN1_X1`, `WIN1_Y0`, `WIN1_Y1`, `COLOR_MATH_CTRL`) | Task 20 / R6 | `VdpTop.scala:249,255-263` |
| `0x0335..0x033B` | **Task 20** — Window 2 + combine (`WIN2_X0`, `WIN2_X1`, `WIN2_Y0`, `WIN2_Y1`, `WIN2_CTRL`, `WIN_COMBINE`, `LAYER_MASK`) | Task 20 / R6 | `VdpTop.scala` |
| `0x033C..0x033F` | **Task 20** — Border window (`BORDER_X0`, `BORDER_X1`, `BORDER_Y0`, `BORDER_Y1`) | Task 20 / R6 | `VdpTop.scala` |
| `0x0340..0x0346` | **Task 19** — Affine Background registers (`AFFINE_A`, `AFFINE_B`, `AFFINE_C`, `AFFINE_D`, `AFFINE_X`, `AFFINE_Y`, `AFFINE_CTRL`) | Task 19 | `VdpTop.scala:297-352` |
| `0x0347` | `BORDER_CTRL` — bit[0]=enable, bit[1]=innerBorderEnable, bits[12:8]=palette index | Task 20 / R6 | `VdpTop.scala` |
| `0x0348` | `BACKDROP_INDEX` — 7-bit palette index for background fallthrough | Lane #10567 | `VdpTop.scala` |
| `0x0349` | `SCALE_CTRL` — [2:0]=scaleX, [6:4]=scaleY, [7]=autoCenter | Lane #10590 | `PixelRepeatScaler.scala` |
| `0x034A` | `LOGIC_WIDTH` — 11-bit logical canvas width (1..640). **NOTE: Scaler source area, NOT asset size.** | Lane #10590 | `VdpTop.scala` |
| `0x034B` | `LOGIC_HEIGHT` — 11-bit logical canvas height (1..480). **NOTE: Scaler source area, NOT asset size.** | Lane #10590 | `VdpTop.scala` |
| `0x034C` | `INNER_BORDER_L` — 10-bit inner border thickness (logical pixels), left edge | Owner exception | `VdpTop.scala` |
| `0x034D` | `INNER_BORDER_R` — 10-bit inner border thickness (logical pixels), right edge | Owner exception | `VdpTop.scala` |
| `0x034E` | `INNER_BORDER_T` — 10-bit inner border thickness (logical pixels), top edge | Owner exception | `VdpTop.scala` |
| `0x034F` | `INNER_BORDER_B` — 10-bit inner border thickness (logical pixels), bottom edge | Owner exception | `VdpTop.scala` |
| `0x0350` | `BITMAP_CTRL` — `bit 7` is **deprecated** (no-op) | Task 44 / CP-1a | `VdpTop.scala`, `BitmapFetch.scala` |
| `0x0351..0x0356` | **Reserved** — deprecated (formerly RGB565 base/stride registers) | — | — |
| `0x0357..0x035F` | **Reserved** — Task 44 expansion / future host-surface registers | — | — |
| `0x0360..0x0362` | **Raster** — Trigger 1 (`TRIGGER1_LINE`, `TRIGGER1_PIXEL`, `TRIGGER1_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x0363` | **Reserved** — trigger alignment | — | — |
| `0x0364..0x0366` | **Raster** — Trigger 2 (`TRIGGER2_LINE`, `TRIGGER2_PIXEL`, `TRIGGER2_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x0367` | **Reserved** — trigger alignment | — | — |
| `0x0368..0x036A` | **Raster** — Trigger 3 (`TRIGGER3_LINE`, `TRIGGER3_PIXEL`, `TRIGGER3_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x036B..0x037F` | **Reserved** — future raster / host-surface registers | — | — |
| `0x0380..0x03DF` | **Reserved for Task 33** — Copper-lite / HDMA control and table RAM | Task 33 | — |
| `0x03E0..0x03FF` | **Reserved** — future expansion | — | — |
| `0x0400..0x05FF` | Copper program RAM (2×512 × 16-bit instructions, double-banked) | Task R5 / R5.4 | `VdpTop.scala:45,182` |
| `0x0600..0x07FF` | **Reserved** — Copper secondary tables (HDMA-style, Task 33) | Task 33 | — |
| `0x0800..0x0FFF` | **Reserved for Task 37** — affine sprite descriptors | Task 37 | — |
| `0x0A00..0x0AFF` | V-scroll table (128 entries × 2 layers × 10-bit offset) | Task 46 | `VdpTop.scala` |
| `0x0B00` | `DMA_DST` — destination start address (15 bits) | Task 47 | `VdpTop.scala`, `DmaEngine.scala` |
| `0x0B01` | `DMA_LEN` — transfer length minus 1 (10 bits) | Task 47 | `VdpTop.scala`, `DmaEngine.scala` |
| `0x0B02` | `DMA_FILL` — fill value (16 bits, FILL mode) | Task 47 | `VdpTop.scala`, `DmaEngine.scala` |
| `0x0B03` | `DMA_CTRL` — `{done_ack[2], mode[1], go[0]}` | Task 47 | `VdpTop.scala`, `DmaEngine.scala` |
| `0x0B10..0x0B4F` | DMA staging buffer (64 × 16-bit, COPY-mode source) | Task 47 | `VdpTop.scala`, `DmaEngine.scala` |
| `0x0C00` | `BLIT_CTRL` — `{done_ack[3], mode[2:1], go[0]}` (mode: 0=RECT_FILL, 1=RECT_COPY, 2=LINE_FILL) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C01` | `BLIT_WIDTH` — words per row minus 1 (10 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C02` | `BLIT_HEIGHT` — rows minus 1 (10 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C03` | `BLIT_DST_ADDR` — destination start address (15 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C04` | `BLIT_DST_STRIDE` — destination row increment in words (15 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C05` | `BLIT_SRC_ADDR` — source RAM start offset (9 bits, COPY mode) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C06` | `BLIT_SRC_STRIDE` — source RAM row increment (9 bits, COPY mode) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C07` | `BLIT_FILL_VAL` — fill constant (16 bits, FILL modes) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C10..0x0D0F` | Blitter source/store RAM (512 × 16-bit) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0D10` | `PATTERN_RAM_DATA` — sprite pattern word write-port (auto-inc) | Task 53 | `VdpTop.scala` |
| `0x0D11` | `PATTERN_RAM_PTR` — sprite pattern RAM word index | Task 53 | `VdpTop.scala` |
| `0x0D20..0x0D3F` | `SPRITE_HARD` — 32 slots x 1 word hardening extension | Phase 2 | `VdpTop.scala` |
| `0x0D40..0x0D49` | `PLANE_BASE` — 5 planes x 2 words (lo/hi). SDRAM byte addresses. | Task 55 | `VdpTop.scala` |
| `0x0D4A` | `PLANAR_CTRL` — bit 0: planar fetch enable | Task 55 | `VdpTop.scala` |
| `0x0F00..0x0FFF` | **ZX Spectrum adapter** — adapter-local register shadow (256 bytes) | Task 50 | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1000..0x10FF` | **Reserved** — future adapter (NES proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1100..0x11FF` | **Reserved** — future adapter (SMS proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1200..0x12FF` | **Reserved** — future adapter (Genesis proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1300..0x13FF` | **Reserved** — future adapter (SNES proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1400..0x14FF` | **Reserved** — future adapter (Amiga proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1500..0x15FF` | **Reserved** — future adapter (Atari ST proposed) | MODE_SELECT architecture | `MODE_SELECT_ARCHITECTURE.md` §4.3 |
| `0x1600..0x7FFF` | **Reserved** — future Mode0 expansion (palette banks, sprite attr, etc.) | — | — |

### 3.1.a Layer Enable Precondition (Linestate vs LAYER_ENABLE)

The VDP uses a **two-level** layer enable for L0 and L1:

1. **Per-line linestate** (`0x0000..0x01DF`): Each scanline has a 12-bit record with a layer-specific enable bit (`bit 11` for L0, `bit 10` for L1). At power-on, **all linestate entries are 0** (all layers disabled on every line).
2. **Global override** (`LAYER_ENABLE @ 0x0300`): A global bitmask with one bit per layer.

The effective enable for a layer on a given line is the **logical AND** of both:

```
effectiveL0Enable = linestate[line].l0en  &&  LAYER_ENABLE.bit0
effectiveL1Enable = linestate[line].l1en  &&  LAYER_ENABLE.bit1
```

**Consequence**: Setting `LAYER_ENABLE` alone is NOT sufficient. If linestate entries are still 0 (power-on default), the layer is forced to output **palette index 0** on every line, producing a uniform solid color regardless of what tiles, sprites, or planar data are configured. This is a common bring-up trap.

**Recommended bring-up sequence**:
1. Upload assets / configure fetch engines.
2. Write linestate entries (`0x0800` for L0-only, `0x0C00` for L0+L1, etc.).
3. Set `LAYER_ENABLE` global bitmask.

### 3.1.1 STATUS_STICKY bit layout (`0x0320`, write-1-to-clear)

| Bit | Name | Source | Landed |
|---|---|---|---|
| 0 | `RASTER_MATCH` | `RasterTriggerUnit.triggerPulse` | Task 35 |
| 1 | `SPRITE_OVERFLOW` | `SpriteEvaluator.overflowFlag` | Task 35 |
| 2 | `QSPI_READY` | decoder cmd_valid pulse | Task 35 |
| 3 | `QSPI_ERROR` | decoder last_error ≠ 0 | Task 35 |
| 4 | `SPRITE_0_HIT` | sprite slot 0 non-transparent over non-transparent BG | **Task 29** |
| 5 | `SPRITE_BG_HIT` | any sprite non-transparent over non-transparent BG | **Task 29** |
| 8 | `DMA_DONE` | `DmaEngine.io.done` — sticky pulse on transfer complete | **Task 47** |
| 9 | `BLIT_DONE` | `BlitterEngine.io.done` — sticky pulse on block transfer complete | **Task 49** |
| 10 | `BLIT_BUSY` | `BlitterEngine.io.busy` — live read-only; **not routed into `statusStickyReg`** | **Task 49** |
| 11 | `MODE_SELECT_CHANGED` | `MODE_SELECT` committed at `V=0` | **Task 51** |
| 6..7, 12..15 | *reserved* | — | — |

`STATUS_ENABLE` (`0x0321`) is the per-bit IRQ mask using the same bit layout; commit is safe-boundary at `hCounter === 0`.

### 3.2 Allocation rules

- Any new task that adds register addresses MUST reserve a contiguous block in its artifact and reference that block here via a commit touching this spec.
- Single-register additions outside a task's reserved block are forbidden — pick up a reserved range or open 32a (or a named extension of it) to claim one.
- Task 32b refactor MAY rename the existing HDL signals but MUST NOT change any address above.

---

## 4. Semantics

### 4.1 Write ordering and commit

All register writes are **safe-boundary committed** at `hCounter === 0` (VdpTop.scala:348). A write pulsing at arbitrary pixel position lands in a shadow register; the shadow transfers to the live register at the start of the next scanline. This prevents mid-line artifacts when host / Copper / animator fire during active video.

**Exception:** linestate prepare (`0x0000..0x01DF`) uses a different two-phase commit — prepare into line N+1, commit at end of line N (`hCounter === hTotal - 1`, see `VdpTop.scala:169`). This is intentional and predates Task 32a; Task 32a documents it, does not modify it.

### 4.2 Multi-master on the same cycle

Priority mux (§2.2) resolves address/data. If two masters pulse `regWriteEnable` in the same cycle:
- OR of enables → single pulse visible to `VdpTop`.
- Addr/data mux picks the higher-priority master's values.
- Lower-priority master's write is silently dropped that cycle.

**Task 36 (Register Write Concurrency Stress)** must prove this doesn't corrupt safe-boundary commits under max traffic.

### 4.3 Write acknowledgement

The bus has no hardware acknowledgement path. Masters pulse and assume the write lands. The legacy QSPI read-path (`READ_STATUS sel=1..3`) has been removed to save logic; `sel=4` (last_error) remains available for transport-layer diagnostics.

### 4.4 Atomicity within a single register

16-bit writes are atomic — the entire `regWriteData` word is captured in a single pulse. Host does not need to split writes.

---

## 5. Read-path companion (informational)

The register bus is write-only. Read-back is provided by the QSPI READ_STATUS response surface, not by this bus. Per Task 38b:

| sel | Response contents |
|---|---|
| `0` | Magic `0x51560002` (host transport ID) |
| `1..3` | **Removed** (formerly rx_cmd_cnt, last_addr, last_data) — returns 0 |
| `4` | `last_error[7:0]` |
| `5` | sticky status bits (`STATUS_STICKY` bit layout, §3.1.1) |
| `6` | upload status (`busy`/`done` bits) |
| `7` | committed live mode (post-safe-boundary `MODE_SELECT` and layer state) |
| `8` | SDRAM readback — 32-bit word from debug address (0x0326/0x0327) |
| `9..255` | Reserved — zero response |

Task 35 status registers MUST be readable both by mapping into this sel table (extending to sel=5+) AND by appearing in the allocated `0x0320..0x032F` write-path block for clear-on-write semantics.

---

## 6. Naming Conventions

Lock: use the prefixes below when adding new register addresses.

| Prefix | Domain |
|---|---|
| `VDP_*` | Global Mode0 control (e.g. `VDP_CTRL`, `VDP_TILE_MODE`, `VDP_ATTR_MODE`) |
| `LAYER_*` | Per-layer config (`LAYER_ENABLE`, future `LAYER_PRIORITY`) |
| `STATUS_*` | Task 35 status + IRQ (`STATUS_ENABLE`, `STATUS_CLEAR`, `STATUS_STICKY`) |
| `COPPER_*` | Task 33 Copper-lite control (`COPPER_RUN`, `COPPER_PC`) |
| `SPRITE_*` | Task 37 sprite-side registers |
| `ASSET_*` | Task 34 asset upload control (handshake, not bulk data itself) |

Names MUST be uppercase, ASCII, underscore-separated, and MUST NOT conflict with any Scala identifier in `VdpTop`. A follow-up (optional) `object RegAddr { final val LAYER_ENABLE = 0x0300; ... }` constants file is a Task 32b candidate.

---

## 7. Forward-compatibility notes

### 7.1 Data width

Locked at **16 bits**. Task 34 (asset upload) wanting wider bursts MUST use an out-of-band transport (e.g. a separate SDRAM burst path via QSPI bulk write), not this bus.

### 7.2 Address width

Locked at **15 bits**. If Mode0 evolves to need more, that's a v2 bus — opened via a new 32-series task, not an extension of 32a.

### 7.3 Bus evolution rule

Any change to §1 (Signal Contract) requires:
1. A new task doc proposing the change
2. Mutual-coverage review by CoralReef + CyanPeak
3. PM sign-off
4. A version bump of this spec with historical table

Version bumps MUST preserve the existing allocated addresses in §3.1 unchanged.

---

## 8. Validation baseline

At the time of this spec lock, the following simulations prove zero behavioral drift:

| Sim | Cases | Coverage |
|---|---|---|
| `QspiRegWriteSim` | 13 | Write-path regression + READ_STATUS sel=0..4 + snapshot |
| `QspiSlaveSim` | 4 | Slave framing + Respond-state drive contract |
| `AffineRegSim` | (existing) | Affine register writes via bus |
| `AffineVdpTopSim` | (existing) | Full-stack bus routing |

All pass as of commit `4cee22e` (Task 38c closeout). Task 32a does not introduce HDL changes; these baselines remain green by construction.

---

## 9. Open questions deferred to later tasks

- **§2.3:** Copper-lite master priority relative to QSPI / animator — Task 33 artifact.
- **§3.1:** Palette bank addressing (currently hardcoded in `VdpTop.scala:755+`) — future task if palette animation moves to host control.
- **§5:** Status register clear semantics (write-1-to-clear vs read-to-clear vs auto-clear) — Task 35 artifact.
- **§7.1:** If Task 34 bulk asset upload needs a sideband write register (e.g. `ASSET_ADDR` pointer), its placement at `0x0350..0x035F` is suggested but not locked.
- **§7.2:** Task 19 Affine Background registers (`0x0340..0x0346`) were omitted in v1.0; corrected in v1.1. Task 33 Copper-lite relocated from `0x0340..0x034F` (erroneous) to `0x0380..0x03DF`.
- **Sprite descriptor capacity:** Approved redesign target is `descCount=32`, `visiblePerLine=8` (BrightForge #10360). The existing `0x0800..0x0FFF` Task 37 descriptor space (2048 words) is already sized for this target; no register-address changes are required. Live build remains `descCount=8` pending merge.

---

### 10. Barebones build register conflict note

The `TopTang20kBarebones` build (scroll + simple-sprite proofs) uses a **separate, incompatible** register surface at `0x0000..0x0005` (`L0_SCROLL_X`, `L0_SCROLL_Y`, `L1_SCROLL_X`, `L1_SCROLL_Y`, `SPRITE_X`, `SPRITE_Y`). These addresses overlap the standard `LINESTATE_BASE` (`0x0000..0x01DF`) used by the rich-top `VdpTop`. Host code MUST use `vdp_barebones_*` helpers (inline bit-bang, 40-bit SPI protocol) with barebones bitstreams and MUST NOT use `vdp_mode0_*` helpers. See `firmware/GOTCHAS.md` §Host Platform Fidelity and `kb/libvdp/README.md` §Migration & Naming Plan.

---

*End of Mode0 Register Bus Spec v1.1.*
