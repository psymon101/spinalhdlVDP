# SNES Modes 0–3-lite — Test and Review Plan

## Required tests

- SNES-SIM-001..004 modes 0–3
- SNES-SIM-010 all tile depths
- SNES-SIM-020 four-layer priority
- SNES-SIM-030 32/33 sprite boundary
- SNES-SIM-040 windows
- SNES-SIM-050 color math
- SNES-SIM-060 HDMA
- SNES-HW-001 mode suite

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
