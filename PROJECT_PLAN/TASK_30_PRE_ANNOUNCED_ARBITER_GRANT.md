# Task 30 — Pre-Announced Arbiter Grant

**Status:** DONE (`734ffb6`) — Pre-announced arbiter grant implemented and integrated
**depends_on:** [15]
**scope_boundary:** SDRAM arbitration lookahead only. No new fetch engines, no new memory types.
**delivers:**

- BA-style lookahead so fetch clients can prepare before the exact memory-use slot
- More deterministic multi-fetch scenes
- Latency-tolerant SDRAM arbitration

**validation:**

- Sim: mixed scene with tile + sprite + Copper fetch proves no arbitration glitches under lookahead
- Hardware: long-soak validation (Task 22 class) with arbiter active

---

## 1. Goal

Harden the existing `FetchSlotScheduler` (R3 primitive) into a fully-proven SDRAM arbitration substrate that supports multiple concurrent fetch clients with deterministic bandwidth allocation. The scheduler already emits `preAnnounce` / `grant` / `slotValid`; Task 30 closes the gap between scheduling signals and actual multi-client SDRAM arbitration under load.

---

## 2. Scope

### 2.1 In scope

1. **Multi-client scheduler integration** — wire the `FetchSlotScheduler`'s `grantClientId` output to drive multiple SDRAM clients (tile fetch, future sprite-SDRAM, etc.) through a single arbitration point.
2. **Pre-announce consumption** — ensure every fetch client uses `preAnnounce` to prepare its next SDRAM address before `grant` opens the window.
3. **Deterministic bandwidth allocation** — static slot table gives each client guaranteed H-windows; no client starves due to reactive priority inversion.
4. **Pause/resume correctness** — fetch engines stop issuing SDRAM commands when `slotValid` drops and resume cleanly at the next window.
5. **Line-budget accounting** — `lineGrantCount` and per-client grant tracking visible for debug/assert.
6. **Sim proof** — mixed-load scene (tile + animated scroll + copper activity) with scheduler active; zero arbitration glitches.
7. **Hardware long-soak** — 30-second minimum capture with scheduler-driven fetch; stability analysis passes.

### 2.2 Out of scope (deferred)

- New fetch engine types (planar, shuffled, sprite-SDRAM — each has its own task)
- SDRAM controller replacement or timing changes
- Dynamic slot reconfiguration mid-frame
- Bandwidth overcommit recovery (underrun handling is per-fetch-engine, not arbiter)
- Formal verification of arbitration fairness

---

## 3. Architecture

### 3.1 Current state (R3 primitive)

`FetchSlotScheduler` already exists in `hw/spinal/spinalhdlvdp/FetchSlotScheduler.scala`:
- 8 slots, power-of-two (GT-022)
- `preAnnounce` at `hCounter == startH - 1`
- `grant` at `hCounter == startH`
- `slotValid` during `[startH, endH]`
- `grantClientId` selects among 4 clients

Currently wired in `VdpTop.scala`:
- Client 0 = `SdramTileAttributeFetch` (tile+attribute fetch)
- Slots 1..7 disabled or reserved

### 3.2 Target state (Task 30)

```
FetchSlotScheduler (8 slots)
  ├─ slot 0: client 0 = tile+attribute fetch [startH=10, endH=200]
  ├─ slot 1: client 0 = tile+attribute fetch continuation [startH=250, endH=400]
  ├─ slot 2: client 1 = reserved (sprite-SDRAM or future)
  ├─ slot 3..7: spare / disabled
  ▼
Arbiter mux: grantClientId selects which client's sdramRd/sdramAddr reaches SDRAM
SDRAM controller sees only one requestor per cycle
```

Key change: the scheduler's `grantClientId` must actually drive an arbitration mux that gates `sdramRd` from all clients. Today, only client 0 exists.

### 3.3 Interface boundaries

