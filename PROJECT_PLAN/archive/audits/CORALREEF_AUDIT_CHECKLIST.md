# CoralReef Audit / Doc-Sync Checklist

Standing checklist for `CoralReef` audit, closeout, and ledger-sync work.

## Per-Lane Audit Checklist

### Before issuing PASS / HOLD / FAIL

- [ ] **Dependencies** — all `depends_on` tasks are `DONE`
- [ ] **Scope boundary** — diff does not cross the approved scope boundary
- [ ] **Validation plan** — proof method is explicit and matches the lane's required proof shape
- [ ] **Simulation evidence** — present and unambiguous (when applicable)
- [ ] **Hardware evidence** — present with commit tie-back, capture duration, and pass/fail metric (when applicable)
- [ ] **Regression** — no regression claim without evidence
- [ ] **Commit tie-back** — exact commit hash stated and reachable in repo

### Doc-Sync Sub-Checklist (embedded per BrightForge #10274)

Whenever an audit / closure / handoff touches **any of these surfaces**, verify the corresponding libvdp doc surface is current **before** signing off:

| If a lane changes... | Verify the doc is current |
|---|---|
| `firmware/libvdp/*.h` (new function, signature change, deprecation) | `kb/libvdp/README.md` API tables |
| `firmware/libvdp/*.c` semantics (behavior change, new sequencing rule, new hazard) | `kb/libvdp/README.md` "Critical Implementation Facts" / GOTCHAs |
| RTL register map changes (new register, new bit, new register surface) | `kb/libvdp/README.md` register reference + `MODE0_REGISTER_BUS_SPEC.md` if it exists for that range |
| A new bench-validated programming pattern (Y-sort, same-Y coalesce, upload-before-swap, etc.) | `kb/libvdp/README.md` or a new section if there's no natural home |

**Rule:** The "should libvdp docs change?" check is part of the audit checklist itself, not a lane participant's memory.

### Closeout / Ledger Sync

- [ ] `TASKS.md` live-lane block updated with latest commit, latest auth mail, phase, next deliverable
- [ ] `TASKS.md` closed-task table updated (if lane is closing)
- [ ] `CHANGELOG.md` updated if the change is user-visible
- [ ] `AGENTS.md` updated only if a role, server, or preventive rule changed (requires BronzeGate auth + CyanPeak audit per Rule #8)

## Sign-Off String

`— CoralReef`
