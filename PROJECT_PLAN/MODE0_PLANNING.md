# MODE0_PLANNING.md

**Updated:** 2026-05-15
**Purpose:** Current `Mode0-T20` profile specification for the Tang20k-targeted Mode0 build.

---

## 1. Ceiling Statement

| Item | Value |
|---|---|
| Ceiling class | Amiga + SNES |
| Explicitly removed as ceiling driver | Neo Geo |
| Profile type | Tang20k-scoped implementation profile |

---

## 2. Guaranteed Feature Summary

| Feature | Guaranteed |
|---|---|
| Progressive raster output | `1` |
| Strong live background layers | `2` |
| Architectural maximum background layers | `4` |
| Visible sprites per scanline | `8` |
| Guaranteed descriptor count | `8` |
| Tile + attribute fetch | `1` |
| Bitmap fetch | `1` |
| Planar / bitplane fetch | `1` |
| Raster trigger | `1` |
| Beam-synchronous automation | `1` |
| Windowing / clipping | `1` |
| Basic color math | `1` |
| Basic blitter / transfer | `1` |

---

## 3. Guaranteed Limits

| Parameter | Guaranteed Value |
|---|---|
| Scan model | Progressive |
| Strong live BG layers | 2 |
| Architectural max BG layers | 4 |
| Visible sprites / scanline | 8 |
| Descriptor count | 8 |
| Guaranteed planar depth | 4 planes |
| Raster compare units | 1 |
| Per-line register update support | `1` |

---

## 4. Optional Build Features

| Feature | Status |
|---|---|
| L2/L3 richer live use | `build-gated` |
| Affine / Mode7 path | `build-gated` |
| Expanded sprite count | `build-gated` |
| Deeper planar support | `separately-proven` |
| Advanced blitter ops | `deferred` |
| HAM / EHB style decode | `deferred` |

---

## 5. Unsupported Base-Profile Features

| Feature | Status |
|---|---|
| Neo Geo-class sprite density | `0` |
| Neo Geo-class descriptor pressure | `0` |
| Hardware zoom/shrink as base requirement | `0` |
| Cycle-exact legacy DMA timing | `0` |
| Interlace as core requirement | `0` |
| Everything-enabled-at-once default build | `0` |

---

## 6. Current Canonical Register Map

### 6.1 Global / Status

| Address | Name |
|---|---|
| `0x0300` | `LAYER_ENABLE` |
| `0x0310` | `VDP_CTRL` |
| `0x0311` | `VDP_TILE_MODE` |
| `0x0312` | `VDP_ATTR_MODE` |
| `0x0313` | `MODE_SELECT` |
| `0x0320` | `STATUS_STICKY` |
| `0x0321` | `STATUS_ENABLE` |
| `0x0322` | `SPRITE_COLL_MASK` |

### 6.2 Window / Color / Border / Affine

| Address | Name |
|---|---|
| `0x0330` | `WIN1_X0` |
| `0x0331` | `WIN1_X1` |
| `0x0332` | `WIN1_Y0` |
| `0x0333` | `WIN1_Y1` |
| `0x0334` | `COLOR_MATH_CTRL` |
| `0x0335` | `WIN2_X0` |
| `0x0336` | `WIN2_X1` |
| `0x0337` | `WIN2_Y0` |
| `0x0338` | `WIN2_Y1` |
| `0x0339` | `WIN2_CTRL` |
| `0x033A` | `WIN_COMBINE` |
| `0x033B` | `LAYER_MASK` |
| `0x033C` | `BORDER_X0` |
| `0x033D` | `BORDER_X1` |
| `0x033E` | `BORDER_Y0` |
| `0x033F` | `BORDER_Y1` |
| `0x0340` | `AFFINE_A` |
| `0x0341` | `AFFINE_B` |
| `0x0342` | `AFFINE_C` |
| `0x0343` | `AFFINE_D` |
| `0x0344` | `AFFINE_X` |
| `0x0345` | `AFFINE_Y` |
| `0x0346` | `AFFINE_CTRL` |
| `0x0347` | `BORDER_CTRL` |

### 6.3 Bitmap Fetch

| Address | Name |
|---|---|
| `0x0350` | `BITMAP_CTRL` |
| `0x0351` | `BITMAP_BASE_LO` |
| `0x0352` | `BITMAP_BASE_HI` |
| `0x0353` | `ATTR_BASE_LO` |
| `0x0354` | `ATTR_BASE_HI` |
| `0x0355` | `BITMAP_STRIDE` |
| `0x0356` | `ATTR_STRIDE` |

### 6.4 Raster Triggers

| Address | Name |
|---|---|
| `0x0360` | `TRIGGER1_LINE` |
| `0x0361` | `TRIGGER1_PIXEL` |
| `0x0362` | `TRIGGER1_CTRL` |
| `0x0364` | `TRIGGER2_LINE` |
| `0x0365` | `TRIGGER2_PIXEL` |
| `0x0366` | `TRIGGER2_CTRL` |
| `0x0368` | `TRIGGER3_LINE` |
| `0x0369` | `TRIGGER3_PIXEL` |
| `0x036A` | `TRIGGER3_CTRL` |

