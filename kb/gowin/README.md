# Gowin / Tang20K Reference Index

Purpose: keep a single local index of the Gowin and Tang Nano 20K docs that matter for this repo, with enough context that engineers know which guide to open first.

Status:
- `Present locally` means the PDF is already in this repo.
- `Missing locally` means the guide is not checked into this repo as of 2026-03-14, but an official Gowin page/link was identified.

Related repo-local note:
- [GOTCHAS.md](/home/itadmin/github/spinalhdlVDP/kb/gowin/GOTCHAS.md) tracks Gowin/Tang20K implementation hazards and Task 15-relevant advisory notes. Treat imported entries there as advisory until validated in this repo.

## Use First

### Present locally

`SUG113_Gowin_FPGA_Design_User_Guide.pdf`
- Path: [SUG113_Gowin_FPGA_Design_User_Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/SUG113_Gowin_FPGA_Design_User_Guide.pdf)
- Use when: deciding general Gowin coding style, CDC strategy, memory/FIFO direction.
- Key sections:
  - `2.1.9 Cross Clock Domains`
  - `2.1.10 Memory Coding`
- Most relevant guidance:
  - single-bit CDC: use two/three-stage registers
  - multi-bit CDC: use asynchronous FIFOs
  - for Gowin devices, block memory and FIFO generation via IP Core Generator is recommended

`SUG550_Gowin_Synthesis_User_Guide.pdf`
- Path: [SUG550_Gowin_Synthesis_User_Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/SUG550_Gowin_Synthesis_User_Guide.pdf)
- Use when: checking inference behavior and synthesis attributes.
- Key sections:
  - `4.2 RAM HDL Code Support`
  - `5.16 syn_ramstyle`
- Most relevant to current blocker:
  - this is the first guide to check before forcing RAM/FIFO storage into flip-flops with `syn_ramstyle="registers"`

`SUG100_Gowin_Software_User_Guide.pdf`
- Path: [SUG100_Gowin_Software_User_Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/SUG100_Gowin_Software_User_Guide.pdf)
- Use when: navigating the Gowin flow, IP generator, reports, and related-document pointers.
- Most relevant note:
  - lists related Gowin user guides including `UG285` BSRAM & SSRAM and other device guides not currently checked into this repo

`SUG935_Gowin_Design_Physical_Constraints_User_Guide.pdf`
- Path: [SUG935_Gowin_Design_Physical_Constraints_User_Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/SUG935_Gowin_Design_Physical_Constraints_User_Guide.pdf)
- Use when: placement/physical constraint issues arise.

`SUG940_Gowin_Design_Timing_Constraints_User_Guide.pdf`
- Path: [SUG940_Gowin_Design_Timing_Constraints_User_Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/SUG940_Gowin_Design_Timing_Constraints_User_Guide.pdf)
- Use when: timing closure or CDC timing constraints need exact syntax/behavior.

`IPUG279E_SDRAM_Controller_IP.pdf`
- Path: [IPUG279E_SDRAM_Controller_IP.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/IPUG279E_SDRAM_Controller_IP.pdf)
- Use when: SDRAM controller IP behavior, ports, or timing assumptions need confirmation.

`UG285-1.4E_Gowin BSRAM & SSRAM User Guide.pdf`
- Path: [UG285-1.4E_Gowin BSRAM & SSRAM User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG285-1.4E_Gowin%20BSRAM%20%26%20SSRAM%20User%20Guide.pdf)
- Use when: exact BSRAM behavior, port modes, and memory semantics matter.
- Most relevant to current blocker:
  - this is the first missing piece we needed for the HSTX FIFO rewrite
  - use it together with the FIFO guide to pin down read latency and storage behavior

`UG289-2.2.1E_Gowin Programmable IO (GPIO) User Guide.pdf`
- Path: [UG289-2.2.1E_Gowin Programmable IO (GPIO) User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG289-2.2.1E_Gowin%20Programmable%20IO%20(GPIO)%20User%20Guide.pdf)
- Use when: I/O standards, pad behavior, or GPIO-related board integration details matter.

`UG290-2.9E_Gowin FPGA Products Programming and Configuration Guide.pdf`
- Path: [UG290-2.9E_Gowin FPGA Products Programming and Configuration Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG290-2.9E_Gowin%20FPGA%20Products%20Programming%20and%20Configuration%20Guide.pdf)
- Use when: configuration/bitstream/programming behavior needs vendor confirmation.

