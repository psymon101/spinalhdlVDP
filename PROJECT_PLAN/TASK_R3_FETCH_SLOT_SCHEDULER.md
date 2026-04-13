# TASK_R3_FETCH_SLOT_SCHEDULER.md

**Status:** OPEN  
**Created:** 2026-04-13  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef (temp)

---

## 1. Task Name

Static Fetch-Slot Scheduler with Pre-Announce

---

## 2. Purpose

Replace the current reactive SDRAM fetch model with a statically scheduled fetch-slot system that provides deterministic bandwidth allocation and explicit memory budget accounting.

**Why now:**
- R2 added sprite evaluation with per-line limits; sprites need guaranteed fetch bandwidth
- Current reactive model risks starvation under sprite+tile pressure
- C64 badline-style, Amiga display window, and NES fixed-cadence fetch all require explicit scheduling
- This refactor prepares the substrate for R4 tile+attribute fetch generalization

**Platform pressure:**
- Commodore 64 badline behavior
- Amiga display data fetch windows  
- NES fixed tile-fetch cadence
- Genesis/MD multi-layer fetch timing

---

## 3. Primitive Boundary

### In Scope

- **Static fetch-slot table** (frame-time schedule, ROM or RAM-based)
- **Per-fetch-engine slot assignment** (tile fetcher, sprite fetcher)
- **H-position aligned fetch windows** within scanline
- **Pre-announce/lookahead grant** (BA-style, one cycle ahead)
- **Line-budget accounting** (explicit bandwidth tracking)
- **Integration with existing Task-15 SDRAM controller** (not replacing it)
- **Re-proof of current tile-fetch baseline** after integration

### Explicitly Out of Scope

- NO replacement of SDRAM controller itself
- NO new fetch engine data formats (R4 concern)
- NO tile+attribute split (R4 concern)
- NO planar fetch engines (R7 concern)
- NO Copper/HDMA automation (R5 concern)
- NO scroll-table primitive (R4.2 concern)
- NO window/color-math (R6 concern)
- NO changes to sprite evaluator selection logic (R2 complete)
- NO register bus definition (R5.1 concern)

---

## 4. Dependencies

- Task-15 SDRAM L0 path (proven, must remain functional)
- R2 Two-Pass Sprite Evaluator (proven, provides sprite fetch pressure)
- LinestateStore with prepare/commit (unchanged interface)

---

## 5. Interfaces

### New Interfaces

```scala
// Scheduler configuration (static for this task)
val fetchSchedule = Vec(FetchSlot(), slotCount)  // ROM or static RAM

// Per-slot definition
case class FetchSlot() extends Bundle {
  val enabled = Bool()
  val clientId = UInt(2 bits)  // 0=tile, 1=sprite
  val startH = UInt(10 bits)   // H-position window start
  val endH = UInt(10 bits)     // H-position window end
}

// Scheduler outputs (to arbiter)
val currentSlot = out UInt(log2Up(slotCount) bits)
val slotValid = out Bool()     // within active window
val preAnnounce = out Bool()   // BA-style lookahead (1 cycle early)
val grant = out Bool()         // actual grant to current client
```

### Modified Interfaces

- `SdramController` req/ready now driven by scheduler grant, not reactive priority
- Fetch engines receive `grant` pulse + `preAnnounce` prepare signal

---

## 6. Data Model

### Persistent (frame-level)

- `fetchSchedule` table: 8-16 slots typical, static for this task
- `currentSlot` counter: tracks active slot index

### Per-Line

- `slotActive` flag: derived from H-counter vs slot window
- `grant` pulse: single-cycle SDRAM access grant
- `preAnnounce` pulse: one cycle before grant (client prefetch prep)

### No GT-022 Exposure

- Schedule table depth will be power-of-two (8 or 16 slots)
- No non-power-of-two memories introduced

---

## 7. Timing Model

- **Schedule evaluation:** Every cycle during active display
- **Window comparison:** H-counter vs slot.startH/endH
- **Pre-announce:** Asserts 1 cycle before grant (client can prepare address)
- **Grant:** Single-cycle SDRAM access opportunity
- **Slot advancement:** Round-robin through enabled slots, or priority-based

**Key timing constraint:**
- Pre-announce must arrive early enough for client to prepare SDRAM address
- Grant must align with SDRAM ready/accept timing

---

## 8. Memory / Bandwidth Impact

### SDRAM Bandwidth

- Same total bandwidth as Task-15 baseline
- Now explicitly budgeted per slot
- Tile fetch: minimum 2 slots per line (sufficient for 640px)
- Sprite fetch slot: reserved capacity, but sprites currently on BSRAM (not SDRAM)

### On-Chip Resources

- Schedule table: small (8-16 entries × ~24 bits)
- Slot counter + comparators: minimal
- No new line buffers (reuse existing)

### Arbitration Change

- From: Reactive priority arbiter (whoever asks first)
- To: Scheduled time-division with pre-announce

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| C64 | Badline fetch cadence |
| NES | Fixed tile fetch timing |
| Amiga | Display data fetch windows |
| Genesis | Multi-layer deterministic fetch |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Bandwidth underrun | Conservative slot sizing + explicit budget proof |
| SDRAM timing mismatch | Re-run full Task-15 regression after integration |
| Pre-announce too late | Verify address prep timing in sim |
| Slot table overflow | Power-of-two depth, 8-16 slots sufficient for proof |
| Reactive→scheduled regression | Keep reactive path as fallback during development |

