# Master reliability plan: CPU↔FPGA QSPI connection

**Owner:** TopazCliff (PM) — BrightForge (RTL/sim/diagnostics) + BronzeGate (firmware/self-healing/HW proof) + CyanPeak (spec review) + CoralReef (docs/runbooks)  
**Opened:** 2026-08-10  
**Status:** ACTIVE — external AI review **PASS WITH CONDITIONS**; conditions locked; BrightForge Step 1 implementation authorized  
**Scope:** Tang Nano 20K + ESP32-P4 QSPI host interface. i80 and legacy SPI are retired and must not be re-enabled without a new Rule-19-gated lane.

## Owner directive

> "I want every outcome and possibility thought out, both good and bad... we have been working on this for weeks off and on.. we need a solid solution, one that scales and is very reliable... and it also needs to be tested beyond what is needed to make sure it doesnt break/have issues."

This plan is the engineering response. It is **not a license to gold-plate**. It means every known failure mode is either (a) prevented by design, (b) detected and reported through health/status, or (c) accepted as a documented risk with a recovery path. "Over-tested" means the test matrix must explicitly cover corner cases, error injection, and long-run stress, not just the happy path.

## External AI review verdict and conditions

The external AI reviewer returned a **PASS WITH CONDITIONS** verdict for this master reliability plan and the `qspi-status-done-bit-fix` Option A approach. The following conditions are now locked engineering requirements and must be tracked to closure:

1. **POR-to-host boot sequencing (F13):** Add an FMEA row for FPGA power-on-reset-to-host-boot sequencing. The host must keep CS# high and enforce a settle delay before the first transaction after host boot, so the FPGA sees a clean CS#-high idle before the first SCLK edge.
2. **Host SPI driver timeout-reset recovery:** BronzeGate must implement low-level SPI driver reset/recovery in `firmware/libvdp/vdp_host_p4.c` so the host can recover from repeated transport timeouts without a full reboot.
3. **Gowin synthesis seed-variance tracking:** The over-test matrix must include explicit Gowin synthesis seed-variance runs and record TNS and resource deltas per seed.

BrightForge is authorized to start Step 1 of `qspi-status-done-bit-fix` (sticky `DONE` lifecycle) immediately. BronzeGate Rule 19 sign-off is still required before merge.

## Reliability attributes

| Attribute | Definition | How we prove it |
|---|---|---|
| **Observable** | Host can always read unambiguous `BUSY`/`DONE`/`ERROR`/`OVERFLOW` status. | Sticky bits with locked lifecycle; QSPI `sel=0x06` and i80 `0x0323` parity; sim reads after every state transition. |
| **Recoverable** | A detectable error does not require an FPGA reconfigure or host reboot by default. | Host clears sticky errors and retries; watchdog/timeout prevents infinite host waits; FPGA state is bounded. |
| **Self-healing / adjusting** | The system can degrade gracefully and resume after a transient fault. | Host retry policy; optional SCLK frequency fallback on repeated CRC/timeout; diagnostic selectors expose internal state. |
| **Silent-corruption-free** | Wrong SDRAM data or wrong register values are never accepted silently. | CRC on bulk upload, health flags, readback validation, no ambiguous status encodings. |
| **Bounded** | Every transaction completes or fails within a known time. | Timeout counters in firmware; FPGA FSMs have no unbounded loops; worst-case latency calculated and tested. |
| **Deterministic at the boundary** | The protocol is unambiguous across clock domains and reset domains. | Free-running-clock reset release; clean CS# semantics; one canonical status contract (ADR-009). |

## Known failure modes and outcomes

The following list must be treated as a living FMEA. Each row states the failure mode, the current evidence, the bad outcome if we ignore it, and the design/test response.

