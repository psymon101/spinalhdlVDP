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

Do not invent alternate names in this repository.

External-review exception:

- `TopazCliff` is allowed to send **outside review / advisory** messages into
  this project mailbox
- `TopazCliff` is not part of the canonical execution roster for
  `spinalhdlVDP`
- `TopazCliff` must not replace `CoralReef` for lane ownership, ledger sync,
  approvals, or canonical Kimi identity inside this repo
- all in-project Kimi workflow ownership here remains `CoralReef`

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
  - `BrightForge`: implementation, validation, proof
  - `CyanPeak`: audit, sign-off, memory curation
  - `CoralReef`: coordination, ledger/doc sync, preflight research
  - `BronzeGate`: sequencing, scope control, stall intervention
- Fast-flow: optimize for shortest trustworthy cycle time, smallest proof-sized batches, and earlier discriminators.
- Ledger sync is part of closeout, not cleanup.

For detailed packet templates, audit checklists, escalation policy, and coordination rules, see `PROJECT_PLAN/archive/AGENTS_WORKFLOW_RULES.md`.

## Source of Truth Order

Use this order every time:

1. latest authoritative mail packet for the active lane
2. `PROJECT_PLAN/TASKS.md` live-lane block
3. current repo state / commit under discussion

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
