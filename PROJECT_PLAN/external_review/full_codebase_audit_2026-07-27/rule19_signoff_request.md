# Rule 19 Sign-Off Request — Codebase Cleanup / Status Contract

**Requester:** TopazCliff (Project Lead)  
**Date:** 2026-07-27  
**Revised:** 2026-08-02 (addresses BronzeGate #14621 and BrightForge #14623)  
**Lane:** `codebase-cleanup-status-contract`  
**Motivation:** External AI full-codebase audit found split-brain status architecture; cleanup required before hardware debugging resumes.

---

## What is being changed

This request covers all host-visible changes introduced by the cleanup lane.

### 1. READ_STATUS selectors (QSPI / CMD=0x04)

The cleanup lane implements the selectors already defined in the firmware headers, avoiding new numbers and collisions with the Lane 1 diagnostic (`0x0D`) and Lane 3 `READ_DONE` (`0x0C`).

| Selector | Content | Source |
|----------|---------|--------|
| `0x00` | Magic `0x51560002` | `QspiTransportCore` |
| `0x05` | **VDP sticky status** (16 bits) — *newly implemented* | `VdpTop.statusStickyReg` |
| `0x06` | **Upload status** (4 bits used) — *newly implemented* | `QspiSdramBridge` / `QspiTransportCore` |
| `0x07` | Header parity health | `QspiTransportCore` |
| `0x08` | SDRAM debug readback | `QspiTransportCore` |
| `0x09` | Last reg-write loopback | `QspiTransportCore` |
| `0x0A` | Transport health (malformed, overflow, CRC) | `QspiTransportCore` |
| `0x0B` | CRC8 error | `QspiTransportCore` |
| `0x0C` | READ_DONE | `QspiTransportCore` |

`0x01`–`0x04` remain zero/unsupported. `0x0D` is reserved for the Lane 1 diagnostic bitstream only and is **not** part of the production contract.

### 2. Centralized W1C register decode

Decode moved into `VdpTop.scala` so both i80 and QSPI writes hit the same state. Reads return the current value; writes are W1C.

| Register | Read | Write |
|----------|------|-------|
| `0x0320` | VDP sticky status | W1C clear |
| `0x0321` | Sticky IRQ enable mask | R/W mask |
| `0x0322` | Sprite-sprite collision mask | W1C clear |
| `0x0323` | Upload status | W1C clear |

### 3. Upload status bitfield

| Bit | Name | Notes |
|-----|------|-------|
| 0 | `BUSY` | Live, not sticky |
| 1 | `DONE` | Sticky until cleared |
| 2 | `ERROR` | Sticky until cleared |
| 3 | `OVERFLOW` | Sticky until cleared |
| 4 | `RESERVED` | Must read 0; W1C write ignored |
| 5 | `RESERVED` | Must read 0; W1C write ignored |

Clear mask for `0x0323`: bits 2 and 3 only. Bits 4/5 remain RESERVED-0, matching the existing `INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md`. A future lane may define bit 4 (`TXN_DROPPED`) only after adding a backing detector.

### 4. i80 status read path

i80 hosts read status through the same memory-mapped registers:

- `0x0320` read → VDP sticky status
- `0x0323` read → upload status

No separate `READ_STATUS` opcode is required for i80. The W1C clear mechanism is identical for both transports.

### 5. Firmware changes

- `vdp_host.h` selector comments updated to match RTL (`0x05` sticky, `0x06` upload).
- `vdp_status.h` / `vdp_i80.h` constants aligned with canonical model.
- `vdp_clear_upload_status()` continues to use `0x0323` W1C; clear mask updated to bits 2/3.
- `vdp_reg_read()` is **not** archived. It is active API used by `vdp_mode0.c`. The cleanup lane will document the current write-only limitation and may scope a real read-path implementation separately.

### 6. Dead-code archival (deferred)

The following items are **out of scope** for this cleanup lane:

- `vdp_reg_read()` — active API; do not archive.
- `QspiSlave.scala` — active SpinalHDL source (was mistakenly archived once before and restored); do not archive.
- Legacy QSPI compatibility shims (`vdp_legacySpi.h`, `vdp_qspi.h`) — consumer audit required before any archival; deferred.

---

## Why this is a Rule 19 change

Any change to host-visible op codes, selectors, register addresses, bitfields, or clearing semantics affects both RTL and firmware compatibility. Both disciplines must approve.

---

## Approvals required

### BrightForge (RTL / FPGA Engineer)

- [x] Approve selector map (`0x05` sticky, `0x06` upload) and W1C decode in `VdpTop.scala`.
- [x] Approve i80 status-read via memory-mapped `0x0320`/`0x0323`. *(Conditional — see note 2.)*
- [x] Approve removal of tie-offs in `QspiTransportCore.scala` for `sel=0x06`.
- [x] Confirm `sel=0x0D` diagnostic is isolated to Lane 1 and will not conflict with production selector map.

**BrightForge RTL-accuracy verification (no rubber-stamp):**

1. **`sel=0x05`/`0x06` are free** (current `QspiTransportCore` responder: `0`/`7`/`8`/`9`/`10`/`11`/`12` used, `1`–`6` fall to `default → 0`). No collision. The audit's "sel=5 broken" is real: `dec.io.status_sticky := B(0)` and `dec.io.upload_* := False` are tied off.
2. **Good news — the sticky infrastructure already exists:** `VdpTop.statusStickyReg` + `0x0320` W1C decode + `io.statusSticky` are present (`VdpTop.scala:2390/2466/2486`; comment already says "read via QSPI sel=5"). So `sel=0x05` needs only un-tie + wire `VdpTop.io.statusSticky → QspiTransportCore` + add the `sel=5` case — not new sticky logic. Lower risk.
3. **Condition on item 2 (i80 status-read):** the i80 read FSM exists (`I80HostInterface` opcode `0x01` → `io.readData` → `io.dOut`), but `readData` is a **parent-driven input**. Approval is conditional on the cleanup lane implementing the address→`readData` mux in `TopTang20kHdmi` (`0x0320`→sticky, `0x0323`→upload) so i80 reads return the real values — not a stub.
4. **Implementation note (0x0323):** the upload stickies physically live in `QspiSdramBridge` (outside `VdpTop`), so a `VdpTop`-centralized `0x0323` read/W1C needs cross-module wiring (bridge stickies in, clear strobes out via `TopTang20kHdmi`). Feasible; same-clock (pixel), no CDC — mirror the existing health-selector crossing.
5. **Regression scope (binding):** this touches the shared transport responder + `VdpTop` + bridge + i80, so the merge gate must run the **full affected regression suite** (`Indexed2bpp{Fine,Checker,Frame}CoSim` + any QSPI/i80 sims), not just a new selector test, plus Gowin PnR (TNS=0, no unexpected new BSRAM/DSP), per the change-packet rule.
6. `sel=0x0D` is on the isolated `brightforge/lane1-reconfig-diag` diagnostic branch only (never merged); it is not in the production map. Confirmed no conflict.

**BrightForge signature / date / commit hash of approval:**

```
Approved (conditional on notes 3 & 5) by BrightForge on 2026-08-02.
RTL plan: mail #14607 (QSPI-side mini-spec) + action_plan Step B; no separate plan commit.
```

### BronzeGate (Firmware Engineer)

- [x] Approve firmware header changes (selectors `0x05`/`0x06`, upload bits 0–3, bits 4/5 RESERVED-0).
- [x] Confirm `vdp_reg_read()` callers are documented and no archival occurs.
- [x] Confirm `vdp_clear_upload_status()` clear mask bits 2/3.
- [x] Confirm ESP32-P4 build compatibility.

**BronzeGate firmware-accuracy verification (no rubber-stamp):**

1. `vdp_reg_read()` is active library API: `firmware/libvdp/vdp_mode0.c` calls it in `vdp_mode0_soft_reset()` and `vdp_mode0_read_bitmap_swap_ctrl()`; `firmware/libvdp/vdp_host.c` uses opcode `0x01` for successful i80 register reads. Archiving it would break the i80 pipeline and active mode0 callers. Keeping it active and documenting the P4 QSPI write-only limitation is the correct choice.
2. Upload status bits 0–3 (`BUSY`, `DONE`, `ERROR`, `OVERFLOW`) with bits 4/5 RESERVED-0 are consistent with the existing `INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md` and avoid introducing an unimplemented `TXN_DROPPED` detector.
3. ESP-IDF v6.0.2 build compatibility is the current proven baseline; all active-target builds remain required by the lane gate.

**Conditions for implementation/closeout:**
1. Implement the i80 memory-mapped read mux exactly as specified (`0x0320`→sticky status, `0x0323`→upload status), not a stub, and preserve W1C writes.
2. Run the full affected simulation suite plus Gowin PnR and active firmware-target builds before claiming closeout.
3. Synchronize `PROJECT_PLAN/TASKS/codebase-cleanup-status-contract.md` with the revised Rule 19 request (now done by TopazCliff).

**BronzeGate signature / date / commit hash of approval:**

```
Approved (conditional on implementation/verification gates) by BronzeGate on 2026-08-02.
Firmware plan: mail #14631; no separate plan commit.
```

---

## Gating checklist (to be filled before execution)

- [x] BrightForge approval recorded.
- [x] BronzeGate approval recorded.
- [ ] Lane 1 reproof closed or explicitly paused by PM.
- [x] Lane 2 officially paused/folded.
- [ ] Cleanup branch created from current active branch.
- [ ] SpinalHDL simulation passes.
- [ ] Synthesis/PnR passes.
- [ ] Firmware builds pass for all active targets.
- [ ] External AI final verification bundle submitted.

---

## External AI Approval

> **Approved by External AI Reviewer on 2026-08-02.**  
> Approval covers the revised `codebase-cleanup-status-contract` scope (selectors `0x05`/`0x06`, memory-mapped i80 reads, bits 4/5 RESERVED-0, `vdp_reg_read()` kept active, no archive of `QspiSlave.scala`).  
> Next: BrightForge + BronzeGate sign-off, then regenerate `source_bundle.md` for final verification.

---

## Notes

- Lane 1 (`2bpp-bank-completion-hw-reproof`) remains frozen. No RTL/firmware changes may be committed beneath it.
- This cleanup is larger than the original Lane 2 scope and replaces it.
- `TXN_DROPPED` (bit 4) is intentionally deferred until a detector is designed and authorized.
