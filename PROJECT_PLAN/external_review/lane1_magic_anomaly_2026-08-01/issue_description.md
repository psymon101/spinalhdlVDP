# Lane 1 first-cycle magic anomaly — external review request

Date: 2026-08-01
Project: spinalhdlVDP (Tang Nano 20K + ESP32-P4 Function EV Board)
Lane: `2bpp-bank-completion-hw-reproof`
Blocking: yes — first hardware cycle failed before any upload/readback

---

## Background and project context

The project is a retro-style VDP FPGA design. The host interface is an ESP32-P4 talking QSPI to a Gowin GW2A-18 on the Tang Nano 20K. Bulk SDRAM uploads use a canonical 4 MHz QSPI clock for the wiring harness.

Recent history:
- **Lane 6** (`720p-proof-build-script-cleanup`) — DONE.
- **Lane 3** (`qspi-upload-si-hardening`) — DONE. A `READ_DONE` completion-poll discriminator proved that SDRAM writes are clean; residual `sel=8` zeros were a readback/CDC artifact, not physical upload corruption.
- **Lane 1** (`2bpp-bank-completion-hw-reproof`) — RUNNING. This is the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening (commit `033cc47`, bitstream `a5a047a2…`). The RTL hardening added bank-completion/row-tag logic to the 2bpp bitmap fetch path to fix a display-bank advance-without-completion hazard identified by external review.

---

## The anomaly

After the lane-3 closeout, BronzeGate started lane 1:

1. Explicit SRAM load of `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` succeeded (`openFPGALoader` exit 0).
2. BronzeGate reset/opened the ESP32 serial port and read the magic word.
3. The first read returned **`magic = 0x22222222`** instead of the expected **`0x51560002`**.
4. No upload, readback, or display capture was attempted for this cycle.

Evidence preserved:
- Serial log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/cycle_01_serial.log`
  - SHA-256: `578344c894f4566676ef92b0a77e99db244c81cab6c23ecaf1f63cba879de6a0`
- Loader log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/cycle_01_openfpgaloader.log`
  - SHA-256: `527863653a61563bd541ef034935bba6ce22747456422f53623843c8461a4c0d`

The same bitstream (`a5a047a2…`) was previously used in an approved 4 MHz hardware reproof and read the correct magic (`0x51560002`) then.

---

## Relevant source-code points

- The magic constant is defined in `QspiTransportCore.scala` line 190:
  ```scala
  is(U(0, 8 bits)) { rxWordSel := B"32'h51560002" }  // magic
  ```
  This value is returned for `READ_STATUS` selector 0. It is a static constant; there is no SDRAM or dynamic state dependency.

- The legacy `QspiSlave` (pixel-oversampled Option B predecessor) stalled at `0x22222222` when a READ_STATUS header was framed with a single-lane/no-LEN header instead of the QUAD header + LEN phase it expected. This is documented in `TopTang20kHdmi.scala` lines 392-402:
  ```scala
  // QSPI host-control frontend — Option A (#13973/#13974): the synchronous
  // word-drain QspiTransportCore (SCLK-domain capture + CDC token FIFO + an
  // internal QspiDecoder) replaces the legacy pixel-oversampled QspiSlave/QspiDecoder
  // pair. It fixes the READ_STATUS read-header framing mismatch (#13966: legacy
  // required a QUAD header with a LEN phase; the P4 firmware sends a single-lane
  // header with no LEN on reads) that stalled the legacy slave at 0x22222222.
  ```

- The P4 firmware `READ_STATUS` implementation is in `firmware/libvdp/vdp_host_p4.c` (`rx_status`). It sends `CMD_READ_STATUS = 0x04` with the selector as the 24-bit address, 2 dummy bits, and reads 32 bits. The transaction uses QIO quad mode, `.cs_ena_pretrans = 2`, `.cs_ena_posttrans = 8`, `.dummy_bits = 2`.

