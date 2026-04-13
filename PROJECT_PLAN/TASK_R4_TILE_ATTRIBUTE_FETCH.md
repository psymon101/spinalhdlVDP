# TASK_R4_TILE_ATTRIBUTE_FETCH.md

**Status:** OPEN  
**Created:** 2026-04-13  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef (temp)

---

## 1. Task Name

R4 Tile + Attribute Fetch Primitive

---

## 2. Purpose

Generalize the proven Task-15 SDRAM tile fetcher into a **tile + attribute fetch engine** that can read synchronized tile indices and attribute bytes from SDRAM, decode flexible bit-depth tile data, select from a multi-bank palette, and carry per-pixel metadata flags into the compositor.

**Why now:**
- R3 established the static fetch-slot scheduler but only exercised it with a single end-of-line slot.
- R4 is the natural next step to validate multi-slot scheduling and to unlock the fetch patterns required by NES, C64, Genesis, and SNES background layers.
- The roadmap explicitly notes R3 and R4 are tightly coupled; defining R4 while the scheduler is fresh ensures the fetch engine respects the slot-based bandwidth model.

**Platform pressure:**
- NES: separate tile index and attribute tables with palette bank selection
- Commodore 64: text/bitmap hybrids needing attribute-like color-per-tile
- Genesis / SNES: multi-bank palette selection and per-tile priority

---

## 3. Primitive Boundary

### In Scope

- **Refactor `SdramTileFetch` → `SdramTileAttributeFetch`**
  - Fetch both tile map and a separate attribute map from SDRAM
  - Support 2/4/8bpp tile data structurally; **4bpp is the mandatory proof target** (fits the existing 4-bit compositor path)
  - Attribute byte per tile provides at minimum:
    - **palette bank** (e.g. 3 bits → 8 banks)
    - **priority** metadata flag (1 bit)
  - Multi-bank palette memory (e.g. 8 banks × 16 entries = 128 entries, or 256 entries for 8bpp headroom); must be **power-of-two depth** (GT-022)
- **Scheduler integration**
  - Configure the R3 scheduler with **2–3 slots per line** for the tile+attribute client
  - Prove the fetch engine can start, pause, and resume across multiple scheduled windows
- **Compositor metadata hook**
  - Carry the tile attribute priority bit through the line buffer into `VdpTop`
  - Minimal visible effect: priority determines layer mixing (e.g. layer0 tile with priority=1 wins over layer1)
- **Re-proof of baseline** after all changes

### Explicitly Out of Scope

- NO 2bpp/8bpp hardware proof (infrastructure must support them, but proof scene is 4bpp only)
- NO packed-attribute decode (R4.1b concern — e.g. NES 2×2 tile attribute packing)
- NO scroll-table primitive (R4.2 concern)
- NO sprite-to-SDRAM migration
- NO planar fetch engine (R7 concern)
- NO Copper/HDMA automation (R5 concern)
- NO window or color-math stages (R6 concern)
- NO changes to sprite evaluator logic (R2 complete)
- NO register bus definition (R5.1 concern)

---

## 4. Dependencies

- R3 Static Fetch-Slot Scheduler (proven, commit `fcc3aa6`)
- Task-15 SDRAM L0 path (proven, must remain functional)
- R2 Two-Pass Sprite Evaluator (proven)
- `LinestateStore` with prepare/commit (unchanged interface)

---

## 5. Interfaces

### New / Modified Interfaces

```scala
// Attribute byte layout (per tile)
case class TileAttribute() extends Bundle {
  val paletteBank = UInt(3 bits)  // 0-7
  val priority    = Bool()
  // reserved / flip bits for future tasks
}

// Fetch engine (replaces SdramTileFetch)
case class SdramTileAttributeFetch(sdramCd: ClockDomain) extends Component {
  val io = new Bundle {
    // SDRAM controller interface (same as today)
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    val sdramRefresh   = out Bool()
    val sdramDout      = in  Bits(8 bits)
    val sdramDout32    = in  Bits(32 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()

    // Scheduler-driven fetch control (pixel clock domain)
    val fetchGrant      = in Bool()   // 1-cycle start grant
    val fetchSlotValid  = in Bool()   // true during assigned windows
    val fetchPreAnnounce = in Bool()  // 1 cycle before grant

    val fetchLine       = in UInt(10 bits)
    val fetchScrollX    = in UInt(10 bits)
    val fetchScrollY    = in UInt(10 bits)

    val pixelAddr       = in UInt(10 bits)
    val pixelIndex      = out Bits(4 bits)   // 4bpp proof target
    val pixelPriority   = out Bool()         // metadata from attribute

    val bootDone        = out Bool()
    val memtestPass     = out Bool()
    val memtestFail     = out Bool()
    val underrun        = out Bool()
  }
}
```

### Modified Compositor Path

- `VdpTop` line buffer and consumer path widened to carry `{pixelIndex(4 bits), priority(1 bit)}` — total 5 bits per pixel
- Palette expanded to 128-entry or 256-entry × 24-bit, initialized with multiple distinguishable banks
- `layer0Pixel` and priority bit feed a slightly modified background mixing stage