- **Scheduler → Arbiter**: `grant`, `grantClientId`, `slotValid`
- **Arbiter → SDRAM**: single `sdramRd` + `sdramAddr` from the granted client
- **Arbiter → Clients**: per-client `clientGrant` pulse + `clientSlotValid` window
- **Debug**: `lineGrantCount`, per-client grant counters, underrun flags

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`SdramArbiter.scala`** (new or extend existing) —
   - Inputs: `Vec(sdramRd, sdramAddr)` from each fetch client
   - Selects the active client via `grantClientId`
   - Asserts `clientGrant(i)` one cycle after `grantClientId == i`
   - Gates `slotValid` to each client
2. **`VdpTop.scala`** (diff) —
   - Instantiate `SdramArbiter` between scheduler and SDRAM controller
   - Wire `layer0FetchGrant/SlotValid/PreAnnounce` through arbiter
   - Reserve client 1..3 ports for future engines (tied inactive)
3. **`FetchSlotScheduler`** — no changes required; primitive is already correct
4. **Top-level wiring** — `TopTang20kHdmi` connects SDRAM controller to arbiter output

### 4.2 Data model

| Structure | Size | Notes |
|-----------|------|-------|
| Slot table | 8 entries | Already exists; power-of-two |
| Arbiter client count | 4 | Matches `grantClientId` width |
| Grant counters | 4 × 4 bits | Per-client grants per line |

### 4.3 Register / bus impact

- Optional: expose per-client grant counts as status registers for debug
- No new host-programmable state required for baseline proof

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `ArbiterSim` (new): two clients compete; scheduler grants alternate; no double-grant, no missed grant
- `VdpTopSim` regression: existing tile-fetch scene passes with arbiter in path
- `FetchSlotSchedulerSim` regression: 8-case suite still passes

**Checkpoint B — Hardware:**
- Build existing tile-fetch scenario (Sc8 or similar) with scheduler+arbiter active
- 30-second capture; OpenCV stability analysis passes
- No visible difference from pre-arbiter baseline

---

## 5. Deliverables

| File / Path | Purpose |
|-------------|---------|
| `hw/spinal/spinalhdlvdp/SdramArbiter.scala` (new or extend) | Multi-client SDRAM arbitration mux |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Arbiter integration |
| `sim/` test additions | `ArbiterSim` + regression proof |
| `PROJECT_PLAN/TASK_30_PRE_ANNOUNCED_ARBITER_GRANT.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Arbiter adds combinational delay to SDRAM path | Measure path in P&R; pipeline if needed |
| Multi-client grant collision | Scheduler guarantees single grant per cycle by design |
| Client 0 regression from arbiter insertion | Bit-identical `VdpTopSim` proof before hardware |
| `grantClientId` not consumed by existing fetch | Existing `SdramTileAttributeFetch` already uses `fetchGrant` |
| Scope creep into new fetch engine | Strict boundary: only arbitration mux, no new engine |

---

## 7. Dependencies

- **Task 15 (Memory-Backed Fetch Path)** — DONE. SDRAM substrate proven.
- **R3 (Static Fetch-Slot Scheduler)** — DONE. `FetchSlotScheduler` primitive exists and is sim-proven.
- **Task 28/37 (Sprite Evaluator)** — DONE. Sprites exist; sprite-to-SDRAM is future work, not required for Task 30.

---

## 8. Open Questions

1. **Does an `SdramArbiter` already exist?** `TopTang20kHdmi.scala` has SDRAM wiring; verify whether a reactive arbiter already gates `sdramRd` or if the tile fetcher drives SDRAM directly.
2. **Client count**: Is 4 clients sufficient for the Mode0 substrate (tile, sprite, Copper/HDMA, spare)?
3. **Hardware proof scenario**: Re-use an existing stable scene (Sc8 parallax) or build a new Sc30? Re-use is lower risk.

---

## 9. Audit Focus

- Scope compliance: no new fetch engines, only arbitration mux
- Regression: existing scenes bit-identical with arbiter inserted
- Timing: no new P&R violations from arbiter logic
- Determinism: scheduler slot table produces repeatable grant sequences

---

## 10. Exit Condition

This task is done when the `FetchSlotScheduler` pre-announce/grant signals drive a multi-client SDRAM arbitration mux, all regression sims pass, and a 30-second hardware soak proves no visible regression in an existing scheduled-fetch scene.
