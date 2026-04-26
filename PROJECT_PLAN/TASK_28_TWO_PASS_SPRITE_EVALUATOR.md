# Task 28 — Two-Pass Sprite Evaluator

**Status:** DONE (`9e07804`) — Sprite evaluator two-pass architecture implemented, audited, and hardware-proven
**depends_on:** [12, 15]
**scope_boundary:** Sprite evaluation pipeline only. No new compositor changes, no collision logic (Task 29), no attribute extension (Task 37), no affine transforms.
**delivers:**

- Per-line active-sprite scan with bounded visible-sprite selection
- Sprite-per-scanline limit enforcement
- Secondary sprite buffer for fetched attributes
- Deterministic overlap / priority behavior inside the bounded list
- Simulation proof for evaluator + no-regression proof for baseline
- One hardware-visible proof scene demonstrating bounded selection

**validation:**

- Sim: mixed scene with >8 sprites on a line proves correct selection and limit enforcement
- Hardware: visual proof on Tang Nano 20K that sprite drop behavior matches expected limits

---

## 1. Goal

Replace the current inline, hard-coded two-sprite pixel test in `VdpTop` with a proper two-pass sprite evaluation primitive that later sprite flags, collision hooks, and platform adapters can reuse.

Current sprite path is ad hoc: direct `sprite0` / `sprite1` conditionals baked into the pixel-fill logic. Task 28 introduces a bounded descriptor table, a per-line active-sprite selection pass, and a per-pixel render pass from that bounded list.

## 2. Scope

### 2.1 In scope

1. **Sprite descriptor table** — bounded on-chip store with fields: `x`, `y`, `enabled`, `patternIndex`, `priority`
2. **Pass 1 (per-line scan)** — range scan selects active sprites for the upcoming scanline, enforces a per-line limit
3. **Pass 2 (per-pixel render)** — resolve sprite pixels from the bounded active-sprite list during fill
4. **Deterministic overlap / priority** — stable ordering inside the active list when multiple sprites overlap
5. **Line-local secondary buffer** — staging structure holding the active-sprite subset for one line
6. **Sim + no-regression proof** — evaluator sim plus verification that existing scenarios (Sc0–Sc33) are unchanged
7. **Hardware proof scene** — one scenario demonstrating >2 sprites with correct drop/selection behavior

### 2.2 Out of scope (deferred)

- Sprite-0-hit, overflow, or collision status flags (Task 29)
- Platform-exact OAM register maps
- Sprite DMA / linked-list sprite systems
- Sprite scaling / zoom / flipping / affine behavior (Task 37)
- Palette banking, color math, or windowing
- Fetch-slot scheduler refactor
- Mid-line sprite register writes
- Copper / HDMA / beam-script behavior
- Compositor metadata consumption (Task 41 pipe is in place; sprite-side metadata generation is deferred)

## 3. Architecture

### 3.1 Current state (ad-hoc two-sprite)

```
VdpTop pixel fill:
  if (sprite0 enabled && y matches && x matches) pixel := sprite0 color
  else if (sprite1 enabled && y matches && x matches) pixel := sprite1 color
  else pixel := bg color
```

Problems:
- Hard-coded to exactly 2 sprites
- No per-line limit enforcement
- No deterministic overlap ordering
- No descriptor table — sprite state is scattered registers

### 3.2 Target state (Task 28)

```
Sprite Descriptor Table (on-chip RAM/Reg):
  entry[N] = {x, y, enabled, patternIndex, priority}

Pass 1 (during h-blank or line prep):
  for each descriptor:
    if descriptor.enabled && line in [y, y+height):
      add to activeLine[]
      if activeLine.size == LIMIT: break
  sort activeLine by priority (or stable insertion order)

Pass 2 (per-pixel fill):
  for each active sprite in activeLine (front-to-back or back-to-front):
    if pixelX in [sprite.x, sprite.x+width):
      pixel := sprite color
      layerSource := SPRITE
      break
```

### 3.3 Interface boundaries

