# Lane: qspi-transport-reliability-hardening

**Owner:** BrightForge (RTL/sim) + BronzeGate (firmware build + HW reproof)  
**PM:** TopazCliff  
**Status:** BLOCKED — mechanism unconfirmed; awaiting corrected diagnostic  
**Opened:** 2026-08-08  
**Updated:** 2026-08-09  

## Goal

Eliminate the config-boundary first-transaction failure that forces the Lane 1 discard-read prime workaround. The specific RTL fix is **not yet selected**; we must first confirm the mechanism.

## Background

The Lane 1 diagnostic bitstream (`eaad44f8…`) returned `raw=0x00004045`. The `sawCsHigh=1` bit decisively refutes the CS#-stuck-low hypothesis, and the failure is config-boundary-only and self-heals — so it is a first-transaction timing/robustness issue.

However, the `firstPhase=CMD / firstBitc=1` interpretation that pointed to a reset-release race in `QspiSlaveSync` is now suspected to be a **diagnostic capture artifact**. The capture latch runs in the SCLK-clocked domain; because SCLK stops during the CS#-idle gap, the latch closed ~2 SCLK edges into the *recovered* transaction, not the first failing one. Therefore `firstPhase/firstBitc` read ~CMD/bit1 regardless of how the first transaction actually framed.

This means the reset-release race remains a **leading but unconfirmed hypothesis**. Other not-ruled-out mechanisms include CS# signal-integrity/bounce at the config boundary and a read-data output/OSER launch glitch right after configuration.

Current workaround: BronzeGate performs one ignored `read_status(0x00)` (the "discard-read prime") before trusting the second transaction.

## Proposed change (pending mechanism confirmation)

**Do not start RTL implementation yet.** First, build a corrected diagnostic that captures the first transaction state reliably (e.g., latch `firstPhase`/`firstBitc` in the free-running sys/pixel clock domain, or use a free-running "first-txn-done" qualifier), re-run the `eaad44f8`-class experiment, and read the real first-transaction state.

Once the mechanism is confirmed, select the targeted fix:
- **Reset-release synchronizer** if the first transaction is genuinely mis-framed.
- **CS# SI / input conditioning** if CS# is glitching/bouncing at the boundary.
- **Read-data launch-path hardening** if framing is correct but read data is corrupted.

The existing protocol and pinout will remain unchanged.

## Implementation plan

1. **Corrected diagnostic** — BrightForge  
   - Fix the Lane 1 diagnostic capture so `firstPhase`/`firstBitc` are latched in the free-running sys/pixel clock domain (or otherwise independent of SCLK stoppage during CS# idle).
   - Re-build the diagnostic bitstream from the same `a5a047a2` base.

2. **Diagnostic hardware run** — BronzeGate  
   - Flash the corrected diagnostic bitstream on a fresh reconfigure that reproduces `magic=0x22222222`.
   - Capture `sel=0` (expected `0x2222`) + `sel=0x0D` and report.
   - BrightForge interprets the corrected reading and rules in/out each mechanism.

3. **Select fix + Rule 19 sign-off** — TopazCliff / BrightForge / BronzeGate  
   - Update this task file with the confirmed mechanism and chosen fix.
   - Re-request Rule 19 sign-off with the confirmed target.

4. **RTL fix + sim + PnR + HW reproof** — BrightForge / BronzeGate  
   - Implement the confirmed fix.
   - Run full affected QSPI regression and the new mechanism-specific sim.
   - Run Gowin PnR; confirm TNS=0.
   - Build firmware and run ≥10 cold-POR cycles without the discard-read prime.

5. **Closeout** — TopazCliff  
   - Update `STATUS.md` to DONE, archive proof packet, send closeout mail.

## Acceptance criteria

- [ ] Corrected diagnostic captures the real first-transaction state.
- [ ] Mechanism confirmed and fix selected.
- [ ] Rule 19 sign-off from BrightForge and BronzeGate for the confirmed fix.
- [ ] `sbt compile` PASS.
- [ ] Mechanism-specific sim PASS.
- [ ] Full affected QSPI regression PASS.
- [ ] Gowin PnR PASS (TNS=0).
- [ ] Firmware build PASS.
- [ ] Hardware reproof ≥10/10 PASS without discard-read prime.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/qspi-transport-reliability-hardening/`.
- [ ] `STATUS.md` updated to DONE with artifacts.

## Risks / open questions

- The `firstPhase/firstBitc` fields in the original diagnostic are unreliable. Any conclusion drawn from them is provisional.
- CS# signal-integrity at the FPGA pin is difficult to observe without a logic analyzer or scope; a corrected diagnostic may not distinguish CS# bounce from a reset-domain issue cleanly.
- If the output/OSER path is the true cause, the fix may be in the launch/IOBUF domain rather than in `QspiSlaveSync`.

## Artifacts

- Source branch: `brightforge/qspi-transport-reliability-hardening` (not yet created)
- Diagnostic base: `brightforge/lane1-reconfig-diag` (forked from `a5a047a2` source `033cc471`)
- Task file: `PROJECT_PLAN/TASKS/qspi-transport-reliability-hardening.md`
- Rule 19 request: on hold until mechanism is confirmed
