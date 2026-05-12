# AGENTS.md — spinalhdlVDP

Repo-specific rules for `/home/itadmin/github/spinalhdlVDP`.

The workspace file at `/home/itadmin/github/AGENTS.md` remains authoritative
for canonical identity, roster, and cross-project coordination rules.

---

## Identity

Use the workspace canonical names:

- `BronzeGate` for Codex
- `BrightForge` for Claude
- `CoralReef` for Kimi
- `CyanPeak` for Gemini
- `FoggyWolf` for the MCU / host-transport agent

Do not invent alternate names in this repository.

External-review exception (only one):

- `TopazCliff` is allowed to send **outside review / advisory** messages into
  this project mailbox
- `TopazCliff` is not part of the canonical execution roster for
  `spinalhdlVDP`
- `TopazCliff` must not replace `CoralReef` for lane ownership, ledger sync,
  approvals, or canonical Kimi identity inside this repo
- all in-project Kimi workflow ownership here remains `CoralReef`

**FoggyWolf is NOT an external-review exception.** `FoggyWolf` is part of the
canonical execution roster and must register, commit, and operate inside this
repository. An outside workspace is not permitted.

## Mail Registration

This repo has its own project mailbox at:

- `/home/itadmin/github/spinalhdlVDP`

When joining this repo in the mail system, register with the same canonical
name already used in the other project mailboxes.

Required names:

- `BronzeGate` for Codex
- `BrightForge` for Claude
- `CoralReef` for Kimi
- `CyanPeak` for Gemini
- `FoggyWolf` for the MCU / host-transport agent

Do not:

- omit the `name` field
- create a fresh alias for this repo
- use a different display name than the one already used in the other repos

Minimum registration sequence:

1. `ensure_project` with `human_key=/home/itadmin/github/spinalhdlVDP`
2. `register_agent` with:
   - `project_key=/home/itadmin/github/spinalhdlVDP`
   - `name=<canonical name above>`
   - `program=<your client name>`
   - `model=<your actual model name>`

If registration says the canonical name is unavailable, stop and resolve the
mismatch. Do not create a replacement identity.

## Scope

This repository is the dedicated SpinalHDL implementation lane for the Tang
Nano 20K VDP effort.

For current and future work sessions, treat this repository as the primary and
authoritative project / repo for the active VDP effort.

Do not treat older sibling repositories as equal peers for execution planning.
They may exist for historical reference, but they are not the source of truth
for the current implementation lane.

Keep here:

- SpinalHDL source under `hw/spinal/spinalhdlvdp/`
- generated HDL configuration targeting `hw/gen/`
- Tang Nano 20K specific wrappers, constraints, and integration glue

Do not treat older VDP repositories as the source of truth for this repo's
directory structure. Port only what is intentionally adopted.

## Mandatory Session Start Rule

When starting a new session in this repository, read the current project docs
before proposing work, assigning work, or coding.

Minimum required reading order:

1. `AGENTS.md`
2. `PROJECT_PLAN/PROJECT_PLAN.md`
3. `PROJECT_PLAN/TASKS.md`

Then read any active planning artifact needed for the current lane:

- `PROJECT_PLAN/MODE0_PLANNING.md` for roadmap-driven work
- `PROJECT_PLAN/TASK_TEMPLATE.md` when defining a new bounded task
- the active `PROJECT_PLAN/TASK_*.md` file for the current execution lane

Working rule:

- use the docs above to understand what the project is, what is already proven,
  and where the project currently is before taking action
- if mail, docs, and local assumptions disagree, stop and reconcile before
  coding or assigning work

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

- One active engineering lane at a time on the critical path.
- Source of truth order: (1) latest authoritative mail, (2) `TASKS.md` live-lane block, (3) current repo state.
- Standing role split:
  - `BrightForge`: FPGA implementation, validation, proof
  - `CyanPeak`: audit, sign-off, memory curation
  - `CoralReef`: coordination, ledger/doc sync, preflight research
  - `BronzeGate`: sequencing, scope control, stall intervention
  - `FoggyWolf`: MCU firmware, host transport, platform parity, scenario bootstrap sketches
- Fast-flow: optimize for shortest trustworthy cycle time, smallest proof-sized batches, and earlier discriminators.
- Ledger sync is part of closeout, not cleanup.

For detailed packet templates, audit checklists, escalation policy, and coordination rules, see `PROJECT_PLAN/archive/AGENTS_WORKFLOW_RULES.md`.

## FoggyWolf Scope and Rules

`FoggyWolf` is the dedicated MCU / host-transport agent.

