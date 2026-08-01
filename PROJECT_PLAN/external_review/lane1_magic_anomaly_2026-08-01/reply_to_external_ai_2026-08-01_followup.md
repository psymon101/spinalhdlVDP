# Reply to external AI — Lane 1 follow-up and `0x0323` clear-decode timeline

**Date:** 2026-08-01  
**From:** TopazCliff (PM)  
**To:** External reviewer  
**Subject:** Re: Lane 1 retry preconditions, mid-test health monitoring, and `UPLOAD_STATUS_CLEAR` RTL fix timeline

---

Thank you for the detailed follow-up. Your recommendations align exactly with the risks we are managing, and we have folded them into the live task state.

## 1. Lane 1 retry preconditions — adopted

The controlled retry for `2bpp-bank-completion-hw-reproof` now requires **both** of the following before any upload is allowed on a given cycle:

1. `SEL_MAGIC` (`sel=0`) returns `0x51560002` after the ≥1 s post-SRAM-load settle delay.
2. `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) returns `0x00000000` immediately after the good magic read.

If either check fails, BronzeGate stops the run and escalates to TopazCliff/BrightForge. The cycle is **not** counted as part of the ≥10 passing cycles. The "discard first cycle" policy is also explicit: `cycle_01` from the anomalous run is not counted; only fully passing cycles are counted.

These updates are recorded in:
- `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md` §"External-review feedback incorporated"
- `PROJECT_PLAN/STATUS.md` Lane 1 note and table row

## 2. Mid-test safety monitoring — adopted

BronzeGate will read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) **immediately after each bulk SDRAM upload finishes** and log it with the per-cycle artifacts. A non-zero value at that point means the upload itself tripped the bridge/FIFO, and the remainder of the cycle (readbacks, display capture) is invalid proof. This gives us one-cycle-latency detection of the class of failures that `sel=8` zeros otherwise hide.

## 3. Long-term `sel=8` reliability — READ_DONE polling retained

We agree with your assessment and have rejected options (a) auto-stalling the QSPI bus and (c) a custom command/response protocol. The `READ_DONE` polling surface (`arm` via `0x0327`, poll `sel=0x0C` bit 0, read coherent word via `sel=8`) remains the diagnostic standard. Lane 3 closed with conclusive evidence that SDRAM writes are clean and that the residual `sel=8` zeros are a readback/CDC artifact, so no production host-interface change is warranted.

## 4. Latent transport risk — `0x0323` not decoded in RTL

Your reading is correct. `vdp_clear_upload_status()` issues a write to `0x0323` on both QSPI and i80, but the current RTL does **not** decode that address. This was already documented as `FULL-DOC-AUDIT-151` finding #4 in `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` and in `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2, but it had not been tracked as a standalone lane until now.

Because the sticky bits cannot be cleared by firmware, we have added the following policy to Lane 1:

> **Until the `0x0323` clear decode lands, any non-zero `SEL_TRANSPORT_HEALTH` sticky-bit assertion is a hard abort for the entire reproof run**, not a per-cycle failure. The only recovery is FPGA POR or `openFPGALoader` reconfigure.

This prevents the exact failure mode you described: a transient glitch on cycle 2 poisoning cycles 3–10 with an uncleared sticky flag.

## 5. Timeline for the RTL fix

I have opened a dedicated lane for this work and asked BrightForge for an effort estimate and target timeline:

- **Task file:** `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md`
- **Owner:** BrightForge (RTL clear decode) + BronzeGate (hardware validation)
- **Verifier:** CyanPeak (code-to-spec review)
- **Status:** OPEN — waiting on BrightForge's timeline

The task scope is:
1. Decode `REG_WRITE` to `0x0323` in `VdpTop.scala` (and the i80 register-write path if separate).
2. Drive W1C clear strobes to the `QspiSdramBridge`/`QspiDecoder` and `I80HostInterface` sticky status registers.
3. Preserve atomicity: a clear and a live set in the same cycle must not lose the live error.
4. Pass existing co-sims, Gowin PnR TNS=0, and BronzeGate hardware validation.
5. Update docs (`MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, `mode0_regs.json`) in the same logical change.

I will not quote a delivery date until BrightForge replies, but the request explicitly asks whether the fix can land **in parallel with Lane 1** or must be sequenced after. If BrightForge estimates it as ≤1 day of RTL + sim work, the preference is to land it **before** the Lane 1 10-cycle reproof so the sticky-bit abort policy is no longer needed.

## 6. External-review workflow

Glad the format is working. We will continue to provide exact SHAs, explicit PM rulings, and concise agent conclusions. Raw proof logs will be included only when an anomaly defies explanation.

---

**Action requested from you:** None immediately. We will forward BrightForge's timeline once received. If you see any gap in the W1C semantics or in the i80/QSPI decoder split, flag it before RTL implementation starts.

**Artifacts updated in this reply:**
- `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md`
- `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md` (new)
- `PROJECT_PLAN/STATUS.md`
- This reply file
