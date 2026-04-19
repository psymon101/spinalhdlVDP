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

## Execution Workflow

This repo runs under a compact, event-driven coordination policy. Preserve
momentum, but do not spend tokens reconstructing state that is already
available in mail and the live-lane ledger.

### Source of truth order

Use this order every time:

1. latest authoritative mail packet for the active lane
2. `PROJECT_PLAN/TASKS.md` live-lane block
3. current repo state / commit under discussion

Do not restate older lane history in routine messages unless the current
decision depends on it.

### Standing role split

- `BrightForge`: implementation, validation, proof packets
- `CyanPeak`: audit outcomes and explicit sign-off
- `CoralReef`: routine coordination, hardware support, ledger/doc sync
- `BronzeGate`: sequencing, stall intervention, scope control

Routine lane mechanics stay with `CoralReef`.
`BronzeGate` steps in only for drift, ambiguity, stalls, lane transitions,
blocker decisions, or priority changes.

### Mutual Coverage Check

`CoralReef` and `CyanPeak` must explicitly cross-check each other's coverage so
planning, ledger, and validation gaps do not slip through.

- `CoralReef` must flag omissions in `CyanPeak`'s audit coverage, including:
  - missing follow-on tasks or missing scope coverage
  - stale ledger state that was not called out
  - planning decomposition gaps that affect execution coverage
- `CyanPeak` must flag omissions in `CoralReef`'s planning / ledger work,
  including:
  - missing validation gates or proof requirements
  - incomplete task decomposition
  - stale or internally inconsistent task state

Neither lane should assume the other already caught everything. If either sees
a coverage gap, it must be called out explicitly in mail.

### Live-Lane Hygiene

When the active lane changes materially, update the `TASKS.md` live-lane block
in the same change or immediately after with:

- latest commit
- latest authoritative mail id
- current phase
- next deliverable

Do not let the team reconstruct active state from scattered mail if the ledger
can be updated directly.

### Event-Driven Progression

For routine bounded lanes, progress automatically by role without extra PM
nudges once the next owner is obvious.

Default handoff chain:

- `BrightForge` completion / proof / blocker / corrected-evidence packet
  triggers `CyanPeak`
- `CyanPeak` audit ruling triggers `CoralReef`
- `CoralReef` ledger sync or closeout on a converged lane opens the next
  obvious artifact automatically unless reassessment changed the order

For low-risk bounded lanes:

- `CoralReef` lands artifact + live-lane block
- `CyanPeak` audits as soon as the artifact lands
- `BrightForge` starts immediately after audit GO
- no extra PM message is required between those routine steps

Ledger sync is part of closeout, not a later cleanup step:

- audit PASS should be followed immediately by `TASKS.md` / live-lane sync
- a lane is not functionally closed until repo state matches authoritative mail

If task order is already converged, the next listed lane is the default next
step unless:

- post-completion reassessment changes the plan
- audit finds a real HOLD
- a hardware blocker appears
- `BronzeGate` explicitly overrides priority

If a lane closes cleanly and the next lane is already converged, unblocked, and
unchanged by reassessment, `CoralReef` should open the next artifact in the
same progression cycle. Do not leave the repo in an idle "awaiting PM
direction" state when the next lane is obvious.

If there is uncertainty about whether reassessment changed the next lane, stop
and ask in mail instead of guessing.

### Post-Completion Reassessment

Every completed task must trigger an explicit reassessment of the task list and
the project plan before the team simply moves on.

Required post-closeout checks:

- `BrightForge` must state whether the completed implementation exposed missing
  engineering slices, hidden limitations, or new follow-on work.
- `CyanPeak` must state whether the completed audit exposed missing validation
  gates, proof gaps, or plan changes that should now be reflected.
- `CoralReef` must update `TASKS.md` and planning docs if the completed task
  changed what the next plan should be.
- `BronzeGate` uses those inputs to decide whether the next lane stays the same
  or the plan needs adjustment.

No task closeout should be treated as purely local. If a completed task reveals
an unplanned dependency, missing task, changed ordering, or a reason to split a
coarse item into concrete tasks, the docs must be updated explicitly instead of
leaving the discovery only in mail history.

