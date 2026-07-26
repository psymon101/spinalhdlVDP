> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Testing Documentation

Objective test oracles, evidence requirements, and clean-room reproduction.

Each test plan includes:
- Test ID;
- Requirement IDs;
- Owner;
- Environment;
- Source commit;
- Input asset/vector and hash;
- Initial state;
- Command;
- Expected trace;
- Expected pixel/line/frame result;
- Expected status;
- Maximum latency/time;
- Pass/fail rule;
- Evidence path.

Actual results belong in `PROJECT_PLAN/proof_packets/<LANE>/`.
