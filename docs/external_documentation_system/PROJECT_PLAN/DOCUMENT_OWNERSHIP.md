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