### 6.5 HDMA / Copper / Palette / Tables

| Address | Name |
|---|---|
| `0x0380` | `HDMA_BASE` |
| `0x0400` | `COPPER_RAM_BASE` |
| `0x0600` | `PALETTE_DATA` |
| `0x0601` | `PALETTE_PTR` |
| `0x0A00` | `VSCROLL_BASE` |

### 6.6 DMA / Blitter

| Address | Name |
|---|---|
| `0x0B00` | `DMA_DST` |
| `0x0B01` | `DMA_LEN` |
| `0x0B02` | `DMA_FILL` |
| `0x0B03` | `DMA_CTRL` |
| `0x0B10` | `DMA_STAGING_BASE` |
| `0x0C00` | `BLIT_CTRL` |
| `0x0C01` | `BLIT_WIDTH` |
| `0x0C02` | `BLIT_HEIGHT` |
| `0x0C03` | `BLIT_DST_ADDR` |
| `0x0C04` | `BLIT_DST_STRIDE` |
| `0x0C05` | `BLIT_SRC_ADDR` |
| `0x0C06` | `BLIT_SRC_STRIDE` |
| `0x0C07` | `BLIT_FILL_VAL` |
| `0x0C10` | `BLIT_SRC_RAM_BASE` |

### 6.7 Linestate / Adapter Pages

| Address | Name |
|---|---|
| `0x0000` | `LINESTATE_BASE` |
| `0x0E00..0x0EFF` | `ADAPTER_PAGE_1` |
| `0x0F00..0x0FFF` | `ADAPTER_PAGE_2` |

---

## 7. Status Selectors

| Selector | Meaning |
|---|---|
| `0` | magic |
| `1` | `rx_cmd_cnt` |
| `2` | `last_addr` |
| `3` | `last_data` |
| `4` | `last_error` |
| `5` | sticky bits |
| `6` | upload status |
| `7` | committed live mode |

---

## 8. Conformance Rules

| Rule | Meaning |
|---|---|
| Required feature missing | `non-conforming` |
| Optional feature absent | `conforming` |
| Numeric limit exceeded | `out-of-profile` |
| Unsupported behavior requested | `reject-or-defer` |

---

## 9. Current Quantified Behavior

### 10.1 Clock / Output

| Parameter | Current Value | Source |
|---|---|---|
| Board input clock | `27 MHz` | `PLATFORM.md` |
| Pixel clock | `25.2 MHz` | `PLATFORM.md` |
| Serializer clock | `5x pixel clock` | `PLATFORM.md` |
| Scan model | Progressive | `MODE0_PLANNING.md` §4, `PLATFORM.md` |
| Active visible raster | `640x480` | `README.md`, `PLATFORM.md` |
| Output transport | DVI-compatible TMDS over HDMI connector | `PLATFORM.md` |

### 10.2 Register Bus

| Parameter | Current Value | Source |
|---|---|---|
| Address width | `15 bits` | `MODE0_REGISTER_BUS_SPEC.md` §1 |
| Data width | `16 bits` | `MODE0_REGISTER_BUS_SPEC.md` §1 |
| Write enable shape | `1 pixel-clock cycle pulse` | `MODE0_REGISTER_BUS_SPEC.md` §1 |
| Ack path | None | `MODE0_REGISTER_BUS_SPEC.md` §4.3 |
| Standard register commit boundary | `hCounter === 0` | `MODE0_REGISTER_BUS_SPEC.md` §4.1 |
| Linestate commit exception | `hCounter === hTotal - 1` | `MODE0_REGISTER_BUS_SPEC.md` §4.1 |
| Current master priority | `bootstrap > QSPI > animator` | `MODE0_REGISTER_BUS_SPEC.md` §2.2 |
| Same-cycle lower-priority write result | Dropped | `MODE0_REGISTER_BUS_SPEC.md` §4.2 |

### 10.3 Host-Write Visible Effect Boundary

| Parameter | Current Value | Source |
|---|---|---|
| Earliest visible effect for standard global register writes | Start of next scanline after `hCounter === 0` commit | `MODE0_REGISTER_BUS_SPEC.md` §4.1 |
| Mid-line visible effect from host register write | Not supported by current write-path contract | `MODE0_REGISTER_BUS_SPEC.md` §4.1 |

### 10.4 Layer Service Model

| Parameter | Current Value | Source |
|---|---|---|
| Guaranteed strong live background layers | `2` | `MODE0_PLANNING.md` §4 |
| Architectural maximum background layers | `4` | `MODE0_PLANNING.md` §4 |
| Guaranteed core fetch formats | `tile+attribute`, `bitmap`, `planar/bitplane` | `MODE0_PLANNING.md` §3 |
| Richer L2/L3 live use | Optional / build-gated | `MODE0_PLANNING.md` §5 |

### 10.5 Current Fetch-Slot Priority

| Parameter | Current Value | Source |
|---|---|---|
| Scheduler slot count | `8` | `VdpTop.scala` |
| Current overlap resolution rule | Lowest slot index wins | `VdpTop.scala` comment at Task 56 scheduler block |
| Current effective fetch priority under overlap | `Planar (slot 2) > L0 (slots 0/1) > L1 (slots 3/4)` | `VdpTop.scala` comment at Task 56 scheduler block |

