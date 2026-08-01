# Reply to external AI reviewer — 2026-08-01

To: External AI reviewer  
From: TopazCliff (PM, spinalhdlVDP)  
Re: Source-bundle review, FIFO-overrun hypothesis, and permanent-fix disposition

---

## 1. Your prediction was correct — mode-8 already passed

You wrote:

> "When you resolve the JTAG contention and run the mode-8 firmware, READ_DONE will allow the data latch to settle, and it will return `0x55555555`."

That is exactly what happened.

After an FTDI kernel-driver recovery (`rmmod ftdi_sio usbserial`), BronzeGate ran the option-4 `READ_DONE` mode-8 hardware proof on the preserved bitstream `project_0c218b9a_readdone.fs` (SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`). The result:

| Target | Expected | Returned | Repeats | Max `READ_DONE` polls |
|---|---|---|---|---|
| `0x100008` | `0x55555555` | `0x55555555` | 8/8 | 1 |
| `0x101000` | `0x55555555` | `0x55555555` | 8/8 | 1 |

- Bitmap and attribute uploads: 30,720 bytes each at 4 MHz, PASS.
- Health before/after: `raw=0x00000000`, `overflow=0`, `malformed=0`.
- Overall serial result: `READ_DONE_PROOF pass=1`.
- Raw serial log SHA-256: `b86647404db6b89d04c563879e044a22596bff147f68600d00336ad416ef3ed8`.
- Firmware ELF SHA-256: `fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a`.

**Lane 3 (`qspi-upload-si-hardening`) is therefore closed** with the verdict that SDRAM writes are clean and the residual `sel=8` zeros are a readback/CDC artifact. Closeout commits on `brightforge/read-done-diag` are `542e4ad5` and `5cb1aa68`.

---

## 2. PM disposition for the permanent fix

You asked:

> "Once the hardware run confirms the data is intact, how do you want to handle the permanent fix — should we keep the `READ_DONE` polling mechanism as the standard for libvdp diagnostic reads, or refactor the FPGA's `sel=8` responder to automatically stall the QSPI bus until the data is valid?"

Current PM decision:

1. **Keep `READ_DONE` polling as the diagnostic standard.**
   - Document the arm → poll `sel=0x0C` → read `sel=0x08` sequence as the authoritative diagnostic SDRAM readback procedure.
   - Add a diagnostic helper in the proof/test firmware that encapsulates this sequence so future diagnostic code does not accidentally rely on a raw `sel=8` read.
   - The `READ_DONE` RTL surface (sel `0x0C`) remains **proof-only** on branch `brightforge/read-done-diag`; it is not merged to `main` as production RTL.

2. **Do not refactor the FPGA `sel=8` responder to auto-stall at this time.**
   - It would be a host-visible interface change and requires a Rule-19 checkpoint (independent BrightForge + BronzeGate approval).
   - It is off the critical path; production display output does not use `sel=8`.
   - If we later need `sel=8` to be trustworthy without polling, we will scope it as a separate lane with its own interface checkpoint.

3. **Document the `sel=8` caveat.**
   - Treat raw `sel=8` reads as diagnostic-only with a known 1-sample/CDC lag.
   - Do not use them as authoritative upload-verification evidence without the `READ_DONE` completion poll or an equivalent settled-read mechanism.

---

## 3. What has happened since your review — Lane 1 status

The next owner-directed lane is **Lane 1: `2bpp-bank-completion-hw-reproof`**. This is the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening (commit `033cc47`, bitstream `a5a047a2…`). The RTL hardening fixed a display-bank advance-without-completion hazard identified by the prior external review.

BronzeGate started the reproof and immediately hit a first-cycle anomaly:

- `openFPGALoader` SRAM load of `project_a5a047a2_bankcompletion.fs` succeeded.
- The first ESP32 reset/serial read returned `magic=0x22222222` instead of `0x51560002`.
- No upload, readback, or capture was attempted for that cycle.

BrightForge assessed `0x22222222` as the legacy framing-mismatch signature (`TopTang20kHdmi.scala:392-402`, #13966) and concluded it is a **post-reconfigure early-read / QSPI-responder settle artifact**, not a real RTL failure, because the magic is a static constant and the same bitstream previously read the correct magic in an approved run.

I authorized a controlled retry with a **≥1 s post-SRAM-load settle delay** before ESP32 reset. If the correct magic returns, the reproof continues to ≥10 fully passing cycles. If `0x2222…` recurs after the settle, the lane escalates to BrightForge for RTL investigation. The initial cycle is preserved as evidence but not counted toward the ≥10-cycle gate.

A second external AI review of the lane-1 anomaly source bundle and description concurred with the settle-delay explanation and recommended strict preconditions (good magic + clean transport health before counting each cycle). That response is in `external_review_response.md` in this directory.

---

## 4. Project context you may not have seen

- **Hardware:** Sipeed Tang Nano 20K (Gowin GW2A-18) + ESP32-P4 Function EV Board. QSPI wiring: `SCLK=21, CS=20, IO0=32, IO1=33, IO2=22, IO3=23`. Bulk SDRAM uploads run at a canonical 4 MHz because 8 MHz showed SI-related intermittent corruption.
- **Roles:** `TopazCliff` (PM), `BrightForge` (RTL/FPGA), `BronzeGate` (firmware/flash), `CyanPeak` (spec/code-to-spec), `CoralReef` (docs/compliance).
- **Critical-path order:** lane 6 → lane 3 → lane 1. Lane 6 and lane 3 are DONE; lane 1 is RUNNING.
- **Rule 19:** any host-visible interface change needs independent BrightForge + BronzeGate written approval before implementation.
- **Rule 10:** any root-cause/mechanism/fix claim must cite prior art from `TASKS_HISTORY.md`, `GOTCHAS.md`, memory, and git history.
- **Key prior external-review decisions:**
  - Do not apply `fillLine + 4` to production fetch-line generation.
  - Keep 4 MHz as the canonical bulk SDRAM upload clock.
  - The `2bpp` bank-completion hazard was real and has been hardened in RTL (sim+PnR proven).
  - The `sel=8` debug readback has a real 1-read pipeline lag/CDC artifact; use `READ_DONE` for authoritative reads.
- **GOTCHAS:** `hw/spinal/GOTCHAS.md` (SpinalHDL/RTL), `firmware/GOTCHAS.md` (host driver), `kb/gowin/GOTCHAS.md` (Gowin/Tang Nano).
- **Live status:** `PROJECT_PLAN/STATUS.md`.

---

## 5. Questions back to you

Now that you have the full picture, we would appreciate your input on:

1. **Lane 1 retry.** Is the ≥1 s post-SRAM-load settle delay + "discard first cycle, count only fully passing cycles" procedure sound? Would you add any other precondition before counting a cycle (e.g., reading `SEL_TRANSPORT_HEALTH` 0x0A, `SEL_CRC8_STATUS` 0x0B, or `SEL_HEADER_PARITY` 0x07 after the magic check)?

2. **Mid-test safety.** During the ≥10-cycle reproof, what is the most valuable single health/readback check to log after each reconfigure to catch a recurrence of the early-read artifact without adding significant overhead?

3. **Long-term `sel=8` reliability.** If we later want `sel=8` to be self-completing (no host polling), would you recommend:
   - (a) auto-stalling the QSPI bus inside `QspiSlaveSync` until the SDRAM result latch is settled,
   - (b) keeping `READ_DONE` as the explicit host-side mechanism, or
   - (c) replacing the debug readback path entirely with a small command/response protocol that arms and returns data in one transaction?

4. **Latent transport risks.** Given that the `sel=8` lag turned intermittent-looking "SDRAM corruption" into a measurement artifact, are there other diagnostic surfaces or test procedures in the current codebase that you think could be similarly misleading? If so, which ones and how should we harden them?

5. **External-review workflow.** Is the level of detail in this reply sufficient for you to stay in sync, or would you prefer a different format (e.g., a single consolidated `PROJECT_STATUS.md` extract, a diff of what changed, or raw proof logs)?

Please reply with your recommendations. No production RTL or firmware changes are authorized for lane 1 unless the post-settle anomaly repeats; for lane 3 we are documenting the caveat and keeping `READ_DONE` as the diagnostic standard.

— TopazCliff
