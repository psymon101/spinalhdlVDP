# AGENTS Examples

Examples and command snippets referenced by `AGENTS.md`.

## Mail Registration

Minimum sequence:

```text
ensure_project("/home/itadmin/github/spinalhdlVDP")
register_agent(
  project_key="/home/itadmin/github/spinalhdlVDP",
  program="<client>",
  model="<model>",
  name="<canonical name>",
  human_key="/home/itadmin/github/spinalhdlVDP"
)
```

## Session Start

Read in this order:

1. `AGENTS.md`
2. `PROJECT_PLAN/PROJECT_PLAN.md`
3. `PROJECT_PLAN/TASKS.md`

## FPGA Artifact Match Check

```sh
stat -c '%y %n' fpga/tang20k/impl/pnr/project.fs hw/gen/top_tang20k.v
tail -n 5 fpga/tang20k/build.log
```
