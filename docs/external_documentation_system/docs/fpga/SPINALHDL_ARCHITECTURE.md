# SpinalHDL Architecture

## Source organization

Recommended logical packages:

```text
src/main/scala/
├── top/
├── host/
├── memory/
├── timing/
├── engines/
├── adapters/
├── compositor/
├── output/
├── registers/
└── diagnostics/

src/test/scala/
├── unit/
├── integration/
├── adapters/
├── stress/
└── regression/
```

Existing project names may remain, but the mapping must be documented.

## Design rules

- Scala/SpinalHDL is editable source.
- Use explicit Bundles and Streams between components.
- Isolate platform frontends from shared rendering/output.
- Use pending/active banks for vblank-sensitive state.
- Make clock crossings explicit.
- Add assertions for impossible states and overflow.
- Avoid hidden global constants; pass configuration through case classes.
- A new adapter receives its own component and simulation suite.
- Generated Verilog receives no permanent edits.

## Required test levels

1. component unit simulation;
2. adapter simulation;
3. `VdpTop` integration simulation;
4. SDRAM-contention regression;
5. generated-RTL/synthesis gate;
6. hardware proof.

## Generator requirements

The production generator must emit:

- deterministic top name;
- build metadata;
- selected device/profile configuration;
- generated RTL into a clean directory;
- register/interface consistency report.
