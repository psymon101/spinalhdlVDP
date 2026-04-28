# Task 43 — Scenario Regression Harness

**Status:** DONE — Scenario regression harness implemented and operational
**depends_on:** [21]
**scope_boundary:** Test infrastructure only. No new HDL, no new features. No new compositor math or fetch formats.
**delivers:**

- Regression script/Makefile target that rebuilds and tags scenario bitstreams 1–17
- Capture + analysis artifact preservation policy
- Automated stability check (OpenCV/FFT-based) for each scenario
- CI-friendly entry point for post-substrate-change validation

**validation:**

- All 17 scenarios rebuild successfully from clean state
- At least 3 representative scenarios pass automated stability analysis

---

## 1. Goal

Provide a repeatable, automated regression harness so that any substrate change (Mode0 bus, register map, fetch engine, or compositor tweak) can be validated against the full scenario backlog without manual per-scenario effort.

Today, scenarios are validated ad-hoc: a developer manually builds `SCENARIO=N make flash`, captures via HDMI, and runs a bespoke Python analysis script. Task 43 systematizes this into a single command.

## 2. Scope

### 2.1 In scope

1. **Build harness** — Makefile target or shell script that iterates scenarios 0–17, generates Verilog, and optionally synthesizes each bitstream
2. **Capture harness** — scripted HDMI capture via V4L2 (`/dev/video0` or `/dev/video2`) with consistent duration (30 s) and format (1080p50)
3. **Analysis harness** — reusable OpenCV stability checker derived from the existing `captures/sc*/analyze_*.py` scripts
4. **Artifact preservation policy** — directory structure, naming convention, and retention rules for captures + analysis JSON
5. **CI entry point** — a single shell command that a future CI runner can invoke

### 2.2 Out of scope (deferred)

- FPGA farm / parallel synthesis (single-board sequential builds only)
- Automatic Pico firmware flash per scenario (scenarios share firmware; only bitstream changes)
- Long-soak testing beyond 30 s (Task 22 already covers 24-hour soak)
- Hardware-in-the-loop simulation (Verilator cosimulation deferred)

## 3. Architecture

### 3.1 Current state (ad-hoc validation)

```
Developer manually:
  cd fpga/tang20k && SCENARIO=17 make flash
  python3 captures/sc17/analyze_sc17.py   # bespoke per-scenario script
  # result: eyeball the PNG + JSON
```

Problems:
- No systematic rebuild of all scenarios after a bus or register change
- Analysis scripts are per-scenario and not reusable
- Captures are scattered across `captures/sc*/` with inconsistent naming
- No single-command validation path

### 3.2 Target state (Task 43)

```
scripts/regression/
  regress.sh              — master orchestrator
  build_all.sh            — iterate scenarios 0..17, build bitstreams
  capture.sh              — V4L2 capture with consistent parameters
  analyze.py              — reusable stability analyzer (OpenCV/FFT)
  preserver.sh            — move captures to dated artifact directory

captures/
  policy.md               — retention rules, naming convention
  template_analyze.py     — base class for scenario-specific analysis
```

### 3.3 Reusable stability analyzer

Core algorithm (derived from existing `analyze_sc17.py` and Task 33/36 analysis):

1. **Inter-frame delta** — compute mean absolute difference between consecutive frames
2. **Glitch detection** — flag frames whose delta is > 5σ above the median
3. **Freeze detection** — flag runs of ≥ 4 consecutive identical frames
4. **FFT banding check** — verify expected horizontal band structure via row-wise FFT (for banded scenarios like Sc33)
5. **Motion consistency** — compute motion percentage; reject if < 0.5 % (frozen) or > 20 % (unstable)

Scenario-specific extensions (optional overlays):
- Sprite detection (Sc4–Sc7, Sc15–Sc17)
- Scroll-rate measurement (Sc2–Sc3, Sc15–Sc17)
- Color-math band edge alignment (Sc33)

