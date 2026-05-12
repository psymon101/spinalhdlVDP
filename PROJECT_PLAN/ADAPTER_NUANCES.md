# ADAPTER_NUANCES.md

> **This file is retired as a live per-platform truth source.**
>
> All live per-platform nuance content has been migrated to the canonical
> adapter knowledge files under `kb/<Adapter>/README.md`.
>
> For the central adapter index and honesty matrix, see
> [`PLATFORM_ADAPTERS.md`](PLATFORM_ADAPTERS.md).
>
> Full historical specs remain archived in `PROJECT_PLAN/archive/adapters/`.

---

## Generic Adapter Fidelity Checklist

The following standards apply to all platform adapters. They are foundational
to the definition of a "platform adapter" in this project.

### General Capability Rule

- `Mode0` owns the reusable superset capability.
- Adapters own platform-specific register semantics, limits, quirks, and presentation choices.
- An adapter may clamp or subset a richer `Mode0` primitive to match its target platform.
- A more demanding adapter may use more of the same primitive's range without requiring a second engine.

### Transport Separation Rule

- Each external host transport must be internally complete and self-consistent.
- `QSPI` is one complete transport contract; a future parallel bus is a separate contract.
- Transports must not borrow hidden timing, framing, or completion assumptions from each other.

### Required Documentation

Every adapter kb file must document:

- Native logical resolution or tile/cell structure
- Intended output scaling / aspect treatment on the fixed HDMI raster
- Border / overscan / display-window behavior
- Palette rules (circuitry-aware, not generic RGB)
- Attribute / fetch-layout quirks
- Timing-visible or artifact-like presentation quirks
- Which `Mode0` primitives those behaviors depend on

### Gap Analysis

Adapters must include an explicit "honest gap analysis" section listing:

- Features that are emulated.
- Features that are deliberately omitted and why.
- Features that are architecturally impossible on the current Mode0 substrate.

### What Not To Do

- Do not erase platform-specific color restrictions just to make the output look "cleaner".
- Do not replace platform-visible pixel/cell quirks with a universal square-pixel assumption unless the adapter explicitly documents that compromise.
- Do not silently upgrade a platform from cell-attribute color rules to per-pixel free color.
- Do not claim cycle-accurate hardware behavior from a presentation-only proof.
