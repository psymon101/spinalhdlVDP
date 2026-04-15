# TASK_R4_1_MULTI_SLOT_SCHEDULER_COUPLING.md

**Status:** CLOSED (`9dfeb9f`)
**Closed:** 2026-04-13  
**Created:** 2026-04-13  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef (temp)

---

## 1. Task Name

R4.1 Multi-Slot Scheduler Coupling for Tile+Attribute Fetch

---

## 2. Purpose

Take the proven R4 `SdramTileAttributeFetch` engine (currently operating in single-slot grant-only mode) and make it a true **multi-slot client** of the R3 scheduler.

**Why now:**
- R4 proved the tile+attribute fetch primitive is functionally correct end-to-end
- The scheduler was designed for multi-slot clients, but R4 deferred this coupling to minimize diagnostic surface area
- Unlocking multi-slot fetch is required for higher bandwidth layers (more tiles per line, deeper bit depths, or multiple layers sharing SDRAM)

---

## 3. Primitive Boundary

### In Scope

- **Fetch engine pause/resume**
  - Remove the `readGate = True` bypass in `SdramTileAttributeFetch`
  - Fetch FSM states (`sFetchMapRq`, `sFetchAttrRq`, `sFetchRowRq0`, `sFetchRowRq1`) must stall when `slotValid` drops
  - Resume cleanly from the exact same state when `slotValid` rises again
  - Do NOT lose `tileIdx`, `tileIndexReg`, `attrByteReg`, `bankReg`, `priorityReg`, or `rowWord0Reg` across a pause

- **Scheduler multi-slot configuration**
  - Configure `FetchSlotScheduler` with **2–3 slots per line** for `clientId = 0`
  - Example layout:
    - slot 0: `startH = 0`, `endH = 159` (early-line burst for map reads)
    - slot 1: `startH = 320`, `endH = 479` (mid-line burst for row reads)
    - slot 2: `startH = hTotal - 1`, `endH = hTotal - 1` (end-of-line safety margin)
  - Exact positions are tunable; the proof only requires non-contiguous windows

- **Pre-announce integration**
  - Use `fetchPreAnnounce` to prepare the next SDRAM address one cycle before the slot opens
  - Verify this does not cause spurious reads when `slotValid` is still low

- **Re-proof of baseline**
  - Rerun full regression sim suite after scheduler+fetch integration

### Explicitly Out of Scope

- NO changes to tile/attribute decode logic
- NO changes to palette, assets, or proof scene
- NO changes to compositor or line buffer
- NO packed-attribute decode (R4.1b concern)
- NO scroll-table primitive (R4.2 concern)
- NO sprite-to-SDRAM migration
- NO planar fetch engine (R7 concern)
- NO Copper/HDMA automation (R5 concern)

---

## 4. Dependencies

- R4 Tile + Attribute Fetch Primitive (proven, commit `df7af63`)
- R3 Static Fetch-Slot Scheduler (proven, commit `fcc3aa6`)
- `LinestateStore` with prepare/commit (unchanged interface)

---

## 5. Interfaces

### Existing Interfaces (unchanged)

```scala
val fetchGrant        = in Bool()
val fetchSlotValid    = in Bool()
val fetchPreAnnounce  = in Bool()
```

### Scheduler Configuration (pixel domain)

In `VdpTop`, update `FetchSlotScheduler` slot table for `clientId = 0`:
- At least two non-contiguous slots per line
- `startH` and `endH` chosen so the total SDRAM bandwidth across all slots is sufficient for ~41 tiles × (1 tile byte + 1 attr byte + 2×32-bit row words)

---

## 6. Data Model

No new persistent or per-line storage introduced.

The fetch engine already holds all state in registers (`tileIdx`, `tileIndexReg`, `attrByteReg`, `bankReg`, `priorityReg`, `rowWord0Reg`). The only change is that these registers must **retain their values** across `slotValid` gaps instead of resetting.

---

## 7. Timing Model

- **Scheduler slots**: 2–3 non-contiguous H-windows per line
- **Fetch engine behavior**:
  - Starts on `fetchGrant` edge (same as today)
  - Issues SDRAM reads only while `slotValid` is true
  - Pauses cleanly at window boundaries without dropping in-progress tile state
  - Resumes at the next slot and continues from the same FSM state
- **Line buffer fill**: must still complete before pixel read catches up

---

## 8. Memory / Bandwidth Impact

### SDRAM Bandwidth

Identical to R4 — same number of bytes fetched per line, just spread across multiple H-windows.

### Scheduler Impact

- First true multi-slot client on the scheduler
- Proves non-contiguous pacing works for a real fetch engine

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| NES | Map + attribute reads can be split into early/mid-line slots |
| Genesis | Higher tile counts benefit from distributed fetch windows |
| SNES | Mode 7 / subscreen layers will need multi-slot bandwidth sharing |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Tile state corrupted across pause/resume | Register values are unchanged; only `cmdRd` is gated by `slotValid` |
| CDC glitch on `slotValidSync` causes missed resume | Use `BufferCC` plus edge detection; verify with sim |
| Slot windows too narrow for a single tile's reads | Size windows to fit at least one full tile fetch cycle |
| Pre-announce fires outside slot window | Gate pre-announce action with `slotValid` or ignore until window opens |
| Priority inversion between L0/L1 due to timing shift | Regression `VdpTopSim` must pass unchanged |

