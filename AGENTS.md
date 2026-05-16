# AGENTS.md — spinalhdlVDP

Repo-specific rules for `/home/itadmin/github/spinalhdlVDP`.

The workspace file at `/home/itadmin/github/AGENTS.md` remains authoritative
for canonical identity, roster, and cross-project coordination rules.

Examples and command snippets: `AGENTS_EXAMPLES.md`

---

## Identity

| Canonical Name | Role | Model |
|----------------|------|-------|
| `BronzeGate` | PM / sequencing | Codex |
| `BrightForge` | FPGA implementation | Claude |
| `CoralReef` | Coordination / ledger | Kimi |
| `CyanPeak` | Audit / sign-off | Gemini |
| `FoggyWolf` | MCU / host-transport | (server-assigned) |

**External-review exception:** `TopazCliff` — outside review / advisory only. Not part of the execution roster. Does not replace `CoralReef` for any in-repo ownership.

**FoggyWolf is NOT an external-review exception.** Must register, commit, and operate inside this repository.

## Mail Registration

Project mailbox: `/home/itadmin/github/spinalhdlVDP`

Register with the same canonical name used in other project mailboxes.

All in-repo agents must use this single repo-root mail project for lane
packets, replies, acknowledgements, and coordination. Do not create or use a
subdirectory-specific mailbox, a firmware-only mailbox, or an external
workspace mailbox for `spinalhdlVDP` work.

| Do | Do Not |
|----|--------|
| Use canonical name from Identity table | Omit the `name` field |
| `ensure_project` + `register_agent` with `human_key=/home/itadmin/github/spinalhdlVDP` | Create a fresh alias |
| | Use a different display name |
| Use the repo-root mailbox for all replies and ACKs | Route firmware mail through a separate mailbox |

If the canonical name is unavailable, stop and resolve the mismatch. Do not create a replacement identity.

## Scope

- **Primary repo:** SpinalHDL implementation lane for Tang Nano 20K VDP.
- Older sibling repos are historical reference only, not peers for execution planning.
- Keep here:
  - `hw/spinal/spinalhdlvdp/` — SpinalHDL source
  - `hw/gen/` — generated HDL
  - `fpga/tang20k/` — wrappers, constraints, integration glue
- Port from older repos only what is intentionally adopted.

## Mandatory Session Start Rule

Read before acting:

1. `AGENTS.md`
2. `PROJECT_PLAN/PROJECT_PLAN.md`
3. `PROJECT_PLAN/TASKS.md`

Then, if needed:
- `PROJECT_PLAN/MODE0_PLANNING.md` — roadmap-driven work
- `PROJECT_PLAN/TASK_TEMPLATE.md` — new bounded task
- Active `PROJECT_PLAN/TASK_*.md` — current execution lane

Working rule: if mail, docs, and local assumptions disagree, stop and reconcile before coding or assigning work.

## Build Path Rules

- keep the Scala package name as `spinalhdlvdp`
- keep `build.sbt` and `build.sc` pointing to `hw/spinal`
- keep generated HDL under `hw/gen`
- use `hw/verilog` and `hw/vhdl` only for deliberate checked-in outputs

If those paths change, update `README.md`, `build.sbt`, `build.sc`, and
`Config.scala` together in the same change.

When editing Scala source in `hw/spinal/`:

- use `metals-lsp` first for symbol navigation, compile diagnostics, and reference search
- do not rely on manual grep or file reading when the language server can answer the question directly

## Validation

For FPGA-affecting changes:

- run a simulator-based validation step before claiming hardware-ready status
- do not mix stale generated HDL with current Scala sources
- regenerate outputs from the current source tree before downstream Gowin use
- follow `PROJECT_PLAN/TEST_PATTERN_POLICY.md` for task proof scenes

### Artifact Match Rule

Hardware proof must use artifacts verified to match the intended source state.

- do not assume a flashed sketch or bitstream is current just because upload or
  programming succeeded
- before bench testing, verify the flashed firmware matches the intended
  sketch/build and the flashed FPGA bitstream matches the intended source/build
- if the match cannot be proven, rebuild and reflash before testing
- for Tang Nano 20K, do not flash `fpga/tang20k/impl/pnr/project.fs` as
  "current" when it is older than `hw/gen/top_tang20k.v`

### 100% Verification Rule (Mandatory)

**Every task must be proven 100% before closeout. No exceptions.**

- Ambiguous or "probably correct" states are not acceptable.
- Simulator proof alone is not sufficient for hardware-facing primitives; an unambiguous hardware proof is also required.
- If visual proof is noisy or ambiguous, a dedicated diagnostic asset/probe must be created to resolve the ambiguity.
- A task is not closed until the final evidence is definitive and reproducible.

After running simulation:

- use `pywellen` to query waveform files (VCD/FST) for signal values, timing checks, and behavioral proof
- do not launch GTKWave or parse VCD text manually when `pywellen` can answer the query directly

## Execution Workflow (Summary)

