# Mode0-T20 Default Build Feature Strip Analysis
## BronzeGate #10010 — `mode2optimized` branch baseline `c52ed8d`

**Author:** TopazCliff (external review / advisory)
**Scope:** Analysis only — no code edits.
**Baseline:** Commit `c52ed8d` on branch `mode2optimized`.

---

## 1. Current Live Features That Exceed or Are Softer Than Spec

| Feature | Spec Requirement | Live Code | Gap |
|---|---|---|---|
| **Planar depth** | Guaranteed 4 planes | `PLANE_COUNT = 5` hardcoded at `VdpTop.scala:881` | **1 plane excess** — consumes extra row-buffer BRAM + planar fetch FSM state + 2 extra base-address registers |
| **Raster triggers** | 1 compare unit guaranteed | 4 `RasterTriggerUnit()` instances (TR0..TR3) at lines 1750, 1801, 1810, 1819 | **3 triggers excess** — each consumes ~2×10-bit comparators + pending FF + 3×16-bit register decode |
| **Affine / Mode7** | Build-gated optional | Always present: affine regs (0x0340..0x0346), `AffineStepper`, 128×128 texture BRAM (`16,384 × 8b`) | **16 Kbit BRAM + coordinate stepper** always in fabric even when `affineEnable=0` at runtime |
| **L2/L3 layers** | Richer live use = build-gated optional | `BasicPatternSource()` for L2/L3 always instantiated at lines 1170, 1176 | **2 lightweight layers** always present; low LUT cost but nonzero fabric usage |
| **Everything-enabled default** | Unsupported (spec §5) | Default `scenarioId=0` does NOT enable all features at runtime, but **all hardware is compiled into fabric** | Compile-time issue — the default bitstream contains planar(5), affine, 4 triggers, dual-window, copper, blitter, DMA, etc. |

### Features that MATCH spec (no cut needed)

- Sprite count: 8 descriptors, 8 visible/line — matches spec exactly.
- Palette: 128 entries (8 banks × 16) — matches spec.
- Windowing: 2 `WindowUnit` instances present. Spec guarantees 1; dual is acceptable as "architectural max" and both are used by existing scenarios.
- Color math: present and functional.
- Blitter + DMA: both present and wired.
- Copper + HDMA: present and functional.
- Core fetch formats: tile+attribute, bitmap, planar — all present.
- L0/L1 SDRAM-backed fetch: present; `enableL1Fetch` defaults to `true`.
- Output timing: 640×480@60 progressive — matches spec.

### Features that are SOFTER than spec (code has LESS than spec)

None identified. The code exceeds spec in every dimension where a gap exists.

---

## 2. Smallest Safe Cuts / Gates to Reach Spec

### Priority 1 — High impact, low risk

**A. Gate Affine behind `enableAffine: Boolean = false`**
- Wrap `affineTexture` Mem, `AffineStepper`, affine register block (0x0340..0x0346), and affine source mux in `if (enableAffine)`.
- When gated off, affine pixel path collapses to tied-off zero; layer-0 source mux loses the affine input but retains tile/bitmap/planar paths.
- Estimated savings: ~16 Kbit BRAM + modest LUTs for stepper + 7×16-bit registers.
- Risk: Low. Only Sc12 and Sc37 use affine at runtime. All other scenarios keep `affineEnable=0`.

**B. Reduce `PLANE_COUNT` from 5 → 4 (parameterize)**
- Change hardcoded `PLANE_COUNT = 5` to a constructor parameter (default 5 to preserve existing behavior, overridden to 4 for stripped build).
- `PlanarLineFetch` already accepts `planeCount` as a constructor argument.
- Register decode range shrinks from `0x0D40..0x0D51` to `0x0D40..0x0D4F` (saves 2 words).
- Estimated savings: 1 plane worth of row-buffer BRAM + 2×16-bit base-address registers.
- Risk: Low. Sc9/Sc10 use planar but do not depend on the 5th plane specifically.

