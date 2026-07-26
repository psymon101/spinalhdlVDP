# Commodore 64 VIC-II — Test and Review Plan

## Required tests

- C64-SIM-001 each display mode
- C64-SIM-010 multicolor grouping
- C64-SIM-020 border/raster bars
- C64-SIM-030 sprite modes
- C64-SIM-040 collisions
- C64-HW-001 multiplex-style proof

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