### Active-lane execution rule

Once a bounded lane is approved:

- `BrightForge` should proceed directly until a real blocker, completion
  packet, or proof packet exists
- `CyanPeak` should audit completion packets automatically unless scope is
  genuinely unclear
- `CoralReef` should keep the live-lane block and artifact docs current

### Delta-Only Messaging

Allowed default message types:

- assignment / approval
- blocker or changed diagnosis
- completion / proof packet
- audit result
- closeout
- ACK when direct receipt is actually needed

Default rule:

- if authoritative state did not change, do not send a recap of stable
  background
- if authoritative state changed, report only the delta plus the next owner
  unless a blocker or plan change requires more context
- do not reconstruct full lane history in routine status mail when the
  live-lane block and latest mail already preserve it

Routine ACK-only or no-change mail should be minimized. Use it only when:

- blocker receipt needs explicit confirmation
- assignment is ambiguous or risky
- hardware-facing action needs explicit receipt confirmation
- a standing rule explicitly requires a receipt

If a check finds no change and no direct task, remain silent by default.

### Structured Packet Templates

Routine mail should be compact and structured by packet type.

`BrightForge` default completion / proof packet:

- task / checkpoint
- commit
- exact files or subsystem touched
- simulation or build result
- hardware-proof result when applicable
- blocker / none
- next expected owner

`CyanPeak` default audit packet:

- ruling: `PASS`, `HOLD`, or `FAIL`
- exact reason
- exact corrective requirement when not `PASS`
- next expected owner

`CoralReef` default artifact / ledger packet:

- task / phase
- commit
- exact doc or ledger state change
- next deliverable
- next expected owner

`BronzeGate` default PM packet:

- decision or state delta only
- exact owner change
- exact next step
- why only when ambiguity or priority changed

If those fields fit in a short message, do not add extra recap.

### Audit Optimization

`CyanPeak` should optimize audits for speed without lowering evidence quality.

Default audit behavior:

- audit the newest authoritative packet only; superseded packets should not be
  re-audited unless a new packet explicitly depends on them
- use the smallest sufficient ruling by default:
  - `PASS`
  - `HOLD`
  - `FAIL`
- keep routine audit mail compact:
  - ruling
  - exact reason
  - exact next corrective requirement when not PASS

Required immediate HOLD conditions:

- required proof method is missing
- required proof duration/window is missing
- commit / programmed state tie-back is missing
- packet evidence is visibly incomplete or stale

Do not spend audit cycles trying to salvage incomplete proof. Reject quickly
and request the exact missing evidence.

Preferred audit checklists by packet type:

- artifact audit:
  - dependencies correct
  - scope bounded
  - validation plan explicit
  - no hidden scope creep
- implementation audit:
  - diff matches approved artifact
  - simulation evidence present
  - no regression claim without evidence
- hardware-proof audit:
  - exact capture duration stated
  - exact analysis method stated
  - exact pass/fail metric stated
  - current commit / programmed state tied to the evidence

When a blocker packet presents multiple hypotheses, prefer the cheapest
discriminating next experiment unless a larger step is clearly required.

### Coordination Optimization

`CoralReef` should optimize artifact / ledger flow for low-latency progression.

Default coordination behavior:

- artifact drafting should start immediately once dependencies and ownership are
  clear; do not wait for another PM nudge when the next step is already obvious
- ledger sync should happen immediately after audit PASS unless BronzeGate has
  explicitly ordered a temporary hold
- live-lane metadata should always reflect the newest authoritative state once
  a lane has materially advanced

Preferred default outputs:

- artifact packet
- ledger sync / live-lane update
- closeout sync

Avoid routine ACK-only mail when no authoritative state changed.

When the next lane is obvious and unblocked:

- auto-open the next artifact in the same progression cycle
- include the latest commit, latest authoritative mail, phase, and next
  deliverable in the same repo/docs sync

When the next lane is not obvious:

- stop and ask in mail with the minimum concrete ambiguity stated

### Context Compression

When resuming or handing off, compress state to:

- task / checkpoint
- latest commit
- latest authoritative mail
- blocker or next allowed step

Do not carry full conversational history forward when those four facts are
sufficient.

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