**C. Gate TR1..TR3 behind `enableExtraRasterTriggers: Boolean = false`**
- Keep TR0 with its existing top-level IO surface (backward compat with `RasterTriggerUnitSim` / `VdpTopSim`).
- Wrap TR1..TR3 instantiation + register decode (0x0360..0x036A) in `if (enableExtraRasterTriggers)`.
- When gated off, `rasterPendingMask` collapses to TR0 only; `evRasterMatch` still fires from TR0.
- Estimated savings: 3 × `RasterTriggerUnit` + 9×16-bit registers.
- Risk: Low. Only Sc20 and Sc70 configure TR0 via top-level IO; no scenario uses TR1..TR3.

### Priority 2 — Medium impact, low risk

**D. Consider gating L2/L3 behind `enableL2L3: Boolean = false`**
- `BasicPatternSource` is lightweight (~LUTs for address generation + small tile ROM lookup).
- Removing L2/L3 simplifies the layer-enable mask from 5 bits to 3 bits and removes 2 compositor inputs.
- **Caution required:** `layerEnableReg` is 5 bits wide (bits 4..3 = L3/L2). Many scenarios write `0x0300` with values that encode L2/L3 state. Gating them requires audit of every scenario's layer bootstrap value.
- Risk: Medium due to scenario mask audit burden. Savings are small; defer unless LUT budget is tight.

**E. Keep `enableL1Fetch` default as `true` but ensure stripped scenario only uses L0**
- Already correct: `TopTang20kHdmi(scenarioId=0)` sets `layerData = 0x0001` (L0 only) and `video.io.layer1UseSdram := False`.
- The `fetchL1` instance is still created but electrically inactive. We could gate its instantiation, but this is lower priority since L1 SDRAM fetch is part of the "2 strong live BG layers" guarantee.

### Priority 3 — Nice to have / future

**F. Gate dual-window to single-window in default build**
- Spec guarantees 1 window; code has 2 `WindowUnit` instances + 6-mode combination logic.
- However, the second window + combinator is small (a few dozen LUTs). Lower priority.

**G. Advanced blitter ops / HAM / EHB**
- These are already marked "deferred" in spec. No code exists to cut.

---

## 3. Exact Files / Modules Likely to Change

