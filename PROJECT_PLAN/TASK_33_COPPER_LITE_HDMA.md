# Task 33 — Copper-lite / HDMA Automator

**Status:** Artifact phase
**depends_on:** [32a]
**scope_boundary:** Beam-synchronous micro-engine only. No new fetch engines, no new output stages, no new rendering primitives.
**delivers:**

- Wait-for-beam-position + write-selected-register engine
- Optional table-driven value reload
- Palette-bank or palette-entry reload actions
- Amiga Copper-style wait/move and SNES HDMA-style per-line updates

**validation:**

- Sim: copper script produces expected raster splits and color bars
- Hardware: visible raster effects on Tang Nano 20K

---

## 1. Goal

Extend the existing R5 Copper coprocessor into a dual-mode engine that supports both:
1. **Amiga Copper-style** programmable wait/move scripts (existing functionality, hardened)
2. **SNES HDMA-style** per-line table-driven register updates (new — automatic per-frame reload)

The HDMA mode enables effects like color gradients, parallax scroll changes, and palette bank switches every scanline without host CPU intervention.

## 2. Scope

### 2.1 In scope

1. **HDMA table RAM** — 128 × 32-bit entries storing `{target_line[9:0], reg_addr[15:0], reg_data[15:0]}`
2. **HDMA engine** — auto-executes table entries each frame at the specified lines
3. **Palette reload path** — fast palette-bank switch or palette-entry update via HDMA
4. **Host control registers** — HDMA enable, table base, length, mode select
5. **Integration with Mode0RegBus** — Copper/HDMA becomes a formal bus master (post-Task 32b)
6. **Sim scenario** — HDMA-driven color gradient + raster split

### 2.2 Out of scope (deferred)

- DMA-from-SDRAM for HDMA tables (tables stored in on-chip RAM only)
- HDMA indirect mode (pointer chains)
- Copper horizontal-position waits (current Copper waits on `vCounter` only at `hCounter==0`)
- New compositor math or blending modes

## 3. Architecture

### 3.1 Current state (R5 Copper)

The existing `Copper.scala` has:
- 512 × 16-bit program RAM
- WAIT/WRITE/WRITE_SEQ/JUMP instructions
- Outputs `regAddr`, `regData`, `regWr` through a drain FIFO to VdpTop
- Program uploaded via `progAddr/progData/progWr` when `enabled == False`

### 3.2 Target state (Copper-lite + HDMA)

```
Copper.scala (extended):
  ┌─────────────────┐
  │  Program RAM    │ 512 × 16 (existing Copper script)
  │  (existing)     │
  └────────┬────────┘
           │
  ┌────────▼────────┐
  │  Copper FSM     │ WAIT / WRITE / WRITE_SEQ / JUMP (existing)
  │  (existing)     │
  └────────┬────────┘
           │ regWr / regAddr / regData
           ▼
  ┌─────────────────┐
  │  HDMA Engine    │ NEW: reads HDMA table, fires writes at line matches
  │  (new)          │
  └────────┬────────┘
           │
  ┌────────▼────────┐
  │  Priority Mux   │ Copper script wins over HDMA if both fire same cycle
  │  (new)          │
  └────────┬────────┘
           │ Mode0RegBus master output
           ▼
        RegBusArbiter
```

### 3.3 HDMA table format

Each entry is 32 bits:
```
[31]     = valid bit (1 = active, 0 = end-of-table)
[30:24]  = reserved
[23:16]  = target line (0..255 for visible lines, 255 = disabled)
[15:0]   = register data (written to the register address)
```

Wait, this doesn't include the register address. Let me reconsider:

HDMA can work in two modes:
- **Direct mode:** each entry has `{line, addr, data}` — 48 bits, larger table
- **Indirect mode:** table has `{line, data}` only, address is implied by the channel — 32 bits, simpler

For Copper-lite, I propose **channel-based indirect mode** (SNES-style):
- 8 HDMA channels, each with a fixed target register address
- Each channel has its own small table (up to 16 entries)
- Table entry: `{valid[1], line[8], data[16]}` = 25 bits, stored in 32-bit words

