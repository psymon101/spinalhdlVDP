# 720p-proof-build-script-cleanup

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** OPEN  
**Opened:** 2026-07-30  
**Trigger:** Owner-directed sequence: lane 6 → lane 3 → lane 1. Finish the leftover cleanup from `repo-cleanup-rtl-build` (2026-07-19).

---

## Objective

Clean up the 720p proof build scripts, constraints, and SDC files under `fpga/tang20k/` so the production build area is uncluttered and the surviving 720p proof targets are easy to find and rebuild.

---

## Background

The `repo-cleanup-rtl-build` lane archived retired i80/barebones/wire_rev CSTs and TCLs, but explicitly left the 720p-proof CSTs/SDCs/TCLs in place because they were still referenced by `Makefile` targets. That lane noted a separate cleanup pass was pending.

Current 720p proof artifacts in `fpga/tang20k/`:
- `build_hdmi720p_bridge.tcl`
- `build_hdmi720p_linebuf.tcl`
- `build_hdmi720p_mode0.tcl`
- `build_hdmi720p_planar.tcl`
- `build_hdmi720p_proof.tcl`
- `tang20k_hdmi720p_bridge.cst`
- `tang20k_hdmi720p_bridge.sdc`
- `tang20k_hdmi720p_linebuf.cst`
- `tang20k_hdmi720p_linebuf.sdc`
- `tang20k_hdmi720p_mode0.cst`
- `tang20k_hdmi720p_mode0.sdc`
- `tang20k_hdmi720p_planar.cst`
- `tang20k_hdmi720p_planar.sdc`
- `tang20k_hdmi720p_proof.cst`
- `tang20k_hdmi720p_proof.sdc`
- `Makefile` targets: `gen-720p-bridge`, `720p-bridge`, `prog-720p-bridge`, `flash-720p-bridge`, etc.
- `.gitignore` entries: `fpga/tang20k/impl_720p_bridge/`, `impl_720p_mode0/`, `impl_720p_linebuf/`, `impl_720p_planar/`.

Source proof tops in `hw/spinal/spinalhdlvdp/`:
- `Hdmi720pBridgeProofTop.scala`
- `Hdmi720pLinebufProofTop.scala`
- `Hdmi720pMode0ProofTop.scala`
- `Hdmi720pPlanarProofTop.scala`

---

## Scope

1. Audit each 720p proof target and decide **keep / archive / delete**:
   - A proof target is **kept** only if it exercises a current or near-term regression path not covered by the native 640×480 production build or the new `diagnostic` target.
   - A proof target is **archived** if it is historical/no longer maintained but might be useful for reference.
   - A proof target is **deleted** if it is fully superseded or broken and not worth keeping.
2. Reorganize surviving 720p-proof build files into a clear location, e.g. `fpga/tang20k/proofs/720p/` or `fpga/tang20k/archive/720p_proofs/`.
3. Update `fpga/tang20k/Makefile` so that:
   - surviving targets still work from the new paths,
   - obsolete targets are removed,
   - production targets (`all`, `gen`, `prog`, `flash`) are untouched.
4. Update `.gitignore` to match the new layout.
5. Verify `sbt compile` passes and at least one surviving 720p proof Verilog generator runs cleanly.
6. Ensure production `make gen` / `make` still works and the production `impl/` directory layout is unchanged.
7. If build commands change, update any affected runbooks under `docs/runbooks/`.

## Out of Scope

- No RTL behavior changes.
- No production CST/SDC/TCL changes.
- No hardware flash or capture proof required.
- No new features or regression content.

---

## Acceptance Criteria

- [ ] Audit decision recorded in this task file (keep/archive/delete per target).
- [ ] Surviving 720p proof files moved to a clean location; obsolete files archived/deleted.
- [ ] `Makefile` updated and still passes a dry-run / syntax check.
- [ ] `sbt compile` passes.
- [ ] At least one surviving 720p proof Verilog generator (`sbt runMain spinalhdlvdp.<Name>Verilog`) runs cleanly.
- [ ] Production `make gen` or equivalent still generates `hw/gen/top_tang20k.v` without error.
- [ ] `.gitignore` updated and `git status` clean after build.
- [ ] Any doc/runbook changes committed.

---

## Blockers

None.

---

## Artifacts / References

- Original cleanup lane: `PROJECT_PLAN/TASKS/repo-cleanup-rtl-build.md`
- `repo-cleanup-rtl-build` closeout note: "Remaining 720p proof CSTs/SDCs/TCLs still referenced by Makefile targets were left in place pending a separate build-script cleanup pass."
- 720p proof source tops: `hw/spinal/spinalhdlvdp/Hdmi720p*ProofTop.scala`
- Build scripts: `fpga/tang20k/Makefile`, `fpga/tang20k/build_hdmi720p_*.tcl`
