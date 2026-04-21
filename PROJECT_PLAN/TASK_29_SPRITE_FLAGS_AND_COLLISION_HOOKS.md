# Task 29 — Sprite Flags and Collision Hooks

**Status:** Artifact phase  
**depends_on:** [28]  
**scope_boundary:** Sprite-side status flags only. No new compositor, no new fetch formats.  
**delivers:**

- Sprite-0-hit style flag (first non-transparent sprite pixel vs non-transparent background)
- Per-line sprite overflow/limit status register (host-readable via existing QSPI status path)
- Sprite/background collision latch (any visible sprite overlaps non-transparent background)
- Clearer priority-vs-layer hooks (per-slot collision tracking exposed to host status surface)

**validation:**

- Sim: collision scenarios produce correct latch values
- Hardware: raster-IRQ or status-read path proves flags are visible to host

---

## 1. Goal

Add host-visible collision and status flags to the sprite pipeline so that:
1. Host firmware can detect when a sprite (especially sprite 0) overlaps the background — enabling raster-sync and game-loop collision patterns.
2. The existing sprite-overflow flag becomes reliably readable via the QSPI status surface.
3. Platform adapters (C64, NES) have the semantic hooks they need for sprite-background collision without cycle-accurate emulation.

Today the `SpriteEvaluator` produces an `overflowFlag` and `VdpTop` exposes `io.spriteOverflow`, but there is **no sprite-background collision detection** and the overflow path to host readback has not been explicitly validated as a first-class status surface.

---

## 2. Scope

### 2.1 In scope

1. **Sprite-0-hit flag** — sticky status bit set when the first visible descriptor (slot 0) has a non-transparent pixel that overlaps a non-transparent background pixel.
2. **Sprite/background collision flag** — sticky status bit set when *any* visible sprite has a non-transparent pixel overlapping non-transparent background.
3. **Per-line overflow flag hardening** — confirm `overflowFlag` is correctly captured in the host-visible sticky status register and is readable via QSPI READ_STATUS.
4. **Status register expansion** — extend the existing Task 35 status bank with sprite-collision bits, using write-1-to-clear semantics.
5. **Per-slot collision metadata** — track which slot(s) triggered the collision so the host can disambiguate (optional: packed into a second status word if bit budget allows; at minimum, sprite-0 is distinguishable).
6. **Sim proof** — scenarios that intentionally place a sprite over transparent vs non-transparent background prove collision flags behave correctly.
7. **Hardware proof** — host firmware reads collision status and changes screen state (e.g. color flash) when collision occurs.

### 2.2 Out of scope (deferred)

- Sprite-to-sprite collision detection — requires per-sprite pixel overlap matrix, significant new logic.
- Per-sprite collision registers in the style of C64 `$D01E`/`$D01F` — 8+ bits of per-sprite state is a larger surface than the current status bank.
- Compositor priority changes — sprite vs background priority rules remain unchanged.
- New fetch formats or pattern memories.
- Full platform adapter register map — this is substrate work only.

---

## 3. Architecture

### 3.1 Current state (Task 28 + Task 35 baseline)

```
VdpTop fill phase (per pixel):
  composedBg = compositor output (L0/L1/affine)
  slotPixel[s], slotVisible[s] = sprite s pixel and visibility
  fillIdx/fillBank = final pixel going to line buffer
  
SpriteEvaluator:
  overflowFlag = (totalOnLine > visiblePerLine)  // latched per line
  
Status registers (Task 35):
  STATUS_STICKY @ 0x0320 — bits 0..3:
    bit 0: RASTER_MATCH
    bit 1: SPRITE_OVERFLOW  ← wired to spriteEval.overflowFlag
    bit 2: QSPI_READY
    bit 3: QSPI_ERROR
```

### 3.2 Target state (Task 29)

