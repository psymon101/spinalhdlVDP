# Lane: qspi-transport-reliability-hardening

**Owner:** BrightForge (RTL/sim) + BronzeGate (firmware build + HW reproof)  
**PM:** TopazCliff  
**Status:** RULE 19 SIGN-OFF PENDING  
**Opened:** 2026-08-08  

## Goal

Eliminate the config-boundary CS# reset-release race in `QspiSlaveSync` so that the first QSPI transaction after FPGA configuration is reliable without the Lane 1 discard-read prime workaround.

## Background

The Lane 1 diagnostic bitstream (`eaad44f8…`) decoded the first failing transaction as `raw=0x00004045`, selecting the reset-fired-but-first-transaction-mis-framed branch. The root cause is an async reset-release race in `QspiSlaveSync`: `io.csn` is used directly as the SCLK-domain async reset, and under some configuration-boundary timings the first SCLK edge arrives before the reset release is clean, causing the FSM to start at CMD bit 1 instead of bit 0.

Current workaround: BronzeGate performs one ignored `read_status(0x00)` (the "discard-read prime") before trusting the second transaction.

## Proposed change

Modify `hw/spinal/spinalhdlvdp/QspiSlaveSync.scala` so that the SCLK-domain reset is not a raw async release on `io.csn` falling. Instead:

1. Keep async assertion when `csn` is high (transaction abort / idle reset).
2. Add a reset-release synchronizer that waits for `csn` to be low and stable across a small number of SCLK rising edges before deasserting the domain reset.
3. Ensure the first transaction bit is sampled reliably even when the master provides minimal `cs_ena_pretrans` setup.

The existing protocol and pinout are unchanged; this is an internal timing fix.

## Implementation plan

1. **RTL design** — BrightForge  
   - Replace the direct `reset = io.csn` `ClockDomain` in `QspiSlaveSync` with a generated reset produced by `ResetCtrl.asyncAssertSyncDeassert` or an equivalent SCLK-synchronized reset generator driven by `io.csn`.
   - Update `outCd` (falling-edge launch domain) to use the same synchronized reset.
   - Confirm no cross-domain assumptions are broken.

2. **Simulation proof** — BrightForge  
   - Create `QspiSlaveSyncResetReleaseSim` (or extend an existing sim) that explicitly varies the delay from FPGA config-done / `csn` falling to first `sclk` edge, including violating the nominal setup.
   - Prove that the first transaction always decodes to opcode `0x05` (READ_STATUS) and returns the expected magic/status word without requiring a prior discard transaction.

3. **Synthesis / PnR** — BrightForge  
   - Regenerate `top_tang20k.v`.
   - Run Gowin PnR; confirm TNS=0 and no new timing closure issues.

4. **Firmware build** — BronzeGate  
   - Build the current `esp32p4_scaler_proof` firmware against the new RTL (no firmware changes required for this scope).

5. **Hardware reproof** — BronzeGate  
   - Flash the new bitstream and the existing firmware (without the discard-read prime).
   - Run ≥10 cold-POR reconfigure cycles on Tang Nano 20K + ESP32-P4.
   - Acceptance: 10/10 cycles pass with first magic read `0x51560002`, clean health, readbacks, and capture.

6. **Closeout** — TopazCliff  
   - Update `STATUS.md` to DONE, archive proof packet, send closeout mail.

## Acceptance criteria

- [ ] Rule 19 sign-off from BrightForge and BronzeGate.
- [ ] `sbt compile` PASS.
- [ ] New reset-release sim PASS.
- [ ] Gowin PnR PASS (TNS=0).
- [ ] Firmware build PASS.
- [ ] Hardware reproof ≥10/10 PASS without discard-read prime.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/qspi-transport-reliability-hardening/`.
- [ ] `STATUS.md` updated to DONE with artifacts.

## Risks / open questions

- The synchronizer adds latency before the first SCLK is processed. It must not break hosts that start SCLK immediately after CS# low. The sim will bound the maximum allowed CS#-to-SCLK setup and document it.
- If the race is actually in the pad/IOBUF path rather than the reset domain, this fix may be insufficient. The diagnostic word already points to the reset domain, so this is the leading hypothesis.

## Artifacts

- Source branch: `brightforge/qspi-transport-reliability-hardening`
- Task file: `PROJECT_PLAN/TASKS/qspi-transport-reliability-hardening.md`
- Rule 19 request: (to be posted after sign-off)
