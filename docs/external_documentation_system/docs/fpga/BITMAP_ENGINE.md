# Bitmap Engine

## Target formats

The final authoritative encoding must be reconciled before Foundation 0 closes.
The intended generic capability set includes packed indexed 1/2/4/8bpp and
RGB565. Unsupported/reserved codes must be explicit.

## Required configuration

- enable;
- format;
- base;
- stride;
- source width/height;
- pending base;
- explicit swap/commit;
- transparency or palette bank where applicable.

## SpinalSim

For every format, verify bit order, byte order, odd widths, stride padding,
base alignment, line start/end, clipping, swap boundaries, and reset.