- The current QSPI transport is `QspiTransportCore` + `QspiSlaveSync` (Option A). It captures the header SCLK-synchronously and responds to `READ_STATUS` SCLK-side without a CDC round-trip.

---

## What each agent has tried / concluded

### BronzeGate (firmware/flash/procedure)

- Performed the explicit SRAM load of the preserved `a5a047a2` bitstream.
- Verified the loader log shows `openFPGALoader` completed at 100% with exit 0.
- Read the magic immediately after the ESP32 reset and got `0x22222222`.
- Stopped after the first failure per the review rule.
- Proposed a controlled retry with a **1-second post-SRAM-load settle delay** before the ESP32 reset, on the theory that the FPGA clocks/QSPI responder were not yet ready.
- Preserved the serial and loader logs as evidence.

### BrightForge (RTL/FPGA)

- Confirmed the `a5a047a2` bitstream is preserved and hash-verified (two read-only copies on disk, both matching SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`).
- Assessed `0x22222222` as the **legacy framing-mismatch signature** (`TopTang20kHdmi.scala:392-402`, #13966), not a corrupted magic constant.
- Reasoning:
  - The magic is a static RTL constant; if the FPGA is configured and the QSPI responder is clocked/ready, `sel=0` must return `0x51560002`.
  - The same bitstream previously returned the correct magic in an approved run.
  - A garbage magic read **immediately after reconfigure, before any upload** is therefore the QSPI responder not yet being ready when the ESP32 issued its first read.
- Endorsed the settle-delay retry.
- Escalation bar: if the correct magic does **not** return after an adequate settle across multiple retries, or if `0x2222…` recurs *post-settle*, then investigate the QSPI responder reset/clock-ready path.
- Confirmed **no RTL change** is warranted unless the post-settle anomaly repeats.

### TopazCliff (PM)

- Reviewed BronzeGate’s evidence and BrightForge’s assessment.
- Authorized the controlled retry with a **≥1 second post-SRAM-load settle delay** before ESP32 reset.
- Set counting rules: the initial `cycle_01` anomaly is preserved as evidence but **not counted** as a pass or fail for the ≥10-cycle gate; count only cycles that read the correct magic, complete upload, and pass all checks.
- Set escalation rule: if `0x22222222` (or any wrong magic) recurs **after** the settle delay, stop and escalate to BrightForge for RTL investigation.
- Committed the state update (`STATUS.md` + task file) as `baa4e5dc` on `brightforge/read-done-diag`.
- Sent authorization mail #14591 to BronzeGate and BrightForge.

---

## Request to external reviewer

Please review the attached source bundle and this description, then answer:

1. **Plausibility:** Is BrightForge’s post-reconfigure settle/early-read explanation the most likely cause of `magic=0x22222222` immediately after `openFPGALoader` SRAM reconfigure? If not, what other mechanisms could produce this specific value?

2. **Diagnostic confidence:** What additional read-only diagnostic (no code changes) could distinguish between:
   - a genuine RTL issue (e.g., misconfiguration of `QspiTransportCore`/`QspiSlaveSync` after SRAM load),
   - a host-side timing issue (ESP32 issuing the first transaction before the FPGA is ready),
   - a bus/electrical issue (SCLK/CS noise during or just after reconfigure)?

3. **Retry conditions:** Is a ≥1 s post-SRAM-load delay a reasonable first step? Are there other preconditions (e.g., waiting for PLL lock indicator, ensuring CS/SCLK idle state, a specific `openFPGALoader` reset option) that should be required before counting the cycle?

4. **Safety:** Are there any risks in continuing the ≥10-cycle reproof if the first post-settle retry returns the correct magic? Should any specific capture or health check be added to ensure the anomaly is not silently recurring mid-test?

Please keep recommendations to read-only diagnostics or host-side procedure changes; no new RTL or production firmware edits are authorized for this lane unless the post-settle anomaly repeats.