`UG295-1.4.5E_Gowin User Flash User Guide.pdf`
- Path: [UG295-1.4.5E_Gowin User Flash User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG295-1.4.5E_Gowin%20User%20Flash%20User%20Guide.pdf)
- Use when: user flash behavior or image/data placement in flash becomes relevant.

`DS100-3.3.1E_GW1N series of FPGA Products Data Sheet.pdf`
- Path: [DS100-3.3.1E_GW1N series of FPGA Products Data Sheet.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/DS100-3.3.1E_GW1N%20series%20of%20FPGA%20Products%20Data%20Sheet.pdf)
- Use when: you need a Gowin family-level datasheet example and the exact GW2AR family sheet is unavailable locally.
- Note: this is `GW1N`, not the Tang Nano 20K's `GW2AR` family, so do not treat it as the final source of truth for device limits.

`UG103-2.9.4E_GW1N series of FPGA Products Package & Pinout User Guide.pdf`
- Path: [UG103-2.9.4E_GW1N series of FPGA Products Package & Pinout User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG103-2.9.4E_GW1N%20series%20of%20FPGA%20Products%20Package%20%26%20Pinout%20User%20Guide.pdf)
- Use when: package/pinout documentation format is needed as a reference.
- Note: also `GW1N`, not `GW2AR`.

`UG114-1.9E_GW1N-9 Pinout.pdf`
- Path: [UG114-1.9E_GW1N-9 Pinout.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG114-1.9E_GW1N-9%20Pinout.pdf)
- Note: helpful as a pinout reference template, but not the correct family for Tang Nano 20K.

`UG171-1.8.1E_GW1N-2 Pinout.pdf`
- Path: [UG171-1.8.1E_GW1N-2 Pinout.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG171-1.8.1E_GW1N-2%20Pinout.pdf)
- Note: helpful as a pinout reference template, but not the correct family for Tang Nano 20K.

`TN662-1.3.1E_Gowin FPGA-based DDR2&DDR3 Hardware Design Reference Manual.pdf`
- Path: [TN662-1.3.1E_Gowin FPGA-based DDR2&DDR3 Hardware Design Reference Manual.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/TN662-1.3.1E_Gowin%20FPGA-based%20DDR2%26DDR3%20Hardware%20Design%20Reference%20Manual.pdf)
- Use when: board-level DDR/SDRAM layout or signal-integrity guidance becomes relevant.

`IPUG760-1.2E_Gowin FIFO HS User Guide.pdf`
- Path: [IPUG760-1.2E_Gowin FIFO HS User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/IPUG760-1.2E_Gowin%20FIFO%20HS%20User%20Guide.pdf)
- Use when: exact asynchronous FIFO semantics matter.
- Most relevant to current blocker:
  - read latency
  - first-word fall-through vs registered output
  - full/empty/almost-full behavior
  - reset semantics

`IPUG105-1.08E_Gowin FIFO IP User Guide.pdf`
- Path: [IPUG105-1.08E_Gowin FIFO IP User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/IPUG105-1.08E_Gowin%20FIFO%20IP%20User%20Guide.pdf)
- Use when: generic Gowin FIFO IP behavior is enough and HS-specific details are not required.

`IPUG1028-1.0E_Gowin AXI-Stream FIFO IP User Guide.pdf`
- Path: [IPUG1028-1.0E_Gowin AXI-Stream FIFO IP User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/IPUG1028-1.0E_Gowin%20AXI-Stream%20FIFO%20IP%20User%20Guide.pdf)
- Use when: AXI-stream FIFO semantics or interface conventions become relevant.

`UG286-2.0.2E_Gowin Clock User Guide.pdf`
- Path: [UG286-2.0.2E_Gowin Clock User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG286-2.0.2E_Gowin%20Clock%20User%20Guide.pdf)
- Use when: PLL/clocking/CDC assumptions need exact vendor guidance.

`UG287-1.4E_Gowin Digital Signal Processing (DSP) User Guide.pdf`
- Path: [UG287-1.4E_Gowin Digital Signal Processing (DSP) User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG287-1.4E_Gowin%20Digital%20Signal%20Processing%20(DSP)%20User%20Guide.pdf)
- Use when: deciding whether arithmetic should move into DSP resources.

