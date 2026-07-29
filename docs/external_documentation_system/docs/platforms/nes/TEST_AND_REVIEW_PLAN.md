# NES/Famicom — Test and Review Plan

## Required tests

- NES-SIM-001 2bpp decode
- NES-SIM-002 attribute quadrants
- NES-SIM-003 mirroring
- NES-SIM-004 8/9 sprite boundary
- NES-SIM-005 sprite-zero positive/negative
- NES-HW-001 scrolling/priority

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
