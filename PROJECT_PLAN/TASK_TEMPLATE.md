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

Fill every section below. If a section cannot be answered briefly, the task is too broad.

| # | Section | Purpose | Max Lines |
|---|---------|---------|-----------|
| 1 | Task Name | Short primitive name | 3 |
| 2 | Purpose | Gap closed + why now | 5 |
| 3 | Primitive Boundary | In scope / out of scope | 10 |
| 4 | Dependencies | Prerequisites | 5 |
| 5 | Interfaces | New/changed signals, registers, buses | 8 |
| 6 | Data Model | State owned by this primitive | 8 |
| 7 | Timing Model | When it runs, when outputs valid | 6 |
| 8 | Memory / Bandwidth | SDRAM, RAM, buffer, arbiter changes | 6 |
| 9 | Platform Reuse | Beneficiaries | 5 |
| 10 | Failure Modes / Risks | Likely things to go wrong | 6 |
| 11 | Validation Plan | Sim proof required | 6 |
| 12 | Hardware Proof | Hardware-visible proof required | 6 |
| 13 | Audit Questions | Exact questions for CyanPeak | 5 |
| 14 | Constraints / Gotchas | Project constraints touched | 5 |
| 15 | Exit Condition | One-sentence done criterion | 2 |

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
