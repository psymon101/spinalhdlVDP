# standalone-diagnostic-build

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** OPEN  
**Opened:** 2026-07-29  
**Trigger:** External static review Phase 1; owner request to close the remaining reviewer-recommended work.

---

## Objective

Produce a native 640×480 Tang Nano 20K bitstream that boots from cold power with **no host interaction**, **no QSPI traffic**, and **no SDRAM upload**, and displays a deterministic test pattern at **1× scale**.

This validates the on-chip rendering/HDMI path in isolation from host transport and SDRAM.

---

## Background

All external-review sub-lanes tracked in `STATUS.md` are now closed **except** the standalone diagnostic build recommended by the external static review (`kb/reviews/external_static_review_2026-07-25.md`, Phase 1). The reviewer explicitly recommended this build before relying on SDRAM/host-init paths.

Current state:
- `TopTang20kHdmi.useHostInit` is hard-coded `true`.
- Layer 0 is wired to SDRAM (`layer0UseSdram := True`).
- Layer 0 test-pattern override is disabled.
- A 720p proof top (`Hdmi720pMode0ProofTop`) demonstrates the test-pattern path, but it uses a 720p shell, not native 640×480 timing.

---

## Scope

- Add a diagnostic build target using **Option A** from the approved plan: parameterize `TopTang20kHdmi` with `diagnosticMode: Boolean = false`.
- When `diagnosticMode = true`:
  - Force the bootstrap FSM to run (`useHostInit = false`).
  - Force Layer 0 source to the on-chip test pattern:
    - `layer0UseSdram := False`
    - `layer0TestPatternEnable := True`
    - pattern select = grid (`6`) unless BrightForge prefers red field (`1`).
  - Keep scale at 1× and auto-center off.
  - Bootstrap `LAYER_ENABLE` to `0x0001` (L0 only).
- Reuse the proven native 640×480 PLL, reset sequencing, and `tang20k_hdmi.cst` pinout.
- Add `TopTang20kHdmiDiagnosticVerilog` generator and a `diagnostic` Makefile target/TCL producing `hw/gen/top_tang20k_diagnostic.v`.
- Generate Verilog, run Gowin PnR, and produce a bitstream.
- Optional but encouraged: a lightweight SpinalSim smoke test verifying `bootDoneR` and `LAYER_ENABLE` reach expected values.
- Hardware proof: N≥10 cold POR cycles; HDMI locks every time; pattern is stable.
- Proof packet under `PROJECT_PLAN/proof_packets/standalone-diagnostic-build/`.

## Out of Scope

- Do **not** change the default production `TopTang20kHdmiVerilog` output or behavior.
- Do **not** modify SDRAM controller, QSPI slave, or scaler logic.
- Do **not** implement the `BasicPatternSource` synchronous pipeline (remains the deferred `external-review-tile-pipeline` lane).

---

## Acceptance Criteria

- [ ] `sbt compile` passes with no errors.
- [ ] Diagnostic Verilog generation (`make gen-diagnostic` or equivalent) passes cleanly.
- [ ] Gowin PnR passes with TNS=0 on all clocks and no new resource alarms.
- [ ] Bitstream is produced; SHA-256 recorded in proof packet.
- [ ] Hardware proof: ≥10 cold power cycles; HDMI locks every cycle; test pattern is stable and matches expectation.
- [ ] Proof packet contains `manifest.yaml`, `hashes.sha256`, `PASS.txt`, `review.md`, synthesis summary, and capture hashes.
- [ ] Production regression spot-check: default `TopTang20kHdmiVerilog` still elaborates and PnRs cleanly, or BrightForge demonstrates no production-path diff.

---

## Blockers

None.

---

## Artifacts / References

- Approved plan: `/home/itadmin/.agent-homes/topazcliff/home/.kimi-code/sessions/wd_github_bb88525e79a2/session_56f35323-7a4c-479b-8964-e07e5e796390/agents/main/plans/ragman-monet-green-lantern.md`
- External static review Phase 1: `kb/reviews/external_static_review_2026-07-25.md`
- Existing 720p test-pattern proof top: `hw/spinal/spinalhdlvdp/Hdmi720pMode0ProofTop.scala`
- Production top: `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- Test pattern source: `hw/spinal/spinalhdlvdp/TestPatternSource.scala`
- On-chip tile source: `hw/spinal/spinalhdlvdp/BasicPatternSource.scala`
- Build scripts: `fpga/tang20k/Makefile`, `fpga/tang20k/build.tcl`
