# HDMA and LINESTATE

## Purpose

Apply per-line state efficiently for scrolling, windows, palette, and
platform-specific raster behavior.

## Contract

- table format and address units;
- channel count;
- direct/indirect modes;
- line activation point;
- double buffering/commit;
- out-of-range behavior;
- completion/error flags.

## Tests

- every channel;
- line 0 and last line;
- sparse changes;
- indirect table;
- table swap;
- malformed/end-of-table;
- contention with host/Copper.