---

## 6. Data Model

### Persistent (frame-level)

- **Palette RAM**: 128 or 256 entries × 24-bit RGB, initialized content, **power-of-two depth** (GT-022)
- **SDRAM base addresses**: tile map base, attribute map base, tile row base (constants or simple config registers)

### Per-Line

- **Ping-pong line buffer**: 640 × 5 bits (4-bit pixel index + 1-bit priority)
  - *Note:* Uninitialized `Mem`, so non-power-of-two depth (640) is acceptable under GT-022
- **Attribute scratch buffer** (SDRAM domain): small register or RAM holding the attribute bytes for the current line being fetched (~41 tiles)
- **Tile row unpack state**: same structure as today, but decoding 4bpp rows (64 bits = two 32-bit SDRAM words)

### GT-022 Checklist

- [ ] Palette RAM depth is power-of-two
- [ ] Any new initialized lookup tables are power-of-two
- [ ] No new initialized non-power-of-two inferred memories introduced

---

## 7. Timing Model

- **Scheduler slots**: 2–3 slots per line assigned to `clientId = 0` (tile+attribute fetcher)
  - Example: slot 0 at `hTotal - 1` for tile-map + attribute-map burst; slot 1 mid-line for row-data refill
  - Exact H-window positions are configurable via the schedule table
- **Fetch engine behavior**:
  - Starts on `fetchGrant` or `fetchSlotValid` rising edge
  - Issues SDRAM reads only while `fetchSlotValid` is true
  - Pauses at window boundaries, resumes at the next assigned slot
- **Pre-announce**: used to prepare the next SDRAM address before the grant window opens
- **Line buffer fill**: completes by end of line; underrun flag raised if pixel read catches up

---

## 8. Memory / Bandwidth Impact

### SDRAM Bandwidth

- Tile map reads: ~41 bytes/line (unchanged address pattern)
- Attribute map reads: +41 bytes/line
- Tile row reads: same as today in 4bpp (64 bits = two 32-bit words per tile)
- Total increase: roughly +1 small read burst per line for attributes

### On-Chip Resources

- Palette RAM: 256 × 24 bits ≈ 768 bytes → ~1 BSRAM block
- Line buffer widening: 640 × 5 bits vs previous 640 × 3 bits → negligible
- Attribute scratch: ~41 bytes → registers or distributed RAM

### Scheduler Impact