---

## 11. Validation Plan

### Dedicated Sim

Extend `TileAttributeFetchSim` to cover:

1. **Multi-slot scheduling**: fetch engine receives grants at 2+ distinct H positions and completes the line
2. **Pause/resume**: fetch engine stops issuing SDRAM reads when `slotValid` drops and resumes at the next slot
3. **Underrun detection**: artificially short slots trigger the underrun flag

*(Cases 1–4 from R4 already proved tile+attribute correctness; cases 5–7 now prove scheduling coupling.)*

### Regression Sims (must rerun)

- `VdpTopSim`
- `SpriteEvaluatorSim`
- `RasterTriggerUnitSim`
- `FetchSlotSchedulerSim`

### Assertions Required

```scala
// Read command only during valid slot
assert(io.sdramRd -> slotValidSync)

// Tile index does not regress across pause
assert(tileIdx.next >= tileIdx)
```

---

## 12. Hardware Proof

### Proof Scene

Reuse the R4 **Palette-Bank Checkerboard** scene. No asset changes.

With multi-slot scheduling active, the expected result is:
- Identical visual output to the R4 single-slot proof
- Four color quadrants scrolling correctly
- No regression in the mixed L0+L1 baseline scene

### Regression Scene

- Mixed sprite+tile scene (same as R2/R3/R4 proof) using palette bank 0 only
- Must show **no visual regression** vs commit `df7af63`

---

## 13. Audit Questions

CyanPeak to verify:

1. **Scope compliance:** Is this purely scheduling coupling, or does it smuggle in new features?
2. **Scheduler coupling:** Does the fetch engine truly pause and resume, or does it ignore `slotValid` after the first grant?
3. **State retention:** Are `tileIdx`, `tileIndexReg`, `attrByteReg`, `bankReg`, `priorityReg`, and `rowWord0Reg` preserved across slot gaps?
4. **Pre-announce safety:** Does pre-announce ever issue an early or spurious SDRAM command?
5. **Sim coverage:** Do `TileAttributeFetchSim` cases 5–7 (multi-slot, pause/resume, underrun) pass?
6. **Regression proof:** Do all regression sims pass after the scheduler config change?
7. **Hardware proof:** Does the checkerboard render identically to the R4 single-slot proof?

---

## 14. Constraints / Gotcha Check

- [ ] **No hardware before sim:** `TileAttributeFetchSim` multi-slot cases must pass before hardware build
- [ ] **Cleanup before testing:** Remove any debug telemetry or diagnostic scroll-freeze overrides left over from R4 diagnosis
- [ ] **No mid-line linestate change:** Linestate commit strobe untouched

---

## 15. Exit Condition

This task is done when `SdramTileAttributeFetch` operates correctly across 2–3 non-contiguous scheduler slots per line, all regression sims pass, and hardware proves the palette-bank checkerboard renders identically to the R4 single-slot baseline.

---

## Short-Form Summary

```markdown
## Task
R4.1 Multi-Slot Scheduler Coupling for Tile+Attribute Fetch

## Purpose
Make the proven R4 fetch engine a true multi-slot client of the R3 scheduler:
start, pause, and resume cleanly across non-contiguous H-windows.

## Scope
- in scope: Remove `readGate = True` bypass; gate reads on `slotValid`
- in scope: Scheduler configured with 2–3 slots per line for client 0
- in scope: Pre-announce integration without spurious early reads
- in scope: Sim proof of pause/resume and underrun detection
- out of scope: Any changes to tile decode, palette, assets, or compositor
- out of scope: Packed attributes (R4.1b), scroll tables (R4.2), sprites

## Dependencies
- R4 Tile + Attribute Fetch Primitive (proven, `df7af63`)
- R3 Static Fetch-Slot Scheduler (proven, `fcc3aa6`)

## Interfaces
- Existing scheduler ports: `fetchGrant`, `fetchSlotValid`, `fetchPreAnnounce`
- Existing fetch/compositor ports unchanged

## Timing
- Fetch engine pauses when `slotValid` drops, resumes at next slot
- All tile/attribute/row state retained across gaps

## Risks
- CDC glitch on `slotValidSync` (mitigate: BufferCC + edge detect)
- Slot windows too narrow (mitigate: conservative sizing)
- State loss across pause (mitigate: registers hold value)

## Validation
- sim: `TileAttributeFetchSim` cases 5–7 (multi-slot, pause/resume, underrun)
- regression: `VdpTopSim`, `SpriteEvaluatorSim`, `RasterTriggerUnitSim`, `FetchSlotSchedulerSim`
- hardware: R4 checkerboard (multi-slot) + no-regression mixed scene

## Audit Focus
- Scope compliance, true pause/resume, state retention, pre-announce safety,
  sim coverage, regression proof, hardware equivalence to R4 single-slot

## Exit Condition
This task is done when the fetch engine correctly operates across 2–3 scheduler
slots per line, all regression sims pass, and hardware shows no visual
regression vs the R4 single-slot baseline.
```