### 10.6 Sprite Capacity / Overflow

| Parameter | Current Value | Source |
|---|---|---|
| Guaranteed visible sprites per scanline | `8` | `MODE0_PLANNING.md` §4 |
| Guaranteed descriptor count | `8` | `MODE0_PLANNING.md` §4 |
| Overflow status surface | `STATUS_STICKY[1] = SPRITE_OVERFLOW` | `MODE0_REGISTER_BUS_SPEC.md` §3.1.1 |
| Current selection rule when over capacity | Lowest descriptor indices retained first | `SpriteCapacitySim.scala` comments / assertions |

### 10.7 DMA / Blitter Surfaces

| Parameter | Current Value | Source |
|---|---|---|
| DMA done sticky bit | `STATUS_STICKY[8]` | `MODE0_REGISTER_BUS_SPEC.md` §3.1.1 |
| Blitter done sticky bit | `STATUS_STICKY[9]` | `MODE0_REGISTER_BUS_SPEC.md` §3.1.1 |
| Blitter busy live status bit | `STATUS bit 10`, read-only, not sticky | `MODE0_REGISTER_BUS_SPEC.md` §3.1.1 |
| DMA staging buffer size | `64 x 16-bit words` | `MODE0_REGISTER_BUS_SPEC.md` §3.1 |
| Blitter source/store RAM size | `512 x 16-bit words` | `MODE0_REGISTER_BUS_SPEC.md` §3.1 |

### 10.8 QSPI / Host Transport

| Parameter | Current Value | Source |
|---|---|---|
| Header format | `6 bytes` = `[CMD:1][ADDR:3][LEN:2]` | `PLATFORM.md` |
| Commands | `0x01 REG_WRITE`, `0x02 SDRAM_WRITE`, `0x04 READ_STATUS` | `PLATFORM.md` |
| Proven SCK | `2 MHz` | `PLATFORM.md` |
| Unverified ceiling | `5 MHz` | `PLATFORM.md` |

### 10.9 Current CDC Facts

| Parameter | Current Value | Source |
|---|---|---|
| Validated separate `clkSys` domain in active use | None | `PLATFORM.md` |
| Register-bus clock domain | Pixel clock | `MODE0_REGISTER_BUS_SPEC.md` §1 |
| Documented host-side queue crossing model | QSPI-side queue / FIFO into pixel-domain parser | `TECH_SPEC_HOST_INTERFACE_AND_COPPER.md` |
| Example explicit CDC primitive in fetch path | `BufferCC` used on planar base-address sampling | `BitplaneRowFetch.scala` |

### 10.10 Default-Build Reduction Plan

| Parameter | Current Value | Source |
|---|---|---|
| First-pass cut `1` | `enableTestPattern = false` | `#10011`, `#10016`, `#10017` |
| First-pass cut `2` | `planeCount = 4` | `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |
| First-pass cut `3` | `enableAffine = false` | `#10016`, `#10018`, `#10019` |
| First-pass cut `4` | `enableExtraRasterTriggers = false` | `#10016`, `#10018`, `#10019` |
| Keep in first pass | `L0 + L1` | `#10011`, `#10017`, `#10018` |
| Defer in first pass | `L2/L3` gating | `#10011`, `#10017`, `#10018`, `#10019` |
| Defer in first pass | `DMA engine` gating | `#10011`, `#10017`, `#10019` |
| Defer in first pass | `HDMA table / extra automation` gating | `#10011`, `#10017`, `#10019` |
| Synthesis rule | `run synthesis after each cut` | `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |
| Stop rule | `revert immediately on LUT/DFF regression` | `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |

### 10.11 Current Default-Build Excess vs Spec

| Parameter | Current Value | Source |
|---|---|---|
| Live background layers in default build | `4` | `#10011`, `#10016`, `#10017` |
| Guaranteed strong live background layers in spec | `2` | `MODE0_PLANNING.md` §3, `#10011`, `#10017` |
| Current planar depth in default build | `5` | `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |
| Guaranteed planar depth in spec | `4` | `MODE0_PLANNING.md` §3 |
| Current raster trigger units in default build | `4` | `#10018`, `#10019` |
| Guaranteed raster compare units in spec | `1` | `MODE0_PLANNING.md` §3 |
| Affine unit in default build | `1` | `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |
| Affine unit in spec default build | `build-gated` | `MODE0_PLANNING.md` §4 |

### 10.12 Default-Build Regression Constraint

| Parameter | Current Value | Source |
|---|---|---|
| Proven risk class for Mem/topology cuts | `high` | `#10011`, `#10017`, `#10018`, `#10019` |
| Documented prior failure pattern | `Mem -> FF promotion / non-local LUT increase` | `#9923`, `#9929`, `#9967`, `#9976`, `#9981` |
| Current allowed high-risk cuts in first pass | `0` | converged recommendation from `#10011`, `#10016`, `#10017`, `#10018`, `#10019` |

---