| Role | Responsibility |
|------|----------------|
| `BrightForge` | FPGA implementation, validation, proof |
| `CyanPeak` | Audit, sign-off, memory curation |
| `CoralReef` | Coordination, ledger/doc sync, preflight research |
| `BronzeGate` | Sequencing, scope control, stall intervention |
| `FoggyWolf` | MCU firmware, host transport, platform parity, scenario bootstrap |

**Rules:**
- One active engineering lane at a time on the critical path.
- Source of truth order: (1) authoritative mail → (2) `TASKS.md` live-lane → (3) repo state.
- Fast-flow: shortest trustworthy cycle, smallest proof-sized batches, earlier discriminators.
- Ledger sync is part of closeout, not cleanup.

### Deliverable Verification Rule

A lane deliverable counts only when the mailbox-visible message matches the
required owner and packet type.

- if an agent claims "I sent it", they must provide the exact message id
- the visible message must match the required lane owner and packet type
  (`planning`, `completion`, `audit`, `blocker`, or `ETA`)
- a different agent's message or a different packet type does not satisfy the
  missing deliverable unless `BronzeGate` explicitly reassigns the lane
- if the claimed message cannot be verified in the mailbox, treat it as not
  received and require resend
- after repeated non-response, `BronzeGate` may reassign the lane without
  waiting further

Detailed templates, checklists, and escalation policy: `PROJECT_PLAN/archive/AGENTS_WORKFLOW_RULES.md`.
Examples and command snippets: `AGENTS_EXAMPLES.md`.

## FoggyWolf Scope and Rules

`FoggyWolf` — MCU / host-transport agent.

| Attribute | Value |
|-----------|-------|
| Registration | Server-assigned adjective+noun; accept it, do not force custom names |
| Workspace | `/home/itadmin/github/spinalhdlVDP/` (repo root or `firmware/`) |
| AGENTS.md hierarchy | `firmware/AGENTS.md` overrides root `AGENTS.md` inside `firmware/` |

### Ownership

| Owns | Does Not Touch |
|------|----------------|
| `firmware/libvdp/` — cross-platform host driver | `hw/spinal/` — SpinalHDL source |
| `firmware/GOTCHAS.md` | `hw/gen/` — generated HDL |
| `firmware/README.md` | `fpga/tang20k/` — FPGA build flow |
| Scenario sketches (ESP8266, ESP32, Pico) | `PROJECT_PLAN/` — planning / ledger (read-only) |
| QSPI transport validation | RTL architecture, synthesis, PnR, simulation |
| Platform build systems (CMake / Arduino) | |

### Working Rules

| # | Rule | One-line requirement |
|---|------|----------------------|
| 1 | Inside-repo only | Work from this repo; external workspaces not permitted |
| 2 | Register contract is read-only | Consume register map / QSPI spec from `BrightForge` / `CoralReef`; do not invent new commands or addresses |
| 3 | Scenario parity is mandatory | Every ESP8266 sketch needs a plan for ESP32 + Pico parity |
| 4 | Host-side proof standard | Build clean → same HDMI output as canonical → update `GOTCHAS.md` if new pitfall found |
| 5 | Library-first preference | Reusable logic belongs in `libvdp/`; sketches are thin wrappers |
| 6 | Coordination handoff | Check `TASKS.md` Live Lane State → confirm contract with `BrightForge` → confirm authorization with `BronzeGate` |
| 7 | Platform identity | Part of canonical roster; same mail project, git repo, and task ledger as FPGA agents |

### MCP Servers Relevant to FoggyWolf

| Server | Purpose |
|--------|---------|
| `clangd-lsp` | C firmware navigation and diagnostics |
| `gdb` | C firmware debugging |
| `uart` | Serial console sessions |
| `mcp-git` | Git history and diff queries |
| `mcp-agent-mail` | Cross-agent coordination |

## Source of Truth Order

Use this order every time:

1. latest authoritative mail packet for the active lane
2. `PROJECT_PLAN/TASKS.md` live-lane block
3. current repo state / commit under discussion

## Adapter Documentation Policy

To avoid adapter-spec sprawl, each platform adapter must have exactly one
canonical knowledge file under `kb/`.

Working rule:

- use `kb/<Adapter>/README.md` as the single canonical adapter document
- do not split the live adapter contract across `PROJECT_PLAN/`,
  `firmware/README.md`, ad hoc notes, and task artifacts
- `PROJECT_PLAN/` may summarize status, priority, and archive references, but
  must point back to the `kb/` adapter file for the current contract
- firmware sketches and proof code remain in `firmware/`, but the host-side
  workflow they implement must be described in the adapter's `kb/` file

Each canonical adapter file should contain, at minimum:

1. video model summary
2. supported features
3. unsupported / deferred features
4. adapter register surface
5. Mode0 mapping
6. host memory layout
7. firmware workflow
8. proof / validation plan
9. known gaps / gotchas
10. reference links

## Context Compression

When resuming or handing off, compress state to:

- task / checkpoint
- latest commit
- latest authoritative mail
- blocker or next allowed step

Do not carry full conversational history forward when those four facts are
sufficient.