| File | Lines | Changes |
|---|---|---|
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | 2245 | Add constructor params (`enableAffine`, `enableExtraRasterTriggers`, `planeCount`); wrap affine instantiation block, TR1..TR3 block, and planar depth in conditionals; adjust register decode ranges for plane base addresses; adjust layer-0 source mux; adjust `rasterPendingMask` width logic |
| `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` | 2014 | Pass new params into `VdpTop(...)` constructor; default `scenarioId=0` uses stripped param set; keep other scenarios on full param set for bit-identicalness |
| `hw/spinal/spinalhdlvdp/AffineAssets.scala` | ~50 | No change required if affine is gated at `VdpTop` instantiation site; object can remain in source tree unused |
| `sim/src/vdp/VdpTopSim.scala` | N/A | Check for hardcoded affine register writes or TR1..TR3 references; may need conditional compilation or param pass-through |
| `PROJECT_PLAN/MODE0_PLANNING.md` | 301 | Update §10.6 register map if plane base-address range shrinks; update §4 if default build params change |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` | N/A | Verify no address collision after shrinking plane block; document gated addresses as "optional build only" |

---

## 4. Major Regression Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Scenario bit-identicalness** | HIGH | Every scenario (0, 9, 10, 12, 20, 37, 44, 50, 55, 60, 62, 70, etc.) must be validated after changes. The default stripped scenario MUST remain backward-compatible for existing OpenCV tests. Run full capture-diff suite. |
| **Gowin inference instability** | HIGH | Per CyanPeak #9901 / BrightForge consensus, structural pruning is the #1 risk for DFF promotion/demotion side effects. Any gate must be `if (param)` at the SpinalHDL Scala level so synthesis sees true structural difference, not just dead-code elimination. After each param addition, run Gowin synthesis and compare resource report line-by-line. |
| **Register map shift / collision** | MEDIUM | Reducing `PLANE_COUNT` from 5→4 shrinks the plane base-address register block. If any firmware or copper program hardcodes `0x0D50+` for other purposes, collision risk exists. Audit `MODE0_REGISTER_BUS_SPEC.md` for free-space boundaries. |
| **Simulator breakage** | LOW | `VdpTopSim` and `RasterTriggerUnitSim` may reference affine regs or TR1..TR3 directly. Check `sim/` for hardcoded address assumptions. Add param pass-through to sim constructor. |
| **Copper/HDMA program compatibility** | LOW | If TR1..TR3 addresses are gated, copper programs that write `0x0360..0x036A` will have no hardware effect. Acceptable for default build but must be documented as "optional feature absent = conforming" per spec §8. |
| **Layer-enable mask semantic change** | LOW | If L2/L3 are gated, `layerEnableReg` width drops from 5 to 3 bits. Scenarios writing bits [4:3]=1 would silently lose those layers. Do NOT change mask width without full scenario audit. |

---

## 5. Proof / Validation Needed After Cuts

| Proof | Method | Owner |
|---|---|---|
| **Bit-identical scenario verification** | Run full scenario suite and diff hardware captures against baseline `c52ed8d` | BronzeGate / BrightForge |
| **LUT/BRAM count delta** | Gowin synthesis before/after on stripped default build; target ~800–1,500 LUT relief + 1–2 BSRAM blocks | BronzeGate |
| **Register map consistency check** | Verify no address collision after shrinking plane base-address block; confirm `0x0D50+` remains free | CyanPeak audit |
| **Scala simulation pass** | `sbt test` across all Scala sims (`VdpTopSim`, `RasterTriggerUnitSim`, etc.) | BrightForge |
| **Firmware compile check** | Ensure ESP8266 sketches compile against unchanged (or documented-changed) register map | FoggyWolf |
| **OpenCV automated test pass** | Capture-diff on Sc0, Sc20, Sc50, Sc55, Sc60, Sc62 | BronzeGate |
| **Synthesis timing closure** | Confirm stripped build still meets 33 MHz pixel clock | BronzeGate |

---

## 6. Recommended Order of Implementation

| Step | Action | Est. Effort | Gate? |
|---|---|---|---|
| 1 | **Parameterize `planeCount`** in `VdpTop` constructor (default 5, overridable); pass through `TopTang20kHdmi`; verify Sc9/Sc10 planar scenarios still produce identical captures | 1–2 hrs | CyanPeak diff audit |
| 2 | **Gate affine** behind `enableAffine` (default `true` for compat, `false` for stripped build); verify Sc12 can still enable affine via explicit `true` param | 2–3 hrs | CyanPeak diff audit |
| 3 | **Gate TR1..TR3** behind `enableExtraRasterTriggers` (default `true`, `false` for stripped); verify Sc20/Sc70 raster trigger behavior unchanged | 1–2 hrs | CyanPeak diff audit |
| 4 | **Create stripped default** in `TopTang20kHdmi`: `scenarioId=0` uses `enableAffine=false, enableExtraRasterTriggers=false, planeCount=4`; run Gowin synthesis and compare resource report to baseline | 2–3 hrs | Synthesis report diff |
| 5 | **Evaluate L2/L3 gating** if further LUT relief needed; cost/benefit may favor keeping them (very small LUT cost, large scenario audit burden) | 2–4 hrs | Scenario mask audit |
| 6 | **Full regression suite** — all scenarios, all sims, synthesis, OpenCV capture-diff | 4–8 hrs | CyanPeak PASS/HOLD/FAIL |
| 7 | **Update docs** — `MODE0_PLANNING.md` register map and §10; `MODE0_REGISTER_BUS_SPEC.md` if addresses changed; add build-gate param summary | 1–2 hrs | TopazCliff review |

---

## Bottom Line

The smallest safe path to a Mode0-T20-compliant default build is **3 parameterized gates + 1 stripped default scenario**:

1. `planeCount` parameter (5 → 4 for stripped)
2. `enableAffine` gate (default `false` for stripped)
3. `enableExtraRasterTriggers` gate (default `false` for stripped)
4. `scenarioId=0` invokes `VdpTop` with the stripped param set

This is **low-risk** because each change is a constructor-parameter conditional, not a logic rewrite. The fabric still contains all the original hardware for non-default scenarios; only the default bitstream is stripped.

The **highest risk is Gowin inference instability** from structural changes. Per the fit-cleanup consensus (#9893 / #9901), synthesis validation must accompany every param addition — simulation alone is insufficient.

---

*End of analysis packet. No code changes were made in the production of this document.*
