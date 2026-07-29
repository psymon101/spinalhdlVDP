# Tile Engine

## Shared capability

- configurable tile width/height;
- packed and native planar pattern decode;
- tilemap fetch;
- palette bank;
- horizontal/vertical flip;
- priority;
- transparent index;
- scroll offsets;
- optional per-row/per-column state.

## Platform use

TMS9918A, SMS/Game Gear, NES, Genesis, and SNES adapters map native entries
into this substrate or add a narrow native decoder before it.

## Tests

- every supported BPP;
- flip/priority/palette combinations;
- map wrapping;
- edge clipping;
- scroll boundaries;
- cache/prefetch behavior;
- contention stress.