**Critical risk:**
This is a **Task-15-path refactor**. The existing tile fetch baseline must be re-proven after integration.

---

## 11. Validation Plan

### Dedicated Sim

New `FetchSlotSchedulerSim` covering:

1. **Single slot:** One enabled slot grants correctly at window
2. **Multiple slots:** Round-robin through 2-4 slots
3. **Pre-announce timing:** Grant arrives 1 cycle after pre-announce
4. **Window boundaries:** No grant outside startH/endH
5. **Disabled slot skip:** Disabled slots don't consume bandwidth
6. **Tile fetch integration:** Schedule drives actual tile fetch, produces pixels
7. **Sprite fetch integration:** Schedule interleaves sprite fetch with tile
8. **Budget accounting:** Bandwidth math visible for audit

### Regression Sims (must rerun)

- `SdramTileFetchSim` — must still produce correct pixels
- `VdpTopSim` — full pipeline still functions
- `SpriteEvaluatorSim` — sprites still evaluate correctly

### Assertions Required

```scala
// Pre-announce timing
assert(preAnnounce === grant.delay(1))

// Grant only in window
assert(grant -> (hCounter >= slot.startH && hCounter <= slot.endH))

// Budget: grant count per line <= slotCount
assert(lineGrantCount <= maxGrantsPerLine)
```

---

## 12. Hardware Proof

### Proof Scene

**"Scheduled Sprite+Tile Mixed Scene"**

- 3-region linestate (existing tile layer, scrolling)
- 4 sprites enabled, 2 visible per line (R2 overflow case)
- Scheduler must allocate:
  - 2 slots for tile fetch (sufficient for 640px line)
  - 1-2 slots for sprite fetch (for 2 sprites)
- Visual result: Same as R2 proof but with deterministic fetch timing

### Hardware Verification

- Capture card shows no visible difference from R2 (proves no regression)
- Optional: Expose `currentSlot` or `grant` on debug pins for scope/ILA verification
- LEDs: Keep LED 0-5 health indicators (pixel, SDRAM, linestate, etc.)

---

## 13. Audit Questions

CyanPeak to verify:

1. **Scope compliance:** Does implementation stay within bounded fetch-slot scheduler scope?
2. **No accidental scope creep:** No tile+attribute changes, no planar, no Copper?
3. **GT-022 compliance:** Schedule table depth power-of-two?
4. **Timing correctness:** Pre-announce 1 cycle before grant, every time?
5. **Integration safety:** Existing Task-15 tile fetch still works after scheduler integration?
6. **Bandwidth accounting:** Explicit budget visible and verified?
7. **Refactor proof:** Full regression sims pass after integration?
8. **Hardware proof:** No visual regression in mixed sprite+tile scene?

---

## 14. Constraints / Gotcha Check

- [x] **GT-022:** Schedule table depth = power-of-two (8 or 16)
- [x] **SDRAM-latency awareness:** Pre-announce accounts for address setup
- [x] **Interface-stability:** Scheduler interface compatible with later register bus
- [x] **No hardware before sim:** Sim proof first, hardware second
- [x] **No mid-line linestate:** Scheduler doesn't change linestate application

---

## 15. Exit Condition

This task is done when the static fetch-slot scheduler with pre-announce is integrated with the Task-15 SDRAM path, all regression sims pass, and a mixed sprite+tile hardware scene proves no regression from the reactive fetch baseline.

---

## Short-Form Summary

```markdown
## Task
R3 Static Fetch-Slot Scheduler with Pre-Announce

## Purpose
Replace reactive SDRAM fetch with scheduled fetch slots + BA-style lookahead. 
Prepare substrate for sprite+tile bandwidth pressure.

## Scope
- in scope: Static slot table, per-engine assignment, H-window alignment
- in scope: Pre-announce grant, line-budget accounting
- in scope: Integration with Task-15 SDRAM, re-proof baseline
- out of scope: SDRAM controller replacement
- out of scope: Tile+attribute, planar, Copper, scroll-table

## Dependencies
- Task-15 SDRAM L0 (proven)
- R2 Sprite Evaluator (proven)

## Interfaces
- fetchSchedule[8-16] table (ROM/static)
- currentSlot, slotValid, preAnnounce, grant signals
- Drives SDRAM arbiter instead of reactive priority

## Timing
- Pre-announce 1 cycle before grant
- H-window aligned slots within scanline
- Round-robin or priority slot advance

## Risks
- Bandwidth underrun (mitigate: conservative sizing)
- Task-15 regression (mitigate: full re-proof)
- Pre-announce timing (mitigate: sim verification)

## Validation
- sim: FetchSlotSchedulerSim (8 cases)
- regression: SdramTileFetchSim, VdpTopSim, SpriteEvaluatorSim
- hardware: Mixed sprite+tile scene, no visual regression

## Audit Focus
- Scope compliance, GT-022, timing, integration safety, regression proof

## Exit Condition
This task is done when the scheduled fetch is integrated, all regression sims pass, 
and hardware proves no regression in mixed sprite+tile scene.
```
