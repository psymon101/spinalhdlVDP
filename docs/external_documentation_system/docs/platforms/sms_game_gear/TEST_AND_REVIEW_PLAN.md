# Sega Master System and Game Gear — Test and Review Plan

## Required tests

- SEGA8-SIM-001 tile decode/flip
- SEGA8-SIM-002 priority
- SEGA8-SIM-003 scrolling
- SEGA8-SIM-004 sprite boundary
- SEGA8-SIM-005 palette conversion
- SEGA8-HW-001 SMS/GG paired proof

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
