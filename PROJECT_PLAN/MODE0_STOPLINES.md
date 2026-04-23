# MODE0_STOPLINES.md

**Updated:** 2026-04-23  
**Purpose:** Quantified stop-line policy for `Mode0` growth on the Tang Nano 20K target. This document exists to prevent vague "maybe it still fits" planning and to force new `Mode0` features to justify their cost in measurable board resources, timing margin, and SDRAM complexity.

---

## Why This Exists

The project needs a way to answer:

- how far can `Mode0` grow on the Tang Nano 20K?
- when is a proposed feature too expensive?
- when should a feature be redesigned, deferred, or rejected?

This file defines **measurable stop-lines**, not intuition.

It does not replace architectural judgment, but it does define the minimum quantitative evidence required before growing the substrate.

---

## Authority

Use this file when evaluating any proposed new `Mode0` primitive, major expansion, or architectural widening.

If this file and `TASKS.md` disagree on execution order, `TASKS.md` wins.

If this file and `PLATFORM.md` disagree on board facts, `PLATFORM.md` wins.

If this file and actual post-P&R reports disagree on current fit, the **post-P&R reports win** and this file must be updated.

---

## Tang Nano 20K Board Budget

Reference board: **Sipeed Tang Nano 20K**

Nominal hardware budget:

- LUT4: `20,736`
- FF: `15,552`
- BSRAM blocks: `46`
- BSRAM bits: `828 Kbit`
- DSP: `48` nominal board spec; use the project tool's effective reported budget for actual stop-line enforcement
- SDRAM: `64 Mbit` on-board SDR SDRAM
- PLL: `2`

Primary source:

- [Tang Nano 20K board spec](https://wiki.sipeed.com/hardware/en/tang/tang-nano-20k/nano-20k.html)

---

## Current Local Baseline

The current project baseline must always be taken from the latest successful local build, not from memory.

At the time this file was written, the local `spinalhdlVDP` build reported approximately:

- LUT/ALU/ROM16: `9566` total (`8873 LUT`, `693 ALU`)
- FF: `6033 / 15552` (`39%`)
- BSRAM: `5 / 46` (`11%`)
- DSP: `18 / 24` (`75%`) as reported by the current Gowin flow
- active clocks include `25.2 MHz` pixel and `64.8 MHz` memory-domain timing, both currently meeting timing

These baseline numbers are a **moving reference point**. They must be refreshed when the project changes materially.

---

## Stop-Line Zones

### Green Zone

A proposed feature is in the green zone when, after integrating it, the design is expected to remain below:

- LUT: `65%`
- FF: `65%`
- BSRAM: `50%`
- DSP: `70%`

and:

- all required clocks still meet timing with non-trivial slack
- SDRAM arbitration remains easy to explain and verify
- no new fragile clock-domain crossings are introduced without strong reason

Green-zone features are generally acceptable if they provide real cross-platform value.

### Yellow Zone

A proposed feature is in the yellow zone when the integrated design is expected to land in any of:

- LUT: `65% .. 80%`
- FF: `65% .. 80%`
- BSRAM: `50% .. 70%`
- DSP: `70% .. 85%`

or:

- timing margin is clearly shrinking
- SDRAM arbitration is materially more complex
- buffering strategy becomes harder to reason about

Yellow-zone features require explicit justification:

- which platforms benefit
- why this belongs in `Mode0`
- why adapter-side policy cannot achieve the goal more cheaply
- what the escape plan is if timing or memory pressure worsens

Yellow-zone features must not be approved as "nice to have."

### Red Zone

A proposed feature is in the red zone when the integrated design is expected to hit any of:

- LUT: `> 80%`
- FF: `> 80%`
- BSRAM: `> 70%`
- DSP: `> 85%`

or:

- timing only barely passes
- timing closure becomes fragile or tool-sensitive
- SDRAM behavior becomes difficult to prove per-line
- the feature demands large buffering or broad architectural special cases

Red-zone features should be:

- rejected
- deferred
- split into smaller parts
- or re-expressed as adapter policy instead of substrate growth

unless they unlock major cross-platform value and no cheaper honest alternative exists.

---

## Video-System Hard Stop Rules

These rules are stricter than raw LUT percentages because they directly affect viability on this board.

### 1. Full Framebuffer Rule

Treat a new feature as **high-risk by default** if it requires:

- a full framebuffer in BRAM, or
- a full framebuffer in SDRAM under active HDMI scanout plus heavy concurrent fetch traffic

Reason:

- framebuffer-heavy designs consume memory rapidly
- scanout bandwidth and synchronization complexity become dominant
- comparable Tang Nano 20K projects have explicitly avoided full-frame buffering for this reason

### 2. SDRAM Client Rule

Treat a new feature as **not ready for approval** if it adds a new SDRAM client and the proposer cannot clearly state:

- when it requests service
- worst-case bandwidth demand per line/frame
- arbitration priority relative to existing clients
- failure behavior when bandwidth is insufficient

If the per-line budget cannot be explained, the feature is not ready.

### 3. Clock-Domain Rule

Treat a new feature as **yellow or red by default** if it introduces:

- a new clock domain
- complex CDC
- additional phase relationships that are not already standard in the repo

Such changes require strong cross-platform value.

### 4. Genericity Rule

If a proposed hardware feature mainly helps one platform and materially increases:

- memory pressure
- arbitration complexity
- timing pressure
- buffering complexity

then it should be assumed **adapter-local or out of scope** unless there is a strong argument that the primitive is broadly reusable.

---

## Required Evidence For Any New Mode0 Primitive

No substantial `Mode0` expansion should be approved without a short evidence block that includes:

- estimated LUT delta
- estimated FF delta
- estimated BSRAM delta
- estimated DSP delta
- estimated SDRAM bandwidth / client delta
- timing-domain impact
- line-buffer / cache impact
- platforms helped
- reason this belongs in `Mode0` instead of an adapter

If exact post-P&R deltas are not available yet, the proposal must still provide bounded estimates and state uncertainty honestly.

No numbers means no approval.

---

## Decision Rule

A proposed feature should normally be approved into `Mode0` only if **all** of the following are true:

1. it has real cross-platform leverage
2. it fits within the current stop-line zone policy
3. its memory and timing impact are stated explicitly
4. it is more honest as a shared primitive than as adapter-local policy
5. its failure mode is understood if the board budget proves tighter than expected

If those conditions are not met, default to:

- defer
- narrow scope
- move to adapter layer
- or reject

---

## Comparison Guidance

Use these practical heuristics during planning:

- prefer line buffers over framebuffers
- prefer one stronger shared primitive over multiple weak platform-specific engines
- prefer beam-synchronous control over duplicating timing engines per platform
- prefer adapter-side clamping of rich primitives over hardware duplication
- distrust any proposal whose main answer is "we can probably fit it"

---

## What Not To Do

- Do not approve a `Mode0` feature on the basis of platform desirability alone.
- Do not use whole-board theoretical limits without checking current project utilization.
- Do not assume SDRAM capacity implies SDRAM bandwidth safety.
- Do not let "just for fun" experiments silently redefine the substrate scope.
- Do not let a red-zone feature enter the mainline without explicit project-level re-approval.