- **Descriptor storage** — readable/writable via Mode0RegBus (stub the bus connection if bus refactor is not yet merged; otherwise use existing bus)
- **Active-line buffer** — purely internal, line-local, cleared each line
- **Pixel output** — feeds the existing compositor pixel path; should be a drop-in replacement for the current `sprite0/sprite1` wires

## 4. Implementation Plan

### 4.1 HDL changes

1. **`SpriteDescriptor.scala`** — case class defining the descriptor fields
2. **`SpriteEvaluator.scala`** — two-pass evaluator module:
   - Pass 1 FSM: scans descriptor table, builds `activeLine` vector
   - Pass 2: combinational or registered lookup from `activeLine` at pixel X
3. **`VdpTop.scala`** (diff) — replace `sprite0/sprite1` inline logic with `SpriteEvaluator` instance
4. **Descriptor table wiring** — connect to `Mode0RegBus` for host programming (or stub with init values for first proof)

### 4.2 Data model

| Structure | Size | Notes |
|-----------|------|-------|
| Descriptor table | 16–32 entries | Power-of-two depth per GT-022 |
| Active-line buffer | ≤ 8 entries | Register-based or small RAM; limit enforced here |
| Per-line count | 4 bits | Tracks how many sprites are active this line |

### 4.3 Register / bus impact

If Mode0RegBus is available:
- New register block: `SPRITE_DESC_BASE` through `SPRITE_DESC_END`
- Each descriptor field gets a bus address

If bus connection is deferred:
- Descriptor table initialized from `Vec(...)` constants in SpinalHDL
- Host programming added in a later bus-integration task

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `SpriteEvaluatorSim`: 10+ sprites on one line, verify only first `LIMIT` are rendered
- `VdpTopSim` regression: all existing scenarios pass unchanged

**Checkpoint B — Hardware:**
- Build a test scenario (e.g. Sc28) with 8+ sprites moving vertically
- Capture and verify: only the first `LIMIT` sprites appear on any given line
- Confirm no regression against Sc0 baseline

## 5. Deliverables

| File / Path | Purpose |
|-------------|---------|
| `hw/spinal/spinalhdlvdp/SpriteDescriptor.scala` | Descriptor bundle definition |
| `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala` | Two-pass evaluator module |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Integration: replace ad-hoc sprite logic |
| `sim/` test additions | Sim validation + no-regression proof |
| `PROJECT_PLAN/TASK_28_TWO_PASS_SPRITE_EVALUATOR.md` | This artifact |

## 6. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Off-by-one line inclusion | Sim test with sprite Y at scanline boundary |
| Sprite-per-line limit at wrong boundary | Explicit `>= LIMIT` check in Pass 1 with sim coverage |
| Unstable overlap ordering | Define deterministic sort key (priority then index) |
| Active list mutates mid-line | Freeze list at line start; no bus writes during fill |
| GT-022 power-of-two violation | Pad descriptor table to next power of two |
| Scope creep into sprite flags | Strict boundary: overlap/priority only, no collision logic |

## 7. Dependencies

- Task 12 (Sprite Priority / Transparency) — DONE. Basic sprite pixel path exists.
- Task 15 (Memory-Backed Fetch Path) — DONE. SDRAM fetch substrate proven.
- Task 32b (Mode0 Register Bus: Master Refactor) — DONE. Bus available for descriptor programming.
- Task 41 (Compositor Metadata Pipe) — DONE. Pipe in place; sprite-side `layerSource` can be wired when evaluator produces sprite pixels.

## 8. Open Questions

1. **Descriptor count / limit**: Is 16 descriptors with an 8-sprite-per-line limit appropriate for the first bounded slice?
2. **Bus address block**: Where should the sprite descriptor register block live in the Mode0 address map?
3. **Priority sort order**: Should active sprites be rendered front-to-back (highest priority first) or back-to-front for correct transparency stacking?
4. **Pattern index width**: How many bits for `patternIndex` to remain future-safe without over-allocating?
