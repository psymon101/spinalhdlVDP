# Buffer and Commit Model

## Principle

The host may prepare state asynchronously, but the FPGA must display only
complete, explicitly committed state.

## Required patterns

- pending/active register banks;
- inactive framebuffer base;
- inactive Copper program bank;
- optional inactive LINESTATE/HDMA table;
- explicit commit/swap request;
- completion/status sequence number.

## Safety requirements

- no swap to an uninitialized bank;
- no partial framebuffer promotion;
- reset invalidates pending work;
- repeated commit is deterministic;
- late commit is counted or deferred, never half-applied.
