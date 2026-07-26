# Generic Mode0 — Test and Review Plan

## Required tests

- MODE0-SIM-001 each bitmap format
- MODE0-SIM-010 plane counts 1–6
- MODE0-SIM-020 four-layer priority
- MODE0-SIM-030 sprite maximum and overflow
- MODE0-SIM-040 Copper raster bars
- MODE0-SIM-050 HDMA/LINESTATE per-line scroll
- MODE0-SIM-060 DMA/Blitter operations
- MODE0-HW-001 repeated reset/mode-switch/soak

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.
