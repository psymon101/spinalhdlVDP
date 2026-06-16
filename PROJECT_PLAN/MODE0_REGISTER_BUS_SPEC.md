# MODE0_REGISTER_BUS_SPEC.md

**Status:** Stable contract — v1.5 Landed (Task 129 + ACK/NAK Phase 2)
**Governing task:** Task 32a — Mode0 Register Bus: Spec & Naming Lock
**Version:** v1.7 — auto-generated register detail tables from `firmware/libvdp/mode0_regs.json`; added RGB565 direct-color burst-read 32-byte alignment note.
**Scope:** Write-path control surface for Mode0. The `READ_STATUS` response surface is defined by the i80/QSPI status multiplexer and is referenced here for completeness but is not part of the register bus itself.

This document is the authoritative naming and semantic contract for the Mode0 write-path register bus.
For high-level usage and examples, see the [**`VDP Programming Guide`**](../VDP_PROGRAMMING_GUIDE.md).
Tasks 33 (Copper-lite), 34 (host asset upload), 35 (Host IRQ / Status Registers), and 37 (Affine Sprite Path) MUST target this contract without ad-hoc drift.
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
- **Data width is 16 bits** — a single register slot. Wider registers use multiple consecutive addresses (e.g. `last_addr` in the `READ_STATUS` response is 16 bits of the 32-bit response word, reserved for Task 34 to extend if needed).
- **Enable is a one-cycle pulse**, not a level. A master asserts it for exactly one pixel-clock cycle when `regWriteAddr`/`regWriteData` carry a valid write.

The 3-tuple name pattern `regWrite{Addr,Data,Enable}` is the frozen naming. Future masters MUST use this exact naming at the top level of `VdpTop` integration. Internal module-level signals may use different names (e.g. `qspiDec.io.regWriteEnable`, `bootWrite`) as long as they fold into this 3-tuple at the mux boundary.

---

## 2. Masters

### 2.1 Current masters

| Master | Source | Active window | Notes |
|---|---|---|---|
| **Bootstrap** | `TopTang20kHdmi` `bootWrite` block | Power-on, ends when `bootDoneR=1` | Loads scenario-specific scene config; gates all other masters via `regWriteFromBoot` |
| **i80 (Primary)** | `I80HostInterface.io.regBus` | `bootDoneR=1` and host issues write | **Primary transport for Tang Nano 20K**; bit-exact write of host-supplied data |
| **QSPI Decoder** | `QspiDecoder.io.regWrite*` | `bootDoneR=1` and host issues REG_WRITE | Legacy/alternate transport; same bit-exact contract as i80 |
| **Animator** | `TopTang20kHdmi` `animWrite*` | Per-scenario, pixel-clock-periodic | In-FPGA register updates for Sc1..Sc17 animated scenes (mutually exclusive with i80) |

### 2.2 Master priority (mux at `TopTang20kHdmi.scala:500-530`)

Priority is **bootstrap > host > i80/animator**, implemented via `RegBusArbiter`. i80/animator occupy the same slot (Master 2) and are mutually exclusive based on the `hostI80` parameter. The legacy QSPI decoder occupies Master 1 when the QSPI top is built; on the canonical i80 top, Master 1 is unused.

```
Master 0: regWriteFromBoot : highest — bootstrap wins during boot window
Master 1: qspiActive       : next — post-boot QSPI writes (legacy, i80 builds leave unused)
Master 2: i80 / animator   : lowest — post-boot i80 host OR internal animator
```

### 2.3 i80 Transport Protocol (Canonical)

The primary host interface is an 8-bit parallel Intel-8080-style bus driven by an ESP32-S3. `firmware/libvdp/vdp_i80.h` is the C/C++ facade.

| Opcode (DC=0) | Transaction | Direction | Phase sequence |
|---|---|---|---|
| `0x00` | Register write | Host → VDP | `opcode` → `addr_lo` → `addr_hi` → `data_lo` → `data_hi` |
| `0x01` | Register read  | Host ← VDP | `opcode` → `addr_lo` → `addr_hi` → read `data_lo` → read `data_hi` |
| `0x02` | SDRAM block write | Host → VDP | `opcode` → `addr_lo` → `addr_hi` → `len_lo` → `len_hi` → `len+1` data words |

- `CS#` frames the entire transaction.
- `DC#` low = opcode or address byte; `DC#` high = data byte.
- `WR#` rising edge latches host output; `RD#` rising edge samples host input.

**Readback semantics:** Most register reads return the **last value written to that address** (loopback). This is sufficient for host shadow verification but is not a full register-file readback. `0x0328`/`0x0329` return armed SDRAM debug data. Status snapshots use the `READ_STATUS` selector mechanism described in §5.

### 2.4 QSPI Transport Performance (Bench-validated 2026-05-23) — Retired
The QSPI transport performance below is historical; QSPI is no longer the canonical Tang Nano 20K host path. See `archive/QSPI_HOST_CONTROL_PLAN.md` for the full QSPI history.

| Platform | Direction | Production SCK | Effective Throughput |
|---|---|---|---|
| ESP8266 / ESP32 | Bi-di | ~500 kHz (bit-bang) | ~15 KB/s |
| **ESP32-S3** | Writes | **60 MHz** (hardware) | **~6.8 MB/s** |
| **ESP32-S3** | Reads | **3 MHz** (hardware) | ~10k reads/s (~40 KB/s) |

