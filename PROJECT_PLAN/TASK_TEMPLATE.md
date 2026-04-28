# TASK_TEMPLATE.md

**Updated:** 2026-04-12  
**Purpose:** Reusable planning template for converting a roadmap item into a bounded execution task. Use this before opening a new post-roadmap implementation lane.

---

## Why This Exists

`MODE0_PLANNING.md` §3 (Strategic Roadmap) defines strategic capability order.

`TASKS.md` is the authoritative execution/status ledger.

This template sits in between:

- it turns one roadmap primitive into a concrete, bounded execution task
- it makes task specs consistent
- it reduces drift between PM, coding, and audit lanes

Do not paste this template directly into `TASKS.md`. Fill it first, then translate the result into the execution format you want to use.

---

## Task Planning Template

### 1. Task Name

Short, specific primitive name.

Example:

- `Raster Trigger Unit`
- `2-Pass Sprite Evaluator`
- `Scroll Table Primitive`

### 2. Purpose

State what gap this task closes and why it matters to `Mode0`.

Questions to answer:

- what primitive is being added
- which platform pressures make it necessary
- why this task is the right next step now

### 3. Primitive Boundary

Define exactly what is in scope and out of scope.

Use both:

- **in scope**
- **explicitly out of scope**

This is the most important section for preventing scope creep.

### 4. Dependencies

List the prerequisites that must already be proven.

Types of dependency to call out:

- prior Mode0 primitive dependencies
- architectural prerequisites
- required refactors
- required documentation or register-contract prerequisites

### 5. Interfaces

List the new or changed interfaces introduced by the task.

Include as needed:

- top-level signals
- internal interfaces
- registers
- status bits
- interrupt/event outputs
- control inputs
- metadata bits carried between stages

### 6. Data Model

Describe the state this primitive owns.

Examples:

- counters
- comparators
- memories
- buffers
- flags
- tables
- descriptor records

Questions:

- what is persistent vs per-line vs per-frame
- what is programmable vs internal-only
- what needs GT-022 power-of-two handling

### 7. Timing Model

Describe when the primitive runs and when its outputs become valid.

Examples:

- per-pixel
- per-tile
- per-line
- beam-triggered
- blanking-only
- scheduled fetch-slot

Questions:

- what clock domain(s) are involved
- what event edge defines correctness
- what can and cannot happen mid-line

### 8. Memory / Bandwidth Impact

Describe how this task changes memory use or arbitration pressure.

Include:

- SDRAM use
- on-chip RAM use
- line-buffer pressure
- table-memory additions
- arbitration changes
- whether prefetch/cache/shadowing is required

### 9. Platform Reuse

List which platforms benefit and how.

Keep it compact:

- primary beneficiaries
- secondary beneficiaries
- whether this is foundational or optional for each

### 10. Failure Modes / Risks

List the most likely things to go wrong.

Examples:

- CDC / pulse-crossing issues
- off-by-one raster timing
- priority/compositor mistakes
- GT-022 inferred-memory failures
- bandwidth underrun
- synthesis optimization hazards
- refactor regressions

### 11. Validation Plan

Define the sim-side proof before hardware.

Questions:

- what dedicated sim/testbench is required
- what assertions must exist
- what matrix of cases must pass
- what old proofs must be rerun after the change

### 12. Hardware Proof

Define the hardware-visible proof required to call the task done.

Examples:

- one visible scene
- one diagnostic pattern
- one IRQ/status observation
- one capture-device confirmation
- one soak window

This should be specific enough that audit can reject ambiguous proof.

### 13. Audit Questions

List the exact questions CyanPeak should answer.

Examples:

- does the implementation match the bounded scope
- are the new interfaces coherent
- does the proof match the current code
- did the task accidentally imply a larger architectural commitment

### 14. Constraints / Gotcha Check

Call out the known project constraints this task touches.

Examples:

- GT-022 power-of-two `Mem` rule
- SDRAM-latency awareness
- interface-stability rule
- no mid-line linestate application
- no hardware before sim

### 15. Exit Condition

One sentence only.

Format:

- “This task is done when …”

If you cannot write this in one sentence, the task is probably too broad.

---

## Short-Form Execution Packet Template

Use this when turning the planning notes into a team-facing task packet.

```markdown
## Task
[Task name]

## Purpose
[1 short paragraph]

## Scope
- in scope: ...
- in scope: ...
- out of scope: ...
- out of scope: ...

## Dependencies
- ...

## Interfaces / State
- ...

## Timing / Memory Notes
- ...

## Risks
- ...

## Validation
- sim: ...
- hardware: ...

## Audit Focus
- ...

## Exit Condition
- This task is done when ...
```

---

## Working Rule

If a task cannot be planned cleanly with this template, do not open the lane yet.

Instead:

- split the task
- add a prerequisite task
- or clarify the architecture first

## 100% Verification Rule (Mandatory)

**Every task must be proven 100% before closeout. No exceptions.**

- Ambiguous or "probably correct" states are not acceptable because any uncertainty forces a future backtrack to this exact point.
- Simulator proof alone is not sufficient for hardware-facing primitives; an unambiguous hardware proof is also required.
- If visual proof is noisy or ambiguous, a dedicated diagnostic asset/probe must be created to resolve the ambiguity.
- A task is not closed until the final evidence is definitive and reproducible.
