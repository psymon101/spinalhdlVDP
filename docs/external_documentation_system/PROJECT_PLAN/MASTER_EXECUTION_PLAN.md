# Master Execution Plan

## Purpose

This is the project-control document. It answers:

- what is active;
- what is blocked;
- what must happen next;
- which specification governs the work;
- which evidence closes the lane.

Technical implementation detail belongs in the linked architecture, FPGA,
firmware, platform, test, and runbook documents.

## Product goal

Build a host-independent retro graphics coprocessor using SpinalHDL on the Tang
Nano 20K. Any supported host uses `libvdp` to configure Mode0 or a
platform-specific visual adapter. The FPGA emulates display hardware only.

## State model

1. BACKLOG
2. RESEARCH
3. SPEC REVIEW
4. DESIGN APPROVED
5. SPINALHDL IMPLEMENTATION
6. SPINALSIM PASS
7. FIRMWARE IMPLEMENTATION
8. SYNTHESIS PASS
9. HARDWARE PROOF
10. DOC/AUDIT
11. CLOSED
12. BLOCKED

No lane skips a state.

## Current program

### Active lane

`FOUNDATION-0 — Baseline and Contract Reconciliation`

See `ACTIVE_LANE.md`.

### Foundation gates

1. **Foundation 0:** lock source, build, hardware, transport, and current contracts.
2. **Foundation 1:** stabilize shared Mode0 engines and SpinalSim regression.
3. **Foundation 2:** make `libvdp` host-independent and ABI-aware.

### Platform sequence

1. Generic Mode0 closure
2. ZX Spectrum closure
3. TMS9918A
4. Sega Master System and Game Gear
5. NES/Famicom
6. Commodore 64 VIC-II
7. Atari ST/STE
8. Amiga OCS/ECS
9. Sega Mega Drive/Genesis
10. SNES Modes 0–3-lite
11. Atari 2600 TIA

Atari 2600 research may proceed early, but its dedicated scanline frontend must
not interrupt the shared RTL lane.

## Global definition of done

A lane closes only when:

- scope and non-goals are approved;
- SpinalHDL source and SpinalSim tests pass;
- generated Verilog is clean and unmodified;
- `libvdp` API and reference firmware build;
- Gowin synthesis and timing pass;
- matched firmware/bitstream hardware proof passes;
- documentation is synchronized;
- an independent reviewer signs the proof packet;
- regressions enter the standard suite.

## One-active-RTL-lane rule

Only one lane may modify common `VdpTop`, SDRAM arbitration, shared fetch
engines, compositor, Copper, HDMA, sprite substrate, or HDMI integration at a
time. Research, documentation, test-vector preparation, and firmware-only work
may proceed in parallel.

## Exact next task

Open `FOUNDATION-0-001` using the task template and populate
`CURRENT_BASELINE.md`. The next task is not selected until the active task's
proof packet is accepted.