```
VdpTop fill phase (add collision detection):
  bgOpaque = composedBgIdx =/= 0   // non-transparent background
  sprite0Hit = slotVisible(0) && slotPixel(0) =/= 0 && bgOpaque
  spriteBgCollision = any (slotVisible(s) && slotPixel(s) =/= 0 && bgOpaque)
  
  Latch sprite0Hit and spriteBgCollision into sticky flags that persist
  until host clears them via write-1-to-clear.

Status registers (extended):
  STATUS_STICKY @ 0x0320 — bits 0..7:
    bit 0: RASTER_MATCH
    bit 1: SPRITE_OVERFLOW
    bit 2: QSPI_READY
    bit 3: QSPI_ERROR
    bit 4: SPRITE_0_HIT      ← NEW
    bit 5: SPRITE_BG_HIT     ← NEW
    bit 6: reserved
    bit 7: reserved
  
  STATUS_ENABLE @ 0x0321 — IRQ mask extended to cover bits 4-5.
```

### 3.3 Interface boundaries

- **Collision detection** — combinational logic in `VdpTop` fill loop, using existing `slotVisible`, `slotPixel`, and `composedBgIdx` signals. No new module; pure wiring.
- **Sticky latches** — `Reg(Bool())` in `VdpTop`, cleared by write-1-to-clear on `STATUS_STICKY`.
- **Host read path** — QSPI READ_STATUS sel=5 (existing) returns `statusStickyReg`; sprite bits now included.
- **IRQ path** — `io.irq = (sticky & enable).orR` already covers all 16 bits; extension is automatic.

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`VdpTop.scala` (diff)** — Collision detection wiring:
   - Add `val bgOpaque = composedBgIdx =/= B(0, 4 bits)` (already computed as `layer0Opaque`/`layer1Opaque`; reuse)
   - Add `val sprite0HitPixel = slotVisible(0) && slotPixel(0) =/= B(0, 4 bits) && bgOpaque`
   - Add `val spriteBgHitPixel = Vec(...).reduce(...)` for any slot
   - Add sticky registers:
     ```scala
     val stickySprite0Hit  = Reg(Bool()) init False
     val stickySpriteBgHit = Reg(Bool()) init False
     when(sprite0HitPixel)  { stickySprite0Hit  := True }
     when(spriteBgHitPixel) { stickySpriteBgHit := True }
     ```
   - Extend `evBus` to include bits 4-5:
     ```scala
     val evBus = (B(0, 10 bits) ## 
                  stickySpriteBgHit ## stickySprite0Hit ## 
                  evQspiError ## evQspiReady ## evSpriteOverflow ## evRasterMatch).asBits
     ```
   - Update `statusClearMask` clearing: when host writes 0x0320 with bit 4 set, clear `stickySprite0Hit`; bit 5 clears `stickySpriteBgHit`.
   - The existing `statusStickyReg := (statusStickyReg | evBus) & (~statusClearMask)` already handles this automatically because `evBus` now carries the collision bits.

2. **`SpriteEvaluator.scala`** — Overflow flag validation:
   - Verify `overflowFlagReg` clears correctly at start of next eval (it already does: `overflowFlagReg` is updated at scan completion, not a sticky across lines).
   - **No changes required** — overflow behavior is correct; Task 29 only validates the read path.

3. **Status register address map update** — `MODE0_REGISTER_BUS_SPEC.md` §3.1:
   - Update `STATUS_STICKY` bit definitions to document bits 4-5.
   - No new addresses; 0x0320/0x0321 remain the surface.

### 4.2 Data model

| Status bit | Name | Set condition | Clear |
|---|---|---|---|
| bit 0 | RASTER_MATCH | raster trigger pulse | write-1-to-clear @ 0x0320 |
| bit 1 | SPRITE_OVERFLOW | spriteEval.overflowFlag | write-1-to-clear @ 0x0320 |
| bit 2 | QSPI_READY | QSPI cmd_valid pulse | write-1-to-clear @ 0x0320 |
| bit 3 | QSPI_ERROR | decoder last_error != 0 | write-1-to-clear @ 0x0320 |
| bit 4 | SPRITE_0_HIT | sprite 0 non-transparent pixel over non-transparent BG | write-1-to-clear @ 0x0320 |
| bit 5 | SPRITE_BG_HIT | any sprite non-transparent pixel over non-transparent BG | write-1-to-clear @ 0x0320 |

### 4.3 Register / bus impact

