# 720p-proof-build-script-cleanup

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE — 2026-07-30 (archive-all; PM-approved #14502)  
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

## Audit Decision (2026-07-30)

**Method:** inventory of all 720p build artifacts, git-history age check, and a
repo-wide reference scan (docs / runbooks / README / CI). PM checkpoint proposed
in #14501; **archive-all approved in #14502**.

**Findings:**
- All 5 build TCLs + 10 CST/SDC files last touched `4e3dfcf4` (2026-06-07) — dormant.
- **No references** in `docs/`, `docs/runbooks/`, `README*`, or CI. (Only
  `scripts/readasync_baseline.txt` names `Hdmi720pPlanarProofTop` as a readAsync
  inventory reference — that is the Scala source, which is not being moved.)
- All 5 are 720p output-shell HDMI bring-up experiments, now covered by the
  native 640×480 production build + the `diagnostic` target (`standalone-diagnostic-build`,
  merged `ec5c9724`). `720p-mode0` is the direct predecessor the diagnostic build replaced.
- Against the keep-rule (§Scope), **none** exercise a path uncovered by production or diagnostic.

**Decision — ARCHIVE all 5** (keep 0, delete 0):

| Target | Decision | Rationale |
|---|---|---|
| `720p-proof`   | ARCHIVE | colour-bars shell; HDMI output sanity now covered by production + diagnostic |
| `720p-bridge`  | ARCHIVE | 720p centered-640×480 bridge; superseded by native 640×480 |
| `720p-mode0`   | ARCHIVE | VdpTop-under-720p-shell; directly superseded by `diagnostic` |
| `720p-linebuf` | ARCHIVE | dual-clock line-buffer CDC bring-up experiment; historical |
| `720p-planar`  | ARCHIVE | planar fetch-primitive bring-up experiment; historical |

**Actions taken:**
- `git mv` 15 build artifacts (5 `build_hdmi720p_*.tcl` + 10 `.cst/.sdc`) → `fpga/tang20k/archive/720p_proofs/`.
- Removed the 5 720p target blocks from `fpga/tang20k/Makefile` (lines 44–190),
  replaced with an archival breadcrumb comment; fixed a now-stale "720p-proof target"
  note in the diagnostic section.
- Removed the 5 `impl_720p_*` entries from `.gitignore`.
- Added `fpga/tang20k/archive/720p_proofs/README.md` (provenance + supersession).
- **Left the Scala `Hdmi720p*ProofTop.scala` generators untouched** (RTL/source; out of scope, no RTL changes).

## Acceptance Criteria

- [x] Audit decision recorded in this task file (keep/archive/delete per target). *(all 5 → ARCHIVE, above)*
- [x] Surviving 720p proof files moved to a clean location; obsolete files archived/deleted. *(all → `archive/720p_proofs/`)*
- [x] `Makefile` updated and still passes a dry-run / syntax check. *(`make -n gen`/`gen-diagnostic`/`all` OK; `make -n 720p-proof` → "No rule", as intended)*
- [x] `sbt compile` passes.
- [x] At least one surviving 720p proof Verilog generator (`sbt runMain spinalhdlvdp.<Name>Verilog`) runs cleanly. *(`Hdmi720pProofTopVerilog` → Done, only benign pruned-signal warning)*
- [x] Production `make gen` or equivalent still generates `hw/gen/top_tang20k.v` without error. *(1135805 B, sha256 `945b060b…`)*
- [x] `.gitignore` updated and `git status` clean after build. *(15 renames + 2 modified + 1 new README; no stray artifacts)*
- [x] Any doc/runbook changes committed. *(no runbook referenced these targets; archive README added)*

---

## Blockers

None.

---

## Artifacts / References

- Original cleanup lane: `PROJECT_PLAN/TASKS/repo-cleanup-rtl-build.md`
- `repo-cleanup-rtl-build` closeout note: "Remaining 720p proof CSTs/SDCs/TCLs still referenced by Makefile targets were left in place pending a separate build-script cleanup pass."
- 720p proof source tops: `hw/spinal/spinalhdlvdp/Hdmi720p*ProofTop.scala`
- Build scripts: `fpga/tang20k/Makefile`, `fpga/tang20k/build_hdmi720p_*.tcl`
