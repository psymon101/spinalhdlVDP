# Hardware Flash Gate — Mandatory Simulation Discipline

**Effective:** 2026-05-31
**Owner-mandated:** Process enforcement after three flashes (de04c55, 86c625e9, c7d01070) failed due to insufficient simulation.
**Enforced by:** TopazCliff (PM)

---

## Rule

**NO FPGA BITSTREAM MAY BE FLASHED TO HARDWARE UNLESS ALL OF THE FOLLOWING ARE MET:**

### 1. Co-Simulation with Real Controller FSM
- The **actual modified controller RTL** (e.g., `sdram.v`) must be instantiated in the simulation.
- **Behavioral sink models are INSUFFICIENT.** A module that accepts data without exercising the real FSM state transitions does not prove protocol correctness.
- Any claim of "sim-proven" using only behavioral sinks is **INVALID** for hardware authorization.

### 2. Co-Simulation with Behavioral Chip Model
- For SDRAM work: a behavioral SDRAM model that:
  - Decodes commands (ACTIVATE, READ, WRITE, PRECHARGE, AUTO-REFRESH)
  - Stores data in array structures (row × bank × column)
  - Enforces timing parameters (tRCD, tRP, tWR, tCAS, tRFC)
  - Handles DQM byte masking
  - Generates read data with correct latency
- For other interfaces: equivalent behavioral responder that exercises the full protocol.

### 3. Write-Then-Readback Verification
- Sim must write known patterns to **multiple addresses**.
- Sim must read back and verify **exact match**.
- Sim must test **cross-contamination**: write pattern A to address X, write pattern B to address Y, read X to ensure it is still A (not B).

### 4. Refresh / Collision Testing
- Sim must enable periodic refresh and prove writes survive refresh interference.
- Sim must test back-to-back operations (write followed immediately by read, or write during refresh window).

### 5. PM Authorization
- TopazCliff must **explicitly authorize** each flash.
- Authorization requires:
  - Verbatim sim stdout showing **PASS**
  - Commit refs for all RTL under test
  - Bitstream hash
- **"Sim running" or "sim should pass" is NOT authorization.**
- **Verbal/written "go ahead" without sim proof is INVALID.**

---

## Accountability

| Role | Accountability |
|---|---|
| **BrightForge** | May NOT request flash authorization without co-sim proof. May NOT self-authorize. Must build and pass the co-sim harness before asking. |
| **TopazCliff (PM)** | Personally accountable for enforcing this gate. May NOT authorize flash without reviewing co-sim proof. Waivers require owner explicit override. |
| **BronzeGate** | May NOT run HW gate tests on bitstreams that bypassed co-sim. Must refuse the test and report to PM. |
| **CyanPeak** | Audits sim adequacy. Flags any claim of "sim-proven" that uses insufficient models. Validates behavioral model against datasheet. |

---

## Retroactive Record

The following flashes were authorized in violation of this rule. Recorded as process failures:

| Bitstream | Date | What was missing |
|---|---|---|
| `de04c55` fresh rebuild | 2026-05-30 | No co-sim with behavioral SDRAM model |
| `86c625e9` integrated fix (CDC+SDC+T_RP/T_RCD) | 2026-05-30 | Behavioral sink only; no real SDRAM model |
| `c7d01070` Finding 1+2 (arbiter+barrier) | 2026-05-31 | Behavioral sink only; no real SDRAM model |

---

## Phase A — Structural Correctness: CLOSED (2026-06-03)

**Authority:** TopazCliff PM DECISION #11496. **Lane branch:** `brightforge/sdram-arbiter-commit-fix`.

Phase A (SDRAM upload-path structural rework) is **CLOSED**. The upload path is
structurally sound: no permanent wedge, no data loss, arbiter side-channels removed,
refresh centralized, CDC-FIFO pacing fixed.

Phase A commit log:

| CP | Commit | Change |
|---|---|---|
| CP-A1 | `9fbc235` | bridge stall-watchdog + sticky uploadError abort |
| CP-A2 | `d842247` | upload as a first-class arbiter client (client 4) |
| CP-A2b | `74e9ede` | debug-read as a first-class arbiter client (client 5) |
| CP-A3 | `3945abd` | central refresh (arbiter owns the timer) |
| CP-A4 | `22f7363` | real-sdram.v integration proof + ingress-overflow bit |
| CP-A5 | `cb2ba3b` | upload_busy reflects full CDC-FIFO drain (tile[31] fix) |

**HW proof (data path):** CP-A5 bitstream `a496e40b8f40c6eace6275534296a07229479a95a7d73e652836eebbe84762e2`
was confirmed live in FPGA SRAM via `openFPGALoader` SRAM-load (no `-f`, volatile) and
the `t` matrix discriminator showed: sentinel + all 32 tiles + tile[31] read back EXACT,
no wedge, last_err=0 (mail #11490/#11492/#11494). This is the first HW-validated upload
data path. NOTE: validated via SRAM-load only — **not yet SPI-flashed/persisted**, and the
branch is **not yet merged to main** (both pending separate PM authorization).

### Known HW caveat — bit2 (uploadError) framing false-fire

Under the probe's tight-poll `t` matrix, READ_STATUS sel=6 **bit2 (uploadError)** sticky-sets
at a **varying tile index** (observed tile[09], tile[26], tile[01] across three runs) while
the data path stays fully correct (all readbacks exact, bit3/overflow clear). Manual `w`+`u`
pacing with host delays keeps bit2=0.

- **Root cause (BrightForge diagnosis #11493):** a PRE-EXISTING QSPI ingress framing fragility
  (header LEN vs actual payload bytes delivered under back-to-back CS stress) that the CP-A1
  watchdog newly EXPOSES. It is NOT a capacity/arbiter/bridge/data bug — under clean operation
  the watchdog mathematically cannot trip (allowUpload hardwired True, host waits per-tile so
  the CC stays bounded, byte counts match by construction).
- **Firmware workaround (BronzeGate, commit `1ed6068`):** the probe's `t` banner documents that
  `t` is a tight-poll data-path matrix that may set sticky sel=6 bit2; use manual `w`+`u` pacing
  for status-clean checks, and clear the sticky bit host-side between `t` runs.
- **Disposition:** DOWNGRADED from Phase A blocker to a follow-up lane —
  **"host ingress framing hardening"** (candidate fixes: decoder transaction-completion gate, or
  start-edge/nibble hardening). Scoped after Phase B/C per #11496; no RTL fix coded yet.

> **Host-path note:** The historical examples above cite the QSPI transport because that was the active host path at the time. The flash-gate rule itself is transport-agnostic and applies equally to the current i80/ESP32-S3 canonical path.

---

## Exception

The project owner may explicitly override this gate by direct instruction. The override must be:
- Documented in writing
- Scoped to a specific flash event
- Reviewed in post-mortem

*This document is mandatory reading for all FPGA/RTL work. Updates require owner approval.*