- **No new register addresses** — collision bits extend the existing `STATUS_STICKY` word at `0x0320`.
- **STATUS_ENABLE mask** at `0x0321` now controls IRQ generation for bits 4-5 as well.
- **QSPI READ_STATUS sel=5** automatically returns the extended sticky word.

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `SpriteCollisionSim` (new): Program sprite 0 at a known (x,y). Place non-transparent background tile at same location. Verify `stickySprite0Hit` asserts. Move sprite to transparent area; verify it de-asserts (after clear).
- `SpriteOverflowStatusSim` (new): Program more than `visiblePerLine` sprites on one line. Verify `SPRITE_OVERFLOW` bit is set in status sticky and readable.
- `VdpTopSim` regression: all existing scenarios pass (collision bits add no structural change to pixel path).

**Checkpoint B — Hardware:**
- Sc29a: Single sprite 0 over non-transparent background. Host firmware polls QSPI READ_STATUS sel=5 and toggles a pixel color when `SPRITE_0_HIT` (bit 4) is set. 30-second capture confirms visible toggle.
- Sc29b: Multiple sprites, some overlapping background, some not. Host toggles on `SPRITE_BG_HIT` (bit 5). Confirms any-sprite detection.
- Sc29c: Overflow test — program 5+ sprites on same line with `visiblePerLine=4`. Host reads `SPRITE_OVERFLOW` (bit 1) and confirms it sets.

---

## 5. Deliverables

| File / Path | Purpose |
|---|---|
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Collision detection wiring + status bit extension |
| `sim/` test additions | `SpriteCollisionSim` + overflow status proof |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` (diff) | Updated STATUS_STICKY bit definitions |
| `PROJECT_PLAN/TASK_29_SPRITE_FLAGS_AND_COLLISION_HOOKS.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Collision detection adds combinatorial delay in fill loop | Logic is 1-2 AND gates on existing signals; delay is negligible vs existing affine stepper path |
| Status sticky word overflow (16 bits) | Only bits 0-5 used; 10 bits remain for future expansion |
| Per-slot collision tracking too large | Scope limits to two aggregate flags (sprite-0-hit, any-sprite-hit). Per-slot deferred to future task. |
| Host cannot distinguish WHICH sprite hit | Sprite-0 is distinguished (bit 4). Other sprites aggregate to bit 5. Sufficient for C64/NES adapter patterns. |
| Regression in existing sprite scenes | Collision logic is purely observational; it does not modify fill pixel data or compositor behavior |

---

## 7. Dependencies

- **Task 28 (Two-Pass Sprite Evaluator)** — DONE. Provides `slotVisible`, `slotPixel`, `overflowFlag`.
- **Task 35 (Host-Facing IRQ and Status Registers)** — DONE. Provides sticky register bank, write-1-to-clear, and QSPI read path.
- **Task 32b (Mode0 Register Bus: Master Refactor)** — DONE. Bus decode path for 0x0320/0x0321 is stable.

---

## 8. Open Questions

1. **Per-slot collision register**: Should we add a `SPRITE_HIT_MASK` register (e.g. at 0x0322) that shows which slots triggered collision? This would give C64-style `$D01F` semantics but costs an extra register address and more logic. *Recommended: defer to a future sprite-hardening task unless C64 adapter demands it.*
2. **Sprite-to-sprite collision**: NES/C64 both have this. Out of scope for Task 29; would require comparing every visible sprite pair per pixel. *Defer.*
3. **Collision pixel definition**: Is palette index 0 always transparent for sprites and background? Current code uses `pixel =/= B(0, 4 bits)` which matches the existing transparency rule. *Confirmed consistent.*

---

## 9. Audit Focus

- Scope compliance: no compositor changes, no fetch-format changes, no new pattern memories
- Collision logic is purely observational — does not affect rendered pixels
- Regression: all existing Sc0..Sc17 scenarios pass unchanged
- Status read path: QSPI READ_STATUS sel=5 returns extended word with bits 4-5 valid
- IRQ path: enabling bits 4-5 in STATUS_ENABLE causes `io.irq` to assert on collision

---

## 10. Exit Condition

This task is done when:
1. Simulation proves `SPRITE_0_HIT` and `SPRITE_BG_HIT` sticky bits assert correctly under controlled collision and non-collision scenarios.
2. Hardware proves host firmware can read these bits via QSPI READ_STATUS and react visibly.
3. The existing `SPRITE_OVERFLOW` bit is confirmed readable and correct under overload conditions.
4. All existing scenarios regress cleanly (zero pixel behavior change).
