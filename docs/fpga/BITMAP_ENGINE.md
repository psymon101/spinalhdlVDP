> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Bitmap Engine

**Status:** draft — under active development in `2bpp-bank-completion-rtl`  
**Owner:** `BrightForge`  
**Reviewer:** `TopazCliff`

## Scope

Bitmap row fetch, line-buffer bank rotation, and display-bank completion for
the Tang Nano 20K VDP.

## SpinalHDL components

- `BitmapRowFetch.scala`
- `VdpTop.scala` (bitmap fetch control integration)

## Clock/reset domain

- Pixel clock domain for display consumption.
- SDRAM clock domain for row fetch.
- CDC between SDRAM and pixel domains.

## Current behavior

- 3-bank line-buffer rotation.
- `fillLine` triggers fetch of the next display row.
- Production uses `fillLine` with depth = 3-bank machinery.
- `bestDv == 3` is the canonical line-doubling offset (ROW-CODED assertion).

## Open hardening — bank completion

The `2bpp-backlog-cosim` result (`5efe049`) shows:

- **Nominal:** zero display-bank violations.
- **Forced-late:** stale-row detector fires (`grantOverflow=25`, wrong-row
  `214/480`) on the current no-`bankReady` design.

Required hardening (tracked in `STATUS.md` lane `2bpp-bank-completion-rtl`):

- pixel-domain completion tokens;
- `bankReady` + `bankRowTag` state;
- display-bank rotation gated on valid + matching tag;
- diagnostics counters: `displayUnderflow`, `grantOverflow`, `rowTagMismatch`.

## Assertions / coverage

- `Indexed2bppBacklogCoSim` must pass nominal and forced-late modes.
- Forced-late must fail before hardening and pass afterward.

## Limitations

- Current design does not explicitly gate bank rotation on completion.
- Pending BrightForge implementation.
