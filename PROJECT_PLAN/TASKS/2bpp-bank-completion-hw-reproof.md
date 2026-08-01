# 2bpp-bank-completion-hw-reproof

**Owner:** BronzeGate (firmware/flash/procedure) + BrightForge (bitstream/RTL support)  
**PM:** TopazCliff  
**Status:** BLOCKED — post-settle retry repeated the `0x22222222` anomaly; awaiting BrightForge/PM investigation (#14593)  
**Opened:** 2026-07-30  
**Started:** 2026-08-01  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Provide the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening.

---

## Background

`2bpp-bank-completion-rtl` closed on sim+PnR proof only (commit `033cc47`, bitstream `a5a047a2…`). The external review and PM disposition left the hardware bench flash as a separate, PM-sequenced gate. This lane executes that gate using the exact approved 4 MHz bulk-upload firmware artifacts.

---

## Scope

- Use the `2bpp-bank-completion-rtl` bitstream (`a5a047a2…`) or a bitstream byte-identical at 1× if a later lane has changed the production path.
- Use the canonical 4 MHz ESP32-P4 firmware (`firmware/esp32p4_checkerboard/` or the approved QSPI proof app) to upload a non-uniform 2bpp test pattern.
- Perform ≥10 cold-POR or openFPGALoader reconfigure cycles.
- Verify per cycle:
  - Magic/health readbacks `raw=0`, `overflow=0`, `malformed=0`.
  - Basic + row-200 readbacks match expected non-uniform pattern.
  - `CHECKERBOARD_TEST PASS` or equivalent 2bpp content proof.
  - `/dev/video0` YUYV capture shows no torn/stale rows.

---

## First-cycle anomaly (2026-08-01)

After a successful `openFPGALoader` SRAM load of `project_a5a047a2_bankcompletion.fs`, the first ESP32 reset/serial capture read `magic=0x22222222` instead of the expected `0x51560002`. No upload/readback/capture was attempted for this cycle.

Evidence preserved:
- Serial log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/cycle_01_serial.log`, SHA-256 `578344c894f4566676ef92b0a77e99db244c81cab6c23ecaf1f63cba879de6a0`
- Loader log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/cycle_01_openfpgaloader.log`, SHA-256 `527863653a61563bd541ef034935bba6ce22747456422f53623843c8461a4c0d`

BrightForge assessed the value `0x22222222` as the legacy framing-mismatch signature (`TopTang20kHdmi.scala:392-402`, #13966) and concluded it is a **post-reconfigure early-read / QSPI-responder settle artifact**, not a real RTL failure, because the magic constant is static and the same bitstream has previously read the correct magic. BrightForge endorsed a controlled retry with a post-SRAM-load settle delay before the first ESP32 read (#14590).

## External-review feedback incorporated (2026-08-01)

The external review of the lane-1 source bundle concurred with the settle-delay explanation and recommended the following preconditions/safety checks, which are now part of this lane:

1. **Cycle-start preconditions:** After the ≥1 s post-SRAM-load settle, the first host read must be `SEL_MAGIC` (`sel=0`). If `magic != 0x51560002`, stop and escalate. If magic is correct, immediately read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) and confirm `raw == 0x00000000` (both `malformed` and `overflow` sticky bits clear). Only proceed to upload when both preconditions pass.
2. **Mid-test safety monitor:** Immediately after each bulk SDRAM upload finishes, log `SEL_TRANSPORT_HEALTH` (`sel=0x0A`). A non-zero value here means the `uploadCc` FIFO or bridge tripped during the burst; the rest of the cycle's readbacks/capture are invalid proof.
3. **Sticky-bit abort policy until `0x0323` decode lands:** `vdp_clear_upload_status()` writes `0x0323`, but the current RTL does not decode that address (`FULL-DOC-AUDIT-151` finding #4). Therefore, if any cycle records a non-zero transport-health sticky bit, the **entire reproof run must be aborted** rather than continuing to the next cycle. The bits cannot be cleared without an FPGA POR/reconfigure. A dedicated RTL lane (`upload-status-clear-rtl-decode`) has been opened to fix this; see `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md`.
4. **READ_DONE polling retained:** The `sel=8` / `sel=0x0C` completion-poll mechanism remains the diagnostic standard; no auto-stall or packet protocol will be added.

## Settled retry result (2026-08-01)

The PM-authorized retry was stopped at the escalation bar. The preserved
bitstream SRAM load completed successfully, followed by a measured 1.2-second
settle delay, but the first ESP32 read again returned `magic=0x22222222`
instead of `0x51560002`. No upload, readback, or video capture was attempted;
this retry is not counted toward the ten-cycle gate.

Evidence:

- Serial: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/settled_cycle_01_serial.log`, SHA-256 `98914b9218ed0cadf0602f8d7d42864c488c3be7380b116e8bad2c0999663d96`
- Loader: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/settled_cycle_01_openfpgaloader.log`, SHA-256 `9c440acb8292411bc77ee7ae2e48bd230a990646246e3932f3c609c6568b9855`

Per PM procedure in #14591, the lane is paused and escalated in #14593 for
BrightForge/TopazCliff investigation. No further retry, RTL edit, or
production firmware change is authorized at this point.

## PM disposition on the repeated anomaly (2026-08-01)

The post-settle recurrence rules out a simple "FPGA not ready yet" explanation.
The uniform `0x22222222` value is the legacy framing-mismatch signature, but it
is appearing on the current `QspiTransportCore` (Option A) path, which replaced
that legacy slave specifically to fix the mismatch (#13973/#13974). The same
`a5a047a2` bitstream has read the correct magic in prior runs, so the bitstream
itself is not globally broken. The failure is therefore either:

1. A post-reconfigure state in the FPGA QSPI responder that persists well past
   1.2 s under the current reset/clocking sequence, or
2. An interaction between the ESP32-P4 reset/boot and the FPGA responder state
   that leaves the responder misframed, or
3. A difference between the SRAM-load configuration path and the persistent-flash
   configuration path that the prior successful runs relied on.

BrightForge is asked to investigate and propose the next diagnostic step. Until
a technical assessment is delivered, Lane 1 stays **BLOCKED** and no further
hardware cycles are authorized.

## CS_N reset hypothesis (external review 2026-08-01)

An external reviewer identified a likely mechanism consistent with the symptoms:

- `QspiSlaveSync` (`hw/spinal/spinalhdlvdp/QspiSlaveSync.scala:86-94`) uses
  `io.csn` as the **SCLK-domain asynchronous reset, active-high**. CS# high
  resets the FSM to `Phase.CMD`; CS# low releases reset and starts the
  transaction.
- The Tang Nano 20K CST pulls `I_qspi_cs` up (`PULL_MODE=UP`), so if the
  ESP32-P4 leaves the pin floating after reset, the FPGA sees CS# high and the
  FSM resets correctly.
- **If the ESP32-P4 boot/peripheral default drives CS# low during the settle
  delay, the FSM never resets.** The first `READ_STATUS` transaction then starts
  with the FSM out of phase, causing the host to sample a stale/default bus
  value — the observed `0x22222222`.

This hypothesis is testable without RTL changes:

1. **Firmware test (BronzeGate):** Immediately after ESP32-P4 boot, before the
   1-second settle delay, configure the CS_N GPIO as an output and drive it
   **HIGH**. Then hand the pin to the SPI peripheral and run `vdp_host_init()`
   / the normal magic read. This guarantees the FPGA sees the required CS#
   high-idle / rising-edge reset.
2. **RTL review (BrightForge):** Confirm whether the `QspiSlaveSync` reset
   semantics and the CST pull-up make this hypothesis consistent with the
   observed 1.2 s failure. If the firmware fix does not resolve it, propose the
   next electrical/state-machine diagnostic.
3. **Electrical verification:** No bench logic analyzer is available on this
   host (`sigrok-cli`, PulseView, DSView, Saleae, and logic-node tooling are
   absent). If the firmware fix fails, BrightForge should propose an
   alternative diagnostic that does not require external capture hardware
   (e.g., a firmware-driven GPIO probe, internal FPGA LED state, or a
   diagnostic bitstream).

If the firmware test resolves the anomaly, the Lane 1 procedure will be updated
with a mandatory CS#-high pre-flight step. If it does not resolve it, the lane
remains blocked pending BrightForge's next assessment.

## Current Action

**BronzeGate:** the authorized retry was attempted and the lane is now paused pending review of the repeated post-settle magic anomaly.

1. Flash `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` (SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`) via explicit SRAM load.
2. Wait **≥1 second** after `openFPGALoader` reports 100% / success before resetting or connecting to the ESP32. LED0 lit (PLL locked) is the ready indicator; ~1 s is ample for the QSPI transport.
3. Reset/serial-connect the ESP32 and verify the first read returns `magic=0x51560002`.
4. Immediately after a good magic, read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) and verify `raw == 0x00000000`. If non-zero, stop and escalate.
5. If the preconditions pass, continue the reproof: run ≥10 full cold-POR or `openFPGALoader` reconfigure cycles with the settle delay, capturing per-cycle health (before upload, immediately after upload, and after enable), basic + row-200 readbacks, `CHECKERBOARD_TEST PASS`/equivalent, and `/dev/video0` YUYV capture.
6. If `magic=0x22222222` (or any other wrong magic) recurs **after** the settle delay, or if any cycle records a non-zero transport-health sticky bit, stop immediately and escalate to TopazCliff/BrightForge.
7. Record all artifacts in `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/` and update this task file + `STATUS.md`.

**BrightForge:** the `a5a047a2` bitstream is confirmed preserved and hash-verified. Stand by for RTL support **only if** the post-settle anomaly repeats or the new `upload-status-clear-rtl-decode` lane needs interface review; no pre-emptive patching inside this lane.

---

## Acceptance Criteria

- [ ] Bitstream source commit and SHA-256 recorded.
- [ ] Firmware ELF/BIN/partition SHA-256s match approved 4 MHz artifacts.
- [ ] ≥10 cold-start cycles pass with byte-level readback and clean capture.
- [ ] No residual lower-bitmap corruption (rows 200-201 historically failed).
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/`.
- [ ] `STATUS.md` lane updated to `DONE` with proof.

---

## Out of Scope

- New RTL changes. If the existing bitstream/firmware cannot pass, escalate to TopazCliff rather than patch RTL inside this lane.
- Scaled-mode or non-1× display verification.

---

## Dependencies

- `2bpp-bank-completion-rtl` — DONE.
- `qspi-upload-si-hardening` — DONE; this lane is unblocked.

## Next after this lane

- PM decides whether to open any further external-review follow-up lanes.
