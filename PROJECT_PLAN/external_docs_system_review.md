# External Documentation System Review

**Reviewer:** CoralReef  
**Review date:** 2026-07-26  
**Source:** `docs/external_documentation_system/` (140 files, generated 2026-07-26, manifest active_lane=`FOUNDATION-0`)  
**Existing authorities compared:** `PROJECT_PLAN/STATUS.md`, `PROJECT_PLAN/PROJECT_PLAN.md`, `EDUCATION/00_INDEX.md`, `AGENTS.md`.

## Scope

Assess whether the external reviewer's 140-file documentation system should:

1. replace `STATUS.md`/existing docs as the new canonical project control; or
2. be merged selectively into the existing structure; or
3. be kept as a reference snapshot while maintaining our own docs.

## Executive summary

The external documentation system is **well-structured but greenfield**. It describes an idealized, multi-platform retro-graphics project that does not match the current repo state. **Do not adopt it as canonical project control.** Adopting it would replace the live lane ledger with a `FOUNDATION-0` baseline-capture exercise and would require halting active engineering lanes.

**Recommendation:** merge selected high-value templates and ADRs into the existing structure, and keep the remainder as a clearly marked reference snapshot.

## What the external system gets right

| Area | Assessment |
|---|---|
| Document-ownership table | Aligns well with `AGENTS.md` authority rules. Anti-drift rule matches Preventive Rule #12/#14 intent. |
| ADRs | ADR-001..006 encode existing project rules (SpinalHDL source of truth, host-independent `libvdp`, video-only emulation, platform adapter model, no AGA, one active shared RTL lane). Most are already de-facto policy. |
| Templates | Task, proof-packet, ADR, and release-manifest templates are usable once populated. |
| Runbooks / testing / reproducibility | Good skeleton structure, but most content is placeholder/TBD until Foundation 0 locks versions. |
| Source-of-truth policy | Reasonable authority order, though it omits the role of authoritative mail and `STATUS.md`. |

## Conflicts and misrepresentations of current state

### 1. Live-state authority conflict

The external system names `PROJECT_PLAN/ACTIVE_LANE.md` as the project-state authority. The repo's current `AGENTS.md` and `PROJECT_PLAN/STATUS.md` name `STATUS.md` as the live-state authority.

- External: `README.md` line 38, `DOCUMENT_OWNERSHIP.md`.
- Existing: `AGENTS.md` "Live Status Authority" / Source-of-truth order; `PROJECT_PLAN/PROJECT_PLAN.md` lines 8, 27.

Adopting the external system would silently move authority to a file that currently declares `FOUNDATION-0` as active and has no awareness of the work below.

### 2. Active lane mismatch

External `ACTIVE_LANE.md` declares the active lane is `FOUNDATION-0 — Baseline and Contract Reconciliation` in state `RESEARCH / BASELINE CAPTURE`.

Actual active lanes in `PROJECT_PLAN/STATUS.md` (as of 2026-07-26):

- `agent-rule-alignment` (RUNNING)
- `2bpp-backlog-cosim` (RUNNING)
- `2bpp-hardware-reproof-4mhz` (BLOCKED)
- `external-review-tierB-measure` (OPEN)
- `external-review-scaler-rewrite` (OPEN)
- `external-review-tile-pipeline` (OPEN)
- plus the current review lane itself.

The external plan has no acknowledgement of these lanes.

### 3. Baseline not captured

`CURRENT_BASELINE.md` is entirely `TBD-FOUNDATION-0`. In contrast, `PROJECT_PLAN/STATUS.md` already contains extensive locked baseline evidence: bitstream SHA-256 values, ELF/BIN/partition hashes, Gowin timing slack, BSRAM/LUT/CLS utilization, host transport rate (4 MHz canonical), and hardware proof hashes. A baseline capture lane is therefore a backfill exercise, not a starting point.

### 4. Scope / roadmap mismatch

The external dependency graph proposes an 11-platform sequence (Generic Mode0 → ZX Spectrum → TMS9918A → SMS/GG → NES → C64 → Atari ST → Amiga → Genesis → SNES → Atari 2600). The current project reality is a Tang Nano 20K Mode0 substrate with a focused 2bpp indexed proof path. The platform roadmap has not been approved and is not resourced.

### 5. No acknowledgement of recently closed or active technical lanes

Searching the external docs found no references to:

- `QSPI-CRC8-185`
- `HAM6 removal + 2bpp indexed replacement`
- `PIXELWITHINBYTE-ALIGN`
- `BITMAP-CDC-SHIMMER-FIX`
- `external_static_review_2026-07-25.md` / Tier A/B/C findings
- `kb/reviews/spinalhdlvdp_all_*_2026-07-25.md`

This makes the external system unsafe as a live authority: it would lose the project's recent engineering record.

