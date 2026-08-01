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

## Current Action

**BronzeGate:** start the hardware reproof using the preserved bitstream and approved 4 MHz firmware.

1. Flash `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` (SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`) via explicit SRAM load.
2. Build/flash the canonical 4 MHz ESP32-P4 checkerboard/QSPI proof firmware.
3. Run ≥10 cold-POR or `openFPGALoader` reconfigure cycles.
4. Per cycle, capture serial proof, health, basic + row-200 readbacks, and `/dev/video0` YUYV capture.
5. Record all artifacts in `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/`.

**BrightForge:** confirm the `a5a047a2` bitstream is preserved and available; stand by for RTL support only if the reproof exposes a real hardware failure.

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
