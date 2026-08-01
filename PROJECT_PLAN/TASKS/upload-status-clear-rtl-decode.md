# upload-status-clear-rtl-decode

**Owner:** BrightForge (RTL clear decode) + BronzeGate (firmware validation)  
**PM:** TopazCliff  
**Verifier:** CyanPeak (code-to-spec review)  
**Status:** OPEN — BronzeGate firmware sign-off complete; waiting on BrightForge RTL sign-off and implementation
**Opened:** 2026-08-01  
**Trigger:** External review of Lane 1 (`2bpp-bank-completion-hw-reproof`) flagged that uncleared upload-bridge sticky bits could derail automated multi-cycle reproofs. The firmware helper already issues `0x0323`, but the RTL decoder is missing (`FULL-DOC-AUDIT-151` finding #4).

---

## Background

`UPLOAD_STATUS_CLEAR` (`0x0323`) is documented in `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2 as a write-1-to-clear register for upload-bridge sticky bits surfaced by `READ_STATUS` `sel=6` / `SEL_TRANSPORT_HEALTH` `sel=0x0A`:

| Bit | Name | Cleared by |
|---|---|---|
| 2 | `upload_error` / `watchdog_abort` | `0x0323` bit 2 |
| 3 | `upload_overflow` / `fifoOverflow` | `0x0323` bit 3 |
| 4 | `txn_dropped` | `0x0323` bit 4 |
| 5 | `short_frame` (reserved / Fix A) | `0x0323` bit 5 |

`firmware/libvdp/vdp_host.c` `vdp_clear_upload_status()` issues the documented write on both QSPI and i80 backends, and `firmware/libvdp/vdp_host_p4.c` does the same for the ESP32-P4 QSPI app. However, `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` #4 confirms that `0x0323` is **not decoded anywhere in the current RTL**: `VdpTop.scala` handles `0x0320..0x0322`, and neither `QspiDecoder.scala` nor `I80HostInterface.scala` has a clear input. A register-coverage script showed `0x0323` as the only allocated address with no RTL decoder.

Until the decode lands, sticky upload-status bits clear only at power-on reset or through an upload-bridge reset path. In automated 10-cycle reproofs, a single transient sticky assertion on cycle *N* will falsely poison cycles *N+1..10* because `vdp_clear_upload_status()` is a no-op at the hardware level.

---

## Scope

1. **RTL decode in the register-write path**
   - Decode `REG_WRITE` to address `0x0323` in `VdpTop.scala` (and the equivalent i80 register-write path if it is separate).
   - Drive one-cycle clear strobes matching the write-data bits to the upload bridge status registers:
     - `QspiSdramBridge` / `QspiDecoder` sticky regs for `upload_error`, `fifoOverflow`, `txn_dropped`.
     - `I80HostInterface` block-write status regs for the same bits.
   - Implement genuine write-1-to-clear semantics: a set bit in the write data clears the corresponding sticky flag; zeros leave it unchanged. A clear and a live set in the same cycle must not lose the live event.

2. **Spec and doc updates**
   - Remove the "current limitation" note from `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2 and the register table once the decode is proven.
   - Update `firmware/GOTCHAS.md` FIDELITY-2/FIDELITY-6 to remove the workaround language after validation.
   - Update `firmware/libvdp/mode0_regs.json` `UPLOAD_STATUS_CLEAR` description to remove the pending-decode caveat.

3. **Validation**
   - BronzeGate: validate `vdp_clear_upload_status()` on QSPI (ESP32-P4) and, if an i80 test harness is available, on i80.
   - Demonstrate that setting then clearing each sticky bit via the register works as expected.

4. **Out of scope**
   - New sticky-bit definitions. Only the existing bits 2..4 (and bit 5 placeholder if Fix A has landed) need clear strobes.
   - Changes to `vdp_clear_upload_status()` firmware signature or mask — the helper is already correct.

---

## Acceptance Criteria

- [ ] `VdpTop.scala` (and i80 path) decodes `0x0323` writes and emits W1C clear strobes.
- [ ] Sticky bits are individually clearable without losing a concurrently occurring error.
- [ ] All existing co-sims pass (`Indexed2bppFineCoSim`, `Indexed2bppCheckerCoSim`, `Indexed2bppFrameCoSim`, `QspiTransportBridgeSim` or successor).
- [ ] Gowin PnR is clean (TNS=0, no new BSRAM/DSP).
- [ ] BronzeGate validates clear behavior on hardware (QSPI; i80 if harness available).
- [ ] CyanPeak code-to-spec review PASS.
- [ ] Docs updated in the same logical change that lands the RTL fix.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/upload-status-clear-rtl-decode/`.
- [ ] `STATUS.md` lane updated to `DONE` with proof.

---

## Dependencies

- None hard; this can be worked in parallel with Lane 1 hardware reproof if BrightForge has bandwidth.
- Validation depends on a working QSPI bench setup (currently active for Lane 1).

## Risks / Open Questions

1. **Timing of landing vs. Lane 1 reproof:** If the decode is not ready before Lane 1 runs, Lane 1 must treat any non-zero sticky bit as a hard abort for the whole run rather than a per-cycle failure. This is already recorded in `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md`.
2. **i80 path:** The i80 `READ_STATUS` opcode `0x04` response path is also pending (`DOC_AUDIT_FINDINGS.md` #3). If this lane also implements that, update scope; otherwise keep i80 scope limited to the register-write clear decode.
3. **W1C atomicity:** A write that clears bit 3 while the bridge is asserting overflow in the same cycle must result in the bit remaining set. The standard "clear takes effect combinationaly but the set wins in the same cycle" pattern is acceptable.

---

## PM scope decision (2026-08-01)

An external reviewer provided a Rule-19-style checkpoint draft that aligns with
BrightForge's option (A): implement the documented `0x0323` W1C decode for the
bridge upload-status bits surfaced on `READ_STATUS` `sel=6`, with **zero
firmware changes**. This matches the existing `vdp_clear_upload_status()`
contract and `MODE0_REGISTER_BUS_SPEC.md` §3.1.2.

**Decision:**

1. **Primary scope is (A):** implement the `0x0323` write-1-to-clear decode for
the `sel=6` upload-status sticky bits (`upload_error`, `upload_overflow`,
`txn_dropped`, `short_frame`). Re-surface `sel=6` if it is currently tied off.
Use the exact bit mapping in `PROJECT_PLAN/INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md`.
2. **Zero firmware changes:** BronzeGate must confirm the existing
`VDP_UPLOAD_STATUS_ERROR` / `OVERFLOW` / `TXN_DROPPED` mask bits already match
this mapping.
3. **Opportunistic (B) only if free:** making the live `sel=0x0A`
transport-health stickies (`overflow`/`malformed`) clearable is acceptable, but
must not expand schedule or require firmware mask changes. If it cannot be done
inside the ~1-day option-A envelope, defer it.
4. **Keep i80 `READ_STATUS` opcode `0x04` out of this lane:** that is the
separate read-path finding (`DOC_AUDIT_FINDINGS.md` #3). This lane is the
register-write `0x0323` clear decode on both QSPI and i80 write paths.
5. **Rule 19 checkpoint required:** before any RTL is committed, BrightForge and
BronzeGate must both approve
`PROJECT_PLAN/INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md` in writing.
6. **Bitstream isolation:** this lane builds its own bitstream; do **not** fold
the decode into the `a5a047a2` Lane 1 authority bitstream, because that would
invalidate the bank-completion hardware reproof.

## Next Action

**BrightForge:** Review and approve
`PROJECT_PLAN/INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md`. Reply to the
mail thread with sign-off. BronzeGate has completed the firmware review and
approved the checkpoint in #14597, confirming zero firmware changes are
required: the existing `0x0323` address, bit-2/3/4 masks, and QSPI+i80 helper
writes already match the contract. Once BrightForge approves, implementation
may begin (estimated ~0.5–1 day RTL + sim + PnR per BrightForge's earlier
note). This lane can proceed in parallel with the Lane 1 investigation because
it uses a separate bitstream.
