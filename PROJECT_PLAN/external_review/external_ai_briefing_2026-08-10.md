# External AI briefing — spinalhdlVDP CPU↔FPGA QSPI reliability

**Date:** 2026-08-10  
**Project:** spinalhdlVDP — SpinalHDL VDP for Tang Nano 20K  
**Host platform:** ESP32-P4 Function EV Board (canonical QSPI host)  
**FPGA:** Sipeed Tang Nano 20K (Gowin GW2A-18)  
**Branch under review:** `main` at `24d19ef3`  
**Bundle companion file:** `full_project_bundle_2026-08-10.md`

## Purpose of this briefing

The project owner has directed us to build a **solid, scalable, over-tested, self-healing/adjusting connection between the CPU (ESP32-P4) and the FPGA**. We are asking the external AI to review the current state, the master reliability plan, and the bundled source/project files, then identify gaps, risky assumptions, and under-tested areas.

## Current state

### What is merged to `main`

- The `codebase-cleanup-status-contract` lane is **DONE and merged** (`7bff3d65`, `bf1ea619`, `6ca34805`). It centralized the upload-status W1C decode in `VdpTop.scala`, implemented QSPI selectors `0x05` (sticky) and `0x06` (upload), and gave i80 hosts parity via memory-mapped reads of `0x0320`/`0x0323`.
- Retired i80 and legacy SPI firmware paths are guarded by `#error` compile-time checks (`289fa646`) so they cannot be accidentally compiled.
- The active ESP32-P4 QSPI backend is `firmware/libvdp/vdp_host_p4.c` + `vdp_mode0.c`.

### Two active engineering lanes

| Lane | Status | Blocker / next step |
|---|---|---|
| `qspi-status-done-bit-fix` | **RUNNING — Option A confirmed** | The merged cleanup defines `DONE` (bit 1 of `sel=0x06` / `0x0323`) as sticky, but the implementation drives it from a one-cycle pixel-domain pulse (`QspiSdramBridge.donePulse`) that the SCLK/i80 status paths cannot reliably sample. BrightForge is authorized to implement a true sticky level (set at upload completion, clear on next accepted upload start) with no W1C on bit 1. |
| `qspi-transport-reliability-hardening` | **BLOCKED — mechanism unconfirmed** | Lane 1 showed a first-transaction `magic=0x22222222` anomaly on a fresh `a5a047a2` reconfigure. The original diagnostic evidence (`firstPhase=CMD/firstBitc=1`) is now understood as a capture artifact; the true mechanism may be a reset-domain race, CS# SI/bounce, or read-data launch glitch. BrightForge must build a corrected free-running-domain diagnostic before any RTL fix. |

### Master reliability plan

The owner directive prompted us to write:

`PROJECT_PLAN/TASKS/qspi-cpu-fpga-reliability-plan.md`

It contains:
- Six reliability attributes (observable, recoverable, self-healing, silent-corruption-free, bounded, deterministic).
- A 12-row FMEA table covering `DONE`-bit observability, first-transaction mis-framing, CS# SI, read-launch glitches, silent SDRAM corruption, back-to-back upload races, CDC issues, and long-run drift.
- Candidate design mechanisms: sticky status, free-running reset release, CS# glitch filter, upload CRC, sequence numbers, host retry/backoff, diagnostic selectors, SpinalHDL assertions.
- An over-test matrix across simulation, synthesis/PnR, and hardware.
- Acceptance criteria that raise the bar beyond the two individual lanes.

## What we need from the external AI

Please review the companion bundle (`full_project_bundle_2026-08-10.md`) and this briefing, then answer the following:

1. **Failure-mode coverage.** Are there failure modes or corner cases missing from the FMEA in `qspi-cpu-fpga-reliability-plan.md`? Consider:
   - Clock-domain crossing and metastability.
   - FPGA configuration/POR state versus host boot order.
   - SPI peripheral configuration changes on the ESP32-P4 side.
   - Long-cable / breadboard wiring effects.
   - Toolchain/synthesis differences across seeds or Gowin versions.

2. **Design-mechanism trade-offs.** For each candidate mechanism (CRC, CS# glitch filter, sequence numbers, host retry, etc.), is the cost/benefit appropriate for this project? Are any of them essential rather than optional?

3. **Option A correctness.** Is the `DONE`-bit lifecycle decision (sticky across CS# idle until the next accepted upload starts; no W1C on bit 1) sound and self-consistent with the rest of the status contract?

4. **Diagnostic correctness.** For the transport-lane anomaly, what additional diagnostic experiments (beyond the corrected free-running `firstPhase/firstBitc` capture) would definitively discriminate between reset-domain, CS# SI, and read-launch mechanisms?

5. **Test gaps.** What tests in the over-test matrix are insufficient, impossible on this bench, or missing entirely?

6. **Self-healing policy.** Is the proposed host-side retry/timeout/backoff policy complete? What policy edge cases could still leave the host stuck?

7. **Spec/doc risks.** Are there ambiguities in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, or `firmware/libvdp/mode0_regs.json` that could cause the host and FPGA to disagree after the `DONE`-bit fix?

Please provide concrete recommendations, not just general advice. Where possible, cite file paths and line numbers from the bundle.

## Deliverable format

Return a markdown report with:
- Executive verdict (is the current plan adequate to meet the owner's reliability goal?)
- Itemized findings (numbered F1, F2, ...)
- Recommended changes to the plan, source, tests, or docs
- Any blockers that should stop Rule 19 sign-off until resolved

## Constraints

- The retired i80 and legacy SPI paths must stay retired unless a new Rule-19-gated lane explicitly re-opens them.
- Any new host-visible register, bit, or protocol change requires independent BrightForge + BronzeGate Rule 19 sign-off.
- The Tang Nano 20K wiring and ESP32-P4 GPIO mapping (`SCLK=21, CS=20, IO0=32, IO1=33, IO2=22, IO3=23`) are fixed for this build.

---

*This briefing and the companion bundle were generated at `main` `24d19ef3` on 2026-08-10.*
