# TASKS.md

**Updated:** 2026-06-19 (NATIVE-640-BITMAP-148 IN-PROGRESS; BSRAM-L1-GATE-154 IMPLEMENTATION DONE / AWAITING AUDIT; SPRITE-BSRAM-PROBE-153 DONE (0 BSRAM from descCount 32→16); RTL-BSRAM-OPTIMIZATION-149 DONE; CAPTURE-CHAIN-VALIDATION-147 DONE (capture-chain limitation); SDRAM-BANDWIDTH-146 DONE (RTL side exonerated); I80-FRAME-ATOMIC-SWAP-145 DONE; HARDWARE-BASICS-144 DONE; HOST-AFFINE-TEXTURE-143 PAUSED; RTL-EFFICIENCY-142 DONE; PROJECT-AUDIT-141 DONE; QSPI-DEPRECATE-139, SIM-TEST-DEBT-138, SIM-TEST-FOLLOWUP-140 DONE; `main` clean baseline established; 2bpp planar / tile-row-stride sub-lanes PARKED.)
**Purpose:** Authoritative active task ledger for `spinalhdlVDP`. Optimized for fast operational reading. Deep historical detail is in `TASKS_HISTORY.md`.

Status values: `TODO`, `IN-PROGRESS`, `DEFERRED`, `DONE`

---

## How to Use This File

- Active state lives in **Live Lane State** and **Next Up / Open Queue**.
- Closed-lane history, extended proof narratives, and phase-by-phase task detail are in `TASKS_HISTORY.md`.
- Do not begin a task if any entry in its `depends_on` list is not `DONE`.
- A task is only `DONE` when its `validation` criteria are met on hardware (or simulation, for tasks not yet at hardware stage).

---

## Live Lane State

This section tracks the active lane.

| Field | Value |
|-------|-------|
| **Task** | Native 640×480 1:1 bitmap display path |
| **Status** | **IN-PROGRESS** |
| **Task ID** | NATIVE-640-BITMAP-148 |
| **Owner** | BrightForge (RTL/sim/synth) / BronzeGate (firmware/HW) / CyanPeak (audit) / CoralReef (docs) / TopazCliff (PM) |
| **Baseline Commit** | `main @ 252cba4` |
| **Latest Auth Mail** | TopazCliff #12859 (lane open: NATIVE-640-BITMAP-148) |
| **Summary** | Implement a native 640×480 1:1 bitmap path. Current RTL hardwires bitmap fetch to 320 source pixels stretched 2× to 640 (col/2 for direct-color, col/8 for indexed 2bpp). This lane makes compositor reads width-aware, fetches 640 source pixels/row, and grows/banks the line buffer. Scaler is repurposed for upscaling sub-native content. Indexed 2bpp native is the initial target; RGB565 native is gated by bandwidth/BSRAM feasibility. RTL-BSRAM-OPTIMIZATION-149 is now DONE (40/46 BSRAM used, 6 free), so 148's BSRAM trial-synth can proceed on that baseline. |
| **Checkpoints** | A: bandwidth co-sim now; BSRAM trial-synth post-149 (BrightForge). B: width-aware compositor + 640 fetch + buffer (BrightForge). C: sim + synth/PnR ≤46/46 (BrightForge). D: firmware + hardware proof (BronzeGate). E: docs + audit (CoralReef/CyanPeak). |
| **Next Step** | BrightForge runs native-640 bandwidth co-sim (indexed 2bpp + RGB565); BSRAM gate waits for post-149 baseline. |

**Security note:** Messages #10794 and #10795 were sent via the `overseer/send` HTTP endpoint, which stamps `from: HumanOverseer` and injects a `HUMAN OVERSEER` header. BrightForge correctly flagged these as non-canonical (#10796). CyanPeak correctly retracted acknowledgement (#10797). Corrected authorizations sent as #10799 (BrightForge) and #10800 (CyanPeak) via proper agent `send_message` MCP tool. **Rule:** Agent-to-agent mail must use `send_message` MCP tool only. The `overseer/send` endpoint is for human operator injection only and must not be used for PM authorizations.

