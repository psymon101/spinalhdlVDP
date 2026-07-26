# Clock, Reset, and CDC Contract

## Required locked table

Foundation Gate 0 must populate exact clock sources, frequencies, PLL outputs,
reset polarity, and reset release order.

| Domain | Source | Frequency | Reset | Consumers |
|---|---|---:|---|---|
| Host bridge | TBD | TBD | TBD | host decoder/FIFOs |
| Core/video | TBD | TBD | TBD | engines/compositor |
| Pixel | TBD | TBD | TBD | timing/scaler |
| SDRAM | TBD | TBD | TBD | controller/arbiter |
| HDMI/TMDS | TBD | TBD | TBD | serializer |

## CDC rules

Every crossing must use one of:

- two-flop synchronization for stable single-bit state;
- toggle/pulse synchronizer for events;
- `StreamFifoCC` or approved asynchronous FIFO for streams;
- request/acknowledge handshake for multibit snapshots;
- dual-clock memory with documented collision semantics.

Direct combinational crossings are forbidden.

## Reset requirements

- all FIFOs return empty;
- active/pending register banks return to defined defaults;
- scanout produces a stable safe image;
- SDRAM clients remain blocked until initialization completes;
- mode switching cannot leave mixed old/new configuration;
- reset tests cover assertion during idle and active scanout.
