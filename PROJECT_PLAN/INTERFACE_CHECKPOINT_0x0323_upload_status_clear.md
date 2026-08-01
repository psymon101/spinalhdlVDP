# Rule 19 Interface Checkpoint: Enable `0x0323` Upload Status Clear (`FULL-DOC-AUDIT-151`)

**Author:** TopazCliff (PM)  
**Status:** DRAFT / Pending Sign-off  
**Required Approvals:** BrightForge (RTL), BronzeGate (Firmware)  
**Date:** 2026-08-01  
**Related:** `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md`, `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` #4, `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2

---

## 1. Background and Motivation

To support fully autonomous regression testing and reliable hardware proofs, the host firmware must be able to clear sticky upload-bridge error flags after a transport anomaly. The firmware helper `vdp_clear_upload_status(uint16_t mask)` already issues a write to `VDP_UPLOAD_STATUS_CLEAR_REG` (`0x0323`) on both QSPI and i80 backends.

As documented in `firmware/GOTCHAS.md` (FIDELITY-2 / FIDELITY-6) and `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` #4, the current RTL bitstream does **not** decode `0x0323`. Consequently, the documented write-1-to-clear operation is a no-op on hardware, and sticky upload-status bits clear only at power-on reset or through a bridge reset path.

This checkpoint authorizes the minimal RTL decode needed to make the existing firmware contract work.

---

## 2. Interface Contract

| Attribute | Value |
|---|---|
| **Address** | `0x0323` (`VDP_UPLOAD_STATUS_CLEAR_REG`) |
| **Operation** | Write-1-to-Clear (W1C) |
| **Target** | Mode 0 Upload Status sticky register block (`READ_STATUS` `sel=6`, byte0 bits 2..5) per `MODE0_REGISTER_BUS_SPEC.md` §3.1.2 |
| **Masking** | Bits set to `1` in the write payload clear the corresponding sticky bit; bits set to `0` are ignored (preserved). |
| **Live bits** | `sel=6` byte0 bits 0 (`upload_busy`) and 1 (`upload_done`) are **live**, not sticky, and are unaffected by `0x0323`. |
| **Reserved bits** | Bits 6..7 of `sel=6` are reserved and unaffected. |

### Bit mapping

The W1C payload mirrors the `sel=6` byte0 layout:

| `0x0323` payload bit | Clears `sel=6` byte0 bit | Name | Condition |
|---|---|---|---|
| 2 | 2 | `upload_error` | CP-A1 watchdog abort (wedge / short frame) — sticky |
| 3 | 3 | `upload_overflow` | CP-A4 ingress-FIFO overflow — sticky |
| 4 | 4 | `txn_dropped` | New `SDRAM_WRITE` header arrived while previous write still had bytes outstanding — sticky |
| 5 | 5 | `short_frame` | RESERVED — Fix A framing-hardening lane; currently stays 0 until that logic lands |
| 0, 1, 6, 7 | — | ignored | No effect |

### W1C atomicity requirement

A clear and a live set of the same sticky bit in the same clock cycle must **not** lose the live event. The standard implementation is: decode the `0x0323` write in the pixel/host-bridge domain, derive one-cycle clear strobes from the payload bits, and OR-apply them to the sticky registers such that a concurrent set path (e.g., FIFO overflow detected the same cycle) takes precedence.

---

## 3. RTL Notes

- The current `QspiTransportCore` MVP ties off the legacy `sel=6` response path, so the bridge upload-status bits are currently **invisible** to the host. Implementing this checkpoint therefore requires both:
  1. Re-surfacing the bridge `upload_error` / `upload_overflow` / `txn_dropped` / `short_frame` sticky bits on `READ_STATUS` `sel=6`, and
  2. Decoding `0x0323` writes to emit the W1C clear strobes to those sticky registers.
- The decode must be present for **both** the QSPI and i80 host interfaces (the firmware helper issues the same write on both backends).
- No new host-visible selectors, no new commands, and no protocol changes are introduced.
- This decode must **not** be folded into the `a5a047a2` Lane 1 authority bitstream; it builds its own lane bitstream so the `2bpp-bank-completion-hw-reproof` gate remains valid.

---

## 4. Firmware Notes

**Zero code changes are required.** The existing contract is:

```c
void vdp_clear_upload_status(uint16_t mask);
```

with mask bits:

```c
#define VDP_UPLOAD_STATUS_ERROR       0x0004u  // bit 2
#define VDP_UPLOAD_STATUS_OVERFLOW    0x0008u  // bit 3
#define VDP_UPLOAD_STATUS_TXN_DROPPED 0x0010u  // bit 4
```

`vdp_clear_upload_status()` already issues `vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, mask)`. Once the RTL decode lands, the same call will clear the corresponding sticky bits.

---

## 5. Responsibilities and Sign-off

### BronzeGate (Firmware)

- **Impact:** ZERO code changes.
- **Action required:** Review this checkpoint and confirm that the existing `vdp_clear_upload_status()` mask bits and the `VDP_UPLOAD_STATUS_*` defines align with the `0x0323` payload mapping above.

### BrightForge (RTL/FPGA)

- **Impact:** Low — add `0x0323` decode to the register-bus write path, re-surface `sel=6` sticky bits, and wire W1C clear strobes.
- **Action required:** Review feasibility, sign off on the bit mapping and atomicity requirement, and implement once this checkpoint is approved.

### CyanPeak (Spec / Code-to-Spec)

- **Impact:** Documentation sync.
- **Action required:** After implementation, verify that `MODE0_REGISTER_BUS_SPEC.md` §3.1.2 and `firmware/GOTCHAS.md` are updated to remove the "current limitation" caveats.

---

## 6. Sign-off

Please reply to the associated mail thread with your approval.

- [ ] BronzeGate: Approved
- [ ] BrightForge: Approved

---

*End of checkpoint*
