<!-- ARCHIVED: detailed workflow rules extracted from AGENTS.md 2026-05-07 -->
<!-- These rules remain valid but are no longer in the mandatory reading path. -->
<!-- Read AGENTS.md for the concise current version; refer here for packet templates, audit checklists, and escalation policy. -->

## Execution Workflow

This repo runs under a compact, event-driven coordination policy. Preserve
momentum, but do not spend tokens reconstructing state that is already
available in mail and the live-lane ledger.

Fast-flow objective:

- optimize for shortest trustworthy cycle time, not maximum packet volume
- prefer fewer open lanes, smaller proof-sized batches, and earlier
  discriminators over broad parallel churn

Hard WIP rule:

- keep at most **1 active engineering lane** on the critical path at a time
- allow at most **1 sidecar research / preflight lane** in parallel when it
  does not block the active engineering step
- do not open a second implementation lane while the current one still lacks a
  clear next owner

### Source of truth order

Use this order every time:

1. latest authoritative mail packet for the active lane
2. `PROJECT_PLAN/TASKS.md` live-lane block
3. current repo state / commit under discussion

### Deliverable verification

A claimed lane deliverable is not received until it is mailbox-visible and
matches the required owner and packet type.

- required packet types:
  - `planning`
  - `completion`
  - `audit`
  - `blocker`
  - `ETA`
- if an agent claims "I sent it", they must provide:
  - exact message id
  - exact subject
  - exact project key
  - exact recipient list
- a visible message from the wrong owner or with the wrong packet type does
  not satisfy the missing deliverable
- if the message cannot be verified in the mailbox, require resend
- after repeated non-response, `BronzeGate` may reassign the lane or authorize
  a bounded fallback

Do not restate older lane history in routine messages unless the current
decision depends on it.

### Standing role split

- `BrightForge`: implementation, validation, proof packets
- `CyanPeak`: audit outcomes, explicit sign-off, and ongoing shared-memory
  updates for authoritative mail/file/state changes worth durable recall
- `CoralReef`: routine coordination, hardware support, ledger/doc sync, and preflight research for upcoming lanes
- `BronzeGate`: sequencing, stall intervention, scope control

Routine lane mechanics stay with `CoralReef`.
Operational routing rule:

- `CyanPeak` should receive bounded audit and diagnosis asks by default
- `CoralReef` should receive broad exploratory research, repo-wide evidence
  gathering, and first-pass blocker decomposition by default
- when the work can be split into “explore broadly” then “judge narrowly,”
  split it that way instead of asking `CyanPeak` to do both

`BronzeGate` steps in only for drift, ambiguity, stalls, lane transitions,
blocker decisions, or priority changes.
If the next owner is obvious and standing policy already covers the handoff,
`BronzeGate` should stay silent by default.

### Preflight Requirements Research

`CoralReef` is the default owner for forward-looking requirement work on likely
next tasks or proposed features while the current implementation/audit lane is
still active.

Expected preflight coverage:

- what existing primitives or prior tasks the feature depends on
- what new primitives, interfaces, or assets would be required
- what board, timing, SDRAM, bandwidth, or controller limits matter
- what proof would be needed to claim success cleanly the first time
- what follow-on task split is recommended before coding starts

This research lane should reduce first-pass surprises for implementation, but
it does not itself authorize coding or bypass the normal mail + `TASKS.md`
lane activation rules.

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

Live-lane freshness rule:

- `Latest Commit` and `Latest Auth Mail` must point to the newest authoritative
  packet for the active lane
- do not leave the live-lane block one packet behind after artifact delivery,
  audit result, implementation proof, or closeout
- if a lane advanced and the next owner is clear, the responsible owner should
  correct the ledger directly instead of waiting for a PM reminder

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

Small-batch rule:

- prefer the smallest batch that can still produce one clean proof packet
- if two changes do not share the same proof boundary, do not batch them
- if a blocker can be separated by one cheap discriminator, run that before
  planning a broader refactor

Artifact fast-path rule:

- for a routine bounded lane, `CoralReef` should land one compact artifact
  packet that already includes:
  - exact scope boundary
  - dependency statement
  - exact validation/proof requirement
  - recommended next owner
- `CyanPeak` should answer with one compact ruling:
  - `PASS`
  - or `HOLD` with the exact missing requirement
- default `CyanPeak` to bounded packet work:
  one compact ruling, one exact evidence request, or one diagnosis packet
  against one blocker
- do not route broad open-ended lane exploration to `CyanPeak` when
  `CoralReef` can first gather and compress the evidence set
- if the ruling is `PASS` and the next owner is `BrightForge`, coding starts
  immediately with no second authorization round
- if the ruling is `HOLD`, name the single cheapest missing correction or
  clarification instead of reopening broad planning by default
- if an approved assessment packet names a bounded implementation follow-on
  with clear scope and next owner `BrightForge`, treat that follow-on as the
  next active coding slice by default unless `CyanPeak` or `BronzeGate`
  explicitly places it on `HOLD`

Done-shape rule:

