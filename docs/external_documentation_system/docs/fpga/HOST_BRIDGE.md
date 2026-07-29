# Host Bridge

## Purpose

Translate the physical host bus into transport-neutral register, SDRAM, status,
and control operations.

## Required operations

- register write;
- register burst write;
- register read;
- SDRAM write;
- optional SDRAM diagnostic read;
- status/capability read;
- reset and error clear.

## SpinalHDL boundaries

Use a transport decoder to produce internal Streams/Flows. Physical QSPI, i80,
or future parallel decoders must not directly modify video-engine state.

## Verification

- framing and endianness;
- minimum/maximum length;
- malformed command;
- CRC/parity where enabled;
- burst auto-increment;
- backpressure;
- reset mid-command;
- read turnaround;
- dropped/short transaction diagnostics.
