# Task — Mode0 Fetch Envelope Hardening

**Artifact version:** 1.1  
**Author:** BronzeGate  
**Date:** 2026-04-23  
**Status:** DONE — audited and closed. See `TASKS.md` live-lane history.  
**Coding authorized:** YES — CyanPeak audit PASS #8546/#8553

---

## Sub-Slice Tracker

| Sub-Slice | Description | Commit | Audit | Evidence |
|-----------|-------------|--------|-------|----------|
| H-1 | `BitplaneReconstruct` — generic N-plane (1..8) reconstruction | `bc6f5d4` | PASS #8539 | 5/5 sim cases PASS |
| H-2 | `BitplaneRowFetch` — per-scanline `dout32` bitplane fetcher | `7a62faa` | PASS #8546 | 301 cycles for 50 reads; sim PASS |
| H-3 | `SdramTileAttributeFetch` planar decode → `BitplaneReconstruct` | `07a5507` | PASS #8546 | Bit-identical regression; 17/17 sim cases PASS |
| H-3b | `PlanarLineFetch` — composite H-1 + H-2 end-to-end | `350a3e9` | PASS #8553 | 320/320 pixels correct; rowReady at 301 cycles |
| H-4 | Bandwidth pre-verification (cycle budget vs. H-Blank window) | `350a3e9` | PASS #8553 | ~4.7 µs fetch fits in ~7 µs H-Blank |
| H-5 | Hardware proof on Tang Nano 20K (`Hdmi720pPlanarProofTop`) | `dcb5b2f` | PASS #8553 | 3× reflash 8-bar SMPTE lock; 0 timing violations |

---

## 1. Executive Summary

The project now has multiple real fetch primitives:

- tile + attribute fetch
- bitmap + attribute fetch
- planar fetch
- shuffled / dual-base fetch
- affine groundwork

But the new planning stack (`MODE0_MAX_CAPABILITIES.md`, `MODE0_COVERAGE_MATRIX.md`, `MODE0_HARDENING_BACKLOG.md`) says the open question is no longer "do we have a fetch primitive at all?" It is:

> Is the current fetch envelope **strong enough** to support serious future adapters as a shared `Mode0` substrate, or is it only strong enough for bounded proofs?

This task does **not** add a new platform adapter. It defines and validates the target level for the shared fetch envelope so later adapters do not force platform-specific engines or mid-lane substrate rescues.

**Current PM recommendation:** this is the highest-priority shared hardening lane after the planning stack closed.

---

## 2. Why This Task Exists

The coverage matrix currently says:

- tile + attribute fetch is `Strong`
- bitmap + attribute fetch is `Strong`
- planar fetch is only `Usable`
- shuffled / non-linear fetch is only `Usable`
- scheduler / memory arbitration is only `Usable`

That means the next high-value question is:

- can Amiga / Atari ST / ZX Spectrum / stronger C64 bitmap use-cases sit on the current shared fetch machinery honestly?

If the answer is "not yet," the project should harden the shared fetch envelope now instead of opening a hard adapter lane and discovering the weakness halfway through.

---

## 3. Scope

### In Scope

This lane is about **shared fetch-envelope definition and bounded hardening**, not platform emulation.

In scope:

1. define the minimum acceptable fetch envelope for:
   - planar fetch
   - shuffled / non-linear fetch
   - bitmap + attribute fetch under serious adapter pressure
2. identify what is already strong enough versus what still needs shared hardening
3. prove that the current scheduler/memory model can support those fetch modes without violating `MODE0_STOPLINES.md`
4. define the exact adapter-local boundaries so future adapters do not push layout quirks back into `Mode0`
5. produce a bounded proof plan and acceptance criteria for future implementation work if hardening is required

### Explicitly Out of Scope

- no new Amiga adapter
- no new Atari ST adapter
- no new ZX Spectrum adapter
- no whole-new fetch engine invented "just in case"
- no widening into full-frame framebuffer design
- no platform-specific register map work

---

## 4. Target Level

This task should treat the fetch envelope as strong enough only if all of the following are true.

### 4.1 Tile + Attribute Path

Already close to strong. The lane should confirm that:

- it remains the default general path for NES / Genesis / SNES / TMS-family-class backgrounds
- no known future adapter requires a separate tile-engine architecture

### 4.2 Bitmap + Attribute Path

Must be strong enough that:

