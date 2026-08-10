# External AI briefing — spinalhdlVDP CPU↔FPGA QSPI reliability

**Date:** 2026-08-10  
**Project:** spinalhdlVDP — SpinalHDL VDP for Tang Nano 20K  
**Host platform:** ESP32-P4 Function EV Board (canonical QSPI host)  
**FPGA:** Sipeed Tang Nano 20K (Gowin GW2A-18)  
**Branch under review:** `main` at `24d19ef3`  
**Bundle companion file:** `full_project_bundle_2026-08-10.md`

## Purpose of this briefing

The project owner has directed us to build a **solid, scalable, over-tested, self-healing/adjusting connection between the CPU (ESP32-P4) and the FPGA**. We are asking the external AI to review the current state, the master reliability plan, and the bundled source/project files, then identify gaps, risky assumptions, and under-tested areas.

## Current state

### What is merged to `main`

- The `codebase-cleanup-status-contract` lane is **DONE and merged** (`7bff3d65`, `bf1ea619`, `6ca34805`). It centralized the upload-status W1C decode in `VdpTop.scala`, implemented QSPI selectors `0x05` (sticky) and `0x06` (upload), and gave i80 hosts parity via memory-mapped reads of `0x0320`/`0x0323`.
- Retired i80 and legacy SPI firmware paths are guarded by `#error` compile-time checks (`289fa646`) so they cannot be accidentally compiled.
- The active ESP32-P4 QSPI backend is `firmware/libvdp/vdp_host_p4.c` + `vdp_mode0.c`.

### Two active engineering lanes

| Lane | Status | Blocker / next step |
|---|---|---|
| `qspi-status-done-bit-fix` | **RUNNING — Option A confirmed** | The merged cleanup defines `DONE` (bit 1 of `sel=0x06` / `0x0323`) as sticky, but the implementation drives it from a one-cycle pixel-domain pulse (`QspiSdramBridge.donePulse`) that the SCLK/i80 status paths cannot reliably sample. BrightForge is authorized to implement a true sticky level (set at upload completion, clear on next accepted upload start) with no W1C on bit 1. |
| `qspi-transport-reliability-hardening` | **BLOCKED — mechanism unconfirmed** | Lane 1 showed a first-transaction `magic=0x22222222` anomaly on a fresh `a5a047a2` reconfigure. The original diagnostic evidence (`firstPhase=CMD/firstBitc=1`) is now understood as a capture artifact; the true mechanism may be a reset-domain race, CS# SI/bounce, or read-data launch glitch. BrightForge must build a corrected free-running-domain diagnostic before any RTL fix. |

### Master reliability plan

The owner directive prompted us to write:

`PROJECT_PLAN/TASKS/qspi-cpu-fpga-reliability-plan.md`

It contains:
- Six reliability attributes (observable, recoverable, self-healing, silent-corruption-free, bounded, deterministic).
- A 12-row FMEA table covering `DONE`-bit observability, first-transaction mis-framing, CS# SI, read-launch glitches, silent SDRAM corruption, back-to-back upload races, CDC issues, and long-run drift.
- Candidate design mechanisms: sticky status, free-running reset release, CS# glitch filter, upload CRC, sequence numbers, host retry/backoff, diagnostic selectors, SpinalHDL assertions.
- An over-test matrix across simulation, synthesis/PnR, and hardware.
- Acceptance criteria that raise the bar beyond the two individual lanes.

## What we need from the external AI

Please review the companion bundle (`full_project_bundle_2026-08-10.md`) and this briefing, then answer the following:

1. **Failure-mode coverage.** Are there failure modes or corner cases missing from the FMEA in `qspi-cpu-fpga-reliability-plan.md`? Consider:
   - Clock-domain crossing and metastability.
   - FPGA configuration/POR state versus host boot order.
   - SPI peripheral configuration changes on the ESP32-P4 side.
   - Long-cable / breadboard wiring effects.
   - Toolchain/synthesis differences across seeds or Gowin versions.

2. **Design-mechanism trade-offs.** For each candidate mechanism (CRC, CS# glitch filter, sequence numbers, host retry, etc.), is the cost/benefit appropriate for this project? Are any of them essential rather than optional?

3. **Option A correctness.** Is the `DONE`-bit lifecycle decision (sticky across CS# idle until the next accepted upload starts; no W1C on bit 1) sound and self-consistent with the rest of the status contract?

4. **Diagnostic correctness.** For the transport-lane anomaly, what additional diagnostic experiments (beyond the corrected free-running `firstPhase/firstBitc` capture) would definitively discriminate between reset-domain, CS# SI, and read-launch mechanisms?

5. **Test gaps.** What tests in the over-test matrix are insufficient, impossible on this bench, or missing entirely?

6. **Self-healing policy.** Is the proposed host-side retry/timeout/backoff policy complete? What policy edge cases could still leave the host stuck?

7. **Spec/doc risks.** Are there ambiguities in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, or `firmware/libvdp/mode0_regs.json` that could cause the host and FPGA to disagree after the `DONE`-bit fix?

Please provide concrete recommendations, not just general advice. Where possible, cite file paths and line numbers from the bundle.

## Deliverable format

Return a markdown report with:
- Executive verdict (is the current plan adequate to meet the owner's reliability goal?)
- Itemized findings (numbered F1, F2, ...)
- Recommended changes to the plan, source, tests, or docs
- Any blockers that should stop Rule 19 sign-off until resolved

## Constraints

- The retired i80 and legacy SPI paths must stay retired unless a new Rule-19-gated lane explicitly re-opens them.
- Any new host-visible register, bit, or protocol change requires independent BrightForge + BronzeGate Rule 19 sign-off.
- The Tang Nano 20K wiring and ESP32-P4 GPIO mapping (`SCLK=21, CS=20, IO0=32, IO1=33, IO2=22, IO3=23`) are fixed for this build.

---

*This briefing and the companion bundle were generated at `main` `24d19ef3` on 2026-08-10.*

---

# Full project bundle

## File: AGENTS.md

```md
# AGENTS.md — spinalhdlVDP

Repo-specific rules for `/home/itadmin/github/spinalhdlVDP`.
The workspace file at `/home/itadmin/github/AGENTS.md` remains authoritative
for canonical identity, roster, and cross-project coordination rules.

Examples and command snippets: `AGENTS_EXAMPLES.md`
Agent-specific rules: `AGENTS/<CanonicalName>.md`

## Model-specific bootstrap

This repo is used by multiple agent hosts. Load the right instruction file for your host **after** reading this file:

| Canonical Name | Host | Reads | Repo file |
|---|---|---|---|
| `BrightForge` | Claude Code | `CLAUDE.md` (project root) | `CLAUDE.md` in this directory |
| `BronzeGate` | Codex CLI | `AGENTS.md` (root → cwd) + `~/.codex/AGENTS.md` | `AGENTS/BronzeGate.md` |
| `TopazCliff` | Kimi Code | `AGENTS.md` (root → cwd) + `AGENTS.local.md` (cwd) | `AGENTS/TopazCliff.md` |
| `CoralReef` | Kimi Code | `AGENTS.md` (root → cwd) + `AGENTS.local.md` (cwd) | `AGENTS/CoralReef.md` |
| `CyanPeak` | Antigravity CLI (`agy`) | `AGENTS.md` (root → cwd) + `~/.gemini/GEMINI.md` | `AGENTS/CyanPeak.md` |

The launcher (`launch_agent_isolated.sh`) injects your role file into the host's expected location before startup, so you do not need to locate it manually each session.

---

## Quick Reference

| | |
|---|---|
| **Active repo** | `spinalhdlVDP` — SpinalHDL VDP for Tang Nano 20K |
| **Mailbox** | `/home/itadmin/github/spinalhdlVDP` (repo-root only); use `team-mailbox` skill |
| **Source of truth order** | (1) latest authoritative mail → (2) `STATUS.md` → (3) `PROJECT_PLAN/TASKS.md` active task → (4) repo state |
| **Critical path rule** | One active shared engineering lane at a time |
| **Session start** | Read `AGENTS.md` → your model-specific instruction file (`CLAUDE.md` for BrightForge, otherwise `AGENTS/<YourName>.md`) → `STATUS.md` → `PROJECT_PLAN/PROJECT_PLAN.md` → `PROJECT_PLAN/TASKS.md` → active task/specification |
| **Hardware proof rule** | Simulator first, then unambiguous hardware proof. 100% required. No exceptions. |
| **No simulation assumptions** | No path or corner may be dismissed as "structurally impossible" without a sim run that exercises it and shows it clean. Paths that cannot be modeled must be documented as unclosed risks, not as proof of safety. |
| **Sim config (global)** | Automatic — no per-agent setup. JVM heap 16G via `.jvmopts`; Verilator threads via `Config.simThreads` (=19). Use `Config.sim` for sims, or for headless/no-wave add `--threads ${Config.simThreads}` via `SimConfig.addSimulatorFlag`. |
| **FPGA source rule** | SpinalHDL is editable source; generated Verilog is a build artifact |
| **Live status authority** | `STATUS.md` owns durable state, history, blockers, active lanes, and immediate next work |
| **AGENTS.md edits** | Requires PM authorization + diff review (Preventive Rule #8) |
| **Your agent rules** | `AGENTS/BrightForge.md` · `AGENTS/BronzeGate.md` · `AGENTS/TopazCliff.md` · `AGENTS/CyanPeak.md` · `AGENTS/CoralReef.md` |

---

## Identity

| Canonical Name | Role | Model | Activation |
|----------------|------|-------|------------|
| `BrightForge` | FPGA RTL engineer | Claude | Active executor |
| `BronzeGate` | MCU firmware engineer | Codex | Active executor |
| `TopazCliff` | Technical project manager | Kimi (Inst. 2) | PM |
| `CyanPeak` | Datasheet / spec review | Antigravity CLI (`agy`) | **Advisory — PM-activated only** |
| `CoralReef` | Compliance / documentation | Kimi | **Advisory — PM-activated only** |

*Advisory roles are pulled in by `TopazCliff` only when needed. Do not self-assign implementation work.*

**Role ownership:**
- `BronzeGate` owns MCU firmware responsibilities for `spinalhdlVDP`
- `TopazCliff` owns PM sequencing and interface-definition work for this repository
- `CyanPeak` owns datasheet/spec review when activated by PM
- `CoralReef` owns compliance/documentation review when activated by PM

## Mail Registration

Project mailbox: `/home/itadmin/github/spinalhdlVDP`

Register with the same canonical name used in other project mailboxes.
All in-repo agents must use this single repo-root mail project for lane
packets, replies, acknowledgements, and coordination.

**Allowed registration names:** the only valid agent names for this repo are
the canonical identities listed above:
`BrightForge`, `BronzeGate`, `TopazCliff`, `CyanPeak`, `CoralReef`.
Do not register hybrid names, abbreviations, or aliases (for example,
`BrightReef` is not a valid identity).

**Invalid names observed in this project:** `BrightReef`, `FoggyWolf`,
`AzureSparrow`, `RainyHill`, `WhitePond`, `StormyRidge`, `JadeGrove`,
`IvoryOwl`, `WildFinch`, `SilentCrane`. These contact links are **blocked**;
mail sent from any name other than the five canonical identities will not be
treated as authoritative project communication. Blocked links may still appear
in `list_contacts` with `status: blocked`; that is a backend display artifact
and does not make them valid recipients. If you are one of the canonical team
members, re-register under your correct identity; if you are a helper/sub-agent,
coordinate through the lane owner instead of maintaining a separate project
identity.

**Use the `team-mailbox` skill for all mail operations.** MCP mail is the
backend mail system; the `team-mailbox` skill is the interface you use to
talk to other agents.

- `fetch_inbox` — read shared inbox (always use `include_bodies=True` for substantive review)
- `acknowledge_message` — ack `ack_required` mail promptly
- `send_message` — open a new thread
- `reply_message` — continue an existing thread
- Do not use raw HTTP calls, ad-hoc scripts, or local caches as the authority.

| Do | Do Not |
|----|----|
| Use canonical name from Identity table | Create a fresh alias or different display name |
| `ensure_project` + `register_agent` with `human_key=/home/itadmin/github/spinalhdlVDP` | Route firmware mail through a separate mailbox |

**Mail checks:**
- Use the shared repo-root mailbox record as the source of truth.
- Before reporting "no new mail," fetch the current inbox snapshot and check the newest mailbox-visible message id.
- Do not rely on local cache, ad hoc queries, or last-seen timestamps alone.

## Scope

- **Primary repo:** SpinalHDL implementation lane for Tang Nano 20K VDP.
- Older sibling repos are historical reference only, not peers for execution planning.
- Keep here:
  - `hw/spinal/spinalhdlvdp/` — SpinalHDL source
  - `hw/gen/` — generated HDL
  - `fpga/tang20k/` — wrappers, constraints, integration glue
- Port from older repos only what is intentionally adopted.

## Source of Truth Order

Use this order for live execution state:

1. latest authoritative mail packet for the active lane
2. `PROJECT_PLAN/STATUS.md`
3. `PROJECT_PLAN/TASKS.md` and the active task file
4. current repo state / commit under discussion

An authoritative mail instruction that changes live state must be synchronized
into `STATUS.md` during the same engineering cycle.

For technical behavior, use the authority identified in the documentation
ownership table below. Do not treat mail, screenshots, generated Verilog,
proof firmware, or visual output as a substitute for an approved technical
specification and its SpinalHDL or `libvdp` implementation.

## Documentation and Engineering Record Authority

`PROJECT_PLAN/STATUS.md` is the sole durable authority for:

- current project state
- active engineering lanes
- blockers
- recent engineering history
- current baseline under test
- immediate next approved action

Authoritative mail may introduce a new instruction, ruling, or lane transition
before `STATUS.md` is updated. In that case:

1. follow the latest authoritative mail
2. update `STATUS.md` in the same engineering cycle
3. do not leave mail and `STATUS.md` intentionally inconsistent

`PROJECT_PLAN/TASKS.md` contains the detailed active task and checkpoint
requirements. It does not replace `STATUS.md`.

The modular documentation system has these responsibilities:

| Information | Authority |
|---|---|
| Live state, history, blockers, active lanes | `STATUS.md` |
| Current task/checkpoint details | `PROJECT_PLAN/TASKS.md` and active task file |
| Long-term sequencing and gates | project execution plan |
| Shared FPGA behavior | `docs/fpga/` and `docs/architecture/` |
| Firmware and `libvdp` behavior | `docs/firmware/` |
| Platform behavior and implementation | `docs/platforms/<platform>/` |
| Exact commands | `docs/runbooks/` |
| Expected test results | test plan and golden vectors |
| Actual test results | proof packet |
| Permanent architecture decisions | ADRs |
| Release versions and artifact hashes | release manifest |
| Register addresses and fields | authoritative register schema |
| Public C API | `libvdp` headers and generated API documentation |

Do not duplicate live status, current blockers, engineering history, or active
lane information in architecture, platform, firmware, or runbook documents.
Those documents must link to `STATUS.md` instead.

When documents disagree:

1. stop and identify the authoritative owner
2. open or report a reconciliation item
3. do not silently select whichever version supports the current implementation
4. do not copy the discrepancy into additional files

## FPGA Source and Generated Artifact Rule

Scala/SpinalHDL source is the editable FPGA implementation authority.

- Implement FPGA behavior in SpinalHDL.
- Add or update SpinalSim tests with every behavioral FPGA change.
- Generated Verilog is a build artifact.
- Do not make permanent behavioral changes directly in generated Verilog.
- Regenerate Verilog from a clean source state before synthesis.
- Any generated-Verilog difference must be traceable to a SpinalHDL,
  configuration, generator, or toolchain change.
- Shared RTL changes require the complete affected regression suite, not only
  the new component test.
- Tang Nano 20K synthesis, timing review, and matched hardware proof are
  required before an FPGA lane closes.
- Generated RTL may be inspected for verification or tool diagnosis, but fixes
  must be made in SpinalHDL or the approved generator/integration source.
- Diagnostic bitstreams must identify the source commit and generator used.

## libvdp and Platform Adapter Rule

Reusable host-facing behavior belongs in `libvdp`.

The supported layering is:

```text
Application or reference firmware
    ↓
Platform adapter: vdp_zx_*, vdp_atarist_*, vdp_amiga_*, etc.
    ↓
Generic helpers: vdp_mode0_*, vdp_copper_*, vdp_status_*, vdp_upload_*
    ↓
Host API: vdp_host_*, vdp_reg_*, vdp_sdram_*
    ↓
Transport backend
    ↓
FPGA
```

Rules:

- Reference applications remain thin.
- Do not hand-frame protocol packets in an application when the operation
  belongs in `libvdp`.
- Platform adapters translate native visual concepts into shared Mode0 or
  platform-extension behavior.
- Platform adapters must not duplicate the host bridge, compositor, scaler,
  palette RAM, or HDMI pipeline.
- Transport-specific limitations must not silently change generic API
  semantics.
- A public API, register, or transport behavior change must update code,
  tests, and governing documentation in the same task.
- Platform-specific helpers use the existing `vdp_*` namespace. Do not create
  a parallel `retro_vdp_*` SDK.
- Host applications supply graphics data, memory updates, register state, and
  timed events; the FPGA owns deterministic video interpretation and scanout.

## Change Packet Requirements

Every behavioral engineering change must include, in the same authorized lane:

- implementation source
- affected SpinalSim or firmware tests
- expected results
- governing documentation changes
- register/schema updates when applicable
- synthesis/resource impact when FPGA logic changes
- hardware proof requirements
- rollback or recovery notes

A task is not complete merely because the code compiles or a reference image
appears correct.

## Proof Packet and Evidence Requirements

Every lane that produces a build, simulation, synthesis, or hardware result
must store evidence in a proof packet under:

```text
PROJECT_PLAN/proof_packets/<LANE-or-TASK>/
```

A proof packet contains:

- `manifest.yaml` — lane, commits, mail IDs, reviews, decision;
- `source/` — source commit and diff summary;
- `simulation/` — SpinalSim / firmware test logs and commands;
- `generated_rtl/` — generated Verilog hash and regeneration command;
- `synthesis/` — Gowin resource/timing reports;
- `firmware/` — ELF/BIN/partition hashes and build commands;
- `hardware/` — board/wiring revision, procedure, and results;
- `captures/` — images, traces, or logs;
- `hashes.sha256` — all artifact hashes;
- `review.md` — review verdicts and open deviations.

A hardware result is invalid without:

- source commit;
- generated RTL hash;
- bitstream hash;
- firmware hash;
- asset hash;
- board and wiring revision;
- tool versions;
- exact procedure.

Expected results belong in test plans. Actual results belong in proof packets.

## Architecture Decisions and Runbooks

Permanent architecture decisions for registers, protocols, opcodes, APIs,
pinouts, build flags, or role boundaries require an ADR under
`PROJECT_PLAN/DECISIONS/`.

Exact operational commands belong in runbooks under `docs/runbooks/`. A
runbook is not complete until the owner has executed every command from a
clean state and recorded expected outputs and pass/fail criteria.

## Context Compression

When resuming or handing off, compress state to:

- task / checkpoint
- latest commit
- latest authoritative mail
- current `STATUS.md` state
- governing specification
- latest passing simulation or hardware proof
- blocker or next allowed step

Do not carry full conversational history forward when those facts are sufficient.

## Context Survival

If this session exceeds 50 tool calls or 2 hours:

1. Write a checkpoint note to `memory` before compaction
2. After compaction, re-read `AGENTS.md` and `AGENTS/<YourName>.md`
3. Re-read `STATUS.md`
4. Re-read the active `PROJECT_PLAN/TASK_*.md`
5. Re-read the governing technical specification
6. Do not resume work without verifying lane ownership, latest commit, and latest passing proof

## GitHub Authentication

Pushing to the upstream repository (`https://github.com/psymon101/spinalhdlVDP.git`)
requires authentication. The canonical shared credential for this workspace is
stored locally in:

```text
~/.netrc
```

Required format:

```text
machine github.com login <GITHUB_USER> password <TOKEN>
```

Rules:

- Keep `~/.netrc` mode `600`.
- Do **not** commit the token, a `.netrc` file, or any credential file to the repository.
- For repeated use, prefer `git credential-manager` or `git credential.helper store`.
- If the token is revoked or regenerated, update `~/.netrc` and verify the next push succeeds.

*Owner-directed addition on 2026-07-29.*

## Preventive Rules

Binding rules. Enforced to prevent identity, authorization, contract, status,
source, and evidence drift.

| # | Rule | One-line Requirement |
|---|------|----------------------|
| 1 | Role Transfer | No self-declared role absorption. Requires TopazCliff authorization + transition mail + `AGENTS.md` update |
| 2 | Review Singleton | `CyanPeak` owns spec-accuracy review; `CoralReef` owns compliance/doc review. Both are advisory and PM-activated. Any future transfer requires TopazCliff authorization and explicit role reassignment |
| 3 | Commit-Within-Cycle | Audit PASS work must be committed before next PM review. Audit owner may withhold PASS until commit hash is in packet |
| 4 | Contract Deviation | >25% deviation from locked hardware contract must be documented in `GOTCHAS.md` with quantitative analysis |
| 5 | Signoff Consistency | One canonical signoff per agent. No mixed aliases mid-thread |
| 6 | Identity Retirement | Requires retirement mail + roster removal + no pending audits + 24h observation window |
| 7 | Side-Lane Authorization | Parallel work requires TopazCliff lane-open authorization before implementation |
| 8 | AGENTS.md Immutability | No unilateral rewrites. Requires TopazCliff authorization AND `CyanPeak` review (when activated) AND diff review |
| 9 | Project Owner Override | Owner may override process constraints by direct instruction. Agent records owner-directed change; diff remains reviewable; scope is limited to instruction unless broader intent stated |
| 10 | Prior Art Search | No novel-root-cause/mechanism/fix claim without searching `TASKS_HISTORY.md`, `archive/artifacts/`, `GOTCHAS.md`, and `memory`, and citing results in the same message. Claims without citation are invalid. |
| 11 | Memory Closeout | After every task, write comprehensive task summary to `memory` including lessons learned and dialogue context. PM writes lane/project summaries. No closeout without memory entry |
| 12 | Live Status Authority | `STATUS.md` owns durable live state; authoritative mail changes must be synchronized into it during the same engineering cycle |
| 13 | Generated RTL Integrity | FPGA behavior changes originate in SpinalHDL; permanent generated-Verilog-only edits are prohibited |
| 14 | Complete Change Packet | Behavioral changes include implementation, tests, documentation, expected results, and proof requirements in the same lane |
| 15 | Proof Packet | Hardware and synthesis results require a complete proof packet with artifact hashes, procedure, and reviews |
| 16 | Architecture Decisions | Permanent contract changes require an ADR under `PROJECT_PLAN/DECISIONS/` |
| 17 | Validated Runbooks | Operational commands must be validated from a clean state and stored under `docs/runbooks/` |
| 18 | Canonical Adapter Directory | Each platform adapter has one canonical directory under `kb/<Adapter>/`; do not duplicate adapter authority |
| 19 | Interface Checkpoint | Host-visible changes require independent BrightForge + BronzeGate approval before implementation |

### Prior-art search procedure (Rule 10)

Before claiming a new root cause, mechanism, or fix, the agent **must**:

1. Search `TASKS_HISTORY.md`, `PROJECT_PLAN/TASKS/*/`, `archive/artifacts/`, `kb/*/`, and `GOTCHAS.md` for related failures, fixes, or warnings.
2. Query `memory` for related lessons, root causes, and dialogue context.
3. Search the git history (`git log --all --grep=<keyword>`) for prior commits, sims, or proof packets on the same path.
4. Cite the exact prior-art found (file path, commit hash, memory entry, GOTCHA id) in the same message that makes the claim.
5. If no prior art is found, explicitly state **"No prior art found after searching X/Y/Z"**.

The PM will reject any root-cause/mechanism/fix claim that does not include this citation block. A claim that is later contradicted by prior art is grounds for a lane post-mortem and a `memory` lesson-learned entry.

**Legacy SPI contract:** 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.

**Signoff strings:**
- `— BronzeGate`
- `— BrightForge`
- `— CyanPeak`
- `— CoralReef`
- `— TopazCliff`

If you believe a rule is wrong, escalate to TopazCliff with a specific amendment proposal. Do not edit the file directly.

```

## File: build.sbt

```sbt
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "local.spinalhdlvdp"

val spinalVersion = "1.12.3"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val spinalhdlvdp = (project in file("."))
  .settings(
    name := "spinalhdlVDP",
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    Test / scalaSource := baseDirectory.value / "hw" / "spinal",
    libraryDependencies ++= Seq(
      spinalCore, 
      spinalLib, 
      spinalIdslPlugin,
      "org.scalatest" %% "scalatest" % "3.2.17"
    )
  )

```

## File: docs/architecture/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Architecture Documentation

System-wide technical contracts for spinalhdlVDP.

Documents here describe *what the system must do* and *who owns each decision*.
Implementation details belong in `docs/fpga/` and `docs/firmware/`.

```

## File: docs/external_documentation_system/docs/architecture/CAPABILITY_MODEL.md

```md
# Capability and ABI Model

The host must discover the connected bitstream rather than assume every feature
is present.

## Required capability data

- magic value;
- ABI major/minor;
- build/profile identifier;
- feature bitmap;
- adapter bitmap;
- SDRAM byte count;
- maximum logical width/height;
- maximum layers and sprites;
- supported bitmap formats;
- supported planar layouts and plane count;
- Copper/HDMA/Blitter availability;
- supported transport read/status functions.

## Compatibility

- major mismatch: initialization fails;
- newer minor version: allowed only when required feature bits are present;
- missing feature: platform initialization fails with a specific error;
- unknown reserved bits: ignored unless the ABI says otherwise.

## Required firmware API

```c
bool vdp_get_capabilities(vdp_capabilities_t *out);
bool vdp_require_features(uint32_t required);
bool vdp_adapter_supported(vdp_adapter_id_t adapter);
```

```

## File: docs/external_documentation_system/docs/architecture/CLOCK_RESET_CDC.md

```md
# Clock, Reset, and CDC Contract

## Required locked table

Foundation Gate 0 must populate exact clock sources, frequencies, PLL outputs,
reset polarity, and reset release order.

| Domain | Source | Frequency | Reset | Consumers |
|---|---|---:|---|---|
| Host bridge | TBD | TBD | TBD | host decoder/FIFOs |
| Core/video | TBD | TBD | TBD | engines/compositor |
| Pixel | TBD | TBD | TBD | timing/scaler |
| SDRAM | TBD | TBD | TBD | controller/arbiter |
| HDMI/TMDS | TBD | TBD | TBD | serializer |

## CDC rules

Every crossing must use one of:

- two-flop synchronization for stable single-bit state;
- toggle/pulse synchronizer for events;
- `StreamFifoCC` or approved asynchronous FIFO for streams;
- request/acknowledge handshake for multibit snapshots;
- dual-clock memory with documented collision semantics.

Direct combinational crossings are forbidden.

## Reset requirements

- all FIFOs return empty;
- active/pending register banks return to defined defaults;
- scanout produces a stable safe image;
- SDRAM clients remain blocked until initialization completes;
- mode switching cannot leave mixed old/new configuration;
- reset tests cover assertion during idle and active scanout.

```

## File: docs/external_documentation_system/docs/architecture/SDRAM_ARBITRATION.md

```md
# SDRAM and Arbitration Architecture

## Required clients

- scanline/pixel fetch;
- tile and sprite fetch;
- host uploads;
- DMA/Blitter;
- platform-specific prefetch;
- optional diagnostic readback.

## Required contract

The implementation must document:

- SDRAM geometry and address units;
- endianness and alignment;
- burst lengths;
- refresh behavior;
- client priority;
- maximum service latency;
- line-buffer fill deadline;
- host-write behavior during active display;
- bank ownership and frame commit;
- underrun handling and status;
- fairness and starvation prevention.

## Preferred scheduling model

Real-time scanout has bounded service guarantees. Host uploads and Blitter work
use remaining bandwidth or explicitly approved vblank windows. A platform lane
must provide a bandwidth calculation before adding fetch demand.

## Mandatory tests

- simultaneous scanout and host upload;
- refresh at worst scanline point;
- Blitter load during active display;
- forced late service;
- repeated framebuffer swap;
- stale-bank and incomplete-buffer protection;
- deterministic underrun indication.

```

## File: docs/external_documentation_system/docs/architecture/SOURCE_OF_TRUTH.md

```md
# Source-of-Truth and Generated-Artifact Policy

## Authority order

1. Approved normative specification
2. SpinalHDL source
3. SpinalSim regression
4. Register schema
5. `libvdp` public API and implementation
6. Generated Verilog
7. Firmware examples
8. Visual captures

A conflict must be opened as a reconciliation task. Do not silently choose one
side or patch generated output.

## Generated artifacts

- generated Verilog;
- generated C/Scala register bindings;
- compiled firmware;
- Gowin project outputs;
- bitstreams;
- converted assets;
- test reports.

Every generated artifact must identify source inputs and generator versions.

```

## File: docs/external_documentation_system/docs/architecture/SYSTEM_ARCHITECTURE.md

```md
# System Architecture

## Product model

```text
Any host MCU/CPU/SBC/custom machine
        ↓
platform API or generic Mode0 API
        ↓
libvdp transport-neutral operations
        ↓
transport backend
        ↓
FPGA host bridge
        ↓
register state + FPGA SDRAM
        ↓
platform frontend/shared engines
        ↓
compositor → scaler → HDMI
```

## Responsibility split

### Host

- chooses Mode0 or a platform visual adapter;
- supplies graphics memory and assets;
- writes registers and optional timed-event programs;
- performs high-level asset conversion where appropriate;
- handles application logic.

### FPGA

- stores video state needed for continuous scanout;
- fetches and decodes native formats;
- applies deterministic layer, sprite, palette, raster, and priority behavior;
- generates continuous display timing and HDMI output;
- reports status, overflow, late events, and errors.

### libvdp

- hides packet framing and transport details;
- exposes generic and platform-oriented helpers;
- enforces ABI/capability compatibility;
- provides safe commit, upload, status, and error behavior.

## Non-goals

- complete computer emulation;
- CPU execution;
- audio emulation;
- storage or OS emulation;
- universal cycle-exact bus contention.

```

## File: docs/external_documentation_system/docs/architecture/VIDEO_PIPELINE.md

```md
# Video Pipeline

## Common internal path

```text
platform/native memory
    ↓
frontend fetch and decode
    ↓
per-layer pixel candidate
(index/RGB, opaque, priority, source)
    ↓
sprite candidate
    ↓
platform priority mapping
    ↓
common compositor
    ↓
color math/effects
    ↓
logical pixel stream
    ↓
integer scaler/centering/border
    ↓
HDMI timing and output
```

## Internal pixel candidate

A shared candidate should contain enough information for:

- palette index or direct RGB;
- transparency;
- priority;
- layer/source identifier;
- collision participation;
- validity.

The exact Bundle definition is locked in the SpinalHDL architecture document.

## Timing rule

A frontend may have native memory and register behavior, but it must deliver
pixels to the common pipeline at the documented latency and must never alter
HDMI timing.

```

## File: docs/external_documentation_system/docs/firmware/ASSET_PIPELINE.md

```md
# Asset Pipeline

## Purpose

Convert common source assets into deterministic platform-native binary layouts.

## Required properties

- command-line operation;
- pinned dependencies;
- deterministic output;
- input and output hashes;
- explicit width/height/palette checks;
- failure on unsupported colors/layout;
- golden conversion tests.

## Required converters

- generic packed indexed formats;
- generic planar;
- ZX screen/attributes;
- TMS tables;
- Sega/NES/Genesis/SNES tile formats;
- C64 screen/color/bitmap;
- Atari ST interleaved planar;
- Amiga independent bitplanes;
- procedural helper data where useful for TIA.

```

## File: docs/external_documentation_system/docs/firmware/BUFFER_COMMIT_MODEL.md

```md
# Buffer and Commit Model

## Principle

The host may prepare state asynchronously, but the FPGA must display only
complete, explicitly committed state.

## Required patterns

- pending/active register banks;
- inactive framebuffer base;
- inactive Copper program bank;
- optional inactive LINESTATE/HDMA table;
- explicit commit/swap request;
- completion/status sequence number.

## Safety requirements

- no swap to an uninitialized bank;
- no partial framebuffer promotion;
- reset invalidates pending work;
- repeated commit is deterministic;
- late commit is counted or deferred, never half-applied.

```

## File: docs/external_documentation_system/docs/firmware/CAPABILITY_DISCOVERY.md

```md
# Capability Discovery

## Required initialization flow

1. initialize transport;
2. read magic;
3. read ABI version;
4. read feature and adapter bitmaps;
5. read memory/limit data;
6. compare required capabilities;
7. reset/configure only after compatibility passes.

## Error cases

- no device;
- wrong magic;
- unsupported ABI major;
- missing adapter;
- missing required engine;
- insufficient memory/limits;
- transport lacks required read/status behavior.

```

## File: docs/external_documentation_system/docs/firmware/ERROR_HANDLING.md

```md
# Error Handling

## Error classes

- invalid argument;
- not initialized;
- transport initialization;
- transmit/receive;
- incompatible ABI;
- unsupported capability;
- timeout;
- upload overflow/drop/short frame;
- late event;
- SDRAM/scanout underrun;
- engine busy or invalid trigger.

## Rules

- errors are never silently converted to success;
- sticky hardware errors have explicit clear semantics;
- APIs document whether errors are returned immediately or polled;
- reference applications print source, code, and corrective action;
- proof tests include negative/error cases.

```

## File: docs/external_documentation_system/docs/firmware/HOST_TRANSPORT_ABI.md

```md
# Host Transport ABI

## Logical operations

- initialize/deinitialize;
- register read/write/burst;
- SDRAM write;
- optional readback;
- status/capability read;
- speed/configuration control;
- wait/interrupt integration.

## Backend interface target

A backend should provide a function table or equivalent compile-time contract:

```c
typedef struct {
    bool (*init)(void *ctx);
    bool (*reg_write)(void *ctx, uint32_t addr, uint16_t value);
    bool (*reg_read)(void *ctx, uint32_t addr, uint16_t *value);
    bool (*reg_write_burst)(void *ctx, uint32_t addr,
                            const uint16_t *words, size_t count);
    bool (*sdram_write)(void *ctx, uint32_t addr,
                        const void *data, size_t bytes);
    bool (*read_status)(void *ctx, uint8_t selector, uint32_t *value);
} vdp_transport_ops_t;
```

The final API may differ, but every backend must satisfy the same semantics.

## Required backend record

- target/SDK;
- pins;
- bus rate;
- max transaction;
- DMA support;
- read support;
- vblank/status method;
- electrical constraints;
- known limitations;
- acceptance test results.

```

## File: docs/external_documentation_system/docs/firmware/LIBVDP_ARCHITECTURE.md

```md
# libvdp Architecture

## Public layers

```text
application
    ↓
vdp_<platform>_* adapters
    ↓
vdp_mode0_* / vdp_copper_* / vdp_status_* / upload helpers
    ↓
vdp_host_* / vdp_reg_* / vdp_sdram_*
    ↓
selected transport backend
```

## Design rules

- applications do not hand-frame host packets;
- platform adapters remain thin;
- generic functions return actionable errors where possible;
- ABI and capabilities are checked during initialization;
- blocking/asynchronous behavior is documented;
- callback and reentrancy rules are explicit;
- transport limitations do not silently leak into platform APIs.

## Required build targets

- authoritative host;
- at least one secondary host;
- unit-test/mock transport target;
- examples for Generic Mode0 and every closed platform.

```

## File: docs/external_documentation_system/docs/firmware/PORTING_LIBVDP.md

```md
# Porting libvdp to a New Host

## Procedure

1. select or implement a transport backend;
2. define board pins and electrical requirements;
3. implement initialization and reset;
4. implement register write/read;
5. implement burst and SDRAM upload;
6. implement status/vblank method;
7. implement timeouts and errors;
8. build the common API unchanged;
9. run the transport conformance suite;
10. run Generic Mode0 hardware proof;
11. record the host in the support matrix.

## Conformance tests

- magic/ABI read;
- register round trip;
- maximum burst;
- SDRAM write and diagnostic read/hash;
- malformed transaction recovery;
- reset recovery;
- vblank/status;
- long repeated transaction soak.

A build-only port is not labeled supported.

```

## File: docs/external_documentation_system/docs/firmware/REGISTER_ABI.md

```md
# Register ABI

## Single authority

One machine-readable schema owns address, width, access, reset, fields,
commit boundary, description, and reserved values.

## Generated outputs

- SpinalHDL decode/constants;
- C headers;
- human-readable reference;
- reset-value tests;
- optional additional language bindings.

## CI checks

- generated outputs are current;
- no duplicate address;
- field widths fit;
- access type is legal;
- reset values agree with SpinalSim;
- public headers and FPGA decode use the same ABI version.

## Commit boundaries

Every register is marked as immediate, H-boundary, line-boundary, vblank,
explicit commit, or engine-completion.

```

## File: docs/external_documentation_system/docs/fpga/BITMAP_ENGINE.md

```md
# Bitmap Engine

## Target formats

The final authoritative encoding must be reconciled before Foundation 0 closes.
The intended generic capability set includes packed indexed 1/2/4/8bpp and
RGB565. Unsupported/reserved codes must be explicit.

## Required configuration

- enable;
- format;
- base;
- stride;
- source width/height;
- pending base;
- explicit swap/commit;
- transparency or palette bank where applicable.

## SpinalSim

For every format, verify bit order, byte order, odd widths, stride padding,
base alignment, line start/end, clipping, swap boundaries, and reset.

```

## File: docs/external_documentation_system/docs/fpga/COMPOSITOR.md

```md
# Compositor

## Input

Layer and sprite pixel candidates with color/index, transparency, priority,
source, and collision metadata.

## Responsibilities

- resolve backdrop;
- platform-mapped priority;
- window/mask selection;
- sprite/background collision;
- color math/effects;
- emit one logical pixel.

## Rule

Platform adapters may supply priority metadata or a compact priority mode, but
must not duplicate the full compositor.

## Tests

Truth-table tests are required for every priority/color-math mode used by a
platform.

```

## File: docs/external_documentation_system/docs/fpga/COPPER_ENGINE.md

```md
# Copper Engine

## Purpose

Execute beam-synchronized register changes without requiring the host to meet
pixel deadlines.

## Instruction classes

- wait by line;
- wait by X/Y;
- register write;
- sequential write;
- jump;
- conditional skip;
- end/stop behavior as defined by the approved ISA.

## State model

- double-buffered program storage;
- inactive-bank upload;
- explicit swap request;
- vblank commit;
- deterministic reset;
- late/missed-wait diagnostics.

## Tests

- exact boundary matches;
- missed boundary;
- program swap;
- stale-bank protection;
- write to each allowed register class;
- jump/skip bounds;
- maximum program;
- reset during execution.

```

## File: docs/external_documentation_system/docs/fpga/DMA_BLITTER.md

```md
# DMA and Blitter

## Shared operations

- fill;
- copy;
- rectangle operations;
- line operation where supported.

## Contract

- source/destination address units;
- width/height/stride;
- overlap behavior;
- trigger ordering;
- busy/done/error;
- arbitration priority;
- allowed execution during active scanout.

## Tests

- smallest and largest legal operations;
- aligned/unaligned cases permitted by spec;
- overlap;
- busy re-trigger;
- reset;
- scanout contention;
- platform proof images.

```

## File: docs/external_documentation_system/docs/fpga/HDMA_LINESTATE.md

```md
# HDMA and LINESTATE

## Purpose

Apply per-line state efficiently for scrolling, windows, palette, and
platform-specific raster behavior.

## Contract

- table format and address units;
- channel count;
- direct/indirect modes;
- line activation point;
- double buffering/commit;
- out-of-range behavior;
- completion/error flags.

## Tests

- every channel;
- line 0 and last line;
- sparse changes;
- indirect table;
- table swap;
- malformed/end-of-table;
- contention with host/Copper.

```

## File: docs/external_documentation_system/docs/fpga/HOST_BRIDGE.md

```md
# Host Bridge

## Purpose

Translate the physical host bus into transport-neutral register, SDRAM, status,
and control operations.

## Required operations

- register write;
- register burst write;
- register read;
- SDRAM write;
- optional SDRAM diagnostic read;
- status/capability read;
- reset and error clear.

## SpinalHDL boundaries

Use a transport decoder to produce internal Streams/Flows. Physical QSPI, i80,
or future parallel decoders must not directly modify video-engine state.

## Verification

- framing and endianness;
- minimum/maximum length;
- malformed command;
- CRC/parity where enabled;
- burst auto-increment;
- backpressure;
- reset mid-command;
- read turnaround;
- dropped/short transaction diagnostics.

```

## File: docs/external_documentation_system/docs/fpga/PLANAR_ENGINE.md

```md
# Planar Engine

## Goal

Provide one shared planar fetch/decode substrate for native platform adapters.

## Required capability

- one through six planes;
- independent-plane layout;
- Atari ST interleaved-word layout;
- tile-planar layout where reused;
- selectable bit significance;
- per-plane base;
- common or odd/even modulo;
- logical width and clipping;
- line reset hooks for stateful decoders such as HAM6.

## Output

Produce palette indices or platform-decoder inputs at a documented latency.

## SpinalSim vectors

- one-plane bit significance;
- all plane counts 1–6;
- independent pointers;
- nonzero modulo;
- interleaved ST 16-pixel groups;
- line boundaries;
- missing/disabled plane;
- forced SDRAM latency;
- EHB/HAM handoff.

```

## File: docs/external_documentation_system/docs/fpga/RESOURCE_BUDGET.md

```md
# Resource and Timing Budget

Foundation Gate 0 must lock the current baseline.

| Resource | Baseline | Warning threshold | Hard ceiling |
|---|---:|---:|---:|
| LUT | TBD | TBD | device limit minus reserve |
| Block RAM | TBD | TBD | device limit minus reserve |
| DSP | TBD | TBD | device limit minus reserve |
| PLL | TBD | TBD | device limit |
| I/O | TBD | TBD | board routing limit |
| Worst slack | TBD | 0 ns | < 0 ns blocks release |

Every platform design checkpoint includes an estimated and measured delta.
Resource growth beyond the approved threshold requires an ADR.

```

## File: docs/external_documentation_system/docs/fpga/SCALER_HDMI.md

```md
# Scaler and HDMI Output

## Responsibilities

- stable output timing;
- integer scaling;
- centering;
- outer/inner borders;
- logical clipping;
- HDMI/TMDS serialization.

## Contract

The FPGA continues output even when the host is idle. Platform frontends never
control physical HDMI timing directly.

## Tests

- supported logical dimensions;
- scale factors;
- center offsets;
- border dimensions;
- blanking/sync;
- reset;
- mode switch;
- long soak on direct display and secondary capture.

```

## File: docs/external_documentation_system/docs/fpga/SPINALHDL_ARCHITECTURE.md

```md
# SpinalHDL Architecture

## Source organization

Recommended logical packages:

```text
src/main/scala/
├── top/
├── host/
├── memory/
├── timing/
├── engines/
├── adapters/
├── compositor/
├── output/
├── registers/
└── diagnostics/

src/test/scala/
├── unit/
├── integration/
├── adapters/
├── stress/
└── regression/
```

Existing project names may remain, but the mapping must be documented.

## Design rules

- Scala/SpinalHDL is editable source.
- Use explicit Bundles and Streams between components.
- Isolate platform frontends from shared rendering/output.
- Use pending/active banks for vblank-sensitive state.
- Make clock crossings explicit.
- Add assertions for impossible states and overflow.
- Avoid hidden global constants; pass configuration through case classes.
- A new adapter receives its own component and simulation suite.
- Generated Verilog receives no permanent edits.

## Required test levels

1. component unit simulation;
2. adapter simulation;
3. `VdpTop` integration simulation;
4. SDRAM-contention regression;
5. generated-RTL/synthesis gate;
6. hardware proof.

## Generator requirements

The production generator must emit:

- deterministic top name;
- build metadata;
- selected device/profile configuration;
- generated RTL into a clean directory;
- register/interface consistency report.

```

## File: docs/external_documentation_system/docs/fpga/SPRITE_ENGINE.md

```md
# Sprite Engine

## Shared capability

- descriptor RAM;
- configurable platform-visible limits;
- per-line evaluation;
- pattern fetch;
- flip, size, palette, priority, transparency;
- collision participation;
- overflow reporting.

## Rule

The shared engine may support a larger ceiling than a platform. The platform
adapter must enforce original limits and status behavior.

## Tests

- 0, 1, maximum, and maximum+1 sprites per line;
- transparent pixels;
- overlap order;
- clipping;
- large/doubled sprites;
- collision flags;
- repeated mode changes;
- worst-case fetch load.

```

## File: docs/external_documentation_system/docs/fpga/TILE_ENGINE.md

```md
# Tile Engine

## Shared capability

- configurable tile width/height;
- packed and native planar pattern decode;
- tilemap fetch;
- palette bank;
- horizontal/vertical flip;
- priority;
- transparent index;
- scroll offsets;
- optional per-row/per-column state.

## Platform use

TMS9918A, SMS/Game Gear, NES, Genesis, and SNES adapters map native entries
into this substrate or add a narrow native decoder before it.

## Tests

- every supported BPP;
- flip/priority/palette combinations;
- map wrapping;
- edge clipping;
- scroll boundaries;
- cache/prefetch behavior;
- contention stress.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/FIRMWARE_LIBVDP_PLAN.md

```md
# Amiga OCS/ECS — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_amiga_*`.
2. Add plane/modulo/window/color/sprite helpers.
3. Add Copper list builder using existing opcode helpers.
4. Add native independent-bitplane converter.
5. Add EHB/HAM6 reference assets.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/FPGA_SPINALHDL_PLAN.md

```md
# Amiga OCS/ECS — FPGA / SpinalHDL Plan

## Dependencies

- independent 1–6 plane fetch
- Copper
- shared sprites
- Blitter

## Ordered implementation tasks

1. Create `AmigaOcsAdapter.scala`.
2. Add independent plane pointers.
3. Add odd/even modulo and 1–6 plane fetch.
4. Implement dual-playfield split/priority.
5. Implement OCS sprite restrictions/attachment.
6. Map DIW/DDF-like windows.
7. Map allowed Copper registers.
8. Implement EHB.
9. Implement stateful HAM6 with line reset.
10. Map approved Blitter subset.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/LIMITATIONS_AND_DEFERRED.md

```md
# Amiga OCS/ECS — Limitations and Deferred Work

## Current exclusions

- No AGA, HAM8, 8 bitplanes, AGA palette/fetch/sprites.
- No cycle-exact Agnus chip-bus contention or complete computer emulation.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/README.md

```md
# Amiga OCS/ECS

- **Current state:** BLOCKED — after Atari ST/STE
- **Dependencies:** independent 1–6 plane fetch, Copper, shared sprites, Blitter

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/REFERENCES.md

```md
# Amiga OCS/ECS — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/SCOPE_AND_VIDEO_MODEL.md

```md
# Amiga OCS/ECS — Scope and Video Model

## Supported visual target

- 1–6 independent bitplanes
- lores/hires
- 32-color palette
- dual playfield
- 8 OCS-style sprites
- attached sprites
- display/fetch windows
- odd/even modulo
- Copper changes
- basic Blitter copy/fill/line
- EHB
- HAM6
- selected ECS positioning
- explicitly no AGA

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/amiga_ocs_ecs/TEST_AND_REVIEW_PLAN.md

```md
# Amiga OCS/ECS — Test and Review Plan

## Required tests

- AMIGA-SIM-001..006 plane counts
- AMIGA-SIM-010 independent bases
- AMIGA-SIM-011 odd/even modulo
- AMIGA-SIM-020 dual playfield
- AMIGA-SIM-030 sprites/attached
- AMIGA-SIM-040 Copper mid-line
- AMIGA-SIM-050 EHB
- AMIGA-SIM-060 HAM6 and line reset
- AMIGA-HW-001 Blitter-generated proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/FIRMWARE_LIBVDP_PLAN.md

```md
# Atari 2600 TIA — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create TIA-register API.
2. Create scanline command-list builder.
3. Document deadlines and late-command behavior.
4. Optional image-to-event helper.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/FPGA_SPINALHDL_PLAN.md

```md
# Atari 2600 TIA — FPGA / SpinalHDL Plan

## Dependencies

- beam-timed event engine
- common compositor/output

## Ordered implementation tasks

1. Create `Atari2600TiaAdapter.scala`.
2. Implement procedural scanline state.
3. Generate playfield/player/missile/ball pixels.
4. Implement motion/copy/size.
5. Implement priority and collision latches.
6. Integrate timed-write command list.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/LIMITATIONS_AND_DEFERRED.md

```md
# Atari 2600 TIA — Limitations and Deferred Work

## Current exclusions

- Visual procedural TIA only.
- Host must prepare timed writes ahead of the beam; late events are reported.
- No 6507 or complete console emulation.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/README.md

```md
# Atari 2600 TIA

- **Current state:** BLOCKED — research may proceed after Foundation 0
- **Dependencies:** beam-timed event engine, common compositor/output

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/REFERENCES.md

```md
# Atari 2600 TIA — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/SCOPE_AND_VIDEO_MODEL.md

```md
# Atari 2600 TIA — Scope and Video Model

## Supported visual target

- 20-bit playfield reflected/repeated
- 2 players
- 2 missiles
- ball
- color registers
- horizontal position/motion
- size/copy controls
- priority/collisions
- beam-synchronous writes

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/atari_2600_tia/TEST_AND_REVIEW_PLAN.md

```md
# Atari 2600 TIA — Test and Review Plan

## Required tests

- TIA-SIM-001 playfield reflection
- TIA-SIM-010 player copy/size
- TIA-SIM-020 missile/ball
- TIA-SIM-030 motion
- TIA-SIM-040 priority
- TIA-SIM-050 collision matrix
- TIA-HW-001 timed mid-line proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/FIRMWARE_LIBVDP_PLAN.md

```md
# Atari ST/STE — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_atarist_*` and optional `vdp_atariste_*`.
2. Upload authentic 32 KB screen data.
3. Add packed-to-ST-planar converter.
4. Reuse ST/STE palette helpers.
5. Add dirty-region and atomic-present helpers.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/FPGA_SPINALHDL_PLAN.md

```md
# Atari ST/STE — FPGA / SpinalHDL Plan

## Dependencies

- shared planar engine
- Copper X/Y writes
- atomic framebuffer commit

## Ordered implementation tasks

1. Create `AtariStAdapter.scala`.
2. Add interleaved 16-pixel word fetch.
3. Support 1/2/4 plane selection and significance.
4. Map border/display window.
5. Apply palette writes at approved X/Y.
6. Prove bandwidth for all three modes.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/LIMITATIONS_AND_DEFERRED.md

```md
# Atari ST/STE — Limitations and Deferred Work

## Current exclusions

- No 68000, GLUE, MMU, Blitter, or complete machine emulation.
- No cycle-exact CPU/video contention.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/README.md

```md
# Atari ST/STE

- **Current state:** BLOCKED — after C64
- **Dependencies:** shared planar engine, Copper X/Y writes, atomic framebuffer commit

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/REFERENCES.md

```md
# Atari ST/STE — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/SCOPE_AND_VIDEO_MODEL.md

```md
# Atari ST/STE — Scope and Video Model

## Supported visual target

- ST low 320×200 4 planes
- ST medium 640×200 2 planes
- ST high 640×400 1 plane
- ST RGB333
- STE RGB444
- border/scaling
- raster palette changes
- selected STE extensions

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/atari_st_ste/TEST_AND_REVIEW_PLAN.md

```md
# Atari ST/STE — Test and Review Plan

## Required tests

- ST-SIM-001 known 16-pixel plane words
- ST-SIM-010 low
- ST-SIM-011 medium
- ST-SIM-012 high
- ST-SIM-020 palette conversion
- ST-SIM-030 raster bars
- ST-HW-001 full/dirty/swap proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/c64/FIRMWARE_LIBVDP_PLAN.md

```md
# Commodore 64 VIC-II — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create VIC-register helpers.
2. Add charset/screen/color/bitmap/sprite uploads.
3. Add raster program builder.
4. Add native asset converters.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/c64/FPGA_SPINALHDL_PLAN.md

```md
# Commodore 64 VIC-II — FPGA / SpinalHDL Plan

## Dependencies

- shared raster events
- shared sprite collisions

## Ordered implementation tasks

1. Create `C64VicIIAdapter.scala`.
2. Map character/screen/color/bitmap memory.
3. Implement multicolor decode.
4. Implement sprite expansion/multicolor/priority.
5. Map collisions/status.
6. Use Copper/HDMA for raster effects.
7. Model visual bad-line effects only when required.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/c64/LIMITATIONS_AND_DEFERRED.md

```md
# Commodore 64 VIC-II — Limitations and Deferred Work

## Current exclusions

- No CPU cycle stealing or full shared-bus timing.
- Advanced border tricks require explicit approved scope.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/c64/README.md

```md
# Commodore 64 VIC-II

- **Current state:** BLOCKED — after NES
- **Dependencies:** shared raster events, shared sprite collisions

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/c64/REFERENCES.md

```md
# Commodore 64 VIC-II — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/c64/SCOPE_AND_VIDEO_MODEL.md

```md
# Commodore 64 VIC-II — Scope and Video Model

## Supported visual target

- standard/multicolor text
- standard/multicolor bitmap
- optional extended background mode
- 8 sprites
- border/raster changes
- sprite collisions

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/c64/TEST_AND_REVIEW_PLAN.md

```md
# Commodore 64 VIC-II — Test and Review Plan

## Required tests

- C64-SIM-001 each display mode
- C64-SIM-010 multicolor grouping
- C64-SIM-020 border/raster bars
- C64-SIM-030 sprite modes
- C64-SIM-040 collisions
- C64-HW-001 multiplex-style proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/FIRMWARE_LIBVDP_PLAN.md

```md
# Generic Mode0 — Firmware / libvdp Plan

## Ordered implementation tasks

1. Complete full `libvdp` build matrix.
2. Add capability query and ABI guard.
3. Add structured Mode0 configuration and atomic commit helpers.
4. Add deterministic generic asset converters.
5. Remove transport-specific assumptions from generic helpers.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/FPGA_SPINALHDL_PLAN.md

```md
# Generic Mode0 — FPGA / SpinalHDL Plan

## Dependencies

- Foundation 0
- Foundation 1
- Foundation 2

## Ordered implementation tasks

1. Reconcile bitmap format encoding and regenerate schema/bindings.
2. Finish and prove packed bitmap modes.
3. Finish shared one-to-six-plane engine.
4. Stabilize four layers and documented sprite ceilings.
5. Stabilize Copper, HDMA, LINESTATE, DMA, Blitter, compositor, scaler.
6. Add capability/ABI registers and late/underrun diagnostics.
7. Run full contention and reset regression.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/LIMITATIONS_AND_DEFERRED.md

```md
# Generic Mode0 — Limitations and Deferred Work

## Current exclusions

- No platform-specific native register compatibility in the generic API.
- Exact cycle behavior is not claimed unless a specific engine test states it.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/README.md

```md
# Generic Mode0

- **Current state:** BLOCKED — Foundation Gates 0–2
- **Dependencies:** Foundation 0, Foundation 1, Foundation 2

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/REFERENCES.md

```md
# Generic Mode0 — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/SCOPE_AND_VIDEO_MODEL.md

```md
# Generic Mode0 — Scope and Video Model

## Supported visual target

- Host-independent graphics-card programming model.
- Packed indexed 1/2/4/8bpp and RGB565 after encoding reconciliation.
- One-to-six-plane shared planar capability.
- Four background layers, shared sprites, palette, windows, color math.
- Copper, HDMA, LINESTATE, DMA, Blitter, scaling, borders, HDMI.
- Capability registers, ABI version, diagnostics, atomic commit.

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/generic_mode0/TEST_AND_REVIEW_PLAN.md

```md
# Generic Mode0 — Test and Review Plan

## Required tests

- MODE0-SIM-001 each bitmap format
- MODE0-SIM-010 plane counts 1–6
- MODE0-SIM-020 four-layer priority
- MODE0-SIM-030 sprite maximum and overflow
- MODE0-SIM-040 Copper raster bars
- MODE0-SIM-050 HDMA/LINESTATE per-line scroll
- MODE0-SIM-060 DMA/Blitter operations
- MODE0-HW-001 repeated reset/mode-switch/soak

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/genesis/FIRMWARE_LIBVDP_PLAN.md

```md
# Sega Mega Drive/Genesis — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create VRAM/CRAM/VSRAM helpers.
2. Add plane/window/sprite/scroll APIs.
3. Add palette and native tile converters.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/genesis/FPGA_SPINALHDL_PLAN.md

```md
# Sega Mega Drive/Genesis — FPGA / SpinalHDL Plan

## Dependencies

- complex layer priority
- scroll-table fetch
- sprite chain

## Ordered implementation tasks

1. Create `GenesisAdapter.scala`.
2. Decode native name tables.
3. Implement plane/window selection.
4. Fetch scroll tables.
5. Implement approved priority resolver.
6. Map sprite chain/table.
7. Implement shadow/highlight post-compositor.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/genesis/LIMITATIONS_AND_DEFERRED.md

```md
# Sega Mega Drive/Genesis — Limitations and Deferred Work

## Current exclusions

- No complete FIFO or CPU interface timing.
- Undocumented VDP quirks require separate approved scope.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/genesis/README.md

```md
# Sega Mega Drive/Genesis

- **Current state:** BLOCKED — after Amiga
- **Dependencies:** complex layer priority, scroll-table fetch, sprite chain

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/genesis/REFERENCES.md

```md
# Sega Mega Drive/Genesis — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/genesis/SCOPE_AND_VIDEO_MODEL.md

```md
# Sega Mega Drive/Genesis — Scope and Video Model

## Supported visual target

- Plane A/B
- Window
- 4bpp tiles
- priority/flips
- row/line horizontal scroll
- full/column vertical scroll
- sprites
- 64-entry CRAM
- shadow/highlight
- approved 256/320 width modes

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/genesis/TEST_AND_REVIEW_PLAN.md

```md
# Sega Mega Drive/Genesis — Test and Review Plan

## Required tests

- GEN-SIM-001 A/B/window priority
- GEN-SIM-010 tile priority/flips
- GEN-SIM-020 scroll modes
- GEN-SIM-030 sprite boundary
- GEN-SIM-040 shadow/highlight
- GEN-HW-001 width/mode proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/nes/FIRMWARE_LIBVDP_PLAN.md

```md
# NES/Famicom — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create PPU-style CHR/nametable/attribute/palette/OAM helpers.
2. Add scroll/control/mask helpers.
3. Add native tile converter.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/nes/FPGA_SPINALHDL_PLAN.md

```md
# NES/Famicom — FPGA / SpinalHDL Plan

## Dependencies

- shared planar tile decode
- shared sprite evaluator

## Ordered implementation tasks

1. Create `NesAdapter.scala`.
2. Decode native 2bpp tiles.
3. Decode attribute quadrants.
4. Map OAM to shared sprites.
5. Enforce 8 sprites per line.
6. Implement sprite-zero hit/overflow.
7. Map mirroring and clipping.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/nes/LIMITATIONS_AND_DEFERRED.md

```md
# NES/Famicom — Limitations and Deferred Work

## Current exclusions

- Visual PPU model only.
- No claim of dot-exact CPU/PPU timing, mapper behavior, or undocumented races.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/nes/README.md

```md
# NES/Famicom

- **Current state:** BLOCKED — after Sega 8-bit
- **Dependencies:** shared planar tile decode, shared sprite evaluator

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/nes/REFERENCES.md

```md
# NES/Famicom — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/nes/SCOPE_AND_VIDEO_MODEL.md

```md
# NES/Famicom — Scope and Video Model

## Supported visual target

- 256×240/224 presentation
- 2bpp pattern tables
- nametables/attribute tables
- fine scrolling
- 64 sprites/8 per line
- sprite-zero hit
- sprite/background priority

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/nes/TEST_AND_REVIEW_PLAN.md

```md
# NES/Famicom — Test and Review Plan

## Required tests

- NES-SIM-001 2bpp decode
- NES-SIM-002 attribute quadrants
- NES-SIM-003 mirroring
- NES-SIM-004 8/9 sprite boundary
- NES-SIM-005 sprite-zero positive/negative
- NES-HW-001 scrolling/priority

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/FIRMWARE_LIBVDP_PLAN.md

```md
# Sega Master System and Game Gear — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_sms_*` and `vdp_gamegear_*`.
2. Reuse SMS/GG palette helpers.
3. Add native VRAM/CRAM upload helpers.
4. Add mode and viewport configuration.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/FPGA_SPINALHDL_PLAN.md

```md
# Sega Master System and Game Gear — FPGA / SpinalHDL Plan

## Dependencies

- TMS9918A
- shared tile/sprite substrate

## Ordered implementation tasks

1. Create shared Sega 8-bit adapter.
2. Decode native tilemap entries.
3. Enforce sprite-per-line limit/overflow.
4. Implement approved scroll locks.
5. Implement Game Gear viewport.
6. Reuse shared tile/sprite/compositor.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/LIMITATIONS_AND_DEFERRED.md

```md
# Sega Master System and Game Gear — Limitations and Deferred Work

## Current exclusions

- Only visual behavior selected in scope.
- FIFO/CPU bus timing and complete undocumented quirks are excluded.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/README.md

```md
# Sega Master System and Game Gear

- **Current state:** BLOCKED — after TMS9918A
- **Dependencies:** TMS9918A, shared tile/sprite substrate

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/REFERENCES.md

```md
# Sega Master System and Game Gear — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/SCOPE_AND_VIDEO_MODEL.md

```md
# Sega Master System and Game Gear — Scope and Video Model

## Supported visual target

- 4bpp tiles
- scrollable background
- sprites
- priority/flips
- SMS CRAM
- Game Gear 12-bit CRAM
- Game Gear viewport/crop

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/sms_game_gear/TEST_AND_REVIEW_PLAN.md

```md
# Sega Master System and Game Gear — Test and Review Plan

## Required tests

- SEGA8-SIM-001 tile decode/flip
- SEGA8-SIM-002 priority
- SEGA8-SIM-003 scrolling
- SEGA8-SIM-004 sprite boundary
- SEGA8-SIM-005 palette conversion
- SEGA8-HW-001 SMS/GG paired proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/FIRMWARE_LIBVDP_PLAN.md

```md
# SNES Modes 0–3-lite — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create mode/BG/tilemap/palette/OAM/window/color/HDMA helpers.
2. Add native planar tile converter.
3. Build one reference scene per mode.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/FPGA_SPINALHDL_PLAN.md

```md
# SNES Modes 0–3-lite — FPGA / SpinalHDL Plan

## Dependencies

- four layers
- windows/color math
- HDMA
- large sprite descriptors

## Ordered implementation tasks

1. Create `SnesAdapter.scala`.
2. Map modes and BG BPP.
3. Decode native tilemaps/tiles.
4. Implement mode priority.
5. Map OAM to shared sprites.
6. Map windows/masks/color math.
7. Map HDMA tables.
8. Defer Mode 7 until modes 0–3 close.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/LIMITATIONS_AND_DEFERRED.md

```md
# SNES Modes 0–3-lite — Limitations and Deferred Work

## Current exclusions

- No interlace or full cycle-accurate PPU behavior.
- Mode 7 is a later lane; advanced mosaic/offset corner cases deferred.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/README.md

```md
# SNES Modes 0–3-lite

- **Current state:** BLOCKED — after Genesis
- **Dependencies:** four layers, windows/color math, HDMA, large sprite descriptors

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/REFERENCES.md

```md
# SNES Modes 0–3-lite — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/SCOPE_AND_VIDEO_MODEL.md

```md
# SNES Modes 0–3-lite — Scope and Video Model

## Supported visual target

- Modes 0–3 only
- up to four backgrounds
- required 2/4/8bpp tiles
- 128 descriptors/approved 32 sprites per line
- windows/masks
- color math
- per-line HDMA
- mode priority
- Mode 7 optional only after closure

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/snes_modes_0_3/TEST_AND_REVIEW_PLAN.md

```md
# SNES Modes 0–3-lite — Test and Review Plan

## Required tests

- SNES-SIM-001..004 modes 0–3
- SNES-SIM-010 all tile depths
- SNES-SIM-020 four-layer priority
- SNES-SIM-030 32/33 sprite boundary
- SNES-SIM-040 windows
- SNES-SIM-050 color math
- SNES-SIM-060 HDMA
- SNES-HW-001 mode suite

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/FIRMWARE_LIBVDP_PLAN.md

```md
# TMS9918A Family — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_tms9918_*`.
2. Reuse fixed-palette loader.
3. Add native table upload helpers.
4. Add mode/register configuration objects.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/FPGA_SPINALHDL_PLAN.md

```md
# TMS9918A Family — FPGA / SpinalHDL Plan

## Dependencies

- Generic Mode0
- ZX closure

## Ordered implementation tasks

1. Create `Tms9918Adapter.scala`.
2. Map name/pattern/color tables.
3. Implement native sprite limits and status semantics.
4. Reuse tile/sprite/palette engines.
5. Add table-base register mapping.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/LIMITATIONS_AND_DEFERRED.md

```md
# TMS9918A Family — Limitations and Deferred Work

## Current exclusions

- Only approved TMS9918A-family visual behavior.
- Exact CPU/VRAM access timing is not modeled.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/README.md

```md
# TMS9918A Family

- **Current state:** BLOCKED — after ZX closure
- **Dependencies:** Generic Mode0, ZX closure

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/REFERENCES.md

```md
# TMS9918A Family — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/SCOPE_AND_VIDEO_MODEL.md

```md
# TMS9918A Family — Scope and Video Model

## Supported visual target

- Graphics I
- Graphics II
- Text
- Multicolor
- fixed 16-color palette
- sprites
- overflow/collision status

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/tms9918a/TEST_AND_REVIEW_PLAN.md

```md
# TMS9918A Family — Test and Review Plan

## Required tests

- TMS-SIM-001 Graphics I
- TMS-SIM-002 Graphics II
- TMS-SIM-003 Text
- TMS-SIM-004 Multicolor
- TMS-SIM-010 sprite overflow/collision
- TMS-HW-001 mode/table proof

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/FIRMWARE_LIBVDP_PLAN.md

```md
# ZX Spectrum — Firmware / libvdp Plan

## Ordered implementation tasks

1. Add `vdp_zx_init`.
2. Add bitmap and attribute upload helpers.
3. Add border/flash/present helpers.
4. Add Spectrum memory-layout converter.
5. Build an intentional attribute-clash reference scene.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/FPGA_SPINALHDL_PLAN.md

```md
# ZX Spectrum — FPGA / SpinalHDL Plan

## Dependencies

- Generic Mode0

## Ordered implementation tasks

1. Re-run existing adapter simulation against reconciled baseline.
2. Verify production shuffled addressing.
3. Verify flash cadence/reset.
4. Define border commit boundary.
5. Add direct attribute-clash regression.
6. Integrate without duplicating shared palette/scaler/output.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/LIMITATIONS_AND_DEFERRED.md

```md
# ZX Spectrum — Limitations and Deferred Work

## Current exclusions

- Visual profile only; no Z80/ULA contention model.
- Border timing is limited to the approved Copper/register boundary.

## Change rule

Adding a deferred feature requires:

1. research packet;
2. scope update;
3. dependency/resource analysis;
4. ADR when architecture or compatibility changes;
5. new requirements and tests;
6. release-plan update.

Do not widen the accuracy claim because a scene happens to render correctly.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/README.md

```md
# ZX Spectrum

- **Current state:** BLOCKED — re-close after Generic Mode0
- **Dependencies:** Generic Mode0

## Documents

1. `SCOPE_AND_VIDEO_MODEL.md`
2. `FPGA_SPINALHDL_PLAN.md`
3. `FIRMWARE_LIBVDP_PLAN.md`
4. `TEST_AND_REVIEW_PLAN.md`
5. `LIMITATIONS_AND_DEFERRED.md`
6. `REFERENCES.md`

## Lane rule

No implementation starts before the research packet and design checkpoint are
approved. The adapter must reuse common engines unless an approved requirement
cannot be represented by them.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/REFERENCES.md

```md
# ZX Spectrum — References and Research Packet

## Status

`UNPOPULATED — required before SPEC REVIEW`

## Source policy

Use primary hardware manuals, manufacturer documentation, schematics, and
well-established reference implementations. Record edition/version, page or
section, URL or repository commit, license, and the requirement supported.

## Required research table

| Requirement area | Primary source | Location | Interpretation | Reviewer |
|---|---|---|---|---|
| Memory layout | TBD | TBD | TBD | TBD |
| Palette | TBD | TBD | TBD | TBD |
| Sprites | TBD | TBD | TBD | TBD |
| Priority | TBD | TBD | TBD | TBD |
| Raster/timing | TBD | TBD | TBD | TBD |
| Reset/status | TBD | TBD | TBD | TBD |

General memory or unverified web summaries are not normative sources.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/SCOPE_AND_VIDEO_MODEL.md

```md
# ZX Spectrum — Scope and Video Model

## Supported visual target

- 256×192 bitmap
- 32×24 attribute cells
- ink/paper/bright/flash
- border color
- Spectrum shuffled bitmap addressing
- visible attribute clash

## Required specification before design approval

The platform team must document:

- native memory layout and byte/bit order;
- registers/configuration represented by the adapter;
- palette conversion;
- tile/bitmap/planar decode;
- sprites and per-line limits;
- priority and transparency truth tables;
- scrolling, borders, windows, and raster behavior;
- reset values;
- exact versus visually equivalent versus approximated behavior;
- unsupported behavior.

## Platform-to-Mode0 mapping

Create an approved table:

| Native feature | Shared engine | Adapter logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | exact/visual/approx |

## Memory examples

At least one worked example must include source bytes, FPGA SDRAM placement,
register configuration, decoded pixels, final palette/RGB result, and expected
hash.

```

## File: docs/external_documentation_system/docs/platforms/zx_spectrum/TEST_AND_REVIEW_PLAN.md

```md
# ZX Spectrum — Test and Review Plan

## Required tests

- ZX-SIM-001 shuffled address vectors
- ZX-SIM-002 attribute truth table
- ZX-SIM-003 flash cadence/reset
- ZX-SIM-004 border boundary
- ZX-HW-001 attribute-clash scene
- ZX-HW-002 cold/reset/mode soak

## Test case template

Every test states:

- requirement IDs;
- exact input vector and hash;
- initial register/memory state;
- simulation or hardware command;
- expected pixel/line/frame hash;
- expected status flags;
- maximum latency/time;
- pass/fail condition;
- evidence path.

## Review sequence

1. platform research review;
2. design/Mode0 mapping review;
3. SpinalHDL unit review;
4. SpinalSim review;
5. `VdpTop`/SDRAM integration review;
6. `libvdp` API review;
7. schema/document synchronization;
8. synthesis/timing/resource review;
9. hardware proof review;
10. independent clean-room/doc audit.

## Closure

All supported claims must map to passing tests. Visual inspection alone cannot
close the lane.

```

## File: docs/external_documentation_system/docs/reproducibility/REPRODUCIBLE_PRODUCT_PACKAGE.md

```md
# Reproducible Product Package

The complete normative reproducibility requirements remain preserved in `PROJECT_PLAN/REFERENCE_FULL_EXECUTION_PLAN.md`, Part VI. This modular package distributes those requirements into architecture, runbooks, testing, platform work packages, and the release manifest.

A release must state whether it achieves functional or artifact reproducibility.

```

## File: docs/external_documentation_system/docs/runbooks/01_SETUP_DEVELOPMENT_ENVIRONMENT.md

```md
# Setup Development Environment

This runbook is not complete until Foundation 0 locks exact versions.

1. Acquire the supported OS/container.
2. Verify source archive hash.
3. Install the locked JDK, Scala, sbt, SpinalHDL, simulator, Gowin EDA,
   host SDK, compiler, Python, CMake, and flashing tools.
4. Run `tools/verify_toolchain` once implemented.
5. Clone/checkout the locked commit.
6. Confirm clean tree.
7. Run the smoke build.
8. Save `tool_versions.txt`.

All exact commands and expected versions remain `TBD-FOUNDATION-0`.

```

## File: docs/external_documentation_system/docs/runbooks/02_RUN_SPINALSIM.md

```md
# Run SpinalSim

1. Clean simulation outputs.
2. Run component unit tests.
3. Run adapter tests.
4. Run `VdpTop` integration tests.
5. Run contention/stress tests.
6. Capture deterministic seeds and reports.
7. Fail on assertions, timeouts, unknowns, or mismatched hashes.

Foundation 0 must populate the canonical command and expected test count.

```

## File: docs/external_documentation_system/docs/runbooks/03_GENERATE_VERILOG.md

```md
# Generate Verilog

1. Verify clean Scala source and tool versions.
2. Remove the generated output directory.
3. run the production SpinalHDL generator;
4. capture generator metadata;
5. verify top/module interface;
6. run stale-generated-file check;
7. hash generated RTL.

Never hand-edit generated Verilog.

```

## File: docs/external_documentation_system/docs/runbooks/04_SYNTHESIZE_TANG_NANO_20K.md

```md
# Synthesize Tang Nano 20K

1. Verify generated RTL hash.
2. Verify Gowin version/device/package.
3. Verify constraint file and clock definitions.
4. Run the checked-in project script.
5. review critical warnings;
6. check timing and resource thresholds;
7. archive reports;
8. hash the bitstream.

Exact commands and thresholds are locked in Foundation 0.

```

## File: docs/external_documentation_system/docs/runbooks/05_PROGRAM_FPGA.md

```md
# Program FPGA

1. Confirm board revision and power.
2. Confirm bitstream hash.
3. Connect the approved programmer.
4. Program volatile or persistent target as specified.
5. power-cycle/reset as required;
6. record programmer log and detected device;
7. do not flash an unmatched firmware proof pair.

```

## File: docs/external_documentation_system/docs/runbooks/06_BUILD_LIBVDP_AND_REFERENCE_FIRMWARE.md

```md
# Build libvdp and Reference Firmware

1. Verify ABI/register generated headers.
2. Build the authoritative host target.
3. Build secondary supported targets.
4. Build Generic Mode0 example.
5. Build every closed platform example.
6. run software unit/mock tests;
7. record binary hashes and map/size reports.

```

## File: docs/external_documentation_system/docs/runbooks/07_BUILD_ESP32_P4.md

```md
# Build ESP32-P4 Backend

Status: blocked until the P4 backend is integrated into `libvdp`.

The final runbook must cover:

- exact ESP-IDF version;
- board/pin definition;
- required I/O power-domain setup;
- chosen i80/PARLIO or approved transport;
- write framing;
- manual or hardware readback;
- build, flash, and monitor commands;
- transport conformance and Generic Mode0 proof.

```

## File: docs/external_documentation_system/docs/runbooks/08_RUN_HARDWARE_REGRESSION.md

```md
# Run Hardware Regression

1. Verify source, bitstream, firmware, wiring, and asset hashes.
2. Program FPGA.
3. Flash host.
4. verify magic/ABI/capabilities;
5. run transport tests;
6. run Generic Mode0 tests;
7. run selected platform suite;
8. record status counters;
9. run reset/mode-switch tests;
10. run required soak;
11. capture direct-display and objective hash evidence;
12. complete proof packet.

```

## File: docs/external_documentation_system/docs/runbooks/09_BUILD_RELEASE.md

```md
# Build Release

1. require all milestone lanes closed;
2. run clean-room build;
3. generate and hash RTL;
4. synthesize and hash bitstream;
5. build and hash firmware;
6. package source, manifests, reports, tests, docs, and proofs;
7. verify archive from a new extraction;
8. collect all sign-offs.

```

## File: docs/external_documentation_system/docs/testing/CLEAN_ROOM_REPRODUCTION.md

```md
# Clean-Room Reproduction

An independent team must:

1. acquire and verify source;
2. establish the locked toolchain;
3. assemble the documented hardware;
4. run all SpinalSim tests;
5. generate Verilog;
6. synthesize the bitstream;
7. build `libvdp` and firmware;
8. program and flash;
9. run generic and platform acceptance;
10. compare hashes, counters, and expected images;
11. record deviations;
12. sign the report.

Release is blocked when undocumented private knowledge is required.

```

## File: docs/external_documentation_system/docs/testing/GOLDEN_VECTORS.md

```md
# Golden Vectors

Every closed engine/platform publishes:

- smallest legal vector;
- representative vector;
- boundary/stress vector;
- invalid vector;
- raster vector where applicable;
- input hashes;
- expected pixel/line/frame hashes;
- expected status bits;
- expected transaction trace.

Random failures become permanent seeded regressions.

```

## File: docs/external_documentation_system/docs/testing/HARDWARE_PROOF_STANDARD.md

```md
# Hardware Proof Standard

A proof identifies:

- source commit;
- generated RTL hash;
- bitstream hash;
- firmware hash;
- asset hashes;
- board and wiring revision;
- tool versions;
- exact commands;
- expected and actual results;
- status/error counters;
- test duration and reset count;
- reviewer.

A direct monitor is required for visual confirmation. Capture hardware is
secondary unless independently validated. Internal pixel hashes are preferred.

```

## File: docs/external_documentation_system/docs/testing/TEST_STRATEGY.md

```md
# Test Strategy

## Pyramid

1. pure conversion/unit tests;
2. SpinalHDL component tests;
3. adapter tests;
4. `VdpTop` integration;
5. contention/stress;
6. synthesis/static checks;
7. hardware conformance;
8. platform visual acceptance;
9. soak and reset;
10. clean-room reproduction.

Every requirement has at least one objective test. Visual inspection alone is
supporting evidence, not the primary oracle.

```

## File: docs/external_documentation_system/docs/troubleshooting/README.md

```md
# Troubleshooting

Create one decision tree per failure class:

- SpinalHDL compile/generation;
- SpinalSim mismatch;
- generated RTL drift;
- Gowin synthesis/timing;
- FPGA programming;
- no/unstable HDMI;
- host initialization;
- register read/write mismatch;
- SDRAM upload;
- CRC/parity/short frame;
- vblank timeout;
- Copper late event;
- line-buffer underrun;
- sprite overflow;
- platform visual mismatch.

Every entry includes symptoms, diagnostic commands, known-good values, likely
causes, safe corrective actions, and evidence required for escalation.

```

## File: docs/external_documentation_system/DOCUMENTATION_INDEX.md

```md
# Documentation Index

## Project control

- `PROJECT_PLAN/MASTER_EXECUTION_PLAN.md`
- `PROJECT_PLAN/ACTIVE_LANE.md`
- `PROJECT_PLAN/CURRENT_BASELINE.md`
- `PROJECT_PLAN/DEPENDENCY_GRAPH.md`
- `PROJECT_PLAN/RELEASE_MILESTONES.md`
- `PROJECT_PLAN/DOCUMENT_OWNERSHIP.md`
- `PROJECT_PLAN/CHANGE_CONTROL.md`

## Architecture

- `docs/architecture/SYSTEM_ARCHITECTURE.md`
- `docs/architecture/SOURCE_OF_TRUTH.md`
- `docs/architecture/CLOCK_RESET_CDC.md`
- `docs/architecture/SDRAM_ARBITRATION.md`
- `docs/architecture/VIDEO_PIPELINE.md`
- `docs/architecture/CAPABILITY_MODEL.md`

## FPGA / SpinalHDL

- `docs/fpga/SPINALHDL_ARCHITECTURE.md`
- `docs/fpga/HOST_BRIDGE.md`
- `docs/fpga/BITMAP_ENGINE.md`
- `docs/fpga/PLANAR_ENGINE.md`
- `docs/fpga/TILE_ENGINE.md`
- `docs/fpga/SPRITE_ENGINE.md`
- `docs/fpga/COPPER_ENGINE.md`
- `docs/fpga/HDMA_LINESTATE.md`
- `docs/fpga/DMA_BLITTER.md`
- `docs/fpga/COMPOSITOR.md`
- `docs/fpga/SCALER_HDMI.md`
- `docs/fpga/RESOURCE_BUDGET.md`

## Firmware / libvdp

- `docs/firmware/LIBVDP_ARCHITECTURE.md`
- `docs/firmware/HOST_TRANSPORT_ABI.md`
- `docs/firmware/REGISTER_ABI.md`
- `docs/firmware/CAPABILITY_DISCOVERY.md`
- `docs/firmware/BUFFER_COMMIT_MODEL.md`
- `docs/firmware/PORTING_LIBVDP.md`
- `docs/firmware/ERROR_HANDLING.md`
- `docs/firmware/ASSET_PIPELINE.md`

## Platform work packages

Each platform directory contains:

- `README.md`
- `SCOPE_AND_VIDEO_MODEL.md`
- `FPGA_SPINALHDL_PLAN.md`
- `FIRMWARE_LIBVDP_PLAN.md`
- `TEST_AND_REVIEW_PLAN.md`
- `LIMITATIONS_AND_DEFERRED.md`
- `REFERENCES.md`

## Procedures

- `docs/runbooks/`
- `docs/testing/`
- `docs/troubleshooting/`
- `docs/reproducibility/`

## Templates

- `PROJECT_PLAN/TASKS/TASK_TEMPLATE.md`
- `PROJECT_PLAN/proof_packets/PROOF_PACKET_TEMPLATE.md`
- `PROJECT_PLAN/DECISIONS/ADR_TEMPLATE.md`
- `RELEASE_MANIFEST_TEMPLATE.yaml`

```

## File: docs/external_documentation_system/PROJECT_PLAN/ACTIVE_LANE.md

```md
# Active Lane

## Lane

**FOUNDATION-0 — Baseline and Contract Reconciliation**

## State

`RESEARCH / BASELINE CAPTURE`

## Goal

Produce one authoritative, reproducible baseline for source, SpinalHDL
generation, SpinalSim, generated Verilog, Gowin synthesis, FPGA bitstream,
`libvdp`, reference firmware, host hardware, transport, wiring, and hardware
acceptance.

## Ordered work

1. `FOUNDATION-0-001` — source and artifact inventory.
2. `FOUNDATION-0-002` — lock SpinalHDL generator and Gowin project.
3. `FOUNDATION-0-003` — reconcile bitmap-format encoding.
4. `FOUNDATION-0-004` — reconcile planar plane-count/layout contract.
5. `FOUNDATION-0-005` — reconcile Copper timing and `VdpTop` integration.
6. `FOUNDATION-0-006` — select authoritative host and transport.
7. `FOUNDATION-0-007` — repair complete `libvdp` build matrix.
8. `FOUNDATION-0-008` — run and lock baseline SpinalSim regression.
9. `FOUNDATION-0-009` — synthesize and capture resource/timing baseline.
10. `FOUNDATION-0-010` — matched firmware/bitstream hardware proof.
11. `FOUNDATION-0-011` — independent documentation and source audit.
12. `FOUNDATION-0-012` — sign gate and open Foundation 1.

## Blocking defects already identified

- Bitmap mode names/encodings are not consistently represented.
- Public planar configuration does not yet express the intended full contract.
- Vblank/status behavior is not uniformly transport-neutral.
- The current Pico CMake target does not compile the full visible `libvdp`
  source surface.
- ESP32-P4 requires a dedicated pure ESP-IDF transport/backend path.
- Exact source, bitstream, firmware, and hardware baseline values remain to be
  locked.

## Exit gate

Foundation 0 closes only when `CURRENT_BASELINE.md` contains no unresolved
`TBD-FOUNDATION-0` fields required for the supported baseline and a clean-room
reviewer can repeat the baseline build and hardware proof.

```

## File: docs/external_documentation_system/PROJECT_PLAN/CHANGE_CONTROL.md

```md
# Change Control

## Changes requiring an ADR

- host protocol framing;
- register ABI;
- clock-domain structure;
- SDRAM arbitration;
- public `libvdp` API compatibility;
- platform scope or accuracy claim;
- authoritative host/transport;
- source-of-truth order;
- resource-budget changes;
- release reproducibility policy.

## Required PR contents

Every implementation PR includes:

- task ID and lane state;
- approved specification links;
- SpinalHDL files changed;
- firmware files changed;
- register/schema changes;
- simulations added and commands;
- synthesis/resource impact;
- hardware proof requirements;
- documentation changes;
- rollback plan.

## Generated-file policy

Generated Verilog and generated bindings may be committed when required for
tooling, but CI must prove they were regenerated from the checked-in sources.
Permanent hand edits are forbidden.

```

## File: docs/external_documentation_system/PROJECT_PLAN/CURRENT_BASELINE.md

```md
# Current Baseline

> Status: **INCOMPLETE — Foundation Gate 0**

Every `TBD-FOUNDATION-0` field is a release blocker for the baseline.

## Source

| Item | Locked value |
|---|---|
| Repository URL | `TBD-FOUNDATION-0` |
| Branch | `TBD-FOUNDATION-0` |
| Commit | `TBD-FOUNDATION-0` |
| Dirty-tree policy | clean |
| Submodules | `TBD-FOUNDATION-0` |
| Source archive SHA-256 | `TBD-FOUNDATION-0` |

## FPGA / SpinalHDL

| Item | Locked value |
|---|---|
| Production generator class | `TBD-FOUNDATION-0` |
| `build.sbt` hash | `TBD-FOUNDATION-0` |
| JDK | `TBD-FOUNDATION-0` |
| Scala | `TBD-FOUNDATION-0` |
| sbt | `TBD-FOUNDATION-0` |
| SpinalHDL | `TBD-FOUNDATION-0` |
| SpinalSim backend | `TBD-FOUNDATION-0` |
| Generated Verilog directory | `TBD-FOUNDATION-0` |
| Generated Verilog SHA-256 | `TBD-FOUNDATION-0` |

## Gowin

| Item | Locked value |
|---|---|
| Gowin EDA version | `TBD-FOUNDATION-0` |
| Device/package | `TBD-FOUNDATION-0` |
| Constraint file | `TBD-FOUNDATION-0` |
| Project/script entry point | `TBD-FOUNDATION-0` |
| Bitstream SHA-256 | `TBD-FOUNDATION-0` |
| Worst timing result | `TBD-FOUNDATION-0` |
| LUT/BRAM/DSP/PLL use | `TBD-FOUNDATION-0` |

## Hardware

| Item | Locked value |
|---|---|
| FPGA board | Tang Nano 20K |
| Board revision | `TBD-FOUNDATION-0` |
| Authoritative host | `TBD-FOUNDATION-0` |
| Host board revision | `TBD-FOUNDATION-0` |
| Transport | `TBD-FOUNDATION-0` |
| Validated bus rate | `TBD-FOUNDATION-0` |
| Wiring revision | `TBD-FOUNDATION-0` |
| Power/I/O requirements | `TBD-FOUNDATION-0` |
| HDMI target | `TBD-FOUNDATION-0` |

## Firmware

| Item | Locked value |
|---|---|
| `libvdp` ABI version | `TBD-FOUNDATION-0` |
| Reference application | `TBD-FOUNDATION-0` |
| SDK/toolchain | `TBD-FOUNDATION-0` |
| Firmware binary SHA-256 | `TBD-FOUNDATION-0` |
| Register-schema SHA-256 | `TBD-FOUNDATION-0` |

## Acceptance

| Item | Locked value |
|---|---|
| SpinalSim command | `TBD-FOUNDATION-0` |
| Expected test count | `TBD-FOUNDATION-0` |
| Synthesis command | `TBD-FOUNDATION-0` |
| Firmware build command | `TBD-FOUNDATION-0` |
| FPGA program command | `TBD-FOUNDATION-0` |
| Host flash command | `TBD-FOUNDATION-0` |
| Hardware test command | `TBD-FOUNDATION-0` |
| Expected frame/status results | `TBD-FOUNDATION-0` |
| Proof packet | `TBD-FOUNDATION-0` |

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-001-SPINALHDL-SOURCE-OF-TRUTH.md

```md
# ADR-001 — SpinalHDL is the FPGA source of truth

## Status

Accepted.

## Decision

All editable FPGA behavior is authored in Scala using SpinalHDL. Generated
Verilog is a build artifact. SpinalSim is the primary behavioral regression
environment before Gowin synthesis and hardware proof.

## Consequences

- No permanent generated-Verilog edits.
- Every FPGA feature requires SpinalHDL tests.
- Generator versions and commands are release-controlled.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-002-HOST-INDEPENDENT-LIBVDP.md

```md
# ADR-002 — `libvdp` is the universal host SDK

## Status

Accepted.

## Decision

The public SDK remains `libvdp` with the `vdp_*` prefix. Platform APIs are thin
layers such as `vdp_atarist_*` and `vdp_amiga_*`. A parallel `retro_vdp_*`
library will not be created.

## Consequences

Transport differences remain below the generic and platform APIs.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-003-VIDEO-ONLY-EMULATION.md

```md
# ADR-003 — FPGA emulates video hardware only

## Status

Accepted.

## Decision

The FPGA does not emulate complete CPUs or machines. Any host may supply
platform-native graphics memory, register state, and timed events.

## Consequences

Accuracy claims are visual-chipset claims, not complete-machine compatibility.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-004-PLATFORM-ADAPTER-MODEL.md

```md
# ADR-004 — Shared engines plus platform adapters

## Status

Accepted.

## Decision

Platforms reuse shared bitmap, planar, tile, sprite, palette, Copper, HDMA,
Blitter, compositor, scaler, and HDMI engines. Platform-specific logic is added
only where shared engines cannot represent required visual behavior.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-005-NO-AGA.md

```md
# ADR-005 — Amiga scope is OCS/ECS; AGA is deferred

## Status

Accepted.

## Decision

The initial Amiga visual adapter supports OCS/ECS features only. AGA, HAM8,
eight bitplanes, AGA palette/fetch/sprite behavior, and AGA timing are excluded.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-006-ONE-ACTIVE-SHARED-RTL-LANE.md

```md
# ADR-006 — One active shared RTL integration lane

## Status

Accepted.

## Decision

Only one lane may modify common top-level or shared timing/memory components at
a time. Parallel work is limited to research, documents, vectors, firmware-only
work, and isolated components that do not create integration conflicts.

```

## File: docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR_TEMPLATE.md

```md
# ADR-NNN — Decision title

- Status: proposed
- Date:
- Owners:
- Reviewers:

## Context

## Options considered

## Decision

## Technical rationale

## Consequences

## Migration and compatibility

## Affected specifications and tests

```

## File: docs/external_documentation_system/PROJECT_PLAN/DEPENDENCY_GRAPH.md

```md
# Dependency Graph

```text
FOUNDATION-0
    ↓
FOUNDATION-1 — shared Mode0 substrate
    ↓
FOUNDATION-2 — host-independent libvdp
    ↓
GENERIC-MODE0 closure
    ↓
ZX closure
    ↓
TMS9918A
    ↓
SMS / Game Gear
    ↓
NES
    ↓
C64
    ↓
Atari ST / STE
    ↓
Amiga OCS / ECS
    ↓
Mega Drive / Genesis
    ↓
SNES Modes 0–3-lite
```

Atari 2600 TIA depends on Foundation 1 and Foundation 2, but uses a dedicated
procedural scanline frontend. Its research and test-vector work may proceed in
parallel. Its shared-RTL integration waits for the active integration lane.

## Shared dependency matrix

| Capability | First lane requiring closure |
|---|---|
| Stable packed bitmap | Generic Mode0 |
| Stable tile/sprite substrate | TMS9918A |
| Native planar tile decode | NES |
| Raster-event automation | C64 |
| Interleaved framebuffer planar | Atari ST |
| Independent 1–6 plane pointers | Amiga |
| Complex layer priority | Genesis |
| Windows/color math/HDMA | SNES |
| Procedural beam-timed writes | Atari 2600 |

```

## File: docs/external_documentation_system/PROJECT_PLAN/DOCUMENT_OWNERSHIP.md

```md
# Document Ownership

| Subject | Authoritative artifact |
|---|---|
| Project state and next task | `PROJECT_PLAN/ACTIVE_LANE.md` |
| Overall sequence | `PROJECT_PLAN/MASTER_EXECUTION_PLAN.md` |
| Locked baseline | `PROJECT_PLAN/CURRENT_BASELINE.md` |
| Architecture decisions | ADR files |
| Register addresses/fields | authoritative register schema |
| FPGA behavior | approved component/platform specification plus SpinalHDL |
| Public firmware API | `libvdp` headers plus generated API docs |
| Build commands | runbooks |
| Expected test results | test plans/golden vectors |
| Actual test evidence | proof packets |
| Release versions and hashes | release manifest |

## Anti-drift rule

A document may summarize another authority, but it must link to it and must not
become a second manually maintained source of the same value.

```

## File: docs/external_documentation_system/PROJECT_PLAN/MASTER_EXECUTION_PLAN.md

```md
# Master Execution Plan

## Purpose

This is the project-control document. It answers:

- what is active;
- what is blocked;
- what must happen next;
- which specification governs the work;
- which evidence closes the lane.

Technical implementation detail belongs in the linked architecture, FPGA,
firmware, platform, test, and runbook documents.

## Product goal

Build a host-independent retro graphics coprocessor using SpinalHDL on the Tang
Nano 20K. Any supported host uses `libvdp` to configure Mode0 or a
platform-specific visual adapter. The FPGA emulates display hardware only.

## State model

1. BACKLOG
2. RESEARCH
3. SPEC REVIEW
4. DESIGN APPROVED
5. SPINALHDL IMPLEMENTATION
6. SPINALSIM PASS
7. FIRMWARE IMPLEMENTATION
8. SYNTHESIS PASS
9. HARDWARE PROOF
10. DOC/AUDIT
11. CLOSED
12. BLOCKED

No lane skips a state.

## Current program

### Active lane

`FOUNDATION-0 — Baseline and Contract Reconciliation`

See `ACTIVE_LANE.md`.

### Foundation gates

1. **Foundation 0:** lock source, build, hardware, transport, and current contracts.
2. **Foundation 1:** stabilize shared Mode0 engines and SpinalSim regression.
3. **Foundation 2:** make `libvdp` host-independent and ABI-aware.

### Platform sequence

1. Generic Mode0 closure
2. ZX Spectrum closure
3. TMS9918A
4. Sega Master System and Game Gear
5. NES/Famicom
6. Commodore 64 VIC-II
7. Atari ST/STE
8. Amiga OCS/ECS
9. Sega Mega Drive/Genesis
10. SNES Modes 0–3-lite
11. Atari 2600 TIA

Atari 2600 research may proceed early, but its dedicated scanline frontend must
not interrupt the shared RTL lane.

## Global definition of done

A lane closes only when:

- scope and non-goals are approved;
- SpinalHDL source and SpinalSim tests pass;
- generated Verilog is clean and unmodified;
- `libvdp` API and reference firmware build;
- Gowin synthesis and timing pass;
- matched firmware/bitstream hardware proof passes;
- documentation is synchronized;
- an independent reviewer signs the proof packet;
- regressions enter the standard suite.

## One-active-RTL-lane rule

Only one lane may modify common `VdpTop`, SDRAM arbitration, shared fetch
engines, compositor, Copper, HDMA, sprite substrate, or HDMI integration at a
time. Research, documentation, test-vector preparation, and firmware-only work
may proceed in parallel.

## Exact next task

Open `FOUNDATION-0-001` using the task template and populate
`CURRENT_BASELINE.md`. The next task is not selected until the active task's
proof packet is accepted.

```

## File: docs/external_documentation_system/PROJECT_PLAN/PLATFORM_STATUS.md

```md
# Platform Status

| Platform | State | Blocking dependency |
|---|---|---|
| Generic Mode0 | ACTIVE/BLOCKED | Foundation 0–2 |
| ZX Spectrum | BLOCKED | Generic Mode0 |
| TMS9918A | BLOCKED | ZX |
| SMS/Game Gear | BLOCKED | TMS9918A |
| NES | BLOCKED | Sega 8-bit |
| C64 | BLOCKED | NES |
| Atari ST/STE | BLOCKED | C64 |
| Amiga OCS/ECS | BLOCKED | Atari ST/STE |
| Mega Drive/Genesis | BLOCKED | Amiga |
| SNES Modes 0–3-lite | BLOCKED | Genesis |
| Atari 2600 TIA | BLOCKED; research parallel | Foundation 1–2 |

```

## File: docs/external_documentation_system/PROJECT_PLAN/REFERENCE_FULL_EXECUTION_PLAN.md

```md
# Reference Full Execution Plan

> This file preserves the previously generated all-in-one plan. It is a
> reference source, not the current navigation authority. The modular
> documents in this package own their respective subjects.

# spinalhdlVDP Full Project Execution Plan

**Project:** Universal host-independent retro video display processor  
**FPGA platform:** Tang Nano 20K  
**FPGA implementation language:** SpinalHDL / Scala  
**Host library:** `libvdp`  
**Primary host reference:** Must be confirmed and locked during Foundation Gate 0  
**Plan date:** 2026-07-25

> **Reproducibility status:** This document defines the complete engineering and
> governance process. A release is not reproducible until the exact values required
> by **Part VI — Reproducible Product Package** are populated, committed, and
> validated by a clean-room build. Placeholders, undocumented local settings, and
> verbal knowledge are release blockers.

---

## 1. Project Goal

Build one FPGA-based video coprocessor that any MCU, CPU, SBC, or custom computer can use through `libvdp` to render graphics using either:

1. the native generic Mode0 graphics model; or
2. a platform-specific visual adapter that reproduces the visible behavior, formats, limits, and raster effects of a historical video chipset.

The FPGA emulates **video hardware only**. It does not emulate the platform CPU, operating system, storage controller, audio subsystem, or complete computer/console.

The host supplies graphics memory, registers, commands, and optional beam-timed event programs. The FPGA owns deterministic fetch, decode, composition, scaling, and HDMI scanout.

### In scope

- Generic Mode0
- ZX Spectrum
- TMS9918A family
- Sega Master System
- Game Gear
- NES/Famicom
- Commodore 64 VIC-II visual profile
- Atari ST/STE visual profile
- Amiga OCS/ECS visual profile
- Sega Mega Drive/Genesis
- SNES modes 0–3 visual subset, with optional Mode 7 later
- Atari 2600 TIA visual profile

### Explicitly out of scope for this roadmap

- Full-machine CPU emulation in the FPGA
- Amiga AGA
- Cycle-exact shared-bus contention between CPU and video hardware
- Neo Geo as a full adapter target
- Complete SNES interlace and every undocumented hardware quirk
- Complete Atari ST, Amiga, C64, NES, or other machine cores

---

## 2. Architectural Rules

### 2.1 Source-of-truth order

When two artifacts disagree, use this order until the discrepancy is formally resolved:

1. Approved platform and Mode0 specification
2. SpinalHDL source
3. SpinalSim regression behavior
4. Register schema
5. `libvdp` public headers and implementations
6. Generated Verilog
7. Firmware examples
8. Captures and screenshots

Generated Verilog is a build artifact. Team members must not make permanent behavior changes by editing generated RTL directly.

### 2.2 SpinalHDL rule

All FPGA features must be implemented in Scala using SpinalHDL components, bundles, clocking areas, and simulation tests.

Every new hardware feature requires:

- a bounded SpinalHDL component or a clearly documented modification to an existing component;
- a SpinalSim unit test;
- a VdpTop integration test;
- generated Verilog regeneration;
- Gowin synthesis and timing review;
- Tang Nano 20K hardware proof.

### 2.3 Firmware rule

Reusable host behavior belongs in `libvdp`. Platform proof applications must remain thin wrappers.

The public layering is:

```text
Application
    ↓
Platform adapter API: vdp_zx_*, vdp_atarist_*, vdp_amiga_*, etc.
    ↓
Generic API: vdp_mode0_*, vdp_copper_*, vdp_status_*, vdp_upload_*
    ↓
Transport API: vdp_host_*, vdp_reg_*, vdp_sdram_*
    ↓
Transport backend: QSPI, i80, SPI, parallel, MMIO, or future bus
    ↓
FPGA
```

### 2.4 Platform-adapter rule

A platform adapter may:

- translate native platform registers into Mode0 registers;
- translate native memory layouts into shared fetch-engine configuration;
- enforce original platform limits;
- generate Copper, LINESTATE, or HDMA programs;
- expose platform-native palette helpers;
- add a small FPGA extension when generic Mode0 primitives cannot reproduce the visual behavior.

A platform adapter must not duplicate the whole compositor, scaler, palette RAM, host bridge, or HDMI output path.

### 2.5 One active RTL lane

Only one platform or shared-substrate RTL lane may modify `VdpTop.scala`, the SDRAM arbiter, common fetch engines, compositor, Copper, HDMA, or sprite substrate at a time.

Research, documentation, firmware-only work, and test-vector preparation may proceed in parallel, but common SpinalHDL integration is serialized.

---

## 3. Project State Model

Every work lane must have exactly one state:

1. **BACKLOG** — requested but not researched
2. **RESEARCH** — source material and visual requirements being collected
3. **SPEC REVIEW** — platform contract awaiting independent review
4. **DESIGN APPROVED** — register/memory/component design approved
5. **SPINALHDL IMPLEMENTATION** — Scala RTL being written
6. **SPINALSIM PASS** — unit and integration simulations green
7. **FIRMWARE IMPLEMENTATION** — `libvdp` and proof firmware being written
8. **SYNTHESIS PASS** — Verilog generated, Gowin synthesis and timing green
9. **HARDWARE PROOF** — authoritative board/host proof underway
10. **DOC/AUDIT** — independent code/spec/doc reconciliation
11. **CLOSED** — evidence packet accepted and regression added
12. **BLOCKED** — dependency, resource, or hardware issue prevents progress

No lane may skip a state. A lane may move backward when review finds a defect.

---

## 4. Definition of Done for Every Lane

A lane is complete only when all of these are true:

### Specification

- Platform scope and non-goals are explicit.
- Native registers, memory layouts, palette rules, sprites, scrolling, priority, borders, and raster effects are documented.
- The design identifies generic Mode0 reuse versus platform-specific FPGA logic.
- Register addresses and bit encodings are assigned or explicitly declared unnecessary.

### SpinalHDL

- Scala source compiles.
- No generated-Verilog-only edits exist.
- Unit SpinalSim passes all positive, boundary, reset, and negative cases.
- VdpTop integration regression passes.
- CDC, reset, pending/active register semantics, and memory arbitration are reviewed.

### Firmware

- `libvdp` contains the public API.
- No proof application hand-frames protocol packets that belong in `libvdp`.
- At least one reference application builds for the authoritative host.
- Platform-native asset conversion is documented or automated.
- Error and timeout behavior is tested.

### Build

- Generated Verilog is reproducible.
- Gowin synthesis completes without new critical warnings.
- Timing closes at the approved clocks.
- LUT, block RAM, DSP, PLL, and SDRAM bandwidth deltas are recorded.
- The bitstream hash and source commit are recorded.

### Hardware

- Firmware and bitstream artifact hashes are matched before testing.
- Transport health and upload status are clean.
- A monitor proof is captured.
- Capture-device output is considered secondary evidence.
- A minimum 10-minute static/animated soak passes; timing-sensitive lanes require a longer platform-specific soak.
- Reset, repeated mode switching, and cold boot are tested.

### Documentation and review

- Platform specification is current.
- Mode0 register documentation is current.
- `kb/libvdp/README.md` is current.
- Firmware build and flash instructions are current.
- An independent reviewer cross-checks spec, SpinalHDL, firmware, and proof packet.
- All new regressions are included in the standard test suite.

---

# PART I — FOUNDATION PROGRAM

## 5. Foundation Gate 0: Re-baseline the Current Repository

**This is the next project step. No new platform implementation begins before this gate closes.**

### 5.1 Freeze and identify the authoritative state

1. Freeze shared RTL and `libvdp` changes.
2. Record the current branch, commit, dirty files, generated-Verilog state, firmware commit, and flashed bitstream hash.
3. Identify the authoritative Tang Nano 20K top-level generator and constraint file.
4. Identify the authoritative host and transport used for acceptance testing.
5. Move historical hosts and experiments under clearly labeled archived/reference sections.
6. Create `PROJECT_PLAN/CURRENT_BASELINE.md` containing the locked artifacts and commands.

### 5.2 Resolve current contract conflicts

The team must produce one reconciliation PR covering:

- bitmap-format encoding;
- packed 1/2/4/8bpp target support versus current two-bit mode field;
- RGB565 and HAM6 allocation;
- planar plane count and plane-base behavior;
- Copper pixel-precise writes versus top-level write-drain gating;
- status and vblank behavior across QSPI and i80;
- upload-status clear semantics;
- active host selection;
- current authoritative QSPI clock limits and read/write rates;
- `mode0_regs.json`, SpinalHDL register decode, `vdp_mode0.h`, and documentation agreement.

### 5.3 Repair the build surfaces

- Make the Scala/SpinalHDL build command canonical and reproducible.
- Add one command to run all SpinalSim tests.
- Add one command to generate the production Verilog.
- Add one command to synthesize the Tang Nano 20K bitstream.
- Make `libvdp` CMake include all public implementation files.
- Add the authoritative host build system, including an ESP-IDF component path when ESP32-P4 remains canonical.
- Narrow `architectures=*` or prove every advertised platform build.

### 5.4 Gate 0 tests

- Full existing SpinalSim regression
- Clean generated-Verilog diff from a fresh checkout
- Clean Gowin synthesis
- Generic Mode0 hardware scene
- QSPI/i80 register write/read test as applicable
- SDRAM bulk upload/readback test
- Bitmap 1bpp, 2bpp, direct color, and any currently claimed additional modes
- Copper WAIT/WRITE/JUMP/SKIP test
- Sprite and collision test
- Reset and mode-select test

### Gate 0 exit criteria

- One baseline commit is named.
- One authoritative host and transport are named.
- One bitmap-format map is named.
- One planar-plane-count contract is named.
- One standard regression command is named.
- One standard bitstream build command is named.
- All documents and public headers agree.

---

## 6. Foundation Gate 1: Stabilize the Shared Mode0 Substrate

### 6.1 Required SpinalHDL component boundaries

The shared design should expose or converge toward these bounded components:

```text
VdpTop
├── HostInterface / register bridge
├── Mode0RegisterFile
├── SdramArbiter
├── BitmapLineFetch
├── PlanarLineFetch
├── TileMapFetch L0–L3
├── SpriteEvaluator / SpriteRasterizer
├── Copper
├── HDMA / LINESTATE
├── DmaEngine
├── BlitterEngine
├── PlatformAdapterMux
├── FourLayerCompositor
├── PaletteRam
├── WindowUnit / ColorMath
├── LogicalScaler
└── VideoTiming / HDMI output
```

Platform adapters should live under a dedicated Scala namespace, for example:

```text
hw/spinal/spinalhdlvdp/adapters/
    ZXSpectrumAdapter.scala
    Tms9918Adapter.scala
    SmsAdapter.scala
    NesAdapter.scala
    C64VicIIAdapter.scala
    AtariStAdapter.scala
    AmigaOcsAdapter.scala
    GenesisAdapter.scala
    SnesAdapter.scala
    Atari2600TiaAdapter.scala
```

### 6.2 Shared format target

The final generic bitmap target is:

- packed indexed 1bpp;
- packed indexed 2bpp;
- packed indexed 4bpp;
- indexed 8bpp;
- RGB565 direct color.

HAM6 is an Amiga compatibility decoder, not a generic packed BPP alias.

The team must expand or redesign the bitmap format register because a two-bit field cannot unambiguously encode all five generic formats plus special compatibility decoders.

### 6.3 Shared planar target

- One to six planes
- Independent plane bases
- Configurable line stride/modulo
- Configurable plane ordering
- Interleaved-word mode for Atari ST
- Independent-plane mode for Amiga
- Common output as palette index plus metadata

### 6.4 Shared timing automation target

- Copper WAIT(Y)
- Copper WAIT(X,Y)
- Register WRITE and WRITE_SEQ
- JUMP and SKIP
- Atomic inactive-bank program upload and vblank swap
- HDMA/LINESTATE per-line update table
- Late-event and underrun status

### 6.5 Shared simulation suite

The foundation suite must include:

- `Mode0RegisterFileSim`
- `BitmapFetchSim` for every format
- `PlanarLineFetchSim` for 1–6 planes and both memory layouts
- `SdramArbiterSim` with refresh and worst-case concurrent clients
- `FetchSlotSchedulerSim`
- `CopperSim`
- `HdmaSim`
- `SpriteEvaluatorSim`
- `SpriteSubstrateSim`
- `SpriteCollisionSim`
- `FourLayerCompositorSim`
- `WindowColorMathSim`
- `ModeSelectSim`
- `SoftResetSim`
- `VdpTopRegressionSim`
- continuous scanout plus host-write stress simulation

### Gate 1 exit criteria

- Generic substrate features are documented and stable.
- Platform adapters can be selected without replacing the common output pipeline.
- Resource budget leaves agreed headroom for platform adapters.
- Shared regressions are green before and after every adapter lane.

---

## 7. Foundation Gate 2: Make `libvdp` Truly Host-Independent

### 7.1 Public API layers

Keep the `vdp_` prefix. Do not introduce a second `retro_vdp_*` API.

Split implementation concerns into:

```text
libvdp/core/
    vdp_mode0.c
    vdp_copper.c
    vdp_status.c
    vdp_upload.c

libvdp/transports/
    vdp_transport_qspi_p4.c
    vdp_transport_i80_s3.c
    vdp_transport_pio_pico.c
    vdp_transport_spi_legacy.c
    future vdp_transport_mmio.c

libvdp/adapters/
    vdp_zx.c
    vdp_tms9918.c
    vdp_sms.c
    vdp_nes.c
    vdp_c64.c
    vdp_atarist.c
    vdp_amiga.c
    vdp_genesis.c
    vdp_snes.c
    vdp_atari2600.c
```

A transport operations structure is recommended so the generic library does not grow one large conditional implementation file.

### 7.2 Required common API additions

- ABI and capability query
- Adapter mask query
- SDRAM size query
- Transport feature query
- Atomic configuration commit
- Backend-independent vblank wait
- Backend-independent register read
- Upload completion and error status
- Explicit transport timeout handling
- Exact active bitstream ABI check during initialization

### 7.3 Host matrix

Every release records support as one of:

- authoritative;
- tested;
- builds only;
- archived;
- unsupported.

No metadata may claim universal architecture support unless the CI/build matrix proves it.

### Gate 2 exit criteria

- The authoritative host uses `libvdp`, not a standalone raw proof implementation.
- All active transports share the same public API semantics.
- Platform adapters compile against the same headers.
- Vblank and status behavior is transport neutral.

---

# PART II — STANDARD PLATFORM LANE

## 8. Required Workflow for Every Platform

### Step 1 — Research packet

Create or update `PROJECT_PLAN/platform_specs/<PLATFORM>_VIDEO_SPEC.md`.

It must cover:

1. visible resolutions and refresh families;
2. memory layout;
3. tile/bitmap/text modes;
4. palette format;
5. sprites;
6. scrolling;
7. priority;
8. borders/windows;
9. raster effects;
10. collisions/status;
11. exact versus visual-only behaviors;
12. Mode0 reuse;
13. required new FPGA logic;
14. resource risks;
15. verification references.

### Step 2 — Design checkpoint

Produce a design packet containing:

- SpinalHDL components touched;
- new bundles and signals;
- registers and memory map;
- reset and commit timing;
- CDC crossings;
- SDRAM clients and bandwidth;
- `libvdp` API;
- proof scene;
- simulation cases;
- acceptance criteria;
- deferred behavior.

No code begins before independent design approval.

### Step 3 — SpinalHDL unit implementation

Implement the smallest standalone adapter or extension first. Do not start with VdpTop integration.

### Step 4 — SpinalSim unit proof

Test:

- reset defaults;
- legal modes;
- boundary values;
- invalid writes;
- timing transitions;
- memory layout;
- color decode;
- priority;
- status and collision behavior;
- backward compatibility.

### Step 5 — VdpTop integration

Connect the adapter through `PlatformAdapterMux` or the agreed adapter control path. Run the complete shared regression.

### Step 6 — Register and documentation sync

Update in one change:

- register schema;
- Mode0 register bus specification;
- `vdp_mode0.h` or platform header;
- `kb/libvdp/README.md`;
- platform specification.

### Step 7 — Firmware adapter

Create `vdp_<platform>.h/.c` with platform-native helpers. Add conversion tools and one minimal proof application.

### Step 8 — Synthesis gate

Generate Verilog, synthesize, record resource/timing deltas, and confirm no critical warnings.

### Step 9 — Hardware proof

Use an authoritative firmware/bitstream pair. Capture:

- console logs;
- register/readback evidence;
- transport health;
- raw test assets and hashes;
- monitor photo or direct proof;
- optional capture-device output;
- soak results.

### Step 10 — Independent audit

A reviewer who did not write the primary implementation compares:

- platform spec;
- SpinalHDL;
- generated RTL interface;
- register schema;
- `libvdp`;
- proof firmware;
- evidence.

### Step 11 — Closeout

Merge only after the closeout packet names:

- commits;
- tests;
- bitstream hash;
- firmware hash;
- known limitations;
- regression names;
- next platform lane.

---

# PART III — PLATFORM ROADMAP

## 9. Platform Sequence

The implementation order is dependency-driven:

1. Generic Mode0 closure
2. ZX Spectrum closure
3. TMS9918A
4. Sega Master System and Game Gear
5. NES/Famicom
6. Commodore 64
7. Atari ST/STE
8. Amiga OCS/ECS
9. Mega Drive/Genesis
10. SNES modes 0–3-lite
11. Atari 2600 TIA

Atari 2600 research may run earlier, but its procedural scanline engine is a separate architecture and should not interrupt shared substrate work.

---

## 10. Generic Mode0

### Goal

Provide the stable, host-independent graphics card API used by all applications and adapters.

### FPGA/SpinalHDL work

- Reconcile bitmap formats.
- Finish packed 1/2/4/8bpp and RGB565.
- Finish one-to-six-plane planar engine.
- Stabilize four background layers.
- Stabilize 32-sprite-per-line ceiling and documented total descriptors.
- Stabilize Copper, HDMA, LINESTATE, windows, color math, DMA, and Blitter.
- Add capability registers and ABI version.
- Add late-event, upload, and underrun diagnostics.

### Firmware work

- Complete `libvdp` build and transport abstraction.
- Add capability query and ABI guard.
- Add structured configuration objects.
- Add asset tools for every generic format.
- Add atomic commit helpers.

### Test proof

- One scene for every bitmap format.
- Four-layer scene.
- 32-sprite stress scene.
- Copper raster bars.
- HDMA per-line scroll.
- Blitter fill/copy/line.
- repeated mode-switch and soft-reset soak.

### Exit criteria

No platform adapter depends on undocumented raw register writes.

---

## 11. ZX Spectrum

### Current direction

Treat the existing ZX v1 implementation as a lane requiring closure and re-baselining, not a new design.

### Visual target

- 256×192 1bpp bitmap
- 32×24 attribute cells
- ink, paper, bright, and flash
- border color
- Spectrum memory addressing/shuffle
- visible attribute clash

### FPGA/SpinalHDL work

- Re-run `ZXSpectrumAdapterSim` against the reconciled baseline.
- Confirm shuffled bitmap addressing in the production path.
- Confirm flash cadence and reset.
- Confirm border changes at approved timing boundaries.
- Add a direct attribute-clash regression.

### Firmware work

- Add `vdp_zx_init`, screen upload, attribute upload, border, flash, and present helpers.
- Add a Spectrum memory-layout converter.

### Required proof scene

One scene must intentionally place conflicting colored shapes inside the same 8×8 cell so attribute clash is undeniable.

### Exit criteria

- Existing v1 behavior is preserved.
- Attribute clash, flash, border, and shuffled addressing are independently proven.

---

## 12. TMS9918A Family

### Visual target

- Graphics I
- Graphics II
- Text mode
- Multicolor mode
- fixed 16-color palette
- hardware sprites and overflow/collision flags

### FPGA/SpinalHDL work

- Create `Tms9918Adapter.scala`.
- Map name, pattern, and color tables to shared tile/attribute fetch.
- Implement TMS sprite limits and status semantics.
- Reuse generic palette RAM with fixed palette preload.

### Firmware work

- Build `vdp_tms9918_*` helpers.
- Reuse the existing fixed-palette loader.
- Add table upload helpers matching native TMS table concepts.

### Tests

- All four display modes.
- sprite overflow and collision.
- table-base changes.
- transparent color behavior.

### Exit criteria

A host can program the FPGA using TMS-style name/pattern/color/sprite tables without constructing generic Mode0 descriptors manually.

---

## 13. Sega Master System and Game Gear

### Visual target

- 4bpp tile graphics
- scrollable background
- sprite layer
- tile priority and flips
- SMS CRAM palette
- Game Gear 12-bit palette and viewport

### FPGA/SpinalHDL work

- Create a shared Sega 8-bit adapter with SMS and GG flags.
- Implement native tilemap entry decode.
- Enforce sprite-per-line and overflow behavior.
- Implement top-row/right-column scrolling locks if included in the agreed visual scope.
- Implement Game Gear crop/window behavior.

### Firmware work

- `vdp_sms_*` and `vdp_gamegear_*` APIs.
- Reuse existing SMS and GG palette conversion helpers.
- Native VRAM/CRAM upload helpers.

### Tests

- tile priority and flips;
- scrolling;
- sprite limit and overflow;
- palette conversion;
- SMS full frame and GG viewport.

### Exit criteria

SMS and GG share one verified FPGA substrate while presenting separate native firmware APIs.

---

## 14. NES/Famicom

### Visual target

- 256×240/224 presentation
- 2bpp planar pattern tables
- nametables and attribute tables
- fine scrolling
- 64 sprites, 8 per scanline
- sprite 0 hit
- sprite/background priority

### FPGA/SpinalHDL work

- `NesAdapter.scala`.
- Native 2bpp planar tile decode.
- nametable and attribute quadrant decode.
- OAM translation into the shared sprite evaluator.
- hard 8-sprites-per-line visual limit.
- sprite 0 hit and overflow status.
- mirroring/scroll mapping at the adapter level.

### Firmware work

- PPU-style memory region helpers.
- CHR, nametable, attribute, palette, and OAM uploads.
- scroll and mask/control helpers.

### Tests

- 2bpp decode;
- attribute quadrants;
- all mirroring selections in scope;
- 8/9 sprite boundary;
- sprite 0 hit positive and negative cases;
- left-edge clipping and priority.

### Exit criteria

A host can create a visually NES-like screen by supplying PPU-formatted tables and OAM.

---

## 15. Commodore 64 VIC-II

### Visual target

- standard text
- multicolor text
- standard bitmap
- multicolor bitmap
- extended background color mode if retained
- 8 sprites
- raster-controlled changes
- border
- sprite collisions

### FPGA/SpinalHDL work

- `C64VicIIAdapter.scala`.
- character, screen, color, and bitmap memory mapping.
- multicolor decode.
- C64 sprite width/double-size/multicolor behavior.
- collision/status mapping.
- raster event hooks through Copper/HDMA.
- visual bad-line behavior only where it affects output; do not model CPU cycle stealing.

### Firmware work

- C64-native VIC register helpers.
- charset, screen RAM, color RAM, bitmap, and sprite uploads.
- raster program builder.

### Tests

- each display mode;
- multicolor bit grouping;
- border and raster color bars;
- eight sprites and multiplex-style Copper updates;
- sprite/sprite and sprite/background collision.

### Exit criteria

The visual result and register-facing model reproduce VIC-II display behavior without claiming CPU-bus timing accuracy.

---

## 16. Atari ST/STE

### Visual target

Phase 1:

- ST low: 320×200, four interleaved bitplanes, 16 colors
- RGB333 palette
- border and integer scaling
- raster palette changes

Phase 2:

- ST medium: 640×200, two planes
- ST high: 640×400, monochrome
- STE RGB444 palette
- selected STE scrolling/display extensions

### FPGA/SpinalHDL work

- `AtariStAdapter.scala`.
- Interleaved-word planar fetch mode.
- one, two, and four plane selection.
- correct bit significance and word order.
- border/display window.
- palette writes through Copper at approved X/Y precision.

### Firmware work

- `vdp_atarist_*` and optional `vdp_atariste_*` helpers.
- native 32 KB screen upload.
- packed-to-ST-planar conversion tool.
- reuse existing ST and STE palette helpers.

### Tests

- known 16-pixel plane-word vectors;
- low/medium/high modes;
- palette conversion;
- border;
- raster bars;
- double-buffer swap;
- full-screen and dirty-region upload.

### Exit criteria

A generic host can upload authentic ST screen data and visually reproduce all three standard ST display modes.

---

## 17. Amiga OCS/ECS — No AGA

### Visual target

Core:

- 1–6 independent bitplanes
- lores and hires
- 32-color palette
- dual playfield
- 8 OCS-style sprite channels
- attached sprites
- Copper beam-synchronous changes
- display and fetch windows
- odd/even modulo
- basic Blitter copy/fill/line

Extended:

- EHB
- HAM6
- selected ECS display positioning

Explicit exclusions:

- AGA
- HAM8
- 8 bitplanes
- AGA palette behavior
- AGA sprites/fetch modes
- cycle-exact Agnus DMA contention

### FPGA/SpinalHDL work

- `AmigaOcsAdapter.scala`.
- independent plane pointers and odd/even modulo.
- one-to-six-plane fetch.
- dual-playfield split and priority.
- Amiga sprite channel restrictions and attached-pair mode.
- DIW/DDF-like window mapping.
- Copper register mapping.
- EHB decoder.
- HAM6 stateful line decoder with correct line reset.
- basic Blitter mapping to shared Blitter engine.

### Firmware work

- `vdp_amiga_*` API.
- plane pointer, modulo, display/fetch window, color, sprite, and Copper helpers.
- native planar asset conversion.
- Copper list builder using existing opcode helpers.

### Tests

- 1 through 6 plane decode;
- independent plane-base proof;
- odd/even modulo;
- dual playfield transparency and priority;
- 8 sprites and attached pairs;
- Copper mid-line palette and scroll changes;
- EHB half-bright result;
- HAM6 direct and modify operations plus line reset;
- Blitter-generated proof image.

### Exit criteria

The FPGA can be used as an OCS/ECS-style visual chipset from any host while making no AGA or cycle-exact DMA claim.

---

## 18. Sega Mega Drive/Genesis

### Visual target

- Plane A
- Plane B
- Window plane
- 4bpp tiles
- per-tile priority and flips
- full, per-row, and selected per-line horizontal scroll
- full and column vertical scroll
- sprite system
- 64-entry CRAM
- shadow/highlight

### FPGA/SpinalHDL work

- `GenesisAdapter.scala`.
- native name-table entry decode.
- plane/window selection.
- horizontal and vertical scroll-table fetch.
- priority resolver matching the agreed visual model.
- sprite chain/table translation.
- shadow/highlight post-compositor operation.

### Firmware work

- VRAM, CRAM, VSRAM helpers.
- plane, window, sprite, and scroll configuration.
- palette conversion.

### Tests

- A/B/window priority combinations;
- per-tile priority;
- all supported scroll modes;
- sprite boundary and overflow;
- shadow/highlight;
- 320- and 256-wide output modes if retained.

### Exit criteria

The adapter reproduces the standard Genesis visual organization without emulating its complete FIFO timing or CPU interface.

---

## 19. SNES Modes 0–3-lite

### Visual target

Required first release:

- modes 0–3 only
- up to four backgrounds
- 2bpp, 4bpp, and 8bpp tile decode as required by selected modes
- 128 sprite descriptors and up to the approved 32 sprites per line
- windows and masks
- color math
- per-line HDMA-style changes
- mode-specific priority

Optional later release:

- Mode 7

Deferred:

- interlace
- every mosaic/offset-per-tile corner case
- full cycle-accurate PPU behavior

### FPGA/SpinalHDL work

- `SnesAdapter.scala`.
- mode configuration and layer BPP mapping.
- native tilemap decode.
- priority resolver.
- OAM-to-shared-sprite mapping.
- window/mask mapping.
- color math mapping.
- HDMA table builder/consumer mapping.
- optional Mode 7 through the shared affine engine after modes 0–3 close.

### Firmware work

- mode, BG, tilemap, palette, OAM, window, color-math, and HDMA helpers.
- native planar tile conversion.

### Tests

- each supported mode;
- four-layer priority;
- all tile depths;
- 32/33 sprite boundary and tile-budget overflow;
- window combinations;
- add/sub/half color math;
- per-line HDMA changes;
- optional Mode 7 affine scene.

### Exit criteria

Modes 0–3-lite close before any Mode 7 lane opens.

---

## 20. Atari 2600 TIA

### Visual target

- 20-bit playfield reflected or repeated
- two players
- two missiles
- ball
- color registers
- horizontal positioning and motion
- size/copy controls
- priority and collisions
- beam-synchronous register changes

### Architectural note

TIA is not naturally a framebuffer or tile adapter. It is a procedural scanline generator. It should be implemented as a dedicated frontend that feeds the common compositor/output pipeline.

### FPGA/SpinalHDL work

- `Atari2600TiaAdapter.scala`.
- scanline register state.
- playfield/player/missile/ball generators.
- horizontal motion and copy/size decode.
- priority and collision logic.
- Copper/command-list integration for timed writes.

### Firmware work

- TIA-register API.
- scanline command-list builder.
- optional helper that converts a simple image into playfield/player events.

### Tests

- playfield reflection/repetition;
- player copy/size modes;
- missile and ball;
- horizontal motion;
- priority modes;
- collision latches;
- timed mid-line register changes.

### Exit criteria

A host can construct a frame by submitting TIA-style timed register activity without streaming HDMI pixels.

---

# PART IV — TEST, REVIEW, AND RELEASE SYSTEM

## 21. Continuous Integration

Every pull request that touches FPGA, registers, or `libvdp` must run:

1. Scala formatting and compile
2. All SpinalSim unit tests
3. VdpTop regression
4. Verilog generation from a clean tree
5. generated-interface consistency check
6. firmware compile matrix
7. register-schema/header consistency generator or checker
8. documentation link and command validation

Nightly or release CI should additionally run:

- Gowin synthesis;
- timing and resource report diff;
- long randomized SDRAM/host-write simulation;
- adapter regression suite;
- firmware static analysis.

## 22. Hardware Proof Standard

Every hardware proof packet must contain:

```text
FPGA source commit:
Generated Verilog hash:
Bitstream hash:
Firmware source commit:
Firmware binary hash:
Board:
Host:
Transport:
Transport frequency:
Display/capture path:
Test asset hashes:
Cold boots:
Warm resets:
Mode switches:
Soak duration:
Transport status:
Known artifacts:
Result:
```

A capture-device artifact alone cannot fail or pass an RTL lane. Serial readback, transport status, repeat-frame hashes, and a direct monitor check take precedence.

## 23. Review Roles

Every lane names these owners before work starts:

- **Lane owner:** accountable for scope and completion
- **SpinalHDL implementer:** writes Scala RTL and simulations
- **Firmware implementer:** writes `libvdp` and proof application
- **Specification reviewer:** verifies historical/platform behavior
- **RTL reviewer:** checks SpinalHDL structure, timing, CDC, reset, and synthesis implications
- **Firmware reviewer:** checks API, transport, timeout, and build behavior
- **Hardware validator:** flashes and records evidence
- **Documentation auditor:** reconciles every public document and command
- **Project manager:** accepts checkpoints and opens the next lane

The same person may fill multiple roles, but the primary implementer may not be the only reviewer.

## 24. Change-Control Rules

- Register addresses are not assigned informally in source code.
- Any register change updates schema, SpinalHDL, firmware header, API documentation, and regression in one lane.
- Any behavior change touching a shared primitive reruns all closed platform regressions.
- Any transport timing change requires a dedicated hardware validation packet.
- Any generated Verilog change without a corresponding SpinalHDL change is rejected.
- Any proof without matched firmware and bitstream hashes is invalid.
- Deferred features remain documented as deferred and are not described as supported.

---

# PART V — IMMEDIATE EXECUTION QUEUE

## 25. Exact Next Steps

The team should execute the following in order:

### Task 1 — Open Foundation Gate 0

Create one lane named:

```text
FOUNDATION-0 — Baseline and Contract Reconciliation
```

### Task 2 — Produce current-state manifest

Record source, generated RTL, bitstream, firmware, board, host, transport, toolchain, and test commands.

### Task 3 — Resolve bitmap-format contract

Decide and document the final generic format encoding for 1/2/4/8bpp and RGB565. Move HAM6 to the Amiga compatibility path.

### Task 4 — Resolve planar contract

Name the supported plane count, independent bases, interleaved mode, modulo/stride, and common output representation.

### Task 5 — Resolve Copper timing contract

Make pixel-precise WAIT/WRITE behavior agree between Copper, VdpTop drain logic, simulations, firmware, and documentation.

### Task 6 — Resolve authoritative host and transport

Make firmware documentation, `libvdp`, build files, and hardware proof process name one current authoritative path.

### Task 7 — Repair `libvdp` builds

Include all public sources and add the authoritative host build integration.

### Task 8 — Run and lock the baseline regression

Publish the command, results, synthesis report, and bitstream hash.

### Task 9 — Close Generic Mode0 gaps

Complete shared format, planar, capability, status, and commit semantics.

### Task 10 — Close ZX Spectrum

Re-verify existing v1 and add the explicit attribute-clash hardware proof.

### Task 11 onward

Open platform lanes in this order:

```text
TMS9918A
SMS/Game Gear
NES
C64
Atari ST/STE
Amiga OCS/ECS
Mega Drive/Genesis
SNES modes 0–3-lite
Atari 2600 TIA
```

The next lane is always the first item in this list whose shared dependencies are CLOSED. A later platform may not bypass an earlier platform merely because its proof scene is easier.

---

## 26. Release Milestones

### Release 0.9 — Stable generic VDP

- Gate 0–2 complete
- Mode0 stable
- host-independent `libvdp`
- ZX closed

### Release 1.0 — 8-bit visual systems

- TMS9918A
- SMS/Game Gear
- NES
- C64

### Release 1.5 — 16-bit computer visuals

- Atari ST/STE
- Amiga OCS/ECS, no AGA

### Release 2.0 — 16-bit console visuals

- Mega Drive/Genesis
- SNES modes 0–3-lite

### Release 2.5 — Procedural raster profile

- Atari 2600 TIA

---

## 27. Final Project Rule

At every point, the team should be able to answer these questions from the repository without asking another person:

1. What is the active lane?
2. What state is it in?
3. What commit is authoritative?
4. What test must run next?
5. What evidence is required to move forward?
6. Who reviews it?
7. Which document must be updated?
8. What platform opens after it closes?

If any answer is missing, the lane is not ready to advance.

---

# PART VI — REPRODUCIBLE PRODUCT PACKAGE

## 28. Reproducibility Standard

The project is considered reproducible only when an independent team, starting
with a clean supported workstation and the released repository, can produce the
same functional product without contacting the original implementers.

There are two levels of reproducibility:

1. **Functional reproducibility**
   - The independently built FPGA bitstream and firmware pass the same acceptance
     tests and produce the same defined visual behavior.
   - Tool-generated binary hashes may differ only when the vendor toolchain is
     nondeterministic and the difference is documented and reviewed.

2. **Artifact reproducibility**
   - The independently built generated RTL, bitstream, firmware binary, generated
     headers, asset binaries, and test vectors match the published hashes.
   - This is the preferred release standard.

A release must explicitly state which level it achieves. It must not claim
artifact reproducibility when only functional equivalence has been demonstrated.

## 29. Mandatory Repository Layout

The release repository must contain, or clearly map to, the following logical
structure. Existing names may be retained, but the documentation must identify
the exact equivalents.

```text
/
├── README.md
├── LICENSE
├── CHANGELOG.md
├── RELEASE_MANIFEST.yaml
├── REPRODUCIBILITY.md
├── AGENTS.md
├── PROJECT_PLAN/
│   ├── CURRENT_BASELINE.md
│   ├── ACTIVE_LANE.md
│   ├── DECISIONS/
│   ├── platform_specs/
│   ├── test_plans/
│   ├── proof_packets/
│   └── release_checklists/
├── hw/
│   ├── spinal/
│   │   ├── build.sbt
│   │   ├── project/
│   │   ├── src/main/scala/
│   │   └── src/test/scala/
│   ├── generated/
│   ├── gowin/
│   │   ├── constraints/
│   │   ├── project/
│   │   └── scripts/
│   └── reports/
├── firmware/
│   ├── libvdp/
│   ├── reference_apps/
│   ├── transports/
│   └── platform_examples/
├── tools/
│   ├── asset_converters/
│   ├── register_generator/
│   ├── test_vector_generator/
│   └── capture_validation/
├── tests/
│   ├── golden/
│   ├── assets/
│   ├── expected/
│   ├── hardware/
│   └── clean_room/
├── docs/
│   ├── hardware/
│   ├── protocol/
│   ├── mode0/
│   ├── libvdp/
│   ├── platforms/
│   ├── build/
│   ├── test/
│   └── troubleshooting/
└── ci/
    ├── scripts/
    ├── containers/
    └── workflows/
```

Every top-level directory must have a short README explaining its ownership,
inputs, generated outputs, and whether files are hand-maintained or generated.

## 30. Exact Hardware Definition

A repeatable product requires an exact hardware package. The release must include:

### 30.1 Bill of materials

For every supported hardware configuration:

- FPGA board manufacturer and exact model;
- FPGA device and package;
- board revision;
- onboard SDRAM manufacturer/part when known;
- host board manufacturer, model, and revision;
- level shifters, resistors, connectors, cables, and adapters;
- power-supply voltage and minimum current;
- HDMI adapter or connector details;
- optional capture device used for secondary validation.

Substitutions must be classified as:

- equivalent and validated;
- expected compatible but unvalidated;
- unsupported.

### 30.2 Wiring definition

The hardware documentation must include:

- one canonical connection table;
- a schematic or wiring diagram;
- FPGA package pin;
- board header pin;
- host GPIO number;
- signal direction;
- idle level;
- voltage domain;
- pull-up/pull-down requirements;
- maximum validated clock;
- wire-length and grounding limits;
- signals that must not float;
- reset and boot sequencing.

The pinout must be machine-readable in a checked-in file such as:

```yaml
signals:
  - name: HOST_D0
    fpga_pin: "<LOCKED>"
    fpga_header: "<LOCKED>"
    host_gpio: "<LOCKED>"
    direction: bidirectional
    voltage: 3.3
    idle: 0
```

### 30.3 Board-specific electrical setup

Document all board-specific requirements, including:

- I/O bank voltages;
- required LDO or power-domain initialization;
- drive strength;
- slew rate;
- pull configuration;
- clock source and PLL input;
- reset polarity;
- JTAG/programming interface;
- safe power-on and power-off order.

No electrical prerequisite may exist only in a team message or engineer notebook.

## 31. Locked Toolchain and Build Environment

The release must lock every tool that can affect generated output.

### 31.1 Required version record

Record exact versions and acquisition method for:

- operating system and architecture;
- Java/JDK;
- Scala;
- sbt;
- SpinalHDL;
- SpinalSim;
- simulator backend and version;
- Verilator or other simulator;
- Gowin EDA edition and version;
- device database;
- Python;
- C/C++ compiler;
- CMake;
- Ninja or Make;
- Pico SDK, Arduino core, ESP-IDF, or other host SDK;
- host flashing tools;
- serial tools;
- hashing utilities;
- asset-conversion dependencies.

Use a lock file, container image, Nix/Devbox definition, or equivalent. A prose
version list alone is not enough when dependencies can drift.

### 31.2 Canonical environment

Provide at least one supported clean environment:

- pinned container image; or
- reproducible VM image with documented checksum; or
- scripted host setup with locked package versions.

The environment must not depend on undeclared files from a developer home
directory.

### 31.3 Vendor-tool exception

If Gowin EDA cannot legally or technically be redistributed:

- record the exact installer filename and checksum;
- record the official acquisition location;
- document installation options and license requirements;
- provide a script that verifies the installed version;
- archive project scripts, constraints, device selection, and synthesis options.

## 32. Canonical Commands

`REPRODUCIBILITY.md` must provide copy-and-paste commands for a clean build.

At minimum:

```text
bootstrap environment
verify tool versions
clean repository outputs
format/check Scala
compile SpinalHDL
run all SpinalSim tests
generate Verilog
verify generated interface
run register/schema generator
build every supported libvdp target
build every reference firmware
run software unit tests
synthesize Tang Nano 20K bitstream
extract timing/resource reports
program FPGA
flash authoritative host
run hardware acceptance suite
collect proof packet
build release archive
verify release hashes
```

Each command must state:

- working directory;
- required environment variables;
- expected exit code;
- expected output files;
- expected important console markers;
- approximate resource requirements, not as a promise but for planning;
- whether network access is required.

A command that exists only in CI is insufficient; CI must call the same checked-in
script that developers run locally.

## 33. SpinalHDL Reproducibility Contract

### 33.1 Source ownership

- Scala/SpinalHDL is the only editable FPGA behavioral source.
- Generated Verilog is created in a clean output directory.
- Generated files contain a generator version header.
- Permanent edits to generated RTL are forbidden and checked by CI.

### 33.2 Generator entry points

Document the exact Scala main classes or sbt tasks for:

- production top generation;
- simulation-only tops;
- diagnostic bitstreams;
- register/header generation;
- optional platform-specific debug variants.

Each generated top must name:

- target board;
- clock frequencies;
- reset assumptions;
- enabled adapters;
- feature flags;
- output directory.

### 33.3 Clock and reset specification

The release must contain a clock/reset table:

| Domain | Source | Frequency | Reset | Crossing rules |
|---|---|---:|---|---|
| Host | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| Pixel | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| SDRAM | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| HDMI/TMDS | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |

For every crossing, document the synchronizer, FIFO, handshake, or ownership rule.

### 33.4 Memory and arbitration specification

Document:

- SDRAM geometry and addressing;
- byte/word endianness;
- burst rules;
- refresh interval;
- client list;
- arbitration order;
- maximum service latency;
- line-buffer depth;
- prefetch deadlines;
- underrun behavior;
- host-write behavior during active scanout;
- bank ownership and completion protocol.

A platform lane may not rely on an undocumented memory timing assumption.

### 33.5 Resource budget

Maintain a checked-in budget containing:

- current LUT usage;
- block RAM usage;
- DSP usage;
- PLL usage;
- I/O usage;
- worst negative slack;
- maximum supported clock;
- reserved headroom per future lane.

Every synthesis report is compared against the approved budget. Threshold
violations block merging unless a design decision explicitly changes the budget.

## 34. Host Protocol and `libvdp` ABI Contract

The host-facing interface must be documented independently of any transport.

### 34.1 Protocol definition

Specify:

- command opcode table;
- command and response framing;
- address width;
- length encoding;
- byte order;
- CRC/parity behavior;
- command atomicity;
- burst auto-increment behavior;
- timeout behavior;
- invalid-command response;
- reset recovery;
- read turnaround;
- transport-specific idle and chip-select rules.

Every packet example must include both logical fields and exact wire bytes.

### 34.2 Register map

The register map must be generated from one authoritative schema.

Generation must produce:

- SpinalHDL constants or decode data;
- C headers;
- human-readable documentation;
- optional Rust or other language bindings;
- reset-value test vectors.

CI must fail when generated outputs are stale.

### 34.3 ABI and capability discovery

A host must be able to read:

- magic value;
- ABI major/minor;
- feature bitmap;
- adapter bitmap;
- SDRAM size;
- maximum logical resolution;
- maximum sprite count;
- supported bitmap formats;
- supported planar layouts;
- supported transport features.

`vdp_host_init()` or its successor must reject an incompatible major ABI.

### 34.4 `libvdp` portability

For each supported host, record:

- SDK and version;
- compiler flags;
- transport backend;
- pin map;
- validated clock;
- read support;
- interrupt support;
- DMA support;
- maximum validated transaction;
- known limitations.

The release support table must use only:

- authoritative;
- tested;
- build-only;
- experimental;
- archived;
- unsupported.

## 35. Mode0 Technical Product Specification

The release must include one normative Mode0 specification that defines:

- output timing and HDMI mode;
- logical coordinate system;
- scaling and centering;
- backdrop and border behavior;
- layer count and ordering;
- tilemap formats;
- tile pattern formats;
- bitmap formats;
- planar formats;
- palette format;
- sprite descriptor layout;
- sprite limits;
- windows and masks;
- color math;
- affine behavior;
- Copper instruction set;
- HDMA and LINESTATE tables;
- DMA and Blitter behavior;
- status, collision, overflow, late-event, and underrun flags;
- active/pending register commit boundaries;
- reset values;
- unsupported and reserved encodings.

Every field must state whether it takes effect:

- immediately;
- at an H boundary;
- at a scanline boundary;
- at vblank;
- on explicit commit;
- after engine completion.

## 36. Per-Platform Reproducibility Package

Each platform lane must produce a complete package, not only code.

Required path:

```text
docs/platforms/<platform>/
├── VIDEO_SPEC.md
├── REGISTER_MAPPING.md
├── MEMORY_LAYOUT.md
├── TIMING_MODEL.md
├── LIMITATIONS.md
├── BUILD_AND_RUN.md
├── TEST_PLAN.md
├── GOLDEN_VECTORS.md
└── REFERENCES.md
```

### 36.1 Normative video specification

For the supported visual subset, document:

- native terminology;
- supported modes;
- exact dimensions;
- pixel aspect and display scaling policy;
- palette encoding;
- memory organization;
- tile/bitmap/character decode;
- sprite rules;
- priority;
- scrolling;
- border/window behavior;
- raster effects;
- collision/status behavior;
- reset state;
- undefined or intentionally simplified behavior.

Clearly distinguish:

- exact behavior;
- visually equivalent behavior;
- approximated behavior;
- deferred behavior;
- unsupported behavior.

### 36.2 Platform-to-Mode0 mapping

Provide a table for every native feature:

| Native feature | Mode0/shared implementation | New FPGA logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| `<feature>` | `<component/register>` | `<component or none>` | `<API/helper>` | exact/visual/approx |

### 36.3 Exact memory examples

Include worked binary examples showing:

- source platform bytes;
- FPGA SDRAM placement;
- register configuration;
- decoded pixel indices;
- final RGB result.

At least one example must be hand-checkable.

### 36.4 Public firmware API

Document every `vdp_<platform>_*` function:

- parameters;
- valid ranges;
- required call order;
- memory ownership;
- synchronous/asynchronous behavior;
- timeouts;
- errors;
- thread/interrupt safety;
- example use.

### 36.5 Golden vectors

Every platform must publish:

- smallest legal scene;
- normal representative scene;
- boundary/stress scene;
- intentional invalid-input scene;
- raster-effect scene when applicable;
- expected line/frame hashes or pixel dumps;
- expected status flags;
- source asset hashes.

## 37. Platform-Specific Mandatory Coverage

The following are minimum technical deliverables for each planned platform.

### 37.1 ZX Spectrum

- canonical 6144-byte bitmap addressing;
- 768-byte attribute addressing;
- ink/paper/bright/flash truth table;
- flash period definition;
- border timing boundary;
- golden attribute-clash scene;
- exact host upload layout.

### 37.2 TMS9918A

- supported screen modes;
- pattern, color, name, and sprite table layouts;
- fixed palette values and provenance;
- fifth-sprite and collision behavior selected for the visual model;
- sprite size/magnification rules;
- backdrop and transparency behavior.

### 37.3 Sega Master System and Game Gear

- 4bpp tile bitplane order;
- tilemap entry format;
- scroll modes;
- column-0 blanking decision;
- sprite table layout and limits;
- SMS CRAM and Game Gear CRAM conversion;
- priority and transparency truth tables.

### 37.4 NES/Famicom

- pattern-table format;
- nametable and attribute decoding;
- fine/coarse scroll mapping;
- mirroring policy;
- sprite OAM layout;
- eight-sprites-per-line behavior;
- sprite-zero-hit visual model;
- clipping and palette mirroring rules;
- documented exclusions from cycle-exact PPU behavior.

### 37.5 Commodore 64 VIC-II

- text, multicolor text, bitmap, multicolor bitmap, and extended-color modes selected;
- character/bitmap/color RAM layouts;
- bad-line behavior included or explicitly excluded;
- sprite expansion, multicolor, priority, and collision;
- border opening approximation policy;
- raster-register timing model;
- PAL/NTSC visual timing policy.

### 37.6 Atari ST/STE

- low, medium, and high-resolution layouts;
- interleaved 16-pixel planar word examples;
- screen-base and stride alignment;
- ST RGB333 and STE palette mapping;
- border and overscan policy;
- raster palette-change timing;
- STE fine-scroll and line-width scope;
- explicit statement that CPU/GLUE/MMU timing is not emulated.

### 37.7 Amiga OCS/ECS

- one-to-six independent bitplanes;
- bitplane pointers and odd/even modulo;
- fetch and display window model;
- lores and hires policy;
- dual-playfield plane assignment and priority;
- EHB truth table;
- HAM6 direct/modify truth table and line reset;
- eight sprite channels and attached-pair behavior;
- Copper-supported register set and timing;
- Blitter subset mapping;
- explicit no-AGA statement;
- explicit exclusions from cycle-exact chip-bus contention.

### 37.8 Mega Drive/Genesis

- Plane A, Plane B, and Window layouts;
- 4bpp tile order;
- CRAM and VSRAM layout;
- horizontal and vertical scroll modes;
- sprite-link table and limits;
- complete supported priority truth table;
- shadow/highlight behavior;
- 256/320-width policy.

### 37.9 SNES Modes 0–3-lite

- per-mode background count and BPP;
- native planar tile layout;
- tilemap entry format;
- OAM mapping and approved per-line limits;
- priority tables;
- window/mask combination;
- add/sub/half color-math truth tables;
- HDMA mapping;
- explicit deferred interlace, edge cases, and Mode 7 status.

### 37.10 Atari 2600 TIA

- procedural scanline command format;
- playfield reflection/repetition;
- player copy/size behavior;
- missile and ball behavior;
- horizontal motion;
- priority truth table;
- collision latch matrix;
- beam-coordinate write scheduling;
- maximum command rate and late-command behavior.

## 38. Test Oracle and Acceptance Thresholds

Tests must define expected results, not only actions.

### 38.1 Simulation oracle

Each simulation records:

- deterministic seed;
- input vector hash;
- expected transaction trace;
- expected pixel/line/frame hash;
- expected status flags;
- maximum permitted latency;
- expected assertion count of zero.

Randomized tests publish failing seeds and retain them as fixed regressions.

### 38.2 Synthesis oracle

A synthesis pass requires:

- correct device and package;
- no unconstrained primary clock;
- no failed timing domain;
- no critical warnings unless explicitly waived;
- resource use below the approved budget;
- generated report archived with hash.

### 38.3 Hardware oracle

Each hardware test defines:

- exact firmware and bitstream hashes;
- exact asset hashes;
- expected boot log markers;
- expected register values;
- expected status counters;
- expected frame or scanline hashes when available;
- allowed visual tolerance;
- duration;
- reset count;
- pass/fail rule.

Words such as “looks right,” “seems stable,” or “mostly works” are not acceptance criteria.

### 38.4 Visual comparison

When exact frame capture is possible:

- use lossless capture;
- document RGB/YUV conversion;
- compare active area only unless borders are under test;
- publish exact or tolerance-based pixel comparison;
- record the tolerance and rationale.

When capture hardware is not trustworthy:

- use internal pixel-stream hashing or test-port readback;
- supplement with direct monitor confirmation;
- classify photographs as supporting evidence only.

## 39. Clean-Room Reproduction Procedure

Before a milestone release, a person or team not involved in the primary
implementation must perform this procedure:

1. Acquire the released repository and verify its source archive hash.
2. Acquire or build the documented environment.
3. Run the tool-version verification script.
4. Assemble the documented hardware from the BOM and wiring package.
5. Run the repository clean check.
6. Run the complete SpinalHDL/SpinalSim suite.
7. Generate Verilog.
8. Compare generated interface and expected generated-file hashes.
9. Run register/header generation and stale-file checks.
10. Build the complete firmware matrix.
11. Synthesize the production bitstream.
12. Compare timing and resource results with allowed ranges.
13. Flash the FPGA and authoritative host.
14. Run generic Mode0 hardware acceptance.
15. Run every closed platform acceptance suite.
16. Collect a new proof packet.
17. Compare expected hashes, counters, images, and logs.
18. Record every deviation.
19. Sign the clean-room report.
20. Block release until deviations are resolved or formally accepted.

The clean-room report becomes part of the release archive.

## 40. Procedural Work Package Template

Every task opened from this plan must contain:

```text
Task ID:
Lane:
State:
Owner:
Reviewers:
Dependency commits:
Goal:
Non-goals:
Files allowed to change:
SpinalHDL components:
Firmware components:
Registers/memory affected:
Documentation affected:
Test vectors:
Simulation commands:
Expected simulation results:
Synthesis command:
Resource/timing thresholds:
Hardware setup:
Firmware/bitstream pair:
Hardware test commands:
Expected hardware results:
Evidence path:
Rollback plan:
Known risks:
Definition of done:
Next task after closure:
```

A task without this information remains in BACKLOG or RESEARCH.

## 41. Decision and Deviation Management

### 41.1 Architecture decisions

Every material decision receives a checked-in ADR containing:

- context;
- options considered;
- decision;
- technical rationale;
- consequences;
- affected specifications;
- migration plan;
- reviewers;
- date and commit.

### 41.2 Deviations

Any mismatch from the reproducibility package must be recorded as:

- expected nondeterminism;
- supported alternative;
- temporary waiver;
- defect.

A waiver must include an owner and expiration milestone. Permanent undocumented
deviations are forbidden.

## 42. Failure Recovery and Troubleshooting

The documentation must include decision trees for:

- Scala or sbt dependency failure;
- SpinalHDL generation failure;
- SpinalSim mismatch;
- generated RTL drift;
- Gowin synthesis failure;
- timing regression;
- FPGA programming failure;
- no HDMI output;
- unstable HDMI output;
- host initialization failure;
- register write/read mismatch;
- SDRAM upload error;
- CRC/parity error;
- vblank wait timeout;
- Copper late event;
- line-buffer underrun;
- sprite overflow;
- transport signal-integrity failure;
- incorrect platform visual result.

Each entry must include:

- observable symptoms;
- diagnostic commands;
- known-good expected values;
- likely causes;
- safe corrective actions;
- evidence to collect before escalation.

## 43. Release Archive Contents

Every milestone release archive must contain:

```text
source archive + hash
release manifest
toolchain/version manifest
BOM and wiring revision
SpinalHDL sources
generated Verilog
register schema and generated bindings
Gowin project/scripts/constraints
synthesis, timing, and resource reports
production bitstream + hash
libvdp sources and built libraries
reference firmware sources and binaries + hashes
asset converters
test assets and golden results
simulation reports
hardware proof packets
clean-room reproduction report
known limitations
migration notes
license and third-party notices
```

## 44. Release Sign-Off

A release is approved only when the following signatures are recorded:

- architecture/specification;
- SpinalHDL/RTL;
- firmware/`libvdp`;
- hardware validation;
- documentation;
- clean-room reproduction;
- release manager.

No individual may provide every signature.

## 45. Reproducibility Exit Questions

Before marking any lane or release CLOSED, the reviewer must answer:

1. Can a new engineer identify the exact source commit?
2. Can they install or acquire the exact toolchain?
3. Can they assemble the exact supported hardware?
4. Can they run one canonical command per build stage?
5. Can they regenerate RTL without editing generated files?
6. Can they synthesize with the same device, constraints, and settings?
7. Can they build and flash the same host firmware?
8. Can they identify the exact protocol and register ABI?
9. Can they reproduce every claimed visual platform behavior?
10. Can they compare their result against objective expected outputs?
11. Can they diagnose a failure without private team knowledge?
12. Can they produce a release proof packet with matched hashes?

Any “no” blocks closure.

```

## File: docs/external_documentation_system/PROJECT_PLAN/RELEASE_MILESTONES.md

```md
# Release Milestones

## 0.9 — Stable generic VDP

- Foundation gates closed
- Generic Mode0 closed
- authoritative host and transport
- complete `libvdp` baseline
- reproducible SpinalHDL, firmware, and bitstream build
- clean-room proof

## 1.0 — 8-bit visual systems

- ZX Spectrum
- TMS9918A
- SMS/Game Gear
- NES
- C64

## 1.5 — 16-bit computer visuals

- Atari ST/STE
- Amiga OCS/ECS
- no AGA

## 2.0 — 16-bit console visuals

- Mega Drive/Genesis
- SNES Modes 0–3-lite

## 2.5 — Procedural raster visual system

- Atari 2600 TIA

Every milestone has its own release manifest, proof packet, clean-room report,
known-limitations statement, and migration notes.

```

## File: docs/external_documentation_system/PROJECT_PLAN/TASKS/TASK_TEMPLATE.md

```md
# Task Work Package

- **Task ID:**
- **Lane:**
- **State:**
- **Owner:**
- **Independent reviewers:**
- **Dependency commits:**

## Goal

## Non-goals

## Approved specifications

## Files allowed to change

## SpinalHDL components

## Firmware components

## Registers and memory affected

## Documentation affected

## Test vectors

## Simulation commands and expected results

## Synthesis command and thresholds

## Hardware setup and artifacts

## Hardware commands and expected results

## Evidence path

## Risks and rollback

## Definition of done

## Exact next task after closure

```

## File: docs/external_documentation_system/README.md

```md
> **Reference snapshot — not canonical live state.** This documentation system
> was delivered by an external reviewer on 2026-07-25. Per CoralReef's review
> (`PROJECT_PLAN/external_docs_system_review.md`), it is kept as a reference
> snapshot. Live project state remains in repository-root `STATUS.md`.

# spinalhdlVDP Documentation System (Reference Snapshot)

This directory is a modular documentation system delivered by an external
reviewer. It is **not** the canonical live authority for the project.

The product is a host-independent FPGA video coprocessor implemented in
SpinalHDL on the Tang Nano 20K. A host MCU, CPU, SBC, or custom machine uses
`libvdp` to supply graphics memory and video state. The FPGA emulates video
hardware only and owns deterministic rendering, scaling, and HDMI scanout.

## Start here

1. Read `PROJECT_PLAN/ACTIVE_LANE.md`.
2. Read `PROJECT_PLAN/MASTER_EXECUTION_PLAN.md`.
3. Open the work package for the active task.
4. Follow the linked technical specification and runbook.
5. Store actual evidence in a proof packet.
6. Do not start the next lane until the current exit gate is signed.

## Documentation levels

- **Project control:** current state, sequencing, dependencies, releases.
- **Architecture:** system-wide technical contracts.
- **FPGA:** SpinalHDL component specifications and verification expectations.
- **Firmware:** `libvdp`, ABI, transport, and host-porting contracts.
- **Platforms:** one repeatable work package per visual platform.
- **Runbooks:** exact commands and operational procedures.
- **Testing:** objective test oracles, evidence, and clean-room reproduction.
- **Proof packets:** actual logs, hashes, reports, and approvals.

## Authority rule

No fact should be manually maintained in multiple authoritative locations.

- Register addresses: register schema.
- FPGA behavior: approved SpinalHDL/component specification.
- Public C API: headers plus generated API reference.
- Current project status: `PROJECT_PLAN/ACTIVE_LANE.md`.
- Build commands: runbooks.
- Expected results: test specifications and golden vectors.
- Actual results: proof packets.
- Release hashes and tool versions: release manifest.

## Current active lane

`FOUNDATION-0 — Baseline and Contract Reconciliation`

No new platform RTL lane starts until Foundation Gate 0 closes.

```

## File: docs/firmware/HOST_TRANSPORT_ABI.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Host Transport ABI

**Status:** draft  
**Owner:** `BronzeGate`  
**Reviewer:** `TopazCliff`, `BrightForge`

## Scope

Host-facing transport protocol for configuring the Tang Nano 20K VDP.

## Canonical host path

- **QSPI / ESP32-P4** is the canonical Tang Nano 20K host path.
- Components: `QspiSlave` / `QspiDecoder` / `QspiSdramBridge`.
- Historical i80/ESP32-S3 and legacy SPI paths are retired as primary targets.

## Transport rate

- **4 MHz** is the canonical bulk SDRAM upload clock for the current wiring.
- Legacy SPI contract: 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.

## Transport health

- Health selector: active word-drain `sel=0x0A`.
- `raw=0x00000000`, `overflow=0`, `malformed=0` expected in clean operation.
- Silent value corruption (no health flag) is a known SI-margin risk at higher
  rates; mitigated by 4 MHz canonical rate.

## Public API

Reusable host logic belongs in `firmware/libvdp/`. Applications remain thin
wrappers.

## Bitmap/indexed `SCALE_CTRL` semantics (P3b interface checkpoint)

BronzeGate concurs with BrightForge's **Option B — Compose**, pending PM and
CyanPeak review. The existing bitmap/indexed path's fixed 2× source-to-display
mapping remains part of the contract; `SCALE_CTRL` applies to the logical
coordinates before bitmap fetch. Therefore a bitmap's effective display scale
per axis is `2 × SCALE_CTRL` (subject to the active-display clamp), and the
default `scaleX=1`, `scaleY=1` remains byte-identical to the HW-proven
`a5a047a2` path.

Consequences for host code:

- A full 320×240 bitmap at `scaleX=scaleY=2` requests a 4× effective image and
  is larger than the 640×480 active area; use `LOGIC_WIDTH`/`LOGIC_HEIGHT` to
  crop the logical source before scaling when a zoomed image should fit.
- Write logical dimensions before `SCALE_CTRL`, using the existing
  `vdp_mode0_set_scale_mode()` helper or the equivalent two helpers.
- No new register, command, or libvdp helper is required. Existing scale
  fields retain their encoding; this section defines their bitmap/indexed
  fetch-side meaning for P3b.
- This semantic decision does not authorize RTL changes by itself. BrightForge
  must complete the checkpoint review, co-sim, and PnR gates before the fetch
  path changes.

## References

- `firmware/libvdp/`
- `firmware/GOTCHAS.md`
- `STATUS.md` lanes `QSPI-SI-CEILING-183`, `HAM6 removal + 2bpp indexed replacement`

```

## File: docs/firmware/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Firmware Documentation

`libvdp`, ABI, transport, and host-porting contracts.

Each document covers one boundary or concept and includes:
- API boundary;
- semantics;
- errors/timeouts;
- blocking/asynchronous behavior;
- transport limitations;
- compatibility;
- build/test targets;
- examples.

The implementation authority is `firmware/libvdp/`.

```

## File: docs/fpga/BITMAP_ENGINE.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Bitmap Engine

**Status:** draft — under active development in `2bpp-bank-completion-rtl`  
**Owner:** `BrightForge`  
**Reviewer:** `TopazCliff`

## Scope

Bitmap row fetch, line-buffer bank rotation, and display-bank completion for
the Tang Nano 20K VDP.

## SpinalHDL components

- `BitmapRowFetch.scala`
- `VdpTop.scala` (bitmap fetch control integration)

## Clock/reset domain

- Pixel clock domain for display consumption.
- SDRAM clock domain for row fetch.
- CDC between SDRAM and pixel domains.

## Current behavior

- 3-bank line-buffer rotation.
- `fillLine` triggers fetch of the next display row.
- Production uses `fillLine` with depth = 3-bank machinery.
- `bestDv == 3` is the canonical line-doubling offset (ROW-CODED assertion).

## Open hardening — bank completion

The `2bpp-backlog-cosim` result (`5efe049`) shows:

- **Nominal:** zero display-bank violations.
- **Forced-late:** stale-row detector fires (`grantOverflow=25`, wrong-row
  `214/480`) on the current no-`bankReady` design.

Required hardening (tracked in `STATUS.md` lane `2bpp-bank-completion-rtl`):

- pixel-domain completion tokens;
- `bankReady` + `bankRowTag` state;
- display-bank rotation gated on valid + matching tag;
- diagnostics counters: `displayUnderflow`, `grantOverflow`, `rowTagMismatch`.

## Assertions / coverage

- `Indexed2bppBacklogCoSim` must pass nominal and forced-late modes.
- Forced-late must fail before hardening and pass afterward.

## Limitations

- Current design does not explicitly gate bank rotation on completion.
- Pending BrightForge implementation.

```

## File: docs/fpga/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# FPGA Documentation

SpinalHDL component specifications and verification expectations.

Each document covers one component or subsystem and includes:
- scope;
- SpinalHDL component names;
- interfaces;
- clock/reset domain;
- memory behavior;
- latency;
- commit timing;
- errors/status;
- assertions;
- SpinalSim coverage;
- synthesis/resource limits;
- hardware proof;
- limitations.

Editable source lives in `hw/spinal/spinalhdlvdp/`. Generated Verilog under
`hw/gen/` is a build artifact.

```

## File: docs/reproducibility/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Reproducibility

Release manifests and clean-room build requirements.

A release is not reproducible until the exact values required for source,
SpinalHDL generation, synthesis, firmware build, and hardware proof are
populated, committed, and validated.

```

## File: docs/runbooks/BUILD_LIBVDP.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Build libvdp

**Owner:** `BronzeGate`  
**Status:** template — requires validation for CMake target

## Working directory

`/home/itadmin/github/spinalhdlVDP/firmware/libvdp`

## Prerequisites

- Platform toolchain installed (ESP-IDF v6.0.2 for ESP32-P4).

## Command

```bash
# TBD — validate with BronzeGate
```

## Expected outputs

- Compiled `libvdp` object files / static library.

## Pass/fail criteria

- Build exits 0 with no warnings treated as errors.

## Evidence to save

- Build log.
- Commit hash.

```

## File: docs/runbooks/BUILD_REFERENCE_FIRMWARE.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Build Reference Firmware

**Owner:** `BronzeGate`  
**Status:** template — requires validation per project

## Working directory

`/home/itadmin/github/spinalhdlVDP/firmware/<PROJECT>`

## Prerequisites

- ESP-IDF v6.0.2 sourced.

## Command

```bash
idf.py build
```

## Expected outputs

- `build/<project>.elf`
- `build/<project>.bin`
- `build/partition_table/partition-table.bin`

## Pass/fail criteria

- Exit code 0.

## Evidence to save

- ELF SHA-256.
- BIN SHA-256.
- Partition-table SHA-256.
- Source commit.

```

## File: docs/runbooks/BUILD_RELEASE.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Build Release

**Owner:** `TopazCliff`  
**Status:** template — requires validation

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Procedure

1. Confirm all active lanes closed.
2. Record source commit.
3. Generate Verilog.
4. Synthesize bitstream.
5. Build firmware.
6. Run full hardware regression.
7. Populate release manifest.

## Command

```bash
# TBD — validate with BrightForge and BronzeGate
```

## Evidence to save

- Release manifest YAML.
- All artifact hashes.
- Proof packets for every lane.

```

## File: docs/runbooks/COSIM_VALIDATION.md

```md
# Runbook: Co-Simulation Validation

**Owner:** BrightForge  
**Reviewer:** CyanPeak (spec), CoralReef (reproducibility)  
**Scope:** SpinalSim co-simulation for VDP RTL, including the 2bpp backlog cosim used as the pass/fail gate for `2bpp-bank-completion-rtl`.

## Prerequisites

- JDK 17+ and `sbt` installed.
- Repository branch `brightforge/ham-decoder-171` checked out.
- Clean working tree or any local changes documented.

## Running a 2bpp co-simulation

```bash
cd /home/itadmin/github/spinalhdlVDP
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

## Expected results

### Nominal mode

- `bestDv == 3`
- `grantOverflow == 0`
- `displayUnderflow == 0`
- Wrong-row count ≤ startup allowance documented in the test plan.

### Forced-late mode

- With the **current** design (no `bankReady`/`bankRowTag` hardening), the run must show:
  - elevated `grantOverflow`
  - elevated wrong-row / incomplete-bank count
  - `displayUnderflow` may remain zero (the detector fires on stale row tags, not pixel starvation)
- After hardening, the same forced-late stimulus must return to clean counts.

## Capturing evidence

1. Save the console log as `cosim_log.txt`.
2. Compute SHA-256: `sha256sum cosim_log.txt > cosim_log.sha256`.
3. Copy both files into `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`.

## Failure handling

- If nominal mode shows violations, stop and report to TopazCliff before any RTL change.
- If forced-late mode does **not** fire the detector on the unhardened design, the testbench or stimulus is suspect; do not proceed with RTL hardening.

## Tool versions to record

- SpinalHDL version from `build.sbt` or `hw/spinal/build.sbt`.
- sbt version.
- JDK version (`java -version`).

```

## File: docs/runbooks/FLASH_REFERENCE_HOST.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Flash Reference Host

**Owner:** `BronzeGate`  
**Status:** template — requires validation for target host

## Working directory

`/home/itadmin/github/spinalhdlVDP/firmware/<PROJECT>`

## Prerequisites

- Reference firmware built.
- Host board connected.

## Command

```bash
idf.py flash
```

## Expected outputs

- ESP-IDF writes and verifies flash.

## Pass/fail criteria

- Exit code 0.
- Verify passes.

## Evidence to save

- Flash log.
- Firmware hashes.

```

## File: docs/runbooks/GENERATE_VERILOG.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Generate Verilog

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Command

```bash
make gen
```

Equivalently, from repo root:

```bash
sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"
```

## Expected outputs

- `hw/gen/top_tang20k.v` regenerated.
- Hash changes only reflect source changes.

## Pass/fail criteria

- `sbt` exits 0.
- No manual edits to `hw/gen/top_tang20k.v`.

## Evidence to save

- Source commit.
- `sha256sum hw/gen/top_tang20k.v`.

```

## File: docs/runbooks/PROGRAM_FPGA.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Program FPGA (SRAM)

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Prerequisites

- Synthesized bitstream available.
- Tang Nano 20K connected.

## Command

```bash
make prog
```

## Expected outputs

- `openFPGALoader` reports success.
- FPGA reconfigured.

## Pass/fail criteria

- Exit code 0.
- Verify step passes if `--verify` used.

## Evidence to save

- Bitstream hash.
- Loader output.
- Board/wiring revision.

## Note

SRAM load is volatile; persistent flash uses `make flash`.

```

## File: docs/runbooks/README.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbooks

Exact commands and operational procedures.

Each runbook must be validated by its owner from a clean state and include:
- owner;
- supported environment;
- working directory;
- prerequisites;
- exact commands;
- environment variables;
- expected outputs;
- expected console markers;
- pass/fail criteria;
- common failures;
- evidence to save;
- recovery/rollback.

```

## File: docs/runbooks/RUN_HARDWARE_REGRESSION.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Run Hardware Regression

**Owner:** `BrightForge` + `BronzeGate`  
**Status:** template — procedure varies by lane

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Prerequisites

- Matched bitstream and firmware committed.
- FPGA programmed or flashed.
- Host board flashed.

## Procedure

1. Record bitstream hash.
2. Record firmware ELF/BIN/partition hashes.
3. Power-cycle or reset host.
4. Run lane-specific test sequence.
5. Capture serial output / captures.
6. Verify health flags and PASS markers.

## Pass/fail criteria

- Lane-specific oracle passes.
- Health flags clear.

## Evidence to save

- Serial log.
- Capture hashes.
- Proof packet under `PROJECT_PLAN/proof_packets/<LANE>/`.

```

## File: docs/runbooks/RUN_SPINALSIM.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Run SpinalSim

**Owner:** `BrightForge`  
**Status:** draft — commands need validation against current test main

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Command

```bash
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

Replace `Indexed2bppBacklogCoSim` with the target simulation main.

## Expected outputs

- Console PASS/FAIL marker.
- `simWorkspace/` generated artifacts.

## Pass/fail criteria

- Exit code 0.
- Expected counters/behavior match test oracle.

## Evidence to save

- Console log.
- Sim commit hash.

```

## File: docs/runbooks/SETUP_DEVELOPMENT_ENVIRONMENT.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Set Up Development Environment

**Owner:** `BrightForge` / `BronzeGate`  
**Status:** template — requires validation

## Supported environment

- Linux workstation.
- Java / sbt for SpinalHDL.
- Gowin IDE for synthesis.
- ESP-IDF v6.0.2 for ESP32-P4 firmware.
- `openFPGALoader` for FPGA programming.

## Prerequisites

- `sbt` installed.
- `GOWIN_HOME` set or `gw_sh` on PATH.
- `openFPGALoader` installed.
- ESP-IDF v6.0.2 environment sourced.

## Commands

```bash
# Verify sbt
cd /home/itadmin/github/spinalhdlVDP
sbt about

# Verify Gowin
gw_sh --version

# Verify openFPGALoader
openFPGALoader --help

# Verify ESP-IDF
idf.py --version
```

## Expected output

`sbt about` reports Scala 2.13.14 and SpinalHDL 1.12.3.

## Common failures

- Missing `GOWIN_HOME` — set or ensure `gw_sh` is on PATH.
- Wrong ESP-IDF version — source v6.0.2.

## Evidence to save

- Tool version output.

```

## File: docs/runbooks/SYNTHESIZE_TANG_NANO_20K.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Synthesize Tang Nano 20K

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Prerequisites

- Generated Verilog up to date (`make gen`).
- `GOWIN_HOME` set or `gw_sh` on PATH.

## Command

```bash
make
```

## Expected outputs

- `fpga/tang20k/impl/pnr/project.fs`
- Gowin timing/resource reports under `fpga/tang20k/impl/`.

## Pass/fail criteria

- `make` exits 0.
- TNS = 0 (or documented exception).

## Evidence to save

- Bitstream SHA-256.
- Timing/resource summary.
- Source commit.

```

## File: docs/testing/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Testing Documentation

Objective test oracles, evidence requirements, and clean-room reproduction.

Each test plan includes:
- Test ID;
- Requirement IDs;
- Owner;
- Environment;
- Source commit;
- Input asset/vector and hash;
- Initial state;
- Command;
- Expected trace;
- Expected pixel/line/frame result;
- Expected status;
- Maximum latency/time;
- Pass/fail rule;
- Evidence path.

Actual results belong in `PROJECT_PLAN/proof_packets/<LANE>/`.

```

## File: docs/testing/TEST_PLAN_TEMPLATE.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Test Plan Template

## Test metadata

- **Test ID:**
- **Requirement IDs:**
- **Owner:**
- **Environment:**
- **Source commit:**

## Input

- Input asset/vector and hash:
- Initial state:

## Procedure

- Command:
- Expected trace:
- Expected pixel/line/frame result:
- Expected status:
- Maximum latency/time:

## Pass/fail rule

## Evidence path

`PROJECT_PLAN/proof_packets/<LANE>/`

```

## File: docs/testing/TP-2bpp-backlog-cosim.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# TP-2bpp-backlog-cosim — Continuous Scanout Bank-Completion Gate

**Owner:** `BrightForge`  
**Lane:** `2bpp-backlog-cosim` / `2bpp-bank-completion-rtl`  
**Environment:** SpinalSim, continuous pixel + SDRAM clocks, realistic latency/refresh  
**Source commit:** `5efe049` (cosim); current HEAD for hardening

## Procedure

```bash
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

## Nominal mode expected results

- `bestDv == 3`
- wrong-row events ≤ documented startup slack
- `grantOverflow == 0`
- `displayUnderflow == 0`
- `rowTagMismatch == 0` (gate idle in nominal — the fetch always keeps up)
- `malformed == 0`
- max fetch span within source-row budget

## Forced-late mode expected results (before hardening)

- Detector fires: `grantOverflow > 0` or wrong-row count increases sharply.
- Demonstrates incomplete-bank display hazard is reachable.

## Forced-late mode expected results (after hardening)

- `displayUnderflow == 0`
- `malformed == 0`
- No torn or stale display banks (gate holds on a non-consecutive tag rather than presenting a partial row).
- `rowTagMismatch` may be non-zero because it counts intentional gate-hold events when the next consecutive row is not yet complete; this is expected, not a failure.
- Wrong-row events within startup slack only.

## Pass/fail rule

- Nominal must be clean.
- Forced-late must fail before `bankReady`/row-tag hardening and pass afterward.

## Evidence path

`PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`

```

## File: docs/troubleshooting/README.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Troubleshooting

Known issues, diagnostic steps, and recovery procedures.

Each entry references the governing specification and the relevant runbook.
Do not duplicate live status or proof results.

```

## File: firmware/esp32p4_scaler_proof/CMakeLists.txt

```txt
cmake_minimum_required(VERSION 3.16)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(esp32p4_scaler_proof)

```

## File: firmware/esp32p4_scaler_proof/partitions.csv

```csv
# Name,   Type, SubType, Offset,  Size, Flags
nvs,      data, nvs,     0x9000,  0x6000,
phy_init, data, phy,     0xf000,  0x1000,
factory,  app,  factory, 0x10000, 0x400000,

```

## File: firmware/esp32p4_scaler_proof/sdkconfig.defaults

```defaults
CONFIG_IDF_TARGET="esp32p4"
CONFIG_ESP32P4_SELECTS_REV_LESS_V3=y
CONFIG_ESP32P4_REV_MIN_100=y
CONFIG_ESP32P4_REV_MIN_FULL=100
CONFIG_ESP_REV_MIN_FULL=100
CONFIG_ESP32P4_REV_MAX_FULL=199
CONFIG_ESP_REV_MAX_FULL=199
CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ_360=y
CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ=360
CONFIG_ESPTOOLPY_FLASHSIZE_32MB=y
CONFIG_ESPTOOLPY_FLASHSIZE="32MB"
CONFIG_PARTITION_TABLE_CUSTOM=y
CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions.csv"

```

## File: firmware/GOTCHAS.md

```md
# firmware/GOTCHAS.md

Firmware-specific pitfalls, proven fixes, and contract deviations for the
`spinalhdlVDP` host driver library.

## legacy SPI Transport

### GOTCHA-1: ESP32 / ESP8266 SCK speed is ~500 kHz (bit-bang)

**Deviation:** The locked legacy SPI contract specifies 2 MHz SCK. The Arduino
bit-bang implementation for ESP32 and ESP8266 achieves only ~500 kHz due to
digitalWrite overhead.

**Why it is tolerated:**
- The VDP-side state machine tracks absolute microseconds for CS hold (10 µs)
  and OSR drain (20 µs), not SCK edge counts.
- Bench testing with Sc45, Sc62, and Task 55 scenarios on both ESP32 and
  ESP8266 produced correct HDMI output.
- The VDP legacy SPI receiver is a shift-register with no minimum frequency spec
  other than "fast enough to complete before the next VDP operation."

**Risk:**
- Very long bursts (>1 ms total legacy SPI active time) may span multiple scanlines
  and interact with VDP scanline deadlines.
- Mitigation: keep individual legacy SPI bursts under 256 bytes unless explicitly
  validated on hardware.

**Fix status:** Documented. No code change required.

---

### GOTCHA-2: Pico PIO legacy SPI runs at 2 MHz (native)

**Fact:** The Pico RP2350 uses a PIO state machine for legacy SPI, achieving the full
2 MHz contract speed. This is the reference implementation.

**Implication:** When validating timing-sensitive scenarios, use the Pico as the
authoritative host. ESP32/ESP8266 bit-bang results are valid for functional
correctness but not for timing margin characterization.

---

### GOTCHA-3: CS hold time must be absolute, not cycle-counted

**Rule:** Always maintain CS_N low for at least 10 µs after the final SCK edge.
Do not compute this as "N clock cycles" because host SCK rates vary by platform.

**Implementation:**
- Pico: PIO program uses `delay` sideset for microsecond-level hold.
- ESP32/ESP8266: `vdp_legacySpi.c` uses `delayMicroseconds(10)` after the final bit.

---

## Host Platform Fidelity

### FIDELITY-1: Authoritative vs Functional Host

- **Authoritative:** ESP32-S3 (i80 parallel). Native GPIO fast toggling. Deterministic timing. **Required for audit sign-off.**
- **Functional / Legacy:** Pico 2 (RP2350) PIO legacy SPI, ESP32, ESP8266. Bit-bang legacy SPI @ ~500 kHz. Acceptable for functional regression only; not authoritative for timing-sensitive proofs.

### FIDELITY-2: Upload Error Trust Requirement

Visual output is only valid if the upload bridge sticky error bits remain clear.
**Procedure:** Poll `vdp_last_error()` after bursts. On legacy SPI builds, clear error bits with `vdp_clear_upload_status()` if set and retrust only when `vdp_last_error() == 0` throughout setup (this corresponds to `LEGACY_SPI_ERROR` / sticky bit 3 / `sel=4`).

**Current limitation:** On bitstreams built before the `codebase-cleanup-status-contract` lane, `vdp_clear_upload_status()` issues the documented `0x0323` write, but the RTL does not decode that address, so the sticky bits are not actually cleared. On bitstreams built after that lane, `0x0323` is decoded centrally in `VdpTop.scala` and the sticky bits clear normally. See FIDELITY-6 and `MODE0_REGISTER_BUS_SPEC.md` §3.1.2.

### FIDELITY-3: Pico 2 / RP2350 Authority Notes

- **PIO Determinism:** Exactly 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.
- **No Pin Hazard:** PIO `set_pindirs` eliminates the ESP output-enable hazard.
- **Toolchain:** Pico SDK 2.2.0, `-DPICO_PLATFORM=rp2350-arm-s`.

### FIDELITY-4: Artifact Stewardship

**Match Rule:** Every bench test must prove artifact freshness.
1. Verify FPGA bitstream and firmware commits match intended source.
2. Rebuild/reflash if match cannot be proven.
3. Record commit hashes and `last_error` status in every proof packet.
4. **Tang Nano 20K:** Rebuild bitstream from Scala source before every proof session.

### FIDELITY-5: Copper Timing Latency

- **Fact:** Copper script FIFO introduces ~1-line vertical lag.
- **Rule:** Use `y-1` compensation for single-shot effects.
- **Exception:** Do not apply `y-1` to looping programs spanning the active area.
- **Requirement:** State program shape (single-shot/looping) and timing accuracy in reports.

### FIDELITY-6: `vdp_read_status()` is not supported on the i80 backend

**Fact:** The canonical ESP32-P4 host uses the QSPI transport, where `vdp_read_status()` issues the `READ_STATUS` opcode (`0x04`). The i80 RTL decoder (`I80HostInterface.scala`) does **not** decode opcode `0x04`, so `vdp_read_status()` is not available on i80 builds.

**Implication:**
- `vdp_read_status()` works correctly only on QSPI (and legacy SPI) builds.
- On i80/ESP32-S3 builds, `vdp_read_status()` returns undefined data and does not reflect VDP state.
- i80 hosts must use memory-mapped register reads instead.

**Workaround:** On i80, poll status through normal register reads:
- Sticky status → `vdp_reg_read(0x0320)` (or write-1-to-clear with `vdp_reg_write(0x0320, mask)`).
- Upload status → `vdp_reg_read(0x0323)`.
- Upload sticky clear → `vdp_clear_upload_status()` writes `0x0323` with the W1C mask (bits 2 and 3).

On bitstreams built before the `codebase-cleanup-status-contract` lane, `0x0323` is not decoded and upload sticky bits clear only at POR or bridge reset. On bitstreams built after that lane, both QSPI and i80 writes to `0x0323` are decoded centrally in `VdpTop.scala`. See `MODE0_REGISTER_BUS_SPEC.md` §2.3 and §3.1.2.

**Fix status:** Implemented by the `codebase-cleanup-status-contract` lane.

---

## Build / Flash

### GOTCHA-4: Arduino CLI board identifiers

| Platform | Correct FQBN |
|----------|-------------|
| ESP8266 | `esp8266:esp8266:nodemcuv2` |
| ESP32 | `esp32:esp32:esp32` |

Using `arduino:avr:uno` or other generic boards will fail because the pin
mappings and SPI peripheral headers are platform-specific.

---

### GOTCHA-5: `library.properties` is required for Arduino IDE recognition

`firmware/libvdp/library.properties` must exist with at least:

```
name=libvdp
version=1.0.0
author=SignalWire
maintainer=SignalWire
sentence=Shared host driver library for VDP Mode0.
paragraph=Encapsulates host transport, register writes, and SDRAM uploads.
category=Display
url=https://github.com/spinalhdlVDP
architectures=*
```

Without this file, the Arduino IDE and `arduino-cli` will not recognize
`libvdp` as a valid library and sketches will fail to compile.

---

## Platform-Specific

### GOTCHA-6: ESP32 GPIO 25/27 are safe for legacy SPI IO2/IO3

**Fact:** On the ESP32 dev1 board used in this project, GPIO 25 and 27 are
not strap pins and do not conflict with JTAG or flash access.

**Verification:** Checked against Espressif GPIO matrix and dev1 schematic.

---

### GOTCHA-7: ESP8266 GPIO 16 (D0) has no internal pull-up

**Fact:** IO3 on the ESP8266 NodeMCU maps to GPIO 16 (D0), which has no
internal pull-up. If the VDP ever tri-states IO3, the ESP8266 side may float.

**Current status:** The VDP legacy SPI implementation always drives IO3 during
transactions, so this is not a live issue. Documented for future reference if
the VDP side ever adds high-Z states.

---

### GOTCHA-8: Barebones 40-bit legacy SPI protocol (Stage 2+)

**Deviation:** The "barebones" rebuild branch (`mode0t20-barebones-rebuild`) uses a simplified 1-bit SPI protocol instead of the full 6-byte header legacy SPI contract.

**Protocol:**
- **Width:** 1-bit (SCK, CS_N, MOSI only; no MISO/IO2/IO3)
- **Frame:** 40 bits = `[CMD:8] [ADDR:16] [DATA:16]`
- **Command:** Only `0x01` (REG_WRITE) is supported.
- **Timing:** Same 2 MHz SCK and 10 µs CS hold invariants as the main contract.

**Why it exists:** To provide a truly-minimal bring-up path on Tang Nano 20K that fits in low LUT counts and doesn't require the full SDRAM/legacy SPI infrastructure.

**Fix status:** Documented. Host sketches `esp8266_barebones_scroll`, `esp32_barebones_scroll`, and `test_barebones_scroll` implement this protocol. Main `libvdp` DOES NOT support this protocol; it remains locked to the 6-byte header legacy SPI contract.

---

### GOTCHA-9: API Naming and Register Map Conflict (Mode0 vs Barebones)

**Fact:** The `TopTang20kBarebones` build uses a custom register map (`0x0000..0x0005`) that conflicts with the standard `VDP_MODE0_REG_LINESTATE_BASE` (also `0x0000`).

**Implication:**
- `vdp_mode0_*` helpers must NOT be used with barebones builds.
- Use `vdp_barebones_*` for any future helpers targeting the barebones registers.
- The `libvdp` documentation in `kb/libvdp/README.md` is the source of truth for these classifications.

**Transition:** As features migrate from barebones to rich-top, the barebones-specific wrappers will be replaced by standard Mode0 equivalents. No renaming of existing code is authorized until the documentation update is complete and reviewed.

---

### GOTCHA-10: Disabled-Layer Backdrop Index

**Fact:** When all layers and sprites are disabled (`LAYER_ENABLE = 0`), the VDP compositor falls through to the **backdrop color** indexed by `BACKDROP_INDEX` (`0x0348`). This is a 7-bit absolute palette index and is independent of any layer's palette bank.

**Implication:** At power-on, `BACKDROP_INDEX` defaults to `0`, so the backdrop is `palette[0]`. If you disable all layers and see an unexpected color, it is because `BACKDROP_INDEX` points to an entry you did not expect, not because of Layer 0's bank.

**Fix:** Write your intended backdrop palette entry (0..127) to `BACKDROP_INDEX` (`0x0348`), then write the RGB color to that palette entry. Do not rely on Layer 0's palette bank for the disabled-layer backdrop.

### GOTCHA-11: ESP32-S3 legacy SPI SI Ceiling at 80 MHz

**Fact:** The ESP32-S3 hardware SPI2 peripheral supports up to 80 MHz when using the dedicated FSPI IOMUX pin group (GPIO 9..14).

**Implication:** At 80 MHz, signal integrity on breadboards or long unshielded wires is poor. Reflections can cause bit-flips in bulk register writes, leading to corrupted palette or SDRAM data.

**Fix:** Use **60 MHz** (`VDP_SPI_SCK_WRITE_HZ`) as the production bulk-write speed. It provides nearly the same throughput (~6.8 MB/s) with significantly more SI margin. Interleaving data lines with multiple Ground wires on the ribbon cable is also recommended.

### GOTCHA-12: Scaler Register Ordering and Safe-Boundary Commit

**Fact:** The integer pixel-repetition scaler (lane #10590) uses safe-boundary commit logic. Register writes to `SCALE_CTRL`, `LOGIC_WIDTH`, and `LOGIC_HEIGHT` are staged in pending registers and committed only when `hCounter === 0`.

**Implication:**
1. **Set `LOGIC_WIDTH` and `LOGIC_HEIGHT` before enabling scale mode.** If you write `SCALE_CTRL` first with a non-1x scale factor while `LOGIC_WIDTH`/`LOGIC_HEIGHT` are still at POR defaults (640×480), the scaler may compute out-of-bounds line-buffer addresses. This was the root cause of the OOB-write bug caught by BronzeGate (#10697). The RTL now guards against this, but the ordering rule remains: size first, then scale mode.

2. **Register writes take effect at the next frame boundary**, not immediately. Do not expect visible changes mid-frame.

3. **Hardware silently clamps `scale × logicSize` to the active display dimensions.** A 4× scale of 320×240 on a 640×480 display will not crash; it will be clamped to the visible area. The auto-center bezel math computes offsets based on the clamped visible region.

**Fix:** Always set size before scale:
```c
vdp_mode0_set_logic_size(320, 240);   // size first
vdp_mode0_set_scale_ctrl(
    vdp_mode0_scale_ctrl(2, 2, true)  // then scale mode
);
```

---

### GOTCHA-13: Scaler Bezel Test as Canonical Hardware Discriminator

**Fact:** The scaler hardware proof uses a "bezel test" pattern: white bezel (palette[1]) + black scaled center (palette[64 default backdrop]). This is the unambiguous visual discriminator that the scaler is in the data path and auto-center math is working.

**Implication:** If the capture shows all-white or all-black, the scaler is either disconnected (see `7ff34f0` anomaly) or the border/backdrop configuration is wrong. The bezel test must show **structured** white-on-black (or chosen color-on-color) with measurable bezel width.

**Fix:** For hardware proof, always use the bezel test with `autoCenter=1`, `borderEnable=1`, and a high-contrast palette choice. Predict bezel width from `((hActive - scaleX*logicWidth) / 2)` and verify against capture mean.

---

### GOTCHA-030: Tang Nano 20K (GW2AR-18) Embedded SDRAM Pins are SiP

**Fact:** The GW2AR-18 used on the Tang Nano 20K features a System-in-Package (SiP) 64 Mbit SDRAM. These connections are die-to-die internal to the chip.

**Implication:**
- The SDRAM Address bus (A[10:0]), Bank Address (BA[1:0]), and Data bus (DQ[31:0]) are NOT routed to external FPGA pins.
- You cannot probe these signals with an oscilloscope or logic analyzer.
- Signal integrity is fixed by the package substrate; no external termination or board-level tuning is possible.

---

### GOTCHA-031: Embedded SDRAM Address Margin Ceiling (64.8 MHz retired)

**Fact:** Initial bring-up attempts at the retired 64.8 MHz SDRAM clock (Phase 1A) showed non-deterministic Row Address Aliasing (e.g., Row 0x28 overwriting Row 0x2C). The current stable baseline is **40.5 MHz**.

**Why it occurred:** The physical capture window at 180° phase (7.7 ns) is marginal for the combined SiP substrate skew and chip-internal latch requirements. Timing artifacts in the EDA tool hid this marginality until hardware verification.

**Fix:** Lower the SDRAM clock to **40.5 MHz**. This widens the capture window to **12.35 ns**, providing ~60% more setup/hold margin. This is the mandatory stable baseline for Tang Nano 20K Phase 1A. All live documentation and new designs must use 40.5 MHz; 64.8 MHz references are historical/retired.

**Simulation Note:** At 40.5 MHz (RefreshPeriodCycles=593), the `PlanarRefreshStallSim` canary may trip deterministically due to the tighter refresh cadence shifting `memtestPassR` into phase with the artificial testbench stimulus. This is a **benign sim-stimulus artifact** and does not indicate a hardware bug. Do not relax the RTL assert contract.

---

### GOTCHA-032: Multi-bit Clock Domain Crossing (CDC) Value Corruption

**Fact:** Using `BufferCC` (a simple 2-stage synchronizer) on multi-bit vectors (e.g., 23-bit addresses, 10-bit line indices) is a critical integrity failure.

**Why it occurs:** Independent bit-skew during the domain crossing allows the capture domain to sample the vector while only some bits have transitioned. This results in "torn" or "mangled" values that never existed in the source domain.

**Impact:**
- Random jumps in SDRAM fetch addresses (leading to "ghost" tiles).
- Corruption of grant IDs in the arbiter (leading to bus-ownership confusion).
- Mangled status readbacks (e.g., `READ_STATUS` returning invalid dwords).

**Fix:** Replace `BufferCC` for vectors with proper coherent crossings: `PulseSync` + Latch-stable data, or Gray-code encoding for counters.

---

### GOTCHA-033: legacy SPI Physical SCK Ceiling (25.2 MHz Oversampling)

**Fact:** The `QspiSlave.scala` oversamples the asynchronous SCK pin using the 25.2 MHz pixel clock.

**Logic Ceiling:** Per Nyquist-Shannon, the SCK frequency MUST be less than 12.6 MHz (half the oversampling rate). In practice, with routing jitter and setup/hold requirements, the stable ceiling is ~8 MHz.

**Physical Ceiling:** The current ESP32-P4-to-Tang-Nano-20K wiring shows intermittent byte/nibble shifts at 8 MHz bulk SDRAM upload (QSPI-SI-CEILING-183). Bench logs: 8 MHz = 4/10 pass, 4 MHz = 3/3 pass, 2 MHz = 3/3 pass.

**Implication:**
- The 60 MHz write speed recommended in earlier firmware versions is **physically invalid**.
- At 60 MHz, the FPGA sees aliased/random transitions, causing protocol collapse and non-deterministic register/SDRAM write failures.
- At 8 MHz, signal-integrity margin is insufficient for reliable bulk SDRAM upload on this wiring.

**Fix:** Cap the legacy SPI write clock to **4 MHz** max for production bulk uploads. Adjust `VDP_SPI_SCK_WRITE_HZ` in `vdp_platform.h` and the ESP32 probe firmware. Register traffic may still use higher functional clocks after the upload.

---

### GOTCHA-034: Persistent FPGA flash does not prove active SRAM configuration

**Fact:** On the Tang Nano 20K bench, `openFPGALoader --write-flash --verify`
successfully erased, programmed, and verified the persistent bitstream, but its
completion left FPGA SRAM unconfigured. The ESP32-P4 QSPI proof then read
`0xFFFFFFFF` for the magic/status values. A separate
`openFPGALoader --board tangnano20k --bitstream project.fs` SRAM load restored
the active design; the same firmware immediately produced magic `0x51560002`,
health `0x00000000`, and the display-pass marker (`HAM6_PROOF_DONE` at the time;
now the 2bpp indexed reference-mode marker).

**Implication:** A flash hash/verify result is not sufficient for a live host
proof. Load SRAM explicitly for the current session, or power-cycle and verify
the device's configure-from-flash path before interpreting all-ones QSPI reads
as a transport or pin failure.

**Related pin distinction:** The Tang CST numbers (`CS=85`, `SCK=77`,
`IO0..3=25..28`) are FPGA package pins, not ESP32-P4 GPIO numbers. The P4
adapter uses `SCLK=21`, `CS=20`, `IO0=32`, `IO1=33`, `IO2=22`, `IO3=23`.

### GOTCHA-035: ESP32-P4 bulk SDRAM upload clock is 4 MHz on current wiring

**Fact:** The clean-room 30,720-byte checkerboard plane upload passed 3/3 cold-start cycles at 4 MHz and 3/3 at 2 MHz, while 8 MHz passed only 4/10. At 8 MHz, readback showed intermittent byte/nibble shifts in lower bitmap rows even though transport health (`READ_STATUS` selector `0x0A`) stayed `overflow=0`, `malformed=0`.

**Implication:** The current ESP32-P4-to-Tang-Nano-20K QSPI wiring has insufficient signal-integrity margin for the 8 MHz bulk SDRAM upload. This is a host-clock policy, not evidence of FPGA FIFO congestion or a new register/QSPI command.

**Fix:** Keep `QSPI_SDRAM_CLOCK_HZ = 4u * 1000u * 1000u` for checkerboard and other 30,720-byte SDRAM plane uploads on this wiring. Register traffic may return to the 40 MHz functional clock after the upload. Revalidate on any physical wiring, pin, level-shifter, or board revision change.

**Proof:** Checkerboard firmware commit `3d40636` fixed write dummy framing; the 4 MHz policy was directed in mail `#14261`. The 4 MHz canonical rerun must retain 10+ cold-start logs and clean HDMI capture before closeout.

### GOTCHA-036: Guermok USB2 direct-capture chroma and streak artifacts

**Fact:** The Guermok USB2 card can produce stable, repeatable YUV/MJPEG encoding artifacts on an otherwise correct static HDMI frame. In the QSPI-CRC8-185 proof, `/dev/video0` YUYV 720×480 frames 1–3 were byte-identical with SHA-256 `6ce9676fae857417b15bdc0f89aac8e2f336af530786c7b4c900b4babdb17b3d`, and MJPEG 1280×720 frames 1–3 were byte-identical with SHA-256 `499ed65f8385836dcdf5c991cfc6c19d4703a91133f1e742e525caaa2abc029c`, while CRC retry, SDRAM readback, line-state programming, and checkerboard proofs all passed. The earlier HAM6-removal capture artifact showed the same class of evidence—stepped/noisy bands in the direct capture despite a passing transport/display proof (recorded in `PROJECT_PLAN/STATUS.md`, prior capture SHA-256 `f5b36020597f970e21e41e4f1393aff66caaae99e9d9c0521eda642d2a5b8201`).

**Implication:** A stable cyan block, chroma block, or repeated left-edge horizontal streak in this capture path is not by itself evidence of SDRAM corruption, fetch/bank cadence failure, or HDMI scanout failure. Treat serial readback, transport health, and repeat-frame hashes as the authoritative checks before changing firmware or RTL.

**Fix:** If those checks pass, retain the firmware/bitstream and pair-verify with a monitor or alternate capture path. Do not retune QSPI clock, fetch cadence, or bank sequencing solely from this Guermok artifact.

---

### GOTCHA-037: External static review Tier A latent fixes

**Fact:** An external static review of the RTL surfaced two real but dormant wiring/logic issues in `TopTang20kHdmi.scala`, both fixed in commit `10756d1`:

1. **Bootstrap `lastStepIdx` range:** The standalone (`useHostInit=false`) bootstrap FSM wrote zero linestate entries because `lastStepIdx` was set to `colorMathIdx`, which is one less than `linestateBase`. The fix sets `lastStepIdx = linestateBase + LinestateCount - 1`.
2. **Layer 1 pixel-address wiring:** `fetchL1.io.pixelAddr` was wired to `video.io.layer0FetchPixelAddr` instead of `video.io.layer1FetchPixelAddr`. The two scheduling surfaces differ because Layer 0 fetch is pre-registered one pixel ahead of Layer 1.

**Implication:** Neither issue affects the current production path. Production uses `useHostInit=true` (the host writes linestate explicitly) and generates `enableL1Fetch=false` (Layer 1 SDRAM fetch is disabled). They would only become visible in standalone diagnostic builds or in future scenarios that enable Layer 1 fetch.

**Fix status:** Fixed in RTL commit `10756d1`. `VDP_PROGRAMMING_GUIDE.md` notes the Layer 1 scheduling surface and the internal bootstrap linestate behavior.

### GOTCHA-038: FPGA scaler registers persist across MCU reset

**Fact:** Resetting or reflashing the ESP32-P4 does not reset registers in an
already-loaded Tang Nano 20K FPGA bitstream. During the scaler hardware proof,
the mode-0 firmware reported a clean upload/readback pass but the display
remained in the previous mode-2 state (`SCALE_CTRL=0xA2`) until the host
explicitly wrote the mode-0 defaults.

**Implication:** A serial `SCALER_PROOF mode=0 pass=1` proves transport and
content, but it does not prove a 1× display if `SCALE_CTRL` was left untouched
after a prior scaled run. This applies to any proof or application that
restarts the MCU while the FPGA remains configured.

**Fix:** In the canonical GOTCHA-12 order, write the intended logic dimensions
first and then the scale control. For a 1× proof, explicitly use
`vdp_mode0_set_logic_size(640, 480)` followed by
`vdp_mode0_set_scale_ctrl(0)`. For scaled modes, write the corresponding
dimensions and `SCALE_CTRL` every time; do not rely on FPGA POR defaults.

**Proof:** Mode-0 capture after correction commit `2f5be56` showed zero bezel,
full-frame 64×64 checker squares, and 1× baseline regression PASS (BrightForge
mail `#14461`).

### GOTCHA-039: P3b bitmap scaling composes with the built-in 2× path

**Checkpoint recommendation:** For bitmap/indexed content authored at 320×240,
`SCALE_CTRL` composes with the existing 2× source-to-display mapping. Thus
`scaleX=scaleY=1` retains the current 640×480 behavior, while `scaleY=2`
requests 4× effective vertical scaling before the active-display clamp. This
is the BronzeGate concurrence with BrightForge Option B in interface
checkpoint #14467; PM/CyanPeak review remains required before RTL changes.

**Host workflow:** Treat `LOGIC_WIDTH`/`LOGIC_HEIGHT` as the logical crop/source
dimensions and write them before `SCALE_CTRL`. A full 320×240 bitmap at 2× is
too large for the 640×480 active area; crop the logical source first when the
zoomed result must fit. The existing `vdp_mode0_set_scale_mode()` helper is
sufficient; no new host command or public helper is needed.

```

## File: firmware/libvdp/mode0_regs.json

```json
{
  "registers": [
    {
      "name": "LAYER_ENABLE",
      "addr": "0x0300",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "L0",
          "lsb": 0,
          "width": 1,
          "description": "Enables layer 0 output."
        },
        {
          "name": "L1",
          "lsb": 1,
          "width": 1,
          "description": "Enables layer 1 output."
        },
        {
          "name": "SPRITE",
          "lsb": 2,
          "width": 1,
          "description": "Enables sprite output."
        },
        {
          "name": "L2",
          "lsb": 3,
          "width": 1,
          "description": "Enables layer 2 output."
        },
        {
          "name": "L3",
          "lsb": 4,
          "width": 1,
          "description": "Enables layer 3 output."
        }
      ],
      "description": "Enables visible Mode0 display layers."
    },
    {
      "name": "VDP_CTRL",
      "addr": "0x0310",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "COPPER_ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables copper command execution."
        },
        {
          "name": "COPPER_SWAP_REQUEST",
          "lsb": 1,
          "width": 1,
          "description": "Requests a copper buffer swap."
        },
        {
          "name": "SOFT_RESET_REQUEST",
          "lsb": 2,
          "width": 1,
          "description": "Triggers a host-requested soft reset."
        }
      ],
      "description": "Controls global Mode0 runtime features."
    },
    {
      "name": "VDP_TILE_MODE",
      "addr": "0x0311",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "MODE",
          "lsb": 0,
          "width": 2,
          "description": "Tile pattern decode mode selector."
        }
      ],
      "description": "Selects the tile pattern decode mode."
    },
    {
      "name": "VDP_ATTR_MODE",
      "addr": "0x0312",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "MODE",
          "lsb": 0,
          "width": 1,
          "description": "Tile attribute decode mode selector."
        }
      ],
      "description": "Selects the tile attribute decode mode."
    },
    {
      "name": "MODE_SELECT",
      "addr": "0x0313",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADAPTER_MODE",
          "lsb": 0,
          "width": 4,
          "description": "Compatibility adapter mode selector."
        },
        {
          "name": "MODE_FLAGS",
          "lsb": 8,
          "width": 8,
          "description": "Adapter-specific mode option flags."
        }
      ],
      "description": "Selects the active compatibility adapter mode."
    },
    {
      "name": "L0_TRANS_KEY",
      "addr": "0x0314",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 0."
    },
    {
      "name": "L1_TRANS_KEY",
      "addr": "0x0315",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 1."
    },
    {
      "name": "L2_TRANS_KEY",
      "addr": "0x0316",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 2."
    },
    {
      "name": "L3_TRANS_KEY",
      "addr": "0x0317",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 3."
    },
    {
      "name": "STATUS_STICKY",
      "addr": "0x0320",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "fields": [
        {
          "name": "RASTER_MATCH",
          "lsb": 0,
          "width": 1,
          "description": "Raster trigger match occurred."
        },
        {
          "name": "SPRITE_OVERFLOW",
          "lsb": 1,
          "width": 1,
          "description": "Sprite evaluation overflow occurred."
        },
        {
          "name": "HOST_READY",
          "lsb": 2,
          "width": 1,
          "description": "Host bridge reported ready; legacy alias QSPI_READY."
        },
        {
          "name": "HOST_ERROR",
          "lsb": 3,
          "width": 1,
          "description": "Host bridge reported an error; legacy alias QSPI_ERROR."
        },
        {
          "name": "SPRITE_0_HIT",
          "lsb": 4,
          "width": 1,
          "description": "Sprite 0 collision flag latched."
        },
        {
          "name": "SPRITE_BG_HIT",
          "lsb": 5,
          "width": 1,
          "description": "Sprite/background collision flag latched."
        },
        {
          "name": "DMA_DONE",
          "lsb": 8,
          "width": 1,
          "description": "DMA operation completed."
        },
        {
          "name": "BLIT_DONE",
          "lsb": 9,
          "width": 1,
          "description": "Blitter operation completed."
        },
        {
          "name": "BLIT_BUSY",
          "lsb": 10,
          "width": 1,
          "description": "Blitter busy state is latched."
        },
        {
          "name": "MODE_SELECT_CHANGED",
          "lsb": 11,
          "width": 1,
          "description": "Mode select value changed."
        }
      ],
      "description": "Readable VDP sticky status and interrupt cause flags; write one to clear selected bits. QSPI selector 0x05 and i80 register reads return the current value."
    },
    {
      "name": "STATUS_ENABLE",
      "addr": "0x0321",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "diagnostic",
      "description": "Enables reporting for selected sticky status sources."
    },
    {
      "name": "SPRITE_COLL_MASK",
      "addr": "0x0322",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "description": "Clears selected sprite collision sticky bits."
    },
    {
      "name": "UPLOAD_STATUS_CLEAR",
      "addr": "0x0323",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "fields": [
        {
          "name": "UPLOAD_ERROR",
          "lsb": 2,
          "width": 1,
          "description": "Clears upload error sticky flag."
        },
        {
          "name": "UPLOAD_OVERFLOW",
          "lsb": 3,
          "width": 1,
          "description": "Clears upload overflow sticky flag."
        },
        {
          "name": "RESERVED_4",
          "lsb": 4,
          "width": 1,
          "description": "Reserved. Must read 0; W1C write ignored."
        },
        {
          "name": "RESERVED_5",
          "lsb": 5,
          "width": 1,
          "description": "Reserved. Must read 0; W1C write ignored."
        }
      ],
      "description": "Readable upload status; write one to clear sticky host upload bridge error flags. QSPI selector 0x06 and i80 register reads return the current value. Bits 4 and 5 are RESERVED-0; W1C writes to those positions are ignored."
    },
    {
      "name": "WIN1_X0",
      "addr": "0x0330",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 inclusive left X coordinate."
    },
    {
      "name": "WIN1_X1",
      "addr": "0x0331",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 exclusive right X coordinate."
    },
    {
      "name": "WIN1_Y0",
      "addr": "0x0332",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 inclusive top Y coordinate."
    },
    {
      "name": "WIN1_Y1",
      "addr": "0x0333",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 exclusive bottom Y coordinate."
    },
    {
      "name": "COLOR_MATH_CTRL",
      "addr": "0x0334",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls windowed color math and blend behavior."
    },
    {
      "name": "WIN2_X0",
      "addr": "0x0335",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 inclusive left X coordinate."
    },
    {
      "name": "WIN2_X1",
      "addr": "0x0336",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 exclusive right X coordinate."
    },
    {
      "name": "WIN2_Y0",
      "addr": "0x0337",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 inclusive top Y coordinate."
    },
    {
      "name": "WIN2_Y1",
      "addr": "0x0338",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 exclusive bottom Y coordinate."
    },
    {
      "name": "WIN2_CTRL",
      "addr": "0x0339",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls Window 2 enable and selection behavior."
    },
    {
      "name": "WIN_COMBINE",
      "addr": "0x033A",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Selects how Window 1 and Window 2 masks combine."
    },
    {
      "name": "LAYER_MASK",
      "addr": "0x033B",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Selects which layers participate in window/color operations."
    },
    {
      "name": "BORDER_X0",
      "addr": "0x033C",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border inclusive left X coordinate."
    },
    {
      "name": "BORDER_X1",
      "addr": "0x033D",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border exclusive right X coordinate."
    },
    {
      "name": "BORDER_Y0",
      "addr": "0x033E",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border inclusive top Y coordinate."
    },
    {
      "name": "BORDER_Y1",
      "addr": "0x033F",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border exclusive bottom Y coordinate."
    },
    {
      "name": "AFFINE_A",
      "addr": "0x0340",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix A coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_B",
      "addr": "0x0341",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix B coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_C",
      "addr": "0x0342",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix C coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_D",
      "addr": "0x0343",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix D coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_X",
      "addr": "0x0344",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine transform X origin or translation term."
    },
    {
      "name": "AFFINE_Y",
      "addr": "0x0345",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine transform Y origin or translation term."
    },
    {
      "name": "AFFINE_CTRL",
      "addr": "0x0346",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls affine transform enable and options."
    },
    {
      "name": "BORDER_CTRL",
      "addr": "0x0347",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables outer border rendering."
        },
        {
          "name": "INNER_BORDER_ENABLE",
          "lsb": 1,
          "width": 1,
          "description": "Enables inner border inset handling."
        },
        {
          "name": "PALETTE_INDEX",
          "lsb": 8,
          "width": 5,
          "description": "Palette index used for border pixels."
        }
      ],
      "description": "Enables border rendering and selects its palette index."
    },
    {
      "name": "BACKDROP_INDEX",
      "addr": "0x0348",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "INDEX",
          "lsb": 0,
          "width": 7,
          "description": "Palette index used for backdrop pixels."
        }
      ],
      "description": "Selects the backdrop palette index."
    },
    {
      "name": "SCALE_CTRL",
      "addr": "0x0349",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "SCALE_X",
          "lsb": 0,
          "width": 3,
          "description": "Horizontal integer scale factor selector."
        },
        {
          "name": "SCALE_Y",
          "lsb": 4,
          "width": 3,
          "description": "Vertical integer scale factor selector."
        },
        {
          "name": "AUTO_CENTER",
          "lsb": 7,
          "width": 1,
          "description": "Centers the logical image in the output frame."
        }
      ],
      "description": "Controls logical-to-output pixel scaling."
    },
    {
      "name": "LOGIC_WIDTH",
      "addr": "0x034A",
      "width": 16,
      "access": "RW",
      "reset": "0x0280",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "WIDTH",
          "lsb": 0,
          "width": 11,
          "description": "Logical source width in pixels."
        }
      ],
      "description": "Logical source width used by the scaler."
    },
    {
      "name": "LOGIC_HEIGHT",
      "addr": "0x034B",
      "width": 16,
      "access": "RW",
      "reset": "0x01E0",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "HEIGHT",
          "lsb": 0,
          "width": 11,
          "description": "Logical source height in pixels."
        }
      ],
      "description": "Logical source height used by the scaler."
    },
    {
      "name": "INNER_BORDER_L",
      "addr": "0x034C",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Left inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the left edge."
    },
    {
      "name": "INNER_BORDER_R",
      "addr": "0x034D",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Right inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the right edge."
    },
    {
      "name": "INNER_BORDER_T",
      "addr": "0x034E",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Top inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the top edge."
    },
    {
      "name": "INNER_BORDER_B",
      "addr": "0x034F",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Bottom inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the bottom edge."
    },
    {
      "name": "BITMAP_CTRL",
      "addr": "0x0350",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables SDRAM bitmap fetch."
        },
        {
          "name": "BPP",
          "lsb": 1,
          "width": 2,
          "description": "Bitmap bits-per-pixel mode selector; 0b10 selects RGB565 direct color."
        },
        {
          "name": "CELL_WIDTH_LOG2",
          "lsb": 3,
          "width": 4,
          "description": "Log2 cell width for indexed bitmap addressing."
        }
      ],
      "description": "Enables SDRAM bitmap fetch and selects bitmap format."
    },
    {
      "name": "BITMAP_BASE_LO",
      "addr": "0x0351",
      "width": 16,
      "access": "RW",
      "reset": "0x3000",
      "category": "vblank-sensitive",
      "description": "Low 16 bits of the SDRAM bitmap byte-plane base address. In RGB565 direct-color mode (BITMAP_CTRL mode 0b10) the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode."
    },
    {
      "name": "BITMAP_BASE_HI",
      "addr": "0x0352",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADDR_HI",
          "lsb": 0,
          "width": 7,
          "description": "Address bits 22:16 for bitmap base."
        }
      ],
      "description": "High 7 bits of the SDRAM bitmap byte-plane base address. Combined with BITMAP_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode."
    },
    {
      "name": "ATTR_BASE_LO",
      "addr": "0x0353",
      "width": 16,
      "access": "RW",
      "reset": "0x4000",
      "category": "vblank-sensitive",
      "description": "Low 16 bits of the SDRAM attribute or high-byte plane base address. In RGB565 direct-color mode the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode."
    },
    {
      "name": "ATTR_BASE_HI",
      "addr": "0x0354",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADDR_HI",
          "lsb": 0,
          "width": 7,
          "description": "Address bits 22:16 for attribute or high-byte plane base."
        }
      ],
      "description": "High 7 bits of the SDRAM attribute or high-byte plane base address. Combined with ATTR_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode."
    },
    {
      "name": "BITMAP_STRIDE",
      "addr": "0x0355",
      "width": 16,
      "access": "RW",
      "reset": "0x0200",
      "category": "vblank-sensitive",
      "description": "Direct-color bitmap byte-plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned."
    },
    {
      "name": "ATTR_STRIDE",
      "addr": "0x0356",
      "width": 16,
      "access": "RW",
      "reset": "0x0200",
      "category": "vblank-sensitive",
      "description": "Direct-color attribute or high-byte plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned."
    },
    {
      "name": "BITMAP_HEIGHT",
      "addr": "0x0357",
      "width": 16,
      "access": "RW",
      "reset": "0x00F0",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "HEIGHT",
          "lsb": 0,
          "width": 11,
          "description": "Source bitmap height in rows."
        }
      ],
      "description": "Source bitmap height in rows; currently consumed by init-fill path only."
    },
    {
      "name": "PLANAR_WIDTH",
      "addr": "0x0D4B",
      "width": 10,
      "access": "RW",
      "reset": "0x0140",
      "category": "vblank-sensitive",
      "description": "10-bit planar clip width in pixels (default 320)."
    }
  ]
}

```

## File: fpga/tang20k/third_party/hdl_util_hdmi/tmds_channel.sv

```sv
// Implementation of HDMI Spec v1.4a Section 5.4: Encoding, Section 5.2.2.1: Video Guard Band, Section 5.2.3.3: Data Island Guard Bands.
// By Sameer Puri https://github.com/sameer

module tmds_channel
#(
    // TMDS Channel number.
    // There are only 3 possible channel numbers in HDMI 1.4a: 0, 1, 2.
    parameter int CN = 0
)
(
    input logic clk_pixel,
    input logic [7:0] video_data,
    input logic [3:0] data_island_data,
    input logic [1:0] control_data,
    input logic [2:0] mode,  // Mode select (0 = control, 1 = video, 2 = video guard, 3 = island, 4 = island guard)
    output logic [9:0] tmds = 10'b1101010100
);

// See Section 5.4.4.1
// Below is a direct implementation of Figure 5-7, using the same variable names.

logic signed [4:0] acc = 5'sd0;

logic [8:0] q_m;
logic [9:0] q_out;
logic [9:0] video_coding;
assign video_coding = q_out;

logic [3:0] N1D;
logic signed [4:0] N1q_m07;
logic signed [4:0] N0q_m07;
always_comb
begin
    N1D = video_data[0] + video_data[1] + video_data[2] + video_data[3] + video_data[4] + video_data[5] + video_data[6] + video_data[7];
    case(q_m[0] + q_m[1] + q_m[2] + q_m[3] + q_m[4] + q_m[5] + q_m[6] + q_m[7])
        4'b0000: N1q_m07 = 5'sd0;
        4'b0001: N1q_m07 = 5'sd1;
        4'b0010: N1q_m07 = 5'sd2;
        4'b0011: N1q_m07 = 5'sd3;
        4'b0100: N1q_m07 = 5'sd4;
        4'b0101: N1q_m07 = 5'sd5;
        4'b0110: N1q_m07 = 5'sd6;
        4'b0111: N1q_m07 = 5'sd7;
        4'b1000: N1q_m07 = 5'sd8;
        default: N1q_m07 = 5'sd0;
    endcase
    N0q_m07 = 5'sd8 - N1q_m07;
end

logic signed [4:0] acc_add;

integer i;

always_comb
begin
    if (N1D > 4'd4 || (N1D == 4'd4 && video_data[0] == 1'd0))
    begin
        q_m[0] = video_data[0];
        for(i = 0; i < 7; i++)
            q_m[i + 1] = q_m[i] ~^ video_data[i + 1];
        q_m[8] = 1'b0;
    end
    else
    begin
        q_m[0] = video_data[0];
        for(i = 0; i < 7; i++)
            q_m[i + 1] = q_m[i] ^ video_data[i + 1];
        q_m[8] = 1'b1;
    end
    if (acc == 5'sd0 || (N1q_m07 == N0q_m07))
    begin
        if (q_m[8])
        begin
            acc_add = N1q_m07 - N0q_m07;
            q_out = {~q_m[8], q_m[8], q_m[7:0]};
        end
        else
        begin
            acc_add = N0q_m07 - N1q_m07;
            q_out = {~q_m[8], q_m[8], ~q_m[7:0]};
        end
    end
    else
    begin
        if ((acc > 5'sd0 && N1q_m07 > N0q_m07) || (acc < 5'sd0 && N1q_m07 < N0q_m07))
        begin
            q_out = {1'b1, q_m[8], ~q_m[7:0]};
            acc_add = (N0q_m07 - N1q_m07) + (q_m[8] ? 5'sd2 : 5'sd0);
        end
        else
        begin
            q_out = {1'b0, q_m[8], q_m[7:0]};
            acc_add = (N1q_m07 - N0q_m07) - (~q_m[8] ? 5'sd2 : 5'sd0);
        end
    end
end

always_ff @(posedge clk_pixel) acc <= mode != 3'd1 ? 5'sd0 : acc + acc_add;

// See Section 5.4.2
logic [9:0] control_coding;
always_comb
begin
    unique case(control_data)
        2'b00: control_coding = 10'b1101010100;
        2'b01: control_coding = 10'b0010101011;
        2'b10: control_coding = 10'b0101010100;
        2'b11: control_coding = 10'b1010101011;
    endcase
end

// See Section 5.4.3
logic [9:0] terc4_coding;
always_comb
begin
    unique case(data_island_data)
        4'b0000 : terc4_coding = 10'b1010011100;
        4'b0001 : terc4_coding = 10'b1001100011;
        4'b0010 : terc4_coding = 10'b1011100100;
        4'b0011 : terc4_coding = 10'b1011100010;
        4'b0100 : terc4_coding = 10'b0101110001;
        4'b0101 : terc4_coding = 10'b0100011110;
        4'b0110 : terc4_coding = 10'b0110001110;
        4'b0111 : terc4_coding = 10'b0100111100;
        4'b1000 : terc4_coding = 10'b1011001100;
        4'b1001 : terc4_coding = 10'b0100111001;
        4'b1010 : terc4_coding = 10'b0110011100;
        4'b1011 : terc4_coding = 10'b1011000110;
        4'b1100 : terc4_coding = 10'b1010001110;
        4'b1101 : terc4_coding = 10'b1001110001;
        4'b1110 : terc4_coding = 10'b0101100011;
        4'b1111 : terc4_coding = 10'b1011000011;
    endcase
end

// See Section 5.2.2.1
logic [9:0] video_guard_band;
generate
    if (CN == 0 || CN == 2)
        assign video_guard_band = 10'b1011001100;
    else
        assign video_guard_band = 10'b0100110011;
endgenerate

// See Section 5.2.3.3
logic [9:0] data_guard_band;
generate
    if (CN == 1 || CN == 2)
        assign data_guard_band = 10'b0100110011;
    else
        assign data_guard_band = control_data == 2'b00 ? 10'b1010001110
            : control_data == 2'b01 ? 10'b1001110001
            : control_data == 2'b10 ? 10'b0101100011
            : 10'b1011000011;
endgenerate

// Apply selected mode.
always @(posedge clk_pixel)
begin
    case (mode)
        3'd0: tmds <= control_coding;
        3'd1: tmds <= video_coding;
        3'd2: tmds <= video_guard_band;
        3'd3: tmds <= terc4_coding;
        3'd4: tmds <= data_guard_band;
    endcase
end

endmodule

```

## File: PROJECT_PLAN/DECISIONS/ADR-001-SPINALHDL-SOURCE-OF-TRUTH.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-001 — SpinalHDL is the FPGA source of truth

## Status

Accepted.

## Decision

All editable FPGA behavior is authored in Scala using SpinalHDL. Generated
Verilog is a build artifact. SpinalSim is the primary behavioral regression
environment before Gowin synthesis and hardware proof.

## Consequences

- No permanent generated-Verilog edits.
- Every FPGA feature requires SpinalHDL tests.
- Generator versions and commands are release-controlled.

```

## File: PROJECT_PLAN/DECISIONS/ADR-002-HOST-INDEPENDENT-LIBVDP.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-002 — `libvdp` is the universal host SDK

## Status

Accepted.

## Decision

The public SDK remains `libvdp` with the `vdp_*` prefix. Platform APIs are thin
layers such as `vdp_atarist_*` and `vdp_amiga_*`. A parallel `retro_vdp_*`
library will not be created.

## Consequences

Transport differences remain below the generic and platform APIs.

```

## File: PROJECT_PLAN/DECISIONS/ADR-003-VIDEO-ONLY-EMULATION.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-003 — FPGA emulates video hardware only

## Status

Accepted.

## Decision

The FPGA does not emulate complete CPUs or machines. Any host may supply
platform-native graphics memory, register state, and timed events.

## Consequences

Accuracy claims are visual-chipset claims, not complete-machine compatibility.

```

## File: PROJECT_PLAN/DECISIONS/ADR-004-PLATFORM-ADAPTER-MODEL.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-004 — Shared engines plus platform adapters

## Status

Accepted.

## Decision

Platforms reuse shared bitmap, planar, tile, sprite, palette, Copper, HDMA,
Blitter, compositor, scaler, and HDMI engines. Platform-specific logic is added
only where shared engines cannot represent required visual behavior.

```

## File: PROJECT_PLAN/DECISIONS/ADR-005-NO-AGA.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-005 — Amiga scope is OCS/ECS; AGA is deferred

## Status

Accepted.

## Decision

The initial Amiga visual adapter supports OCS/ECS features only. AGA, HAM8,
eight bitplanes, AGA palette/fetch/sprite behavior, and AGA timing are excluded.

```

## File: PROJECT_PLAN/DECISIONS/ADR-006-ONE-ACTIVE-SHARED-RTL-LANE.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-006 — One active shared RTL integration lane

## Status

Accepted.

## Decision

Only one lane may modify common top-level or shared timing/memory components at
a time. Parallel work is limited to research, documents, vectors, firmware-only
work, and isolated components that do not create integration conflicts.

```

## File: PROJECT_PLAN/DECISIONS/ADR-007-MIGRATION-SYSTEM-CUTOVER.md

```md
> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document records the cutover decision; it does not own active-lane status.

# ADR-007 — Migration to Modular Documentation/Specification/Proof System

**Status:** approved  
**Date:** 2026-07-26  
**Owner:** `TopazCliff`  
**Reviewers:** `BrightForge`, `BronzeGate`, `CyanPeak` (architecture/interface review), `CoralReef` (proof-packet/runbook review)

## Context

`PROJECT-SYSTEM-MIGRATION-001` was opened to convert `spinalhdlVDP` from an ad-hoc documentation and coordination model to a modular system with shared specs, canonical adapter directories, validated runbooks, test plans, proof packets, and ADRs — while preserving `STATUS.md` as the live authority, role boundaries, and source/build separation.

The migration progressed through:
- Pre-migration snapshot and inventory.
- Authority reconciliation (`STATUS.md` remains live; external docs kept as reference-only).
- Agent-rule updates (`AGENTS.md` + `AGENTS/*.md`).
- Modular structure under `docs/`, `kb/`, `docs/runbooks/`, `docs/testing/`.
- Proof-packet template and `PROJECT_PLAN/proof_packets/` directory.

## Decision

Approve the cutover. The new modular documentation/specification/proof system is the active project system for `spinalhdlVDP`.

Basis:
- Pilot lane `2bpp-bank-completion-rtl` closed with a complete proof packet (`PASS.txt`, `manifest.yaml`, `hashes.sha256`, `synthesis_summary.md`, `cosim_log.txt` + `.sha256`, `diff.patch`), CyanPeak architecture/interface review PASS (#14375), and CoralReef proof-packet/runbook review PASS (#14376).
- Hardware reproof lane `2bpp-hardware-reproof-4mhz` closed with exact-approved-artifact flash and separated serial/readback/health/YUYV proof (#14415).
- All migration exit criteria are met: state reproducible, mail/`STATUS.md`/task file/repo agree, agent rules updated, role ownership preserved, canonical directories and specs exist, runbooks and test plans in place, expected results documented, proof packets complete, required reviews mailbox-visible, and the pilot closed normally.

## Consequences

- `STATUS.md` remains the sole durable live-state authority.
- `PROJECT_PLAN/TASKS/` owns durable task descriptions; active-lane status stays in `STATUS.md`.
- Proof packets are required under `PROJECT_PLAN/proof_packets/<LANE>/` for every closing lane.
- ADRs are required under `PROJECT_PLAN/DECISIONS/` for permanent architecture and project-system decisions.
- `docs/external_documentation_system/` remains a read-only reference snapshot; its files are not canonical.
- Superseded root `PROJECT_PLAN/TASKS.md` is already archived to `PROJECT_PLAN/archive/TASKS_stale_2026-06-19.md`.
- Existing engineering lanes (`external-review-tierB-measure`, etc.) continue under the new system.

## Related

- `PROJECT_PLAN/STATUS.md` — live lane state
- `PROJECT_PLAN/TASKS/PROJECT-SYSTEM-MIGRATION-001.md` — migration task file
- `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/` — pilot proof packet
- `PROJECT_PLAN/proof_packets/2bpp-hardware-reproof-4mhz/` — hardware reproof proof packet
- `AGENTS.md`, `AGENTS/*.md` — updated agent rules

```

## File: PROJECT_PLAN/DECISIONS/ADR-008-BASICPATTERNSOURCE-ASYNC-READS.md

```md
# ADR-008 — BasicPatternSource Async Reads Acceptance

**Status:** approved  
**Date:** 2026-07-29  
**Owner:** `CyanPeak`  
**Reviewers:** `TopazCliff`

## Context

`BasicPatternSource` contains two asynchronous memory reads (`readAsync`) on the pixel critical path:
1. `tileMap.readAsync(tileAddress)` to look up the tile index (line 43).
2. `tileRows.readAsync(rowAddress)` to fetch the tile-row pixel data (line 48).

These asynchronous reads introduce combinatorial propagation delay that could potentially affect clock timing ($F_{\text{max}}$) under synthesis, and they bypass registered pipelining stages. The external review (F7) raised a query on whether these should be converted to synchronous reads (`readSync`) with lookahead-address generation.

## Decision

We formally accept the asynchronous read path in `BasicPatternSource` as an approved, deferred risk. We will not modify the RTL design to use `readSync` for this diagnostic-only block.

## Consequences

* **Positive:** Bypasses the need for a complex lookahead-address generation unit and extra latency-matching registers for the diagnostic tile generator, keeping the implementation simple and easy to maintain.
* **Negative:** Asynchronous read timing remains combinatorial. However, because the on-chip test-pattern/tile path is completely disabled in production builds (which use SDRAM Layer 0 with `layer0UseSdram=True`), this combinatorial path does not impact production timing constraints, $F_{\text{max}}$, or setup slack.

## Related

* **STATUS.md lane:** `external-review-doc-cleanup-f1-f7-stale-links`
* **Task file:** [external-review-doc-cleanup-f1-f7-stale-links.md](file:///home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/TASKS/external-review-doc-cleanup-f1-f7-stale-links.md)
* **Doc impact tracker:** [external_review_doc_impact.md](file:///home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/external_review_doc_impact.md) (item F7)

```

## File: PROJECT_PLAN/DECISIONS/ADR-009-CANONICAL-STATUS-CONTRACT.md

```md
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

```

## File: PROJECT_PLAN/DECISIONS/ADR_TEMPLATE.md

```md
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-NNN — Short Title

**Status:** proposed | approved | superseded by ADR-XXX | retired  
**Date:** YYYY-MM-DD  
**Owner:** `CanonicalName`  
**Reviewers:** `CanonicalName`, `CanonicalName`

## Context

What decision is needed and why.

## Decision

The decision made.

## Consequences

Positive and negative consequences.

## Related

- `STATUS.md` lane
- task file
- proof packet
- prior art / prior ADRs

```

## File: PROJECT_PLAN/external_review/external_ai_briefing_2026-08-10.md

```md
# External AI briefing — spinalhdlVDP CPU↔FPGA QSPI reliability

**Date:** 2026-08-10  
**Project:** spinalhdlVDP — SpinalHDL VDP for Tang Nano 20K  
**Host platform:** ESP32-P4 Function EV Board (canonical QSPI host)  
**FPGA:** Sipeed Tang Nano 20K (Gowin GW2A-18)  
**Branch under review:** `main` at `24d19ef3`  
**Bundle companion file:** `full_project_bundle_2026-08-10.md`

## Purpose of this briefing

The project owner has directed us to build a **solid, scalable, over-tested, self-healing/adjusting connection between the CPU (ESP32-P4) and the FPGA**. We are asking the external AI to review the current state, the master reliability plan, and the bundled source/project files, then identify gaps, risky assumptions, and under-tested areas.

## Current state

### What is merged to `main`

- The `codebase-cleanup-status-contract` lane is **DONE and merged** (`7bff3d65`, `bf1ea619`, `6ca34805`). It centralized the upload-status W1C decode in `VdpTop.scala`, implemented QSPI selectors `0x05` (sticky) and `0x06` (upload), and gave i80 hosts parity via memory-mapped reads of `0x0320`/`0x0323`.
- Retired i80 and legacy SPI firmware paths are guarded by `#error` compile-time checks (`289fa646`) so they cannot be accidentally compiled.
- The active ESP32-P4 QSPI backend is `firmware/libvdp/vdp_host_p4.c` + `vdp_mode0.c`.

### Two active engineering lanes

| Lane | Status | Blocker / next step |
|---|---|---|
| `qspi-status-done-bit-fix` | **RUNNING — Option A confirmed** | The merged cleanup defines `DONE` (bit 1 of `sel=0x06` / `0x0323`) as sticky, but the implementation drives it from a one-cycle pixel-domain pulse (`QspiSdramBridge.donePulse`) that the SCLK/i80 status paths cannot reliably sample. BrightForge is authorized to implement a true sticky level (set at upload completion, clear on next accepted upload start) with no W1C on bit 1. |
| `qspi-transport-reliability-hardening` | **BLOCKED — mechanism unconfirmed** | Lane 1 showed a first-transaction `magic=0x22222222` anomaly on a fresh `a5a047a2` reconfigure. The original diagnostic evidence (`firstPhase=CMD/firstBitc=1`) is now understood as a capture artifact; the true mechanism may be a reset-domain race, CS# SI/bounce, or read-data launch glitch. BrightForge must build a corrected free-running-domain diagnostic before any RTL fix. |

### Master reliability plan

The owner directive prompted us to write:

`PROJECT_PLAN/TASKS/qspi-cpu-fpga-reliability-plan.md`

It contains:
- Six reliability attributes (observable, recoverable, self-healing, silent-corruption-free, bounded, deterministic).
- A 12-row FMEA table covering `DONE`-bit observability, first-transaction mis-framing, CS# SI, read-launch glitches, silent SDRAM corruption, back-to-back upload races, CDC issues, and long-run drift.
- Candidate design mechanisms: sticky status, free-running reset release, CS# glitch filter, upload CRC, sequence numbers, host retry/backoff, diagnostic selectors, SpinalHDL assertions.
- An over-test matrix across simulation, synthesis/PnR, and hardware.
- Acceptance criteria that raise the bar beyond the two individual lanes.

## What we need from the external AI

Please review the companion bundle (`full_project_bundle_2026-08-10.md`) and this briefing, then answer the following:

1. **Failure-mode coverage.** Are there failure modes or corner cases missing from the FMEA in `qspi-cpu-fpga-reliability-plan.md`? Consider:
   - Clock-domain crossing and metastability.
   - FPGA configuration/POR state versus host boot order.
   - SPI peripheral configuration changes on the ESP32-P4 side.
   - Long-cable / breadboard wiring effects.
   - Toolchain/synthesis differences across seeds or Gowin versions.

2. **Design-mechanism trade-offs.** For each candidate mechanism (CRC, CS# glitch filter, sequence numbers, host retry, etc.), is the cost/benefit appropriate for this project? Are any of them essential rather than optional?

3. **Option A correctness.** Is the `DONE`-bit lifecycle decision (sticky across CS# idle until the next accepted upload starts; no W1C on bit 1) sound and self-consistent with the rest of the status contract?

4. **Diagnostic correctness.** For the transport-lane anomaly, what additional diagnostic experiments (beyond the corrected free-running `firstPhase/firstBitc` capture) would definitively discriminate between reset-domain, CS# SI, and read-launch mechanisms?

5. **Test gaps.** What tests in the over-test matrix are insufficient, impossible on this bench, or missing entirely?

6. **Self-healing policy.** Is the proposed host-side retry/timeout/backoff policy complete? What policy edge cases could still leave the host stuck?

7. **Spec/doc risks.** Are there ambiguities in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, or `firmware/libvdp/mode0_regs.json` that could cause the host and FPGA to disagree after the `DONE`-bit fix?

Please provide concrete recommendations, not just general advice. Where possible, cite file paths and line numbers from the bundle.

## Deliverable format

Return a markdown report with:
- Executive verdict (is the current plan adequate to meet the owner's reliability goal?)
- Itemized findings (numbered F1, F2, ...)
- Recommended changes to the plan, source, tests, or docs
- Any blockers that should stop Rule 19 sign-off until resolved

## Constraints

- The retired i80 and legacy SPI paths must stay retired unless a new Rule-19-gated lane explicitly re-opens them.
- Any new host-visible register, bit, or protocol change requires independent BrightForge + BronzeGate Rule 19 sign-off.
- The Tang Nano 20K wiring and ESP32-P4 GPIO mapping (`SCLK=21, CS=20, IO0=32, IO1=33, IO2=22, IO3=23`) are fixed for this build.

---

*This briefing and the companion bundle were generated at `main` `24d19ef3` on 2026-08-10.*

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/ack_to_external_ai.md

```md
> **To:** External AI Reviewer  
> **From:** TopazCliff (Project Lead, spinalhdlVDP)  
> **Re:** Approval of `codebase-cleanup-status-contract` and execution plan  
> **Date:** 2026-07-27

Acknowledged. Thank you for the formal sign-off.

We will proceed exactly as gated:

1. **Lane 1 remains frozen** until the ten-cycle reproof closes.
2. **Lane 2 is folded** into the new `codebase-cleanup-status-contract` lane.
3. **BrightForge and BronzeGate Rule 19 written approval** is obtained before any RTL or firmware change.
4. **Dead code** is moved to `PROJECT_PLAN/archive/`, not deleted.
5. Once the cleanup branch is committed and passes sim/synth/firmware-build, we will regenerate `source_bundle.md` and submit it for your final verification pass.

I will ping you in this thread when the regenerated bundle is ready.

— TopazCliff

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/external_ai_action_plan.md

```md
# External AI Code Audit — Action Plan (TopazCliff)

**Date:** 2026-07-27  
**Revised:** 2026-08-02  
**Audit bundle SHA-256:** `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`  
**Original review request:** `review_request_for_external_ai.md`

---

## Executive Summary

The external AI confirmed the suspicion: the repository has a **split-brain status architecture**. Firmware headers promise selectors and status bits that the current RTL either does not implement or has tied off. This drift is the root cause of the confusion that forced us to grep across half the repo to answer *"what are the other status bits?"*

The external AI issued **five mandatory cleanup directives**. This plan maps those directives onto our current lanes, identifies conflicts, and proposes a controlled execution order. The plan was revised after BronzeGate (#14621) and BrightForge (#14623) raised concrete selector-collision and contract conflicts.

---

## 1. External AI Findings (Condensed)

| Finding | Severity | Summary |
|---------|----------|---------|
| `vdp_wait_vblank()` / `vdp_wait_sticky()` poll `READ_STATUS` sel=5, but `QspiTransportCore` does not decode sel=5. | **CRITICAL** | VBLANK pacing is broken for QSPI hosts. |
| Upload-status inputs (`upload_busy/done/error/overflow`) are tied off in `QspiTransportCore`. | **CRITICAL** | QSPI hosts cannot see bridge backpressure. |
| `vdp_reg_read()` returns 0 on P4; the RegBus is write-only. | **HIGH** | The public API has a function that cannot work on the canonical transport. |
| Firmware defines `READ_STATUS` selectors 1–6; RTL only implements 0, 7, 8, 9, 10, 11, 12. | **HIGH** | Headers and hardware disagree. |
| Upload-status clear at `0x0323` is not decoded in current RTL. | **HIGH** | Errors would be permanently stuck once surfaced. |
| Lane 2 plan puts `0x0323` decode only in `I80HostInterface.scala`. | **HIGH** | QSPI hosts calling `vdp_clear_upload_status()` would still be unable to clear errors. |

---

## 2. Canonical Status Contract (Revised)

The project agrees with the external AI's unified-model goal, but the exact selector numbers and bitfield have been reconciled with existing firmware headers and active diagnostic selectors.

### READ_STATUS selectors (QSPI / CMD=0x04)

| Selector | Name | Source |
|----------|------|--------|
| `0x00` | Magic | `QspiTransportCore` |
| `0x05` | **VDP sticky status** | `VdpTop.statusStickyReg` |
| `0x06` | **Upload status** | `QspiSdramBridge` / `QspiTransportCore` |
| `0x07` | Header parity health | `QspiTransportCore` |
| `0x08` | SDRAM debug readback | `QspiTransportCore` |
| `0x09` | Last reg-write loopback | `QspiTransportCore` |
| `0x0A` | Transport health | `QspiTransportCore` |
| `0x0B` | CRC8 error | `QspiTransportCore` |
| `0x0C` | READ_DONE | `QspiTransportCore` |
| `0x0D` | Lane 1 diagnostic only (not production) | `QspiTransportCore` |

`0x05` and `0x06` already exist in firmware headers; this contract finally implements them in RTL.

### Memory-mapped status / W1C registers

Decode centrally in `VdpTop.scala`. Reads return current value; writes are W1C clear.

| Register | Read | Write |
|----------|------|-------|
| `0x0320` | VDP sticky status | W1C clear |
| `0x0321` | Sticky IRQ enable mask | R/W mask |
| `0x0322` | Sprite-sprite collision mask | W1C clear |
| `0x0323` | Upload status | W1C clear |

### Upload status bitfield

| Bit | Name | Notes |
|-----|------|-------|
| 0 | `BUSY` | Live, not sticky |
| 1 | `DONE` | Sticky until cleared |
| 2 | `ERROR` | Sticky until cleared |
| 3 | `OVERFLOW` | Sticky until cleared |
| 4 | `RESERVED` | Must read 0 |
| 5 | `RESERVED` | Must read 0 |

Clear mask for `0x0323`: bits 2 and 3. Bit 4 (`TXN_DROPPED`) is deferred until a detector is designed and authorized.

### i80 parity

i80 hosts read status from the same memory-mapped registers (`0x0320`, `0x0323`). They clear status by writing those registers. No separate i80 `READ_STATUS` opcode is required.

---

## 3. Conflicts with Current Lanes

### Lane 1: `2bpp-bank-completion-hw-reproof`

- **Status:** BLOCKED. Locked to bitstream `project_a5a047a2_bankcompletion.fs`.
- **Conflict:** None. Lane 1 must not be touched by cleanup.
- **Action:** BrightForge is authorized to build and BronzeGate to flash a **separate diagnostic bitstream** (`eaad44f8`) to resolve the fresh-reconfigure `0x22222222` anomaly. The cleanup lane does not depend on Lane 1 closing first, but no cleanup RTL may be committed into the Lane 1 bitstream.

### Lane 2: `upload-status-clear-rtl-decode`

- **Status:** PAUSED.
- **Conflict:** Original option-1 i80 local decode would leave QSPI hosts unable to clear errors.
- **Action:** Folded into the cleanup lane. Central `VdpTop.scala` decode replaces the local-decode approach.

---

## 4. Execution Plan

Create a new lane/branch: `codebase-cleanup-status-contract`.

### Step A — Design & Approval (TopazCliff)

1. Circulate the revised `rule19_signoff_request.md` to BrightForge and BronzeGate.
2. Obtain **written Rule 19 approval**.
3. Update `STATUS.md` and the lane task file.

### Step B — RTL (BrightForge)

1. In `QspiTransportCore.scala`:
   - Implement `sel=0x05` output from a new `status_sticky` input.
   - Implement `sel=0x06` output from `upload_busy/done/error/overflow` inputs.
   - Remove the tie-offs on the upload-status inputs.
2. In `VdpTop.scala`:
   - Decode `0x0323` as upload-status read/W1C.
   - Decode `0x0320` as sticky-status read/W1C (read path may already exist; verify).
   - Route `statusStickyReg` to the QSPI core.
3. In `I80HostInterface.scala`:
   - Ensure `0x0320` and `0x0323` reads return the current status words.
4. In `TopTang20kHdmi.scala`:
   - Wire the new `QspiTransportCore` status inputs to the real sources.

### Step C — Firmware (BronzeGate)

1. Update `vdp_host.h` selector comments to match the exact RTL map (`0x05`, `0x06`).
2. Update `vdp_status.h` / `vdp_i80.h` constants if needed (they should already align).
3. Keep `vdp_reg_read()` active; document the P4 write-only limitation and call sites.
4. Ensure `vdp_clear_upload_status()` uses the canonical `0x0323` W1C contract with bits 2/3.

### Step D — Documentation (CoralReef)

1. Update `MODE0_REGISTER_BUS_SPEC.md` with the canonical status map.
2. Update `firmware/GOTCHAS.md` to remove contradictions.
3. Update any README that documents the old selector map.

### Step E — Archive Dead Code (deferred)

- Do **not** archive `vdp_reg_read()` (active API).
- Do **not** archive `QspiSlave.scala` (active source).
- Legacy QSPI shims require a consumer audit before archival; defer to a follow-up lane.

### Step F — Verification

1. SpinalHDL sims pass.
2. Synthesis/PnR for Tang Nano 20K passes.
3. Firmware builds for ESP32-P4 and legacy targets.
4. Rule 19 sign-off rechecked after implementation.

### Step G — Re-bundle for External AI (TopazCliff)

After cleanup is committed, regenerate `source_bundle.md` and submit it to the external AI for final verification.

---

## 5. Gating Criteria Before Hardware Debugging Resumes

- [ ] Rule 19 written approval from BrightForge and BronzeGate.
- [ ] Lane 1 reproof closed (pass or fail documented) **or** explicitly paused by PM.
- [ ] Lane 2 folded into cleanup lane.
- [ ] Cleanup branch passes sim + synth.
- [ ] External AI final verification PASS.

---

## 6. Risks

- **Scope creep:** The cleanup touches transport, register bus, and status logic. It is larger than Lane 2.
- **Hardware re-validation:** Any RTL change requires a new bitstream and fresh proof.
- **Legacy host breakage:** Changing selector semantics may break archived sketches; using existing `0x05`/`0x06` numbers minimizes this.

---

## 7. Next Immediate Action

TopazCliff has authorized the Lane 1 diagnostic bitstream flash/readout and is requesting revised Rule 19 sign-off from BrightForge and BronzeGate.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/final_verification_2026-08-03/final_verification_request_for_external_ai.md

```md
# External AI — Final Verification Request

**Project:** spinalhdlVDP  
**Lane:** `codebase-cleanup-status-contract` (Step B RTL + Step C firmware sync)  
**Branch:** `brightforge/status-contract-cleanup` (base `main` `fd39d2b0`)  
**Date:** 2026-08-03  
**Requested by:** TopazCliff (Project Lead)  
**Bundle location:** `PROJECT_PLAN/proof_packets/codebase-cleanup-status-contract/`  

---

## What we are asking

This is the **final verification gate** before the Project Lead authorizes merging `brightforge/status-contract-cleanup` into `main`. We need you to confirm that the implementation actually matches the canonical contract we agreed on, and that no new contradictions or host-visible regressions were introduced.

All prior gates are closed:
- Rule 19 sign-off: BrightForge #14629, BronzeGate #14631, External AI approval.
- CyanPeak code-to-spec review: PASS (#14647).
- BronzeGate Step C firmware/header sync + ESP-IDF v6.0.2 builds: PASS (#14650).
- BrightForge Step B RTL + SpinalSim + Gowin PnR: PASS (#14643).

---

## What to review

1. **`rtl_implementation_bundle.md`** — high-level contract map, source-diff summary, sim results, PnR summary, hashes.
2. **`rtl_source.diff`** — 261-line full diff of `main..brightforge/status-contract-cleanup`.
3. **`review.md`** — CyanPeak's Step C firmware verdict.
4. **`hashes.sha256`** — build artifact hashes.
5. **Canonical contract reference:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md`.

If you want the full regenerated codebase bundle as well, let us know and we will produce it. For this gate we are hoping a focused diff + bundle review is sufficient.

---

## Specific verification questions

1. **Contract conformance:** Does the implemented selector map (`READ_STATUS sel=0x05` sticky, `sel=0x06` upload, reg `0x0320`/`0x0323` W1C, i80 read mux) match `rule19_signoff_request.md`?
2. **Host-visible deviations:** Are there any selector collisions, bitfield mismatches, or register-address changes that would break existing firmware or host code?
3. **W1C semantics:** Is the `0x0323` write-1-to-clear decode correct (bits 2/3 only, set-wins-on-tie, no corruption of other bits)?
4. **i80 parity:** Does the i80 `readData` mux for `0x0320`/`0x0323` give i80 hosts the same status words QSPI hosts see via `READ_STATUS`?
5. **No dead-code reintroduction:** Did the cleanup leave any new tie-offs, stubs, or duplicated status definitions?
6. **Scope discipline:** Did the changes stay inside the approved contract, or did they creep into Lane 1 hardware-debug logic, the production bitstream, or unrelated register decode?
7. **Documentation alignment:** Do the updated docs (`MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, `kb/libvdp/README.md`, `firmware/libvdp/mode0_regs.json`) still match the RTL?

---

## Verdict format

Please return a single verdict:

- **PASS** — implementation matches the canonical contract; no host-visible regressions; merge can proceed.
- **PASS WITH CONDITIONS** — minor findings that must be fixed before merge; list them explicitly.
- **NEEDS-CHANGES** — significant deviation or regression; do not merge until re-reviewed.

For any finding, include:
- File/module
- Line or selector/register reference
- Why it matters
- Suggested fix

---

## Do not do

- Do not propose new host-visible changes (selector numbers, bitfield changes, new registers) — those would need a fresh Rule 19 cycle.
- Do not re-audit the entire unrelated codebase unless you believe the focused bundle is insufficient.

Thank you.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/final_verification_2026-08-03/README.md

```md
# Final Verification Package — `codebase-cleanup-status-contract`

**Date:** 2026-08-03  
**Lane:** `codebase-cleanup-status-contract`  
**Branch:** `brightforge/status-contract-cleanup` (base `main` `fd39d2b0`)  
**PM:** TopazCliff  

This package contains everything the external AI reviewer needs for the **final verification gate** before the Project Lead authorizes merging the cleanup lane into `main`.

## Files

| File | Purpose |
|---|---|
| `final_verification_request_for_external_ai.md` | The verification request with context, questions, and verdict format. |
| `rtl_implementation_bundle.md` | High-level contract map, source-diff summary, simulation results, PnR summary, and artifact hashes. |
| `rtl_source.diff` | 261-line full diff of `main..brightforge/status-contract-cleanup`. |
| `review.md` | CyanPeak's Step C firmware contract-sync verdict (PASS). |
| `hashes.sha256` | SHA-256 hashes of firmware build artifacts. |
| `manifest.yaml` | Proof-packet manifest with commit references and mail threads. |

## Reference contract

The approved host-visible contract is in:

`PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md`

## Gate status

- Rule 19 sign-off: BrightForge #14629, BronzeGate #14631, External AI approval ✅
- CyanPeak code-to-spec review: PASS (#14647) ✅
- BronzeGate Step C firmware/header sync + builds: PASS (#14650) ✅
- BrightForge Step B RTL + sim + PnR: PASS (#14643) ✅
- **Remaining gate:** External AI final verification → PM merge authorization.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/final_verification_2026-08-03/review.md

```md
# Step C review

Verdict: PASS for the BronzeGate firmware scope.

Evidence:

- Source commit `a5f2aaa93e89d3afbb4b0adf041eb19582508251` contains only the
  five assigned firmware contract/schema files.
- ESP32-P4 proof builds for modes 0, 2, and 3 passed with ESP-IDF v6.0.2.
- Artifact hashes are recorded in `hashes.sha256`.
- `git diff --check` passed before the source commit.
- JSON validation passed with `python3 -m json.tool firmware/libvdp/mode0_regs.json`.
- No merge was attempted. External-AI final verification and explicit PM merge
  authorization remain open gates.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/final_verification_2026-08-03/rtl_implementation_bundle.md

```md
# Status-Contract Cleanup — RTL Implementation Bundle (external-AI final verification)

**Lane:** `codebase-cleanup-status-contract` (Step B, RTL) · **Author:** BrightForge · **Date:** 2026-08-03
**Branch:** `brightforge/status-contract-cleanup` · **Base:** `main` `fd39d2b0`
**Purpose:** final implementation bundle for the external-AI re-check (the only remaining gate before PM
merge authorization, per #14652). Gates already passed: CyanPeak code-to-spec review (#14647/#14649),
BronzeGate Step C firmware (#14650).

---

## 1. Canonical status contract implemented (per `rule19_signoff_request.md`)

| Surface | Content | Source |
|---|---|---|
| `READ_STATUS sel=0x05` | VDP sticky status (16b) | `VdpTop.statusStickyReg` |
| `READ_STATUS sel=0x06` | Upload status `[3:0]=busy,done,error,overflow` | `QspiSdramBridge` |
| reg `0x0320` R / W1C | VDP sticky read / write-1-to-clear | `VdpTop` (already existed) |
| reg `0x0323` R / W1C | Upload status read / W1C (bits 2/3) | `VdpTop` (new, centralized) |
| i80 read `0x0320`/`0x0323` | same words via `readData` mux | `TopTang20kHdmi` |

- Removed the `QspiTransportCore` MVP tie-offs (`dec.io.status_sticky := B(0)`, `dec.io.upload_* := False`).
- `0x0323` W1C decoded **centrally in `VdpTop`** so both QSPI and i80 writes (shared reg-bus) clear the
  same bridge stickies. Bit 2 → `upload_error`, bit 3 → `fifoOverflow`. **Set-wins-on-tie** in the bridge.
- Bits 4/5 RESERVED-0 (write ignored); `TXN_DROPPED` deferred (no detector). No `0x11`/`0x12`.
  `vdp_reg_read()` and `QspiSlave.scala` untouched.

## 2. Source diff

Full diff: `rtl_source.diff` (261 lines). Summary — **5 files, +155/−5**:

```
Qspi0x0323StatusClearSim.scala  | 98 +   (new sim)
QspiSdramBridge.scala           |  7 +   (uploadErrorClear/fifoOverflowClear inputs + W1C set-priority)
QspiTransportCore.scala         | 26 +/- (un-tie + status inputs + BufferCC + sel=0x05/0x06 responder cases)
TopTang20kHdmi.scala            | 15 +   (statusSticky+bridge stickies -> qspiCore; VdpTop clear -> bridge; i80 read mux)
VdpTop.scala                    | 14 +   (0x0323 W1C decode + uploadErrorClear/fifoOverflowClear outputs)
```

Commits on branch: `2366f104`, `77405bb3`, `1bd5d73b`.

## 3. Simulation

**`Qspi0x0323StatusClearSim` — PASS (7/7):**
```
[ok] fifoOverflow SET on wrCmd downstream stall
[ok] fifoOverflow CLEARED by W1C strobe
[ok] uploadError SET on watchdog stall abort
[ok] uploadError CLEARED by W1C strobe
[ok] fifoOverflow also cleared before tie test
[ok] fifoOverflow SET (tie setup)
[ok] SET-WINS-ON-TIE: fifoOverflow stays SET (clear pulsed while set active)
Qspi0x0323StatusClearSim: PASS
```

**Full affected regression — PASS** (each run in its own sbt invocation):
- `Indexed2bppFineCoSim`: MATCH on rows 100/240/400; INTRA-BYTE CLEAN.
- `Indexed2bppCheckerCoSim`: CHECKER-EDGE CLEAN (interior runs 64±1 px, no spurious runs).
- `Indexed2bppFrameCoSim`: ROW-CODED bestDv=3 (479/480, 1 startup); **SHEAR_SPAN=0px**.

`sbt compile` PASS; both tops (`TopTang20kHdmiVerilog` production + `TopTang20kI80Verilog`) elaborate clean.

## 4. Synthesis / PnR (Gowin V1.9.12.01, GW2AR-LV18QN88C8/I7)

- **TNS = 0.000 on ALL clocks** (clk_pixel, clk_x5, I_clk, clk_sdram, qspi_sck + all PLL generated clocks).
- Resources: Logic 11329/20736 (55%), Register 5629/15915 (36%), CLS 7661/10368 (74%),
  **BSRAM 40/46 (= production baseline, NO new BSRAM)**, **DSP 12/24 (NO new DSP)**.
- Reports: `fpga/tang20k/impl/pnr/project_tr_content.html` (timing), `project.rpt.txt` (resources).

## 5. Hashes

| Artifact | SHA-256 |
|---|---|
| Generated Verilog `hw/gen/top_tang20k.v` | `670c9c8c0175adacd5fc1817a8cb28e786f91616c9460cb8272efe9ea84210f7` |
| Bitstream `project_be997838_statuscontract.fs` | `be9978382fb16c463b238adc23a95275663dfdd540a3b6cff91e23a987c0fb5a` |

Firmware (BronzeGate Step C, `a5f2aaa9`): ELF `cb5a52d5…`, BIN `a3fda3dd…` (recorded in `firmware/`).

## 6. Notable implementation facts (for the reviewer)

- The **`QspiTransportCore.rxWordSel` switch is the authoritative READ_STATUS responder** (drives
  `slave.io.rxWord`); the internal `QspiDecoder`'s own sel response is vestigial — the new `sel=0x05`/
  `0x06` cases were added to `rxWordSel`, and `status_sticky`/`upload_*` cross sys→SCLK via `BufferCC`
  (same pattern as the existing sel=8/sel=11 selectors).
- `VdpTop.statusStickyReg` + `0x0320` W1C pre-existed; only the un-tie/wiring + `sel=5` case + the new
  `0x0323` decode were added.
- i80 `readData` is a parent-driven input; the existing `TopTang20kHdmi` mux (0x0328/9/0x0310/0x035C)
  was extended with `0x0320`/`0x0323` — a real mux entry, not a stub.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/response_to_external_ai.md

```md
> **To:** External AI Reviewer  
> **From:** TopazCliff (Project Lead, spinalhdlVDP)  
> **Re:** Full Codebase Audit (bundle SHA-256 `ce2c0d4a...`)  
> **Date:** 2026-07-27

Thank you for the audit. Your conclusion matches our suspicion: the repository has become a split-brain system where the firmware headers promise status surfaces that the RTL has either abandoned or tied off. We accept the findings and will treat the cleanup as mandatory, not optional.

## What we agree with

- The QSPI upload-status bits are effectively **not visible** to the host today because `QspiTransportCore` ties them off.
- `vdp_wait_vblank()` / `vdp_wait_sticky()` are broken for QSPI hosts because `sel=5` is not decoded.
- `vdp_reg_read()` cannot work; the RegBus is write-only.
- Lane 2 as originally scoped (decode `0x0323` only inside `I80HostInterface.scala`) is insufficient because QSPI hosts also call `vdp_clear_upload_status()`.
- We need **one canonical status contract**, not three parallel interfaces.

## What we will change about the plan

We cannot execute all directives immediately because two hardware-debug lanes are currently open:

- **Lane 1** (`2bpp-bank-completion-hw-reproof`) is running a ten-cycle reproof with a locked bitstream. It must not be disturbed.
- **Lane 2** (`upload-status-clear-rtl-decode`) was already approved, but your audit shows its scope is too narrow. We will **pause Lane 2** and fold it into a new cleanup lane.

The new lane will be `codebase-cleanup-status-contract`.

## Canonical contract we will implement

### READ_STATUS selectors (CMD=0x04)

| Selector | Content |
|----------|---------|
| `0x00` | Magic `0x51560002` |
| `0x07` | Header-parity health |
| `0x08` | SDRAM debug readback |
| `0x09` | Last reg-write loopback |
| `0x0A` | Transport health |
| `0x0B` | CRC8 error |
| `0x0C` | READ_DONE |
| `0x11` | **VDP sticky status** (routed from `VdpTop`) |
| `0x12` | **Upload status** (routed from `QspiSdramBridge`) |

### W1C registers (decoded centrally in `VdpTop.scala`)

- `0x0320` — sticky status W1C
- `0x0321` — sticky IRQ enable mask
- `0x0322` — sprite-sprite collision mask W1C
- `0x0323` — upload status W1C

Both QSPI and i80 hosts will use the same `0x0323` register write to clear upload errors. The i80 decoder will gain a way to read the same status words that QSPI reads via `READ_STATUS`.

## Execution order

1. **TopazCliff** circulates the action plan and obtains written Rule 19 approval from BrightForge and BronzeGate.
2. **BrightForge** implements the RTL changes:
   - Add `sel=0x11` and `sel=0x12` to `QspiTransportCore.scala`.
   - Decode `0x0323` centrally in `VdpTop.scala`.
   - Update `I80HostInterface.scala` so i80 hosts can read status.
   - Remove tie-offs.
3. **BronzeGate** updates firmware headers and removes/archives dead functions.
4. **CoralReef** updates docs and register spec.
5. **TopazCliff** regenerates `source_bundle.md` and submits it to you for final verification.

## What we will archive, not delete

Per your instruction, we will move the following to `PROJECT_PLAN/archive/` rather than permanently deleting them:

- `vdp_reg_read()` if removed from the active API.
- Legacy QSPI compatibility shims (`vdp_legacySpi.h`, `vdp_qspi.h`) if confirmed unused.
- Bypassed oversampled RTL (`QspiSlave.scala`) if confirmed unused.

## Gate before hardware debugging resumes

We will not flash any new bitstream or continue Lane 2 until:

- Rule 19 written approval is recorded.
- Lane 1 reproof is closed.
- Cleanup branch passes SpinalHDL sim, synthesis, and firmware build.
- Your final verification of the regenerated bundle passes.

We will send the regenerated bundle as soon as the cleanup is committed.

— TopazCliff

---

## Follow-up — External AI approval received (2026-08-02)

> **To:** External AI Reviewer  
> **From:** TopazCliff

Thank you for the formal approval of the revised Rule 19 request. Your acknowledgments are exactly right:

- `vdp_reg_read()` stays active because it is required by `vdp_mode0.c` and the i80 path.
- Reusing `0x05`/`0x06` keeps the RTL honest to the existing firmware contract.
- Memory-mapped i80 reads avoid forcing a `READ_STATUS` opcode into the i80 decoder.
- Deferring `TXN_DROPPED` keeps the interface honest.

The remaining gate is written sign-off from BrightForge and BronzeGate. I will not authorize RTL or firmware edits until both are recorded.

Regarding Lane 1 telemetry: BronzeGate is about to flash your diagnostic bitstream (`eaad44f8`) and capture `sel=0x0D`. I will send you the raw readout as soon as it is available. If the diagnostic does not produce a clean discriminator, I may ask you to review the combined cycle-01 logs (`LANE1_COMBINED_LOGS.md`) and the `sel=0x0D` word together.

— TopazCliff

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/review_request_for_external_ai.md

```md
# External AI Review Request — Full spinalhdlVDP Code Audit

**Project:** spinalhdlVDP (Sipeed Tang Nano 20K + ESP32-P4 Function EV Board)  
**Date:** 2026-07-27  
**Requested by:** TopazCliff (Project Lead)  
**Review scope:** All firmware, SpinalHDL, and RTL source code  
**Bundle location:** `PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/source_bundle.md`  
**Bundle SHA-256:** `ce2c0d4abe53a09ddd51b85a9719a07f67173b99904ddd1a7598684ed9247da9`

---

## Why we need this

We are going in circles on what should be simple questions.

A recent example: the user asked, *"what are the other status bits?"*  
To answer that one question I had to grep across:

- `firmware/libvdp/vdp_host.h`
- `firmware/libvdp/vdp_status.h`
- `firmware/libvdp/vdp_i80.h`
- `firmware/libvdp/vdp_mode0.h`
- `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`
- `hw/spinal/spinalhdlvdp/QspiDecoder.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala`
- `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- project docs and MCP memory

…just to reconstruct the answer. The same status bits are defined in three places, implemented in two places, and reachable in different ways depending on whether the host is using the legacy QSPI path, the new QSPI transport core, or the i80 path. Some selectors return zero in the current bitstream even though the headers still document them. Some register addresses exist in firmware but are not decoded in RTL. Some bits are stuck, some are tied off, and nobody can tell without reading the source.

We have MCP memory. We have documentation. We have a project plan. **And we still had to hunt through the code to answer a basic question.** That is not a hardware problem; that is an organization problem. If this project had a single, accurate, line-up-to-line status and register model, we would probably be done by now.

So we are asking for a cold, hard review of the entire codebase.

---

## What we want you to do

Please open the attached bundle (`source_bundle.md`) and go through it line by line. Treat nothing as trusted. Assume the docs are stale and the comments are wrong until proven otherwise.

Specifically:

1. **Verify and explain.** For every significant module, write a short plain-language explanation of:
   - What it is supposed to do.
   - What it actually does.
   - Whether the two match.

2. **Find contradictions.** Look for:
   - Bitfield definitions in firmware that do not match RTL.
   - Register addresses that are defined but never decoded.
   - `READ_STATUS` selectors that are documented but return zero or are not implemented.
   - Status/irq/event bits that are set in one file and ignored in another.
   - Magic numbers duplicated with different names.
   - Code that is referenced by comments but no longer exists.

3. **Identify dead code and orphans.** Mark anything that is:
   - No longer reachable.
   - Tied off in the top-level integration.
   - Superseded by a newer implementation.
   - Left over from a retired host interface (legacy QSPI, Pico PIO, etc.).

4. **Optimize.** Where you find redundancy, propose the smallest clean refactor that removes it without changing behavior. We prefer correctness over cleverness.

5. **Propose a canonical status model.** We need one host-facing status surface that works for both QSPI and i80. It should include:
   - Upload state (busy, done, error, overflow, dropped).
   - Host/transport error state.
   - VDP event state (raster, sprite overflow, DMA/Blit done, mode switch, collisions).
   - A single W1C clear register and a single enable/mask register.
   - No duplicated constants between firmware and RTL.

6. **Rank issues.** Give each finding a severity:
   - **CRITICAL** — could cause data corruption, deadlock, or the symptoms we are debugging now.
   - **HIGH** — wrong or misleading enough to waste engineering time.
   - **MEDIUM** — technical debt that should be fixed soon.
   - **LOW** — cleanup only.

7. **Suggest the order of fixes.** If we can only fix ten things, which ten move the project forward fastest?

---

## Current context you should know

We are running two hardware-debug lanes in parallel:

- **Lane 1:** `2bpp-bank-completion-hw-reproof` — intermittent lower-bitmap upload corruption on QSPI. The working theory is SI/marginal timing, but we are ten-cycling a probe-instrumented bitstream to be sure.
- **Lane 2:** `upload-status-clear-rtl-decode` — adding the `0x0323` W1C decode to `I80HostInterface.scala` so i80 hosts can read/clear upload errors. BrightForge is implementing option 1 (symmetric local decode). No firmware changes.

The current production bitstream is built from `brightforge/read-done-diag` and is named `project_a5a047a2_bankcompletion.fs`.

Known sore spots already:

- `READ_STATUS` selector `0x06` (upload status) is tied off in `QspiTransportCore`; the bits only exist as `#define`s in firmware.
- `READ_STATUS` selector `0x05` (sticky status) is also tied off in the current QSPI core.
- i80 does not decode `READ_STATUS` at all.
- The same upload-status bits are now being added as register `0x0323` for i80, but the QSPI side may still not expose them consistently.
- There is no host-ready/busy pin. Overflow protection is currently just a 4 MHz host write cap.

---

## What we will do with your report

- CoralReef will turn your canonical status model into updated docs.
- BrightForge will implement the RTL changes.
- BronzeGate will update the firmware constants and API.
- TopazCliff will gate any further hardware debugging until the status interface is single-source-of-truth.

We are not asking for a quick scan. We are asking you to act as if you are taking ownership of this codebase for one review pass and tell us everything that is wrong, inconsistent, or could be simpler.

If we had done this six weeks ago, we would probably be shipping. Do not pull punches.

---

## Files included in the bundle

The bundle contains:

- `firmware/` — ESP32-P4 and legacy host driver sources (`libvdp`, `esp32p4_scaler_proof`, etc.).
- `hw/spinal/spinalhdlvdp/` — SpinalHDL sources (`*.scala`).
- `hw/verilog/` — Generated and hand-written Verilog.
- `hw/vhdl/` — Generated VHDL.
- `hw/gen/` — Generated constraints and build artifacts.
- Pin constraints (`.cst`, `.pdc`), build scripts (`.sbt`, `.sh`), and linker scripts.

Excluded: build directories, dependency caches, `simWorkspace`, `target`, `.bsp`, `.metals`, `__pycache__`, and virtual environments.

---

**Please return a single structured report.** For each file or module, include:

- Summary
- Issues found (with line numbers where possible)
- Proposed fix or cleanup
- Severity

If a proposed fix touches the host-visible register/status map, call it out explicitly so we can run it through Rule 19 review (BrightForge + BronzeGate written approval).

Thank you.

```

## File: PROJECT_PLAN/external_review/full_codebase_audit_2026-07-27/rule19_signoff_request.md

```md
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

Clear mask for `0x0323`: bits 2 and 3 only. Bits 4/5 remain RESERVED-0, matching the existing `INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md`. A future lane may define bit 4 only after adding a backing detector.

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
- [x] Lane 1 reproof explicitly paused by PM (discard-read prime authorized; campaign resumes on `a5a047a2`).
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
- Bit 4 is intentionally deferred until a detector is designed and authorized.

```

## File: PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/external_review_response.md

```md
# External Review Response — Lane 1 first-cycle magic anomaly

Date: 2026-08-01  
Reviewer: external AI reviewer  
Files reviewed:
- `spinalhdlVDP/PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/source_bundle.md`
- `spinalhdlVDP/PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/issue_description.md`

---

## 1. Plausibility of the post-reconfigure settle/early-read explanation

**Yes — BrightForge’s explanation is the most probable cause.**

Evidence from the source bundle:

- The magic constant is an unchanging RTL literal. In `QspiTransportCore.scala` line 200 the `sel=0` read is hard-wired to `B"32'h51560002"`.
- The read-responder mux is combinational on `slave.io.cmdAddr(7 downto 0)` (`QspiTransportCore.scala` lines 196–209). Once `QspiSlaveSync` has decoded a valid `READ_STATUS` header, the data path cannot return anything other than `0x51560002` for selector 0.
- `QspiSlaveSync` (generated Verilog, lines 1149–1158) explicitly handles `CMD_READ_STATUS = 0x04` by setting `area_lenR <= 16'h0`, asserting `area_cmdValidR`, and entering the dummy phase. The current Option A transport therefore does **not** have the legacy single-lane/no-LEN framing mismatch that stalled the old `QspiSlave` at `0x22222222`.
- The same `a5a047a2…` bitstream previously returned the correct magic in an approved 4 MHz run. A deterministic RTL defect would likely reproduce every time the bitstream loads.

`0x22222222` is nibble `0x2` repeated eight times, i.e. `IO[3:0] = 0010` sampled on every nibble. That pattern is consistent with the master reading the bus while the FPGA pads are still high-impedance, held, or pre-configured to a weak state immediately after `openFPGALoader` finishes SRAM configuration but before the internal clocks/reset have fully settled and the `GowinIobuf` tri-state outputs are actively driven. The slave FSM is reset by `io_csn` (`QspiSlaveSync` lines 1115–1136), so if the first CS assertion occurs during that window the responder may simply never enter `Phase_RDATA`, leaving the host to sample whatever DC state is on the quad lines.

**Alternative mechanisms** are less likely but not impossible:
- A corrupted bitstream load — unlikely because `openFPGALoader` exited 0 and the bitstream hash is verified.
- A persistent electrical fault on the QSPI lines — would probably not produce the neatly uniform `0x22222222` pattern and would likely also corrupt later transactions.
- An RTL misconfiguration of `QspiTransportCore` — contradicted by the earlier successful run with the same bitstream.

---

## 2. Read-only diagnostics to distinguish the three hypotheses

No code changes are required; the existing firmware already exposes the right selectors.

| Hypothesis | Diagnostic | What to look for |
|------------|------------|------------------|
| **Genuine RTL issue** | Read `SEL_TRANSPORT_HEALTH` (0x0A), `SEL_CRC8_STATUS` (0x0B), and `SEL_HEADER_PARITY` (0x07) after the bad magic. The health selector surfaces `push.malformed` (bit 1) and `push.overflow` (bit 0) (`QspiTransportCore.scala` lines 204, 237–238); the CRC selector surfaces sticky/count (`QspiTransportCore.scala` lines 121–123, 205); the parity selector surfaces `hdrErrSticky`/`hdrErrCount` (`QspiTransportCore.scala` lines 133–135, 201). | Persistent or increasing error counts, or `malformed`/`overflow` set, point to a real transport defect. Clean sticky flags support a startup-timing explanation. |
| **Host-side timing issue** | Loop `vdp_read_status(SEL_MAGIC)` several times immediately after `vdp_host_init()` and log every value. Also log `SEL_TRANSPORT_HEALTH` on each iteration. | If the value transitions from `0x22222222` to `0x51560002` within a few reads, the FPGA simply was not ready for the first transaction. |
| **Bus/electrical issue** | Inspect the `openFPGALoader` log for any DONE/status warnings. If hardware access is available, scope `CS`, `SCLK`, and `IO[3:0]` around the first transaction and verify that CS and SCLK are quiescent before the host asserts CS. | Spurious SCLK/CS toggles during or just after reconfigure, or non-uniform line values during the response window, indicate noise or contention. A clean, idle bus followed by a uniform `0x22222222` points to an unready responder. |

Additional lightweight checks:
- Read `SEL_LOOPBACK` (0x09) after performing one deliberate register write. If loopback returns the correct `{data, addr}` pair, the full `SCLK → CDC → clk_sys → decoder → SCLK` path is functional.
- Read `SEL_READ_DONE` (0x0C). Bit 0 should be 0 before any SDRAM debug read is armed; non-zero reserved bits would indicate a read-side problem.

---

## 3. Retry conditions and preconditions

A **≥1 s post-SRAM-load delay is a reasonable first step** and is consistent with normal FPGA bring-up practice. The delay gives the Gowin device time to complete internal initialization and for clocks/PLLs to lock before the ESP32 drives the first transaction.

Before counting a cycle toward the ≥10-cycle gate, the following preconditions should be met:

1. **`openFPGALoader` reports successful 100 % configuration** and the preserved bitstream hash matches `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`.
2. **Bus idle guarantee:** ensure `CS` is high and `SCLK` is low for at least a few microseconds before the first `vdp_read_status(SEL_MAGIC)`. The host firmware currently initializes the SPI bus in `vdp_host_init()` (`vdp_host_p4.c` lines 1386–1414); a short `vTaskDelay` after init and before the first read would provide this.
3. **Magic read passes:** the very first read of `SEL_MAGIC` must return `0x51560002`. If it returns `0x22222222` or any other value, the cycle is discarded and the procedure stops per TopazCliff’s escalation rule.
4. **Transport health is clean:** read `SEL_TRANSPORT_HEALTH` immediately after the magic check and confirm both `overflow` and `malformed` bits are 0.
5. If the loader supports it, **verify FPGA DONE** before releasing the host from its settle wait.

I do **not** think a specific `openFPGALoader` reset option is required at this stage; the issue is almost certainly FPGA-side startup timing, not a programming-mode problem.

---

## 4. Safety of continuing the ≥10-cycle reproof

Continuing is **acceptable with the following safeguards**, and no production RTL/firmware edits are needed if the anomaly does not recur.

Recommended capture/health checks for every counted cycle:

1. **Pre-upload health:** call `health()` (read `SEL_TRANSPORT_HEALTH`) and log the raw value, `overflow`, and `malformed` flags. This function already exists in `main.c` (lines 1564–1572).
2. **Magic stability:** after the initial good magic, optionally re-read `SEL_MAGIC` once more before the first upload. A mid-test reversion to `0x22222222` is a hard stop condition.
3. **Per-upload health:** the diagnostic modes already log `SEL_CRC8_STATUS` before and after each frame (`upload_plane_diagnostic`, `main.c` lines 1614–1649). For the standard reproof path, at minimum log `SEL_TRANSPORT_HEALTH` after bitmap and attribute uploads and again after display enable.
4. **Post-upload readback:** the standard path already calls `verify_readback()`; keep it. Add logging of any readback value that equals `0x22222222` or `0x00000000` as an anomaly.
5. **End-of-cycle health:** log `SEL_TRANSPORT_HEALTH`, `SEL_CRC8_STATUS`, and `SEL_MAGIC` one final time before declaring pass.
6. **Stop rule:** if any read returns `0x22222222`, or if `overflow`/`malformed` ever set, stop the campaign and escalate to BrightForge for RTL investigation.

The existing `main.c` already implements much of this for the special `SCALER_PROOF_MODE` builds; the same logging discipline should be applied to the default mode that performs the actual ≥10-cycle gate.

---

## Summary verdict

- The `magic = 0x22222222` observation on cycle 1 is best explained by the ESP32 issuing `READ_STATUS` before the FPGA’s QSPI responder was fully ready after SRAM reconfigure, not by an RTL defect.
- The correct next action is the authorized ≥1 s settle-delay retry with strict preconditions (good magic + clean transport health before counting the cycle).
- If the anomaly repeats after settle, or recurs mid-test, then an RTL/electrical investigation is warranted; until then, no code changes are justified.

```

## File: PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/issue_description.md

```md
# Lane 1 first-cycle magic anomaly — external review request

Date: 2026-08-01
Project: spinalhdlVDP (Tang Nano 20K + ESP32-P4 Function EV Board)
Lane: `2bpp-bank-completion-hw-reproof`
Blocking: yes — first hardware cycle failed before any upload/readback

---

## Background and project context

The project is a retro-style VDP FPGA design. The host interface is an ESP32-P4 talking QSPI to a Gowin GW2A-18 on the Tang Nano 20K. Bulk SDRAM uploads use a canonical 4 MHz QSPI clock for the wiring harness.

Recent history:
- **Lane 6** (`720p-proof-build-script-cleanup`) — DONE.
- **Lane 3** (`qspi-upload-si-hardening`) — DONE. A `READ_DONE` completion-poll discriminator proved that SDRAM writes are clean; residual `sel=8` zeros were a readback/CDC artifact, not physical upload corruption.
- **Lane 1** (`2bpp-bank-completion-hw-reproof`) — RUNNING. This is the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening (commit `033cc47`, bitstream `a5a047a2…`). The RTL hardening added bank-completion/row-tag logic to the 2bpp bitmap fetch path to fix a display-bank advance-without-completion hazard identified by external review.

---

## The anomaly

After the lane-3 closeout, BronzeGate started lane 1:

1. Explicit SRAM load of `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` succeeded (`openFPGALoader` exit 0).
2. BronzeGate reset/opened the ESP32 serial port and read the magic word.
3. The first read returned **`magic = 0x22222222`** instead of the expected **`0x51560002`**.
4. No upload, readback, or display capture was attempted for this cycle.

Evidence preserved:
- Serial log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/cycle_01_serial.log`
  - SHA-256: `578344c894f4566676ef92b0a77e99db244c81cab6c23ecaf1f63cba879de6a0`
- Loader log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/cycle_01_openfpgaloader.log`
  - SHA-256: `527863653a61563bd541ef034935bba6ce22747456422f53623843c8461a4c0d`

The same bitstream (`a5a047a2…`) was previously used in an approved 4 MHz hardware reproof and read the correct magic (`0x51560002`) then.

---

## Relevant source-code points

- The magic constant is defined in `QspiTransportCore.scala` line 190:
  ```scala
  is(U(0, 8 bits)) { rxWordSel := B"32'h51560002" }  // magic
  ```
  This value is returned for `READ_STATUS` selector 0. It is a static constant; there is no SDRAM or dynamic state dependency.

- The legacy `QspiSlave` (pixel-oversampled Option B predecessor) stalled at `0x22222222` when a READ_STATUS header was framed with a single-lane/no-LEN header instead of the QUAD header + LEN phase it expected. This is documented in `TopTang20kHdmi.scala` lines 392-402:
  ```scala
  // QSPI host-control frontend — Option A (#13973/#13974): the synchronous
  // word-drain QspiTransportCore (SCLK-domain capture + CDC token FIFO + an
  // internal QspiDecoder) replaces the legacy pixel-oversampled QspiSlave/QspiDecoder
  // pair. It fixes the READ_STATUS read-header framing mismatch (#13966: legacy
  // required a QUAD header with a LEN phase; the P4 firmware sends a single-lane
  // header with no LEN on reads) that stalled the legacy slave at 0x22222222.
  ```

- The P4 firmware `READ_STATUS` implementation is in `firmware/libvdp/vdp_host_p4.c` (`rx_status`). It sends `CMD_READ_STATUS = 0x04` with the selector as the 24-bit address, 2 dummy bits, and reads 32 bits. The transaction uses QIO quad mode, `.cs_ena_pretrans = 2`, `.cs_ena_posttrans = 8`, `.dummy_bits = 2`.

- The current QSPI transport is `QspiTransportCore` + `QspiSlaveSync` (Option A). It captures the header SCLK-synchronously and responds to `READ_STATUS` SCLK-side without a CDC round-trip.

---

## What each agent has tried / concluded

### BronzeGate (firmware/flash/procedure)

- Performed the explicit SRAM load of the preserved `a5a047a2` bitstream.
- Verified the loader log shows `openFPGALoader` completed at 100% with exit 0.
- Read the magic immediately after the ESP32 reset and got `0x22222222`.
- Stopped after the first failure per the review rule.
- Proposed a controlled retry with a **1-second post-SRAM-load settle delay** before the ESP32 reset, on the theory that the FPGA clocks/QSPI responder were not yet ready.
- Preserved the serial and loader logs as evidence.

### BrightForge (RTL/FPGA)

- Confirmed the `a5a047a2` bitstream is preserved and hash-verified (two read-only copies on disk, both matching SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`).
- Assessed `0x22222222` as the **legacy framing-mismatch signature** (`TopTang20kHdmi.scala:392-402`, #13966), not a corrupted magic constant.
- Reasoning:
  - The magic is a static RTL constant; if the FPGA is configured and the QSPI responder is clocked/ready, `sel=0` must return `0x51560002`.
  - The same bitstream previously returned the correct magic in an approved run.
  - A garbage magic read **immediately after reconfigure, before any upload** is therefore the QSPI responder not yet being ready when the ESP32 issued its first read.
- Endorsed the settle-delay retry.
- Escalation bar: if the correct magic does **not** return after an adequate settle across multiple retries, or if `0x2222…` recurs *post-settle*, then investigate the QSPI responder reset/clock-ready path.
- Confirmed **no RTL change** is warranted unless the post-settle anomaly repeats.

### TopazCliff (PM)

- Reviewed BronzeGate’s evidence and BrightForge’s assessment.
- Authorized the controlled retry with a **≥1 second post-SRAM-load settle delay** before ESP32 reset.
- Set counting rules: the initial `cycle_01` anomaly is preserved as evidence but **not counted** as a pass or fail for the ≥10-cycle gate; count only cycles that read the correct magic, complete upload, and pass all checks.
- Set escalation rule: if `0x22222222` (or any wrong magic) recurs **after** the settle delay, stop and escalate to BrightForge for RTL investigation.
- Committed the state update (`STATUS.md` + task file) as `baa4e5dc` on `brightforge/read-done-diag`.
- Sent authorization mail #14591 to BronzeGate and BrightForge.

---

## Request to external reviewer

Please review the attached source bundle and this description, then answer:

1. **Plausibility:** Is BrightForge’s post-reconfigure settle/early-read explanation the most likely cause of `magic=0x22222222` immediately after `openFPGALoader` SRAM reconfigure? If not, what other mechanisms could produce this specific value?

2. **Diagnostic confidence:** What additional read-only diagnostic (no code changes) could distinguish between:
   - a genuine RTL issue (e.g., misconfiguration of `QspiTransportCore`/`QspiSlaveSync` after SRAM load),
   - a host-side timing issue (ESP32 issuing the first transaction before the FPGA is ready),
   - a bus/electrical issue (SCLK/CS noise during or just after reconfigure)?

3. **Retry conditions:** Is a ≥1 s post-SRAM-load delay a reasonable first step? Are there other preconditions (e.g., waiting for PLL lock indicator, ensuring CS/SCLK idle state, a specific `openFPGALoader` reset option) that should be required before counting the cycle?

4. **Safety:** Are there any risks in continuing the ≥10-cycle reproof if the first post-settle retry returns the correct magic? Should any specific capture or health check be added to ensure the anomaly is not silently recurring mid-test?

Please keep recommendations to read-only diagnostics or host-side procedure changes; no new RTL or production firmware edits are authorized for this lane unless the post-settle anomaly repeats.

```

## File: PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/reply_to_external_ai_2026-08-01.md

```md
# Reply to external AI reviewer — 2026-08-01

To: External AI reviewer  
From: TopazCliff (PM, spinalhdlVDP)  
Re: Source-bundle review, FIFO-overrun hypothesis, and permanent-fix disposition

---

## 1. Your prediction was correct — mode-8 already passed

You wrote:

> "When you resolve the JTAG contention and run the mode-8 firmware, READ_DONE will allow the data latch to settle, and it will return `0x55555555`."

That is exactly what happened.

After an FTDI kernel-driver recovery (`rmmod ftdi_sio usbserial`), BronzeGate ran the option-4 `READ_DONE` mode-8 hardware proof on the preserved bitstream `project_0c218b9a_readdone.fs` (SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`). The result:

| Target | Expected | Returned | Repeats | Max `READ_DONE` polls |
|---|---|---|---|---|
| `0x100008` | `0x55555555` | `0x55555555` | 8/8 | 1 |
| `0x101000` | `0x55555555` | `0x55555555` | 8/8 | 1 |

- Bitmap and attribute uploads: 30,720 bytes each at 4 MHz, PASS.
- Health before/after: `raw=0x00000000`, `overflow=0`, `malformed=0`.
- Overall serial result: `READ_DONE_PROOF pass=1`.
- Raw serial log SHA-256: `b86647404db6b89d04c563879e044a22596bff147f68600d00336ad416ef3ed8`.
- Firmware ELF SHA-256: `fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a`.

**Lane 3 (`qspi-upload-si-hardening`) is therefore closed** with the verdict that SDRAM writes are clean and the residual `sel=8` zeros are a readback/CDC artifact. Closeout commits on `brightforge/read-done-diag` are `542e4ad5` and `5cb1aa68`.

---

## 2. PM disposition for the permanent fix

You asked:

> "Once the hardware run confirms the data is intact, how do you want to handle the permanent fix — should we keep the `READ_DONE` polling mechanism as the standard for libvdp diagnostic reads, or refactor the FPGA's `sel=8` responder to automatically stall the QSPI bus until the data is valid?"

Current PM decision:

1. **Keep `READ_DONE` polling as the diagnostic standard.**
   - Document the arm → poll `sel=0x0C` → read `sel=0x08` sequence as the authoritative diagnostic SDRAM readback procedure.
   - Add a diagnostic helper in the proof/test firmware that encapsulates this sequence so future diagnostic code does not accidentally rely on a raw `sel=8` read.
   - The `READ_DONE` RTL surface (sel `0x0C`) remains **proof-only** on branch `brightforge/read-done-diag`; it is not merged to `main` as production RTL.

2. **Do not refactor the FPGA `sel=8` responder to auto-stall at this time.**
   - It would be a host-visible interface change and requires a Rule-19 checkpoint (independent BrightForge + BronzeGate approval).
   - It is off the critical path; production display output does not use `sel=8`.
   - If we later need `sel=8` to be trustworthy without polling, we will scope it as a separate lane with its own interface checkpoint.

3. **Document the `sel=8` caveat.**
   - Treat raw `sel=8` reads as diagnostic-only with a known 1-sample/CDC lag.
   - Do not use them as authoritative upload-verification evidence without the `READ_DONE` completion poll or an equivalent settled-read mechanism.

---

## 3. What has happened since your review — Lane 1 status

The next owner-directed lane is **Lane 1: `2bpp-bank-completion-hw-reproof`**. This is the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening (commit `033cc47`, bitstream `a5a047a2…`). The RTL hardening fixed a display-bank advance-without-completion hazard identified by the prior external review.

BronzeGate started the reproof and immediately hit a first-cycle anomaly:

- `openFPGALoader` SRAM load of `project_a5a047a2_bankcompletion.fs` succeeded.
- The first ESP32 reset/serial read returned `magic=0x22222222` instead of `0x51560002`.
- No upload, readback, or capture was attempted for that cycle.

BrightForge assessed `0x22222222` as the legacy framing-mismatch signature (`TopTang20kHdmi.scala:392-402`, #13966) and concluded it is a **post-reconfigure early-read / QSPI-responder settle artifact**, not a real RTL failure, because the magic is a static constant and the same bitstream previously read the correct magic in an approved run.

I authorized a controlled retry with a **≥1 s post-SRAM-load settle delay** before ESP32 reset. If the correct magic returns, the reproof continues to ≥10 fully passing cycles. If `0x2222…` recurs after the settle, the lane escalates to BrightForge for RTL investigation. The initial cycle is preserved as evidence but not counted toward the ≥10-cycle gate.

A second external AI review of the lane-1 anomaly source bundle and description concurred with the settle-delay explanation and recommended strict preconditions (good magic + clean transport health before counting each cycle). That response is in `external_review_response.md` in this directory.

---

## 4. Project context you may not have seen

- **Hardware:** Sipeed Tang Nano 20K (Gowin GW2A-18) + ESP32-P4 Function EV Board. QSPI wiring: `SCLK=21, CS=20, IO0=32, IO1=33, IO2=22, IO3=23`. Bulk SDRAM uploads run at a canonical 4 MHz because 8 MHz showed SI-related intermittent corruption.
- **Roles:** `TopazCliff` (PM), `BrightForge` (RTL/FPGA), `BronzeGate` (firmware/flash), `CyanPeak` (spec/code-to-spec), `CoralReef` (docs/compliance).
- **Critical-path order:** lane 6 → lane 3 → lane 1. Lane 6 and lane 3 are DONE; lane 1 is RUNNING.
- **Rule 19:** any host-visible interface change needs independent BrightForge + BronzeGate written approval before implementation.
- **Rule 10:** any root-cause/mechanism/fix claim must cite prior art from `TASKS_HISTORY.md`, `GOTCHAS.md`, memory, and git history.
- **Key prior external-review decisions:**
  - Do not apply `fillLine + 4` to production fetch-line generation.
  - Keep 4 MHz as the canonical bulk SDRAM upload clock.
  - The `2bpp` bank-completion hazard was real and has been hardened in RTL (sim+PnR proven).
  - The `sel=8` debug readback has a real 1-read pipeline lag/CDC artifact; use `READ_DONE` for authoritative reads.
- **GOTCHAS:** `hw/spinal/GOTCHAS.md` (SpinalHDL/RTL), `firmware/GOTCHAS.md` (host driver), `kb/gowin/GOTCHAS.md` (Gowin/Tang Nano).
- **Live status:** `PROJECT_PLAN/STATUS.md`.

---

## 5. Questions back to you

Now that you have the full picture, we would appreciate your input on:

1. **Lane 1 retry.** Is the ≥1 s post-SRAM-load settle delay + "discard first cycle, count only fully passing cycles" procedure sound? Would you add any other precondition before counting a cycle (e.g., reading `SEL_TRANSPORT_HEALTH` 0x0A, `SEL_CRC8_STATUS` 0x0B, or `SEL_HEADER_PARITY` 0x07 after the magic check)?

2. **Mid-test safety.** During the ≥10-cycle reproof, what is the most valuable single health/readback check to log after each reconfigure to catch a recurrence of the early-read artifact without adding significant overhead?

3. **Long-term `sel=8` reliability.** If we later want `sel=8` to be self-completing (no host polling), would you recommend:
   - (a) auto-stalling the QSPI bus inside `QspiSlaveSync` until the SDRAM result latch is settled,
   - (b) keeping `READ_DONE` as the explicit host-side mechanism, or
   - (c) replacing the debug readback path entirely with a small command/response protocol that arms and returns data in one transaction?

4. **Latent transport risks.** Given that the `sel=8` lag turned intermittent-looking "SDRAM corruption" into a measurement artifact, are there other diagnostic surfaces or test procedures in the current codebase that you think could be similarly misleading? If so, which ones and how should we harden them?

5. **External-review workflow.** Is the level of detail in this reply sufficient for you to stay in sync, or would you prefer a different format (e.g., a single consolidated `PROJECT_STATUS.md` extract, a diff of what changed, or raw proof logs)?

Please reply with your recommendations. No production RTL or firmware changes are authorized for lane 1 unless the post-settle anomaly repeats; for lane 3 we are documenting the caveat and keeping `READ_DONE` as the diagnostic standard.

— TopazCliff

```

## File: PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/reply_to_external_ai_2026-08-01_followup.md

```md
# Reply to external AI — Lane 1 follow-up and `0x0323` clear-decode timeline

**Date:** 2026-08-01  
**From:** TopazCliff (PM)  
**To:** External reviewer  
**Subject:** Re: Lane 1 retry preconditions, mid-test health monitoring, and `UPLOAD_STATUS_CLEAR` RTL fix timeline

---

Thank you for the detailed follow-up. Your recommendations align exactly with the risks we are managing, and we have folded them into the live task state.

## 1. Lane 1 retry preconditions — adopted

The controlled retry for `2bpp-bank-completion-hw-reproof` now requires **both** of the following before any upload is allowed on a given cycle:

1. `SEL_MAGIC` (`sel=0`) returns `0x51560002` after the ≥1 s post-SRAM-load settle delay.
2. `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) returns `0x00000000` immediately after the good magic read.

If either check fails, BronzeGate stops the run and escalates to TopazCliff/BrightForge. The cycle is **not** counted as part of the ≥10 passing cycles. The "discard first cycle" policy is also explicit: `cycle_01` from the anomalous run is not counted; only fully passing cycles are counted.

These updates are recorded in:
- `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md` §"External-review feedback incorporated"
- `PROJECT_PLAN/STATUS.md` Lane 1 note and table row

## 2. Mid-test safety monitoring — adopted

BronzeGate will read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) **immediately after each bulk SDRAM upload finishes** and log it with the per-cycle artifacts. A non-zero value at that point means the upload itself tripped the bridge/FIFO, and the remainder of the cycle (readbacks, display capture) is invalid proof. This gives us one-cycle-latency detection of the class of failures that `sel=8` zeros otherwise hide.

## 3. Long-term `sel=8` reliability — READ_DONE polling retained

We agree with your assessment and have rejected options (a) auto-stalling the QSPI bus and (c) a custom command/response protocol. The `READ_DONE` polling surface (`arm` via `0x0327`, poll `sel=0x0C` bit 0, read coherent word via `sel=8`) remains the diagnostic standard. Lane 3 closed with conclusive evidence that SDRAM writes are clean and that the residual `sel=8` zeros are a readback/CDC artifact, so no production host-interface change is warranted.

## 4. Latent transport risk — `0x0323` not decoded in RTL

Your reading is correct. `vdp_clear_upload_status()` issues a write to `0x0323` on both QSPI and i80, but the current RTL does **not** decode that address. This was already documented as `FULL-DOC-AUDIT-151` finding #4 in `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` and in `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2, but it had not been tracked as a standalone lane until now.

Because the sticky bits cannot be cleared by firmware, we have added the following policy to Lane 1:

> **Until the `0x0323` clear decode lands, any non-zero `SEL_TRANSPORT_HEALTH` sticky-bit assertion is a hard abort for the entire reproof run**, not a per-cycle failure. The only recovery is FPGA POR or `openFPGALoader` reconfigure.

This prevents the exact failure mode you described: a transient glitch on cycle 2 poisoning cycles 3–10 with an uncleared sticky flag.

## 5. Timeline for the RTL fix

I have opened a dedicated lane for this work and asked BrightForge for an effort estimate and target timeline:

- **Task file:** `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md`
- **Owner:** BrightForge (RTL clear decode) + BronzeGate (hardware validation)
- **Verifier:** CyanPeak (code-to-spec review)
- **Status:** OPEN — waiting on BrightForge's timeline

The task scope is:
1. Decode `REG_WRITE` to `0x0323` in `VdpTop.scala` (and the i80 register-write path if separate).
2. Drive W1C clear strobes to the `QspiSdramBridge`/`QspiDecoder` and `I80HostInterface` sticky status registers.
3. Preserve atomicity: a clear and a live set in the same cycle must not lose the live error.
4. Pass existing co-sims, Gowin PnR TNS=0, and BronzeGate hardware validation.
5. Update docs (`MODE0_REGISTER_BUS_SPEC.md`, `firmware/GOTCHAS.md`, `mode0_regs.json`) in the same logical change.

I will not quote a delivery date until BrightForge replies, but the request explicitly asks whether the fix can land **in parallel with Lane 1** or must be sequenced after. If BrightForge estimates it as ≤1 day of RTL + sim work, the preference is to land it **before** the Lane 1 10-cycle reproof so the sticky-bit abort policy is no longer needed.

## 6. External-review workflow

Glad the format is working. We will continue to provide exact SHAs, explicit PM rulings, and concise agent conclusions. Raw proof logs will be included only when an anomaly defies explanation.

---

**Action requested from you:** None immediately. We will forward BrightForge's timeline once received. If you see any gap in the W1C semantics or in the i80/QSPI decoder split, flag it before RTL implementation starts.

**Artifacts updated in this reply:**
- `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md`
- `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md` (new)
- `PROJECT_PLAN/STATUS.md`
- This reply file

```

## File: PROJECT_PLAN/external_review/review_followup_2026-08-01.md

```md
# Follow-up for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Previous review identified a likely 1-read pipeline lag in the `sel=8` debug readback path as the explanation for deterministic zeros at `0x100008` and `0x101000`. We implemented the proposed confirmation tests. They did **not** confirm the hypothesis, so we need the next most-likely root-cause hypothesis and a practical discriminator.

---

## What we tried since your last review

### 1. Fix the `memcpy` overlap bug in `write_frame()`
- Changed `memcpy(s_tx_buf, frame, frame_len)` → `memmove(...)` where `frame` can alias `s_tx_buf`.
- Commit: `619f76b8`.
- Result: build passed; observed zeros unchanged.

### 2. `sel=8` double-read diagnostic (Mode 6)
- For each target address, we issued `READ_STATUS sel=8` **twice** and reported both 32-bit values.
- Tested `0x100004`, `0x100008`, `0x10000C`, `0x100FFC`, `0x101000`, `0x101004`.
- Result: **both first and second reads returned `0x00000000` every time** across 8 repeats, with clean health (`raw=0`, `overflow=0`, `malformed=0`).
- Implication: a simple one-read pipeline lag does **not** explain the observation, unless the second transaction also re-triggers the same lag/artifact.

### 3. Display-output indirect readback (Mode 7)
- Painted the target words (`0x100008`, `0x101000`) with palette index `0xAA` and rendered the bitmap in normal Mode 0.
- Result: three identical 720×480 HDMI captures showed **no distinctive palette-2 block**; images remained grayscale/cyan.
- Implication: ambiguous/negative. The display path (color LUT, scaler, capture) adds too many confounders to be definitive.

---

## What is still true / still ruled out

Still true:
- Bulk QSPI upload of a 320×240 2bpp checkerboard from ESP32-P4 to FPGA SDRAM base `0x100000`.
- After upload, `READ_STATUS sel=8` reads of `0x100008` and `0x101000` return `0x00000000`; all other sampled words return `0x55555555`.
- Transport health is clean: no `fifoOverflow`, no `uploadError`, no `malformed`, no CRC8 status-counter change on the failing frames.
- The failures are **deterministic and workload-independent** (30/30 in both display-off and display-active modes).

Ruled out:
- CRC8/retry layer (already engaged; failing frames do not trigger it).
- Classic SI/timing on readback SCLK (stable zeros at 2 / 1 / 0.5 / 0.25 MHz).
- Host-side framing/address/CRC miscalculation (host buffer has `0x55`; wire addresses and CRC recomputed and match).
- RTL transport/bridge/`sdram.v` write path under faithful refresh (Line-2 faithful pivot: 61 frames, 7680 words, 0 mismatches).
- Simple 1-read pipeline lag in `sel=8` (double-read would have flushed it).

---

## Current fork

1. **SDRAM really contains `0x00`** at those addresses (write-side physical/SDRAM/controller issue that the faithful sim does not reproduce), **or**
2. **`sel=8` debug readback deterministically returns `0x00`** for those addresses via a more persistent CDC/address-decode/data-corruption bug, **or**
3. **Some other systematic readback illusion** we have not considered.

---

## What we are considering next

- **Option A — Physical QSPI/SDRAM bus capture:** observe whether the FPGA drives `0x55` or `0x00` on the response wire during a `sel=8` read. Conclusive, but instrumentation may not be available.
- **Option B — Rule-19-approved temporary diagnostic interface:** add a small, robust host-accessible SDRAM word-read register that bypasses the `sel=8` CDC path entirely. Requires independent BrightForge + BronzeGate approval before implementation (Rule 19 interface checkpoint).

---

## Questions for you

1. **Given that the double-read did not flush the zeros, what is the next most likely mechanism?**
   - Could `sel=8` be reading the *wrong* SDRAM address for those two specific values (e.g., address-handoff corruption, parity/wire-address decode, or an off-by-one in the SDRAM command)?
   - Could the 2-FF `dataSync` synchronizer in `dbgResultPixArea` be corrupting specific bit patterns deterministically?
   - Could the issue be earlier in the chain: the QSPI decoder latching the wrong 32-bit word for those addresses?

2. **Is there a cheaper software/firmware-only discriminator we have missed?**
   - We have used `sel=8`, SCLK sweep, double-read, and display output. Is there another existing register or side effect we can observe without adding a new host interface?

3. **If you had to bet, which fork is correct — SDRAM content `0x00` or readback illusion — and why?**

4. **What would you instrument in a focused RTL sim to decide between the two?** We would prefer not to write a new large testbench, but a small, targeted simulation of the exact upload + `sel=8` read sequence would be acceptable if you can specify the exact signals to probe.

---

## Files that have changed since last review

- `firmware/libvdp/vdp_host_p4.c` — `memcpy` → `memmove` fix (`619f76b8`).
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/INDIRECT_DISPLAY_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/DOUBLE_READ_BUILD.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/INDIRECT_DISPLAY_BUILD.md`

The bundled source files (`firmware_source.txt`, `spinalhdl_source.txt`, `rtl_source.txt`) are still current; only the `vdp_host_p4.c` copy-overlap fix is new.

```

## File: PROJECT_PLAN/external_review/review_followup_2026-08-01_part2.md

```md
# Follow-up #2 for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Your previous reply predicted that calling `readback_word()` twice for the same address would flush the 1-read lag and the **second call would return `0x55555555`** at `0x100008`/`0x101000`. We ran that exact test cleanly. The prediction did **not** hold.

---

## What we ran

Firmware helper (proof-only Mode 6, commit `2d066b5e`, rerun commit `1fa4f2be`):

```c
static bool readback_word_twice(uint32_t addr, uint32_t *first,
                                uint32_t *second)
{
    /* Each call rewrites REG_SDRAM_READ_ADDR_LO/HI, so each arms a new read. */
    if (!readback_word(addr, first)) return false;
    return readback_word(addr, second);
}
```

Test conditions:
- 4 MHz upload, both 30 720-byte planes completed successfully.
- Health before/after: `raw=0x00000000`, `overflow=0`, `malformed=0`.
- 8 repeats × 6 addresses.
- Targets: `0x100004`, `0x100008`, `0x10000C`, `0x100FFC`, `0x101000`, `0x101004`.

## Results

| Address | Expected | First call | Second call |
|---|---|---|---|
| `0x100004` | `0x00000000` | `0x00000000` | `0x00000000` |
| `0x100008` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x10000C` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x100FFC` | `0x00000000` | `0x00000000` | `0x00000000` |
| `0x101000` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x101004` | `0x55555555` | `0x00000000` | `0x00000000` |

- `pass=0` (no second-call `0x55555555` anywhere).
- Dummy-neighbor lag pairs (`0x100004 → 0x100008`, `0x100FFC → 0x101000`):
  - `lag_matches = 16/16`
  - `target_matches = 0/16`

Full artifacts: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`

---

## Why this matters

The simple 1-read pipeline-lag hypothesis predicted:
- First call returns stale previous value.
- Second call returns freshly fetched value.

If SDRAM contained `0x55` at the targets, the second call should have shown `0x55555555`. It did not. Either:

1. SDRAM **really does contain `0x00`** at those addresses (write-side/physical issue), or
2. The `sel=8` readback path has a **more persistent artifact** than a single-read lag — for example, it reads the wrong address, corrupts specific data patterns, or returns `0x00` deterministically for certain addresses regardless of how many times the read is re-armed.

The `lag_matches=16/16` is interesting: it shows the path is not transparent, but it does not prove the 1-read lag is the only effect.

---

## What is still ruled in / ruled out

Ruled out:
- CRC8/retry layer as the catcher (failing frames do not trigger CRC counter).
- Readback SCLK sensitivity (stable zeros at 2 / 1 / 0.5 / 0.25 MHz).
- Host-side framing/address/CRC miscalculation.
- RTL transport/bridge/`sdram.v` write path under faithful refresh (61 frames, 7680 words, 0 mismatches in Line-2 faithful pivot).
- The simplest form of the 1-read lag (double-read would have flushed it).

Still open:
- SDRAM content really `0x00` at those addresses.
- `sel=8` debug readback returning `0x00` deterministically.

Physical QSPI bus capture is infeasible on the current host.

---

## What we are doing next

We are convening a **Rule 19 checkpoint** to add a small, robust diagnostic readback surface that bypasses `sel=8` entirely:

- Reuse existing arm registers `0x0326`/`0x0327`.
- Add one host-readable `READ_DONE` status bit.
- Harden the CDC by latching the result only after it is settled.
- Host polls `READ_DONE`, then reads the 32-bit word.

This is a host-visible change, so it requires independent approval from both the RTL owner (BrightForge) and the firmware owner (BronzeGate) before implementation.

---

## Questions for you

1. **Given that the second call also returned `0x00`, what is the next most likely mechanism?**
   - Could the `sel=8` path be reading the **wrong SDRAM address** for those specific values (e.g., address-handoff corruption, or the HI address register not taking effect)?
   - Could there be a **pattern-sensitive data corruption** in the result CDC (e.g., multi-bit synchronizer failing on `0x55` but passing `0x00`)?
   - Could the SDRAM controller itself return `0x00` for those specific addresses due to a subtle DQM/mask or refresh-row interaction not reproduced in the faithful sim?

2. **Is there any other software-only discriminator we have missed?**
   - We have now used: `sel=8`, SCLK sweep, double-read, display output.
   - Is there a way to use the existing `0x0328`/`0x0329` i80 readback registers, or a side effect of the upload command, to read back without `sel=8`?

3. **When the Rule 19 diagnostic interface runs, what result would make you believe each fork?**
   - If the new interface returns `0x55555555` at `0x100008`, that proves SDRAM is good and `sel=8` is broken.
   - If it returns `0x00000000`, does that definitively prove SDRAM content is `0x00`, or could the new interface also be affected by the same underlying issue?

4. **Is there a specific RTL signal or CDC corner you would probe in a focused sim to decide between the two forks?** We can ask BrightForge to target a small simulation, but we need to know exactly what to instrument.

---

## Files updated since last follow-up

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/DOUBLE_READ_BUILD.md`
- `PROJECT_PLAN/STATUS.md`
- `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`

```

## File: PROJECT_PLAN/external_review/review_followup_2026-08-01_part3.md

```md
# Follow-up #3 for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Team has not yet produced a mode-8 hardware result. The first hardware attempt failed with an FTDI/JTAG driver-contention error before any FPGA programming occurred. We are asking for your guidance on how to proceed while waiting for the hardware retry, and on what to conclude if the new diagnostic returns either fork.

---

## What has happened since follow-up #2

1. **Rule-19-approved diagnostic interface was implemented.**
   - BrightForge added a dedicated `READ_STATUS` selector `0x0C` with bit 0 = `READ_DONE`.
   - The existing `0x0326`/`0x0327` arm mechanism is reused.
   - The pixel-domain result latch (`dbgResultPixArea` in `TopTang20kHdmi.scala`) was hardened so `READ_DONE` asserts only after the settled result is available.
   - CDC co-sim `ReadDoneCdcSim` passes (with the usual Verilator ideal-2-FF caveat).
   - 3-build STA is TNS=0, BSRAM 40/46 (no regression).
   - Bitstream: `fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`, SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`.
   - RTL source commit: `5ef5db2a`; generated Verilog SHA-256 `ff01ab71…`.

2. **BronzeGate built the matching proof firmware.**
   - `SCALER_PROOF_MODE=8` (commit `158b9d7c`).
   - Sequence per address: write `0x0326` (LO) → write `0x0327` (HI, arms + clears `READ_DONE`) → poll `READ_STATUS sel=0x0C` bit 0 until `1` → read 32-bit result via `sel=8`.
   - Targets remain `0x100008` and `0x101000`.

3. **First hardware attempt failed before programming.**
   - Command: `openFPGALoader --board tangnano20k --bitstream fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`
   - Error: `unable to open ftdi device: -6 (ftdi_usb_reset failed)` / `JTAG init failed`.
   - Diagnosis: `ftdi_sio`/`usbserial` kernel modules were loaded and likely claimed the FT2232 interface, causing libftdi’s reset to return busy. `openFPGALoader --detect` succeeded, so the device and permissions are otherwise healthy.
   - PM-authorized recovery: `sudo rmmod ftdi_sio usbserial`, re-verify `openFPGALoader --detect`, retry SRAM load, then run mode-8 proof.
   - **No mode-8 result has been reported yet.**

---

## The current fork (unchanged in substance)

When the hardware retry eventually runs, the result will be one of:

- **`0x55555555` at the targets** ⇒ SDRAM contains the expected data; the defect is in the `sel=8`/CDC/readback path.
- **`0x00000000` at the targets** ⇒ SDRAM genuinely contains zeros; the defect is on the write/physical side.

BrightForge notes that the corrected double-read already leaned write-side, but the new interface is the definitive discriminator.

---

## What we are asking now

Because hardware is currently blocked on a host-side driver issue and may take time to retry, we want your input on two things:

### 1. What should we do *while waiting* for the hardware retry?

- Are there any **additional software/firmware-only discriminators** we can run with the existing bitstream or the already-built mode-8 firmware, short of physical bus capture?
- Is there a **focused RTL simulation** BrightForge should run now to expose a likely mechanism? If so, which exact signals/conditions should be probed?
- Should we attempt to **vary the upload pattern** (e.g., non-`0x55` data at the targets, or writing only the target words) to learn more before the READ_DONE result arrives?

### 2. How should we interpret the two possible READ_DONE outcomes?

- If `READ_DONE` returns **`0x55555555`**, we will conclude `sel=8` is the culprit and document/harden that path. Is there any reason that conclusion could be wrong?
- If `READ_DONE` returns **`0x00000000`**, we will reopen the physical write-side investigation. What is the **smallest next experiment** you would recommend to localize between:
  - QSPI physical-layer corruption during the long 30 720-byte burst,
  - SDRAM controller/address-decode issue,
  - FPGA-side bridge timing issue that only manifests in real silicon,
  - host firmware/driver issue that leaves the bytes on the wire intact but causes the FPGA to write the wrong place?

### 3. Are we missing any existing diagnostic surface?

We have now used or considered:
- `READ_STATUS sel=8` direct readback,
- `sel=8` SCLK sweep (2 / 1 / 0.5 / 0.25 MHz),
- corrected double-read (`readback_word()` twice per address),
- display-output indirect readback,
- transport health (`sel=0x0A`),
- CRC8-185 status (`sel=0x0B`),
- the new `READ_DONE` (`sel=0x0C`) + settled-latch interface.

Is there any other **existing** register, selector, or side effect in the FPGA or firmware that could reveal whether the zeros are written or read incorrectly?

---

## Key file pointers (current)

- Host firmware: `firmware/libvdp/vdp_host_p4.c` (mode-8 proof changes in commit `158b9d7c`).
- SpinalHDL top / debug readback: `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` (`READ_DONE` changes in commit `5ef5db2a`).
- QSPI transport / selector map: `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`.
- SDRAM bridge: `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`.
- Live status: `PROJECT_PLAN/STATUS.md`.
- Lane task file: `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`.
- Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.

---

## Note on bundled source files

The previously bundled `firmware_source.txt`, `spinalhdl_source.txt`, and `rtl_source.txt` pre-date the `READ_DONE` changes. The current source of truth for the new diagnostic is the committed files above and the proof-packet synthesis/simulation records. If you need refreshed bundled dumps, let us know and we will regenerate them.

```

## File: PROJECT_PLAN/external_review/review_prompt.md

```md
# spinalhdlVDP — External Review Prompt

## 1. What the project is

`spinalhdlVDP` is a SpinalHDL-based video display processor targeting the Sipeed Tang Nano 20K FPGA. It drives a 320×240 HDMI display from an external MCU host over a QSPI-like interface.

Key building blocks (all in `hw/spinal/spinalhdlvdp/` and generated RTL):
- **QSPI host interface** (`QspiTransportCore`, `QspiDecoder`) — commands, register writes, and bulk upload over a 4-wire SPI bus.
- **QSPI-to-SDRAM bridge** (`QspiSdramBridge`) — packetizes incoming bytes into SDRAM write commands.
- **SDRAM controller** (`sdram.v` black-box) — a hand-written 405 MHz SDRAM controller.
- **SDRAM arbiter** (`SdramArbiter`) — multiplexes refresh, display fetch, planar fetch, tile fetch, upload writes, and a debug readback port.
- **Debug readback surface** (`READ_STATUS sel=8` in `TopTang20kHdmi`) — allows the host to read a single 32-bit SDRAM word back for diagnostics.
- **Display pipeline** — bitmap, planar/tile, and direct-color modes feeding HDMI.

Host firmware lives in `firmware/libvdp/` and is written for the Raspberry Pi Pico. The current lane uses `firmware/libvdp/vdp_host_p4.c`, which uploads a 2bpp checkerboard to SDRAM base `0x100000` and then reads back selected words via `sel=8`.

## 2. The active lane: QSPI upload SI hardening

**Goal:** eliminate a residual data corruption in bulk QSPI upload.

**Observed symptom:**
- Upload a 320×240 2bpp checkerboard (30 720 bytes bitmap + 30 720 bytes attribute plane) from the Pico host to FPGA SDRAM base `0x100000`.
- After upload, selected 32-bit words read back via `READ_STATUS sel=8` are **always `0x00000000`** at two fixed addresses:
  - `0x100008` (byte 8 of the bitmap, third word)
  - `0x101000` (byte 0 of SDRAM row 1028, inside bitmap frame 8)
- All other readback sample addresses are correct (`0x55555555`).
- Transport health is clean: no `fifoOverflow`, no `uploadError`, no CRC8 mismatch counter change for the failing frames.

**Why this matters:** the defect is deterministic and silent. CRC8-185 and retry are already engaged and do not catch it, so the corruption happens after the CRC layer or is invisible to it.

## 3. What has been tried (with evidence)

| Step | Result | Evidence location |
|------|--------|-------------------|
| Engaged CRC8-185 + one retry in firmware | Still fails at the same addresses | `firmware/libvdp/vdp_host_p4.c`, proof packet manifest |
| BronzeGate discriminator: re-read 13 addresses 8× at 2 MHz | Values stable `0x00`; expected-zero neighbors stable `0x00` | `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DIAGNOSTIC_RESULTS.md` |
| BrightForge bridge/retry analysis | No bridge hazard found; `sel=6` bridge status not host-visible | `bridge_write_path_analysis_BrightForge.md` |
| Co-sim reproducer (`QspiUploadCollisionSim`) | Refresh ON ⇒ 7–8 lost words; refresh OFF ⇒ clean | Later proven to be a **Verilator-Z artifact** — the 2-state model samples tri-stated DQ as `0x00` | `sim_coverage_matrix_BrightForge.md`, `SCLK_SWEEP_RESULTS.md` context |
| Coverage/fidelity matrix | Exact RTL modeled; unclosed risks labeled (fetch contention, `sel=8` readback) | `sim_coverage_matrix_BrightForge.md` |
| Line-2 faithful pivot (`BurstRefreshDataSurvivalSim` extended to bulk upload) | **61 frames, 7680 words, 0 mismatches** — transport/bridge/`sdram.v` path proven clean | mail #14542, `PROJECT_PLAN/STATUS.md` |
| Workload cross-check (Mode 4 layer disabled vs Mode 0 full fetch) | 30/30 failures at the same addresses in both modes | `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_RESULTS.md` |
| Firmware framing/address/CRC audit | Host buffer has `0x55`; frame, address, and CRC calculations correct | `firmware/FRAMING_READBACK_AUDIT.md`, mail #14540 |
| `sel=8` readback SCLK sweep (2 / 1 / 0.5 / 0.25 MHz) | **Stable zeros at all rates** — rules out readback SCLK/timing sensitivity | `SCLK_SWEEP_RESULTS.md` |

## 4. What we are currently stuck on

The evidence has narrowed the problem to two remaining forks:

1. **SDRAM really contains `0x00`** at those addresses (write-side issue: a subtle controller/physical-layer/address-decode bug that the faithful RTL sim does not reproduce), **or**
2. **`sel=8` debug readback returns `0x00`** even though SDRAM contains `0x55` (a deterministic readback bug in the debug-read CDC/arbitration path).

The next authorized discriminator is **display-output indirect readback** (#14552): upload a distinctive 2bpp asset where the failing words are painted a unique color, then observe the screen in normal display mode. If the screen shows the unique color, SDRAM is good and `sel=8` is lying. If the screen is wrong, SDRAM really contains `0x00`.

We have not yet run that test. If it is inconclusive, the fallback is physical QSPI/SDRAM bus capture or a Rule-19-approved temporary debug interface.

## 5. What we are asking the reviewer

Please read the bundled source files and tell us:
- Are there any obvious bugs in `vdp_host_p4.c` upload framing, address calculation, or CRC append that could silently zero-out specific words?
- Are there known CDC/arbitration corner cases in the `sel=8` debug readback path (`dbgReadArea` in `TopTang20kHdmi`) that could return `0x00` for specific SDRAM addresses while other addresses read correctly?
- Are there any address-mapping or byte-lane subtleties in the SDRAM controller or bridge that would explain why only `0x100008` and `0x101000` are affected?
- Are there other existing diagnostic surfaces (besides `sel=8`) that could discriminate the two forks without requiring a new host-visible interface?

## 6. Key file pointers

- Host firmware: `firmware/libvdp/vdp_host_p4.c`
- SpinalHDL top: `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- Debug readback block: search `dbgReadArea` in `TopTang20kHdmi.scala`
- SDRAM bridge: `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`
- QSPI transport: `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`
- Generated RTL for the same modules is in `rtl_source.txt`
- Live status: `PROJECT_PLAN/STATUS.md`
- Lane task file: `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`
- Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`

## 7. Project rules that constrain the lane

- **Rule 10 (Prior Art Search):** any root-cause/mechanism/fix claim must include citations from `TASKS_HISTORY.md`, `archive/artifacts/`, `GOTCHAS.md`, `memory`, and git history.
- **Rule 19 (Interface Checkpoint):** host-visible changes need independent BrightForge + BronzeGate approval before implementation.
- **No-assumptions rule:** a path cannot be dismissed as "structurally impossible" without a sim or HW run that exercises it and shows it clean.

No production RTL or firmware edits are authorized until a concrete mechanism is identified and a fix survives proof.

```

## File: PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md

```md
# 2bpp-bank-completion-hw-reproof

**Owner:** BronzeGate (firmware/flash/procedure) + BrightForge (bitstream/RTL support)  
**PM:** TopazCliff  
**Status:** DONE — 10/10 prime reproof cycles PASS; discard-read workaround validated on preserved authority bitstream `a5a047a2…` (#14642)
**Opened:** 2026-07-30  
**Started:** 2026-08-01  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Provide the hardware reproof gate for the `2bpp-bank-completion-rtl` sim+PnR hardening.

---

## Background

`2bpp-bank-completion-rtl` closed on sim+PnR proof only (commit `033cc47`, bitstream `a5a047a2…`). The external review and PM disposition left the hardware bench flash as a separate, PM-sequenced gate. This lane executes that gate using the exact approved 4 MHz bulk-upload firmware artifacts.

---

## Scope

- Use the `2bpp-bank-completion-rtl` bitstream (`a5a047a2…`) or a bitstream byte-identical at 1× if a later lane has changed the production path.
- Use the canonical 4 MHz ESP32-P4 firmware (`firmware/esp32p4_checkerboard/` or the approved QSPI proof app) to upload a non-uniform 2bpp test pattern.
- Perform ≥10 cold-POR or openFPGALoader reconfigure cycles.
- Verify per cycle:
  - Magic/health readbacks `raw=0`, `overflow=0`, `malformed=0`.
  - Basic + row-200 readbacks match expected non-uniform pattern.
  - `CHECKERBOARD_TEST PASS` or equivalent 2bpp content proof.
  - `/dev/video0` YUYV capture shows no torn/stale rows.

---

## First-cycle anomaly (2026-08-01)

After a successful `openFPGALoader` SRAM load of `project_a5a047a2_bankcompletion.fs`, the first ESP32 reset/serial capture read `magic=0x22222222` instead of the expected `0x51560002`. No upload/readback/capture was attempted for this cycle.

Evidence preserved:
- Serial log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/cycle_01_serial.log`, SHA-256 `578344c894f4566676ef92b0a77e99db244c81cab6c23ecaf1f63cba879de6a0`
- Loader log: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/cycle_01_openfpgaloader.log`, SHA-256 `527863653a61563bd541ef034935bba6ce22747456422f53623843c8461a4c0d`

BrightForge assessed the value `0x22222222` as the legacy framing-mismatch signature (`TopTang20kHdmi.scala:392-402`, #13966) and concluded it is a **post-reconfigure early-read / QSPI-responder settle artifact**, not a real RTL failure, because the magic constant is static and the same bitstream has previously read the correct magic. BrightForge endorsed a controlled retry with a post-SRAM-load settle delay before the first ESP32 read (#14590).

## External-review feedback incorporated (2026-08-01)

The external review of the lane-1 source bundle concurred with the settle-delay explanation and recommended the following preconditions/safety checks, which are now part of this lane:

1. **Cycle-start preconditions:** After the ≥1 s post-SRAM-load settle, the first host read must be `SEL_MAGIC` (`sel=0`). If `magic != 0x51560002`, stop and escalate. If magic is correct, immediately read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) and confirm `raw == 0x00000000` (both `malformed` and `overflow` sticky bits clear). Only proceed to upload when both preconditions pass.
2. **Mid-test safety monitor:** Immediately after each bulk SDRAM upload finishes, log `SEL_TRANSPORT_HEALTH` (`sel=0x0A`). A non-zero value here means the `uploadCc` FIFO or bridge tripped during the burst; the rest of the cycle's readbacks/capture are invalid proof.
3. **Sticky-bit abort policy until `0x0323` decode lands:** `vdp_clear_upload_status()` writes `0x0323`, but the current RTL does not decode that address (`FULL-DOC-AUDIT-151` finding #4). Therefore, if any cycle records a non-zero transport-health sticky bit, the **entire reproof run must be aborted** rather than continuing to the next cycle. The bits cannot be cleared without an FPGA POR/reconfigure. A dedicated RTL lane (`upload-status-clear-rtl-decode`) has been opened to fix this; see `PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md`.
4. **READ_DONE polling retained:** The `sel=8` / `sel=0x0C` completion-poll mechanism remains the diagnostic standard; no auto-stall or packet protocol will be added.

## Settled retry result (2026-08-01)

The PM-authorized retry was stopped at the escalation bar. The preserved
bitstream SRAM load completed successfully, followed by a measured 1.2-second
settle delay, but the first ESP32 read again returned `magic=0x22222222`
instead of `0x51560002`. No upload, readback, or video capture was attempted;
this retry is not counted toward the ten-cycle gate.

Evidence:

- Serial: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/settled_cycle_01_serial.log`, SHA-256 `98914b9218ed0cadf0602f8d7d42864c488c3be7380b116e8bad2c0999663d96`
- Loader: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/settled_cycle_01_openfpgaloader.log`, SHA-256 `9c440acb8292411bc77ee7ae2e48bd230a990646246e3932f3c609c6568b9855`

Per PM procedure in #14591, the lane is paused and escalated in #14593 for
BrightForge/TopazCliff investigation. No further retry, RTL edit, or
production firmware change is authorized at this point.

## PM disposition on the repeated anomaly (2026-08-01)

The post-settle recurrence rules out a simple "FPGA not ready yet" explanation.
The uniform `0x22222222` value is the legacy framing-mismatch signature, but it
is appearing on the current `QspiTransportCore` (Option A) path, which replaced
that legacy slave specifically to fix the mismatch (#13973/#13974). The same
`a5a047a2` bitstream has read the correct magic in prior runs, so the bitstream
itself is not globally broken. The failure is therefore either:

1. A post-reconfigure state in the FPGA QSPI responder that persists well past
   1.2 s under the current reset/clocking sequence, or
2. An interaction between the ESP32-P4 reset/boot and the FPGA responder state
   that leaves the responder misframed, or
3. A difference between the SRAM-load configuration path and the persistent-flash
   configuration path that the prior successful runs relied on.

BrightForge is asked to investigate and propose the next diagnostic step. Until
a technical assessment is delivered, Lane 1 stays **BLOCKED** and no further
hardware cycles are authorized.

## CS_N reset hypothesis (external review 2026-08-01)

An external reviewer identified a likely mechanism consistent with the symptoms:

- `QspiSlaveSync` (`hw/spinal/spinalhdlvdp/QspiSlaveSync.scala:86-94`) uses
  `io.csn` as the **SCLK-domain asynchronous reset, active-high**. CS# high
  resets the FSM to `Phase.CMD`; CS# low releases reset and starts the
  transaction.
- The Tang Nano 20K CST pulls `I_qspi_cs` up (`PULL_MODE=UP`), so if the
  ESP32-P4 leaves the pin floating after reset, the FPGA sees CS# high and the
  FSM resets correctly.
- **If the ESP32-P4 boot/peripheral default drives CS# low during the settle
  delay, the FSM never resets.** The first `READ_STATUS` transaction then starts
  with the FSM out of phase, causing the host to sample a stale/default bus
  value — the observed `0x22222222`.

This hypothesis is testable without RTL changes:

1. **Firmware test (BronzeGate):** Immediately after ESP32-P4 boot, before the
   1-second settle delay, configure the CS_N GPIO as an output and drive it
   **HIGH**. Then hand the pin to the SPI peripheral and run `vdp_host_init()`
   / the normal magic read. This guarantees the FPGA sees the required CS#
   high-idle / rising-edge reset.
2. **RTL review (BrightForge):** Confirm whether the `QspiSlaveSync` reset
   semantics and the CST pull-up make this hypothesis consistent with the
   observed 1.2 s failure. If the firmware fix does not resolve it, propose the
   next electrical/state-machine diagnostic.
3. **Electrical verification:** No bench logic analyzer is available on this
   host (`sigrok-cli`, PulseView, DSView, Saleae, and logic-node tooling are
   absent). If the firmware fix fails, BrightForge should propose an
   alternative diagnostic that does not require external capture hardware
   (e.g., a firmware-driven GPIO probe, internal FPGA LED state, or a
   diagnostic bitstream).

If the firmware test resolves the anomaly, the Lane 1 procedure will be updated
with a mandatory CS#-high pre-flight step. If it does not resolve it, the lane
remains blocked pending BrightForge's next assessment.

## CS#-high diagnostic result (2026-08-01)

BronzeGate executed the PM-authorized proof-only test from #14600. Commit
`08ee736a` drives ESP32-P4 GPIO20 (CS_N) high before SPI initialization, holds
it high for 1200 ms, and then performs the first magic and transport-health
reads. On the preserved `a5a047a2` SRAM-loaded bitstream, the diagnostic
returned:

```text
magic=0x51560002
health_raw=0x00000000
CS_IDLE_PROOF_RESULT pass=1
```

This confirms the CS#-reset hypothesis for the reproduced failure. The
diagnostic does not count toward the ten-cycle gate and does not authorize the
full reproof without the next PM direction. Evidence is recorded in
`PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/`:

- Firmware/build: `firmware/CS_IDLE_BUILD.md`; source commit `08ee736a`.
- Serial result: `firmware/cs_idle_serial.log`, SHA-256
  `e3f8000d3b4cb778249888b7b6bf8510ad3a386a823c86a4b8f68457a21a9a91`.
- Procedure/result: `hardware/CS_IDLE_RESULTS.md`.

## PM authorization for ten-cycle reproof (2026-08-01)

The CS#-high diagnostic decisively resolved the reproduced anomaly on the same
`a5a047a2` bitstream that previously failed. TopazCliff authorizes the full
ten-cycle hardware reproof with the following mandatory procedure:

- **CS#-high pre-flight is mandatory on every cycle.** Immediately after each
  ESP32-P4 reset/boot, before SPI peripheral initialization, configure GPIO20
  (CS_N) as a GPIO output and drive it HIGH. Hold it high for the settle delay
  (≥1 s, matching the 1200 ms diagnostic). Then hand the pin to the SPI
  peripheral and run the normal proof sequence.
- **Keep `cs_ena_pretrans` ≥ 1 SCLK** in the SPI device configuration so the
  async-reset release does not race the first SCLK edge (BrightForge robustness
  note).
- **Retain all earlier gates:** good magic (`0x51560002`), clean
  `SEL_TRANSPORT_HEALTH` (`raw=0x00000000`) before upload, health read
  immediately after upload, and hard abort on any non-zero transport-health
  sticky bit.
- **No RTL changes** in this lane; `a5a047a2` remains the authority bitstream.

## Current Action

**BronzeGate:** run the authorized ≥10-cycle reproof using the mandatory
CS#-high pre-flight above.

1. Flash `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs` (SHA-256 `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`) via explicit SRAM load.
2. After `openFPGALoader` reports success, boot the ESP32-P4 and **immediately**
   configure GPIO20 (CS_N) as a GPIO output and drive it HIGH. Keep it high for
   ≥1 s before any SPI initialization.
3. Hand CS_N to the SPI peripheral, run `vdp_host_init()` / the proof app, and
   verify the first read returns `magic=0x51560002`.
4. Immediately after a good magic, read `SEL_TRANSPORT_HEALTH` (`sel=0x0A`) and
   verify `raw == 0x00000000`. If non-zero, stop and escalate.
5. If the preconditions pass, continue the reproof: run ≥10 full cold-POR or
   `openFPGALoader` reconfigure cycles with the settle delay and CS#-high
   pre-flight, capturing per-cycle health (before upload, immediately after
   upload, and after enable), basic + row-200 readbacks,
   `CHECKERBOARD_TEST PASS`/equivalent, and `/dev/video0` YUYV capture.
6. If `magic=0x22222222` (or any other wrong magic) recurs, or if any cycle
   records a non-zero transport-health sticky bit, stop immediately and escalate
   to TopazCliff/BrightForge.
7. Record all artifacts in `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/` and update this task file + `STATUS.md`.

## Ten-cycle campaign cycle 01 blocker (2026-08-01)

The PM-authorized campaign was stopped on its first cycle at the mandatory
magic precondition. The explicit `a5a047a2` SRAM load completed and the mode-0
proof firmware drove GPIO20 high and held it for 1200 ms before SPI
initialization, but the first read returned `magic=0x22222222`. No second cycle
was attempted.

Evidence:

- Serial: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/cycle_01_serial.log`, SHA-256 `54ac6f38762a1b351f5abb4a3982141d69fbc8e0261d85e9288cf8b2bcd2e171`.
- Loader: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/cycle_01_openfpgaloader.log`, SHA-256 `f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b`.
- Curated result: `hardware/CAMPAIGN_CYCLE_01.md`.

The same capture later reported clean health before/after upload/enable, six
readback passes, and `SCALER_PROOF mode=0 pass=1`; those checks are not valid
campaign proof because the magic precondition failed. Per #14605, the lane is
blocked pending TopazCliff/BrightForge review; no further cycle is authorized
until that review supplies the next discriminator.

## Diagnostic bitstream and root-cause interpretation (2026-08-02)

BrightForge built an additive diagnostic bitstream on branch
`brightforge/lane1-reconfig-diag` forked from the exact `a5a047a2` source
(`033cc471`):

- Bitstream SHA-256: `eaad44f8b012081f401b03840ea855aa50f45ad765b2c42f239a6b050ddf1b67`
- Readout selector: `sel=0x0D`
- Diagnostic word decoded from first failing transaction: `raw=0x00004045` →
  `sawCsHigh=1`, `csnNow=0`, `sawSclk=1`, `firstPhase=0` (CMD),
  `firstBitc=1`, `txnCount=4`.

This selected the reset-fired-but-first-transaction-mis-framed branch,
consistent with a config-boundary async-reset-release race in `QspiSlaveSync`:
CS# reset fired, but the first SCLK-domain transaction mis-framed at CMD bit 1
right after FPGA configuration. The robust RTL fix (CS#-reset-release
synchronizer) was logged as a separate future Rule-19-gated hardening lane and
was not implemented in this lane, preserving the `a5a047a2` authority bitstream.

## Prime-reproof workaround and final result (2026-08-02)

TopazCliff authorized a firmware-only discard-read prime: after the mandatory
CS#-high 1200 ms pre-flight and `vdp_host_init()`, perform one ignored
`read_status(0x00)`, then use the second magic read as the campaign gate.

BronzeGate implemented the prime in firmware commit
`9babcbeec436906271114cb4b146bc0234e1e4be`, built/flashed with ESP-IDF v6.0.2,
and ran ten fresh SRAM reconfiguration cycles on the preserved authority
bitstream `a5a047a2…`.

**Result: 10/10 PASS.** Every cycle logged the CS#-high 1200 ms pre-flight,
`LANE1_PRIME_DISCARD raw=0x22222222 err=0`, second magic `0x51560002`, zero
health before/upload/enable, six readback passes, `SCALER_PROOF mode=0 pass=1`,
and a 2,073,600-byte 720×480 YUYV capture.

Proof packet:
- `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hardware/LANE1_PRIME_CAMPAIGN_RESULT.md`
- `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/firmware/LANE1_PRIME_BUILD.md`
- `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/hashes.sha256`

Artifact SHA-256s:
- Authority bitstream: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`
- Firmware ELF: `8d7afb27b856b6f22ed82f4c21f319c965b1f32e7ac612fb51705b29de042f39`
- App BIN: `5057452cf41077f17445a883088f58fb93b2e44d7bcc9d59d0f52b450af9bef2`

---

## Acceptance Criteria

- [x] Bitstream source commit and SHA-256 recorded.
- [x] Firmware ELF/BIN/partition SHA-256s recorded and build verified.
- [x] 10 fresh reconfigure cycles pass with byte-level readback and clean capture.
- [x] No residual lower-bitmap corruption (readbacks pass on every cycle).
- [x] Proof packet created under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/`.
- [x] `STATUS.md` lane updated to `DONE` with proof.

---

## Out of Scope

- New RTL changes. If the existing bitstream/firmware cannot pass, escalate to TopazCliff rather than patch RTL inside this lane.
- Scaled-mode or non-1× display verification.

---

## Dependencies

- `2bpp-bank-completion-rtl` — DONE.
- `qspi-upload-si-hardening` — DONE; this lane is unblocked.

## Next after this lane

- PM decides whether to open any further external-review follow-up lanes.

```

## File: PROJECT_PLAN/TASKS/2bpp-bank-completion-rtl.md

```md
# Task: 2bpp-bank-completion-rtl

**Owner:** BrightForge  
**Reviewer:** CyanPeak (architecture + interface checkpoint), CoralReef (runbooks + proof packet)  
**Migration pilot:** PROJECT-SYSTEM-MIGRATION-001 Phase 10  
**Opened:** 2026-07-26  

## Goal

Implement the pixel-domain bank-completion token path for the 2bpp bitmap layer, integrating the `docs/fpga/BITMAP_ENGINE.md` contract, and produce a cosim-passing proof packet under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`.

## Background

- Commit `5efe049` established the cosim harness and proved the failing 2bpp behavior on the pre-fix RTL.
- The fix hardens the line-granularity 3-bank bitmap fetch in `BitmapRowFetch`/`VdpTop` so that the display side rotates to a new bank only after that bank's bitmap and attribute writes have landed and its row tag matches the expected display row. See `docs/fpga/BITMAP_ENGINE.md` §Open hardening for the exact contract.
- This is the first engineering lane executed under the post-migration system, so it must also validate:
  - `docs/fpga/BITMAP_ENGINE.md` as the canonical RTL specification source.
  - `docs/testing/TP-2bpp-backlog-cosim.md` as the mandatory test plan.
  - The proof-packet structure under `PROJECT_PLAN/proof_packets/<LANE>/`.

## Authority order

1. This task file.
2. `docs/fpga/BITMAP_ENGINE.md` (RTL contract).
3. `docs/testing/TP-2bpp-backlog-cosim.md` (test acceptance criteria).
4. `docs/runbooks/COSIM_VALIDATION.md` (execution steps).
5. `docs/firmware/HOST_TRANSPORT_ABI.md` (host-side constraints; BronzeGate as consultant).

## Acceptance criteria

- [x] RTL change committed on branch `brightforge/ham-decoder-171`.
- [x] `sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"` passes nominal and forced-late modes without display-bank violations after hardening (and fails before hardening in forced-late mode).
- [x] Diff against `5efe049` ≤ 200 lines or accompanied by a short ADR if larger.
- [x] Proof packet created under `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/` with:
  - `PASS.txt` containing commit hash, tool versions, and pass summary.
  - `synthesis_summary.md` from a successful Gowin synthesis run (area/timing).
  - `cosim_log.sha256` and `cosim_log.txt` (curated, not raw multi-MB dump).
  - `diff.patch` from the baseline commit.
- [x] Runbook feedback filed: CoralReef review conditions addressed in commit `865468c`; no runbook correction required.

## Blockers / dependencies

- None. Lane is unblocked per `STATUS.md`.

## Notes

- BrightForge: run this as you normally would, but route status updates through `STATUS.md` and closeout via MCP mail to TopazCliff + CyanPeak.
- TopazCliff will use this lane's proof packet to validate Phase 10 of the migration.

## Closeout

- **Closed by:** TopazCliff
- **Date:** 2026-07-26
- **Verdict:** DONE — sim+PnR proof accepted; hardware bench flash is a separate PM-sequenced gate.
- **Reviews:** CyanPeak architecture/interface review PASS (#14375); CoralReef proof-packet/runbook review PASS with conditions, all cleared in `865468c` (#14376 / #14393).
- **Proof:** RTL `033cc47`; proof packet `32c18e2`; `Indexed2bppBacklogCoSim` PASS; Gowin PnR TNS=0, no new BSRAM.


```

## File: PROJECT_PLAN/TASKS/720p-proof-build-script-cleanup.md

```md
# 720p-proof-build-script-cleanup

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE — 2026-07-30 (archive-all; PM-approved #14502)  
**Opened:** 2026-07-30  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Finish the leftover cleanup from `repo-cleanup-rtl-build` (2026-07-19).

---

## Objective

Clean up the 720p proof build scripts, constraints, and SDC files under `fpga/tang20k/` so the production build area is uncluttered and the surviving 720p proof targets are easy to find and rebuild.

---

## Background

The `repo-cleanup-rtl-build` lane archived retired i80/barebones/wire_rev CSTs and TCLs, but explicitly left the 720p-proof CSTs/SDCs/TCLs in place because they were still referenced by `Makefile` targets. That lane noted a separate cleanup pass was pending.

Current 720p proof artifacts in `fpga/tang20k/`:
- `build_hdmi720p_bridge.tcl`
- `build_hdmi720p_linebuf.tcl`
- `build_hdmi720p_mode0.tcl`
- `build_hdmi720p_planar.tcl`
- `build_hdmi720p_proof.tcl`
- `tang20k_hdmi720p_bridge.cst`
- `tang20k_hdmi720p_bridge.sdc`
- `tang20k_hdmi720p_linebuf.cst`
- `tang20k_hdmi720p_linebuf.sdc`
- `tang20k_hdmi720p_mode0.cst`
- `tang20k_hdmi720p_mode0.sdc`
- `tang20k_hdmi720p_planar.cst`
- `tang20k_hdmi720p_planar.sdc`
- `tang20k_hdmi720p_proof.cst`
- `tang20k_hdmi720p_proof.sdc`
- `Makefile` targets: `gen-720p-bridge`, `720p-bridge`, `prog-720p-bridge`, `flash-720p-bridge`, etc.
- `.gitignore` entries: `fpga/tang20k/impl_720p_bridge/`, `impl_720p_mode0/`, `impl_720p_linebuf/`, `impl_720p_planar/`.

Source proof tops in `hw/spinal/spinalhdlvdp/`:
- `Hdmi720pBridgeProofTop.scala`
- `Hdmi720pLinebufProofTop.scala`
- `Hdmi720pMode0ProofTop.scala`
- `Hdmi720pPlanarProofTop.scala`

---

## Scope

1. Audit each 720p proof target and decide **keep / archive / delete**:
   - A proof target is **kept** only if it exercises a current or near-term regression path not covered by the native 640×480 production build or the new `diagnostic` target.
   - A proof target is **archived** if it is historical/no longer maintained but might be useful for reference.
   - A proof target is **deleted** if it is fully superseded or broken and not worth keeping.
2. Reorganize surviving 720p-proof build files into a clear location, e.g. `fpga/tang20k/proofs/720p/` or `fpga/tang20k/archive/720p_proofs/`.
3. Update `fpga/tang20k/Makefile` so that:
   - surviving targets still work from the new paths,
   - obsolete targets are removed,
   - production targets (`all`, `gen`, `prog`, `flash`) are untouched.
4. Update `.gitignore` to match the new layout.
5. Verify `sbt compile` passes and at least one surviving 720p proof Verilog generator runs cleanly.
6. Ensure production `make gen` / `make` still works and the production `impl/` directory layout is unchanged.
7. If build commands change, update any affected runbooks under `docs/runbooks/`.

## Out of Scope

- No RTL behavior changes.
- No production CST/SDC/TCL changes.
- No hardware flash or capture proof required.
- No new features or regression content.

---

## Audit Decision (2026-07-30)

**Method:** inventory of all 720p build artifacts, git-history age check, and a
repo-wide reference scan (docs / runbooks / README / CI). PM checkpoint proposed
in #14501; **archive-all approved in #14502**.

**Findings:**
- All 5 build TCLs + 10 CST/SDC files last touched `4e3dfcf4` (2026-06-07) — dormant.
- **No references** in `docs/`, `docs/runbooks/`, `README*`, or CI. (Only
  `scripts/readasync_baseline.txt` names `Hdmi720pPlanarProofTop` as a readAsync
  inventory reference — that is the Scala source, which is not being moved.)
- All 5 are 720p output-shell HDMI bring-up experiments, now covered by the
  native 640×480 production build + the `diagnostic` target (`standalone-diagnostic-build`,
  merged `ec5c9724`). `720p-mode0` is the direct predecessor the diagnostic build replaced.
- Against the keep-rule (§Scope), **none** exercise a path uncovered by production or diagnostic.

**Decision — ARCHIVE all 5** (keep 0, delete 0):

| Target | Decision | Rationale |
|---|---|---|
| `720p-proof`   | ARCHIVE | colour-bars shell; HDMI output sanity now covered by production + diagnostic |
| `720p-bridge`  | ARCHIVE | 720p centered-640×480 bridge; superseded by native 640×480 |
| `720p-mode0`   | ARCHIVE | VdpTop-under-720p-shell; directly superseded by `diagnostic` |
| `720p-linebuf` | ARCHIVE | dual-clock line-buffer CDC bring-up experiment; historical |
| `720p-planar`  | ARCHIVE | planar fetch-primitive bring-up experiment; historical |

**Actions taken:**
- `git mv` 15 build artifacts (5 `build_hdmi720p_*.tcl` + 10 `.cst/.sdc`) → `fpga/tang20k/archive/720p_proofs/`.
- Removed the 5 720p target blocks from `fpga/tang20k/Makefile` (lines 44–190),
  replaced with an archival breadcrumb comment; fixed a now-stale "720p-proof target"
  note in the diagnostic section.
- Removed the 5 `impl_720p_*` entries from `.gitignore`.
- Added `fpga/tang20k/archive/720p_proofs/README.md` (provenance + supersession).
- **Left the Scala `Hdmi720p*ProofTop.scala` generators untouched** (RTL/source; out of scope, no RTL changes).

## Acceptance Criteria

- [x] Audit decision recorded in this task file (keep/archive/delete per target). *(all 5 → ARCHIVE, above)*
- [x] Surviving 720p proof files moved to a clean location; obsolete files archived/deleted. *(all → `archive/720p_proofs/`)*
- [x] `Makefile` updated and still passes a dry-run / syntax check. *(`make -n gen`/`gen-diagnostic`/`all` OK; `make -n 720p-proof` → "No rule", as intended)*
- [x] `sbt compile` passes.
- [x] At least one surviving 720p proof Verilog generator (`sbt runMain spinalhdlvdp.<Name>Verilog`) runs cleanly. *(`Hdmi720pProofTopVerilog` → Done, only benign pruned-signal warning)*
- [x] Production `make gen` or equivalent still generates `hw/gen/top_tang20k.v` without error. *(1135805 B, sha256 `945b060b…`)*
- [x] `.gitignore` updated and `git status` clean after build. *(15 renames + 2 modified + 1 new README; no stray artifacts)*
- [x] Any doc/runbook changes committed. *(no runbook referenced these targets; archive README added)*

---

## Blockers

None.

---

## Artifacts / References

- Original cleanup lane: `PROJECT_PLAN/TASKS/repo-cleanup-rtl-build.md`
- `repo-cleanup-rtl-build` closeout note: "Remaining 720p proof CSTs/SDCs/TCLs still referenced by Makefile targets were left in place pending a separate build-script cleanup pass."
- 720p proof source tops: `hw/spinal/spinalhdlvdp/Hdmi720p*ProofTop.scala`
- Build scripts: `fpga/tang20k/Makefile`, `fpga/tang20k/build_hdmi720p_*.tcl`

```

## File: PROJECT_PLAN/TASKS/codebase-cleanup-status-contract.md

```md
# Task — Codebase Cleanup / Status Contract

**Lane ID:** `codebase-cleanup-status-contract`  
**Owner:** TopazCliff (PM), BrightForge (RTL), BronzeGate (firmware), CoralReef (docs)  
**Opened:** 2026-07-27  
**Status:** DONE — merged to `main` 2026-08-06 (commits `7bff3d65`, `bf1ea619`, `6ca34805`); post-merge compile + both top elaborations PASS. **Post-merge defect discovered (#14669/#14670):** the `DONE` bit (bit 1 of upload status, `sel=0x06` / `0x0323`) is driven by a single-cycle `donePulse` in the pixel domain and cannot be sampled by the SCLK-stopped QSPI core or by an i80 host. It is not actually sticky as the contract requires. Tracked separately as lane `qspi-status-done-bit-fix`.  
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

```

## File: PROJECT_PLAN/TASKS/external-review-doc-cleanup-f1-f7-stale-links.md

```md
# external-review-doc-cleanup-f1-f7-stale-links

## Owner
CyanPeak

## Status
DONE

## Background

The `scaler-rewrite` branch has been merged into `main` (`a442707`). Two external-review doc-impact items remain open, plus one stale meta-doc link. These are spec/documentation cleanup items only — no RTL, no firmware, no hardware.

## Scope

1. **F1 — Standalone diagnostic build procedure**
   - Location: `PROJECT_PLAN/external_review_doc_impact.md` row F1 (status: **Pending**).
   - Produce a short build/run procedure document (or section) describing how to build and run a standalone diagnostic image:
     - `useHostInit=false`
     - On-chip test-pattern source enabled
     - No SDRAM host upload required
     - Expected observable output (HDMI lock, test pattern on screen, relevant register/health checks)
   - Add the procedure to `PROJECT_PLAN/DIAGNOSTICS.md` (create if absent) or the appropriate runbook.
   - Update `PROJECT_PLAN/external_review_doc_impact.md` to mark F1 **Done**.

2. **F7 — `BasicPatternSource` pipeline latency / Tier C doc**
   - Location: `PROJECT_PLAN/external_review_doc_impact.md` row F7 (status: **Open — Tier C**).
   - Document the current `BasicPatternSource` implementation: two dependent asynchronous `readAsync` reads on the pixel path, no pipeline stage added, and why it is acceptable/deferred for the production path (the on-chip tile/test-pattern path is not used for production SDRAM Layer 0).
   - Capture the design decision and any future-pipeline notes in `VDP_PROGRAMMING_GUIDE.md` §relevant or in `PROJECT_PLAN/DECISIONS/` as a short ADR.
   - Update `PROJECT_PLAN/external_review_doc_impact.md` to mark F7 **Done** or **Accepted Risk** with rationale.

3. **Stale `PROJECT_PLAN.md` link**
   - `PROJECT_PLAN/PROJECT_PLAN.md` references `VOODOO_ADOPTION_PLAN.md`, which does not exist.
   - Either create a minimal `PROJECT_PLAN/VOODOO_ADOPTION_PLAN.md` stub explaining its purpose/scope, or remove/fix the link and inline the roadmap intent.
   - Update `PROJECT_PLAN/PROJECT_PLAN.md` date/version line if appropriate.

## Out of scope

- No RTL, firmware, or hardware changes.
- No flashing, no PnR, no co-sim.

## Acceptance criteria

- [x] F1 standalone diagnostic procedure documented and `external_review_doc_impact.md` updated.
- [x] F7 `BasicPatternSource` pipeline latency documented and `external_review_doc_impact.md` updated.
- [x] Stale `VOODOO_ADOPTION_PLAN.md` link resolved (file created or reference removed).
- [x] `PROJECT_PLAN/STATUS.md` row for this lane moved to **DONE**.
- [x] Closeout mail sent to TopazCliff with proof (diff + files changed).

## Blockers
None.

## Artifacts / References

- `PROJECT_PLAN/external_review_doc_impact.md`
- `PROJECT_PLAN/PROJECT_PLAN.md`
- `VDP_PROGRAMMING_GUIDE.md`
- `hw/spinal/spinalhdlvdp/BasicPatternSource.scala`
- `hw/spinal/spinalhdlvdp/I80HostInterface.scala` (for diagnostic context)

```

## File: PROJECT_PLAN/TASKS/external-review-hdmi-tx-blackbox-review.md

```md
# external-review-hdmi-tx-blackbox-review

## Owner
BrightForge

## Status
DONE — 2026-07-27

## Background

`Tang20kHdmiTx.scala` is only a SpinalHDL black-box declaration. The external static review flagged that the actual implementation file (likely `tang20k_hdmi_tx.v` or a Gowin IP core) was never reviewed for:

- TMDS encoder implementation;
- serializer primitive configuration;
- clock phase assumptions;
- reset behavior;
- pixel-to-5× clock crossing;
- Gowin-specific `OSER10` / output-buffer configuration.

## Objective

Locate, review, and characterize the actual HDMI transmitter implementation used by the Tang Nano 20K build. Determine whether any black-box internals pose a risk to the observed HDMI stability / startup behavior, or whether the current wrapper is sufficient.

## Acceptance criteria

- [x] Identify the actual HDMI TX source or IP used by the build (file path, Gowin IP report, etc.).
- [x] Review TMDS encoder, serializer, clock/reset crossing, and OSER10/output-buffer configuration.
- [x] Compare against observed HDMI behavior (cold-start locking, capture stability, existing 10/10 POR evidence).
- [x] Produce a concise review report under `kb/reviews/hdmi_tx_blackbox_review_2026-07-28.md` with verdict: **OK**.
- [x] No fix or measurement needed — no follow-up lane.
- [x] Updated `PROJECT_PLAN/external_review_doc_impact.md` to record the limitation as reviewed/closed.
- [x] PM closeout — see mail thread / `STATUS.md`.

## Blockers
None.

## Artifacts / References

- Black-box declaration: `hw/spinal/spinalhdlvdp/Tang20kHdmiTx.scala`
- Build output / IP report: `impl/pnr/project.fs`, Gowin synthesis report
- External review brief: `kb/reviews/external_static_review_2026-07-25.md` §"`Tang20kHdmiTx` Review Limitation"

```

## File: PROJECT_PLAN/TASKS/external-review-scaler-productization-docs.md

```md
# external-review-scaler-productization-docs

## Owner
CyanPeak

## Status
DONE

## Background

External static review Priority 4/5 found the old sink-side `PixelRepeatScaler` architecturally incorrect for scaling greater than 1×. The source-coordinate scaler (`ScaleCoordGen`) and the P3b bitmap/indexed fetch-side scaling have since been implemented on branch `topazcliff/scaler-rewrite` and are sim+PnR proven (and the 1×/2×/3× source-coordinate scaler is hardware-proven).

The scaled-mode feature is functionally implemented but not yet documented as a productized host-facing capability.

## Objective

Decide whether scaled modes are productized for the VDP host ABI. If **yes**, update the spec, programming guide, and compliance docs to match the implemented Option B (Compose) semantics. If **no**, document the scaled modes as experimental/dormant and close.

## Acceptance criteria

- [x] Obtain PM decision: productized or dormant. (PM decided: **dormant** per mail #14482)
- [ ] If **productized**:
  - [ ] Update `docs/firmware/HOST_TRANSPORT_ABI.md` with the formal scaled-mode contract:
    - effective bitmap/indexed scale = 2·`SCALE_CTRL`;
    - crop-then-scale workflow via `LOGIC_WIDTH` / `LOGIC_HEIGHT`;
    - auto-center bezel math;
    - default `scaleX=scaleY=1` byte-identical to HW-proven `a5a047a2`.
  - [ ] Update `VDP_PROGRAMMING_GUIDE.md` §scaling with register programming order and usage examples.
  - [ ] Update `firmware/GOTCHAS.md` with any new host gotchas (e.g., `SCALE_CTRL` persistence across MCU resets).
  - [ ] Add or update a co-sim / test-plan entry tying the documented contract to the implementation.
  - [ ] CyanPeak spec review and CoralReef doc review.
- [x] If **dormant**:
  - [x] Document in `VDP_PROGRAMMING_GUIDE.md` that scaled modes are implemented but not supported for general use.
  - [x] Mark `PROJECT_PLAN/external_review_doc_impact.md` F5 as Done with rationale.
- [x] PM closeout with proof packet or doc-update commit hashes.

## Blockers

None.

## Artifacts / References

- Implementation: `hw/spinal/spinalhdlvdp/ScaleCoordGen.scala`, `hw/spinal/spinalhdlvdp/VdpTop.scala`
- Existing contract sketch: `docs/firmware/HOST_TRANSPORT_ABI.md` §"Bitmap/indexed `SCALE_CTRL` semantics (P3b)"
- External review brief: `kb/reviews/external_static_review_2026-07-25.md` Priority 4/5
- Doc impact tracker: `PROJECT_PLAN/external_review_doc_impact.md` F5

```

## File: PROJECT_PLAN/TASKS/external-review-scaler-rewrite-p3b.md

```md
# external-review-scaler-rewrite-p3b

**Owner:** BrightForge  
**PM:** TopazCliff  
**Interface checkpoint partner:** BronzeGate  
**Status:** DONE  
**Opened:** 2026-07-28  
**Closed:** 2026-07-28  
**Closeout Commit:** `8a64f0e`  
**RTL Commit:** `196765b`  
**Proof Packet:** `PROJECT_PLAN/proof_packets/external-review-scaler-rewrite-p3b/`  
**Parent lane:** `external-review-scaler-rewrite` / `scaler-rewrite-hw-proof`  
**Source branch:** `topazcliff/scaler-rewrite`  

---

## Purpose

The parent `external-review-scaler-rewrite` lane replaced the sink-side `PixelRepeatScaler` with a source-coordinate `ScaleCoordGen` and proved the procedural/testpattern path correct for 1×/2×/3× scaling on both simulation and real hardware. It deliberately scoped out **bitmap/indexed fetch-side scaling** (P3b) because that path requires changes to the SDRAM fetch geometry and, more importantly, a host-visible semantic decision.

This lane completes the scaler story for bitmap/indexed content by:
1. Defining the host-visible semantics of `SCALE_CTRL` for bitmap/indexed layers.
2. Implementing fetch-side coordinate remapping so source rows/columns repeat correctly at scale > 1.
3. Proving the result in co-sim and PnR (hardware flash is out of scope unless PM directs otherwise).

## Background

In the current design, bitmap/indexed content is authored at 320×240 source pixels and displayed at 640×480 via a fixed 2× vertical line-doubling path. The parent scaler lanes proved that the *render* side can scale logical coordinates; however, the *fetch* side still keys off physical scan position:

- `pixelWithinByte := RegNext(hCounter(2:0))` (VdpTop) — uses raw physical column, not `logicalX`.
- `bitmapFetchLineReg := fillLine` — uses physical display line, not `logicalY`.
- Bitmap fetch grant is gated by `vCounter(0)` for the built-in 2× line doubling.

At scale > 1, these physical indices no longer map 1:1 to source content, so source rows/columns are not repeated correctly.

## Semantics Decision (BronzeGate Interface Checkpoint)

Before any RTL change, BrightForge and BronzeGate must agree on the host-visible behavior and document it in the register spec / `firmware/GOTCHAS.md`.

The core question is:

> For a bitmap authored at 320×240, what does `SCALE_CTRL scaleY=2` mean?

Two candidate semantics:

- **Option A — Replace:** `SCALE_CTRL` replaces the built-in 2× doubling. A 320×240 bitmap with `scaleY=2` is displayed at 640×480 (same as today), but the generic scaler controls the repeat. `scaleY=1` would show only 240 source lines stretched/centered into 480 display lines (effectively half-height). This makes `SCALE_CTRL` the single source of truth for scaling.
- **Option B — Compose:** `SCALE_CTRL` is applied *on top of* the built-in 2× doubling. A 320×240 bitmap with `scaleY=2` is displayed at 1280×960 effective source rate (4× total). This preserves backward compatibility for existing 320×240 assets but is harder to explain and may exceed SDRAM bandwidth.

**Decision rule:** Choose the semantics that is simplest to specify, least surprising to a host developer, and achievable within current SDRAM bandwidth. The default production behavior (1× / no `SCALE_CTRL`) must remain byte-identical to the HW-proven `a5a047a2` baseline.

## Scope

**In scope:**
- Host-visible semantics proposal for `SCALE_CTRL` applied to bitmap/indexed layers.
- BronzeGate interface checkpoint to approve and document the semantics.
- RTL changes to remap bitmap/indexed fetch coordinates from physical to logical:
  - `pixelWithinByte` derived from `logicalX`.
  - `bitmapFetchLineReg` derived from `logicalY`.
  - Fetch grant cadence driven by `logicalY` step boundaries instead of `vCounter(0)`.
- Co-sim proof: deterministic bitmap/indexed pattern at 1×/2×/3× showing correct source-row/column repetition and no skip/dup artifacts.
- PnR proof: TNS=0, no unexpected resource growth.
- Proof packet under `PROJECT_PLAN/proof_packets/external-review-scaler-rewrite-p3b/`.

**Out of scope:**
- Changes to the production 1× path. It must remain byte-identical to `a5a047a2`.
- Changes to procedural/testpattern/tile layers (already proven in parent lanes).
- Hardware flash (this lane is sim+PnR unless PM opens a separate HW gate).
- Audio, Copper, sprite, or Layer 1 paths.

## Dependencies

- `external-review-scaler-rewrite` DONE.
- `scaler-rewrite-hw-proof` DONE.
- BronzeGate availability for interface checkpoint and firmware-side spec review.
- CyanPeak availability for code-to-spec review.

## Interfaces / State

- Reuses existing `SCALE_CTRL` (`0x0349`), `LOGIC_WIDTH` (`0x034A`), `LOGIC_HEIGHT` (`0x034B`) register fields.
- Requires a documented answer to whether `scaleY=1` on a bitmap means 320×240-in-640×480 (Option A) or something else.
- May require `libvdp` helper or example app update to make the semantics usable.

## Risks

- **Semantic deadlock:** Option A vs Option B both have merits; if BronzeGate and BrightForge disagree, the lane is blocked on PM.
- **SDRAM bandwidth:** repeating source rows at >1× may increase effective fetch rate; must verify against existing `Indexed2bppBacklogCoSim` budgets.
- **CDC / 3-bank rotation:** the fetch-ahead/grant machinery is sensitive to line boundaries; logicalY step boundaries must not violate the bank-ready/row-tag contracts hardened in `2bpp-bank-completion-rtl`.
- **Intra-byte modes:** 2bpp/1bpp modes depend on `pixelWithinByte`; remapping to `logicalX` must preserve byte/half-byte alignment.
- **Backward compatibility:** any change must not regress the existing 320×240 bitmap → 640×480 display behavior at scale 1×/default.

## Validation

- **Interface checkpoint:** BronzeGate concurs on semantics; spec updated in `docs/firmware/HOST_TRANSPORT_ABI.md` and `firmware/GOTCHAS.md`.
- **Sim:** new or extended co-sim driving `VdpTop` with bitmap/indexed content at 1×/2×/3×. Assertions:
  - 1× byte-identical to baseline.
  - >1× source rows/columns repeat correctly (no skip/dup).
  - Auto-center/bezel math matches predictions.
- **PnR:** TNS=0 all clocks; BSRAM delta tracked.
- **Review:** CyanPeak code-to-spec review.

## Audit Focus

- CyanPeak to review the semantic decision and its correspondence to the RTL implementation.
- BronzeGate to review host ABI impact and `libvdp`/example-app clarity.

## Exit Condition

This task is done when the bitmap/indexed fetch-side scaling semantics are agreed upon, documented, implemented, proven in co-sim and PnR, and reviewed by CyanPeak and BronzeGate.

```

## File: PROJECT_PLAN/TASKS/external-review-scaler-rewrite.md

```md
# external-review-scaler-rewrite

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE  
**Opened:** 2026-07-27  
**Closed:** 2026-07-27  
**Branch:** `topazcliff/scaler-rewrite`  

## Checkpoints

- **P0 DONE** (`eb08b3d`): `ScaleCoordGen` combinational coordinate generator +
  `ScaleCoordGenSim` unit co-sim PASS 8/8 cases. Verified 1× identity, 2×/3×
  horizontal source-coord repeat, vertical repeat, auto-center borders, silent
  clamp, and `sourceValid`.
- **P1a DONE** (`49040ae`): `ScaleCoordGen` wired into `VdpTop`; sink
  `PixelRepeatScaler` forced to bypass (no pipeline rebalance); all coordinate
  consumers rewired. 1× regression byte-identical:
  - `Indexed2bppFineCoSim`: intra-byte MATCH 3/3 rows, 0 mismatched cols.
  - `Indexed2bppFrameCoSim`: ROW-CODED `bestDv=3` (479/480), LEFT-EDGE CLEAN,
    shear 0px.
  - `DirectColorFrameCoSim`: delay=0 byte-exact `dh=0` (0.9956).
- **P1b DONE** (`5514d1d`, correcting `f805ef2`): sink `PixelRepeatScaler` retired
  via a plain `RegNext`, pipeline kept at +2 cycles. Broad 1× regression PASS —
  both `bgOrDirect` co-sims (`Indexed2bppFine`, `Indexed2bppFrame`, `DirectColor`)
  and `io.red` co-sims (`VdpInnerBorderCoSim`, `BitmapDirectColorSim`) are
  byte-identical. `VdpTopSim` `(0,50)` yellow→black failure confirmed pre-existing
  (identical at baseline `eb08b3d`, broken since `e1848b2`), not a scaler regression.
- **P3a DONE** (`15d5b8e`): >1× integration proof `ScaleUpFrameCoSim` — PASS. Drives
  full `VdpTop` at 1×/2×/3× with procedural patterns fed by `logicalX`/`logicalY`.
  Vertical stripes (pattern 7) prove per-pixel HORIZONTAL repetition (run-length ==
  scaleX, viol 0 — skip-sensitive); checkerboard proves both-axes tile scaling
  (H/V spacing == 16·scale, viol 0). Phase-independent (run-lengths/spacings, not
  absolute column); proof signal `dut.bgOrDirectRgb` keyed by io.x/io.de. The
  1-column io.x/bgOrDirectRgb/io.red probe-phase offset is PRE-EXISTING (present at
  the 1× control) and positional-only — not a scaler bug. Proof note:
  `proof_packets/external-review-scaler-rewrite/simulation/P3a_ScaleUpFrameCoSim.md`.
- **P3b SPUN OUT** (#14440): bitmap/indexed >1× vertical scaling requires
  fetch-side changes (`pixelWithinByte`, `bitmapFetchLineReg`, grant cadence) and
  a host-visible semantics decision (built-in 320×2 doubling vs generic scaler).
  Moved to a new lane to be opened after this one closes; do NOT modify the
  bitmap fetch path in this lane.
- **P4 DONE** (`7f8dde6`): Gowin PnR (effort 2, GW2AR-LV18QN88C8/I7, Verilog `b246aed7`).
  **clk_pixel TNS=0, Fmax 30.705 MHz (+21.8% margin)**; all clocks TNS=0. BSRAM **42→40**
  (−2, sink line buffer freed). DSP 46→50% (+2 reciprocal mults). P4 caught a real timing
  FAIL sim cannot: the P0 combinational divide + fitScale was an 82-level path (clk_pixel
  14.67 MHz, TNS −435.8 ns). FIX in 3 iterations: reciprocal-multiply
  `floor(x/s)=(x*ceil(2^18/s))>>18` (`38ee153`, →23.88 MHz) then register sourceX/Y/valid
  (`7f8dde6`, →30.705 MHz TNS=0). +1 latency in SCALED modes only (1× byte-identical via
  the VdpTop mux; >1× proof is phase-independent). Re-validated on `7f8dde6`: ScaleCoordGenSim
  8/8, ScaleUpFrameCoSim >1× PASS, full 1× regression byte-identical. Proof:
  `proof_packets/external-review-scaler-rewrite/synthesis/P4_pnr_PASS.md`.
- **P5 DONE** (`9314aa0`): CyanPeak code-to-spec review PASS (#14447) + PM
  disposition accepted. Proof packet finalized (`PASS.txt`, `review.md`,
  `manifest.yaml`, `hashes.sha256`).

## Objective

Replace the current sink-side `PixelRepeatScaler` with a source-coordinate scaler
that generates logical `(sourceX, sourceY)` coordinates before rendering, as
specified by the external static review Priority 4/5 findings
(`kb/reviews/external_static_review_2026-07-25.md`).

## Background

The existing `PixelRepeatScaler` scales after the compositor while the compositor
advances at one source pixel per physical clock. For `scaleX > 1` this produces:

```text
Input:   P0 P1 P2 P3 P4 P5
Current: P0 P0 P2 P2 P4 P4   (wrong)
Correct: P0 P0 P1 P1 P2 P2
```

The same skip pattern occurs vertically. A sink-side latch cannot recover source
pixels the upstream compositor has already skipped.

The required architecture produces physical→logical coordinates first, then lets
the renderer consume `logicalX`/`logicalY`:

```text
Physical hCounter/vCounter
        |
        v
Scale and centering coordinate generator
        |
        +--> sourceX / sourceY / sourceValid
        +--> borderX0 / borderX1 / borderY0 / borderY1
        |
        v
Tile / bitmap / planar / sprite rendering
        |
        v
Final RGB
```

## Scope

1. **Coordinate generator** — replace or augment `PixelRepeatScaler` with a new
   source-coordinate scaler that outputs:
   - `sourceX`, `sourceY` (logical coordinates)
   - `sourceValid`
   - `borderX0`, `borderX1`, `borderY0`, `borderY1`
   - `scaleXEffOut`, `scaleYEffOut` (optional, for downstream use)
2. **Renderer integration** — wire the coordinate generator so that layer fetchers
   (`layer0`, `layer1`, `testPattern`, etc.) consume `logicalX`/`logicalY` during
   active video, while physical counters still drive sync/DE.
3. **1× behavior preservation** — when `scaleX == scaleY == 1`, the output must be
   byte-identical to the current 1× path (no visible change).
4. **>1× validation** — build deterministic co-sim tests that prove correct 2×/3×
   repetition and centering for bitmap, indexed, and test-pattern sources.

## Out of scope

- New host ABI / register map (reuse existing `scaleCtrl` fields).
- Hardware flash / bench test (this lane is sim+PnR only; a separate HW gate can
  be opened if needed).
- HAM6, sprites, Copper timing changes unrelated to scaling.

## Acceptance criteria

- [x] New coordinate-generator module compiles and elaborates.
- [x] `sbt compile` and `TopTang20kHdmiVerilog` PASS on the target branch.
- [x] 1× regression: existing co-sims (`Indexed2bppFineCoSim`, `Indexed2bppFrameCoSim`,
  `DirectColorFrameCoSim` at 1×) produce byte-identical or visually equivalent
  output compared to `topazcliff/migration-phase11` HEAD.
- [x] >1× proof: a deterministic co-sim demonstrates correct 2×/3× repetition and
  centering (golden-vector comparison).
- [x] Gowin PnR clean: **TNS=0**, no new BSRAM inferred unless architecturally
  required and reviewed.
- [x] Independent CyanPeak code-to-spec review PASS (mailbox-visible).
- [x] Proof packet complete under `PROJECT_PLAN/proof_packets/external-review-scaler-rewrite/`.

## Proof packet contents

- `PASS.txt` — summary, commit hashes, verdict.
- `review.md` — reviewer sign-off table.
- `hashes.sha256` — artifact hashes.
- Co-sim logs / frame captures for 1× regression and >1× validation.
- Gowin PnR timing/resource summary.

## Decision rule

If the coordinate-generator approach requires a host-visible ABI change or a
non-trivial integration change, stop and call an interface checkpoint with
BronzeGate before continuing.

```

## File: PROJECT_PLAN/TASKS/external-review-tile-pipeline.md

```md
# external-review-tile-pipeline

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE — deferred  
**Opened:** 2026-07-25  
**Closed:** 2026-07-28  

---

## Purpose

Evaluate pipelining `BasicPatternSource` tile-map / tile-row reads (external static review Priority 7).

## PM disposition

Deferred. The tile-pipeline optimization is off the current production display path:

- Production builds use `layer0UseSdram = True` with bitmap/planar assets fetched from SDRAM.
- The on-chip test-pattern / tile-map path is disabled in production (`layer0TestPatternEnable = False`).
- No current firmware or product feature depends on `BasicPatternSource` being enabled at scale.

The two dependent asynchronous `readAsync` memory reads in `BasicPatternSource.scala:39-48` remain a latent timing/BSRAM risk if standalone diagnostic mode or on-chip tile layers are ever activated. This task file records the deferral so the risk is not lost.

## Reactivation criteria

Reopen this lane if any of the following become true:

1. `BasicPatternSource` is enabled in a production bitstream.
2. A standalone diagnostic build using on-chip tile patterns is adopted as a release target.
3. Timing closure or BSRAM inference issues are observed in the `BasicPatternSource` path.
4. The external reviewer or a regression gate requires Priority 7 to be implemented.

## References

- External static review Priority 7: `kb/reviews/external_static_review_2026-07-25.md`
- BrightForge technical assessment: #14317
- Source: `hw/spinal/spinalhdlvdp/BasicPatternSource.scala:39-48`

```

## File: PROJECT_PLAN/TASKS/PROJECT-SYSTEM-MIGRATION-001.md

```md
# PROJECT-SYSTEM-MIGRATION-001 — Controlled Modular Documentation Migration

**Owner:** `TopazCliff`  
**Repository:** `/home/itadmin/github/spinalhdlVDP`  
**Opened:** 2026-07-26  
**Pre-migration commit:** `958a01d`

## Mission

Convert spinalhdlVDP to a modular engineering, documentation, verification, and reproducibility system **without** losing current state, history, role boundaries, source authority, build knowledge, or proof evidence.

## Controls preserved

- Authoritative project mailbox.
- `STATUS.md` remains the sole durable live-state authority.
- Current agent identities and role boundaries.
- One critical-path engineering lane.
- SpinalHDL as editable FPGA source; generated Verilog as build artifact.
- `libvdp` as reusable host SDK.
- Simulator-first validation and matched firmware/bitstream hardware proof.
- Prior-art search and closeout memory.

## What the migration adds

- Shared architecture / FPGA / firmware specifications.
- One canonical directory per platform adapter (`kb/<Adapter>/`).
- Validated runbooks.
- Test specifications and golden vectors.
- Proof packets under `PROJECT_PLAN/proof_packets/<LANE>/`.
- ADRs under `PROJECT_PLAN/DECISIONS/`.
- Reproducibility manifests.

## Authority order

1. Latest authoritative mailbox instruction.
2. Repository-root `STATUS.md`.
3. This task file and any linked active task.
4. Current repository state and commit.

## Migration state model

Track through:

1. `PROPOSED`
2. `AUTHORIZED`
3. `SNAPSHOT`
4. `INVENTORY`
5. `AUTHORITY_RECONCILIATION`
6. `RULE_UPDATE`
7. `STRUCTURE_CREATED`
8. `ACTIVE_LANE_MAPPED`
9. `PILOT_EXECUTION`
10. `AUDIT`
11. `CUTOVER_READY`
12. `CUTOVER`
13. `OBSERVATION`
14. `CLOSED`

## Current state

`CLOSED` — Phases 0–14 complete. Observation satisfied; migration to the modular documentation/specification/proof system is complete.

## Next action

- Migration lane is closed. Return to normal engineering lanes per `PROJECT_PLAN/STATUS.md`.
- Open lanes/backlog: `external-review-scaler-rewrite`, `external-review-tile-pipeline` (both OPEN, low priority, productize-only).

## Closeout summary

- Observation confirmed that lanes close with complete proof packets and required ADRs/reviews.
- Pilot `2bpp-bank-completion-rtl` closed with proof packet `32c18e2`, CyanPeak arch/interface review (#14375), and CoralReef proof-packet/runbook review (#14376).
- Observation lane `external-review-tierB-measure` closed with proof packet `5128ff4` and CyanPeak concurrence (#14427).
- Closeout proof packet: `PROJECT_PLAN/proof_packets/PROJECT-SYSTEM-MIGRATION-001/`.
- Closeout memory: stored via MCP memory.

## Completed phases summary

- Phase 0: Lane opened (`PROJECT-SYSTEM-MIGRATION-001.md`).
- Phase 1: Pre-migration snapshot recorded under `PROJECT_PLAN/proof_packets/PROJECT-SYSTEM-MIGRATION-001/pre_migration/`.
- Phase 2: Inventory created (`PROJECT_PLAN/PROJECT_SYSTEM_MIGRATION_INVENTORY.md`).
- Phase 3: Authority reconciled: `STATUS.md` remains live; external docs kept as reference snapshot.
- Phase 4: Agent rules updated (`AGENTS.md` + `AGENTS/*.md`).
- Phase 5: Modular doc structure created under `docs/`.
- Phase 6: Canonical adapter template created (`kb/TEMPLATE_ADAPTER/`).
- Phase 7: Shared specs created (`docs/fpga/BITMAP_ENGINE.md`, `docs/firmware/HOST_TRANSPORT_ABI.md`).
- Phase 8: Runbook skeletons created under `docs/runbooks/`.
- Phase 9: Test-plan template/sample and proof-packet structure created.

## Exit criteria

- Pre-migration state is reproducibly identified.
- Mail, `STATUS.md`, task file, and repo agree.
- All agent files are updated and reviewed.
- Role ownership remains separate.
- Modular directories and ownership guides exist.
- Pilot lane uses one canonical adapter directory.
- Governing FPGA and firmware specs exist for active work.
- Runbooks work.
- Expected results documented; proof packet complete.
- Required advisory reviews pass.
- Pilot closes normally.
- Explicit cutover decision issued and recorded.
- Observation task closes.
- Memory closeout written.
- `STATUS.md` records exact next work.

```

## File: PROJECT_PLAN/TASKS/qspi-cpu-fpga-reliability-plan.md

```md
# Master reliability plan: CPU↔FPGA QSPI connection

**Owner:** TopazCliff (PM) — BrightForge (RTL/sim/diagnostics) + BronzeGate (firmware/self-healing/HW proof) + CyanPeak (spec review) + CoralReef (docs/runbooks)  
**Opened:** 2026-08-10  
**Status:** ACTIVE — owner directive to pursue a solid, scalable, over-tested, self-healing/adjusting connection  
**Scope:** Tang Nano 20K + ESP32-P4 QSPI host interface. i80 and legacy SPI are retired and must not be re-enabled without a new Rule-19-gated lane.

## Owner directive

> "I want every outcome and possibility thought out, both good and bad... we have been working on this for weeks off and on.. we need a solid solution, one that scales and is very reliable... and it also needs to be tested beyond what is needed to make sure it doesnt break/have issues."

This plan is the engineering response. It is **not a license to gold-plate**. It means every known failure mode is either (a) prevented by design, (b) detected and reported through health/status, or (c) accepted as a documented risk with a recovery path. "Over-tested" means the test matrix must explicitly cover corner cases, error injection, and long-run stress, not just the happy path.

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

## Self-healing / adjusting behavior

The host-side policy (BronzeGate owns) should be:

1. **Before every upload:** clear prior sticky errors via `0x0323` W1C.
2. **During upload:** if `ERROR`/`OVERFLOW` set, abort and retry up to N times.
3. **After upload:** poll `DONE` before starting next upload.
4. **If `DONE` not seen within timeout:** clear status, reset transport context (CS# high idle, re-init if needed), retry.
5. **If repeated failures:** fall back to lower SCLK frequency or escalate to user.
6. **Health logging:** after every session, log `raw`, `overflow`, `malformed`, and any CRC/timeout counts.

The FPGA-side policy (BrightForge owns) should be:

1. **Bounded FSMs:** every state has a timeout or exit condition.
2. **Sticky errors:** once set, remain until host clears them.
3. **No silent acceptance:** malformed commands are dropped and reported.
4. **Reset discipline:** config-boundary reset is clean and deterministic.

## Acceptance criteria for "reliable connection"

- [ ] FMEA table above is reviewed and signed by BrightForge + BronzeGate.
- [ ] Every failure mode has a design response or an accepted risk note with recovery path.
- [ ] `qspi-status-done-bit-fix` closes with sticky `DONE`, full regression, PnR, and ≥50-cycle HW sanity.
- [ ] `qspi-transport-reliability-hardening` closes with a confirmed mechanism and a fix proven by HW reproof.
- [ ] Host self-healing retry policy is implemented and tested via error injection.
- [ ] Long-run stress (≥30 min) passes with zero unrecovered errors.
- [ ] ADR-009 + `MODE0_REGISTER_BUS_SPEC.md` + `firmware/GOTCHAS.md` + runbook are updated.
- [ ] Proof packet for the combined reliability campaign is stored under `PROJECT_PLAN/proof_packets/qspi-cpu-fpga-reliability/`.

## Next actions

1. **BrightForge:** Review this plan, extend the FMEA with RTL-specific failure modes, and propose which design mechanisms (CRC, glitch filter, etc.) to adopt. Post branch `brightforge/qspi-status-done-bit-fix` first; keep transport-lane diagnostic in parallel if bandwidth allows.
2. **BronzeGate:** Review this plan, extend the FMEA with host/firmware failure modes, and propose the host-side self-healing policy. Stand by for build/flash gates.
3. **CyanPeak:** Review the FMEA and design mechanisms for spec/contract consistency; ensure no new interface change slips in without Rule 19.
4. **CoralReef:** Capture this plan and the final FMEA in the docs/runbook; update `GOTCHAS.md` as needed.
5. **TopazCliff:** Hold Rule 19 sign-off until the FMEA is closed and both lanes have hardware proof.

## Notes

- This plan is owner-directed (Rule 9). It does **not** authorize unilateral interface changes. Any new host-visible bit, register, or protocol change still requires independent BrightForge + BronzeGate Rule 19 sign-off.
- The retired i80 and legacy SPI paths remain guarded by `#error`; re-enabling them is out of scope.
- "Over-tested" is a test-coverage requirement, not an excuse to delay indefinitely. Each test must have a pass/fail criterion and an owner.

```

## File: PROJECT_PLAN/TASKS/qspi-status-done-bit-fix.md

```md
# Defect lane: qspi-status-done-bit-fix

**Lane ID:** `qspi-status-done-bit-fix`  
**Owner:** BrightForge (RTL/sim) + BronzeGate (firmware build + HW proof)  
**PM:** TopazCliff  
**Opened:** 2026-08-10  
**Status:** RUNNING — Option A confirmed by BrightForge (#14676) and BronzeGate (#14677); implementation/proof authorized  

## Problem

The `codebase-cleanup-status-contract` lane merged to `main` defines upload-status bit 1 (`DONE`) as a sticky bit that remains set after an upload completes until it is cleared (CoralReef #14669, CyanPeak #14670).

The current implementation in `QspiSdramBridge.scala` drives `io.uploadDone` from a single-cycle `donePulse`:

```scala
val donePulse = Reg(Bool()) init False
donePulse := False
...
sDone.whenIsActive {
  donePulse := True
  goto(sIdle)
}
...
io.uploadDone := donePulse
```

`TopTang20kHdmi.scala` wires `qspiCore.io.upload_done := qspiSdramBridge.io.uploadDone`, and `QspiTransportCore.scala` samples it with `BufferCC(io.upload_done, False)` into `upDoneCC` for `READ_STATUS sel=0x06` and for the i80 `0x0323` read mux.

Because the pulse occurs in the **pixel/sys clock domain** and the host samples it through an SCLK-domain `BufferCC` on a bus whose clock stops while CS# is idle, the pulse is almost always missed. An i80 host also cannot reliably poll a 1-cycle 25 MHz pulse. The practical result is that `DONE` reads as `0` even after a completed upload.

## Scope

Fix the `DONE` output so it is a true sticky level:
- Set when the bridge FSM reaches `sDone` (same event that currently creates `donePulse`).
- Clear automatically at the start of the next upload (`headerValid` accepted / `sIdle`→`sActive` transition), so a new upload begins with `DONE=0`.
- Optionally clear on the existing `0x0323` W1C path if the contract is extended; **not required** for this fix because the contract currently only specifies W1C for bits 2/3.

Do **not** change:
- The `BUSY`, `ERROR`, or `OVERFLOW` semantics.
- The `0x0323` W1C clear mask (still bits 2/3).
- The QSPI protocol, pinout, or selector map.

## Proposed RTL change

In `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`:

1. Add a sticky register `uploadDoneSticky`.
2. Set it in `sDone.whenIsActive` (or via the existing `donePulse` as set condition).
3. Clear it in `sIdle.whenIsActive` when a new header is popped (`hdrFifo.io.pop.valid`) — i.e., on the transition to `sActive`.
4. Drive `io.uploadDone := uploadDoneSticky`.
5. Update the ScalaDoc comment that currently says "uploadDone pulses one cycle" to describe the sticky level behavior.

If any internal sim or test relies on the pulse shape (not just the level), evaluate whether to add a separate `uploadDonePulse` debug output or update the test. The existing tests that wait for `uploadDone.toBoolean` true will still pass because the sticky bit stays high.

## Contract decision (Option A)

After BronzeGate's needs-changes review (#14674), both BrightForge (#14676) and BronzeGate (#14677) confirm:

- **`DONE` is sticky across CS# idle until the next accepted upload starts.**
- **Bit 1 has no W1C.** The existing `0x0323` W1C mask continues to cover only bits 2 (`ERROR`) and 3 (`OVERFLOW`). Adding W1C for bit 1 would be a separate interface change requiring re-approval.
- **Back-to-back uploads can clear `DONE` before it is polled.** Callers must poll completion before starting the next upload. This lifecycle will be explicitly stated in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/libvdp/mode0_regs.json`, `firmware/GOTCHAS.md`, and the 0x0323 checkpoint wording.

## Acceptance criteria

- [ ] Rule 19 sign-off from BrightForge and BronzeGate.
- [ ] `sbt compile` PASS.
- [ ] Existing affected simulations PASS:
  - `Qspi0x0323StatusClearSim` (must still pass; extend or add a DONE-bit readback case).
  - `QspiTransportBridgeSim`, `QspiUploadIntegritySim`, `SdramUploadSim`, `QspiDecoderSdramBoundSim` (level-only checks should still pass).
- [ ] New or extended sim proves `DONE` is sticky: after a completed upload and after CS# has been idle, a subsequent `READ_STATUS sel=0x06` returns `DONE=1`.
- [ ] Gowin PnR PASS (TNS=0; no new resources expected).
- [ ] Firmware build PASS (ESP-IDF v6.0.2 active target).
- [ ] `STATUS.md` and this task file updated to DONE with artifacts.
- [ ] `firmware/GOTCHAS.md` or `MODE0_REGISTER_BUS_SPEC.md` updated if the wording about `DONE` is ambiguous.

## Risks

- Changing `uploadDone` from pulse to level may affect logic outside the status path if another module uses it as an edge. Search before editing.
- If `DONE` is not cleared early enough, a host reading status after a previous upload could see an stale `DONE=1`. Clearing on new-upload start is sufficient because the host initiates every new upload.

## BronzeGate Rule 19 needs-changes (mail #14674)

Before re-requesting Rule 19 sign-off, close these gates:

1. **Contract/doc reconciliation.** ADR-009 says `DONE` is "Sticky until cleared," while the proposed implementation clears it automatically when the next upload is accepted and the existing `0x0323` W1C mask covers only `ERROR`/`OVERFLOW` (bits 2/3). Explicitly state in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/libvdp/mode0_regs.json`, `firmware/GOTCHAS.md`, and this task file whether the contract is:
   - "sticky across CS# idle until the next accepted upload starts" (no W1C on bit 1), or
   - "sticky until W1C-cleared" (which requires authorizing W1C on bit 1 as a separate interface decision).
   The default fix path (clear on new-upload start) matches option A.

2. **Extended simulation/proof.** Show that `DONE` goes high after upload completion, remains high across CS# idle, is observable through QSPI `sel=0x06` and i80 `0x0323` reads, and clears at the next upload start. Prove `BUSY`/`ERROR`/`OVERFLOW` behavior is unchanged. Audit `QspiTransportBridgeSim`, `QspiUploadIntegritySim`, `QspiSdramBridgeSim`, and any other consumer that may still expect an edge/pulse on `io.uploadDone`.

3. **Firmware build gate.** No firmware source change is expected, but the active ESP-IDF v6.0.2 target (e.g., `firmware/esp32p4_scaler_proof`) must build cleanly with the exact artifact/toolchain result recorded.

## Artifacts

- Source branch: `brightforge/qspi-status-done-bit-fix`
- Base: `main`
- Task file: `PROJECT_PLAN/TASKS/qspi-status-done-bit-fix.md`

```

## File: PROJECT_PLAN/TASKS/qspi-transport-reliability-hardening.md

```md
# Lane: qspi-transport-reliability-hardening

**Owner:** BrightForge (RTL/sim) + BronzeGate (firmware build + HW reproof)  
**PM:** TopazCliff  
**Status:** BLOCKED — mechanism unconfirmed; awaiting corrected diagnostic  
**Opened:** 2026-08-08  
**Updated:** 2026-08-09  

## Goal

Eliminate the config-boundary first-transaction failure that forces the Lane 1 discard-read prime workaround. The specific RTL fix is **not yet selected**; we must first confirm the mechanism.

## Background

The Lane 1 diagnostic bitstream (`eaad44f8…`) returned `raw=0x00004045`. The `sawCsHigh=1` bit decisively refutes the CS#-stuck-low hypothesis, and the failure is config-boundary-only and self-heals — so it is a first-transaction timing/robustness issue.

However, the `firstPhase=CMD / firstBitc=1` interpretation that pointed to a reset-release race in `QspiSlaveSync` is now suspected to be a **diagnostic capture artifact**. The capture latch runs in the SCLK-clocked domain; because SCLK stops during the CS#-idle gap, the latch closed ~2 SCLK edges into the *recovered* transaction, not the first failing one. Therefore `firstPhase/firstBitc` read ~CMD/bit1 regardless of how the first transaction actually framed.

This means the reset-release race remains a **leading but unconfirmed hypothesis**. Other not-ruled-out mechanisms include CS# signal-integrity/bounce at the config boundary and a read-data output/OSER launch glitch right after configuration.

Current workaround: BronzeGate performs one ignored `read_status(0x00)` (the "discard-read prime") before trusting the second transaction.

## Proposed change (pending mechanism confirmation)

**Do not start RTL implementation yet.** First, build a corrected diagnostic that captures the first transaction state reliably (e.g., latch `firstPhase`/`firstBitc` in the free-running sys/pixel clock domain, or use a free-running "first-txn-done" qualifier), re-run the `eaad44f8`-class experiment, and read the real first-transaction state.

Once the mechanism is confirmed, select the targeted fix:
- **Reset-release synchronizer** if the first transaction is genuinely mis-framed.
- **CS# SI / input conditioning** if CS# is glitching/bouncing at the boundary.
- **Read-data launch-path hardening** if framing is correct but read data is corrupted.

The existing protocol and pinout will remain unchanged.

## Implementation plan

1. **Corrected diagnostic** — BrightForge  
   - Fix the Lane 1 diagnostic capture so `firstPhase`/`firstBitc` are latched in the free-running sys/pixel clock domain (or otherwise independent of SCLK stoppage during CS# idle).
   - Re-build the diagnostic bitstream from the same `a5a047a2` base.

2. **Diagnostic hardware run** — BronzeGate  
   - Flash the corrected diagnostic bitstream on a fresh reconfigure that reproduces `magic=0x22222222`.
   - Capture `sel=0` (expected `0x2222`) + `sel=0x0D` and report.
   - BrightForge interprets the corrected reading and rules in/out each mechanism.

3. **Select fix + Rule 19 sign-off** — TopazCliff / BrightForge / BronzeGate  
   - Update this task file with the confirmed mechanism and chosen fix.
   - Re-request Rule 19 sign-off with the confirmed target.

4. **RTL fix + sim + PnR + HW reproof** — BrightForge / BronzeGate  
   - Implement the confirmed fix.
   - Run full affected QSPI regression and the new mechanism-specific sim.
   - Run Gowin PnR; confirm TNS=0.
   - Build firmware and run ≥10 cold-POR cycles without the discard-read prime.

5. **Closeout** — TopazCliff  
   - Update `STATUS.md` to DONE, archive proof packet, send closeout mail.

## Acceptance criteria

- [ ] Corrected diagnostic captures the real first-transaction state.
- [ ] Mechanism confirmed and fix selected.
- [ ] Rule 19 sign-off from BrightForge and BronzeGate for the confirmed fix.
- [ ] `sbt compile` PASS.
- [ ] Mechanism-specific sim PASS.
- [ ] Full affected QSPI regression PASS.
- [ ] Gowin PnR PASS (TNS=0).
- [ ] Firmware build PASS.
- [ ] Hardware reproof ≥10/10 PASS without discard-read prime.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/qspi-transport-reliability-hardening/`.
- [ ] `STATUS.md` updated to DONE with artifacts.

## Risks / open questions

- The `firstPhase/firstBitc` fields in the original diagnostic are unreliable. Any conclusion drawn from them is provisional.
- CS# signal-integrity at the FPGA pin is difficult to observe without a logic analyzer or scope; a corrected diagnostic may not distinguish CS# bounce from a reset-domain issue cleanly.
- If the output/OSER path is the true cause, the fix may be in the launch/IOBUF domain rather than in `QspiSlaveSync`.

## Artifacts

- Source branch: `brightforge/qspi-transport-reliability-hardening` (not yet created)
- Diagnostic base: `brightforge/lane1-reconfig-diag` (forked from `a5a047a2` source `033cc471`)
- Task file: `PROJECT_PLAN/TASKS/qspi-transport-reliability-hardening.md`
- Rule 19 request: on hold until mechanism is confirmed

```

## File: PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md

```md
# qspi-upload-si-hardening

**Owner:** BrightForge (RTL) + BronzeGate (firmware)  
**PM:** TopazCliff  
**Status:** DONE — mode-8 READ_DONE hardware proof PASS (`0x55555555` at `0x100008` and `0x101000`); SDRAM writes are clean; residual `sel=8` zeros are a readback/CDC artifact. PM disposition: document the `sel=8` diagnostic caveat; no production RTL or host-interface change. Closeout commit `542e4ad5`.
**Opened:** 2026-07-30  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Address the residual intermittent silent QSPI upload corruption observed in `HAM6 removal + 2bpp indexed replacement` / `QSPI-SI-CEILING-183` at the canonical 4 MHz bulk-upload ceiling.

---

## Background

BrightForge's SI sign-off (#14266) concluded that the intermittent, speed-dependent, silent lower-bitmap corruption (8 MHz 4/10 pass, 4 MHz 3/3 pass, 2 MHz 3/3 pass; no `overflow`/`malformed` flags at `sel=0x0A`) is a physical signal-integrity margin issue, not RTL/CDC. The recommended follow-up was one of:

1. **Native ESP32-P4 SPI2 IOMUX + series termination** (physical/firmware side).
2. **Per-SDRAM_WRITE CRC in transport health** (RTL/firmware detection side) so the host can retry silent corruption.

This lane picks the more actionable of the two and proves it reduces/eliminates uncorrected upload corruption at 4 MHz.

---

## Scope

- Choose an SI-hardening approach **before touching RTL or firmware**:
  - **Option A (recommended, software-detectable):** Add a per-SDRAM_WRITE payload CRC8 in `QspiSdramBridge`, accumulate it per write transaction, and expose a `READ_STATUS` selector so firmware can verify each uploaded chunk. Host retry logic on mismatch turns silent corruption into retried writes.
  - **Option B (physical):** Confirm native SPI2 IOMUX pins are usable on the current Tang Nano 20K + P4 wiring, switch the firmware QSPI driver to native IOMUX, and re-run the 4 MHz stress test.
  - **Option C (bench only):** Shorten/ground leads, add series termination, adjust drive strength, quantify improvement.
- No production fetch/display RTL changes.
- No change to the 4 MHz canonical bulk-upload ceiling unless new data justifies it.
- Host-visible addition (new health selector / firmware retry) requires Rule 19 interface checkpoint: independent BrightForge + BronzeGate approval before implementation.

## Approach Reframe (2026-07-30)

BrightForge confirmed that the RTL described as "Option A" already exists on `main` from the `QSPI-CRC8-185` lane (`QspiSlaveSync.scala` / `QspiTransportCore.scala`, commit `368839f`, HW-proven bitstream `780ee698`, mail #14274/#14276/#14278). The per-`SDRAM_WRITE` CRC8 covers `[CMD, ADDR, LEN, payload]` and is exposed via `READ_STATUS sel=11`.

Therefore this lane does **not** build new RTL. The actionable work is:

1. BronzeGate confirms whether the failing 4 MHz bulk 2bpp-upload path actually appends the CRC byte (using `firmware/libvdp/vdp_crc8.h`) and polls `sel=11` with retry-on-mismatch.
2. If yes, run the 4 MHz byte-readback stress at **N≥30 uploads with CRC retry enabled** and measure residual uncorrected corruption.
3. If no, adopt the existing CRC+retry on the bulk path, then run the same stress.
4. Only if residual corruption remains do we scope a minimal delta (likely firmware plumbing, not RTL).

## BronzeGate firmware-path result (2026-07-30)

The current ESP32-P4 backend is already CRC-enabled: `vdp_host_p4.c`
`write_frame()` computes `vdp_crc8_qspi_write_frame()` over the wire-order
`[CMD, ADDR, LEN, payload]`, appends the CRC byte, polls `READ_STATUS` selector
`0x0B` before and after each frame, and retries once when the 16-bit CRC status
counter changes. Both `vdp_reg_write_burst()` and `vdp_sdram_write()` use this
helper; the scaler proof app's 4 MHz bulk bitmap/attribute uploads therefore
exercise the existing CRC8-185 path without a firmware edit.

Clean-baseline hardware stress (`project_38002d5c_scaler_hwproof.fs`, ESP-IDF
6.0.2, app source commit `4f205a08`) ran 30 reset/upload/readback cycles. The
result was 15/30 pass and 15/30 fail. Every failed cycle had two byte-readback
mismatches at the checkerboard samples `0x100008` and `0x101000` (expected
`0x55555555`, observed `0x00000000`); all other sampled words passed. All three
health samples per cycle remained `raw=0x00000000 overflow=0 malformed=0`, and
the application returned after upload without a CRC retry failure. This is
residual uncorrected corruption after CRC+retry, so the lane remains blocked
pending BrightForge/TopazCliff scope of the minimal next delta.

Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.

Rule 19 remains open pending BrightForge/TopazCliff agreement on the next step.

## BronzeGate firmware framing/readback audit (2026-07-31)

Per TopazCliff #14539, BronzeGate traced the source buffer and upload path for
the deterministic failures at `0x100008` and `0x101000`. The checkerboard
contains `0x55555555` at both target words. Frame 0 begins at `0x100000`, with
the first target at byte offset 8; frame 8 begins at `0x100FD0`, with the
second target at byte offset 48. The 253-word frame map, little-endian length
and word encoding, parity-encoded wire addresses, and appended CRC8-185 values
were recomputed from the firmware. The target frames produce wire addresses
`0x100000`/`0x900FD0` and CRC values `0xDF`/`0x67`; the CRC is appended after
the payload and cannot shift it.

The P4 backend's `vdp_reg_read()` is an explicit RX stub. The diagnostic's
selector `0x08` is the only current P4 SDRAM-content surface; selector `0x09`
is transport loopback/status, not alternate SDRAM data. No new command,
register, bitstream, or firmware behavior was invented. The full audit and
Rule 10 citation block are in
`PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/FRAMING_READBACK_AUDIT.md`.

Disposition: host framing/address/CRC is not the demonstrated mechanism, and
the lane remains blocked on an approved alternate readback surface or a
physical-layer test. Any host-visible readback change requires the independent
BrightForge + BronzeGate Rule 19 checkpoint and TopazCliff authorization.

Related mail: #14539, #14540, #14542, #14543.

## BronzeGate focused discriminator result (2026-07-30)

BronzeGate ran proof-only `SCALER_PROOF_MODE=4` using the existing `libvdp`
upload path. Bitmap and attribute planes each used 61 frames of 253 words
(506 bytes) at 4 MHz. Selector `0x0B` was logged before and after every
frame; six counter deltas occurred and all host calls returned success.

The assigned neighborhoods were read at 2 MHz eight times each: 13 addresses,
104 successful reads total. The expected-`0x55555555` words at
`0x100008`, `0x10000C`, `0x100018`, `0x10001C`, `0x101000`, and `0x101004`
all returned stable `0x00000000`; expected-zero neighbors also remained zero.
Health was `raw=0x00000000 overflow=0 malformed=0`. Per the PM discriminator,
this selects the real SDRAM/write-path branch rather than a varying readback
artifact. Detailed evidence is in
`PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DIAGNOSTIC_RESULTS.md`.

No production firmware or RTL fix was made. Rule 19 remains open pending the
three-way PM/BrightForge/BronzeGate scope decision.

## Next step

Before any RTL or firmware edit, discriminate where the residual zeros originate:

1. **BronzeGate** — discriminator complete. The required 2 MHz reads are stable zero and the exact 4 MHz frame/CRC map is recorded in the proof packet.
2. **BrightForge + TopazCliff** — review the stable-zero result with the bridge analysis and choose the minimal authorized delta (firmware readback verification, RTL write-path fix, or physical hardening).
3. **BronzeGate** — implement only the approved host-side change after the independent interface checkpoint; otherwise remain blocked.

No code changes until the discrimination analysis is complete.

## BronzeGate refresh-pressure cross-check (2026-07-31)

Per TopazCliff #14531, BronzeGate ran the existing proof firmware against the
same approved `38002d5c` bitstream under two display-workload conditions. Mode 4
kept layer 0 disabled after upload; mode 0 enabled layer 0 and display fetch.
Both conditions used the existing CRC8/retry path, 4 MHz uploads, and 2 MHz
readback. Each condition ran N=30 reset/upload/readback cycles.

Results: mode 4 was 0/30 pass and mode 0 was 0/30 pass. The expected
`0x55555555` words at `0x100008` and `0x101000` failed on every cycle in both
conditions (60 target mismatches per condition). Health remained
`raw=0x00000000 overflow=0 malformed=0`. Therefore this test observed no
display-workload scaling; it is a correlation result, not a mechanism claim.

Proof artifacts:

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_PROCEDURE.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/REFRESH_PRESSURE_BUILD.md`

Rule 10 prior-art search and citations are included in the results artifact.
The lane remains blocked on BrightForge's waveform-pin/proven bulk-upload
harness and the PM's Rule 19 decision. No production firmware or RTL change was
made.

---

## Acceptance Criteria

- [x] Approach chosen and recorded in this task file with PM approval (option-4 `READ_DONE` completion-poll discriminator, Rule-19 approved by BrightForge and BronzeGate in #14565/#14566).
- [x] Option-4 RTL implemented (`5ef5db2a`), `sbt compile` PASS, CDC co-sim `ReadDoneCdcSim` ALL PASS (ideal-2FF caveat), 3-build STA TNS=0 all clocks, BSRAM 40/46 (no new).
- [x] Matching proof firmware built (`SCALER_PROOF_MODE=8`, source `158b9d7c`), hardware proof run, and result recorded.
- [x] Production `make gen` still emits `top_tang20k.v` with no unintended diff (no production path touched).
- [x] `git status` clean; all changes committed (`542e4ad5`).
- [x] Proof packet created under `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.
- [x] `STATUS.md` lane updated to `DONE` with proof.

---

## External reviewer findings (2026-07-31)

An external reviewer examined the bundled source (`PROJECT_PLAN/external_review/`).

### Primary hypothesis: 1-read pipeline lag in `sel=8` debug readback

The `sel=8` diagnostic readback path has a **one-word pipeline lag**. When the
Pico issues `rx_status(sel=8)` for address N, the QSPI slave returns the value
that was already latched in `dataReg` from the *previous* read request (N-1),
because the SDRAM controller needs ~5 SDRAM clock cycles to produce new data and
the SPI transaction cannot wait.

This explains why `0x100008` and `0x101000` return `0x00`:
- `0x100008` is read immediately after `0x100004`; the first 8 bytes of the row
  are `0x00`, so the lagged result for `0x100008` is the `0x00` from `0x100004`.
- `0x101000` is the start of row 32; the previous diagnostic read is `0x100FFC`,
  which falls in the `0x00` padding at the end of row 31 (active width is only
  320 px = 80 bytes per row, stride is 128 bytes). The lagged result is that
  padding.
- Other sampled addresses do not cross a color/padding boundary, so the lagged
  value happens to match the expected value and the bug is hidden.

### Secondary finding: `memcpy` overlap bug in `write_frame()`

`vdp_host_p4.c:write_frame()` calls:

```c
memcpy(s_tx_buf, frame, frame_len);
```

In several callers (`vdp_sdram_write()`, `vdp_reg_write_burst()`), `frame` is
` s_tx_buf` itself. Overlapping `memcpy` source and destination is undefined
behavior in C and should be fixed immediately (use `memmove` or a distinct
scratch buffer). The reviewer notes this is likely a red herring for the
observed zeros but is a real bug.

### Proposed confirmation

Issue `READ_STATUS sel=8` **twice** for the same target address and return the
second value. If the second read returns `0x55555555` at `0x100008`, the SDRAM
writes are proven correct and the `sel=8` lag is the sole culprit.

## BronzeGate confirmation attempts (2026-08-01)

BronzeGate implemented two proof-only diagnostics in commits `3b246fc7` and
`619f76b8` (the latter fixes the overlapping `memcpy` → `memmove`):

### Double-read diagnostic (Mode 6)
- Protocol: for each target address, issue `READ_STATUS sel=8` twice and report
  both values.
- Result: **both first and second reads returned `0x00` every time** across
  8 repeats × 6 addresses, with clean health (`raw=0`, `overflow=0`,
  `malformed=0`).
- Interpretation: the proposed 1-read lag **was not confirmed by this test**.
  Either the lag does not manifest in this configuration, the second read also
  re-triggered the same artifact, or the root cause lies elsewhere.

### Display-indirect readback (Mode 7)
- Protocol: paint the target words (`0x100008`, `0x101000`) with palette index
  `0xAA` and render the bitmap in normal Mode 0.
- Result: **no distinctive palette-2 block was visible** in three identical
  720×480 HDMI captures; images remained grayscale/cyan.
- Interpretation: ambiguous/negative. The display path adds confounders (color
  LUT, scaler, capture), so this test cannot alone prove SDRAM contents.

### External reviewer correction (2026-08-01)

The reviewer pointed out that the Mode 6 double-read was **flawed**: the SDRAM
read is not armed by `READ_STATUS sel=8`; it is armed by the write to
`REG_SDRAM_READ_ADDR_HI` (0x0327). Polling `sel=8` twice without rewriting the
address registers merely returns the same stale `dataReg` twice.

**Corrected discriminator:** call the full `readback_word(addr, &val)` routine
**twice** for the same target address. The first call arms a new read but
returns the previously-latched value; the second call arms another read and
returns the value fetched during the first call. The reviewer predicts the
**second call will return `0x55555555`** at `0x100008` and `0x101000`, which
would prove the SDRAM writes are pristine and the defect is entirely in the
`sel=8` CDC path.

### Status after correction

The write-side vs readback-side fork is **still open** but now has a decisive,
firmware-only discriminator. If the corrected double-read returns `0x55555555`
on the second call, the lane resolves to a `sel=8` readback illusion and no
production RTL or host-interface change is needed (only documentation of the
lag and/or an optional CDC fix). If the second call still returns `0x00`, the
write-side hunt must reopen.

### Corrected implementation and first-run TX failure (2026-08-01)

BronzeGate implemented the corrected double-read in commit `2d066b5e` using:

```c
static bool readback_word_twice(uint32_t addr, uint32_t *first,
                                uint32_t *second)
{
    /* Each full call rewrites REG_SDRAM_READ_ADDR_HI and arms a new read. */
    if (!readback_word(addr, first)) return false;
    return readback_word(addr, second);
}
```

The first corrected hardware run flashed successfully but is **invalid as
proof**: bitmap upload failed at offset 1518 with `VDP_HOST_ERR_TX` (`err=5`).
Health stayed clean, but because the upload did not complete, no readback
conclusion can be drawn.

BrightForge separately confirmed from the RTL that the SDRAM read is indeed
armed by the write to `REG_SDRAM_READ_ADDR_HI` (0x0327), not by `sel=8` polling
(#14558).

Physical QSPI bus capture is infeasible on the current host (no sigrok/PulseView,
Saleae, DSView, or logic-analyzer tooling available).

### Clean corrected rerun results (2026-08-01)

BronzeGate debugged the TX failure (it did not reproduce on rebuild/flash) and
ran the corrected Mode 6 cleanly:

- Uploads completed at 4 MHz; health `raw=0`, `overflow=0`, `malformed=0`.
- 8 repeats × 6 addresses using the full `readback_word()` twice per address.
- At `0x100008`, `0x10000C`, `0x101000`, `0x101004`: **both first and second
  calls returned `0x00000000` every time**.
- Dummy-neighbor pairs (`0x100004→0x100008`, `0x100FFC→0x101000`):
  `lag_matches=16/16`, `target_matches=0/16`.
- Result: `pass=0`. The simple 1-read-lag model predicts second-call
  `0x55555555` at the targets; this did not happen, so the fork remains open.

Physical QSPI/SDRAM bus capture remains infeasible on the current host.

### Rule 19 checkpoint result (2026-08-01)

Both BrightForge and BronzeGate independently approved the option-4
completion-poll readback surface:

- **BronzeGate (#14565):** Approve — use a dedicated spare `READ_STATUS`
  selector for `READ_DONE`; clear on `0x0327` arm write; assert only after the
  settled pixel-domain result latch.
- **BrightForge (#14566):** Approve — smallest host-visible surface that yields
  a definitively lag-free SDRAM readback. Also interprets the clean corrected
  double-read as leaning **write-side/physical**: because the second call still
  returned `0x00`, SDRAM itself is likely fetching `0x00` at the targets, even
  though the `sel=8` path also exhibits a real 1-read lag on dummy-neighbor
  pairs.

**Authorization granted:** BrightForge may implement option-4 RTL, CDC co-sim,
and a new bitstream; BronzeGate may build the matching proof firmware and run
HW test.

### Next steps

1. **BrightForge:** implement option-4 RTL (`READ_DONE` status bit + hardened
   `dbgResultPixArea` latch), add CDC co-sim proof, build bitstream (3-build
   STA, TNS=0, no regression). **DONE in commit `5ef5db2a` (gen `ff01ab71`);
   `sbt compile` PASS; `ReadDoneCdcSim` ALL PASS (ideal-2FF caveat); 3-build STA
   TNS=0 all clocks, BSRAM 40/46 (no new). Authoritative bitstream
   `fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs` SHA-256
   `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`
   (preserved read-only). Hardware-ready gate delivered #14576; PM flash AUTHORIZED
   (#14575). NOTE: #14575 named SHA `6fd0a81f` (a non-deterministic sibling build that
   overwrote `project.fs` and was never preserved — confirmed absent from disk); flash
   artifact RE-POINTED to the preserved, equivalent `project_0c218b9a_readdone.fs`
   (`0c218b9a…`, own report TNS=0) in #14577, awaiting PM re-point confirmation.**
2. **BronzeGate:** build proof firmware using arm → poll `READ_DONE` → read
   result, and run HW test at `0x100008`/`0x101000`. **Firmware `SCALER_PROOF_MODE=8`
   built in `158b9d7c` (#14573). On PM re-point confirmation (#14577), flash the named
   preserved file `project_0c218b9a_readdone.fs` (not bare `project.fs`) + explicit SRAM load.**
3. **TopazCliff:** track proof and pivot lane scope based on the result:
   - `0x55555555` ⇒ SDRAM writes are clean; defect is in `sel=8`/readback.
   - `0x00000000` ⇒ reopen physical write-side investigation with the two
     fixed-address constraint.
4. No production firmware/host driver uses the new surface unless PM decides.

---

## Closeout (2026-08-01)

### Final result

The option-4 `READ_DONE` completion-poll proof was executed in hardware using:

- FPGA bitstream `fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`, SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2` (RTL source `5ef5db2a`, generated `hw/gen/top_tang20k.v` SHA-256 `ff01ab71a1758b1844a60459cbfaf2f2e628bf20ed45bcb2ae77e13ede5bccb`).
- Proof firmware `firmware/esp32p4_scaler_proof`, source commit `158b9d7c`, workspace build `70c43d7a`, ELF SHA-256 `fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a`.

Mode-8 sequence: write `0x0326`/`0x0327` to arm → poll `READ_STATUS` sel `0x0C` bit 0 until `1` → read result via sel `0x08`.

| Check | Result |
|---|---|
| Bitmap upload | PASS, 30,720 bytes at 4 MHz |
| Attribute upload | PASS, 30,720 bytes at 4 MHz |
| `0x100008` (8 repeats) | `0x55555555` every repeat; max `READ_DONE` polls = 1 |
| `0x101000` (8 repeats) | `0x55555555` every repeat; max `READ_DONE` polls = 1 |
| Health before/after | `raw=0x00000000`, `overflow=0`, `malformed=0` |
| Overall | `READ_DONE_PROOF pass=1` |

### PM disposition

The lag-free `READ_DONE` read returned the expected checkerboard pattern at both historically-failing target words. Therefore **SDRAM writes are clean** and the earlier `sel=8` zeros are a **readback/CDC artifact of the existing diagnostic path**, not physical QSPI/SDRAM upload corruption.

- **No production RTL change.** The `READ_DONE` surface remains a proof-only selector (`0x0C`) on the `brightforge/read-done-diag` branch and is not merged to `main`.
- **No production firmware/host-driver change.** The `memcpy`→`memmove` fix in `619f76b8` is a real hygiene fix but was not the root cause; it may be picked up later at BronzeGate's discretion.
- **Document the caveat:** the `sel=8` SDRAM-content readback must be treated as a diagnostic-only path with a known 1-sample/CDC lag; do not use it as authoritative upload-verification evidence without the `READ_DONE` completion poll or equivalent.
- Optional future work: harden the existing `sel=8` path to be self-completing. This is **not on the critical path** and may be scoped later with its own Rule-19 checkpoint.

### Artifacts

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/READ_DONE_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/READ_DONE_SERIAL.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/READ_DONE_BUILD.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/simulation/READ_DONE_CDC_COSIM.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/synthesis/STA_3BUILD_SUMMARY.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/manifest.yaml`

## Out of Scope

- Reopening the `QspiSlave` clock-domain architecture (that was dispositioned in `QSPI_CLK_DOMAIN_EVAL.md`).
- Changing production display/fetch path.
- Flashing a new bitstream unless the chosen option requires RTL.

---

## Dependencies

- `720p-proof-build-script-cleanup` — DONE.
- `2bpp-bank-completion-rtl` — DONE (sim+PnR; this lane does not require its HW reproof).

## Next after this lane

- `2bpp-bank-completion-hw-reproof` (lane 1).

```

## File: PROJECT_PLAN/TASKS/quick-cleanup-ignored-artifacts.md

```md
# quick-cleanup-ignored-artifacts

## Owner
TopazCliff

## Status
DONE — 2026-07-27

## Background

The working tree has accumulated ignored local tool/environment artifacts that are not tracked by Git and are not part of the project source. The user requested a quick cleanup lane to remove them.

## Scope

Remove the following ignored artifacts from the working tree, after verifying none are actively in use:

- `.aider.chat.history.md`
- `.aider.input.history`
- `.aider.tags.cache.v4/`
- `.metals/`
- `.claude/settings.local.json` (and empty `.claude/` if only this file)
- `.mcp.json`
- `.venv-sessionlog/` — only if not in use by the current session/tooling
- `.worktrees/` — only after confirming no registered git worktrees point here

**Out of scope:** tracked source TODOs (`QspiSlaveSync`, `I80HostInterface`, `SpriteRasterizerSim`) — those are code/design decisions, not temp files.

## Acceptance criteria

- [x] Enumerated ignored artifacts before deletion.
- [x] Verified `.worktrees/` hosts active worktrees (`native-640-bitmap-148`, `native-640-firmware`) — skipped.
- [x] Verified `.venv-sessionlog/` not in use — deleted.
- [x] Deleted safe ignored artifacts: `.aider.*`, `.metals/`, `.claude/settings.local.json` (+ empty `.claude/`), `.mcp.json`, `.venv-sessionlog/`.
- [x] Confirmed no new untracked files introduced; only expected lane files remain before commit.
- [x] Updated `PROJECT_PLAN/STATUS.md` to mark this lane DONE.

## Blockers
None.

## Artifacts / References

- `.gitignore`
- `git status --ignored`

```

## File: PROJECT_PLAN/TASKS/scaler-rewrite-hw-proof.md

```md
# scaler-rewrite-hw-proof

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE  
**Opened:** 2026-07-27  
**Closed:** 2026-07-28  
**Closeout Commit:** `60b01ab`  
**Proof Packet:** `PROJECT_PLAN/proof_packets/scaler-rewrite-hw-proof/`  
**Source lane:** `external-review-scaler-rewrite`  
**Source branch:** `topazcliff/scaler-rewrite`  
**Source commit:** `9314aa0`  
  

---

## Purpose

The `external-review-scaler-rewrite` lane delivered a source-coordinate `ScaleCoordGen` and retired the sink-side `PixelRepeatScaler`. Sim + PnR proof is complete, but the lane was intentionally scoped as **sim+PnR only**. Project history shows multiple cases where sim passed and hardware exposed timing/CDC/SI issues that co-sim did not catch. This lane closes that gap with an unambiguous hardware proof of the scaler change.

## Scope

**In scope:**
- Build a bitstream from the `topazcliff/scaler-rewrite` branch (`9314aa0` or later if minor fixes are required).
- Flash the bitstream to the Tang Nano 20K bench board.
- Capture visual evidence for **1× mode** (production path) and verify byte-equivalence to the existing HW-proven `a5a047a2` baseline.
- Capture visual evidence for **>1× scaled modes** (2×/3× procedural/testpattern and/or bitmap where host support exists).
- Document capture procedure, exact register/config values, and golden comparisons.
- File a complete proof packet under `PROJECT_PLAN/proof_packets/scaler-rewrite-hw-proof/`.

**Out of scope:**
- New RTL features or host ABI changes. If scaled-mode host firmware does not yet exist, use the minimum existing register writes needed; do not design new host commands here.
- Bitmap/indexed fetch-side scaling (P3b) — that remains a separate PM-sequenced lane with a BronzeGate interface checkpoint.
- Fixing hardware-divergence bugs, if any. This lane first *measures* and reports; fixes become a new lane unless trivial and owner-authorized.

## Dependencies

- `external-review-scaler-rewrite` DONE (`9314aa0`, CyanPeak PASS).
- HW-proven baseline bitstream `a5a047a2…` available for regression comparison.
- Board free for flash/reconfigure and host upload path functional.

## Interfaces / State

- Reuses existing `SCALE_CTRL` register fields and `scaleCtrl` wiring from `external-review-scaler-rewrite`.
- Host sets `scaleX`, `scaleY`, `autoCenter`, `logicWidth`, `logicHeight` via existing register map.
- No new FPGA pins or host registers.

## Risks

- **Sim-vs-hardware divergence on scaled modes:** new combinational/reciprocal-multiply paths and registered coordinate latencies could interact with real SDRAM refresh, CDC, or sync timing differently than in sim.
- **1× regression risk:** even though 1× is mux-bypassed in `VdpTop`, synthesis variations or surrounding integration changes could still alter real output.
- **Capture-chain ambiguity:** downstream scaler/overscan artifacts (as seen in QSPI-CRC8-185) can be mistaken for VDP defects. Use deterministic patterns and direct `/dev/video0` captures where possible; document monitor/capture settings.
- **SDRAM content cleared by reconfigure:** the P4 host must re-upload after each flash. Factor this into the procedure.

## Validation

- **Sim (already done):** `ScaleCoordGenSim` 8/8, `ScaleUpFrameCoSim` >1× PASS, 1× regression byte-identical.
- **Hardware (this lane):**
  - Build `top_tang20k.v` and Gowin bitstream from `topazcliff/scaler-rewrite`.
  - Flash and verify.
  - Run 1× checkerboard or equivalent deterministic pattern; compare capture to `a5a047a2` baseline.
  - Run 2×/3× procedural/testpattern captures; verify stripe run-lengths and checkerboard spacing match expected `scaleX`/`scaleY` behavior.
  - Record health/status registers before/after enable.

## Audit Focus

- CyanPeak to review the hardware proof procedure and classification of results (exact / visually equivalent / divergent).
- Confirm that 1× captures are compared against the HW-proven baseline, not just sim golden vectors.

## Exit Condition

This task is done when the scaler-rewrite bitstream is flashed, 1× and >1× modes are captured on real hardware, the captures are compared against the `a5a047a2` baseline and expected scaled behavior, and a complete proof packet with artifact hashes is committed.

```

## File: PROJECT_PLAN/TASKS/scaler-rewrite-merge-prep.md

```md
# scaler-rewrite-merge-prep

## Owner
TopazCliff / BrightForge

## Status
DONE — 2026-07-28; regression PASS; **merged to `main`** at `a442707`; post-merge `sbt compile` + `Indexed2bppFineCoSim` PASS

## Background

The `topazcliff/scaler-rewrite` branch now contains the source-coordinate scaler, P3b bitmap/indexed fetch-side scaling, all external-review doc closeouts, and the recent cleanup commits. It is ahead of `main` (`f09159f`). Before merging to `main`, the branch must pass the same regression bar that `main` requires: compile clean, key co-sims green, PnR TNS=0, and a clean `git status`.

## Objective

Prepare `topazcliff/scaler-rewrite` for merge to `main` by running the standard regression suite and collecting a proof packet. Do **not** merge yet — this lane ends with a go/no-go recommendation and a signed-off proof packet.

## Scope

- Branch hygiene: verify current branch, clean working tree, list commits ahead of `main`.
- Compile: `sbt compile` must pass with zero errors.
- Elaboration: `sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"` must generate `hw/gen/top_tang20k.v` cleanly.
- Regression co-sims (production path):
  - `Indexed2bppFineCoSim` — fine-grained indexed 2bpp MATCH
  - `Indexed2bppCheckerCoSim` — checkerboard edge CLEAN
  - `Indexed2bppFrameCoSim` — LEFT-EDGE and ROW-CODED modes
  - `DirectColorFrameCoSim` — RGB565 X-ramp byte-exact at delay=0
- Synthesis/PnR: Gowin V1.9.12.01 `make pnr` (or equivalent) must produce TNS=0, no new BSRAM/DSP resource alarms, and a bitstream.
- Doc sanity: `PROJECT_PLAN.md` and `STATUS.md` reflect the branch state; `VOODOO_ADOPTION_PLAN.md` stale link noted.
- Produce a proof packet under `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/` with logs, hashes, and a `review.md` verdict.

## Acceptance criteria

- [x] Branch `topazcliff/scaler-rewrite` is clean; ahead of `main` (`f09159f`) by scaler-rewrite feature work + doc/cleanup closeouts.
- [x] `sbt compile` PASS.
- [x] `TopTang20kHdmiVerilog` elaboration PASS; generated `hw/gen/top_tang20k.v` SHA-256 `7ad5cee1…`.
- [x] All listed regression co-sims PASS (`Indexed2bppFineCoSim`, `Indexed2bppCheckerCoSim`, `Indexed2bppFrameCoSim`, `DirectColorFrameCoSim`).
- [x] Gowin PnR PASS: TNS=0, setup/hold violated endpoints=0; bitstream SHA-256 `8b241328…`.
- [x] Proof packet created at `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/` with `PASS.txt`, `review.md`, `manifest.yaml`, `hashes.sha256`, `simulation/regression.log`, `simulation/results.txt`, `synthesis/pnr.log`.
- [x] PM go recommendation: **GO for merge to main** pending PM sign-off; residual F1/F7 docs and stale `PROJECT_PLAN.md` link should be handled before/after merge.
- [x] Merged `topazcliff/scaler-rewrite` into `main` at commit `a442707`; resolved conflicts in `AGENTS.md`, `TopTang20kHdmi.scala`, `VdpTop.scala` (preserved main's BSRAM-L1-GATE conditional and PIXELWITHINBYTE-ALIGN fix while adopting branch's scaler `logicalX`/`logicalY` sourcing).
- [x] Post-merge sanity: `sbt compile` PASS, `Indexed2bppFineCoSim` PASS.

## Blockers
None.

## Artifacts / References

- Branch: `topazcliff/scaler-rewrite`
- Baseline (`main`): `f09159f`
- Proof packet template: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`

```

## File: PROJECT_PLAN/TASKS/standalone-diagnostic-build.md

```md
# standalone-diagnostic-build

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE — 2026-07-30  
**Closed:** 2026-07-30  
**Merge commit:** `ec5c9724`  
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

- [x] `sbt compile` passes with no errors.
- [x] Diagnostic Verilog generation (`make gen-diagnostic`) passes cleanly.
- [x] Gowin PnR passes with TNS=0 on all clocks and no new resource alarms.
- [x] Bitstream is produced; SHA-256 recorded in proof packet.
- [x] Hardware proof: 10/10 cold power cycles; HDMI locks every cycle; full-frame grid byte-identical `7803de18`.
- [x] Proof packet contains `manifest.yaml`, `hashes.sha256`, `PASS.txt`, `review.md`, synthesis summary, and capture hashes.
- [x] Production regression spot-check: default `TopTang20kHdmiVerilog` normalized diff vs `main` = 0 (only SpinalHDL line-number signal renames differ).

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

```

## File: PROJECT_PLAN/TASKS/upload-status-clear-rtl-decode.md

```md
# upload-status-clear-rtl-decode

**Owner:** BrightForge (RTL clear decode) + BronzeGate (firmware validation)  
**PM:** TopazCliff  
**Verifier:** CyanPeak (code-to-spec review)  
**Status:** OPEN — BronzeGate firmware sign-off complete; waiting on BrightForge RTL sign-off and implementation
**Opened:** 2026-08-01  
**Trigger:** External review of Lane 1 (`2bpp-bank-completion-hw-reproof`) flagged that uncleared upload-bridge sticky bits could derail automated multi-cycle reproofs. The firmware helper already issues `0x0323`, but the RTL decoder is missing (`FULL-DOC-AUDIT-151` finding #4).

---

## Background

`UPLOAD_STATUS_CLEAR` (`0x0323`) is documented in `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2 as a write-1-to-clear register for upload-bridge sticky bits surfaced by `READ_STATUS` `sel=6` / `SEL_TRANSPORT_HEALTH` `sel=0x0A`:

| Bit | Name | Cleared by |
|---|---|---|
| 2 | `upload_error` / `watchdog_abort` | `0x0323` bit 2 |
| 3 | `upload_overflow` / `fifoOverflow` | `0x0323` bit 3 |
| 4 | `txn_dropped` | `0x0323` bit 4 |
| 5 | `short_frame` (reserved / Fix A) | `0x0323` bit 5 |

`firmware/libvdp/vdp_host.c` `vdp_clear_upload_status()` issues the documented write on both QSPI and i80 backends, and `firmware/libvdp/vdp_host_p4.c` does the same for the ESP32-P4 QSPI app. However, `PROJECT_PLAN/DOC_AUDIT_FINDINGS.md` #4 confirms that `0x0323` is **not decoded anywhere in the current RTL**: `VdpTop.scala` handles `0x0320..0x0322`, and neither `QspiDecoder.scala` nor `I80HostInterface.scala` has a clear input. A register-coverage script showed `0x0323` as the only allocated address with no RTL decoder.

Until the decode lands, sticky upload-status bits clear only at power-on reset or through an upload-bridge reset path. In automated 10-cycle reproofs, a single transient sticky assertion on cycle *N* will falsely poison cycles *N+1..10* because `vdp_clear_upload_status()` is a no-op at the hardware level.

---

## Scope

1. **RTL decode in the register-write path**
   - Decode `REG_WRITE` to address `0x0323` in `VdpTop.scala` (and the equivalent i80 register-write path if it is separate).
   - Drive one-cycle clear strobes matching the write-data bits to the upload bridge status registers:
     - `QspiSdramBridge` / `QspiDecoder` sticky regs for `upload_error`, `fifoOverflow`, `txn_dropped`.
     - `I80HostInterface` block-write status regs for the same bits.
   - Implement genuine write-1-to-clear semantics: a set bit in the write data clears the corresponding sticky flag; zeros leave it unchanged. A clear and a live set in the same cycle must not lose the live event.

2. **Spec and doc updates**
   - Remove the "current limitation" note from `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2 and the register table once the decode is proven.
   - Update `firmware/GOTCHAS.md` FIDELITY-2/FIDELITY-6 to remove the workaround language after validation.
   - Update `firmware/libvdp/mode0_regs.json` `UPLOAD_STATUS_CLEAR` description to remove the pending-decode caveat.

3. **Validation**
   - BronzeGate: validate `vdp_clear_upload_status()` on QSPI (ESP32-P4) and, if an i80 test harness is available, on i80.
   - Demonstrate that setting then clearing each sticky bit via the register works as expected.

4. **Out of scope**
   - New sticky-bit definitions. Only the existing bits 2..4 (and bit 5 placeholder if Fix A has landed) need clear strobes.
   - Changes to `vdp_clear_upload_status()` firmware signature or mask — the helper is already correct.

---

## Acceptance Criteria

- [ ] `VdpTop.scala` (and i80 path) decodes `0x0323` writes and emits W1C clear strobes.
- [ ] Sticky bits are individually clearable without losing a concurrently occurring error.
- [ ] All existing co-sims pass (`Indexed2bppFineCoSim`, `Indexed2bppCheckerCoSim`, `Indexed2bppFrameCoSim`, `QspiTransportBridgeSim` or successor).
- [ ] Gowin PnR is clean (TNS=0, no new BSRAM/DSP).
- [ ] BronzeGate validates clear behavior on hardware (QSPI; i80 if harness available).
- [ ] CyanPeak code-to-spec review PASS.
- [ ] Docs updated in the same logical change that lands the RTL fix.
- [ ] Proof packet created under `PROJECT_PLAN/proof_packets/upload-status-clear-rtl-decode/`.
- [ ] `STATUS.md` lane updated to `DONE` with proof.

---

## Dependencies

- None hard; this can be worked in parallel with Lane 1 hardware reproof if BrightForge has bandwidth.
- Validation depends on a working QSPI bench setup (currently active for Lane 1).

## Risks / Open Questions

1. **Timing of landing vs. Lane 1 reproof:** If the decode is not ready before Lane 1 runs, Lane 1 must treat any non-zero sticky bit as a hard abort for the whole run rather than a per-cycle failure. This is already recorded in `PROJECT_PLAN/TASKS/2bpp-bank-completion-hw-reproof.md`.
2. **i80 path:** The i80 `READ_STATUS` opcode `0x04` response path is also pending (`DOC_AUDIT_FINDINGS.md` #3). If this lane also implements that, update scope; otherwise keep i80 scope limited to the register-write clear decode.
3. **W1C atomicity:** A write that clears bit 3 while the bridge is asserting overflow in the same cycle must result in the bit remaining set. The standard "clear takes effect combinationaly but the set wins in the same cycle" pattern is acceptable.

---

## PM scope decision (2026-08-01)

An external reviewer provided a Rule-19-style checkpoint draft that aligns with
BrightForge's option (A): implement the documented `0x0323` W1C decode for the
implemented bridge upload-status bits surfaced on `READ_STATUS` `sel=6`, with
**zero firmware changes**.

**Decision:**

1. **Primary scope is (A):** implement the `0x0323` write-1-to-clear decode for
   the implemented `sel=6` upload-status sticky bits: **bit 2 `upload_error`**
   and **bit 3 `upload_overflow`**. Re-surface `sel=6` if it is currently tied
   off. Use the exact bit mapping in
   `PROJECT_PLAN/INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md`.
2. **Bits 4 and 5 are RESERVED-0:** `txn_dropped` and `short_frame` have no
   backing sticky detector in the current RTL. The `0x0323` payload bits 4/5 are
   ignored (no-ops). Separate detectors may be authorized later; this lane does
   not introduce them.
3. **Zero firmware changes:** BronzeGate confirms the existing
   `VDP_UPLOAD_STATUS_ERROR` / `OVERFLOW` masks (bits 2/3) match the mapping;
   the `TXN_DROPPED` mask (bit 4) is reserved-0 in hardware.
4. **Opportunistic (B) only if free:** making the live `sel=0x0A`
   transport-health stickies (`overflow`/`malformed`) clearable is acceptable,
   but must not expand schedule or require firmware mask changes. If it cannot
   be done inside the ~1-day option-A envelope, defer it.
5. **Keep i80 `READ_STATUS` opcode `0x04` out of this lane:** that is the
   separate read-path finding (`DOC_AUDIT_FINDINGS.md` #3). This lane is the
   register-write `0x0323` clear decode on both QSPI and i80 write paths.
6. **Rule 19 checkpoint:** BrightForge and BronzeGate both approved
   `PROJECT_PLAN/INTERFACE_CHECKPOINT_0x0323_upload_status_clear.md` with the
   bits-4/5 reservation (mail #14599/#14601). The checkpoint has been updated
   accordingly and is now final.
7. **Bitstream isolation:** this lane builds its own bitstream; do **not** fold
   the decode into the `a5a047a2` Lane 1 authority bitstream, because that would
   invalidate the bank-completion hardware reproof.

## Next Action

**BrightForge:** begin implementation on a separate lane bitstream. Estimated
~0.5–1 day RTL + sim + PnR. Post the exact signal/clock-domain mini-spec before
committing RTL if the implementation deviates from the checkpoint.
**BronzeGate:** stand by to validate the clear behavior on hardware once the
bitstream is ready.

```

## File: README.md

```md
# spinalhdlVDP

Fresh SpinalHDL-based Tang Nano 20K HDMI VDP development repository.

Project identity: `spinalhdlVDP`.

## Team Roles

| Agent | Model | Role | Core Focus |
|---|---|---|---|
| `BrightForge` | Claude | FPGA RTL Engineer | Structural HDL, state machines, timing-sensitive logic, FPGA proof |
| `BronzeGate` | Codex | MCU Firmware Engineer | Bare-metal C/C++, register manipulation, transport and hardware drivers |
| `CyanPeak` | Antigravity CLI (`agy`) | Datasheet Parser & Reviewer | Large manual ingestion, code-to-spec review, hardware-accuracy checks |
| `TopazCliff` | Kimi (Inst. 2) | Technical Project Manager | Feature tickets, HW/SW interface definition, sequencing, timelines |
| `CoralReef` | Kimi | Compliance & Documentation | Static-ruleset audit, compliance checks, README/doc generation |

## Repository layout

- `hw/spinal/spinalhdlvdp/` Scala / SpinalHDL sources
- `hw/gen/` generated HDL output
- `fpga/tang20k/` Tang Nano 20K HDMI build files
- `firmware/libvdp/` host driver library (C/C++): QSPI (active) / i80 (retired) / legacy SPI transports, Mode0 helpers, register map
- `PROJECT_PLAN/archive/firmware_tests/` historical example / proof-of-concept sketches (retired from the canonical path)
- `kb/` local hardware and Gowin documentation
- `scripts/assets/` host-side asset conversion helpers for PNG → VDP data
- `scripts/gen_reg_docs.py` register-spec generator from `firmware/libvdp/mode0_regs.json`
- `project/` SBT project metadata

The Scala package for this repository is `spinalhdlvdp`.

## Toolchain

- **Scala:** Java 11+, `sbt`
- **FPGA:** Gowin IDE CLI `gw_sh`, `openFPGALoader`
- **Firmware:** `idf.py` / ESP-IDF v6.0.2 (ESP32-P4 canonical); historical sketches are archived
- **Assets:** Python 3.8+ (PNG → VDP)

## Host Interface

The current Tang Nano 20K deployment uses a **1-1-4 quad-SPI (QSPI) bus** driven by an **ESP32-P4** as the canonical host path. The active RTL front-end is `QspiSlave` → `QspiDecoder` → `QspiSdramBridge` in `hw/spinal/spinalhdlvdp/`. `firmware/libvdp/vdp_host.h` exposes host-neutral register and SDRAM upload calls over this interface.

- **QSPI protocol:** opcode `0x01` register write, `0x02` SDRAM write, `0x04` read status; CS#/SCK control with quad I/O on `spi_io[3:0]`.
- **Readback semantics:** `READ_STATUS` selectors return live transport/SDRAM status. Most other register reads return the last-written value (loopback) or are transport-dependent.
- **i80 (retired):** the 8-bit parallel i80 path driven by ESP32-S3 is **retired from the canonical path**. It remains in the tree as historical reference only.
- **Legacy SPI:** the 4-wire legacy SPI path remains supported for Raspberry Pi Pico 2 and earlier ESP32/ESP8266 bench setups, but it is **retired from the canonical ESP32-P4 path**. See `PROJECT_PLAN/PLATFORM.md` for pinouts and `PROJECT_PLAN/archive/deleted legacy host control plan` for historical legacy SPI details.

## Current Development Focus

The active lane is **QSPI word-drain transport + 2bpp indexed bitmap display** on Tang Nano 20K with an ESP32-P4 host. The previous HAM6 render mode has been **shelved** from the critical path and `bpp=0b11` is reserved for future work. See `VDP_PROGRAMMING_GUIDE.md` §12 for the 2bpp indexed reference-mode programming sequence and `PROJECT_PLAN/STATUS.md` for lane state.

## Mode0 Architecture

`Mode0` is a foundational rendering substrate providing generic primitives: raster timing, fetch, composition, palette, sprites, scrolling, Copper, and HDMA.

**Principles:**
1. **Generic Core:** The VDP RTL is a purely generic graphics IP. It grows universal capabilities needed by multiple platforms but contains zero platform-specific logic.
2. **Firmware Personality:** Platform-specific personality (register shims, initialization sequences, asset management) resides entirely in `libvdp` or host-side firmware.
3. **Quirk Isolation:** Platform-specific quirks are handled by the host library translating to generic Mode0 register writes.

Roadmap: [`PROJECT_PLAN/archive/planning/MODE0_PLANNING.md`](PROJECT_PLAN/archive/planning/MODE0_PLANNING.md).
User guide: [`VDP_PROGRAMMING_GUIDE.md`](VDP_PROGRAMMING_GUIDE.md).

```