- every artifact packet must define the exact exit proof before coding starts
- every proof packet must state the exact claim being proven
- if the claim and retained evidence do not line up directly, the packet is not
  done and must stay open as a blocker

Proof-packet rule:

- `BrightForge` should prefer one complete proof packet over multiple partial
  follow-ups
- the default proof packet should already include:
  - exact task/checkpoint
  - commit
  - files/subsystems touched
  - simulation result
  - hardware result when applicable
  - next expected owner
- if evidence is incomplete, do not send a placeholder completion mail unless
  the lane is actually blocked and the missing proof cannot be produced yet

Start-immediately rule:

- after artifact audit PASS, `BrightForge` should start coding immediately when
  the artifact packet and live-lane block authorize implementation
- `BrightForge` should not wait for a second BronzeGate mail unless the audit
  packet or ledger sync states a HOLD, ambiguity, or changed priority
- after implementation audit PASS, `CoralReef` should sync closeout and open
  the next obvious lane immediately without waiting for PM confirmation

Ledger sync is part of closeout, not a later cleanup step:

- audit PASS should be followed immediately by `TASKS.md` / live-lane sync
- a lane is not functionally closed until repo state matches authoritative mail

### In-Lane Continuation

Once a lane is approved, clearly in-scope sub-slices are auto-approved by
default.

Working rule:

- `BrightForge` should continue through obvious in-scope sub-slices without
  waiting for a fresh BronzeGate packet every time
- `CyanPeak` audits completed checkpoints and evidence, rather than gating each
  micro-step in advance
- `CoralReef` supports with research, hardware/ledger sync, and blocker
  assistance, but should not become a routine blocker on the coding critical
  path
- when the work naturally splits into broad exploration plus bounded judgment,
  `CoralReef` should own the exploration and `CyanPeak` should own the final
  judgment
- `BronzeGate` is only reintroduced for scope changes, priority changes,
  hardware-risk pivots, contradictory evidence, or genuine ambiguity

Examples:

- if an approved lane contains bounded follow-on sub-slices such as `H-1`,
  `H-2`, `H-3`, those are auto-approved continuations unless the lane boundary
  is crossed
- after one bounded sub-slice completes, if the next sub-slice is obvious and
  still in scope, continue immediately

Do not pause a lane for a fresh PM packet between routine in-scope
continuations.

### Batched Workflow Cycle

Batching is preferred when the work shares the same approved lane and the same
proof boundary.

Working rule:

- `BrightForge`, `CyanPeak`, and `CoralReef` may batch multiple tightly related
  sub-slices into one workflow cycle when those sub-slices:
  - are in scope for the same lane
  - do not require separate ownership decisions
  - can still be audited and proven cleanly as one bounded result
- do not batch across different lanes
- do not batch across proof boundaries where a failure in one step would
  invalidate the others or make audit ambiguous

### Coding-Blocker Escalation

`BrightForge` is the default coding owner and is expected to work through
routine bugs or unexpected results before escalating.

Default escalation policy:

- for a locally tractable bug or unexpected result, `BrightForge` should make
  up to **3 serious fix attempts / reasoning passes**
- if it becomes obvious earlier that the issue is not locally tractable, stop
  early and ask for help instead of spending attempts mechanically
- after those attempts, or earlier when clearly justified, `CyanPeak` should be
  pulled in first to help reason through the problem
- if `BrightForge` and `CyanPeak` still cannot resolve the issue, escalate to
  the rest of the group with the relevant evidence and blocker summary

Escalation packets should include:

- what was attempted
- what evidence was gathered
- why the issue still appears blocked or ambiguous

Do not escalate on the first ordinary bug. Do not spin indefinitely once local
attempts are exhausted.
- post-audit ledger sync is owned work, not optional cleanup

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

### Stall Intervention Threshold

Use a short stall threshold so routine lanes do not sit idle waiting for manual
nudges.

- if the expected next owner has not acted within 15 minutes of a clear
  authoritative handoff during an active work session, `BronzeGate` should
  intervene
- before that threshold, prefer silence over reminder mail unless there is a
  blocker, ambiguity, or explicit user priority change
- when intervening, state the exact missing handoff and exact next owner rather
  than reconstructing full history

Escalation priority:

- first ask: who is the exact next owner?
- second ask: what is the cheapest discriminator?
- third ask: what proof boundary lets the lane move again?
- do not broaden scope before those three are answered

Workflow metrics rule:

- optimize the workflow against these recurring measures:
  - handoff latency
  - blocker-to-discriminator time
  - audit turnaround time
  - proof reopen rate
  - duplicate / no-change packet rate
- if a lane reopens because the evidence shape was wrong, treat that as a
  workflow defect and tighten the rule that allowed it

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

Direct owner-to-owner retrieval rule:

- if a teammate needs routine facts, evidence, proof artifacts, or task-state
  clarification from another teammate, ask that owner directly by default
- do not route ordinary information-fetch requests through `BronzeGate` when
  the requesting owner can obtain the answer directly in one step
- reintroduce `BronzeGate` only when the issue affects priority, scope,
  approval, lane ownership, or blocker resolution, or when direct retrieval
  produced contradictory/ambiguous results

