# REPO_STRUCTURE.md

**Purpose:** Defines the current repository layout. This file describes where things live today. It is not a wish-list for a future refactor.

---

## Top-Level Layout

```text
spinalhdlVDP/
├── PROJECT_PLAN/                # planning and execution docs
├── build.sbt                    # SBT build definition
├── project/                     # SBT project metadata
├── hw/
│   ├── spinal/
│   │   └── spinalhdlvdp/        # current SpinalHDL source package
│   ├── gen/                     # generated Verilog output
│   ├── verilog/                 # optional checked-in Verilog outputs
│   └── vhdl/                    # optional checked-in VHDL outputs
├── fpga/
│   └── tang20k/                 # Tang Nano 20K board build / constraints / transport
├── kb/                          # local hardware and vendor references
└── target/                      # SBT build outputs
```

---

## Current Placement Rules

| What | Where |
|------|-------|
| shared SpinalHDL RTL | `hw/spinal/spinalhdlvdp/` |
| generated RTL output | `hw/gen/` |
| board-specific build flow | `fpga/tang20k/` |
| board-specific constraints | `fpga/tang20k/*.cst`, `fpga/tang20k/*.sdc` |
| board-specific transport Verilog | `fpga/tang20k/*.sv` |
| vendor / external HDL dependencies | `fpga/tang20k/third_party/` |
| local hardware knowledge base | `kb/` |
| project-planning docs | `PROJECT_PLAN/` |

---

## Source Files Present Today

The current shared RTL package contains:

- `Config.scala`
- `GowinPrimitives.scala`
- `Tang20kHdmiTx.scala`
- `TmdsEncoder.scala`
- `TopTang20kHdmi.scala`
- `VdpTop.scala`
- `VdpTopSim.scala`

Do not move these into a new directory tree just because another structure might be cleaner. A package/directory refactor must be a deliberate task with updated build and documentation changes in the same slice.

---

## Hard Rules

- Do not invent new top-level directories without instruction.
- Do not relocate current sources into a speculative `vdp/` tree unless that refactor is explicitly approved and fully coordinated.
- Keep board-specific logic out of shared rendering logic.
- Keep generated artifacts in generated-artifact locations, not mixed into hand-authored source directories.
- When documenting a future intended structure, label it explicitly as future intent rather than present reality.

---

## Package Naming

Current package prefix:

```scala
package spinalhdlvdp
```

Follow the existing package and path unless an explicit refactor task changes both together.

---

## Notes on Future Cleanup

There may eventually be value in splitting shared RTL, wrapper code, and simulations into a deeper subsystem layout. That is not the current repository state. Until such a refactor is intentionally scheduled:

- keep shared Scala source in `hw/spinal/spinalhdlvdp/`
- keep board-flow artifacts in `fpga/tang20k/`
- keep simulations adjacent to the source that owns them if that is the current pattern
