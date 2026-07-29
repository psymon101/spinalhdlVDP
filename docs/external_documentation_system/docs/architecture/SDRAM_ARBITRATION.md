# SDRAM and Arbitration Architecture

## Required clients

- scanline/pixel fetch;
- tile and sprite fetch;
- host uploads;
- DMA/Blitter;
- platform-specific prefetch;
- optional diagnostic readback.

## Required contract

The implementation must document:

- SDRAM geometry and address units;
- endianness and alignment;
- burst lengths;
- refresh behavior;
- client priority;
- maximum service latency;
- line-buffer fill deadline;
- host-write behavior during active display;
- bank ownership and frame commit;
- underrun handling and status;
- fairness and starvation prevention.

## Preferred scheduling model

Real-time scanout has bounded service guarantees. Host uploads and Blitter work
use remaining bandwidth or explicitly approved vblank windows. A platform lane
must provide a bandwidth calculation before adding fetch demand.

## Mandatory tests

- simultaneous scanout and host upload;
- refresh at worst scanline point;
- Blitter load during active display;
- forced late service;
- repeated framebuffer swap;
- stale-bank and incomplete-buffer protection;
- deterministic underrun indication.
