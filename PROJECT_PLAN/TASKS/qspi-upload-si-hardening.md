# qspi-upload-si-hardening

**Owner:** BrightForge (RTL) + BronzeGate (firmware)  
**PM:** TopazCliff  
**Status:** OPEN  
**Opened:** 2026-07-30  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Address the residual intermittent silent QSPI upload corruption observed in `HAM6 removal + 2bpp indexed replacement` / `QSPI-SI-CEILING-183` at the canonical 4 MHz bulk-upload ceiling.

---

## Background

BrightForge's SI sign-off (#14266) concluded that the intermittent, speed-dependent, silent lower-bitmap corruption (8 MHz 4/10 pass, 4 MHz 3/3 pass, 2 MHz 3/3 pass; no `overflow`/`malformed` flags at `sel=0x0A`) is a physical signal-integrity margin issue, not RTL/CDC. The recommended follow-up was one of:

1. **Native ESP32-P4 SPI2 IOMUX + series termination** (physical/firmware side).
2. **Per-SDRAM_WRITE CRC in transport health** (RTL/firmware detection side) so the host can retry silent corruption.

This lane picks the more actionable of the two and proves it reduces/eliminates uncorrected upload corruption at 4 MHz.

---

## Scope

- Choose an SI-hardening approach **before touching RTL or firmware**:
  - **Option A (recommended, software-detectable):** Add a per-SDRAM_WRITE payload CRC8 in `QspiSdramBridge`, accumulate it per write transaction, and expose a `READ_STATUS` selector so firmware can verify each uploaded chunk. Host retry logic on mismatch turns silent corruption into retried writes.
  - **Option B (physical):** Confirm native SPI2 IOMUX pins are usable on the current Tang Nano 20K + P4 wiring, switch the firmware QSPI driver to native IOMUX, and re-run the 4 MHz stress test.
  - **Option C (bench only):** Shorten/ground leads, add series termination, adjust drive strength, quantify improvement.
- No production fetch/display RTL changes.
- No change to the 4 MHz canonical bulk-upload ceiling unless new data justifies it.
- Host-visible addition (new health selector / firmware retry) requires Rule 19 interface checkpoint: independent BrightForge + BronzeGate approval before implementation.

---

## Acceptance Criteria

- [ ] Approach chosen and recorded in this task file with PM approval.
- [ ] If Option A: RTL computes CRC8 per `SDRAM_WRITE` payload, health selector exposes pass/fail per chunk, firmware performs verify+retry, `sbt compile` PASS, sim/unit-test proves detection of injected nibble error.
- [ ] If Option B or C: procedure documented, before/after 4 MHz stress N≥30 uploads with byte-level readback, quantitative improvement shown.
- [ ] Production `make gen` still emits `top_tang20k.v` with no unintended diff.
- [ ] `git status` clean; all changes committed.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.
- [ ] `STATUS.md` lane updated to `DONE` with proof.

---

## Out of Scope

- Reopening the `QspiSlave` clock-domain architecture (that was dispositioned in `QSPI_CLK_DOMAIN_EVAL.md`).
- Changing production display/fetch path.
- Flashing a new bitstream unless the chosen option requires RTL.

---

## Dependencies

- `720p-proof-build-script-cleanup` — DONE.
- `2bpp-bank-completion-rtl` — DONE (sim+PnR; this lane does not require its HW reproof).

## Next after this lane

- `2bpp-bank-completion-hw-reproof` (lane 1).