`DS226-2.6E_GW2AR series of FPGA Products Data Sheet.pdf`
- Path: [DS226-2.6E_GW2AR series of FPGA Products Data Sheet.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/DS226-2.6E_GW2AR%20series%20of%20FPGA%20Products%20Data%20Sheet.pdf)
- Use when: exact GW2AR family device limits, architecture, and resource facts are needed.
- This is the correct family-level datasheet for the Tang Nano 20K's GW2AR path.

`UG229-1.6.3E_GW2AR series of FPGA Products Package & Pinout User Guide.pdf`
- Path: [UG229-1.6.3E_GW2AR series of FPGA Products Package & Pinout User Guide.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG229-1.6.3E_GW2AR%20series%20of%20FPGA%20Products%20Package%20%26%20Pinout%20User%20Guide.pdf)
- Use when: exact GW2AR package/pinout details are needed.

`UG115-1.7.2E_GW2AR-18 Pinout.pdf`
- Path: [UG115-1.7.2E_GW2AR-18 Pinout.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG115-1.7.2E_GW2AR-18%20Pinout.pdf)
- Use when: exact GW2AR-18 pinout confirmation is required.

`UG206-1.9.2E_GW2A(R) series of FPGA Products Schematic Manual.pdf`
- Path: [UG206-1.9.2E_GW2A(R) series of FPGA Products Schematic Manual.pdf](/home/itadmin/github/VDP/kb/datasheets/gowin/UG206-1.9.2E_GW2A(R)%20series%20of%20FPGA%20Products%20Schematic%20Manual.pdf)
- Use when: board/device schematic conventions and support circuits matter.

### Board / device docs present locally

`tang20k-datasheet.pdf`
- Path: [tang20k-datasheet.pdf](/home/itadmin/github/VDP/kb/datasheets/fpga/tang20k-datasheet.pdf)
- Use when: board-level features, connectors, clocks, and peripherals are the question.

`tang20kfpga-chip-data.pdf`
- Path: [tang20kfpga-chip-data.pdf](/home/itadmin/github/VDP/kb/datasheets/fpga/tang20kfpga-chip-data.pdf)
- Use when: package/device-level capabilities on the shipped Tang Nano 20K module matter.

## Missing Locally But High Value

These are the highest-value missing Gowin docs for the current codebase.

`SUG283` Gowin Primitives User Guide
- Status: `Missing locally`
- Why it matters:
  - exact primitive semantics if we stop relying on inference
  - useful for BSRAM/FIFO wrappers or lower-level memory implementation details
- Related-document pointer:
  - listed by `SUG100`

`UG111` / other GW2AR family guide gaps
- Status: `Missing locally`
- Why they matter:
  - exact package/pinout/resource details for the GW2AR family used by Tang Nano 20K
  - useful if later work touches pins, clocks, or board bring-up assumptions
- Official Gowin product page found:
  - https://www.gowinsemi.com/en/document/main/product/38/

## Current Engineering Guidance

For the active HSTX FIFO blocker:
- Local docs already justify the design direction:
  - `SUG113`: multi-bit CDC should use asynchronous FIFO
  - `SUG113`: memory/FIFO generation via Gowin tooling is recommended
  - `SUG550`: check RAM inference behavior and `syn_ramstyle`
- Missing docs provide the exact behavior needed for implementation confidence:
  - `UG285` for BSRAM details
  - `IPUG760` for exact FIFO behavior

Recommended order for engineers:
1. Read `SUG113` for CDC + memory direction.
2. Read `SUG550` for inference/attribute implications.
3. Use `IPUG760` to lock exact FIFO semantics.
4. Use `UG285` if BSRAM implementation details are still unclear.

## Repo Gaps To Fill Later

If we want this repo to be self-contained, the best next additions are:
1. `SUG283` Gowin Primitives User Guide
2. `UG111` or equivalent GW2AR family guide not yet present locally

## Notes

- A duplicate synthesis guide also exists at [Gowin_Synthesis_User_Guide_SUG550-2.2E.pdf](/home/itadmin/github/VDP/kb/datasheets/fpga/Gowin_Synthesis_User_Guide_SUG550-2.2E.pdf). Prefer the canonical copy under `kb/datasheets/gowin/`.
- This file is an index only. It does not replace the actual vendor guides.
