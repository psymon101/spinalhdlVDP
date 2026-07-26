# Sprite Engine

## Shared capability

- descriptor RAM;
- configurable platform-visible limits;
- per-line evaluation;
- pattern fetch;
- flip, size, palette, priority, transparency;
- collision participation;
- overflow reporting.

## Rule

The shared engine may support a larger ceiling than a platform. The platform
adapter must enforce original limits and status behavior.

## Tests

- 0, 1, maximum, and maximum+1 sprites per line;
- transparent pixels;
- overlap order;
- clipping;
- large/doubled sprites;
- collision flags;
- repeated mode changes;
- worst-case fetch load.
