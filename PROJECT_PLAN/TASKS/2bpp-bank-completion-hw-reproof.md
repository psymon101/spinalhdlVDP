# 2bpp-bank-completion-hw-reproof

**Owner:** BronzeGate (firmware/flash/procedure) + BrightForge (bitstream/RTL support)  
**PM:** TopazCliff  
**Status:** RUNNING — lane 3 closed; hardware reproof gate started  
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

## Current Action

**BronzeGate:** run the authorized controlled retry.

1. Flash `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` (SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`) via explicit SRAM load.
2. Wait **≥1 second** after `openFPGALoader` reports 100% / success before resetting or connecting to the ESP32. LED0 lit (PLL locked) is the ready indicator; ~1 s is ample for the QSPI transport.
3. Reset/serial-connect the ESP32 and verify the first read returns `magic=0x51560002`.
4. If the magic is correct, continue the reproof: run ≥10 full cold-POR or `openFPGALoader` reconfigure cycles with the settle delay, capturing per-cycle health, basic + row-200 readbacks, `CHECKERBOARD_TEST PASS`/equivalent, and `/dev/video0` YUYV capture.
5. If `magic=0x22222222` (or any other wrong magic) recurs **after** the settle delay, stop immediately and escalate to TopazCliff/BrightForge — that would be a genuine anomaly requiring RTL investigation.
6. Record all artifacts in `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/` and update this task file + `STATUS.md`.

**BrightForge:** the `a5a047a2` bitstream is confirmed preserved and hash-verified. Stand by for RTL support **only if** the post-settle anomaly repeats; no pre-emptive RTL patching.

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