### 3.4 Build matrix

| Scenario | Description | Build target | Hardware proof history |
|---|---|---|---|
| 0 | Default / diagnostic | `TopTang20kHdmiVerilog` | Baseline |
| 1–3 | Wave 1 scroll rates | `Scenario1Verilog` … | Task 20 |
| 4–7 | Sprite configs | `Scenario4Verilog` … | R2 |
| 12 | Affine background | `Scenario12Verilog` | Task 19 |
| 13 | Palette animation | `Scenario13Verilog` | Task 20+ |
| 15–17 | Mixed / stress | `Scenario15Verilog` … | Tasks 21–23 |
| 33 | HDMA color-math | `Scenario33Verilog` | Task 33 |

Note: Scenarios 8–11, 14, 16, 18 exist in the scenario docs but may not have dedicated Verilog generation targets. The build harness must skip gracefully if a target is missing.

## 4. Validation Plan

### 4.1 Build validation

```sh
cd scripts/regression
./build_all.sh
```

**Assertions:**
- All 18 targets (0–17) generate Verilog without error
- Synthesis step is optional (controlled by `REGRESS_SYNTH=1`); default is Verilog-only for speed
- Build artifacts are tagged: `build_logs/scN_YYYYMMDD_HHMMSS.log`

### 4.2 Capture + analysis validation (3 representative scenarios)

**Representative set:**
1. **Sc0** — default diagnostic (proves baseline rendering path)
2. **Sc17** — stress scene (proves maximum concurrent load stability)
3. **Sc33** — HDMA color-math (proves copper/HDMA integration)

**For each:**
1. Build + flash bitstream
2. Run `capture.sh` → 30 s MP4 at 1080p50
3. Run `analyze.py` → JSON report
4. Assert:
   - glitch fraction < 1 %
   - freeze fraction < 1 %
   - motion percentage within scenario-specific bounds

### 4.3 Artifact preservation policy

```
captures/regression_YYYYMMDD/
  scN/
    capture.mp4
    analysis.json
    mid_frame.png
    build.log
```

Retention: keep last 10 regression runs; archive older runs to cold storage.

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| V4L2 device path varies (`/dev/video0` vs `/dev/video2`) | High — capture fails | Auto-detect via `v4l2-ctl --list-devices`; allow override via env var |
| Synthesis time makes full matrix impractical | Medium — 17× synthesis = hours | Default to Verilog-only; synth gated by `REGRESS_SYNTH=1` |
| Existing analysis scripts are too scenario-specific | Low — reusable base class handles 80 % | Scenario-specific overlays inherit from base class |
| Pico firmware mismatch with new bitstream | Low — firmware is host-side, scenario-agnostic | Document that regression assumes proven Pico firmware |

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** harness implementation — `regress.sh` + `analyze.py` + build/capture scripts
- **C:** validation — 3 representative scenarios pass automated analysis

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `scripts/regression/` ~200 lines; `captures/policy.md` ~30 lines; Makefile target +10 lines |
| **Hardware target** | Tang Nano 20K + Pico 2 (RP2350) + HDMI capture |
| **Dependencies** | Task 21 (mixed-scene integration, provides Sc15–Sc17) |

## 8. Open Questions (for implementation to resolve)

1. **Synthesis default:** Should `regress.sh` default to Verilog-only (fast) or full bitstream (complete but slow)? Recommend Verilog-only default with `REGRESS_SYNTH=1` opt-in.
2. **Scenario 33 inclusion:** Sc33 is the HDMA proof scenario (Task 33). Should it be part of the standard 0–17 matrix or a supplemental target? Recommend including it as `sc33` in the harness since it exercises copper/HDMA.
3. **Capture device auto-detection:** Should the harness probe V4L2 devices automatically, or require explicit `CAPTURE_DEV=/dev/videoN`? Recommend auto-detect with env override.
