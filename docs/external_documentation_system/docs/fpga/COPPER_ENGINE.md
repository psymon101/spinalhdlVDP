# Copper Engine

## Purpose

Execute beam-synchronized register changes without requiring the host to meet
pixel deadlines.

## Instruction classes

- wait by line;
- wait by X/Y;
- register write;
- sequential write;
- jump;
- conditional skip;
- end/stop behavior as defined by the approved ISA.

## State model

- double-buffered program storage;
- inactive-bank upload;
- explicit swap request;
- vblank commit;
- deterministic reset;
- late/missed-wait diagnostics.

## Tests

- exact boundary matches;
- missed boundary;
- program swap;
- stale-bank protection;
- write to each allowed register class;
- jump/skip bounds;
- maximum program;
- reset during execution.