- ZX Spectrum-style 1bpp + attribute rendering is honest on the current substrate
- C64 bitmap-style 1bpp/2bpp use remains adapter-local semantics over the existing primitive
- attribute-cell restrictions remain adapter-visible and are not silently generalized away

### 4.3 Planar Path

Must be strong enough that:

- Amiga/ST-class planning can treat planar fetch as a real shared primitive, not a toy proof
- the current implementation can be described in terms of reusable `Mode0` machinery rather than a one-off decode experiment
- any still-missing shared features are explicitly listed

### 4.4 Shuffled / Non-Linear Path

Must be strong enough that:

- ZX Spectrum-style and similar non-linear fetch layouts can be expressed without a platform-specific engine
- adapter-local exact memory-map rules stay in the adapter while the fetch substrate remains general

### 4.5 Scheduler / Memory Fit

Must be strong enough that:

- the current scheduler/arbitration model can explain worst-case service for these fetch modes
- no proposed hardening step assumes undefined SDRAM bandwidth
- the lane can name any red/yellow-zone risk against `MODE0_STOPLINES.md`

---

## 5. Pressure Cases To Evaluate

The lane must explicitly evaluate the current fetch envelope against these platform pressures:

### ZX Spectrum

- bitmap + attribute pairing
- non-linear display memory
- color-cell restrictions / clash visibility
- bordered compact active area presentation

### Commodore 64 (bitmap-oriented pressure only)

- 1bpp and 2bpp bitmap-style use
- per-cell color behavior
- no assumption that all C64 work is tile/text-only

### Amiga (shared fetch pressure only)

- planar fetch as a real substrate
- display-window style fetch expectations
- no claim of full Amiga adapter readiness, only shared planar capability strength

### Atari ST

- planar framebuffer pressure
- raster-timing-sensitive display behavior

---

## 6. Required Outputs

This lane should end with the following concrete outputs:

1. **Fetch-envelope assessment**
   - what is already genuinely strong
   - what is merely usable
   - what shared gaps remain

2. **Boundary clarification**
   - which exact problems remain adapter-local
   - which exact problems still belong in `Mode0`

3. **Budget check**
   - whether likely next fetch hardening steps appear green/yellow/red under `MODE0_STOPLINES.md`

4. **Follow-on task recommendation**
   - either:
     - "current fetch envelope is strong enough; open future adapter lanes"
   - or:
     - "open bounded shared hardening task(s) X/Y before harder adapters"

---

## 7. Acceptance Criteria

This task is successful only if it answers all of these clearly:

1. Can current planar fetch honestly support serious future Amiga/ST-oriented adapter planning without a second fetch engine?
2. Can current shuffled/bitmap+attribute fetch honestly support Spectrum-class work without platform-specific substrate forks?
3. Are the remaining fetch problems mostly:
   - substrate-hardening issues, or
   - adapter-local semantics/presentation issues?
4. Can the likely next fetch-hardening step be described with explicit budget and memory-pressure expectations?

If any of these remain vague, the task is incomplete.

---

## 8. Evidence Requirements

Because this is a hardening/planning lane rather than raw implementation, the evidence should include:

- references to already-closed fetch tasks and proof scenes
- explicit comparison against the fetch categories in `MODE0_MAX_CAPABILITIES.md`
- explicit comparison against the current statuses in `MODE0_COVERAGE_MATRIX.md`
- explicit budget framing using `MODE0_STOPLINES.md`
- if implementation changes are proposed, estimated LUT/FF/BSRAM/DSP/SDRAM impact

No "probably good enough" conclusion is acceptable.

---

## 9. Initial Recommendation

Current hypothesis before audit:

- tile + attribute is already strong enough
- bitmap + attribute is likely strong enough as a substrate primitive
- planar and shuffled paths are the main shared hardening questions
- scheduler/memory explanation is likely the gating issue for any further fetch expansion

This hypothesis must be audited, not assumed.

---

## 10. Files Expected In Follow-On Work

If the lane later turns into implementation work, likely touch points would be among:

- `hw/spinal/spinalhdlvdp/SdramTileAttributeFetch.scala`
- `hw/spinal/spinalhdlvdp/BitmapFetch.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala`
- fetch sims and mixed-scene proofs

But this artifact itself authorizes no such changes yet.

---

## 11. Exit Condition

This lane is done when the team has an audited answer to:

- whether the current shared fetch envelope is strong enough for serious future adapter work
- which bounded shared hardening step, if any, should come next

If the result is "hardening still needed," the next task should be opened immediately with a bounded scope and explicit stop-line-aware budget expectations.