**Registration name:** The mail system auto-generates adjective+noun names. FoggyWolf was assigned by the server. Do not attempt to force custom names; accept the auto-generated handle.
**Workspace:** `/home/itadmin/github/spinalhdlVDP/` (may operate from repo root or `firmware/` subdirectory)
**AGENTS.md hierarchy:** `firmware/AGENTS.md` governs firmware-specific work and overrides root `AGENTS.md` for operations inside `firmware/`. FoggyWolf may read the root `AGENTS.md` for general project identity, mail rules, and cross-agent coordination.

### What FoggyWolf Owns

- `firmware/libvdp/` — cross-platform host driver library (Pico PIO + ESP32/ESP8266 Arduino core)
- `firmware/GOTCHAS.md` — firmware-specific pitfalls and proven fixes
- `firmware/README.md` — build and flash instructions
- Scenario bootstrap sketches for **all supported platforms**:
  - ESP8266 (`esp8266_*`)
  - ESP32 (`esp32_*`)
  - Raspberry Pi Pico (`test_qspi_wire/`, `test_qspi_smoke/`, etc.)
- QSPI transport validation and timing verification on each platform
- Platform-specific build systems:
  - CMake for Pico (`test_qspi_wire/CMakeLists.txt`)
  - Arduino IDE / CLI for ESP8266 and ESP32

### What FoggyWolf Must NOT Touch

- `hw/spinal/` — SpinalHDL source
- `hw/gen/` — generated HDL
- `fpga/tang20k/` — FPGA build flow, constraints, pin assignments
- `PROJECT_PLAN/` — substrate planning and task ledger (read-only for context)
- RTL architecture decisions, synthesis, PnR, simulation

### Working Rules

1. **Inside-repo only.** `FoggyWolf` must work from `/home/itadmin/github/spinalhdlVDP/`, register in this repo's mail project, and commit directly to `firmware/` inside this repo. Operating from an external workspace (like `TopazCliff`) is **not permitted**.

2. **Register contract is read-only.** FoggyWolf consumes the register map, QSPI framing spec, and scenario definitions from `BrightForge` / `CoralReef`. FoggyWolf does not invent new transport commands, header formats, or register addresses.

3. **Scenario parity is mandatory.** Every hardware-proven scenario that gets an ESP8266 sketch must eventually receive matching ESP32 and Pico sketches. Do not add a new ESP8266 sketch without a plan (and ideally a task) to close the parity gap on the other platforms.

4. **Host-side proof standard.** A firmware task is not closed until:
   - The sketch builds cleanly for the target platform
   - It produces the same HDMI output (or passes the same diagnostic check) as the canonical ESP8266 version
   - `firmware/GOTCHAS.md` is updated if a new platform-specific pitfall is discovered

5. **Library-first preference.** Reusable QSPI framing, upload, and status logic belongs in `libvdp/`. Scenario sketches should be thin wrappers that call `libvdp` APIs. Do not duplicate QSPI bit-bang logic across sketches.

6. **Coordination handoff.** Before starting a firmware lane, FoggyWolf must:
   - Check `TASKS.md` Live Lane State for conflicts
   - Confirm the register contract and scenario definition with `BrightForge`
   - Confirm the lane is authorized by `BronzeGate`

7. **Platform identity.** FoggyWolf is part of the canonical execution roster for `spinalhdlVDP`, not an external reviewer. Use the same mail project, same git repo, and same task ledger as the FPGA agents.

### MCP Servers Relevant to FoggyWolf

From the server table below, FoggyWolf's primary tools are:

- `clangd-lsp` — C firmware navigation and diagnostics
- `gdb` — C firmware debugging
- `uart` — Serial console sessions for target boards
- `mcp-git` — Git history and diff queries
- `mcp-agent-mail` — Cross-agent coordination

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

This repo uses the shared workspace `memory` MCP as a queryable cache, not as
the authoritative project log. It uses the unified workspace backing store at
`/home/itadmin/github/.mcp_memory/sqlite_vec.db`.

**Note:** The global path `/home/itadmin/.mcp_memory/sqlite_vec.db` is a symlink
to the canonical workspace path above. Some MCP sessions may report the global path
at runtime, but both paths access the same physical database.

**Note:** As of May 2026, this memory has been expanded to include the **full
historical mail archive** (6,500+ entries) from all VDP projects. Agents can search
for the "human why" and coordination history behind past decisions using the
`mail` and `historical` tags.

Authority order remains:

1. `mcp-agent-mail`
2. repo task/state docs
3. shared `memory` cache

Standing ownership:

- `CoralReef` owns the initial curated memory pass for `spinalhdlVDP`
- `CyanPeak` owns ongoing memory updates for audits, important bug fixes,
  proven hardware findings, and reusable Tang/Gowin constraints
- `CyanPeak` must also keep shared memory current for new authoritative mail,
  committed repo/doc changes, and important file/state deltas that are likely
  to matter on resume or future audit; store only short tagged summaries with
  commit/mail tie-back, not raw logs or full mail copies

Use `memory` proactively as a research-support resource.