| ID | Failure mode | Evidence / hypothesis | Bad outcome if ignored | Response |
|---|---|---|---|---|
| F01 | `DONE` bit is a one-cycle pulse, not sticky | `QspiSdramBridge.donePulse`; CyanPeak #14670 | Host polls `DONE`, always reads `0`; upload completion is unobservable | **Fix in `qspi-status-done-bit-fix`:** sticky level, clear on next accepted upload (Option A). |
| F02 | First transaction mis-frames after FPGA config | Lane 1 diagnostic `0x22222222`; `sawCsHigh=1` (#14664) | Host reads garbage magic/status until a second transaction recovers | **Investigate in `qspi-transport-reliability-hardening`:** corrected free-running-domain diagnostic to distinguish reset-domain, CS# SI, and read-launch mechanisms. |
| F03 | CS# signal-integrity / bounce at config boundary | Hypothesis in #14664 | Spurious resets or missed first bits | Evaluate CS# input synchronizer/deglitch; measure with diagnostic; consider series termination/SPI2 IOMUX fallback. |
| F04 | Read-data output/OSER launch glitch | Hypothesis in #14664 | Framing is correct but read data is wrong | Diagnostic must capture first-read data; evaluate launch timing and output-enable gating. |
| F05 | Silent SDRAM upload corruption (wrong value, no health flag) | Historical lower-row corruption (#14266 area) | Display/content corruption with `raw=0` health | CRC or per-burst checksum on upload; health flag for CRC fail; host retry. |
| F06 | Host back-to-back upload race | Option A lifecycle clears `DONE` on next upload | Host misses `DONE` between uploads | Document contract: poll `DONE` before next upload; add assertion/test for this window. |
| F07 | Slow host polling while `DONE` is transient | `DONE` only high between completion and next upload | Host never sees completion if it starts next upload immediately | Same as F06 — contract + test. |
| F08 | CDC/glitch on `uploadDone`/`uploadError` crossings | `BufferCC` used for status bits | Metastable or missed events | Stickies are set in pixel domain and sampled into status domains; prove set-before-clear in sim. |
| F09 | FPGA FSM deadlock under protocol violation | Unknown | Host hangs waiting for `BUSY=0` | Add watchdog/timeout in firmware; assert FSM coverage in sim. |
| F10 | Long-run thermal/voltage drift | None observed yet | Intermittent failures after minutes/hours | Long-run HW campaign (≥30 min, many transactions) with health checks. |
| F11 | Host and FPGA disagree on `0x0323` W1C mask | ADR-009 vs old checkpoint wording | Firmware clears wrong bits | Reconcile all docs to one canonical contract in `qspi-status-done-bit-fix`. |
| F12 | i80/QSPI status read parity mismatch | i80 reads `0x0320`/`0x0323`, QSPI uses `sel=0x05`/`0x06` | Same status has different values depending on transport | Centralized status source in `VdpTop`; sim must read both paths and compare after each event. |
| F13 | FPGA POR-to-host-boot sequencing race | External AI condition #1; prior `0x22222222` config-boundary anomalies | First transaction starts before FPGA reset domain is fully settled; magic/status mis-framing or spurious CS# state | Host keeps CS# high and enforces settle delay before first transaction after boot; FPGA reset-release discipline verified; add explicit cold-POR campaign. |

## Design mechanisms to evaluate

These are candidates, not decisions. BrightForge and BronzeGate must evaluate each and recommend which to adopt, with trade-offs.

1. **Sticky status with Option A lifecycle** (adopted for `DONE` in `qspi-status-done-bit-fix`).
2. **Free-running-clock reset release for `QspiSlaveSync`** (candidate for `qspi-transport-reliability-hardening` if mechanism is confirmed).
3. **CS# input synchronizer / glitch filter** — small latency cost; may help F03.
4. **Upload payload CRC** — catches F05; host retries; area/latency cost must be measured.
5. **Per-transaction sequence number / command CRC** — catches framing and command corruption; higher cost.
6. **Host-side timeout and retry with backoff** — pure firmware; essential for self-healing.
7. **Health sticky error expansion** — add a `CRC_FAIL` or `TIMEOUT` bit if mechanisms 4/6 are adopted.
8. **Diagnostic selectors for field debug** — e.g., `sel=0x0D`-style latches; already used in Lane 1; keep minimal.
9. **SpinalHDL assertions / formal checks** — assert no deadlock, no illegal FSM states, no metastable multi-bit crossings.

## Test matrix (over-test plan)

### Simulation (SpinalSim / Verilator)

| Test | What it exercises | Pass criteria |
|---|---|---|
| `Qspi0x0323StatusClearSim` extended | `DONE` stickiness across CS# idle, clear-on-new-upload, `BUSY`/`ERROR`/`OVERFLOW` unchanged | All assertions pass; reads match expected sequence. |
| `QspiTransportBridgeSim` consumer audit | Every RTL/sim consumer of `uploadDone` | No pulse-width assumptions remain. |
| Randomized CS#-to-SCLK delay sweep | Config-boundary and normal transactions | Correct framing across delay range. |
| Back-to-back upload stress | Host starts next upload before polling `DONE` | Documented behavior occurs; no deadlock. |
| Idle-period sweep | Varying CS# high time | Status stickies hold; no spurious clears. |
| Error injection | Force `fifoOverflow`, `uploadError`, CRC fail | Sticky flags set; host can clear and recover. |
| i80/QSPI parity | Same events read through both transports | Values identical at each observable point. |
| Formal / assertion suite | FSM coverage, no deadlocks, CDC properties | No assertion failures. |

### Synthesis / PnR

| Check | Pass criteria |
|---|---|
| Gowin synthesis seed variance | Run ≥3 independent PnR seeds; record TNS, Fmax, and resource deltas for every seed; all seeds must meet TNS=0 on all clocks. |
| Multiple builds with seed/toolchain variation | TNS=0 all clocks; no resource explosion. |
| Timing corner analysis (fast/slow if available) | Setup/hold clean at target frequencies. |
| Resource margin | BSRAM/DSP/Logic leave ≥10% headroom on Tang Nano 20K. |

### Hardware

| Campaign | What it proves | Scale |
|---|---|---|
| Cold-POR cycles | Config-boundary first-transaction reliability | ≥50 fresh reconfigures (not just 10). |
| Warm reset cycles | Reset-release behavior after known-good state | ≥50 MCU resets without reconfigure. |
| Long-run upload/readback | Thermal/voltage stability | ≥30 minutes continuous mixed traffic. |
| Error-injection / retry | Host self-healing | Inject known bad commands; verify retry succeeds. |
| CRC upload validation | Silent corruption detection | Deliberately corrupt a byte; verify CRC fail + retry. |
| Back-to-back upload race | Host protocol compliance window | Many rapid uploads; check no deadlock. |
| Mixed status polling patterns | All `sel=0x05`/`0x06` and `0x0320`/`0x0323` combinations | Consistent results. |
| Host SPI driver reset recovery | BronzeGate timeout-reset recovery in `vdp_host_p4.c` | Repeated timeout injected; verify CS#-high idle + driver reset returns clean magic/status. |

## Self-healing / adjusting behavior

The host-side policy (BronzeGate owns) should be:

1. **Before first transaction after host boot:** drive CS# high and wait the configured settle delay so the FPGA sees a clean idle before the first SCLK edge (F13).
2. **Before every upload:** clear prior sticky errors via `0x0323` W1C.
3. **During upload:** if `ERROR`/`OVERFLOW` set, abort and retry up to N times.
4. **After upload:** poll `DONE` before starting next upload.
5. **If `DONE` not seen within timeout:** clear status, reset transport context (CS# high idle, re-init if needed), retry.
6. **If repeated timeouts occur:** perform a low-level SPI driver reset/recovery in `firmware/libvdp/vdp_host_p4.c` (external AI condition #2), then re-establish CS# high idle before retry.
7. **If repeated failures persist:** fall back to lower SCLK frequency or escalate to user.
8. **Health logging:** after every session, log `raw`, `overflow`, `malformed`, and any CRC/timeout counts.

The FPGA-side policy (BrightForge owns) should be:

1. **Bounded FSMs:** every state has a timeout or exit condition.
2. **Sticky errors:** once set, remain until host clears them.
3. **No silent acceptance:** malformed commands are dropped and reported.
4. **Reset discipline:** config-boundary reset is clean and deterministic.

## Acceptance criteria for "reliable connection"

- [ ] FMEA table above is reviewed and signed by BrightForge + BronzeGate.
- [ ] Every failure mode has a design response or an accepted risk note with recovery path.
- [ ] External AI conditions #1–#3 are closed and evidenced (FMEA row, host SPI driver reset recovery, seed-variance tracking).
- [ ] `qspi-status-done-bit-fix` closes with sticky `DONE`, full regression, PnR, and ≥50-cycle HW sanity.
- [ ] `qspi-transport-reliability-hardening` closes with a confirmed mechanism and a fix proven by HW reproof.
- [ ] Host self-healing retry policy is implemented and tested via error injection.
- [ ] Long-run stress (≥30 min) passes with zero unrecovered errors.
- [ ] ADR-009 + `MODE0_REGISTER_BUS_SPEC.md` + `firmware/GOTCHAS.md` + runbook are updated.
- [ ] Proof packet for the combined reliability campaign is stored under `PROJECT_PLAN/proof_packets/qspi-cpu-fpga-reliability/`.

## Next actions

1. **BrightForge:** Implement Step 1 of `qspi-status-done-bit-fix` on `brightforge/qspi-status-done-bit-fix`: sticky `DONE` in `QspiSdramBridge.scala` (set on completion, clear on next accepted upload start), verify `QspiTransportCore.scala` and `TopTang20kHdmi.scala` mapping, run full regression, prove PnR `TNS=0`, and post Rule 19 evidence. Do not merge without BronzeGate sign-off.
2. **BronzeGate:** Review the updated FMEA and self-healing policy; implement SPI driver timeout-reset recovery in `firmware/libvdp/vdp_host_p4.c`; run active ESP-IDF v6.0.2 target build gate; provide Rule 19 sign-off after evidence is posted.
3. **CyanPeak:** Review the updated FMEA and self-healing policy for spec/contract consistency; ensure no new host-visible change slips in without Rule 19.
4. **CoralReef:** Capture the external AI verdict, locked conditions, and updated FMEA/self-healing policy in the docs/runbook; update `GOTCHAS.md` as needed.
5. **TopazCliff:** Hold Rule 19 sign-off until the FMEA is closed and both lanes have hardware proof; track external AI conditions to closure.

## Notes

- This plan is owner-directed (Rule 9). It does **not** authorize unilateral interface changes. Any new host-visible bit, register, or protocol change still requires independent BrightForge + BronzeGate Rule 19 sign-off.
- The external AI conditions are now locked requirements, not suggestions. They are tracked as F13, host SPI driver reset recovery, and Gowin seed-variance synthesis tracking.
- The retired i80 and legacy SPI paths remain guarded by `#error`; re-enabling them is out of scope.
- "Over-tested" is a test-coverage requirement, not an excuse to delay indefinitely. Each test must have a pass/fail criterion and an owner.
