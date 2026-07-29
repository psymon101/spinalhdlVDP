> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Proof Packet Template

Use this structure for every lane that produces build, simulation, synthesis,
or hardware evidence.

```text
PROJECT_PLAN/proof_packets/<LANE-or-TASK>/
├── manifest.yaml
├── source/
├── simulation/
├── generated_rtl/
├── synthesis/
├── firmware/
├── hardware/
├── captures/
├── hashes.sha256
└── review.md
```

## manifest.yaml

```yaml
lane: <LANE-ID>
source_commit: <hash>
latest_authoritative_mail: <message-id>
owner: <CanonicalName>
reviewers:
  brightforge: <message-id/verdict>
  bronzegate: <message-id/verdict>
  cyanpeak: <message-id/verdict>
  coralreef: <message-id/verdict>
decision: <PASS/FAIL/CONDITIONAL>
next_task: <task/owner>
```

## Required evidence

A hardware result is invalid without:

- source commit;
- generated RTL hash;
- bitstream hash;
- firmware hash;
- asset hash;
- board revision;
- wiring revision;
- tool versions;
- exact procedure.