This is much simpler and covers the common cases:
- Ch0: scrollX for L0
- Ch1: scrollX for L1
- Ch2: palette bank
- Ch3: layer enable
- Ch4-7: user-defined

### 3.4 Host control registers

| Address | Name | Purpose |
|---|---|---|
| `0x0380` | `HDMA_CTRL` | `data[0]=enable`, `data[3:1]=active channels mask` |
| `0x0381` | `HDMA_STATUS` | `data[0]=done` (sticky, auto-cleared at frame start) |
| `0x0382` | `HDMA_CH0_ADDR` | Channel 0 target register address |
| `0x0383` | `HDMA_CH0_LEN` | Channel 0 table length (0..16) |
| `0x0384..0x0393` | `HDMA_CH0_TABLE[0..15]` | Channel 0 table entries |
| `0x0394..0x03AF` | `HDMA_CH1..CH7` config | Same pattern as CH0 |

### 3.5 Copper as bus master

Post-Task 32b, the `Mode0RegBus` is the standard interface. The Copper/HDMA block should:
- Output a `Mode0RegBus` master interface
- Be connected to `RegBusArbiter` as master index 3 (priority below bootstrap/QSPI/animator)
- The existing `copperFifo` drain mechanism remains for safe-boundary commit

## 4. Validation Plan

### 4.1 Simulation

**`CopperHdmaSim` (new):**
1. Load HDMA table with 8 entries: line 0 = red, line 60 = green, line 120 = blue, line 180 = red
2. Enable HDMA
3. Run for 4 frames
4. Assert that register writes occur at the correct lines each frame
5. Assert auto-repeat (table re-executes from top each frame)

**`CopperLiteSim` (new or extend existing `CopperSim`):**
1. Copper script: WAIT line 100, WRITE scrollX = 0x10, WAIT line 200, WRITE scrollX = 0x20
2. Assert writes happen at correct lines with hCounter==0 safe boundary

### 4.2 Hardware proof

- Build bitstream with HDMA-enabled scene
- Effect: 4 horizontal color bands (red/green/blue/red) created by HDMA palette-bank switches
- 30-second HDMI capture + OpenCV analysis confirms stable band positions
- Compare with pre-HDMA baseline — distinct visible delta

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| HDMA table too large for on-chip RAM | Resource exhaustion | 128-entry max, 32-bit each = 512 bytes |
| HDMA + Copper script collision | Double-write same cycle | Priority mux: Copper script wins over HDMA |
| HDMA line-match jitter | Visible band wobble | Match at hCounter==0 (safe boundary) |
| Mode0RegBus integration breaks existing sims | Regression | Run full sim suite after wiring |
| Host uploads table while HDMA active | Corruption | Disable HDMA during table upload (host protocol) |

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** HDL — HDMA table RAM + engine + channel config + Mode0RegBus master integration + sims
- **C:** hardware proof — visible raster bands on Tang Nano 20K

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `Copper.scala` +40 lines (HDMA FSM + table RAM); `VdpTop.scala` +20 lines (HDMA registers + wiring); `TopTang20kHdmi.scala` +5 lines (arbiter masterCount=4); new sim ~80 lines |
| **Hardware target** | Tang Nano 20K (Gowin GW2AR-LV18) |
| **Dependencies** | `MODE0_REGISTER_BUS_SPEC.md` v1.0+ (Task 32a), Task 32b refactor for bus master integration |

## 8. Open Questions (for implementation to resolve)

1. **HDMA table entry width:** 32-bit `{valid, line, data}` with channel-implied address, or 48-bit `{valid, line, addr, data}`? Recommend 32-bit channel-implied for RAM efficiency.
2. **Channel count:** 8 channels (SNES-style) or fewer? Recommend 4 channels for first proof, expand to 8 if resource allows.
3. **Copper priority:** Should Copper script have higher priority than HDMA, or vice versa? Recommend Copper script > HDMA (script is more explicit/urgent).
4. **Frame-start trigger:** Should HDMA auto-start at vCounter=0 each frame, or require host to set a "go" bit? Recommend auto-start when enabled — true HDMA behavior.
