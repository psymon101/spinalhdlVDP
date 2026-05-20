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

| Feature | Limit / Value |
|---|---|
| Background Layers | 2 (Strong) / 4 (Max) |
| Sprites / Scanline | 8 |
| Descriptor Count | 8 |
| Planar Depth | 4 planes |
| Raster Compare | 1 unit |
| Fetch Formats | Tile+Attr, Bitmap, Planar |
| Automation | Beam-sync, Basic Blitter, HDMA, Copper |
| Clipping | 1 Window |

## 3. Optional Build Features (Build-Gated)

- L2/L3 rich layers
- Affine / Mode7 path
- Expanded sprite count (32/line) — **deferred; live instantiation is 8/8**
- Deeper planar support

---

## 4. Visual Fidelity Policy (Governing)

This profile operates under the **Visual Fidelity Priority** ruling (#10301).
- **Primary Goal:** Authoritative visible output (palette, timing, layering).
- **Secondary Goal:** Platform-native internal mechanics.
- **Substrate Ceiling:** SNES-class sprite/layer capacity is the preferred baseline.

---

## 5. Canonical Register Map

The register map is canonically defined and maintained in **`PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3**.
- **Global Control:** `0x0300..0x031F`
- **Window/Color/Affine:** `0x0330..0x034F`
- **Fetch/Raster:** `0x0350..0x037F`
- **Automation/Tables:** `0x0380..0x0AFF`
- **DMA/Blitter:** `0x0B00..0x0DFF`
- **Adapter Pages:** `0x0E00..0x0FFF`

---

## 6. Current Quantified Behavior (Mode0-T20 Baseline)

### 6.1 Clock & Output Transport
- **Board Input:** `27 MHz`
- **Pixel Clock:** `25.2 MHz` (Progressive 640×480)
- **Transport:** DVI-compatible TMDS over HDMI
- **Source:** `PLATFORM.md`, `README.md`

### 6.2 Register Bus & Timing
- **Signal Contract:** 15-bit Addr / 16-bit Data / 1-cycle Pulse
- **Commit Boundary:** `hCounter === 0` (standard) / `hCounter === hTotal - 1` (linestate)
- **Master Priority:** `bootstrap > QSPI > animator`
- **Source:** `MODE0_REGISTER_BUS_SPEC.md`

### 6.3 Layer & Fetch Capabilities
- **Guaranteed Backgrounds:** 2 Strong (L0/L1) / 4 Maximum
- **Fetch Formats:** Tile+Attribute, Bitmap, Planar (up to 5 planes)
- **Slot Allocation:** 8 slots total; `Planar > L0 > L1` priority
- **Source:** `MODE0_PLANNING.md` §2, `VdpTop.scala`

### 6.4 Object & Composition Limits
- **Sprites:** 8 visible/scanline; 8 total descriptors
- **Collision:** Sprite-0-hit + Sprite-BG-hit status bits
- **Windowing:** 1 active rectangle (standard) / 2 rectangles (extended)
- **Source:** `MODE0_PLANNING.md` §4, `SpriteCapacitySim.scala`

### 6.5 Automation & DMA Performance
- **Copper:** 512-word dual-bank program RAM; line-paced drain
- **HDMA:** 4 channels; 8-bit line compare (standard) / 9-bit (extended)
- **DMA Staging:** 64-word COPY buffer
- **Blitter Storage:** 512-word SRC/STORE RAM
- **Source:** `MODE0_REGISTER_BUS_SPEC.md` §3.1

---

## 7. Build-Gated Feature Status

| Feature | Default Build | Build-Gated (Optional) | Reference |
|---|---|---|---|
| L2/L3 Layers | **Disabled** | Enabled | #10142 |
| Affine / Mode7 | **Disabled** | Enabled | #10142 |
| Extra Triggers | **Disabled** | Enabled | #10142 |
| Plane Count | `4` | `5` or `6` | #10011 |
| Sprite Density | `8/line` | `32/line` | #10077 |

---

## 8. Development Constraints & Risk Profile
- **Mem Fragility:** High risk of Mem→FF promotion on GW2AR-LV18.
- **FF/LUT Ratio:** Maximum 0.75 per CLS. Regional density is the primary bottleneck.
- **Inference Policy:** Avoid `initialContent` for BSRAM; use readSync for high-fanout Mems.
- **Stop Rule:** Revert immediately on LUT/DFF regression without verified logic elision.

---
