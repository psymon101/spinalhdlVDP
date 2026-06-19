# Mode0-T20 Default Build Feature Strip Analysis
## CoralReef — PM Authorization #10010, Branch `mode2optimized` Baseline `c52ed8d`

**Status:** Corrected in-project research packet. Supersedes TopazCliff external advisory.
**Scope:** Analysis only — no code edits.
**Baseline:** Commit `c52ed8d` on branch `mode2optimized`. Hardware identical to `f647e77` (sim-refactor merge). Synthesis terminal state `fedbb36`: 20793 logic = 17546 LUTs + 799 ALUs + 408 SSRAMs (+57 over GW2AR-LV18 limit of 20736).

---

## 1. Current Live Features Exceeding or Softer Than Mode0-T20 Spec

| Feature | Spec Requirement | Live Code State | Gap |
|---|---|---|---|
| **L1 tile+attribute fetch** | Guaranteed `1` (`MODE0_PLANNING.md` §2) | **DISABLED** in default build. `TopTang20kHdmiVerilog` generates with `enableL1Fetch = false` (`TopTang20kHdmi.scala:1906`). Scheduler slots 3/4 tied off; L1 fetch engine still instantiated but electrically idle. | **NON-CONFORMING**. The default bitstream lacks a guaranteed feature. Step 2 of stabilization lane (#9907) disabled L1 scaffolding to save 150 logic. Re-enabling L1 is required for spec conformance. |
| **L2 + L3 layers** | "L2/L3 richer live use" = `build-gated` optional (`MODE0_PLANNING.md` §4) | `BasicPatternSource()` for L2/L3 **always instantiated** (`VdpTop.scala:1170,1176`) with full compositor wiring. `layerEnableReg` defaults to `0b00111` (L0+L1+Sprite on, L2/L3 off at runtime), but hardware is compiled into fabric. | **2 layers excess**. From `impl_720p_mode0` sub-report: L2 = 460 LUTs + 13 ALUs; L3 = 51 LUTs. Compositor priority chain (L3 > L2 > L1 > L0) and `composedBgSource` encoding also carry L2/L3 paths. Estimated total relief: **500–600 LUTs + 10–20 ALUs**. |
| **Affine / Mode7** | `build-gated` optional (`MODE0_PLANNING.md` §4) | `AffineStepper()`, 128×128 texture `Mem.init` (`16,384 × 8b`), and affine register block (`0x0340..0x0346`) **always present** (`VdpTop.scala:1186-1200`). Runtime `affineEnable` bit exists but hardware is never removed. | **16 Kbit texture + coordinate stepper + 7×16-bit registers + L0 source mux branch** always in fabric. 9 `MULTADDALU18X18` DSP blocks consumed. Parent-level muxing (`affineIndex`/`affineBank`/`affinePrio`) adds LUT cost in the L0 source chain. |
| **Raster triggers TR1..TR3** | "Raster compare units" = 1 guaranteed (`MODE0_PLANNING.md` §3) | **4 `RasterTriggerUnit()` instances** present: TR0 (top-level IO) + TR1..TR3 (bus-addressable at `0x0360..0x036A`) (`VdpTop.scala:1750,1801,1810,1819`). | **3 triggers excess**. Each trigger = 2×10-bit comparators + pending FF + 3×16-bit register decode. Aggregate `rasterPendingMask` widened to 4 bits. |
| **Planar depth** | "Guaranteed planar depth" = 4 planes (`MODE0_PLANNING.md` §3) | `PLANE_COUNT = 5` hardcoded (`VdpTop.scala:881`). | **1 plane excess**. Code comment notes 5-plane build previously hit CLS placement wall (1600 FFs). Current code has `PLANE_COUNT = 5` despite the comment. Reduction to 4 saves 320 FFs (not LUTs). Impact on logic limit is minimal. |
| **Second WindowUnit** | "Windowing / clipping" = 1 guaranteed (`MODE0_PLANNING.md` §2) | `WindowUnit()` × 2 + 6-mode combination logic (`VdpTop.scala:2011-2047`). | **1 window excess**. `windowUnit2` + `combMode` mux (AND/OR/XOR/NAND/NOR) + `combinedWindowEffect` logic. Estimated 40+ ALUs + combination LUTs. |
| **Everything-enabled default** | Unsupported (`MODE0_PLANNING.md` §5) | Default `scenarioId=0` does not enable all features at runtime, but **all hardware is compiled into fabric**. No compile-time gating exists for affine, L2/L3, extra raster triggers, or second window. | Compile-time issue. Default bitstream exceeds spec envelope structurally. |

### Features already at spec minimum (no cut available)

- **Sprite system:** 8 descriptors, 8 visible/line — matches spec exactly. Task 57 Path 5A proved this is the floor; descCount=16 failed PnR.
- **Palette:** 128 entries (8 banks × 16) — matches spec.
- **Color math:** `ColorMath()` present. Spec guarantees basic color math. Must stay.
- **Blitter + DMA:** Both present and wired. Spec guarantees basic blitter/transfer. Must stay.
- **Copper + HDMA:** Present. Spec guarantees beam-synchronous automation. Must stay.
- **Core fetch formats:** Tile+attribute, bitmap, planar — all present. Spec guarantees all three. Must stay.
- **Output timing:** 640×480@60 progressive — matches spec.

---

## 2. Smallest Safe Cuts / Gates

Ranked by **estimated logic relief** vs **structural safety** given the fragile Gowin attractor.

### Priority 1 — High impact, high safety

**A. Gate L2 + L3 behind `enableL2L3: Boolean = false`**
- Add constructor param to `VdpTop`; wrap `layer2`/`layer3` instantiation and `layer2Pixel`/`layer3Pixel` muxes in `if (enableL2L3)`.
- When gated off: compositor reduces to L1 > L0 priority; `layer2Opaque`/`layer3Opaque` elaborate to constant `False`; `composedBgSource` codes 2/3 never generated.
- **Estimated savings:** 500–600 LUTs + 10–20 ALUs = **510–620 logic units**.
- **Safety:** HIGH. `BasicPatternSource` is self-contained; no `Mem` inference involved. Same structural pattern as proven-safe `enableL1Fetch` gate (Step 2, fedbb36).
- **Scenario impact:** Only scenarios that write `layerEnableReg` bits [4:3]=1 would be affected. Default `scenarioId=0` uses `0x0001` (L0 only). Audit required for Sc15/16/17/28/etc.

### Priority 2 — Medium impact, high safety

**B. Gate TR1..TR3 behind `enableExtraRasterTriggers: Boolean = false`**
- Keep TR0 with top-level IO surface (backward compat). Wrap TR1..TR3 instantiation + register decode in `if (enableExtraRasterTriggers)`.
- When gated off: `rasterPendingMask` collapses to 1 bit (TR0 only); `evRasterMatch` still fires from TR0.
- **Estimated savings:** 3× `RasterTriggerUnit` + 9×16-bit registers + 4-bit mask aggregation.
- **Safety:** HIGH. `RasterTriggerUnit` is combinational comparator + pending FF. No Mem inference.
- **Scenario impact:** No scenario uses TR1..TR3 at runtime. Sc20/Sc70 configure TR0 via top-level IO.

**C. Gate second `WindowUnit` behind `enableSecondWindow: Boolean = false`**
- Wrap `windowUnit2`, `win2*Reg` decode block, and `combMode` mux in `if (enableSecondWindow)`.
- When gated off: `combinedWindowEffect` collapses to `effect1`; `layerMaskActive` still works with single window.
- **Estimated savings:** ~40+ ALUs (comparators) + combination LUTs.
- **Safety:** HIGH. Pure combinational logic; no Mem inference.
- **Scenario impact:** Scenarios using `WIN2` addresses (`0x0335..0x0339`) would lose second-window behavior. Default Sc0 uses single window.

### Priority 3 — Medium impact, medium safety

**D. Gate affine behind `enableAffine: Boolean = false`**
- Wrap `AffineStepper`, `affineTexture` Mem, affine register block, and L0 source mux branch in `if (enableAffine)`.
- When gated off: L0 source mux loses affine branch (retains tile/bitmap/planar paths).
- **Estimated savings:** Modest LUTs for stepper + parent muxing + 16 Kbit texture storage (BSRAM or ROM16 inference).
- **Safety:** MEDIUM-HIGH. `affineTexture` is `Mem.init` — removing it could perturb Gowin inference. However, it is NOT a load-bearing Mem (unlike `activeListMem` or descriptor storage). No RP0001 precedent for texture-Mem removal.
- **Scenario impact:** Sc12 and Sc37 use affine. They would need `enableAffine=true` in their scenario-top constructors.

### Priority 4 — Low impact, high safety

**E. Reduce `PLANE_COUNT` from 5 → 4 (parameterize)**
- Change hardcoded `PLANE_COUNT = 5` to constructor parameter (default 5, overridable to 4).
- `PlanarLineFetch` already accepts `planeCount` as argument.
- **Estimated savings:** 320 FFs (BitplaneRowFetch.planeWords). Minimal LUT relief.
- **Safety:** HIGH. No Mem inference change; just Vec width reduction.
- **Conformance note:** Required to meet spec guarantee of 4 planes, but does NOT help with the logic-limit gap.

---

## 3. Exact Files / Modules Likely to Change

| File | Lines | Changes |
|---|---|---|
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | ~2245 | Add constructor params (`enableL2L3`, `enableAffine`, `enableExtraRasterTriggers`, `enableSecondWindow`, `planeCount`); wrap: (a) L2/L3 instantiation + compositor inputs, (b) affine block + L0 mux branch, (c) TR1..TR3 block + pending mask width, (d) windowUnit2 + combMode mux, (e) planar depth constant → param |
| `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` | ~2014 | Add matching constructor params; pass to `VdpTop(...)`; update `TopTang20kHdmiVerilog` to generate stripped default with `enableL2L3=false, enableAffine=false, enableExtraRasterTriggers=false, enableSecondWindow=false, planeCount=4`; scenario tops keep defaults for bit-identicalness |
| `hw/gen/top_tang20k.v` | ~32739 | Regenerate after SpinalHDL param changes |
| Sims compiling `VdpTop()` | Multiple | No changes needed if new params have default `true`. Sims using gated features will continue to work unchanged. |
| `PROJECT_PLAN/MODE0_PLANNING.md` | 301 | Update §4/§5 to reflect which features are build-gated in default; update §6 register map if plane base-address range shrinks |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` | N/A | Document gated addresses (affine, TR1..TR3, WIN2) as "optional build only" |

---

## 4. Major Regression Risks

| Risk | Severity | Evidence / Mitigation |
|---|---|---|
| **RP0001 Mem→FF promotion** | **CRITICAL** | Stage 2 SpriteEvaluator wide-output prune (stashed as `stage2-candidate5-failed-mem-promotion`) removed wide Vec outputs from `activeListMem` read ports. Result: **+5554 DFFs** despite identical 59 Mem extractions. Gowin topology is fragile; any structural perturbation that changes combinational fanout of a Mem read port can trigger catastrophic promotion. **Mitigation:** Use compile-time `if (param)` at SpinalHDL level (not runtime mux tie-offs) so synthesis sees true structural absence, not dead code. Never alter `activeListMem` read-port fanout. |
| **L1 fetch re-enable cost** | **HIGH** | Current default is non-conforming because `enableL1Fetch=false`. Re-enabling L1 adds **~150 logic units** (proven at fedbb36 vs 6737bc0). Net savings from all gates must exceed 150 + 57 = **~207 logic units** before L1 can be safely re-enabled. L2/L3 gate alone (510–620 units) covers this with margin. |
| **Scenario bit-identicalness** | **HIGH** | Every scenario bootstrap value for `LAYER_ENABLE`, `AFFINE_CTRL`, `WIN2_CTRL`, and trigger addresses must be audited. Sc15/16/17/28/37/44/50/55/60/62/70 are the highest-touch scenarios. **Mitigation:** Add param pass-through to scenario-specific top objects; only `TopTang20kHdmiVerilog` (default) uses stripped params. |
| **Gowin synthesis timing closure** | **MEDIUM** | Removing logic can alter placement and routing. The baseline `fedbb36` is already at the attractor edge. **Mitigation:** After each param addition, run full synthesis (not just logic count) and verify timing. |
| **Register map semantic shift** | **LOW** | If TR1..TR3 or WIN2 are gated, register writes to `0x0360+` or `0x0335+` have no hardware effect. Acceptable per spec §8 ("optional feature absent = conforming"). Document in register spec. |

---

## 5. Proof / Validation Needed

| Proof | Method | Owner |
|---|---|---|
| **L2/L3 gate synthesis delta** | Run Gowin synthesis on `TopTang20kHdmi(enableL2L3=false)`; compare resource report to `fedbb36` baseline | BrightForge |
| **L1 fetch re-enable fit check** | After all gates landed, re-enable `enableL1Fetch=true` in default build; confirm total logic < 20736 | BrightForge |
| **Bit-identical scenario verification** | Run full capture-diff suite (Sc0, Sc15, Sc16, Sc17, Sc20, Sc28, Sc37, Sc44, Sc50, Sc55, Sc60, Sc62, Sc70) against baseline `c52ed8d` | BronzeGate / BrightForge |
| **Scala simulation pass** | `sbt test` across all Scala sims. New params default to `true`, so existing sims should pass without modification. Add explicit `enableL2L3=false` sim if L2/L3 gate behavior needs coverage. | BrightForge |
| **PnR timing closure** | Confirm stripped build meets 33 MHz pixel clock with all gates active | CyanPeak audit |
| **Register map audit** | CyanPeak verifies no address collision after plane block shrink; confirms gated addresses documented | CyanPeak |
| **Firmware compile check** | Ensure ESP8266 sketches compile; no register map changes affect existing firmware | FoggyWolf |

---

## 6. Recommended Implementation Order

| Step | Action | Rationale | Effort | Gate |
|---|---|---|---|---|
| 1 | **Land `enableL2L3` gate** (default `true`, stripped default `false`) | **Biggest safe win.** 500–600 LUTs relief is ~10× the required 57-logic gap. Uses proven `if(param)` pattern from `enableL1Fetch`. No Mem inference risk. | 1–2 hrs | CyanPeak diff audit on Sc0/Sc15/Sc16 captures |
| 2 | **Land `enableExtraRasterTriggers` gate** (default `true`, stripped default `false`) | **High safety, medium win.** TR1..TR3 are pure logic (no Mem). No scenario uses them. | 1–2 hrs | CyanPeak diff audit |
| 3 | **Land `enableSecondWindow` gate** (default `true`, stripped default `false`) | **High safety, modest win.** Pure combinational logic. | 1–2 hrs | CyanPeak diff audit |
| 4 | **Synthesize stripped default** (L2/L3 off, TR1..TR3 off, second window off) | Measure total logic delta. Target: < 20736 with comfortable margin. If still over limit, proceed to Step 5. | 2–3 hrs | Synthesis report diff |
| 5 | **Land `enableAffine` gate** (default `true`, stripped default `false`) | Medium safety due to `Mem.init` removal. Only needed if Steps 1–3 do not provide enough headroom. | 2–3 hrs | CyanPeak diff audit; verify Sc12/Sc37 still work with `enableAffine=true` |
| 6 | **Re-enable `enableL1Fetch=true`** in stripped default | Required for **spec conformance** (tile+attribute fetch is guaranteed). Only safe once total logic is < 20736 after accounting for +150 L1 cost. | 1 hr | Synthesis report diff |
| 7 | **Parameterize `planeCount`** (default 5, stripped default 4) | Required for **spec conformance** (guaranteed 4 planes). Low logic impact; can land anytime. | 1 hr | CyanPeak diff audit on Sc9/Sc10 planar captures |
| 8 | **Full regression suite** — all scenarios, all sims, synthesis, PnR, OpenCV capture-diff | Final validation before audit sign-off. | 4–8 hrs | CyanPeak PASS/HOLD/FAIL |
| 9 | **Update docs** — `MODE0_PLANNING.md` §4/§5, `MODE0_REGISTER_BUS_SPEC.md` gated addresses | Document which features are build-gated in default vs available in full build. | 1–2 hrs | TopazCliff review (external advisory) |

---

## Bottom Line

The default build is **non-conforming** in two dimensions:
1. **L1 fetch is disabled** (`enableL1Fetch=false`) — violates guaranteed "Tile + attribute fetch" requirement.
2. **Hardware exceeds spec envelope** — affine, L2/L3, extra raster triggers, second window, and 5-plane depth are all compiled into fabric despite being optional.

The **best path** is a **4-gate stripped default**:
1. `enableL2L3 = false` (**largest safe win**, ~500–600 LUTs)
2. `enableExtraRasterTriggers = false` (~3 triggers + registers)
3. `enableSecondWindow = false` (~40+ ALUs + combo LUTs)
4. `enableAffine = false` (if headroom still needed after 1–3)

With **only Step 1 (L2/L3 gate)**, the design drops from 20793 to approximately **20173–20283 logic** (est. 510–620 unit relief). This provides:
- Enough headroom to **re-enable L1 fetch** (+150 logic → ~20323–20433)
- **~300+ logic units of margin** under the 20736 limit
- A **conforming default build** that guarantees 2 strong live BG layers (L0+L1), 8 sprites, 1 raster trigger, 1 window, basic color math, blitter/DMA, and Copper

All other features (L2/L3, affine, extra triggers, second window) remain available via scenario-specific tops or future build flags.

The **highest risk remains RP0001**. The L2/L3 gate is the safest large cut because it touches no Mem inference paths. The affine gate is the riskiest of the four because it removes a `Mem.init`. It should be deferred until after synthesis proves Steps 1–3 are insufficient.

---

*End of corrected analysis packet. No code changes were made in the production of this document.*
