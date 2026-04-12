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

Keep here:

- SpinalHDL source under `hw/spinal/spinalhdlvdp/`
- generated HDL configuration targeting `hw/gen/`
- Tang Nano 20K specific wrappers, constraints, and integration glue

Do not treat older VDP repositories as the source of truth for this repo's
directory structure. Port only what is intentionally adopted.

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