## Memory Curation Rule

The `memory` MCP is a **queryable cache**, not the authoritative log. Backing store: `/home/itadmin/github/.mcp_memory/sqlite_vec.db` (global path `/home/itadmin/.mcp_memory/sqlite_vec.db` is a symlink to the same file).

| Authority Order | Source |
|-----------------|--------|
| 1 | `mcp-agent-mail` |
| 2 | Repo task/state docs |
| 3 | Shared `memory` cache |

| Owner | Responsibility |
|-------|----------------|
| `CoralReef` | Initial curated memory pass |
| `CyanPeak` | Ongoing updates: audits, bug fixes, hardware findings, Tang/Gowin constraints; also keep memory current for new mail, commits, and state deltas |

**Workflow:** check `memory` first → use mail/docs as authority → add back only short, reusable findings.

**Typical uses:**
- Tang/Gowin gotchas before hardware-debug branch
- Prior audit conclusions before architecture restatement
- Root-cause/fix pairs before repeating experiments
- Validation patterns / proven constraints before new task

## MCP Servers

Repo-local configuration: `.mcp.json` (sibling to this file).

Configured servers and typical use:

| Server | Purpose |
|--------|---------|
| `metals-lsp` | Scala/SpinalHDL — goto-definition, compile diagnostics, symbol search |
| `verilator` | Verilog simulation + natural-language waveform queries |
| `verible-lsp` | Generated Verilog lint and style checks |
| `mcp-eda` | Yosys synthesis, Icarus simulation, GTKWave launch |
| `pywellen` | VCD/FST waveform analysis via natural language |
| `clangd-lsp` | Firmware C code navigation and diagnostics |
| `z3smt` | Formal verification / constraint solving |
| `task-orchestrator` | Task dependency tracking and status |
| `mcp-agent-mail` | Cross-agent coordination mail |
| `memory` | Shared searchable cache (see Memory Curation Rule) |
| `pdf-reader` | Datasheet and manual ingestion |
| `context7` | Library/framework documentation lookup |
| `mcp-git` | Git history and diff queries |
| `gdb` | C firmware debugging |
| `uart` | Serial console sessions |
| `markdownlint` | Documentation linting |
| `agent-hub` | Agent discovery and hub coordination |

**Notes:**
- `metals-lsp`: binary at `~/.local/share/coursier/bin/metals`
- `pywellen`: installed from source in `/home/itadmin/mcp-servers/pywellen-mcp` (venv)
- Workspace-scoped LSP servers point to this repository
- Most servers wrapped through `/home/itadmin/mcp-servers/mcp-cache/dist/index.js` for stdio caching

## PDF Rule

Default tool for datasheets, manuals, and app notes: `pdf-reader`.

**Workflow:**
1. Read/query PDF with `pdf-reader`
2. Extract reusable findings
3. Store concise findings in `memory` with strong tags

Do not treat `memory` as a full-document store for PDFs.

| Tag | When to use |
|-----|-------------|
| `vdp` | Minimum tag for all entries |
| `resume` | Resume-relevant state |
| `planning` | Architecture / roadmap |
| `audit` | Audit findings |
| `gotcha` | Pitfalls and fixes |
| `hardware` | Hardware-specific |
| `tang20k` | Tang Nano 20K |
| `gowin` | Gowin toolchain |
| `task-15`, `r1`, `r2`, `roadmap` | Task/lane-specific |

Do not dump raw mail or long logs into memory. Store short, query-friendly summaries with mail/doc tie-back.

## Preventive Rules

Binding rules mirrored from workspace `AGENTS.md`. Enforced to prevent identity, authorization, and contract drift.

| # | Rule | One-line Requirement |
|---|------|----------------------|
| 1 | Role Transfer | No self-declared role absorption. Requires BronzeGate authorization + transition mail + `AGENTS.md` update |
| 2 | Audit Singleton | Only CyanPeak issues PASS/HOLD/FAIL. Outgoing owner must confirm retirement before transfer |
| 3 | Commit-Within-Cycle | Audit PASS work must be committed before next PM review. Audit owner may withhold PASS until commit hash is in packet |
| 4 | Contract Deviation | >25% deviation from locked hardware contract must be documented in `GOTCHAS.md` with quantitative analysis |
| 5 | Signoff Consistency | One canonical signoff per agent. No mixed aliases mid-thread |
| 6 | Identity Retirement | Requires retirement mail + roster removal + no pending audits + 24h observation window |
| 7 | Side-Lane Authorization | Parallel work requires BronzeGate lane-open authorization before implementation |
| 8 | AGENTS.md Immutability | No unilateral rewrites. Requires BronzeGate authorization AND CyanPeak audit AND diff review |

**Canonical QSPI contract:** 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.

**Signoff strings:**
- `— FoggyWolf`
- `— CyanPeak`
- `— CoralReef`
- `— BrightForge`
- `— BronzeGate`

If you believe a rule is wrong, escalate to BronzeGate with a specific amendment proposal. Do not edit the file directly.
