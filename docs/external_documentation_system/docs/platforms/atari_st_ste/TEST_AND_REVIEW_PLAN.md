# Atari ST/STE — Test and Review Plan

## Required tests

- ST-SIM-001 known 16-pixel plane words
- ST-SIM-010 low
- ST-SIM-011 medium
- ST-SIM-012 high
- ST-SIM-020 palette conversion
- ST-SIM-030 raster bars
- ST-HW-001 full/dirty/swap proof

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
