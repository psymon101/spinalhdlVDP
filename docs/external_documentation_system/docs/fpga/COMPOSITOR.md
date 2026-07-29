# Compositor

## Input

Layer and sprite pixel candidates with color/index, transparency, priority,
source, and collision metadata.

## Responsibilities

- resolve backdrop;
- platform-mapped priority;
- window/mask selection;
- sprite/background collision;
- color math/effects;
- emit one logical pixel.

## Rule

Platform adapters may supply priority metadata or a compact priority mode, but
must not duplicate the full compositor.

## Tests

Truth-table tests are required for every priority/color-math mode used by a
platform.