### 6. Duplication of existing reconciled docs

`EDUCATION/` was reconciled by CyanPeak in commit `b5e7dd5` (2026-07-25) and is marked Complete. The external system's architecture, FPGA, firmware, and platform docs cover the same material with a different structure. Keeping both as canonical would create the exact duplication the external system's own anti-drift rule warns against.

### 7. Placeholder content

Many runbooks and `CURRENT_BASELINE.md` fields are explicitly `TBD-FOUNDATION-0`. They cannot be used as canonical procedures until Foundation 0 closes, which contradicts the claim that this system is "repository-ready."

## Recommendation: selective merge + reference snapshot

### Adopt into existing structure (with light editing)

| External artifact | Proposed action | Rationale |
|---|---|---|
| `PROJECT_PLAN/TASKS/TASK_TEMPLATE.md` | Adopt to `PROJECT_PLAN/TASK_TEMPLATE.md` or alongside existing template | Fills a gap; existing `PROJECT_PLAN/TASK_TEMPLATE.md` may be stale. |
| `PROJECT_PLAN/proof_packets/PROOF_PACKET_TEMPLATE.md` | Adopt to `PROJECT_PLAN/proof_packets/` | Proof-packet structure is useful and currently informal. |
| `PROJECT_PLAN/DECISIONS/ADR_TEMPLATE.md` and ADR-001..006 | Review each ADR for conflicts, then adopt to `PROJECT_PLAN/DECISIONS/` or `docs/architecture/` | Most ADRs are already de-facto policy; formalizing them is valuable. |
| `RELEASE_MANIFEST_TEMPLATE.yaml` | Adopt to project root or `PROJECT_PLAN/` | Release manifest is currently ad-hoc. |
| `PROJECT_PLAN/DOCUMENT_OWNERSHIP.md` anti-drift rule | Merge concept into `AGENTS.md` or `PROJECT_PLAN/CONVENTIONS.md` | Already aligned with AGENTS.md rules. |

### Keep as reference snapshot only

| External artifact | Proposed action | Rationale |
|---|---|---|
| `PROJECT_PLAN/ACTIVE_LANE.md` | Do not replace `STATUS.md`. Mark as reference. | Contradicts live state. |
| `PROJECT_PLAN/MASTER_EXECUTION_PLAN.md` | Keep as reference roadmap only. | 11-platform sequence not approved. |
| `PROJECT_PLAN/CURRENT_BASELINE.md` | Keep as reference template only. | Actual baseline lives in `STATUS.md`. |
| `PROJECT_PLAN/PLATFORM_STATUS.md` | Keep as reference only. | Not synchronized with actual platform work. |
| `docs/platforms/*` | Keep as reference snapshots. | Platform adapter planning may be useful later, but none are active lanes. |
| `docs/architecture/*`, `docs/fpga/*`, `docs/firmware/*` | Keep as reference; do not replace `EDUCATION/` or existing `kb/`/`docs/` without a separate reconciliation lane. | Substantial overlap with CyanPeak's recently reconciled `EDUCATION/` docs. |
| `docs/runbooks/*` | Keep as templates; do not replace actual build/run instructions until populated with verified commands. | Currently TBD placeholders. |

### Structural proposal

Option A (minimal disruption):

1. Add a header to `docs/external_documentation_system/README.md` stating it is a **reference snapshot under review**, not canonical live state.
2. Create/adopt the selected templates in `PROJECT_PLAN/`.
3. Leave the 140-file tree in place but explicitly demote it from canonical.

Option B (cleaner):

1. Move `docs/external_documentation_system/` to `docs/external_documentation_system/reference-2026-07-26/`.
2. Adopt selected templates into `PROJECT_PLAN/` and root.

Option B is cleaner but requires moving many files; Option A is lower risk. Either requires PM authorization.

## Required PM decisions

1. **Authority:** Confirm `STATUS.md` remains the live-state authority and `ACTIVE_LANE.md` is reference-only.
2. **Adoption scope:** Approve the selective-merge list above, or modify it.
3. **Directory disposition:** Choose Option A (mark in place) or Option B (move to reference directory).
4. **Foundation-0:** Decide whether to open a `FOUNDATION-0` baseline-capture lane and how it relates to the active `2bpp-*` lanes, or keep the external Foundation plan as a future roadmap only.

## Compliance note

Per `AGENTS.md` Preventive Rule #12, any change to live-state authority must be synchronized into `STATUS.md` during the same engineering cycle. Adopting the external system's `ACTIVE_LANE.md`/`MASTER_EXECUTION_PLAN.md` without such synchronization would violate the rule.

---

**Conclusion:** The external documentation system is a valuable reference and template package, but it is not a drop-in replacement for the project's current live documentation. Selective adoption of templates and ADRs, plus clear demotion of the project-control files to reference status, is the safest path.
