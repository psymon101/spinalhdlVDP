# External AI Review Request — Full spinalhdlVDP Code Audit

**Project:** spinalhdlVDP (Sipeed Tang Nano 20K + ESP32-P4 Function EV Board)  
**Date:** 2026-07-27  
**Requested by:** TopazCliff (Project Lead)  
**Review scope:** All firmware, SpinalHDL, and RTL source code  
**Bundle location:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/source_bundle.md`  
**Bundle SHA-256:** `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`

---

## Why we need this

We are going in circles on what should be simple questions.

A recent example: the user asked, *"what are the other status bits?"*  
To answer that one question I had to grep across:

- `firmware/libvdp/vdp_host.h`
- `firmware/libvdp/vdp_status.h`
- `firmware/libvdp/vdp_i80.h`
- `firmware/libvdp/vdp_mode0.h`
- `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`
- `hw/spinal/spinalhdlvdp/QspiDecoder.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala`
- `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- project docs and MCP memory

…just to reconstruct the answer. The same status bits are defined in three places, implemented in two places, and reachable in different ways depending on whether the host is using the legacy QSPI path, the new QSPI transport core, or the i80 path. Some selectors return zero in the current bitstream even though the headers still document them. Some register addresses exist in firmware but are not decoded in RTL. Some bits are stuck, some are tied off, and nobody can tell without reading the source.

We have MCP memory. We have documentation. We have a project plan. **And we still had to hunt through the code to answer a basic question.** That is not a hardware problem; that is an organization problem. If this project had a single, accurate, line-up-to-line status and register model, we would probably be done by now.

So we are asking for a cold, hard review of the entire codebase.

---

## What we want you to do

Please open the attached bundle (`source_bundle.md`) and go through it line by line. Treat nothing as trusted. Assume the docs are stale and the comments are wrong until proven otherwise.

Specifically:

1. **Verify and explain.** For every significant module, write a short plain-language explanation of:
   - What it is supposed to do.
   - What it actually does.
   - Whether the two match.

2. **Find contradictions.** Look for:
   - Bitfield definitions in firmware that do not match RTL.
   - Register addresses that are defined but never decoded.
   - `READ_STATUS` selectors that are documented but return zero or are not implemented.
   - Status/irq/event bits that are set in one file and ignored in another.
   - Magic numbers duplicated with different names.
   - Code that is referenced by comments but no longer exists.

3. **Identify dead code and orphans.** Mark anything that is:
   - No longer reachable.
   - Tied off in the top-level integration.
   - Superseded by a newer implementation.
   - Left over from a retired host interface (legacy QSPI, Pico PIO, etc.).

4. **Optimize.** Where you find redundancy, propose the smallest clean refactor that removes it without changing behavior. We prefer correctness over cleverness.

5. **Propose a canonical status model.** We need one host-facing status surface that works for both QSPI and i80. It should include:
   - Upload state (busy, done, error, overflow, dropped).
   - Host/transport error state.
   - VDP event state (raster, sprite overflow, DMA/Blit done, mode switch, collisions).
   - A single W1C clear register and a single enable/mask register.
   - No duplicated constants between firmware and RTL.

6. **Rank issues.** Give each finding a severity:
   - **CRITICAL** — could cause data corruption, deadlock, or the symptoms we are debugging now.
   - **HIGH** — wrong or misleading enough to waste engineering time.
   - **MEDIUM** — technical debt that should be fixed soon.
   - **LOW** — cleanup only.

7. **Suggest the order of fixes.** If we can only fix ten things, which ten move the project forward fastest?

---

## Current context you should know

We are running two hardware-debug lanes in parallel:

- **Lane 1:** `2bpp-bank-completion-hw-reproof` — intermittent lower-bitmap upload corruption on QSPI. The working theory is SI/marginal timing, but we are ten-cycling a probe-instrumented bitstream to be sure.
- **Lane 2:** `upload-status-clear-rtl-decode` — adding the `0x0323` W1C decode to `I80HostInterface.scala` so i80 hosts can read/clear upload errors. BrightForge is implementing option 1 (symmetric local decode). No firmware changes.

The current production bitstream is built from `brightforge/read-done-diag` and is named `project_a5a047a2_bankcompletion.fs`.

Known sore spots already:

- `READ_STATUS` selector `0x06` (upload status) is tied off in `QspiTransportCore`; the bits only exist as `#define`s in firmware.
- `READ_STATUS` selector `0x05` (sticky status) is also tied off in the current QSPI core.
- i80 does not decode `READ_STATUS` at all.
- The same upload-status bits are now being added as register `0x0323` for i80, but the QSPI side may still not expose them consistently.
- There is no host-ready/busy pin. Overflow protection is currently just a 4 MHz host write cap.

---

## What we will do with your report

- CoralReef will turn your canonical status model into updated docs.
- BrightForge will implement the RTL changes.
- BronzeGate will update the firmware constants and API.
- TopazCliff will gate any further hardware debugging until the status interface is single-source-of-truth.

We are not asking for a quick scan. We are asking you to act as if you are taking ownership of this codebase for one review pass and tell us everything that is wrong, inconsistent, or could be simpler.

If we had done this six weeks ago, we would probably be shipping. Do not pull punches.

---

## Files included in the bundle

The bundle contains:

- `firmware/` — ESP32-P4 and legacy host driver sources (`libvdp`, `esp32p4_scaler_proof`, etc.).
- `hw/spinal/spinalhdlvdp/` — SpinalHDL sources (`*.scala`).
- `hw/verilog/` — Generated and hand-written Verilog.
- `hw/vhdl/` — Generated VHDL.
- `hw/gen/` — Generated constraints and build artifacts.
- Pin constraints (`.cst`, `.pdc`), build scripts (`.sbt`, `.sh`), and linker scripts.

Excluded: build directories, dependency caches, `simWorkspace`, `target`, `.bsp`, `.metals`, `__pycache__`, and virtual environments.

---

**Please return a single structured report.** For each file or module, include:

- Summary
- Issues found (with line numbers where possible)
- Proposed fix or cleanup
- Severity

If a proposed fix touches the host-visible register/status map, call it out explicitly so we can run it through Rule 19 review (BrightForge + BronzeGate written approval).

Thank you.
