# Task — Codebase Cleanup / Status Contract

**Lane ID:** `codebase-cleanup-status-contract`  
**Owner:** TopazCliff (PM), BrightForge (RTL), BronzeGate (firmware), CoralReef (docs)  
**Opened:** 2026-07-27  
**Status:** MERGE AUTHORIZED — all implementation, simulation, PnR, code-to-spec, firmware build, and external-AI verification gates PASS; PM merge authorization granted; awaiting merge execution  
**External AI audit bundle:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/source_bundle.md` (SHA-256 `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`)  
**External AI final verification package:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/final_verification_2026-08-03/`

---

## Problem Statement

The repository has a split-brain status architecture:

- Firmware headers (`vdp_host.h`, `vdp_status.h`, `vdp_i80.h`) define status bits and `READ_STATUS` selectors that the RTL has either abandoned or tied off.
- `QspiTransportCore` ties off `upload_busy/done/error/overflow` and does not decode `sel=5` (sticky status).
- `vdp_reg_read()` returns 0 on the ESP32-P4 QSPI backend because the RegBus is write-only there, but it is active API used by `vdp_mode0.c` and the i80 backend.
- `0x0323` upload-status W1C is not decoded in the current RTL.
- i80 has no documented memory-mapped status read path.

This drift caused a basic question (*"what are the other status bits?"*) to require grepping across firmware headers, multiple Scala files, and docs.

---

## Goal

Establish a single, accurate, host-visible status contract shared by QSPI and i80, and update documentation so the headers, RTL, and docs agree. Do not archive active API or active RTL source.

---

## Canonical Contract

### READ_STATUS selectors (QSPI / CMD=0x04)

| Selector | Content |
|----------|---------|
| `0x00` | Magic `0x51560002` |
| `0x05` | **VDP sticky status** (16 bits) — newly implemented |
| `0x06` | **Upload status** (4 bits used) — newly implemented |
| `0x07` | Header-parity health |
| `0x08` | SDRAM debug readback |
| `0x09` | Last reg-write loopback |
| `0x0A` | Transport health |
| `0x0B` | CRC8 error |
| `0x0C` | READ_DONE |

`0x01`–`0x04` remain zero/unsupported. `0x0D` is reserved for the Lane 1 diagnostic bitstream only and is **not** part of the production contract.

### Memory-mapped status / W1C registers (decoded centrally in `VdpTop.scala`)

Reads return the current value; writes are W1C clear.

| Register | Read | Write |
|----------|------|-------|
| `0x0320` | VDP sticky status | W1C clear |
| `0x0321` | Sticky IRQ enable mask | R/W mask |
| `0x0322` | Sprite-sprite collision mask | W1C clear |
| `0x0323` | Upload status | W1C clear |

### Upload status bits

| Bit | Name | Notes |
|-----|------|-------|
| 0 | `BUSY` | Live, not sticky |
| 1 | `DONE` | Sticky until cleared |
| 2 | `ERROR` | Sticky until cleared |
| 3 | `OVERFLOW` | Sticky until cleared |
| 4 | `RESERVED` | Must read 0; W1C write ignored |
| 5 | `RESERVED` | Must read 0; W1C write ignored |

Clear mask for `0x0323`: bits 2 and 3. Bits 4/5 remain RESERVED-0; a future lane may define bit 4 only after adding a backing detector.

### i80 parity

i80 hosts read status from the same memory-mapped registers (`0x0320`, `0x0323`) via the existing `vdp_reg_read()` / `io.readData` path. No separate i80 `READ_STATUS` opcode is introduced.

---

## Work Breakdown

### BrightForge (RTL)

- [x] In `QspiTransportCore.scala`: implement `sel=0x05` (sticky status) and `sel=0x06` (upload status), removing the existing tie-offs.
- [x] In `VdpTop.scala`: centralize `0x0320` and `0x0323` read/W1C decode.
- [x] In `TopTang20kHdmi.scala`: wire `VdpTop.statusStickyReg` to `QspiTransportCore`, wire `QspiSdramBridge` upload stickies to `VdpTop`/`QspiTransportCore`, and implement the `0x0320`/`0x0323` → `I80HostInterface.io.readData` mux so i80 reads return real status.
- [x] Add/update SpinalSim tests for `sel=0x05`, `sel=0x06`, and `0x0323` W1C (including set-wins-on-tie).
- [x] Run the **full affected regression suite** (`Indexed2bpp{Fine,Checker,Frame}CoSim` + QSPI/i80 sims) and Gowin PnR on a separate lane bitstream (TNS=0, no unexpected new BSRAM/DSP).
- [x] Record synthesis/timing/resource impact.

### BronzeGate (firmware)

- [x] Update `vdp_host.h` selector comments to match RTL (`0x05` sticky, `0x06` upload).
- [x] Align `vdp_status.h` / `vdp_i80.h` constants with canonical model.
- [x] Align `firmware/libvdp/mode0_regs.json` descriptions/fields with canonical contract.
- [x] Keep `vdp_reg_read()` active; document the P4 QSPI write-only limitation and call sites.
- [x] Ensure `vdp_clear_upload_status()` uses `0x0323` W1C with clear mask bits 2/3.
- [x] Confirm all active firmware targets build under ESP-IDF v6.0.2.

### CoralReef (docs)

- [x] Update `MODE0_REGISTER_BUS_SPEC.md` with the canonical status map.
- [x] Update `firmware/GOTCHAS.md` and `kb/libvdp/README.md` contradictions.
- [x] Add ADR for the canonical status contract under `PROJECT_PLAN/DECISIONS/`.

### TopazCliff (PM)

- [x] Draft action plan and Rule 19 sign-off request.
- [x] Obtain written BrightForge + BronzeGate approval.
- [x] Update `STATUS.md`.
- [x] Regenerate final implementation bundle after cleanup commits (BrightForge `e12b37c4`).
- [ ] Submit final bundle to external AI for final verification.

---

## Archive List (deferred)

The following are **out of scope** for this cleanup lane:

- `vdp_reg_read()` — active API; do not archive.
- `QspiSlave.scala` — active SpinalHDL source; do not archive.
- Legacy QSPI compatibility shims (`vdp_legacySpi.h`, `vdp_qspi.h`) — consumer audit required before any archival; deferred.

---

## Dependencies / Blockers

- **Rule 19 sign-off:** COMPLETE (BrightForge #14629, BronzeGate #14631, External AI).
- **Lane 1:** explicitly paused by PM pending BronzeGate's discard-read prime reproof; cleanup proceeds independently and must not commit RTL/firmware into the Lane 1 authority bitstream.
- **Lane 2:** `upload-status-clear-rtl-decode` is PAUSED and folded into this lane; do not commit its option-1 local decode.

---

## Gates

- [x] Rule 19 written approval recorded.
- [x] External AI approval recorded.
- [x] Lane 1 explicitly paused by PM (discard-read prime authorized; campaign resumes on `a5a047a2`).
- [x] Lane 2 officially paused/folded.
- [x] Cleanup branch created from current active branch (`brightforge/status-contract-cleanup`, base `main` `fd39d2b0`).
- [x] SpinalHDL sim PASS (full affected regression suite).
- [x] Gowin PnR PASS (TNS=0, no unexpected new BSRAM/DSP).
- [x] CyanPeak code-to-spec review PASS (#14647).
- [x] Firmware builds PASS for all active targets (#14650).
- [x] External AI final verification PASS (user-forwarded verdict, 2026-08-04).

---

## Artifacts

- Action plan: `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/external_ai_action_plan.md`
- Rule 19 sign-off request: `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md`
- Response to external AI: `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/response_to_external_ai.md`