Routine ACK-only or no-change mail should be minimized. Use it only when:

- blocker receipt needs explicit confirmation
- assignment is ambiguous or risky
- hardware-facing action needs explicit receipt confirmation
- a standing rule explicitly requires a receipt

If a check finds no change and no direct task, remain silent by default.

Routine ACK suppression:

- do not send ACK-only mail for unchanged “standing by” posture
- send ACK only when receipt is explicitly required, the action is risky, or a
  blocker needs confirmation

Duplicate suppression rule:

- send one authoritative packet per state change
- only resend a packet when delivery or persistence actually failed, and mark
  it explicitly as a retry
- do not generate repeated materially identical proof, audit, or ledger packets
  for visibility alone
- when duplicate packets exist, the newest materially identical packet
  supersedes the older copies and should not trigger extra audit or ledger work

### Structured Packet Templates

Routine mail should be compact and structured by packet type.

`BrightForge` default completion / proof packet:

- task / checkpoint
- commit
- exact files or subsystem touched
- simulation or build result
- hardware-proof result when applicable
- claim under test when hardware proof is involved
- exact scene or workload shown when hardware proof is involved
- why the retained artifact proves that claim, not just general liveness
- explicit statement that `BrightForge` personally reviewed the retained
  capture or still frames and that the visible result matches the claimed
  feature, or else a blocker stating the mismatch
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
- keep `CyanPeak` assignments narrow by default:
  one artifact, one proof packet, or one explicitly scoped blocker packet at a
  time
- avoid giving `CyanPeak` broad “think through the whole lane” work when
  `CoralReef` can first assemble the evidence and candidate paths
- keep routine audit mail compact:
  - ruling
  - exact reason
  - exact next corrective requirement when not PASS
- when issuing `HOLD`, request one smallest sufficient correction first unless
  multiple missing items are inseparable

Evidence retrieval rule:

- `CyanPeak` is allowed and expected to ask directly for missing evidence from
  the teammate most likely to provide it when the gap appears locally
  recoverable
- ask `BrightForge` directly for reruns, proof packets, exact test method,
  commit tie-back, or missing artifacts when implementation evidence is the gap
- ask `CoralReef` directly for missing ledger/artifact mapping when the scope
  or task-state tie-back is the gap
- prefer this direct evidence retrieval path before escalating a broader
  blocker to BronzeGate when the lane remains otherwise clear
- if the evidence remains unavailable, contradictory, or still scope-ambiguous
  after that direct ask, escalate with a blocker packet

Required immediate HOLD conditions:

- required proof method is missing
- required proof duration/window is missing
- commit / programmed state tie-back is missing
- packet evidence is visibly incomplete or stale

Audit latency rule:

- for an active lane with a clear artifact or proof packet, `CyanPeak` should
  either issue `PASS/HOLD/FAIL` promptly or ask directly for one exact missing
  item
- do not let a lane stall because an audit question exists only implicitly

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
  - claimed feature is explicitly stated
  - visible or direct observable content actually matches the claimed feature
  - proof is rejected as insufficient if it shows only general stable output
    while claiming a feature-specific result

Hardware-proof claim rule:

- a hardware-proof packet must state the exact feature claim under test
- it must state what is visibly on screen or otherwise directly observable
- it must explain why that scene or observable proves the claimed feature
- `BrightForge` must inspect the retained capture or representative stills
  directly before calling the packet done; analyzer output alone is not
  sufficient
- if the visible result does not clearly correspond to the claimed feature or
  code change, do not call the work done; treat it as an open blocker and
  investigate the mismatch
- generic “live output” or “stable video” evidence is not sufficient when the
  claim is about a specific feature such as sprite count, priority, collision,
  or mode behavior
- if the packet does not visibly or directly exercise the claimed feature, it
  should be treated as a stability proof only, not feature proof

When a blocker packet presents multiple hypotheses, prefer the cheapest
discriminating next experiment unless a larger step is clearly required.

### Coordination Optimization

`CoralReef` should optimize artifact / ledger flow for low-latency progression.

Default coordination behavior:

- artifact drafting should start immediately once dependencies and ownership are
  clear; do not wait for another PM nudge when the next step is already obvious
- when the next backlog-priority lane is already known, draft the next artifact
  before the previous lane is formally closed so it can be finalized in the same
  progression cycle once closeout converges
- ledger sync should happen immediately after audit PASS unless BronzeGate has
  explicitly ordered a temporary hold
- when practical, land the repo doc/ledger update in the same commit as the
  authoritative state change instead of leaving a separate trailing sync step
- live-lane metadata should always reflect the newest authoritative state once
  a lane has materially advanced
- when a lane closes and the next lane is obvious, combine closeout sync and
  next-lane open in the same progression cycle rather than parking in an
  intermediate waiting state

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

Bench-gate split rule:

- when code, sim, and synthesis are complete but bench access is the only
  remaining dependency, split status explicitly into:
  - engineering closed or passed
  - hardware proof pending
- do not reopen implementation work or keep the coding lane artificially open
  just because the bench step has not run yet
- treat the bench run as a final proof substep with its own audit and closeout
