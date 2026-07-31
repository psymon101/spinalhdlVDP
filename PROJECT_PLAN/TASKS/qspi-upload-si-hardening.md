# qspi-upload-si-hardening

**Owner:** BrightForge (RTL) + BronzeGate (firmware)  
**PM:** TopazCliff  
**Status:** BLOCKED — discriminator selects stable SDRAM/write-path zeros; pending PM/BrightForge three-way scope agreement (#14509–#14512)
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

## Approach Reframe (2026-07-30)

BrightForge confirmed that the RTL described as "Option A" already exists on `main` from the `QSPI-CRC8-185` lane (`QspiSlaveSync.scala` / `QspiTransportCore.scala`, commit `368839f`, HW-proven bitstream `780ee698`, mail #14274/#14276/#14278). The per-`SDRAM_WRITE` CRC8 covers `[CMD, ADDR, LEN, payload]` and is exposed via `READ_STATUS sel=11`.

Therefore this lane does **not** build new RTL. The actionable work is:

1. BronzeGate confirms whether the failing 4 MHz bulk 2bpp-upload path actually appends the CRC byte (using `firmware/libvdp/vdp_crc8.h`) and polls `sel=11` with retry-on-mismatch.
2. If yes, run the 4 MHz byte-readback stress at **N≥30 uploads with CRC retry enabled** and measure residual uncorrected corruption.
3. If no, adopt the existing CRC+retry on the bulk path, then run the same stress.
4. Only if residual corruption remains do we scope a minimal delta (likely firmware plumbing, not RTL).

## BronzeGate firmware-path result (2026-07-30)

The current ESP32-P4 backend is already CRC-enabled: `vdp_host_p4.c`
`write_frame()` computes `vdp_crc8_qspi_write_frame()` over the wire-order
`[CMD, ADDR, LEN, payload]`, appends the CRC byte, polls `READ_STATUS` selector
`0x0B` before and after each frame, and retries once when the 16-bit CRC status
counter changes. Both `vdp_reg_write_burst()` and `vdp_sdram_write()` use this
helper; the scaler proof app's 4 MHz bulk bitmap/attribute uploads therefore
exercise the existing CRC8-185 path without a firmware edit.

Clean-baseline hardware stress (`project_38002d5c_scaler_hwproof.fs`, ESP-IDF
6.0.2, app source commit `4f205a08`) ran 30 reset/upload/readback cycles. The
result was 15/30 pass and 15/30 fail. Every failed cycle had two byte-readback
mismatches at the checkerboard samples `0x100008` and `0x101000` (expected
`0x55555555`, observed `0x00000000`); all other sampled words passed. All three
health samples per cycle remained `raw=0x00000000 overflow=0 malformed=0`, and
the application returned after upload without a CRC retry failure. This is
residual uncorrected corruption after CRC+retry, so the lane remains blocked
pending BrightForge/TopazCliff scope of the minimal next delta.

Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.

Rule 19 remains open pending BrightForge/TopazCliff agreement on the next step.

## BronzeGate firmware framing/readback audit (2026-07-31)

Per TopazCliff #14539, BronzeGate traced the source buffer and upload path for
the deterministic failures at `0x100008` and `0x101000`. The checkerboard
contains `0x55555555` at both target words. Frame 0 begins at `0x100000`, with
the first target at byte offset 8; frame 8 begins at `0x100FD0`, with the
second target at byte offset 48. The 253-word frame map, little-endian length
and word encoding, parity-encoded wire addresses, and appended CRC8-185 values
were recomputed from the firmware. The target frames produce wire addresses
`0x100000`/`0x900FD0` and CRC values `0xDF`/`0x67`; the CRC is appended after
the payload and cannot shift it.

The P4 backend's `vdp_reg_read()` is an explicit RX stub. The diagnostic's
selector `0x08` is the only current P4 SDRAM-content surface; selector `0x09`
is transport loopback/status, not alternate SDRAM data. No new command,
register, bitstream, or firmware behavior was invented. The full audit and
Rule 10 citation block are in
`PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/FRAMING_READBACK_AUDIT.md`.

Disposition: host framing/address/CRC is not the demonstrated mechanism, and
the lane remains blocked on an approved alternate readback surface or a
physical-layer test. Any host-visible readback change requires the independent
BrightForge + BronzeGate Rule 19 checkpoint and TopazCliff authorization.

Related mail: #14539, #14540, #14542, #14543.

## BronzeGate focused discriminator result (2026-07-30)

BronzeGate ran proof-only `SCALER_PROOF_MODE=4` using the existing `libvdp`
upload path. Bitmap and attribute planes each used 61 frames of 253 words
(506 bytes) at 4 MHz. Selector `0x0B` was logged before and after every
frame; six counter deltas occurred and all host calls returned success.

The assigned neighborhoods were read at 2 MHz eight times each: 13 addresses,
104 successful reads total. The expected-`0x55555555` words at
`0x100008`, `0x10000C`, `0x100018`, `0x10001C`, `0x101000`, and `0x101004`
all returned stable `0x00000000`; expected-zero neighbors also remained zero.
Health was `raw=0x00000000 overflow=0 malformed=0`. Per the PM discriminator,
this selects the real SDRAM/write-path branch rather than a varying readback
artifact. Detailed evidence is in
`PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DIAGNOSTIC_RESULTS.md`.

No production firmware or RTL fix was made. Rule 19 remains open pending the
three-way PM/BrightForge/BronzeGate scope decision.

## Next step

Before any RTL or firmware edit, discriminate where the residual zeros originate:

1. **BronzeGate** — discriminator complete. The required 2 MHz reads are stable zero and the exact 4 MHz frame/CRC map is recorded in the proof packet.
2. **BrightForge + TopazCliff** — review the stable-zero result with the bridge analysis and choose the minimal authorized delta (firmware readback verification, RTL write-path fix, or physical hardening).
3. **BronzeGate** — implement only the approved host-side change after the independent interface checkpoint; otherwise remain blocked.

No code changes until the discrimination analysis is complete.

## BronzeGate refresh-pressure cross-check (2026-07-31)

Per TopazCliff #14531, BronzeGate ran the existing proof firmware against the
same approved `38002d5c` bitstream under two display-workload conditions. Mode 4
kept layer 0 disabled after upload; mode 0 enabled layer 0 and display fetch.
Both conditions used the existing CRC8/retry path, 4 MHz uploads, and 2 MHz
readback. Each condition ran N=30 reset/upload/readback cycles.

Results: mode 4 was 0/30 pass and mode 0 was 0/30 pass. The expected
`0x55555555` words at `0x100008` and `0x101000` failed on every cycle in both
conditions (60 target mismatches per condition). Health remained
`raw=0x00000000 overflow=0 malformed=0`. Therefore this test observed no
display-workload scaling; it is a correlation result, not a mechanism claim.

Proof artifacts:

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_PROCEDURE.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/REFRESH_PRESSURE_BUILD.md`

Rule 10 prior-art search and citations are included in the results artifact.
The lane remains blocked on BrightForge's waveform-pin/proven bulk-upload
harness and the PM's Rule 19 decision. No production firmware or RTL change was
made.

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