Note: Reads are capped at 3 MHz by the FPGA response FSM; writes support higher rates with SI limits. See `firmware/GOTCHAS.md` and `kb/libvdp/README.md` for platform-specific policies.

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
| `0x0314` | `L0_TRANS_KEY` — 4-bit transparency index for layer 0 | R6 / #3 | `VdpTop.scala` |
| `0x0315` | `L1_TRANS_KEY` — 4-bit transparency index for layer 1 | R6 / #3 | `VdpTop.scala` |
| `0x0316` | `L2_TRANS_KEY` — 4-bit transparency index for layer 2 | R6 / #3 | `VdpTop.scala` |
| `0x0317` | `L3_TRANS_KEY` — 4-bit transparency index for layer 3 | R6 / #3 | `VdpTop.scala` |
| `0x0318..0x031F` | **Reserved** — global-control expansion | — | — |
| `0x0320..0x0322` | **Task 35** — status registers, IRQ enables, sticky bits (see §3.1.1) | Task 35, 29 | `VdpTop.scala:878-921` |
| `0x0323` | `UPLOAD_STATUS_CLEAR` — write-1-to-clear for bridge sticky bits (see §3.1.2) | **Landed (Phase 2)** | `QspiDecoder.scala` |
| `0x0324..0x032F` | **Reserved** — status expansion | — | — |
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
| `0x0351..0x0352` | `BITMAP_BASE` (23-bit) — assemble LO/HI for SDRAM bitmap base | **Landed (Task 129)** | `VdpTop.scala` |
| `0x0353..0x0354` | `ATTR_BASE` (23-bit) — assemble LO/HI for SDRAM attribute base | **Landed (Task 129)** | `VdpTop.scala` |
| `0x0355` | `BITMAP_STRIDE` — bytes per bitmap row (direct-color default 512) | **Landed (Task 129)** | `VdpTop.scala` |
| `0x0356` | `ATTR_STRIDE` — bytes per attribute row (direct-color default 512) | **Landed (Task 129)** | `VdpTop.scala` |
| `0x0357` | `BITMAP_HEIGHT` — 10-bit source bitmap height (default 240) | **Landed (Task 129)** | `VdpTop.scala` |
| `0x0358..0x035F` | **Reserved** — bitmap expansion | — | — |
| `0x0360..0x0362` | **Raster** — Trigger 1 (`TRIGGER1_LINE`, `TRIGGER1_PIXEL`, `TRIGGER1_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x0363` | **Reserved** — trigger alignment | — | — |
| `0x0364..0x0366` | **Raster** — Trigger 2 (`TRIGGER2_LINE`, `TRIGGER2_PIXEL`, `TRIGGER2_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x0367` | **Reserved** — trigger alignment | — | — |
| `0x0368..0x036A` | **Raster** — Trigger 3 (`TRIGGER3_LINE`, `TRIGGER3_PIXEL`, `TRIGGER3_CTRL`) | Task 35 / R6 | `VdpTop.scala`, `RasterTriggerUnit.scala` |
| `0x036B..0x037F` | **Reserved** — future raster / host-surface registers | — | — |
| `0x0380..0x03DF` | **Reserved for Task 33** — Copper-lite / HDMA control and table RAM | Task 33 | — |
| `0x03E0..0x03FF` | **Reserved** — future expansion | — | — |
| `0x0400..0x05FF` | Copper program RAM (2×512 × 16-bit instructions, double-banked) | Task R5 / R5.4 | `VdpTop.scala:45,182` |
| `0x0600` | `PALETTE_DATA` — write one 16-bit palette half; auto-increments internal pointer | Color/Window Hardening | `VdpTop.scala` |
| `0x0601` | `PALETTE_PTR` — sets the half-pointer for the next `PALETTE_DATA` write | Color/Window Hardening | `VdpTop.scala` |
| `0x0602..0x07FF` | **Reserved** — Copper secondary tables (HDMA-style, Task 33) | Task 33 | — |
| `0x0800..0x08FF` | Sprite external descriptors + affine matrices (bus-writable evaluator state) | Landed | `VdpTop.scala:1604` |
| `0x0900..0x0FFF` | **Reserved** — scroll-table expansion / future sprite state | — | — |
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
| `0x0D4A` | `PLANAR_CTRL` — bit[0] enable, bits[3:1] planeCount-1 | Task 55 | `VdpTop.scala` |
| `0x0D4B` | `PLANAR_WIDTH` — 10-bit planar clip width (default 320; values >320 wrap) | R6 / #4 | `VdpTop.scala` |
| `0x0D4C..0x0D7F` | **Reserved** — planar expansion | — | — |
| `0x0800..0x087F` | **Reserved** — Task 31 legacy scroll mapping (avoid using) | Task 31 | — |
| `0x0900..0x097F` | Layer 0 H-scroll table (128 entries × 10 bits) | Task 31 | `VdpTop.scala:878+` |
| `0x0980..0x09FF` | Layer 1 H-scroll table (128 entries × 10 bits) | Task 31 | `VdpTop.scala:884+` |

> [!WARNING]
> **BITMAP_BASE Overlap / Alignment Note**: The hardware power-on defaults for `BITMAP_BASE` (`0x3000`) and `ATTR_BASE` (`0x4000`) were chosen for legacy 1/2bpp compatibility. When rendering in direct-color RGB565 mode with the default 512-byte stride, these bases overlap after just 8 rows. To display a full-screen RGB565 image, the host **must** reconfigure `0x0351..0x0354` to non-overlapping bases (e.g., `0x100000` and `0x200000`).
>
> In RGB565 direct-color mode the hardware additionally masks the low 5 bits of `BITMAP_BASE`, `ATTR_BASE`, `BITMAP_STRIDE`, and `ATTR_STRIDE` to zero, so all four values **must be 32-byte aligned**. Writes to bits `[4:0]` of those registers are ignored in direct-color mode. The power-on defaults and the recommended `0x100000`/`0x200000` bases are already 32-byte aligned; only custom values need alignment checking.

### 3.1.1 STATUS_STICKY bit layout (`0x0320`, write-1-to-clear)

| Bit | Name | Source | Landed |
|---|---|---|---|
| 0 | `RASTER_MATCH` | `RasterTriggerUnit.triggerPulse` | Task 35 |
| 1 | `SPRITE_OVERFLOW` | `SpriteEvaluator.overflowFlag` | Task 35 |
| 2 | `HOST_READY` | host bridge accepted-command pulse (legacy alias: `QSPI_READY`) | Task 35 |
| 3 | `HOST_ERROR` | host bridge `last_error ≠ 0` (legacy alias: `QSPI_ERROR`) | Task 35 |
| 4 | `SPRITE_0_HIT` | sprite slot 0 non-transparent over non-transparent BG | **Task 29** |
| 5 | `SPRITE_BG_HIT` | any sprite non-transparent over non-transparent BG | **Task 29** |
| 8 | `DMA_DONE` | `DmaEngine.io.done` — sticky pulse on transfer complete | **Task 47** |
| 9 | `BLIT_DONE` | `BlitterEngine.io.done` — sticky pulse on block transfer complete | **Task 49** |
| 10 | `BLIT_BUSY` | `BlitterEngine.io.busy` — live status | **Task 49** |
| 11 | `MODE_SELECT_CHANGED` | `MODE_SELECT` committed at `V=0` | **Task 51** |
| 6..7, 12..15 | *reserved* | — | — |

`STATUS_ENABLE` (`0x0321`) is the per-bit IRQ mask using the same bit layout; commit is safe-boundary at `hCounter === 0`.

### 3.1.2 UPLOAD_STATUS (READ_STATUS sel=6) and `UPLOAD_STATUS_CLEAR` (`0x0323`, W1C)

ACK/NAK lane (#11500 / #11508 / #11557). The bridge's upload status is surfaced on READ_STATUS sel=6 and is **physically separate** from `STATUS_STICKY` (`0x0320`) — a `0x0320` write does NOT clear it.

| sel=6 byte0 bit | Name | Semantics | Host-clearable |
|---|---|---|---|
| 0 | `upload_busy` | bridge active OR uploadCc not fully drained | live (not sticky) |
| 1 | `upload_done` | last SDRAM_WRITE handed off (latched) | live |
| 2 | `upload_error` | CP-A1 watchdog abort (wedge / short frame) — sticky | **W1C via `0x0323` bit2** |
| 3 | `upload_overflow` | CP-A4 ingress-FIFO overflow — sticky | **W1C via `0x0323` bit3** |
| 4 | `txn_dropped` | **Phase 2 (PA-2 #11614/#11626)**: a new SDRAM_WRITE header arrived while the previous write still had bytes outstanding (drop / re-anchor) — sticky | **W1C via `0x0323` bit4** |
| 5 | `short_frame` | RESERVED — Fix A framing-hardening lane (#11557); stays 0 until that logic lands | (W1C via `0x0323` bit5) |
| 6..7 | reserved | 0 | — |

byte1 = `txn_counter` (ACK/NAK Phase 1 commit counter, mod 256). bytes2-3 = 0.

**`UPLOAD_STATUS_CLEAR` (`0x0323`, write-1-to-clear)** — within the reserved `0x0320..0x032F` block. A host `REG_WRITE` whose data bits mirror the sel=6 byte0 positions clears the corresponding sticky bit (bit2→`upload_error`, bit3→`fifoOverflow`, **bit4→`txn_dropped`**; bit5→`short_frame` once Fix A lands). Decoded in the pixel domain by the host bridge (`QspiDecoder` on legacy QSPI builds, equivalent i80 decoder on i80 builds; no CDC): bit2/bit3 strobe into the bridge's Regs (a genuine re-set the same cycle wins, so a live error is never lost); bit4 clears the decoder's own `txn_dropped` Reg. Fix B (#11557) sim-proven: `QspiAckNakSim` (D) + `QspiWriteStatusReproSim` (E). Phase 2 `txn_dropped` (PA-2 #11614/#11626) sim: `QspiWriteStatusReproSim` (F).

### 3.1.3 Auto-Generated Register Detail Tables

> **Generated from:** `firmware/libvdp/mode0_regs.json`  
> **Generator:** `scripts/gen_reg_docs.py`  
> **Note:** Regenerated after BronzeGate backfilled descriptions and normalized categories (`immediate` / `vblank-sensitive` / `stream` / `diagnostic`).

### LAYER_ENABLE (`0x0300`)

| Attribute | Value |
|---|---|
| Addr | `0x0300` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | **Global** layer-enable mask. A layer renders on a given output line only when the corresponding bit here **and** the per-line linestate enable bit are both 1. |

| Bits | Field | Description |
|---|---|---|
| `[0]` | L0 | Enables layer 0 output globally. |
| `[1]` | L1 | Enables layer 1 output globally. |
| `[2]` | SPRITE | Enables sprite output globally. |
| `[3]` | L2 | Enables layer 2 output globally. |
| `[4]` | L3 | Enables layer 3 output globally. |

> [!IMPORTANT]
> `LAYER_ENABLE` is a global override only. The render pipeline computes `effectiveL0Enable = linestate.layer0Enable && LAYER_ENABLE(0)` (and similarly for L1). Host code that sets `LAYER_ENABLE` must also populate the linestate entries at `0x0000..0x01DF` (see §3.1).

### VDP_CTRL (`0x0310`)

| Attribute | Value |
|---|---|
| Addr | `0x0310` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | mixed (see bit descriptions) |
| Description | Controls global Mode0 runtime features. |

| Bits | Field | Category | Description |
|---|---|---|---|
| `[0]` | COPPER_ENABLE | H-boundary | Enables copper command execution. Commits at `hCounter == 0`; PC resets to 0 on the rising edge. |
| `[1]` | COPPER_SWAP_REQUEST | V-boundary | Requests an atomic copper bank swap. Only honored while `COPPER_ENABLE = 1`. Commits at `vSyncStart && hCounter == 0`, flips the active bank, resets PC to 0, and auto-clears. |
| `[2]` | SOFT_RESET_REQUEST | V-boundary | Writing `1` triggers a host-requested soft reset. The controller runs four stages: (1) host-writable BSRAM memories zeroed (copper program RAM, HDMA data/table, palette, sprite pattern RAM, sprite ext descriptors + affine matrices, linestate, scroll tables, DMA staging, blitter source RAM); (2) SDRAM occupied-region zero-fill — `[base, base + stride·height)` per active layer source using the last host-programmed geometry registers, with lightweight auto-refresh roughly every 15 µs; (3) core register reset — all host-writable config registers return to `init`, pending/commit hits are cleared, and `STATUS_STICKY` / `STATUS_ENABLE` / sprite-collision mask are cleared so no stale IRQ fires; (4) done — `SOFT_RESET_BUSY` is released synchronously at `hCounter == 0`. The 1000 ms timeout is retained as a safety bound. Excluded: `affineTexture` and immutable tile ROMs (no write port), transient line buffers, legacy demo sprite input ports, untouched SDRAM outside the occupied regions. The bit auto-clears when reset completes. |

**Polling completion:** A read of `0x0310` returns live `{..., bit2=SOFT_RESET_BUSY}`. Host sequence: write `0x0004`, then poll `read(0x0310)` until bit 2 is `0`.

### VDP_TILE_MODE (`0x0311`)

| Attribute | Value |
|---|---|
| Addr | `0x0311` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects the tile pattern decode mode. |

| Bits | Field | Description |
|---|---|---|
| `[1:0]` | MODE | Tile pattern decode mode selector. |

### VDP_ATTR_MODE (`0x0312`)

| Attribute | Value |
|---|---|
| Addr | `0x0312` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects the tile attribute decode mode. |

| Bits | Field | Description |
|---|---|---|
| `[0]` | MODE | Tile attribute decode mode selector. |

### MODE_SELECT (`0x0313`)

| Attribute | Value |
|---|---|
| Addr | `0x0313` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects the active compatibility adapter mode. |

| Bits | Field | Description |
|---|---|---|
| `[3:0]` | ADAPTER_MODE | Compatibility adapter mode selector. |
| `[15:8]` | MODE_FLAGS | Adapter-specific mode option flags. |

### L0_TRANS_KEY (`0x0314`), L1_TRANS_KEY (`0x0315`), L2_TRANS_KEY (`0x0316`), L3_TRANS_KEY (`0x0317`)

| Attribute | Value |
|---|---|
| Addr | `0x0314` / `0x0315` / `0x0316` / `0x0317` |
| Width | 4 |
| Access | RW |
| Reset | `0x0000` |
| Category | H-boundary |
| Description | Per-layer transparency color index. Pixels matching this palette entry are treated as transparent on the corresponding layer. |

| Bits | Field | Description |
|---|---|---|
| `[3:0]` | KEY | Palette index treated as transparent for this layer. |
| `[15:4]` | — | Reserved, write zero. Firmware helpers mask to 4 bits. |

### PLANAR_WIDTH (`0x0D4B`)

| Attribute | Value |
|---|---|
| Addr | `0x0D4B` |
| Width | 10 |
| Access | RW |
| Reset | `0x0140` (`320`) |
| Category | vblank-sensitive |
| Description | Planar clip width. The planar renderer wraps the active fetch window at this pixel boundary. |

| Bits | Field | Description |
|---|---|---|
| `[9:0]` | WIDTH | Planar clip width in pixels. Default `320`. Values greater than `320` wrap around modulo the line width. |
| `[15:10]` | — | Reserved, write zero. |

### STATUS_STICKY (`0x0320`)

| Attribute | Value |
|---|---|
| Addr | `0x0320` |
| Width | 16 |
| Access | W1C |
| Reset | `0x0000` |
| Category | diagnostic |
| Description | Sticky status and interrupt cause flags; write one to clear. |

| Bits | Field | Description |
|---|---|---|
| `[0]` | RASTER_MATCH | Raster trigger match occurred. |
| `[1]` | SPRITE_OVERFLOW | Sprite evaluation overflow occurred. |
| `[2]` | HOST_READY | Host bridge reported ready (legacy alias: QSPI_READY). |
| `[3]` | HOST_ERROR | Host bridge reported an error (legacy alias: QSPI_ERROR). |
| `[4]` | SPRITE_0_HIT | Sprite 0 collision flag latched. |
| `[5]` | SPRITE_BG_HIT | Sprite/background collision flag latched. |
| `[8]` | DMA_DONE | DMA operation completed. |
| `[9]` | BLIT_DONE | Blitter operation completed. |
| `[10]` | BLIT_BUSY | Blitter busy state is latched. |
| `[11]` | MODE_SELECT_CHANGED | Mode select value changed. |

### STATUS_ENABLE (`0x0321`)

| Attribute | Value |
|---|---|
| Addr | `0x0321` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | diagnostic |
| Description | Enables reporting for selected sticky status sources. |

### SPRITE_COLL_MASK (`0x0322`)

| Attribute | Value |
|---|---|
| Addr | `0x0322` |
| Width | 16 |
| Access | W1C |
| Reset | `0x0000` |
| Category | diagnostic |
| Description | Clears selected sprite collision sticky bits. |

### UPLOAD_STATUS_CLEAR (`0x0323`)

| Attribute | Value |
|---|---|
| Addr | `0x0323` |
| Width | 16 |
| Access | W1C |
| Reset | `0x0000` |
| Category | diagnostic |
| Description | Clears sticky host upload bridge error flags. |

| Bits | Field | Description |
|---|---|---|
| `[2]` | UPLOAD_ERROR | Clears upload error sticky flag. |
| `[3]` | UPLOAD_OVERFLOW | Clears upload overflow sticky flag. |
| `[4]` | TXN_DROPPED | Clears dropped transaction sticky flag. |
| `[5]` | SHORT_FRAME | Clears short-frame sticky flag. |

### WIN1_X0 (`0x0330`)

| Attribute | Value |
|---|---|
| Addr | `0x0330` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 1 inclusive left X coordinate. |

### WIN1_X1 (`0x0331`)

| Attribute | Value |
|---|---|
| Addr | `0x0331` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 1 exclusive right X coordinate. |

### WIN1_Y0 (`0x0332`)

| Attribute | Value |
|---|---|
| Addr | `0x0332` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 1 inclusive top Y coordinate. |

### WIN1_Y1 (`0x0333`)

| Attribute | Value |
|---|---|
| Addr | `0x0333` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 1 exclusive bottom Y coordinate. |

### COLOR_MATH_CTRL (`0x0334`)

| Attribute | Value |
|---|---|
| Addr | `0x0334` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Controls windowed color math and blend behavior. |

### WIN2_X0 (`0x0335`)

| Attribute | Value |
|---|---|
| Addr | `0x0335` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 2 inclusive left X coordinate. |

### WIN2_X1 (`0x0336`)

| Attribute | Value |
|---|---|
| Addr | `0x0336` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 2 exclusive right X coordinate. |

### WIN2_Y0 (`0x0337`)

| Attribute | Value |
|---|---|
| Addr | `0x0337` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 2 inclusive top Y coordinate. |

### WIN2_Y1 (`0x0338`)

| Attribute | Value |
|---|---|
| Addr | `0x0338` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Window 2 exclusive bottom Y coordinate. |

### WIN2_CTRL (`0x0339`)

| Attribute | Value |
|---|---|
| Addr | `0x0339` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Controls Window 2 enable and selection behavior. |

### WIN_COMBINE (`0x033A`)

| Attribute | Value |
|---|---|
| Addr | `0x033A` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects how Window 1 and Window 2 masks combine. |

### LAYER_MASK (`0x033B`)

| Attribute | Value |
|---|---|
| Addr | `0x033B` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects which layers participate in window/color operations. |

### BORDER_X0 (`0x033C`)

| Attribute | Value |
|---|---|
| Addr | `0x033C` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Outer border inclusive left X coordinate. |

### BORDER_X1 (`0x033D`)

| Attribute | Value |
|---|---|
| Addr | `0x033D` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Outer border exclusive right X coordinate. |

### BORDER_Y0 (`0x033E`)

| Attribute | Value |
|---|---|
| Addr | `0x033E` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Outer border inclusive top Y coordinate. |

### BORDER_Y1 (`0x033F`)

| Attribute | Value |
|---|---|
| Addr | `0x033F` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Outer border exclusive bottom Y coordinate. |

### AFFINE_A (`0x0340`)

| Attribute | Value |
|---|---|
| Addr | `0x0340` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine matrix A coefficient for transformed fetches. |

### AFFINE_B (`0x0341`)

| Attribute | Value |
|---|---|
| Addr | `0x0341` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine matrix B coefficient for transformed fetches. |

### AFFINE_C (`0x0342`)

| Attribute | Value |
|---|---|
| Addr | `0x0342` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine matrix C coefficient for transformed fetches. |

### AFFINE_D (`0x0343`)

| Attribute | Value |
|---|---|
| Addr | `0x0343` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine matrix D coefficient for transformed fetches. |

### AFFINE_X (`0x0344`)

| Attribute | Value |
|---|---|
| Addr | `0x0344` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine transform X origin or translation term. |

### AFFINE_Y (`0x0345`)

| Attribute | Value |
|---|---|
| Addr | `0x0345` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Affine transform Y origin or translation term. |

### AFFINE_CTRL (`0x0346`)

| Attribute | Value |
|---|---|
| Addr | `0x0346` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Controls affine transform enable and options. |

### BORDER_CTRL (`0x0347`)

| Attribute | Value |
|---|---|
| Addr | `0x0347` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Enables border rendering and selects its palette index. |

| Bits | Field | Description |
|---|---|---|
| `[0]` | ENABLE | Enables outer border rendering. |
| `[1]` | INNER_BORDER_ENABLE | Enables inner border inset handling. |
| `[12:8]` | PALETTE_INDEX | Palette index used for border pixels. |

### BACKDROP_INDEX (`0x0348`)

| Attribute | Value |
|---|---|
| Addr | `0x0348` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Selects the backdrop palette index. |

| Bits | Field | Description |
|---|---|---|
| `[6:0]` | INDEX | Palette index used for backdrop pixels. |

### SCALE_CTRL (`0x0349`)

| Attribute | Value |
|---|---|
| Addr | `0x0349` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Controls logical-to-output pixel scaling. |

| Bits | Field | Description |
|---|---|---|
| `[2:0]` | SCALE_X | Horizontal integer scale factor selector. |
| `[6:4]` | SCALE_Y | Vertical integer scale factor selector. |
| `[7]` | AUTO_CENTER | Centers the logical image in the output frame. |

### LOGIC_WIDTH (`0x034A`)

| Attribute | Value |
|---|---|
| Addr | `0x034A` |
| Width | 16 |
| Access | RW |
| Reset | `0x0280` |
| Category | vblank-sensitive |
| Description | Logical source width used by the scaler. |

| Bits | Field | Description |
|---|---|---|
| `[10:0]` | WIDTH | Logical source width in pixels. |

### LOGIC_HEIGHT (`0x034B`)

| Attribute | Value |
|---|---|
| Addr | `0x034B` |
| Width | 16 |
| Access | RW |
| Reset | `0x01E0` |
| Category | vblank-sensitive |
| Description | Logical source height used by the scaler. |

| Bits | Field | Description |
|---|---|---|
| `[10:0]` | HEIGHT | Logical source height in pixels. |

### INNER_BORDER_L (`0x034C`)

| Attribute | Value |
|---|---|
| Addr | `0x034C` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Inner border thickness on the left edge. |

| Bits | Field | Description |
|---|---|---|
| `[9:0]` | THICKNESS | Left inner border thickness in logical pixels. |

### INNER_BORDER_R (`0x034D`)

| Attribute | Value |
|---|---|
| Addr | `0x034D` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Inner border thickness on the right edge. |

| Bits | Field | Description |
|---|---|---|
| `[9:0]` | THICKNESS | Right inner border thickness in logical pixels. |

### INNER_BORDER_T (`0x034E`)

| Attribute | Value |
|---|---|
| Addr | `0x034E` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Inner border thickness on the top edge. |

| Bits | Field | Description |
|---|---|---|
| `[9:0]` | THICKNESS | Top inner border thickness in logical pixels. |

### INNER_BORDER_B (`0x034F`)

| Attribute | Value |
|---|---|
| Addr | `0x034F` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Inner border thickness on the bottom edge. |

| Bits | Field | Description |
|---|---|---|
| `[9:0]` | THICKNESS | Bottom inner border thickness in logical pixels. |

### BITMAP_CTRL (`0x0350`)

| Attribute | Value |
|---|---|
| Addr | `0x0350` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | Enables SDRAM bitmap fetch and selects bitmap format. |

| Bits | Field | Description |
|---|---|---|
| `[0]` | ENABLE | Enables SDRAM bitmap fetch. |
| `[2:1]` | BPP | Bitmap bits-per-pixel mode selector; 0b10 selects RGB565 direct color. |
| `[6:3]` | CELL_WIDTH_LOG2 | Log2 cell width for indexed bitmap addressing. |

### BITMAP_BASE_LO (`0x0351`)

| Attribute | Value |
|---|---|
| Addr | `0x0351` |
| Width | 16 |
| Access | RW |
| Reset | `0x3000` |
| Category | vblank-sensitive |
| Description | Low 16 bits of the SDRAM bitmap byte-plane base address. In RGB565 direct-color mode (BITMAP_CTRL mode 0b10) the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode. |

### BITMAP_BASE_HI (`0x0352`)

| Attribute | Value |
|---|---|
| Addr | `0x0352` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | High 7 bits of the SDRAM bitmap byte-plane base address. Combined with BITMAP_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode. |

| Bits | Field | Description |
|---|---|---|
| `[6:0]` | ADDR_HI | Address bits 22:16 for bitmap base. |

### ATTR_BASE_LO (`0x0353`)

| Attribute | Value |
|---|---|
| Addr | `0x0353` |
| Width | 16 |
| Access | RW |
| Reset | `0x4000` |
| Category | vblank-sensitive |
| Description | Low 16 bits of the SDRAM attribute or high-byte plane base address. In RGB565 direct-color mode the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode. |

### ATTR_BASE_HI (`0x0354`)

| Attribute | Value |
|---|---|
| Addr | `0x0354` |
| Width | 16 |
| Access | RW |
| Reset | `0x0000` |
| Category | vblank-sensitive |
| Description | High 7 bits of the SDRAM attribute or high-byte plane base address. Combined with ATTR_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode. |

| Bits | Field | Description |
|---|---|---|
| `[6:0]` | ADDR_HI | Address bits 22:16 for attribute or high-byte plane base. |

### BITMAP_STRIDE (`0x0355`)

| Attribute | Value |
|---|---|
| Addr | `0x0355` |
| Width | 16 |
| Access | RW |
| Reset | `0x0200` |
| Category | vblank-sensitive |
| Description | Direct-color bitmap byte-plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned. |

### ATTR_STRIDE (`0x0356`)

| Attribute | Value |
|---|---|
| Addr | `0x0356` |
| Width | 16 |
| Access | RW |
| Reset | `0x0200` |
| Category | vblank-sensitive |
| Description | Direct-color attribute or high-byte plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned. |

### BITMAP_HEIGHT (`0x0357`)

| Attribute | Value |
|---|---|
| Addr | `0x0357` |
| Width | 16 |
| Access | RW |
| Reset | `0x00F0` |
| Category | vblank-sensitive |
| Description | Source bitmap height in rows; currently consumed by init-fill path only. |

| Bits | Field | Description |
|---|---|---|
| `[10:0]` | HEIGHT | Source bitmap height in rows. |


### PALETTE_DATA (`0x0600`)

| Attribute | Value |
|---|---|
| Addr | `0x0600` |
| Width | 16 |
| Access | W (write-only via register bus; commits 24-bit entry after the high half) |
| Reset | — |
| Category | immediate |
| Description | Writes one 16-bit half of a palette entry and auto-increments `PALETTE_PTR`. |

| Bits | Field | Description |
|---|---|---|
| `[15:0]` | HALF | See write sequence below. |

**Write sequence:**

1. Write `PALETTE_PTR = entry_index * 2` to set the half-pointer to the **low half** of the entry.
2. Write `PALETTE_DATA = (G << 8) | B` (even pointer). This stores `{G,B}` in the accumulator and increments the pointer.
3. Write `PALETTE_DATA = R` (odd pointer). This commits the 24-bit `{R,G,B}` value into `palette[entry_index]` and increments the pointer.

After step 3 the pointer points to the low half of `entry_index + 1`, so bulk uploads can continue with back-to-back `PALETTE_DATA` writes.

> [!NOTE]
> Pointer units are **half-entries** (one byte of the 24-bit color). Valid starting values are even byte offsets `0, 2, 4, …, 254` for entries `0..127`. The pointer wraps modulo 256.

### PALETTE_PTR (`0x0601`)

| Attribute | Value |
|---|---|
| Addr | `0x0601` |
| Width | 16 |
| Access | W |
| Reset | `0x0000` |
| Category | immediate |
| Description | Sets the internal half-pointer used by the next `PALETTE_DATA` write. |

| Bits | Field | Description |
|---|---|---|
| `[7:0]` | PTR | Half-pointer. `entry * 2` selects the low half of `entry`; `entry * 2 + 1` selects the high half. |

### 3.2 Allocation rules

- Any new task that adds register addresses MUST reserve a contiguous block in its artifact and reference that block here via a commit touching this spec.
- Single-register additions outside a task's reserved block are forbidden — pick up a reserved range or open 32a (or a named extension of it) to claim one.
- Task 32b refactor MAY rename the existing HDL signals but MUST NOT change any address above.

---

## 4. Semantics

### 4.1 Commit Boundaries

Most registers are **double-buffered**. Host writes go to a "prepare" (shadow) register. The "commit" to the live RTL register occurs at a specific boundary to prevent visual tearing or logic glitches.

| Category | Commit Boundary | Examples |
|---|---|---|
| **Immediate** | Combinational / next-cycle | `UPLOAD_STATUS_CLEAR`, `DMA_CTRL.go` |
| **H-Boundary** | `hCounter === 0` | `LAYER_ENABLE`, `BORDER_CTRL`, `WIN*_X0`, `BITMAP_CTRL` |
| **V-Boundary** | `vSyncStart && hCounter === 0` (or `vCounter === 0 && hCounter === 0`) | `MODE_SELECT`, `LOGIC_WIDTH`, `COPPER_SWAP_REQUEST` |
| **Mixed** | Per-bit boundary (see detail table) | `VDP_CTRL` (`COPPER_ENABLE` is H-boundary, `COPPER_SWAP_REQUEST` is V-boundary) |

### 4.2 Write-1-to-Clear (W1C)

Registers marked W1C (e.g. `STATUS_STICKY` @ `0x0320`) are used to clear sticky event bits. 
- Writing a `1` to a bit position clears that bit.
- Writing a `0` to a bit position has no effect.
- If an event occurs in the SAME cycle as a clear write, the **event wins** (the bit stays/becomes 1).

---

## 5. READ_STATUS Response (Companion)

Host `READ_STATUS` returns a 32-bit word over the active host transport (i80 opcode `0x01`, or the legacy QSPI status command). The `sel` byte in the command picks the word:

| sel | Response Word [31:0] |
|---|---|
| `0` | Magic `0x51560002` (host transport ID) |
| `1..3` | **Removed** (Sc1..Sc4 legacy debug readback) |
| `4` | committed live mode (post-safe-boundary `MODE_SELECT` and layer state) |
| `5` | sticky status bits (`STATUS_STICKY` bit layout, §3.1.1) |
| `6` | upload status (`busy`/`done`/`error`/`overflow`/`txn_dropped` bits, §3.1.2) |
| `7` | **Reserved** — future diagnostic |
| `8` | SDRAM readback — 32-bit word from debug address (0x0326/0x0327) |
| `9..255` | Reserved — zero response |

Task 35 status registers MUST be readable both by mapping into this sel table (extending to sel=5+) AND by appearing in the allocated `0x0320..0x032F` write-path block for clear-on-write semantics.

---

## 6. Naming Conventions

Lock: use the prefixes below when adding new register addresses.

| Prefix | Domain |
|---|---|
| `VDP_*` | Global Mode0 control (e.g. `VDP_CTRL`, `VDP_TILE_MODE`, `VDP_ATTR_MODE`) |
| `WIN*_` | Window unit coordinates / control |
| `BORDER_` | Border window / unit control |
| `AFFINE_` | Affine background transformation matrix / control |
| `TRIGGER*_` | Raster trigger unit configuration |
| `DMA_` | DMA engine control / status |
| `BLIT_` | Blitter engine control / status |
| `BITMAP_` | Bitmap-fetch unit control / status |
| `ATTR_` | Attribute-fetch unit control / status |
| `STATUS_` | Interrupts and event flags |

---

## 7. Change History (v1.1+)

### v1.1 — Affine Support
Added `AFFINE_A..CTRL` (`0x0340..0x0346`). Claims part of the global expansion block.

### v1.2 — RGB565 Transition
Commit `8b61a2e` stripped the dedicated Task 2b RGB565 path in favor of the unified Task 44 fetcher. This initially deprecated `0x0351..0x0356`.

### v1.3 — ACK/NAK Phase 2
Added `UPLOAD_STATUS_CLEAR` (`0x0323`) for host-side recovery. Defined `txn_dropped` bit in `sel=6`.

### v1.4 — Task 129 Restoration
Un-deprecated `0x0351..0x0356` as part of BITMAP-PLUMB-129. These registers are now ACTIVE and parameterize the bitmap/attribute base and stride. Added `0x0357 BITMAP_HEIGHT`.

### v1.6 — Auto-Generated Register Tables
Added §3.1.3 with per-register detail tables generated from `firmware/libvdp/mode0_regs.json` via `scripts/gen_reg_docs.py`. BronzeGate backfilled all register/field descriptions and normalized categories to `immediate` / `vblank-sensitive` / `stream` / `diagnostic`; tables regenerated to remove `*TBD*` placeholders.

---

## 8. Migration Notes for `libvdp`

- **Scenario 45+**: Stop using Sc1..Sc4 debug selectors (sel=1..3); transition to sel=5 (`STATUS_STICKY`).
- **Phase 2**: Use `vdp_qspi_upload_status()` to poll for `txn_dropped`. Clear via `vdp_reg_write(0x0323, 1 << 4)`.
- **Task 129**: Bitmap base/stride/height are now host-programmable. Default reset values preserve legacy 0x3000/0x4000 layout.

---

## 9. Open questions deferred to later tasks

- **§2.2:** Copper-lite master priority relative to host / animator — Task 33 artifact.
- **§3.1:** Palette bank addressing (currently hardcoded in `VdpTop.scala:755+`) — future task if palette animation moves to host control.
- **§5:** Status register clear semantics (write-1-to-clear vs read-to-clear vs auto-clear) — Task 35 artifact.
- **§7.1:** If Task 34 bulk asset upload needs a sideband write register (e.g. `ASSET_ADDR` pointer), its placement at `0x0350..0x035F` is suggested but not locked.
- **§7.2:** Task 19 Affine Background registers (`0x0340..0x0346`) were omitted in v1.0; corrected in v1.1. Task 33 Copper-lite relocated from `0x0340..0x034F` (erroneous) to `0x0380..0x03DF`.
- **Sprite descriptor capacity:** Approved redesign target is `descCount=32`, `visiblePerLine=8` (BrightForge #10360). The existing `0x0800..0x0FFF` Task 37 descriptor space (2048 words) is already sized for this target; no register-address changes are required. Live build remains `descCount=8` pending merge.

---
