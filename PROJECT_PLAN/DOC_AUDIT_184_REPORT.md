# DOC-AUDIT-184: Documentation Audit Report
**Lanes:** DOC-AUDIT-184 Phase 1  
**Authors:** CyanPeak (Code-to-Spec) & CoralReef (Doc Consistency)  
**Status:** DONE (Phase 3 doc reconciliation complete and verified by both agents)

---

## 1. Executive Summary
This audit reviews the repository's canonical documentation against the active RTL code (branch `brightforge/ham-decoder-171`) and firmware implementation to identify contradictions, dead code/file references, and outdated parameters. 

---

## 2. Contradiction & Staleness Matrix (CyanPeak Review)

| ID | Target Document / Section | Severity | Description | Evidence / Current Reality |
|---|---|---|---|---|
| **CYAN-01** | `README.md` <br> § Host Interface (Line 42) | **BLOCKER** | References active front-end as `QspiSlaveSync` + `QspiTransportCore` (word-drain frame). | **Branch/Code Discrepancy:** On the active development branch `brightforge/qspi-option-a-183` (in the `ham-build-171` worktree), these modules exist and are active. However, on the workspace branch `brightforge/ham-decoder-171`, they do not exist and instead a refactoring commit `06b7bf7` is renaming QSPI to LegacySpi, which leads to compilation errors in `TopTang20kHdmi.scala` due to incomplete renaming of `QspiSlave`, `QspiDecoder`, and `QspiSdramBridge`. |
| **CYAN-02** | `README.md` <br> § Toolchain (Line 37) & Host Interface (Line 48) | **WARN** | Mentions CMake and Pico SDK 2.2.0 for Raspberry Pi Pico 2 setups. | **Refuted by Filesystem:** There are no Pico 2 or RP2350 target source files or CMake setups under the `firmware/` directory. All active work is targeted to the ESP32-P4. |
| **CYAN-03** | `PROJECT_PLAN/MODE0_SPEC.md` <br> § 8.1 Clocking (Line 177) | **WARN** | Lists `SDRAM clock \| 81 MHz (40.5 MHz DDR) \| PLL, 2x pixel clock`. | **Refuted by Clocking RTL:** The SDRAM PLL (`tang20k_sdram_pll.v` / `TopTang20kHdmi.scala:116`) generates a clean `40.5 MHz` clock (lowered from 64.8 MHz under #11197). It is not DDR and does not run at 2x the 25.2 MHz pixel clock. |
| **CYAN-04** | `README.md` <br> § Repository layout (Line 24, 25) | **NOTE** | References `firmware/esp32s3_i80_*` and `esp32s3_rgb565_fullframe` as the primary/historical reference paths. | **Stale:** These ESP32-S3 Arduino example paths are retiring. Authority has shifted to the `esp32p4_qspi_proof` and `esp32p4_rainbow_test` ESP-IDF projects. |
| **CYAN-05** | `VDP_PROGRAMMING_GUIDE.md` <br> § 12 (Line 725, 736) | **NOTE** | Mentions `vdp_qspi.h` and includes details on QSPI header/payload timing. | **Refuted by Filesystem:** `vdp_qspi.h` was deleted on the current branch (git status) as part of the QSPI/LegacySpi renaming refactor. |
| **CYAN-06** | `PROJECT_PLAN/MODE0_SPEC.md` <br> § 2 Register Address Map (Line 46) | **BLOCKER** | Lists address range `0x1000..0x17FF` as `Palette RAM`. | **Refuted by RTL Code:** In `VdpTop.scala:2150..2200`, there is no address decoding for range `0x1000..0x17FF` on the register bus. The Palette RAM is accessed exclusively via the two registers `PALETTE_PTR` (at `0x0601`) and `PALETTE_DATA` (at `0x0600`) using an auto-incrementing pointer scheme. |
| **CYAN-07** | `PROJECT_PLAN/MODE0_SPEC.md` <br> § 1 Guaranteed Feature Summary (Line 17) & § 3 Build-Gated Features (Line 71) | **WARN** | Lists `L1 SDRAM` as a guaranteed/default active background layer (with L0). | **Refuted by Synthesis Code:** In `TopTang20kHdmi.scala:1231,1237`, the production top-level targets explicitly compile with `enableL1Fetch = false`, meaning that the second `SdramTileAttributeFetch` engine for Layer 1 is disabled/compiled-out by default in the generated Verilog builds to save BSRAM blocks (ref. `PROJECT_PLAN.md` R-010). |
| **CYAN-08** | `VDP_PROGRAMMING_GUIDE.md` <br> § 12 (Line 722, 736) | **BLOCKER** | Claims the RTL skips the attribute fetch in HAM6 mode (`BitmapRowFetch.scala` skips `sFetchAttr` when `hamModeSync`). | **Branch/Code Discrepancy:** While this skip is implemented on the `brightforge/qspi-option-a-183` branch (in `ham-build-171` worktree), it is **absent** on the workspace branch `brightforge/ham-decoder-171` where `BitmapRowFetch.scala` still transitions unconditionally to `sFetchAttr` regardless of HAM mode. This means the SDRAM row/bank thrashing issue is still present on this branch. |

---

## 3. Verified Correct Sections (No Action Needed)
The following specifications were audited and found to be **100% accurate** against the current codebase:
*   **Planar Plane Count:** `PROJECT_PLAN/MODE0_SPEC.md` §7.4 and `VDP_PROGRAMMING_GUIDE.md` §13 correctly list `PLANE_COUNT = 5`, matching `VdpTop.scala:1229`.
*   **Write Pipeline Latency:** `VDP_PROGRAMMING_GUIDE.md` §10 (Line 653) and §12 (Line 792) correctly state `BITMAP_PIPELINE_LATENCY = 0` (zero-cycle write delay), matching `TopTang20kHdmi.scala` where `bitmapWritePipelineDelay` defaults to `0`.
*   **Power-On Reset Bases:** `VDP_PROGRAMMING_GUIDE.md` §10 (Line 623) correctly lists the default bases as `0x3000` (Bitmap) and `0x4000` (Attribute), matching `BitmapRowFetch.scala:36-37`.
*   **QSPI Register opcodes:** `README.md` §Host Interface correctly lists `0x01` = `REG_WRITE`, `0x02` = `SDRAM_WRITE`, `0x04` = `READ_STATUS`, matching `LegacySpiDecoder.scala:71-75` (`Op` object).

---

## 4. Consistency & Cross-Document Review (CoralReef)

| ID | Target Document / Section | Severity | Description | Evidence / Current Reality |
|---|---|---|---|---|
| **CORAL-01** | `PROJECT_PLAN/TASKS.md` §Live Lane State (Line 12–23) | **WARN** | Live lane ledger is stale and omits the current open lane. | Still lists **QSPI-SI-CEILING-183** as blocked on P4 console visibility (#13855) with next step "CyanPeak P4 console investigation". `STATUS.md` and mail #14150 show that lane has progressed to bitstream evaluation / vertical-flip discriminator, and **DOC-AUDIT-184** is the current open lane but is absent from `TASKS.md`. |
| **CORAL-02** | `PROJECT_PLAN/PROJECT_PLAN.md` §2.1 Proven on Hardware & §2.5 Hardware Validation Checklist (Lines 43, 120–125) | **WARN** | Host-transport proven-status is stale relative to current bench. | Lists P4 QSPI proven only @ 20 MHz and i80 as "Proven / Retiring". Recent mail #14150 shows the 40–80 MHz sweep is complete, the console blocker is resolved, and i80 is fully retired for new work. |
| **CORAL-03** | `PROJECT_PLAN/PROJECT_PLAN.md` §3.1 Active Work (Lines 160–163) | **WARN** | Roadmap active-work paragraph for QSPI-SI-CEILING-183 is stale. | Still describes the console-visibility blocker (#13855) and next step "CyanPeak P4 console investigation". Current lane state per #14150 is upload-vs-fetch arbitration root-cause + vertical-flip discriminator. |
| **CORAL-04** | `PROJECT_PLAN/PROJECT_PLAN.md` §5 Documentation Structure (Lines 223–253) | **BLOCKER** | Declared canonical paths are wrong or deleted. | Lists `docs/VDP_PROGRAMMING_GUIDE.md` and `docs/MODE0_REGISTER_BUS_SPEC.md`, but `VDP_PROGRAMMING_GUIDE.md` lives at repo root and `MODE0_REGISTER_BUS_SPEC.md` is deleted (`git status` shows `D PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md`). Also lists retiring `firmware/libvdp/vdp_i80.h / vdp_i80.c` without mentioning the active P4 QSPI proof app (`firmware/esp32p4_qspi_proof/`). |
| **CORAL-05** | `PROJECT_PLAN/GLOSSARY.md` overall | **WARN** | Missing canonical definitions for the current host transport. | No entries for **QSPI**, **LegacySpi**, **P4 host transport**, **word-drain frame**, **uploadBusy**, or **SDRAM_WRITE** bulk upload, despite these being central to `README.md`, `CHANGELOG.md`, and `STATUS.md`. |
| **CORAL-06** | `CHANGELOG.md` top entry 2026-07-11 (Lines 3–15) | **WARN** | Changelog top entry is out of date and contradicts current branch state. | Still calls the QSPI Option A pivot "IN-PROGRESS" and names `QspiSlaveSync` + `QspiTransportCore` as the active front-end, but the workspace branch `brightforge/ham-decoder-171` is renaming those modules to `LegacySpi*` (see CYAN-01). It also does not reflect the fetch-after-upload arbitration root cause established in #14150. |
| **CORAL-07** | Cross-document: `README.md` §Host Interface + `CHANGELOG.md` + `PROJECT_PLAN/PROJECT_PLAN.md` §5 | **BLOCKER** | Canonical host interface is described inconsistently across the three top-level documents. | `README.md` (Line 42) and `CHANGELOG.md` (Line 7) say the active front-end is `QspiSlaveSync` + `QspiTransportCore`; `PROJECT_PLAN.md` §5 points to `docs/MODE0_REGISTER_BUS_SPEC.md` and `firmware/libvdp/vdp_i80.h`; `STATUS.md` and bench mail treat the ESP32-P4 QSPI / word-drain path as canonical. A single source-of-truth document is missing. |
| **CORAL-08** | `PROJECT_PLAN/PROJECT_PLAN.md` §6 Team Roles (Lines 266–273) | **NOTE** | Simplified team-role table does not match the AGENTS.md canonical roster. | Lists generic "RTL Engineer / Firmware Engineer / Technical Writer / Project Lead" instead of the canonical agents `BrightForge`, `BronzeGate`, `CyanPeak`, `CoralReef`, and `TopazCliff`. |
| **CORAL-09** | `README.md` Roadmap link (Line 59) | **WARN** | Dead link to a deleted planning file. | References `PROJECT_PLAN/MODE0_PLANNING.md`, but that file is deleted from `PROJECT_PLAN/` (`git status` shows `D PROJECT_PLAN/MODE0_PLANNING.md`) and only exists in `PROJECT_PLAN/archive/MODE0_PLANNING.md`. |

### Verified Correct Sections (CoralReef Review)
* `PROJECT_PLAN/GLOSSARY.md` §Planar Fetch correctly states `PLANE_COUNT = 5` and cites `VdpTop.scala:1229` — consistent with CyanPeak's verified section.
* `PROJECT_PLAN/GLOSSARY.md` definitions of Mode0, Platform Adapter Mode, Linestate, Commit, Fetch Engine, Compositor, etc., are internally consistent with `PROJECT_PLAN/PROJECT_PLAN.md`.
* `CHANGELOG.md` earlier closed-lane entries and commit references sampled back to 2026-05 appear internally consistent (no duplicate or mis-ordered entries found).

*CyanPeak signed above findings; CoralReef additions are below.*

— CyanPeak
— CoralReef

---

## 5. Phase 3 Reconciliation (CoralReef)

Phase 3 was unblocked when BrightForge restored the still-active `QspiSlave.scala`+`QspiSlaveSim.scala` from the mistaken archive (commit `7893811`, mail #14199). The compile break on `TopTang20kHdmi.scala` is resolved and the repo-wide `Qspi`→`LegacySpi` rename is **de-scoped** (#14195).

### 5.1 Naming correction

The committed on-disk RTL on `brightforge/ham-decoder-171` contains **only** the following QSPI front-end modules in `hw/spinal/spinalhdlvdp/`:

| Module | File |
|---|---|
| `QspiSlave` | `QspiSlave.scala` |
| `QspiDecoder` | `QspiDecoder.scala` |
| `QspiSdramBridge` | `QspiSdramBridge.scala` |

No `LegacySpiSlave`, `LegacySpiDecoder`, or `HostSdramBridge` files exist on this branch. Any doc or report text that asserted those names as active has been corrected to the actual `Qspi*` names above.

### 5.2 Doc edits applied

| Doc | Change |
|---|---|
| `README.md` § Host Interface | Now states QSPI/ESP32-P4 as canonical; names active RTL `QspiSlave`/`QspiDecoder`/`QspiSdramBridge`; i80/ESP32-S3 retired to historical reference; legacy SPI retained for Pico 2/old ESP benches. |
| `README.md` § Repository layout | Lists `firmware/esp32p4_qspi_proof/` as canonical and `firmware/esp32s3_i80_*` / `esp32s3_rgb565_fullframe/` as historical. |
| `PROJECT_PLAN/PROJECT_PLAN.md` | Host-interface statement updated to QSPI/ESP32-P4. |
| `CHANGELOG.md` | New 2026-07-19 entry records QSPI front-end restoration and DOC-AUDIT-184 Phase 3. |
| `VDP_PROGRAMMING_GUIDE.md` §1 / §11 | Initialization and host-interface notes now describe QSPI as primary, i80 as retired; `READ_STATUS` note corrected to reflect QSPI implementation. |
| `PROJECT_PLAN/GLOSSARY.md` | `QSPI` entry updated to current modules and ESP32-P4 canonical status. |
| `PROJECT_PLAN/STATUS.md` | DOC-AUDIT-184 row updated: compile-fix blocker cleared, Phase 3 running. |

### 5.3 Items intentionally not edited in this phase

*   `firmware/esp32p4_qspi_proof/README.md` still describes a **word-drain** front-end (`QspiSlaveSync`/`QspiTransportCore`) that does not exist on this branch. Correcting it requires either a firmware-port lane to match the restored oversampled `QspiSlave`+`QspiDecoder` top, or a PM decision to re-introduce the word-drain modules. Left for the active QSPI-SI-CEILING-183 lane.
*   `firmware/GOTCHAS.md` §GOTCHA-033 still refers to `LegacySpiSlave.scala`; the file is actually `QspiSlave.scala`. This is a naming-only stale reference and is noted here for a future GOTCHAS sweep.
*   `firmware/libvdp/vdp_platform.h` contains uncommitted-symbol names (`LegacySpiSlave`) in comments only; no functional impact.

### 5.4 Phase 2 corrections preserved

TopazCliff’s §5.2 verification (BG-03 corroborated, BG-04 refuted) is preserved from the 2026-07-19 STATUS.md update. BronzeGate’s corrected findings (BG-01, BG-04) are reflected in the Phase 1/2 matrix above.

---

*Phase 3 reconciliation applied by CoralReef and verified by CyanPeak.*

— CoralReef
— CyanPeak
