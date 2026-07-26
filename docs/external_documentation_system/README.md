> **Reference snapshot — not canonical live state.** This documentation system
> was delivered by an external reviewer on 2026-07-25. Per CoralReef's review
> (`PROJECT_PLAN/external_docs_system_review.md`), it is kept as a reference
> snapshot. Live project state remains in repository-root `STATUS.md`.

# spinalhdlVDP Documentation System (Reference Snapshot)

This directory is a modular documentation system delivered by an external
reviewer. It is **not** the canonical live authority for the project.

The product is a host-independent FPGA video coprocessor implemented in
SpinalHDL on the Tang Nano 20K. A host MCU, CPU, SBC, or custom machine uses
`libvdp` to supply graphics memory and video state. The FPGA emulates video
hardware only and owns deterministic rendering, scaling, and HDMI scanout.

## Start here

1. Read `PROJECT_PLAN/ACTIVE_LANE.md`.
2. Read `PROJECT_PLAN/MASTER_EXECUTION_PLAN.md`.
3. Open the work package for the active task.
4. Follow the linked technical specification and runbook.
5. Store actual evidence in a proof packet.
6. Do not start the next lane until the current exit gate is signed.

## Documentation levels

- **Project control:** current state, sequencing, dependencies, releases.
- **Architecture:** system-wide technical contracts.
- **FPGA:** SpinalHDL component specifications and verification expectations.
- **Firmware:** `libvdp`, ABI, transport, and host-porting contracts.
- **Platforms:** one repeatable work package per visual platform.
- **Runbooks:** exact commands and operational procedures.
- **Testing:** objective test oracles, evidence, and clean-room reproduction.
- **Proof packets:** actual logs, hashes, reports, and approvals.

## Authority rule

No fact should be manually maintained in multiple authoritative locations.

- Register addresses: register schema.
- FPGA behavior: approved SpinalHDL/component specification.
- Public C API: headers plus generated API reference.
- Current project status: `PROJECT_PLAN/ACTIVE_LANE.md`.
- Build commands: runbooks.
- Expected results: test specifications and golden vectors.
- Actual results: proof packets.
- Release hashes and tool versions: release manifest.

## Current active lane

`FOUNDATION-0 — Baseline and Contract Reconciliation`

No new platform RTL lane starts until Foundation Gate 0 closes.
