# Review — standalone-diagnostic-build

Per AGENTS.md Proof Packet requirements (Rule 15).

## Verdicts

| Reviewer | Scope | Verdict | Ref |
|---|---|---|---|
| BrightForge | RTL (Option A diagnosticMode), diagnostic gen/build, PnR, cold-POR HW proof | **PASS** — native 640×480 standalone build boots no-host/no-QSPI/no-SDRAM; 1× full-screen grid; TNS=0; 10/10 cold-POR; production netlist functionally unchanged (normalized diff=0). | this packet |
| CyanPeak | code-to-spec / procedure (optional, PM-activated) | pending | — |
| TopazCliff (PM) | lane authorization + closeout | pending | — |

## Notes / deviations
- **Beyond the plan's bullet list:** a clean full-screen pattern required two extra
  diagnosticMode tweaks the plan didn't enumerate — (a) `LinestateStore` ships all lines
  L0-enabled (the existing `useHostInit=false` bootstrap only wrote every-8th line, giving
  a sparse grid), and (b) color-math op=00 (the standalone bootstrap otherwise applies the
  §12 shadow window). Flagged to PM in #14497; proceeded per "clean pattern is the better
  diagnostic" unless objected.
- **Production untouched:** every change is gated on `diagnosticMode=false`; my-branch vs
  main production Verilog normalized diff = 0 (only SpinalHDL line-number-embedded signal
  renames differ). Production `TopTang20kHdmiVerilog` output/behavior unchanged.
- **Optional smoke-sim skipped:** no full-top SpinalSim precedent (top instantiates Gowin
  PLL/CLKDIV/OSER10/ELVDS blackboxes); the 10/10 cold-POR HW proof is definitive.
- **Capture method:** post-POR captures must settle ~4 s (UVC HDMI re-lock); a back-to-back
  grab yields all-zero and is a capture artifact, not a display fault.

## Status
All acceptance criteria met (compile, diagnostic gen, PnR TNS=0/no-new-resources, bitstream
+ SHA, 10/10 cold-POR, proof packet, production spot-check). Awaiting PM closeout.
