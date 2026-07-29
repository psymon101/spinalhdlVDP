# Source-of-Truth and Generated-Artifact Policy

## Authority order

1. Approved normative specification
2. SpinalHDL source
3. SpinalSim regression
4. Register schema
5. `libvdp` public API and implementation
6. Generated Verilog
7. Firmware examples
8. Visual captures

A conflict must be opened as a reconciliation task. Do not silently choose one
side or patch generated output.

## Generated artifacts

- generated Verilog;
- generated C/Scala register bindings;
- compiled firmware;
- Gowin project outputs;
- bitstreams;
- converted assets;
- test reports.

Every generated artifact must identify source inputs and generator versions.