---

## Next Up / Open Queue

### Priority 0 — RGB565 Full-Frame Docs and Canonical Example
- **Status:** **DONE**
- **Owner:** CoralReef (docs) / BronzeGate (example)
- **Scope:** Finish `VDP_PROGRAMMING_GUIDE.md` RGB565 section, sweep docs/examples for stale data, CyanPeak doc audit.
- **Depends on:** RGB565-FULLFRAME-132 DONE
- **Validation:** Docs consistent with `mode0_regs.json`; canonical example compiles; CyanPeak audit PASS (#12437); branch merged to `main` @ `c98ec03`.

### Priority 0b — Full Doc-to-Code Audit (FULL-DOC-AUDIT-151)
- **Status:** **IN-PROGRESS / REVIEW**
- **Owner:** CoralReef (consistency) / CyanPeak (code-to-spec) / BronzeGate (firmware helper validation) / BrightForge (RTL escalations)
- **Scope:** PM-activated consistency sweep across `VDP_PROGRAMMING_GUIDE.md`, `MODE0_REGISTER_BUS_SPEC.md`, `TECH_SPEC_HOST_INTERFACE_AND_COPPER.md`, libvdp helper headers, examples, and `mode0_regs.json`.
- **Depends on:** None
- **Validation:** CyanPeak code-to-spec PASS (#12890); BronzeGate helper review PASS (#12891); BrightForge accepted RTL escalations into follow-up lane I80-STATUS-DECODE-152 (#12897 / TopazCliff #12900); CoralReef consistency review pending.
- **Checkpoints:**
  - **A:** DONE (#12886/#12887) — 15 findings triaged; doc-only findings fixed; helper-level findings fixed in firmware; two RTL items escalated.
  - **B:** IN-PROGRESS — CyanPeak PASS (#12890), BronzeGate PASS (#12891), BrightForge escalation acceptance DONE (#12897 → #12900), CoralReef consistency review pending.
  - **C:** Escalated to **I80-STATUS-DECODE-152** after NATIVE-640-BITMAP-148 CP-A — implement i80 `READ_STATUS` opcode `0x04` and `0x0323` upload-status-clear decode.
- **Latest Auth Mail:** TopazCliff #12900 (escalation sequencing to I80-STATUS-DECODE-152)

### Priority 0c — i80 Status Decode and Upload-Clear (I80-STATUS-DECODE-152)
- **Status:** **QUEUED / WAITING**
- **Owner:** BrightForge (RTL) / CyanPeak (code-to-spec) / CoralReef (doc consistency) / BronzeGate (HW validation)
- **Scope:** Implement the two RTL escalations from FULL-DOC-AUDIT-151: (1) i80 opcode `0x04` `READ_STATUS` response path in `I80HostInterface.scala`, and (2) register `0x0323` `UPLOAD_STATUS_CLEAR` decode + clear strobes to `QspiSdramBridge` for both QSPI and i80.
- **Depends on:** NATIVE-640-BITMAP-148 CP-A complete; FULL-DOC-AUDIT-151 CoralReef consistency PASS
- **Validation:** CyanPeak code-to-spec PASS; BronzeGate validates `vdp_read_status(0)` and `vdp_clear_upload_status()` on i80 hardware; CoralReef removes limitation notes from docs in the same commit.
- **Checkpoints:**
  - **A:** (pending) — BrightForge reconciles with PA-2 `3647f2e`/`b3880f2` to avoid double-decode, then implements.
  - **B:** (pending) — Sim + synth PASS; CyanPeak + CoralReef review.
  - **C:** (pending) — BronzeGate HW validation; docs limitation notes removed.
- **Latest Auth Mail:** TopazCliff #12900 (lane queued after NATIVE-640 CP-A)

### Priority 0d — Gate Dead L1 Fetch Engine (BSRAM-L1-GATE-154)
- **Status:** **IN-PROGRESS / IMPLEMENTATION DONE — AWAITING COMBINED BUILD + AUDIT**
- **Owner:** BrightForge (RTL/sim/synth) / CyanPeak (code-to-spec)
- **Scope:** `TopTang20kHdmi.scala` passes `enableL1Fetch = false` to `VdpTop`, but still unconditionally instantiates the L1 SDRAM fetch engine (`fetchL1` at line 710). The production build hardwires `video.io.layer1UseSdram := False`, so the engine is logically dead yet synthesizes. Wrap the `fetchL1` instantiation in `if (enableL1Fetch)` and tie off all downstream layer1 SDRAM signals / arbiter client 3 when disabled.
- **Depends on:** None; can proceed in parallel with NATIVE-640-BITMAP-148.
- **Validation:** Commit `45da013` on branch `brightforge/bsram-l1-gate-154`. Standalone build vs `main@a0e279a`: BSRAM 42/46 → **40/46** (−2 blocks); LUT −174; FF −95; TNS=0. The actual post-merge delta on current `main@252cba4` (which already includes RTL-BSRAM-149 R3) is TBD — BrightForge running combined build.
- **Checkpoints:**
  - **A:** DONE — compile-time gate + tie-offs implemented; elaboration + sim smoke PASS.
  - **B:** DONE — standalone PnR shows −2 BSRAM; combined 149+154 build requested for real post-merge count.
  - **C:** IN-PROGRESS — CyanPeak code-to-spec audit; PM merge authorization after combined-build report + audit PASS.
- **Latest Auth Mail:** TopazCliff #12913 (combined build + audit gate)

### Priority 1 — libvdp Scaler Register Exposure
- **Status:** **DONE**
- **Owner:** BronzeGate (firmware), BrightForge (hardware sanity test)
- **Scope:** Wire `SCALE_CTRL`, `LOGIC_WIDTH`, `LOGIC_HEIGHT` into libvdp register map + host API. `BORDER_CTRL` already exposed.
- **Depends on:** #10590 (scaler RTL proven)
- **Validation:** Host can successfully change scaler mode from firmware; hardware proof shows bezel response to register writes.
- **Checkpoints:**
  - **A:** DONE (`c1f87fd`) — register constants + packer/writer helpers + compile check
  - **B:** DONE (`d900182`) — ESP32/ESP32-S3/ESP8266 runtime bezel sketch, 3500ms mode cycles
  - **C:** DONE (#10762) — BrightForge hardware sanity test: runtime-write path bezel verification + canary stability gate. Runtime-write parity proven byte-identical to POR-init.
- **Closeout:** DONE — scenario cleanup merged (`fe8d6f3`), CyanPeak doc audit PASS (#10770), BronzeGate housekeeping landed (`ec838da`).

### Priority 2 — readAsync Mems Audit
- **Status:** **DONE**
- **Owner:** BrightForge
- **Scope:** Audit remaining `readAsync` memory usages across the design for safety / timing closure.
- **Depends on:** None
- **Validation:** All `readAsync` instances either justified with comment or converted to `readSync`.
- **Checkpoints:**
  - **A:** DONE (#10772) — 21 instances across 13 files, classified into 4 risk classes
  - **A-revised:** DONE (#10778) — CP-A reclassified. All targets have same-cycle FSM semantics.
  - **B(1):** DONE (`72adf70` → `dbb4c08`) — Uniform audit comments on all 21 instances. Merged to main.
  - **B(2):** DONE (`d0b645e` → `17b1c2c`) — Demonstration conversion: `SdramTileFetch tileMapRom` readAsync→readSync with lookahead-address FSM. Sim byte-identical to baseline. Pattern documented as GOTCHA-14.
  - **C:** DONE — Synthesis clean, 0 violations. Converted Mem uses `ram_style="block"`.
- **Closeout:** DONE — CyanPeak doc audit PASS (#10786).

### Priority 3 — Planar Hardening Task 3
- **Status:** **DONE**
- **Owner:** BrightForge
- **Scope:** Defensive checks on planar read path.
- **Depends on:** None
- **Validation:** Simulation proof of planar path under stress conditions.
- **Checkpoints:**
  - **A:** DONE (#10790) — 8 entry points, boundary surfaces mapped, 6 risks classified, zero runtime asserts found
  - **B(1):** DONE (`4ff92d5`) — Runtime `assert(...)` at all 6 risk sites. 80 lines, 2 files, zero new diagnostics. All planar regression sims PASS.
  - **B(2):** DONE (`50fcced`, `aa4b1d0`, `89ec7e9`) — 3 targeted sim discriminators. Boundary + Refresh PASS (asserts silent). WriteBufRace ASSERT TRIPS ~30× under pathological stress → CP-B(3) GO.
  - **B(3):** DONE (`4123604`) — Option α latch-and-flip implemented, scheduler gap analysis (858 cycles min, ~30 cycles emit-clear, 28.6× safety ratio), full regression PASS. Merged to main @ `1efa9c1`.
  - **C:** DONE — CyanPeak doc audit PASS #10814, fixes merged `60a6f03`.
- **Closeout:** DONE — lane-closeout team check sent #10815, awaiting replies before P4.

### Priority 4 — BSRAM Reclamation (BSRAM-RECLAIM-137)
- **Status:** **ABANDONED** (Phase 1 attempted; no safe reclaim)
- **Owner:** BrightForge (RTL) / CyanPeak (review) / CoralReef (docs if semantics change)
- **Scope:** Phase 1 attempted: `byteFifo` 256→32 reclaims 0 BSRAM; `NBanks` 3→2 breaks rendering (2560 mismatches). The 3-bank timing is load-bearing. Phase 1 will not be merged. **Phase 2 sprite descriptor/FIFO reductions are paused by owner directive.** Phase 3 structural consolidation remains parked.
- **Depends on:** VDP-SOFT-RESET-135 Stage 3 sim PASS
- **Validation:** N/A — Phase 1 abandoned per BrightForge #12648 / TopazCliff #12649.

### Priority 4b — RTL BSRAM Structural Optimization (RTL-BSRAM-OPTIMIZATION-149)
- **Status:** **DONE**
- **Owner:** BrightForge (RTL/sim/synth) / CyanPeak (audit) / BronzeGate (HW regression) / CoralReef (docs)
- **Scope:** Refactor 3 (fold double buffers in `LineBuffer`/`SdramTileFetch`/`SdramTileAttributeFetch`) landed at `0eb10e7`. Refactor 1 (`BitplaneRowFetch` flatten) and Refactor 2 (`SpriteEvaluator` matrix pack) were both skipped: synth/netlist inspection showed their targets map to distributed SSRAM/LUTRAM, not BSRAM, so packing/flattening yields 0 BSRAM reclaim and risks consuming blocks or adding complexity.
- **Depends on:** I80-FRAME-ATOMIC-SWAP-145 DONE; SDRAM-BANDWIDTH-146 RTL side CLOSED
- **Validation:** Commit `0eb10e7`; BSRAM 42/46 → **40/46** (−2 blocks); LUT −114; worst setup slack +4.810 ns; hold slack +0.074 ns; TNS=0. Targeted sims PASS (`RGB565FullFrameSim`, `PlanarWriteBufRaceSim`, `TileAttributeFetchSim`, etc.); 2 pre-existing flaky/failing sims unchanged on baseline. CyanPeak logical-equivalence + synth-report audit PASS (#12898). No HARDWARE-BASICS-144 regression required (structural no-functional-change refactor).
- **Latest Auth Mail:** TopazCliff #12876 (lane closeout)

### Priority 5a — Sim Test Debt Cleanup (SIM-TEST-DEBT-138)
- **Status:** **DONE** (commit authorized #12715)
- **Owner:** BrightForge
- **Scope:** Fix three pre-existing RED simulations that fail identically on `main` (`d41dea8`): `PlanarClipSim` (sample skew), `SpriteEvaluatorSim` (undriven `softClear`), `SpriteCapacitySim` (stale wait time). These are test-bench/test-debt issues, not RTL regressions.
- **Depends on:** VDP-SOFT-RESET-135 DONE
- **Validation:** All three sims PASS on `main`; 0 new REDs in 107-sim matrix (#12714); `sbt compile` clean.
- **Opened by:** BrightForge #12668

### Priority 5b — Sim Test De-flake Follow-up (SIM-TEST-FOLLOWUP-140)
- **Status:** **DONE** (commit `9f9b512`, matrix 96/11, 0 new REDs)
- **Owner:** BrightForge
- **Scope:** Apply the same `softClear`/`softClearAddr` tie-off to `SpriteHighPatIdxBusSim` and `Task55SpriteMaskingSim` so the full matrix loses two more flaky fails.
- **Depends on:** SIM-TEST-DEBT-138 DONE
- **Validation:** Both sims PASS deterministically across 3+ seeds; full matrix re-run shows no new REDs.
- **Opened by:** TopazCliff #12716

### Sub-lane: 2bpp Planar FPGA Hardware Proof
- **Status:** **PARKED**
- **Owner:** BrightForge
- **Opened by:** TopazCliff #10851 (ACK sent, plan replied #10853-sub)
- **Scope:** Generate 2bpp planar test asset, flash to Tang Nano 20K, capture visual proof.
- **Validation:** Visual output matches source pattern; any mirroring/color-swap/stride-corruption = FAIL.
- **Park reason:** PM scope clarification — this is a legacy tile-decode verification, not a bitplane blocker. Unpark when the asset generator supports 2bpp-planar layout and a platform adapter demo needs it.

### Action: Tile-Row Stride Verification (non-4bpp)
- **Status:** **PARKED**
- **Owner:** BrightForge
- **Opened by:** TopazCliff #10826 (ACK sent, analysis replied #10853-sub)
- **Scope:** Confirm hardware fetch behavior for 1bpp and 2bpp tiles with fixed 8-byte row stride.
- **Validation:** RTL analysis complete; hardware proof pending PM decision on priority.
- **Park reason:** PM scope clarification — BrightForge analysis (#10826) shows HW stride is safe; the real gap is asset-generator padding/encoding. Unpark when a low-bpp tile adapter demo is scheduled.

### Integer Pixel-Repetition Scaler + Auto-Center Borders
- **Status:** **DONE** (#10590).
- **Scope:** Add integer pixel-repetition scaling to VdpTop output path + libvdp resolution APIs.
- **Closeout:** Merged to main @ `d69d404`. Hardware proof v3 validated by BrightForge #10731, merged by TopazCliff #10732.

### Priority 6 — Host-Loadable Affine Texture
- **Status:** **PAUSED** (HOST-AFFINE-TEXTURE-143) — pending solid HARDWARE-BASICS-144 baseline
- **Owner:** BrightForge (RTL), BronzeGate (libvdp + test), CoralReef (docs), CyanPeak (audit)
- **Scope:** Remove the fixed `affineTexture` ROM in `VdpTop` and replace it with a host-loadable memory path. The affine/Mode7 background texture must be uploadable by the user at runtime, not hardcoded in RTL. Reset implication: once host-loadable, `VDP_SOFT_RESET_REQUEST` must also clear this texture (the existing 16384-cycle sweep already fits it).
- **Depends on:** VDP-SOFT-RESET-135 Stage 4 complete (so the reset sweep contract is stable).
- **Validation:**
  - Simulation: host can upload a new texture and the affine layer displays it.
  - Hardware: uploaded texture visible on Tang Nano 20K output.
- **Checkpoints:**
  - A: RTL change — add write port to affine texture memory, host register interface, and reset-sweep inclusion.
  - B: Simulation proof.
  - C: libvdp helper and hardware proof.
  - D: Docs + audit.

### RGB565 Hardware Bench Framing Investigation
- **Status:** **DEFERRED** (#10503 follow-up). 
- **Scope:** Superseded by ESP32-S3 host bring-up and 1-pixel fix.

### Atari ST Adapter Lane
- **Status:** **PAUSED** (#9783).
- **Scope:** Relocating to libvdp.

---

## Closed Summary

Recently closed lanes. Full detail in `TASKS_HISTORY.md`.

| Task | Status | Reference |
|---|---|---|
| HARDWARE-BASICS-144 — hardware smoke-test baseline on Tang Nano 20K; tests 01–08 flashed and proven, test07 tear moved to I80-FRAME-ATOMIC-SWAP-145 | **DONE** | BronzeGate proofs #12742–#12750/#12752; BronzeGate blocker request #12753 |
| I80-FRAME-ATOMIC-SWAP-145 — vblank-atomic bitmap/attr base swap + 0x035C i80 readback; merged to main | **DONE** | BrightForge RTL #12766/#12782; BronzeGate firmware proof #12783/#12784; residual issue moved to SDRAM-BANDWIDTH-146 |
| SDRAM-BANDWIDTH-146 — RTL display path exonerated under concurrent full-frame RGB565 upload; rate cap not merged; residual reclassified as capture-chain artifact | **DONE** | BrightForge CP-A.2 co-sim #12807; BronzeGate HW cross-check #12805; moved to CAPTURE-CHAIN-VALIDATION-147 |
| CAPTURE-CHAIN-VALIDATION-147 — residual test07 artifact classified as RTSP/MJPEG/upscale capture-chain limitation, not RTL defect | **DONE** | CyanPeak #12812, CoralReef #12813, BronzeGate row-ID-bar proof #12823 |
| RTL-BSRAM-OPTIMIZATION-149 — structural Mem refactor; R3 double-buffer fold landed (−2 BSRAM), R1/R2 skipped as targets map to SSRAM/LUTRAM | **DONE** | BrightForge commit `0eb10e7`; TopazCliff closeout #12876 |
| SPRITE-BSRAM-PROBE-153 — descCount 32→16 saves 0 BSRAM; `activeListMem` (4 BSRAM) scales with `visiblePerLine`, not descCount | **DONE** | BrightForge probe #12909/#12910; PM decision: do not trade sprite capacity for BSRAM |
| RTL-EFFICIENCY-142 — triage and verify `vdp_efficiency_report.md` recommendations; report debunked, no code changes | **DONE** | BrightForge verification #12735/#12736; dangerous `evalStart` suggestion rejected |
| PROJECT-AUDIT-141 — project-wide audit + main-branch consolidation; closed QSPI-DEPRECATE-139, SIM-TEST-DEBT-138, SIM-TEST-FOLLOWUP-140; committed clean baseline to `main` | **DONE** | commits `ed12ece`, `1fe2b61`, `f04960b`, `7e8fd2f`; audits PASS #12721/#12727/#12728/#12730 |
| WHOLE-VDP-134 — whole-system i80/ESP32-S3 regression baseline before BSRAM optimization (scenarios #1–#5) | **DONE** | scenario #5 PASS on raw-i80 + libvdp-helper paths; copper docs merged `36adcbc`; closeout #12543 |
| VDP-SOFT-RESET-135 — host-triggered soft reset via `VDP_CTRL[2]`, all 4 stages + register #3/#4 | **DONE** | combined bitstream `c55e944`; BronzeGate HW smoke PASS #12667; CyanPeak audit PASS #12655; docs merged `d41dea8` |
| RGB565-FULLFRAME-132 — full-frame RGB565 direct-color burst-read controller, sim + STA + HW proof | **DONE** | merge `c8129bd`, formal fix `d668e01`, closeout #12378 |
| P23 Timing-Margin Recovery — burst-refresh re-enabled + place/route effort=2 (clk_pixel +5.709 ns, TNS 0) | **DONE** | #12107/#12114/#12115 |
| P22 i80 Block-Write + SDRAM Upload — byte-exact HW proof through libvdp | **DONE** | #12072/#12084 |
| P21 i80 Parallel Host Interface — full HW proof (continuity + transport + visible BORDER_CTRL) | **DONE** | #12010/#12071 |
| Scanline-start 1-pixel Transient Fix | **DONE** | #10550 |
| ESP32-S3 Host Bring-up | **DONE** | #10539 |
| CLS Optimization — gate2 readAsync→readSync | **DONE** | #10497 |
| R5.4 Copper Integration (gate2 descCount=32) | **DONE** | #10398 |
| 3b Copper Double-Buffer | **DONE** | #10270 |
| Mode2optimized Feature Strip | **DONE** | #10142 |
| Task 10026 — Simple Sprite | **DONE** | #10117 |
| Host Platform Fidelity | **DONE** | #9891 |
| QSPI Transport Fix | **DONE** | #9875 |
| Reference Localization | **DONE** | #9839 |
| ZX Spectrum Host Flow | **DONE** | #9797 |
| 320-pixel planar clipping | **DONE** | #9768 |
| Task 56 — SDRAM Multi-Layer | **DONE** | #9709 |
| Task 54 — Sprite-Sprite Collision | **DONE** | #9672 |
| Task 57 — DFF Optimization | **DONE** | #9605 |
| RTL Platform-Agnosticism Purge | **DONE** | #10567 |
| RGB565 Directcolor (CP-1a..1c + BitmapCtrlCommitSim) | **DONE** | #10503 |

Older closed tasks (Phase 1–8, R-Roadmap, sidecar lanes) are catalogued in `TASKS_HISTORY.md`.

---

## Closed Side-Lanes

Parallel work completed outside the FPGA critical path.

| Task | Status | Owner | Audit | Closeout Mail | Commit |
|---|---|---|---|---|---|
| libvdp Mode0 Helper-Surface Completion — pattern-RAM, VSCROLL, HDMA, bitmap base/stride, standalone control helpers | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10273 | `6830b55`, `9f6b86f`, `29be453` |
| libvdp All-in-One Sprite Upload Helper | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10296 | `c9e6702` |
| libvdp Per-Platform Palette LUT Helpers — TMS9918, SMS/GG, Atari ST/STE | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10305/#10306 | `45f0d88` |
| Docs Cleanup — concision, consistency, visual-fidelity policy sync | **DONE** | CyanPeak | CoralReef verified | BronzeGate #10303 | `b10ab71`, `1b7449c`, `3f108fd` |
| Firmware Platform Parity — ESP32 Scenario Coverage | **DONE** | FoggyWolf | CyanPeak PASS #9727 | #9727 | \`e7c8a06\` |

---

## Historical Artifact Index

| Category | Canonical Doc | Archive Location |
|---|---|---|
| Closed task detail | `TASKS_HISTORY.md` | `archive/tasks/` (per-task artifacts) |
| Platform adapter specs | `PLATFORM_ADAPTERS.md` | `archive/adapters/` (full specs) |
| Substrate assessments | `ASSESSMENT.md` | `archive/assessments/` (source files) |
| Gap task list (v1.0) | `TASKS.md` §Next Up | `archive/tasks/MODE0_GAP_TASKLIST_v1.0.md` |

---

## Agent Rules for This File

| Rule | Requirement |
|------|-------------|
| Dependency gate | Do not begin if any `depends_on` is not `DONE` |
| Scope boundary | Do not implement anything in `scope_boundary` as excluded |
| DONE definition | `DONE` only when `validation` criteria met on hardware (or sim) |
| Status sync | Update status field when marking `IN-PROGRESS` or `DONE` |
| Scope immutability | Do not modify `depends_on` or `scope_boundary` without instruction |

### Deduplication Rule
Before opening a new lane, landing code, or sending a proof packet, verify the task ID does not already appear in `TASKS.md` Live Lane State or project mail from the last 48 hours. If already in flight, halt and request a BronzeGate ruling.

### Audit HOLD Iteration Limit
If CyanPeak issues `HOLD`, BrightForge may correct and resubmit once. A second `HOLD` on the same scope must escalate to BronzeGate. No third HOLD cycle without PM intervention.

### Doc-Audit Rule (PM-activated)
CyanPeak is activated for documentation review after each priority completion. See `AGENTS/CyanPeak.md` §Doc-Audit Protocol. CyanPeak must review and sign off on docs before the next priority opens.

---

## Lane-Closeout Team Check (Mandatory)

At every large-task closeout, `TopazCliff` must send a PM team check to all active agents before opening the next large lane.

Purpose: surface blockers, process friction, and capacity constraints while they are fresh, rather than letting them accumulate across lanes.

Required reply content from each agent:
1. **Blockers** — tooling, docs, coordination, handoffs, stale dependencies, unclear scope
2. **Process friction** — mail patterns, lane handoffs, proof requirements, review cycles
3. **Suggestions** — start/stop/change at large-task boundaries
4. **Status** — current lane/focus and capacity for next PM-assigned task

No new large lane may be opened until the team check has been sent and replies have been reviewed. Small side lanes (doc fixes, sketch tweaks, sim-only cleanup) are exempt.

**Override:** Project owner may direct immediate lane opening via direct instruction. TopazCliff records the override and proceeds.

---

## Lane-Open Packet Template

Every new implementation lane must open with one authoritative packet. Copy this template into the kick-off mail or doc update.

For full pre-execution planning (primitive boundary, interfaces, data model, timing, risks, exit condition), use `TASK_TEMPLATE.md`.

```markdown
## Lane Open: [Task Name]

### Prior Art Search
- [ ] Searched `TASKS_HISTORY.md` for same symptom / module / signal
- [ ] Searched `PROJECT_PLAN/archive/artifacts/` for related closed investigations
- [ ] Searched `firmware/GOTCHAS.md` for known pitfalls matching the symptom
- [ ] Searched `memory` MCP for reusable findings
- [ ] Prior art found (reference): ... / No prior art found

### Scope Boundary
- in scope: ...
- in scope: ...
- out of scope: ...
- out of scope: ...

### Required Proof
- sim: ...
- hardware: ...
- synthesis/PnR: ... (mandatory per `CONVENTIONS.md` §Synthesis and PnR Proof Rule if ≥500 lines or top-level connectivity changed)

### PROJECT-AUDIT-141 Sim Debt Baseline

Classified by BrightForge #12730 (run 3× on `main @ 9f9b512`):

| Sim | 3× result | Class | Root cause / debt |
|---|---|---|---|
| `AffineVdpTopSim` | F F F | deterministic | affine render idx mismatch (affine-path/test mismatch) |
| `BitmapRowFetchSim` | F F F | deterministic | bootDone timeout — SDRAM init vs short wait; known debt |
| `BlitterEngineSim` | F P F | flaky | uninitialized/random read — de-flake candidate |
| `BurstRefreshPacingSim` | F F F | deterministic | SpinalError elaboration — param/API drift; quick fix candidate |
| `LinestateRobustnessSim` | F F F | deterministic | linestate/test mismatch |
| `RegBusConcurrencySim` | F F F | deterministic | write commit timing expectation |
| `RegBusStressSim` | timeout ×3 | hang/perf | stress sim hangs >300s |
| `ScrollTableSim` | F F F | deterministic | uninitialized ScrollTable read — de-flake candidate |
| `SpriteCollisionSim` | F F F | deterministic | SPRITE_0_HIT not set — collision/test mismatch |
| `TileAttributeFetchL1BaseSim` | F F F | deterministic | SpinalError elaboration — param/API drift; quick fix candidate |
| `VdpTopSim` | F F F | deterministic | top-band color mismatch — known long-broken |

**Branch decisions:**
- `debug/baseline-reverify` @ `685c412` — **delete** (stale, unclaimed).
- `experiment/i80-no-delays` @ `233cc0f` — **keep** (BronzeGate experiment).
- `sdram-controller-upgrade-test` @ `3715ac5` — **keep blocked** (out of scope until dedicated SDRAM lane).

### Audit Focus
- ...

### Checkpoints
- A: control/register contract
- B: simulation proof
- C: hardware proof

### Expected Next Deliverable
- [checkpoint name] by [owner]

### Coding Authorized
- YES / NO — [mail id]
```
