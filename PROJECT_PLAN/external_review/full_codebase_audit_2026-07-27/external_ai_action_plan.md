# External AI Code Audit — Action Plan (TopazCliff)

**Date:** 2026-07-27  
**Revised:** 2026-08-02  
**Audit bundle SHA-256:** `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`  
**Original review request:** `review_request_for_external_ai.md`

---

## Executive Summary

The external AI confirmed the suspicion: the repository has a **split-brain status architecture**. Firmware headers promise selectors and status bits that the current RTL either does not implement or has tied off. This drift is the root cause of the confusion that forced us to grep across half the repo to answer *"what are the other status bits?"*

The external AI issued **five mandatory cleanup directives**. This plan maps those directives onto our current lanes, identifies conflicts, and proposes a controlled execution order. The plan was revised after BronzeGate (#14621) and BrightForge (#14623) raised concrete selector-collision and contract conflicts.

---

## 1. External AI Findings (Condensed)

| Finding | Severity | Summary |
|---------|----------|---------|
| `vdp_wait_vblank()` / `vdp_wait_sticky()` poll `READ_STATUS` sel=5, but `QspiTransportCore` does not decode sel=5. | **CRITICAL** | VBLANK pacing is broken for QSPI hosts. |
| Upload-status inputs (`upload_busy/done/error/overflow`) are tied off in `QspiTransportCore`. | **CRITICAL** | QSPI hosts cannot see bridge backpressure. |
| `vdp_reg_read()` returns 0 on P4; the RegBus is write-only. | **HIGH** | The public API has a function that cannot work on the canonical transport. |
| Firmware defines `READ_STATUS` selectors 1–6; RTL only implements 0, 7, 8, 9, 10, 11, 12. | **HIGH** | Headers and hardware disagree. |
| Upload-status clear at `0x0323` is not decoded in current RTL. | **HIGH** | Errors would be permanently stuck once surfaced. |
| Lane 2 plan puts `0x0323` decode only in `I80HostInterface.scala`. | **HIGH** | QSPI hosts calling `vdp_clear_upload_status()` would still be unable to clear errors. |

---

## 2. Canonical Status Contract (Revised)

The project agrees with the external AI's unified-model goal, but the exact selector numbers and bitfield have been reconciled with existing firmware headers and active diagnostic selectors.

### READ_STATUS selectors (QSPI / CMD=0x04)

| Selector | Name | Source |
|----------|------|--------|
| `0x00` | Magic | `QspiTransportCore` |
| `0x05` | **VDP sticky status** | `VdpTop.statusStickyReg` |
| `0x06` | **Upload status** | `QspiSdramBridge` / `QspiTransportCore` |
| `0x07` | Header parity health | `QspiTransportCore` |
| `0x08` | SDRAM debug readback | `QspiTransportCore` |
| `0x09` | Last reg-write loopback | `QspiTransportCore` |
| `0x0A` | Transport health | `QspiTransportCore` |
| `0x0B` | CRC8 error | `QspiTransportCore` |
| `0x0C` | READ_DONE | `QspiTransportCore` |
| `0x0D` | Lane 1 diagnostic only (not production) | `QspiTransportCore` |

`0x05` and `0x06` already exist in firmware headers; this contract finally implements them in RTL.

### Memory-mapped status / W1C registers

Decode centrally in `VdpTop.scala`. Reads return current value; writes are W1C clear.

| Register | Read | Write |
|----------|------|-------|
| `0x0320` | VDP sticky status | W1C clear |
| `0x0321` | Sticky IRQ enable mask | R/W mask |
| `0x0322` | Sprite-sprite collision mask | W1C clear |
| `0x0323` | Upload status | W1C clear |

### Upload status bitfield

| Bit | Name | Notes |
|-----|------|-------|
| 0 | `BUSY` | Live, not sticky |
| 1 | `DONE` | Sticky until cleared |
| 2 | `ERROR` | Sticky until cleared |
| 3 | `OVERFLOW` | Sticky until cleared |
| 4 | `RESERVED` | Must read 0 |
| 5 | `RESERVED` | Must read 0 |

Clear mask for `0x0323`: bits 2 and 3. Bit 4 (`TXN_DROPPED`) is deferred until a detector is designed and authorized.

### i80 parity

i80 hosts read status from the same memory-mapped registers (`0x0320`, `0x0323`). They clear status by writing those registers. No separate i80 `READ_STATUS` opcode is required.

---

## 3. Conflicts with Current Lanes

### Lane 1: `2bpp-bank-completion-hw-reproof`

- **Status:** BLOCKED. Locked to bitstream `project_a5a047a2_bankcompletion.fs`.
- **Conflict:** None. Lane 1 must not be touched by cleanup.
- **Action:** BrightForge is authorized to build and BronzeGate to flash a **separate diagnostic bitstream** (`eaad44f8`) to resolve the fresh-reconfigure `0x22222222` anomaly. The cleanup lane does not depend on Lane 1 closing first, but no cleanup RTL may be committed into the Lane 1 bitstream.

### Lane 2: `upload-status-clear-rtl-decode`

- **Status:** PAUSED.
- **Conflict:** Original option-1 i80 local decode would leave QSPI hosts unable to clear errors.
- **Action:** Folded into the cleanup lane. Central `VdpTop.scala` decode replaces the local-decode approach.

---

## 4. Execution Plan

Create a new lane/branch: `codebase-cleanup-status-contract`.

### Step A — Design & Approval (TopazCliff)

1. Circulate the revised `rule19_signoff_request.md` to BrightForge and BronzeGate.
2. Obtain **written Rule 19 approval**.
3. Update `STATUS.md` and the lane task file.

### Step B — RTL (BrightForge)

1. In `QspiTransportCore.scala`:
   - Implement `sel=0x05` output from a new `status_sticky` input.
   - Implement `sel=0x06` output from `upload_busy/done/error/overflow` inputs.
   - Remove the tie-offs on the upload-status inputs.
2. In `VdpTop.scala`:
   - Decode `0x0323` as upload-status read/W1C.
   - Decode `0x0320` as sticky-status read/W1C (read path may already exist; verify).
   - Route `statusStickyReg` to the QSPI core.
3. In `I80HostInterface.scala`:
   - Ensure `0x0320` and `0x0323` reads return the current status words.
4. In `TopTang20kHdmi.scala`:
   - Wire the new `QspiTransportCore` status inputs to the real sources.

### Step C — Firmware (BronzeGate)

1. Update `vdp_host.h` selector comments to match the exact RTL map (`0x05`, `0x06`).
2. Update `vdp_status.h` / `vdp_i80.h` constants if needed (they should already align).
3. Keep `vdp_reg_read()` active; document the P4 write-only limitation and call sites.
4. Ensure `vdp_clear_upload_status()` uses the canonical `0x0323` W1C contract with bits 2/3.

### Step D — Documentation (CoralReef)

1. Update `MODE0_REGISTER_BUS_SPEC.md` with the canonical status map.
2. Update `firmware/GOTCHAS.md` to remove contradictions.
3. Update any README that documents the old selector map.

### Step E — Archive Dead Code (deferred)

- Do **not** archive `vdp_reg_read()` (active API).
- Do **not** archive `QspiSlave.scala` (active source).
- Legacy QSPI shims require a consumer audit before archival; defer to a follow-up lane.

### Step F — Verification

1. SpinalHDL sims pass.
2. Synthesis/PnR for Tang Nano 20K passes.
3. Firmware builds for ESP32-P4 and legacy targets.
4. Rule 19 sign-off rechecked after implementation.

### Step G — Re-bundle for External AI (TopazCliff)

After cleanup is committed, regenerate `source_bundle.md` and submit it to the external AI for final verification.

---

## 5. Gating Criteria Before Hardware Debugging Resumes

- [ ] Rule 19 written approval from BrightForge and BronzeGate.
- [ ] Lane 1 reproof closed (pass or fail documented) **or** explicitly paused by PM.
- [ ] Lane 2 folded into cleanup lane.
- [ ] Cleanup branch passes sim + synth.
- [ ] External AI final verification PASS.

---

## 6. Risks

- **Scope creep:** The cleanup touches transport, register bus, and status logic. It is larger than Lane 2.
- **Hardware re-validation:** Any RTL change requires a new bitstream and fresh proof.
- **Legacy host breakage:** Changing selector semantics may break archived sketches; using existing `0x05`/`0x06` numbers minimizes this.

---

## 7. Next Immediate Action

TopazCliff has authorized the Lane 1 diagnostic bitstream flash/readout and is requesting revised Rule 19 sign-off from BrightForge and BronzeGate.
