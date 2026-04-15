# AGENTS.md — spinalhdlVDP

Repo-specific rules for `/home/itadmin/github/spinalhdlVDP`.

The workspace file at `/home/itadmin/github/AGENTS.md` remains authoritative
for canonical identity, roster, and cross-project coordination rules.

## Identity

Use the workspace canonical names:

- `BronzeGate` for Codex
- `BrightForge` for Claude
- `CoralReef` for Kimi
- `CyanPeak` for Gemini

Do not invent alternate names in this repository.

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

Example for Gemini:

```json
{
  "project_key": "/home/itadmin/github/spinalhdlVDP",
  "program": "gemini-cli",
  "model": "<actual model>",
  "name": "CyanPeak",
  "task_description": "spinalhdlVDP review/support"
}
```

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

- `PROJECT_PLAN/MODE0_ROADMAP.md` for roadmap-driven work
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

## Memory Curation Rule

This repo uses the shared workspace `memory` MCP as a queryable cache, not as
the authoritative project log.

Authority order remains:

1. `mcp-agent-mail`
2. repo task/state docs
3. shared `memory` cache

Standing ownership:

- `CoralReef` owns the initial curated memory pass for `spinalhdlVDP`
- `CyanPeak` owns ongoing memory updates for audits, important bug fixes,
  proven hardware findings, and reusable Tang/Gowin constraints

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
