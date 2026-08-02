# ADR-009 — Canonical Status Contract for QSPI and i80

**Status:** approved  
**Date:** 2026-08-02  
**Owner:** `TopazCliff` (PM), `BrightForge` (RTL), `BronzeGate` (firmware)  
**Reviewers:** `CoralReef` (docs), external AI reviewer  

## Context

An external AI full-codebase audit (`source_bundle.md` SHA-256 `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`) found that the repository had a split-brain status architecture:

- Firmware headers (`vdp_host.h`, `vdp_status.h`, `vdp_i80.h`) defined `READ_STATUS` selectors and status bits that the RTL had either abandoned or tied off.
- `QspiTransportCore` tied off `upload_busy/done/error/overflow` and did not decode the firmware-defined sticky/upload selectors.
- `0x0323` upload-status W1C was allocated in the register map but not decoded in RTL.
- i80 had no documented memory-mapped status read path, so i80 hosts could not poll upload status at all.
- `vdp_reg_read()` was documented as write-only/returning zero on some backends, creating confusion about whether it was active API.

The drift meant that answering a basic question such as *"what are the other status bits?"* required grepping across firmware headers, multiple Scala files, and docs.

## Decision

Establish a single, host-visible status contract shared by QSPI and i80, implement it in RTL, and update all governing documentation and firmware comments to match. The contract was approved via Rule 19 sign-off (BrightForge #14629, BronzeGate #14631) and external AI review on 2026-08-02.

### 1. `READ_STATUS` selectors (QSPI, opcode `0x04`)

| Selector | Content |
|----------|---------|
| `0x00` | Magic `0x51560002` |
| `0x05` | VDP sticky status (`STATUS_STICKY` bit layout) |
| `0x06` | Upload status (`BUSY`/`DONE`/`ERROR`/`OVERFLOW`) |
| `0x07` | Header-parity health |
| `0x08` | SDRAM debug readback |
| `0x09` | Last reg-write loopback |
| `0x0A` | Transport health (malformed, overflow, CRC) |
| `0x0B` | CRC8 error |
| `0x0C` | `READ_DONE` |

`0x01`–`0x04` return zero/unsupported. `0x0D` is reserved for the Lane 1 diagnostic bitstream only and is **not** part of the production contract.

### 2. Memory-mapped status / W1C registers (decoded centrally in `VdpTop.scala`)

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

Clear mask for `0x0323`: bits 2 and 3 only.

### 4. i80 parity

i80 hosts read status through ordinary memory-mapped register reads (`0x0320`, `0x0323`). No separate i80 `READ_STATUS` opcode is introduced.

### 5. Out of scope / deferred

- `vdp_reg_read()` remains active API. Its P4 QSPI write-only limitation is documented, and real read-path work is left for a future lane if needed.
- `QspiSlave.scala` remains active SpinalHDL source; no archival.
- `TXN_DROPPED` (bit 4) is deferred until a backing detector is designed and authorized.
- Legacy QSPI compatibility shims (`vdp_legacySpi.h`, `vdp_qspi.h`) remain until a consumer audit authorizes archival.

## Consequences

* **Positive:** QSPI and i80 hosts now have parity for sticky and upload status reads/clears. The contract is documented in one authoritative place (`MODE0_REGISTER_BUS_SPEC.md`) and backed by an ADR.
* **Positive:** Centralizing W1C decode in `VdpTop.scala` removes the previous split-brain where QSPI and i80 might have cleared different state.
* **Negative:** Firmware headers (`vdp_host.h`, `vdp_status.h`, `vdp_i80.h`) and the `kb/libvdp/README.md` API reference must be aligned with the canonical selector numbers.
* **Negative:** Bitstreams built before this lane do not decode `0x0323`; host code must tolerate the pre-cleanup limitation on old bitstreams.

## Related

* **STATUS.md lane:** `codebase-cleanup-status-contract`
* **Task file:** [codebase-cleanup-status-contract.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/TASKS/codebase-cleanup-status-contract.md)
* **Rule 19 sign-off request:** [rule19_signoff_request.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md)
* **External AI action plan:** [external_ai_action_plan.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/external_ai_action_plan.md)
* **Authoritative register spec:** [MODE0_REGISTER_BUS_SPEC.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md)
* **Firmware pitfalls:** [firmware/GOTCHAS.md](/home/itadmin/github/spinalhdlVDP/firmware/GOTCHAS.md)
* **API reference:** [kb/libvdp/README.md](/home/itadmin/github/spinalhdlVDP/kb/libvdp/README.md)
