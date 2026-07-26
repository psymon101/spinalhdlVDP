> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Platform Adapter Knowledge Base

Each platform adapter has one canonical directory under `kb/<Adapter>/`.

## Canonical adapter structure

```text
kb/<Adapter>/
├── README.md
├── VIDEO_MODEL.md
├── MEMORY_AND_REGISTERS.md
├── FPGA_SPINALHDL_PLAN.md
├── FIRMWARE_LIBVDP_PLAN.md
├── TEST_AND_PROOF_PLAN.md
├── LIMITATIONS.md
└── REFERENCES.md
```

See `kb/TEMPLATE_ADAPTER/` for a populated template.

## Existing adapters

Existing closed adapters remain as-is until materially reopened. New and
reopened adapters must use the canonical structure above.

## Authority

- `VIDEO_MODEL.md` — CyanPeak research; TopazCliff approves.
- `MEMORY_AND_REGISTERS.md` — TopazCliff; BrightForge/BronzeGate review.
- `FPGA_SPINALHDL_PLAN.md` — BrightForge; TopazCliff review.
- `FIRMWARE_LIBVDP_PLAN.md` — BronzeGate; TopazCliff review.
- `TEST_AND_PROOF_PLAN.md` — BrightForge + BronzeGate.
- `LIMITATIONS.md` — TopazCliff; CyanPeak/CoralReef review.
- `REFERENCES.md` — CyanPeak; TopazCliff review.