Expected workflow for research, audit, and planning work:

1. check `memory` first for concise prior findings
2. use mail and repo docs as the authority
3. add back only short, reusable findings worth querying later

Typical good uses:

- recalling Tang/Gowin gotchas before a new hardware-debug branch
- recalling prior audit conclusions before restating architecture decisions
- recalling root-cause/fix pairs before repeating failed experiments
- recalling validation patterns and proven constraints before opening a new task

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

Notes:

- `metals-lsp` requires the `metals` binary installed via Coursier at `~/.local/share/coursier/bin/metals`.
- `pywellen` is installed from source in `/home/itadmin/mcp-servers/pywellen-mcp` (venv).
- Workspace-scoped LSP servers (`metals-lsp`, `verible-lsp`, `clangd-lsp`) point to this repository.
- Most servers are wrapped through `/home/itadmin/mcp-servers/mcp-cache/dist/index.js` for stdio caching.

## PDF Rule

`pdf-reader` is available for this repository and should be the default tool
for reading datasheets, manuals, app notes, and other PDF sources relevant to
`spinalhdlVDP`.

Working rule:

- use `pdf-reader` to inspect or query actual PDF contents
- use `memory` only for short distilled findings worth recalling later
- do not treat `memory` as a full-document store for PDFs by default

Typical use:

1. read or query the PDF with `pdf-reader`
2. extract the small number of reusable findings that matter
3. store only those concise findings in `memory` with strong tags if they are
   likely to help future work

Memory entries for this repo must be concise and strongly tagged.

Minimum tag:

- `vdp`

Add as appropriate:

- `resume`
- `planning`
- `audit`
- `gotcha`
- `hardware`
- `tang20k`
- `gowin`
- task/lane tags such as `task-15`, `r1`, `r2`, `roadmap`

Do not dump raw mail or long project logs into memory. Store short,
query-friendly summaries that point back to the authoritative mail/doc state.

## Preventive Rules

The following rules exist to prevent the identity, authorization, and contract
drift that occurred during the Task 56 → FoggyWolf onboarding transition.
These rules are mirrored from the workspace-authoritative `AGENTS.md` and
are equally binding inside this repo.

### 1. Role Transfer Rule

No agent may self-declare absorption, consolidation, or transfer of another
agent's role. Role changes require BronzeGate PM authorization in explicit
mail, a transition mail CC'd to all affected agents, and update to the
workspace `AGENTS.md` before the change takes effect.

### 2. Audit Singleton Rule

Only CyanPeak may issue authoritative PASS / HOLD / FAIL audit rulings.
If audit ownership ever transfers, the outgoing owner must explicitly confirm
retirement in mail before the incoming owner issues rulings.

### 3. Commit-Within-Cycle Rule

All work that has received audit PASS must be committed to git before the next
PM review cycle or lane authorization. The audit owner may withhold PASS until
the commit hash is included in the proof packet.

### 4. Contract Deviation Documentation Rule

Any implementation that deviates from a locked hardware contract by more than
25 % must be documented in the relevant `GOTCHAS.md` with quantitative
analysis and evidence that the hardware state machine tolerates it.

The canonical QSPI contract is locked at: 2 MHz SCK, 10 µs CS hold, 20 µs OSR
drain.

### 5. Signoff Consistency Rule

Each agent has one canonical signoff string. Do not use mixed aliases or
alternate signatures mid-thread.

- FoggyWolf signs as `— FoggyWolf`
- CyanPeak signs as `— CyanPeak`
- CoralReef signs as `— CoralReef`
- BrightForge signs as `— BrightForge`
- BronzeGate signs as `— BronzeGate`

### 6. Identity Retirement Rule

Retiring an agent identity requires explicit retirement mail, removal from all
`AGENTS.md` rosters, confirmation that no pending audit rulings remain, and a
24-hour observation window during which the retired identity must not send mail.

An identity missing from the roster but still sending mail is not retired;
it is a roster bug that must be fixed immediately.

### 7. Side-Lane Authorization Rule

Parallel work (e.g., firmware parity, documentation restructure) requires
BronzeGate lane-open authorization before implementation starts. Post-hoc proof
packets are accepted only for bounded reconciliation, not as blanket
authorization for future lanes.

### 8. AGENTS.md Immutability Rule

`AGENTS.md` files contain binding project policy. No agent may unilaterally
rewrite, truncate, remove, or materially alter rules in any `AGENTS.md` without:

- BronzeGate PM authorization, AND
- CyanPeak audit review, AND
- A diff review showing exactly what changed and why

Cosmetic edits (spelling, formatting) are allowed. Removing rules, adding
self-serving exceptions, or truncating sections to strip policy you disagree
with is **not** allowed and will be treated as a roster violation.

If you believe a rule is wrong, escalate to BronzeGate with a specific
amendment proposal. Do not edit the file directly.
