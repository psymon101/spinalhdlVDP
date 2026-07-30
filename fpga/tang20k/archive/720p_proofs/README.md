# Archived 720p output-shell proof builds

**Archived:** 2026-07-30 — lane `720p-proof-build-script-cleanup` (PM TopazCliff, mail #14500/#14502).

These five build recipes were early HDMI bring-up experiments that ran the VDP
inside a **720p output shell** on the Tang Nano 20K. They are **historical /
no longer maintained** and were moved here (build TCLs + CST/SDC) out of the
active `fpga/tang20k/` build root.

## Why archived (not deleted)

They are fully **superseded** by two builds that already cover both production
and no-host sanity paths at the correct native timing:

- **Production** — native 640×480 build (`build.tcl` → `top_tang20k`), the
  HW-proven display path.
- **Diagnostic** — `make diagnostic` (`build_diagnostic.tcl` →
  `top_tang20k_diagnostic`), the no-host / no-QSPI / no-SDRAM native 640×480 1×
  test-pattern build (lane `standalone-diagnostic-build`, merged `ec5c9724`).

Against the lane keep-rule ("keep only if it exercises a path not covered by
the native 640×480 production or the diagnostic build"), none of these qualify:
they run a 720p shell that neither current build uses. `720p-mode0` in
particular is the direct predecessor the `diagnostic` build replaced. They are
retained only as reference for how the 720p shell was wired.

## Contents (provenance)

| Build recipe | CST / SDC | Origin | Purpose |
|---|---|---|---|
| `build_hdmi720p_proof.tcl`   | `tang20k_hdmi720p_proof.{cst,sdc}`   | #8482 Slice B    | 720p output-shell proof (synthetic colour bars) |
| `build_hdmi720p_bridge.tcl`  | `tang20k_hdmi720p_bridge.{cst,sdc}`  | #8486 Slice C    | 720p centered-640×480 bridge proof |
| `build_hdmi720p_mode0.tcl`   | `tang20k_hdmi720p_mode0.{cst,sdc}`   | #8496 Slice D-A  | VdpTop test-pattern under 720p shell |
| `build_hdmi720p_linebuf.tcl` | `tang20k_hdmi720p_linebuf.{cst,sdc}` | #8505 Slice D-B1-L | dual-clock line-buffer CDC proof |
| `build_hdmi720p_planar.tcl`  | `tang20k_hdmi720p_planar.{cst,sdc}`  | Mode0 fetch-envelope hardening | planar fetch primitives HW proof |

## Scala generators (NOT archived — still in source tree)

The SpinalHDL proof tops remain under `hw/spinal/spinalhdlvdp/` and are out of
scope for this build-script cleanup (no RTL changes):

- `Hdmi720pProofTop.scala`        → `spinalhdlvdp.Hdmi720pProofTopVerilog`
- `Hdmi720pBridgeProofTop.scala`  → `spinalhdlvdp.Hdmi720pBridgeProofTopVerilog`
- `Hdmi720pMode0ProofTop.scala`   → `spinalhdlvdp.Hdmi720pMode0ProofTopVerilog`
- `Hdmi720pLineBufferProofTop.scala` → `spinalhdlvdp.Hdmi720pLineBufferProofTopVerilog`
- `Hdmi720pPlanarProofTop.scala`  → `spinalhdlvdp.Hdmi720pPlanarProofTopVerilog`

## Rebuilding one (reference only)

The `make 720p-*` targets were removed from `fpga/tang20k/Makefile`. To rebuild
a shell manually, regenerate its Verilog and run its archived TCL from a working
copy, e.g. for the colour-bars proof:

```sh
# from repo root
sbt "runMain spinalhdlvdp.Hdmi720pProofTopVerilog"
# then run archive/720p_proofs/build_hdmi720p_proof.tcl against the generated
# hw/gen/top_tang20k_720p_proof.v with the matching CST/SDC (paths are relative
# to fpga/tang20k/, so copy the recipe back there or adjust add_file paths).
```
