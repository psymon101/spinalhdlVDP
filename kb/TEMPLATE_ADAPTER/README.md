> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# `<Adapter>` — Platform Adapter

**Status:** template / not active  
**Owner:** `BronzeGate` (firmware) / `BrightForge` (FPGA) / `TopazCliff` (interface)  
**Activation:** PM-authorized lane only

## Purpose

One-sentence description of the video chipset being adapted.

## Adapter-specific documents

| Document | Owner | Purpose |
|---|---|---|
| `VIDEO_MODEL.md` | CyanPeak research; TopazCliff approves | Visible behavior, modes, limits, raster effects |
| `MEMORY_AND_REGISTERS.md` | TopazCliff | Register map and memory layout |
| `FPGA_SPINALHDL_PLAN.md` | BrightForge | FPGA implementation plan |
| `FIRMWARE_LIBVDP_PLAN.md` | BronzeGate | `libvdp` adapter implementation plan |
| `TEST_AND_PROOF_PLAN.md` | BrightForge + BronzeGate | Verification and hardware proof |
| `LIMITATIONS.md` | TopazCliff | Known exclusions and deferred behavior |
| `REFERENCES.md` | CyanPeak | Primary sources and exact citations |

## Do not duplicate

- register addresses;
- API signatures;
- live status;
- active task state;
- actual results;
- release hashes.