- First real multi-slot client on the scheduler
- Proves that the slot table can pace a single engine across non-contiguous H-windows

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| NES | Core tile+attribute background layer pattern |
| C64 | Text/bitmap hybrid with per-tile color attributes |
| Genesis | Multi-bank palette + priority bits |
| SNES | Palette bank selection hooks for background layers |
| ZX Spectrum | Degenerate 1-bit attribute case (future R4.1b) |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Attribute fetch desyncs from tile row fetch | Buffer attributes in SDRAM domain; lookup by tile counter during unpack |
| Palette bank addressing off-by-one | Dedicated sim case testing each bank boundary |
| Scheduler slot underrun (fetch doesn't finish in windows) | Conservative window sizing + underrun flag telemetry |
| Line buffer width change breaks compositor | Sim proof of full `VdpTopSim` pipeline |
| GT-022 violation on palette | Explicit `require(isPow2(paletteDepth))` or static check |
| Pre-announce not used correctly | Verify address-setup timing in isolated scheduler+fetcher sim |
| 4bpp tile data unpack wrong | Sim case comparing decoded pixels against software reference |

---

## 11. Validation Plan

### Dedicated Sim

New `TileAttributeFetchSim` covering:

1. **Tile map + attribute fetch**: attribute bytes are read and stored correctly per tile
2. **4bpp decode**: a known tile pattern produces correct 4-bit pixel indices
3. **Palette bank selection**: same tile pattern rendered with two different banks produces two different colors
4. **Priority propagation**: priority bit from attribute reaches the compositor output
5. **Multi-slot scheduling**: fetch engine receives grants at 2+ distinct H positions and completes the line
6. **Pause/resume**: fetch engine stops issuing SDRAM reads when `slotValid` drops and resumes at the next slot
7. **Underrun detection**: artificially short slots trigger the underrun flag
8. **Scroll correctness**: scrolled tile+attribute scene matches software reference pixels

### Regression Sims (must rerun)

- `VdpTopSim` — full pipeline still functions with widened line buffer and new palette
- `SpriteEvaluatorSim` — sprites still evaluate correctly
- `RasterTriggerUnitSim` — raster triggers unaffected

*Note:* `SdramTileFetchSim` is replaced by `TileAttributeFetchSim` as the authoritative fetch-engine proof.

### Assertions Required

```scala
// Palette depth is power-of-two
require(isPow2(paletteDepth))

// Grant only during assigned slot windows
assert(io.sdramRd -> fetchSlotValid)

// Priority bit propagates through line buffer
assert(pixelPriority === bufferedAttribute(tileCountReg).priority)
```

---

## 12. Hardware Proof

### Proof Scene: "Palette-Bank Checkerboard"

- 2×2 tile macro-checkerboard where each quadrant uses the **same tile pattern** but a **different palette bank**
- Each bank is initialized with a visually distinct color set (e.g. bank 0 = reds, bank 1 = greens, bank 2 = blues, bank 3 = grayscale)
- Result: four differently colored quadrants despite identical tile graphics — proves attribute-driven palette banking works end-to-end

### Regression Scene

- Mixed sprite+tile scene (same as R2/R3 proof) using palette bank 0 only
- Must show **no visual regression** vs commit `fcc3aa6`

### Optional Metadata Proof

- A second test pattern where some tiles have `priority=1` and others `priority=0`
- Visibly demonstrates the priority bit affecting layer mixing (e.g. priority tiles from L0 appearing in front of L1)

---

## 13. Audit Questions

CyanPeak to verify:

1. **Scope compliance:** Does the implementation stay within the bounded R4.1a slice? No packed-attribute (R4.1b), scroll-table (R4.2), or sprite-SDRAM creep?
2. **GT-022 compliance:** Is every new initialized memory (palette, any LUTs) a power-of-two depth?
3. **Scheduler coupling:** Does the fetch engine actually respect multi-slot `slotValid` windows, or does it ignore the schedule after the first grant?
4. **Attribute synchronization:** Is the attribute byte correctly paired with its tile index during the unpack phase?
5. **Palette bank correctness:** Do the hardware proof colors match the expected bank contents?
6. **Metadata reach:** Does the priority bit from the attribute make it into the compositor output?
7. **Refactor proof:** Do all regression sims (`VdpTopSim`, `SpriteEvaluatorSim`, `RasterTriggerUnitSim`) pass after the line-buffer and palette changes?
8. **Hardware proof:** Does the palette-bank checkerboard render correctly on Tang Nano 20K with no regression in the baseline scene?

---

## 14. Constraints / Gotcha Check

- [x] **GT-022:** Palette RAM depth = power-of-two (128 or 256)
- [x] **GT-022:** Any new initialized attribute LUTs = power-of-two
- [x] **SDRAM-latency awareness:** Fetch engine pauses between slots; no assumption of instantaneous multi-tile burst
- [x] **Interface-stability:** New fetch engine interface compatible with later register bus adoption
- [x] **No hardware before sim:** `TileAttributeFetchSim` must pass before hardware build
- [x] **No mid-line linestate change:** Linestate commit strobe untouched
- [x] **Cleanup before testing:** Old `SdramTileFetch` path removed or cleanly replaced; no hybrid old+new state left active for the proof

---

## 15. Exit Condition

This task is done when the Tile + Attribute Fetch Primitive is integrated with the R3 scheduler using multi-slot fetch windows, all regression sims pass, and a hardware palette-bank checkerboard proves that attributes correctly drive palette selection and metadata reaches the compositor.

---

## Short-Form Summary

```markdown
## Task
R4 Tile + Attribute Fetch Primitive

## Purpose
Generalize the SDRAM tile fetcher to read tile index + attribute pairs, support
multi-bank palette selection, and carry per-pixel metadata into the compositor.
Exercise the R3 scheduler with a real multi-slot client.

## Scope
- in scope: `SdramTileFetch` → `SdramTileAttributeFetch` refactor
- in scope: Separate attribute map fetch, palette bank, priority metadata
- in scope: 4bpp tile data proof target; structurally ready for 2/8bpp
- in scope: Multi-bank palette (power-of-two depth)
- in scope: R3 scheduler configured with 2–3 slots per line
- in scope: Minimal compositor priority hook
- out of scope: Packed-attribute decode (R4.1b)
- out of scope: Scroll-table primitive (R4.2)
- out of scope: Sprite-to-SDRAM, planar, Copper, window/color-math

## Dependencies
- R3 Fetch-Slot Scheduler (proven, fcc3aa6)
- Task-15 SDRAM path (proven)
- R2 Sprite Evaluator (proven)

## Interfaces
- `fetchGrant`, `fetchSlotValid`, `fetchPreAnnounce` from scheduler
- `pixelIndex(4 bits)`, `pixelPriority(Bool)` to compositor
- 128/256-entry × 24-bit palette RAM

## Timing
- Fetch engine starts/pauses/resumes across 2–3 scheduler slots per line
- Pre-announce prepares next address before grant window

## Risks
- Attribute-tile desync (mitigate: SDRAM-domain attribute buffer)
- Slot underrun (mitigate: conservative sizing + flag telemetry)
- GT-022 violation (mitigate: explicit power-of-two checks)

## Validation
- sim: `TileAttributeFetchSim` (8 cases)
- regression: `VdpTopSim`, `SpriteEvaluatorSim`, `RasterTriggerUnitSim`
- hardware: Palette-bank checkerboard + no-regression mixed scene

## Audit Focus
- Scope compliance, GT-022, scheduler coupling, attribute sync, metadata reach,
  palette bank correctness, regression proof

## Exit Condition
This task is done when the tile+attribute fetcher is integrated with multi-slot
scheduling, all regression sims pass, and hardware proves palette-bank attributes
and metadata propagation work correctly.
```
