# DMA and Blitter

## Shared operations

- fill;
- copy;
- rectangle operations;
- line operation where supported.

## Contract

- source/destination address units;
- width/height/stride;
- overlap behavior;
- trigger ordering;
- busy/done/error;
- arbitration priority;
- allowed execution during active scanout.

## Tests

- smallest and largest legal operations;
- aligned/unaligned cases permitted by spec;
- overlap;
- busy re-trigger;
- reset;
- scanout contention;
- platform proof images.
