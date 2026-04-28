# TASK_R1_RASTER_TRIGGER_UNIT.md

**Status:** CLOSED — Raster trigger unit implemented and integrated (R1 deliverable)

**Updated:** 2026-04-12  
**Purpose:** First roadmap-derived execution task for `Mode0`. This task introduces the foundational raster-trigger primitive that later platform adapters and automation features will consume.

---

## Task Name

`Raster Trigger Unit`

---

## Purpose

Add the first generic beam-synchronous control primitive to `Mode0`: a programmable raster trigger based on current scanline timing, with host-visible status/IRQ behavior.

This task exists because multiple target platforms depend on a cheap raster-position hook before they need more complex adapter logic:

- Commodore 64 raster IRQ
- Genesis line interrupt style control
- NES mapper / sprite-0 style split-screen coordination
- Amiga Copper / SNES HDMA groundwork

This is the smallest high-leverage primitive that turns `Mode0` from a passive renderer into a substrate that can react to beam position in a platform-usable way.

---

## Primitive Boundary

### In Scope

- one bounded `Raster Trigger Unit` primitive in `Mode0`
- programmable compare against the current visible scanline
- optional programmable compare against horizontal position if it is cheap to include cleanly
- edge-safe trigger pulse generation
- sticky match/status flag
- host-visible IRQ/status surface compatible with later register-bus unification
- simulation proof
- one hardware-visible proof

### Explicitly Out of Scope

- full Copper / HDMA / display-list execution
- mid-line register writes
- any fetch-engine changes
- any palette banking or color-math implementation
- any sprite-engine refactor
- external host bus redesign
- platform-specific register map semantics beyond the minimum bounded surface needed for proof

---

## Dependencies

- current hardware-proven `spinalhdlVDP` baseline after Task 15 closeout
- accepted `MODE0_ROADMAP.md`
- `TASK_TEMPLATE.md` as the planning format

Architectural prerequisite:

- follow the roadmap’s interface-stability rule so this primitive’s status/control surface can later sit behind the `Mode0` register bus without re-plumbing the design

---

## Interfaces

The task must introduce a cleanly bounded interface.

Minimum expected interface shape:

- current raster inputs:
  - current line / `vCounter`
  - optionally current pixel / `hCounter`
- programmable compare values:
  - trigger line
  - optional trigger pixel
  - enable bit
- outputs:
  - one-cycle `triggerPulse`
  - sticky `triggered` / `pending` status
  - clear/ack path for the sticky status
  - one host-visible IRQ/event output

Constraint:

- naming and semantics should be chosen so a later `Mode0` register bus can adopt them without changing behavior

---

## Data Model

The primitive should own only small local state:

- comparator target registers or config fields
- enable bit
- one-shot suppression / same-line retrigger prevention if needed
- sticky status latch

If any new `Mem` is introduced:

- GT-022 power-of-two rules apply immediately

Expected result:

- this task should not require any large new memory structure

---

## Timing Model

This is a raster-timing primitive.

Working assumptions:

- it runs in the pixel-domain timing context already used by `VdpTop`
- the compare is evaluated from current beam position
- the output pulse must be edge-safe and deterministic
- the sticky status must not chatter or retrigger repeatedly within the same programmed event

Preferred first scope:

- line compare is the must-have behavior
- pixel-position compare is optional if it stays cheap and does not destabilize the first slice

Hard rule:

- no mid-line state application is added in this task; this task only emits timing events and status

---

## Memory / Bandwidth Impact

Expected impact is minimal:

- no SDRAM traffic change
- no arbiter changes
- no line-buffer changes
- only small control/state additions in on-chip logic

This task is intentionally chosen first because it adds platform leverage without reopening the proven Task 15 memory path.

---

## Platform Reuse

Primary reuse:

- Commodore 64
- Genesis / Mega Drive
- NES / Famicom

Foundational reuse:

- Amiga
- SNES
- Atari ST

This primitive is useful immediately even before adapter modes exist, because it gives the substrate a generic beam-position event source.

---

## Failure Modes / Risks

Most likely risks:

- off-by-one compare timing against visible scanline boundaries
- repeated retriggering within the same line/frame
- unclear sticky-status clear semantics
- accidental coupling to a future register-bus design that does not exist yet
- unnecessary overreach into Copper-lite behavior

Secondary risk:

- if pixel-position compare is added too early, the task can grow from a simple scanline event into a more fragile timing feature

---

## Validation Plan

Required simulation proof:

- trigger fires on the programmed line
- no trigger on adjacent lines
- sticky status sets on match
- sticky status clears only through the defined clear/ack path
- no repeated trigger within the same programmed event window
- if pixel-position compare is included, prove exact compare behavior at that coordinate

Required regression check:

- current `VdpTopSim` baseline still passes after integration

---

## Hardware Proof

Minimum hardware proof should be simple and highly visible.

Preferred proof:

- map the raster trigger event to a clearly visible diagnostic action with a bounded visual signature

Examples:

- flash a single LED when the programmed line is reached
- switch one layer enable or palette entry only after the trigger line, producing a crisp top/bottom split

Acceptance:

- the split/event must occur at the programmed line on real hardware
- the visible boundary must be stable across repeated runs

Use direct hardware evidence:

- at least one fresh capture-device frame or equivalent hardware confirmation

---

## Audit Questions

CyanPeak should explicitly verify:

- is the implementation still within the bounded scope
- are trigger timing semantics coherent and non-ambiguous
- does the sticky-status behavior make sense for later adapter use
- does the proof use current hardware/software state rather than stale evidence
- did the task accidentally introduce a de facto Copper-lite control plane

---

## Constraints / Gotcha Check

This task must obey:

- GT-022 power-of-two `Mem` rule if any new initialized memory appears
- interface-stability rule from `MODE0_ROADMAP.md`
- no hardware claim before simulation proof
- no mid-line linestate application
- no expansion into fetch scheduling or automation execution

---

## Exit Condition

This task is done when `Mode0` has a hardware-proven programmable raster trigger that asserts cleanly at a specified beam position, exposes coherent sticky status/IRQ behavior, and does so without introducing broader automation or fetch changes.
