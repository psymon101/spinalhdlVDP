# Project-System Migration Inventory

**Lane:** `PROJECT-SYSTEM-MIGRATION-001`  
**Owner:** `TopazCliff`  
**Captured:** 2026-07-26  
**Commit:** `095b65d`

## Classification key

- `CURRENT-AUTHORITY` — authoritative source of truth today.
- `CURRENT-SUMMARY` — current project documentation/summary.
- `REFERENCE` — useful background, not live authority.
- `SUPERSEDED` — replaced by another file; retained for history.
- `HISTORICAL` — archive material.
- `GENERATED` — build artifact; do not edit directly.
- `UNKNOWN` — purpose unclear; needs owner.

## Inventory

| Path | Current purpose | Proposed authority | Classification | Owner | Action | Link status |
|---|---|---|---|---|---|---|
| `PROJECT_PLAN/STATUS.md` | Live lanes, blockers, history, baseline | Live-state authority (unchanged) | CURRENT-AUTHORITY | TopazCliff | Keep; add migration lane updates | Self |
| `PROJECT_PLAN/PROJECT_PLAN.md` | Entry point / reading order | Project-control reference | CURRENT-SUMMARY | TopazCliff | Update navigation after cutover | Links OK |
| `PROJECT_PLAN/TASKS_HISTORY.md` | Historical task ledger | Historical reference | HISTORICAL | TopazCliff | Keep archived | N/A |
| `PROJECT_PLAN/TASK_TEMPLATE.md` | Task template | Reference / possible supersede | REFERENCE | TopazCliff | Compare with external template; adopt better one | N/A |
| `PROJECT_PLAN/external_docs_system_review.md` | CoralReef review of external docs | Decision input | CURRENT-SUMMARY | CoralReef | Keep; reference in migration closeout | N/A |
| `AGENTS.md` | Canonical identities and project rules | Agent-rule authority | CURRENT-AUTHORITY | TopazCliff | Update with migration rules | Links OK |
| `AGENTS/BrightForge.md` | FPGA role rules | Agent-rule authority | CURRENT-AUTHORITY | BrightForge | Update per migration plan | Links OK |
| `AGENTS/BronzeGate.md` | Firmware role rules | Agent-rule authority | CURRENT-AUTHORITY | BronzeGate | Update per migration plan | Links OK |
| `AGENTS/CyanPeak.md` | Spec-review role rules | Agent-rule authority | CURRENT-AUTHORITY | CyanPeak | Update per migration plan | Links OK |
| `AGENTS/CoralReef.md` | Documentation role rules | Agent-rule authority | CURRENT-AUTHORITY | CoralReef | Update per migration plan | Links OK |
| `AGENTS/TopazCliff.md` | PM role rules | Agent-rule authority | CURRENT-AUTHORITY | TopazCliff | Update per migration plan | Links OK |
| `EDUCATION/00_INDEX.md` | Reconciled learning index | EDUCATION reference | CURRENT-SUMMARY | CyanPeak/CoralReef | Keep; do not duplicate into external docs | Links OK |
| `EDUCATION/*.md` | Reconciled walkthroughs/spec primers | Learning/reference | CURRENT-SUMMARY | CyanPeak/CoralReef | Keep; reference from new docs if needed | Links OK |
| `docs/external_documentation_system/` | 140-file external doc system | Reference snapshot (per CoralReef review) | REFERENCE | External reviewer / CoralReef | Mark as reference; adopt selected templates only | README header needed |
| `docs/external_documentation_system/PROJECT_PLAN/ACTIVE_LANE.md` | Declares `FOUNDATION-0` | Do not adopt as live authority | REFERENCE | External reviewer | Mark reference-only | Conflicts with STATUS.md |
| `docs/external_documentation_system/PROJECT_PLAN/MASTER_EXECUTION_PLAN.md` | 11-platform roadmap | Reference roadmap only | REFERENCE | External reviewer | Mark reference-only | Conflicts with STATUS.md |
| `docs/external_documentation_system/PROJECT_PLAN/CURRENT_BASELINE.md` | Baseline template (all TBD) | Reference template | REFERENCE | External reviewer | Mark reference-only | Actual baseline in STATUS.md |
| `docs/external_documentation_system/PROJECT_PLAN/DOCUMENT_OWNERSHIP.md` | Anti-drift rule | Merge concept into AGENTS.md/CONVENTIONS.md | REFERENCE | CoralReef | Adopt concept, not file | N/A |
| `docs/external_documentation_system/PROJECT_PLAN/DECISIONS/ADR-001..006` | Architecture decision records | Adopt to `PROJECT_PLAN/DECISIONS/` | CURRENT-AUTHORITY (after adoption) | TopazCliff/CoralReef | Review each for conflicts, then adopt | N/A |
| `docs/external_documentation_system/PROJECT_PLAN/TASKS/TASK_TEMPLATE.md` | Task template | Adopt to `PROJECT_PLAN/TASK_TEMPLATE.md` | CURRENT-AUTHORITY (after adoption) | TopazCliff | Replace or supplement existing template | N/A |
| `docs/external_documentation_system/PROJECT_PLAN/proof_packets/PROOF_PACKET_TEMPLATE.md` | Proof packet template | Adopt to `PROJECT_PLAN/proof_packets/` | CURRENT-AUTHORITY (after adoption) | TopazCliff | Create template file | N/A |
| `docs/external_documentation_system/RELEASE_MANIFEST_TEMPLATE.yaml` | Release manifest template | Adopt to project root or `PROJECT_PLAN/` | CURRENT-AUTHORITY (after adoption) | TopazCliff | Create template file | N/A |
| `docs/external_documentation_system/docs/architecture/` | Architecture specs | Reference; merge selectively | REFERENCE | CyanPeak/BrightForge | Do not replace EDUCATION/ without separate lane | Overlaps EDUCATION/ |
| `docs/external_documentation_system/docs/fpga/` | FPGA component specs | Reference; merge selectively | REFERENCE | BrightForge | Create shared specs only for active lanes | Overlaps EDUCATION/ |
| `docs/external_documentation_system/docs/firmware/` | Firmware/libvdp specs | Reference; merge selectively | REFERENCE | BronzeGate | Create shared specs only for active lanes | Overlaps EDUCATION/ |
| `docs/external_documentation_system/docs/platforms/` | Platform adapter plans | Reference snapshots | REFERENCE | External reviewer | Do not adopt canonical | N/A |
| `docs/external_documentation_system/docs/runbooks/` | Runbook skeletons | Reference templates | REFERENCE | External reviewer | Populate with verified commands before adoption | TBD placeholders |
| `docs/external_documentation_system/docs/testing/` | Test plan skeletons | Reference templates | REFERENCE | External reviewer | Populate with verified oracles before adoption | TBD placeholders |
| `kb/<Adapter>/` | Existing platform adapter knowledge | Canonical adapter directory | CURRENT-AUTHORITY | BronzeGate/TopazCliff | Convert new/reopened adapters to canonical structure | Partial (some dirs lack full file set) |
| `hw/spinal/spinalhdlvdp/` | SpinalHDL source | FPGA behavior authority | CURRENT-AUTHORITY | BrightForge | Keep; new specs must reference source files | N/A |
| `hw/gen/top_tang20k.v` | Generated Verilog | Build artifact | GENERATED | BrightForge | Keep generated; never edit directly | N/A |
| `fpga/tang20k/impl/pnr/project.fs` | Gowin bitstream | Build artifact / release artifact | GENERATED | BrightForge | Hash and track in proof packets | N/A |
| `firmware/libvdp/` | Reusable host driver | Public firmware API authority | CURRENT-AUTHORITY | BronzeGate | Keep; new specs must reference headers | N/A |
| `firmware/README.md` | Firmware build/usage guide | Firmware summary | CURRENT-SUMMARY | BronzeGate | Update if interface changes | Links OK |
| `firmware/GOTCHAS.md` | Known pitfalls | Firmware reference | REFERENCE | BronzeGate | Keep; reference from specs | Links OK |
| `PROJECT_PLAN/proof_packets/` | Proof packets | Actual evidence authority | CURRENT-AUTHORITY (new) | All | Create per-lane proof packets going forward | New structure |
| `PROJECT_PLAN/DECISIONS/` | Architecture decisions | ADR authority | CURRENT-AUTHORITY (new) | TopazCliff/CoralReef | Create and populate | New structure |
| `PROJECT_PLAN/archive/` | Archived docs/artifacts | Historical | HISTORICAL | TopazCliff | Keep; do not move without cause | N/A |

## Duplicate-authority risks identified

| Fact | Locations | Resolution |
|---|---|---|
| Live active lane | `STATUS.md` vs `docs/external_documentation_system/PROJECT_PLAN/ACTIVE_LANE.md` | Keep `STATUS.md` canonical; mark external `ACTIVE_LANE.md` reference-only. |
| Platform roadmap | `STATUS.md` / `PROJECT_PLAN.md` vs external `MASTER_EXECUTION_PLAN.md` | Keep external as reference; 11-platform sequence not approved. |
| Adapter knowledge | `kb/<Adapter>/` vs `docs/external_documentation_system/docs/platforms/` | Keep `kb/` canonical; external platform docs are reference. |
| Learning material | `EDUCATION/` vs external `docs/architecture/`, `docs/fpga/`, `docs/firmware/` | Keep `EDUCATION/` reconciled; external docs are reference. |
| Task template | `PROJECT_PLAN/TASK_TEMPLATE.md` vs external `PROJECT_PLAN/TASKS/TASK_TEMPLATE.md` | Adopt external template if it is more complete. |

## Unknown / needs owner

None identified at this time. All major paths have an owner and classification.
