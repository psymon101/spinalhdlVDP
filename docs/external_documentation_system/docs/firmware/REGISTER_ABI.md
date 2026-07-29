# Register ABI

## Single authority

One machine-readable schema owns address, width, access, reset, fields,
commit boundary, description, and reserved values.

## Generated outputs

- SpinalHDL decode/constants;
- C headers;
- human-readable reference;
- reset-value tests;
- optional additional language bindings.

## CI checks

- generated outputs are current;
- no duplicate address;
- field widths fit;
- access type is legal;
- reset values agree with SpinalSim;
- public headers and FPGA decode use the same ABI version.

## Commit boundaries

Every register is marked as immediate, H-boundary, line-boundary, vblank,
explicit commit, or engine-completion.
