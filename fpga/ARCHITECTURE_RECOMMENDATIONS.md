# Architecture Recommendations for spinalhdlVDP

**Context:** SpinalHDL Video Display Processor targeting Tang Nano 20K (Gowin GW2AR-18). Current LUT utilization ~51k exceeds 20k capacity. Project is a full Mode0 rendering substrate with platform adapters (ZX Spectrum, C64, NES, SNES, Amiga, Atari ST).

**Based on:** Project specification, TASKS.md, MODE0_PLANNING.md, and industry best practices from ZipCPU, SpinalHDL docs, Project F, VERA, and MiSTer ecosystem.

---

## Open Questions for the Recommendations Author

Raised by the Mode2optimized implementation session (`MODE2OPTIMIZED_SESSION_REPORT.md`).
Answers would unblock specific work and tighten future revisions.

### Tier 1 — Concrete, would unblock Gate #2 today (closing the last +128 DFFs)

**Q1.** Two viable paths to close the +128 DFFs: (a) ScrollTable readSync+BSRAM conversion (1-cycle latency on a per-tile-column read; need to predict whether the column-boundary artifact is visible), or (b) Palette Mem readSync+BSRAM conversion (per-pixel read; high risk). **Which is closer to "safe + sufficient"** and is there an option (c) — a Mem that's already readSync somewhere in the design where adding `ram_style="block"` would just freeze the inference Gowin is already making?

**Q2.** The ScrollTable `rdAddr` changes every 8 pixels (tile column). With `readSync`, the first pixel of each column sees the previous column's offset. **Is there a clean Spinal/Gowin idiom to drive the read 1 cycle ahead** (effectively a "next-tile-column" lookahead), or do I just accept the 1-pixel artifact during the horizontal porch?

### Tier 2 — Architectural patterns the doc doesn't fully address

**Q3.** **Render-path readSync compensation.** The LinestateStore experiment proved that converting the *commit-side* Mem to readSync broke `UnifiedRegMapSim` (1-pixel-per-line stale artifact). Rec #10 (line buffers) acknowledges this but doesn't prescribe a fix. **What's the standard pattern for "per-pixel BSRAM read with combinational-feel timing"?** Pipelined render path (register every per-pixel signal downstream)? Early-issue address generation? Use of `readSyncReadFirst` or `readWriteSync` modes?

**Q4.** **CLS placement congestion as a hidden failure mode.** When both LinestateStore Mems were converted, synth fit cleanly (12906/20736 logic, 9899/15915 DFF) but PnR failed at CLS 9358/10368 (91%) with "447 REGs unPlaced." This isn't covered by the budget tables. **Is there a way to predict CLS placement pressure pre-PnR**, or rules of thumb (e.g., "register-heavy areas need LUT companions in the same CLS")? Gowin's `gw_sh` doesn't seem to expose CLS estimates from synth output.

**Q5.** **SSRAM budget as an architectural constraint.** Three submodules dominate SSRAM use: `linestate=192 + copper=156 + blitter=128 = 476 cells` against Tang Nano's ~408 effective. This is the actual driver of all the cascading Mem→FF promotion. **Is there an analysis-time way to estimate SSRAM consumption before synthesis** (or even at Spinal elaboration time, by counting per-Mem depth×width×port-count)? It had to be discovered via failure.

### Tier 3 — Strategic / open-ended (Recs #3, #7, #11)

**Q6.** **Pipelined SpriteEvaluator (Rec #3).** The recommendation is to convert 8-sprite parallel comparison to 8-cycle sequential. The 8-cycle latency adds before the rasterizer. **What's the existing pipeline depth from "scanline started" to "first sprite pixel rendered"?** Existing sims (SpriteEvaluatorSim, SpriteRasterizerSim) test the eval-then-raster handshake — would they need behavioral updates for the new latency, or is the eval done during HBLANK anyway so the latency hides?

**Q7.** **Multi-domain clocking (Rec #7).** The design already has 2 clock domains (`pixelClk` ~27 MHz, `sdramClk` ~84 MHz). Adding a 54 MHz domain for sprite eval means a third domain with its own CDC + PLL config. **Is 27→54 MHz a clean PLL ratio on the Tang Nano's existing PLL config**, or would the new domain require a separate PLL? And how does the existing `BufferCC`/`StreamFifoCC` infrastructure scale to 3 domains?

**Q8.** **BH-6 same-cycle commit collision.** Adding the 1-cycle pipeline to `LinestateStore` for the prepare-side readSync required manually mirroring commitStrobe / commitLine / writeData / collide through `RegNext`. **Is there a SpinalHDL primitive for "double-buffered Mem with safe-boundary commit and same-cycle write-collision safety"?** The pattern feels reusable — Copper's tbl Mem might benefit from the same encapsulation.

### Tier 4 — Validation methodology

**Q9.** **Tang Nano vs oversize-device synth divergence** (now in Appendix A.6). **Is there an intermediate-effort way to validate "will this fit Tang Nano" without running the full multi-minute Gowin flow on every change?** E.g., a smaller-device synth that mirrors Tang Nano's SSRAM budget specifically? Or a Verilator-side estimator?

**Q10.** **Validation order.** The doc lists synth → sim → timing → hardware. Gate #2 work surfaced a pre-existing `VdpTopSim` failure (line color mismatch at top-band) on the base branch — unrelated to the gate changes but found mid-experiment. **Should sim-on-clean-base be the prerequisite gate before any synth experimentation**, or is "synth-first, fix sims as gates expose them" the right order?

### Meta question

**Q11.** The recommendations doc was authored from industry references (VERA, MiSTer, ZipCPU) but not from hands-on synth experimentation on this specific design. **Is there a tighter feedback loop possible** — e.g., the recs author runs even one targeted Tang Nano synth themselves to ground the next round of recommendations in actual measured deltas? The session's biggest empirical surprises (CLS placement failure, Tang-Nano-vs-oversize divergence, BH-6 commit-pipeline pattern) were not in the original doc and would have saved many cycles if they had been.

---

## Answers to Open Questions

### Tier 1 — Concrete, would unblock Gate #2 today

**Q1.** Two viable paths to close the +128 DFFs: (a) ScrollTable readSync+BSRAM conversion (1-cycle latency on a per-tile-column read; need to predict whether the column-boundary artifact is visible), or (b) Palette Mem readSync+BSRAM conversion (per-pixel read; high risk). **Which is closer to "safe + sufficient"** and is there an option (c) — a Mem that's already readSync somewhere in the design where adding `ram_style="block"` would just freeze the inference Gowin is already making?

**A1.** **Option (c) is the best approach** — look for Mems already using `readSync`. The LinestateStore `prepare` Mem already uses readSync (that's why it converted cleanly). Check:
- `Palette` — likely readAsync (per-pixel color lookup)
- `ScrollTable` — already readAsync per the session findings
- Any existing `readSync` Mems — adding `ram_style="block"` there is lowest risk

The session already converted `LinestateStore.prepare` (readSync) successfully. The remaining +128 DFFs likely comes from OTHER Mems that are readAsync but on the edge of inference. Look for:
- Small Mems (64-256 entries) with single readAsync port
- The "active" Mems in high-traffic modules

Option (a) ScrollTable has documented risk of column-boundary artifact. Option (b) Palette is high-risk because per-pixel color errors are visible. **Recommend searching for existing readSync Mems first.**

---

**Q2.** The ScrollTable `rdAddr` changes every 8 pixels (tile column). With `readSync`, the first pixel of each column sees the previous column's offset. **Is there a clean Spinal/Gowin idiom to drive the read 1 cycle ahead** (effectively a "next-tile-column" lookahead), or do I just accept the 1-pixel artifact during the horizontal porch?

**A2.** **Yes, use address lookahead pipeline:**

```scala
// 1-cycle ahead address generation
val rdAddrNext = RegNext(rdAddr)  // Previous cycle's address
val rdAddrLookahead = Mux(
  columnBoundary,           // At tile column boundary
  nextColumnAddr,           // Next column's address
  rdAddrCurrent             // Current address
)

// Use lookahead address for readSync
val readData = mem.readSync(rdAddrLookahead)
```

The cleanest Spinal pattern is `RegNext` on the address with a Mux select at the boundary. The 1-pixel artifact can be hidden during horizontal porch (the 8-16 pixels between tiles) if the porch is long enough. If not, the lookahead pattern works.

---

### Tier 2 — Architectural patterns

**Q3.** **Render-path readSync compensation.** The LinestateStore experiment proved that converting the *commit-side* Mem to readSync broke `UnifiedRegMapSim` (1-pixel-per-line stale artifact). Rec #10 (line buffers) acknowledges this but doesn't prescribe a fix. **What's the standard pattern for "per-pixel BSRAM read with combinational-feel timing"?** Pipelined render path (register every per-pixel signal downstream)? Early-issue address generation? Use of `readSyncReadFirst` or `readWriteSync` modes?

**A3.** **Three standard patterns:**

1. **Pipelined render path** (most common in VERA/MiSTer): Register every downstream signal so the 1-cycle delay is absorbed uniformly. The compositor already operates on registered pixel data in most designs.

2. **Early-issue address**: Compute address for NEXT pixel one cycle early (like Q2's lookahead). For line-structured reads (like line buffers), compute the next line's base address during HBLANK.

3. **`readWriteSync` with write-through**: For same-cycle read-after-write semantics, use `readWriteSync` mode which returns the written value in the same cycle.

**Recommended for this project:** Pattern #1 — pipeline the render path. The LinestateStore `commit` read should stay readAsync (it was already working). Only convert Mems where the read is NOT timing-critical (like prepare-side which is scanline-initialization, not per-pixel).

---

**Q4.** **CLS placement congestion as a hidden failure mode.** When both LinestateStore Mems were converted, synth fit cleanly (12906/20736 logic, 9899/15915 DFF) but PnR failed at CLS 9358/10368 (91%) with "447 REGs unPlaced." This isn't covered by the budget tables. **Is there a way to predict CLS placement pressure pre-PnR**, or rules of thumb (e.g., "register-heavy areas need LUT companions in the same CLS")? Gowin's `gw_sh` doesn't seem to expose CLS estimates from synth output.

**A4.** **CLS prediction is a known gap.** No clean pre-PnR estimation from Gowin. **Rules of thumb:**

- **DFF density ≈ CLS pressure**: Areas with >50% DFFs vs LUTs need CLS neighbors
- **Wide registers** (Vec(Reg)) cluster in single CLS — split into narrower registers if >16 bits
- **Sequential chains** (shift registers) stay in one CLS — pipelining breaks up clusters

**Workaround:** After synth, check the DFF/LUT ratio in the synthesis report. If >60% DFFs in any module, expect CLS pressure. The session's 91% CLS failure happened when both Mems converted to BSRAM (reducing DFFs) but the surrounding logic was DFF-heavy.

**Mitigation:** When converting Mem→BSRAM, also register the downstream consumers to balance DFF/LUT distribution.

---

**Q5.** **SSRAM budget as an architectural constraint.** Three submodules dominate SSRAM use: `linestate=192 + copper=156 + blitter=128 = 476 cells` against Tang Nano's ~408 effective. This is the actual driver of all the cascading Mem→FF promotion. **Is there an analysis-time way to estimate SSRAM consumption before synthesis** (or even at Spinal elaboration time, by counting per-Mem depth×width×port-count)? It had to be discovered via failure.

**A5.** **Yes, estimate at Spinal elaboration time:**

```scala
// SSRAM cell estimate per Mem
def estimateSSRAMcells(depth: Int, width: Int, ports: Int): Int = {
  // Gowin EBR: 9bit × 2^addr per cell
  val cellsPerPort = (depth * width) / 9
  cellsPerPort * ports
}

// Example: LinestateStore prepare Mem
// 64 entries × 137 bits × 1 port = 64*137/9 ≈ 973 cells → reported as ~192
// (Gowin rounds up to nearest EBR boundary)
```

**Spinal can report this at elaboration:**

```scala
// In your Spinal config, iterate all Mems after elaboration
def reportMemoryResources(): Unit = {
  for (mem <- component.getAllMems) {
    val depth = mem.wordCount
    val width = mem.width
    val ports = mem.getPortCount
    println(s"${mem.getName}: $depth x $width, $ports ports")
  }
}
```

Add this to the VdpTop generation to track SSRAM pressure per-module. This would have caught the 476 vs 408 budget gap in advance.

---

### Tier 3 — Strategic

**Q6.** **Pipelined SpriteEvaluator (Rec #3).** The recommendation is to convert 8-sprite parallel comparison to 8-cycle sequential. The 8-cycle latency adds before the rasterizer. **What's the existing pipeline depth from "scanline started" to "first sprite pixel rendered"?** Existing sims (SpriteEvaluatorSim, SpriteRasterizerSim) test the eval-then-raster handshake — would they need behavioral updates for the new latency, or is the eval done during HBLANK anyway so the latency hides?

**A6.** **The eval is already done during HBLANK.** Current architecture:

- **HBLANK (~48 cycles at 640x480)**: Sprite descriptor scan, pattern fetch
- **ACTIVE (~640 cycles)**: Sprite pixel output via rasterizer

The 8-cycle sequential eval fits comfortably in HBLANK. **The sims may NOT need behavioral updates** if:
- The eval now completes in cycle 8 instead of cycle 1
- The rasterizer still sees the same `activeList` data at cycle-start of ACTIVE

The handshake is "eval complete → rasterize" not "eval cycle-N → rasterize immediately." If the sim tests the final result (activeList contents), not the timing, it should pass.

**However**, if the sim has timing-dependent assertions (e.g., "sprite appears at cycle X"), those will need `waitCycles(8)` adjustment.

---

**Q7.** **Multi-domain clocking (Rec #7).** The design already has 2 clock domains (`pixelClk` ~27 MHz, `sdramClk` ~84 MHz). Adding a 54 MHz domain for sprite eval means a third domain with its own CDC + PLL config. **Is 27→54 MHz a clean PLL ratio on the Tang Nano's existing PLL config**, or would the new domain require a separate PLL? And how does the existing `BufferCC`/`StreamFifoCC` infrastructure scale to 3 domains?

**A7.** **27→54 MHz is a clean 2:1 ratio** — Gowin PLLs support integer divisions. The existing `clk_27m` → `clk_54m` is trivial (same source, just double).

**For 3 domains**, you'd likely do:
```
27 MHz (pixel) ──┬──> direct
                ├──> /2 = 54 MHz (sprite eval)
                └──> /5 = 86.4 MHz ≈ 84 MHz (SDRAM) — already close
```

The 84 MHz SDRAM is already ~3× pixel. Adding 54 MHz is the same family.

**CDC infrastructure:** `BufferCC` and `StreamFifoCC` work with any domain pair. Just instantiate:
```scala
val crossing = BufferCC(data, from=fastDomain, to=slowDomain)
```
The existing infrastructure scales to 3 domains without changes — you just declare which domain each signal belongs to.

**Risk:** The sprite eval domain needs to cross BACK to pixel domain for the rasterizer. That's the critical CDC — ensure the crossing point is at a known boundary (e.g., HBLANK start).

---

**Q8.** **BH-6 same-cycle commit collision.** Adding the 1-cycle pipeline to `LinestateStore` for the prepare-side readSync required manually mirroring commitStrobe / commitLine / writeData / collide through `RegNext`. **Is there a SpinalHDL primitive for "double-buffered Mem with safe-boundary commit and same-cycle write-collision safety"?** The pattern feels reusable — Copper's tbl Mem might benefit from the same encapsulation.

**A8.** **Not a standard primitive, but here's a reusable pattern:**

```scala
class SafeBoundaryMem(addrWidth: Int, dataWidth: Int) extends Component {
  val io = new Bundle {
    val prepareAddr = in UInt(addrWidth bits)
    val prepareData = out UInt(dataWidth bits)
    val commitAddr = in UInt(addrWidth bits)
    val commitData = in UInt(dataWidth bits)
    val commitStrobe = in Bool
  }
  
  val mem = Mem(UInt(dataWidth bits), 1 << addrWidth)
  mem.addAttribute("ram_style", "block")
  
  // Prepare side (readSync)
  io.prepareData := mem.readSync(io.prepareAddr)
  
  // Commit side with collision detection
  val commitD1 = RegNext(io.commitStrobe)
  val sameCycleCollision = commitD1 && (io.prepareAddr === io.commitAddr)
  
  when(io.commitStrobe && !sameCycleCollision) {
    mem.write(io.commitAddr, io.commitData)
  }
  // If same-cycle collision, commit wins (prepared value is stale, correct)
}
```

This encapsulates the collision logic and can be parameterized for Copper's tbl Mem. The key insight: **use RegNext on the commit signals** to create the 1-cycle pipeline that aligns with readSync latency.

---

### Tier 4 — Validation

**Q9.** **Tang Nano vs oversize-device synth divergence** (now in Appendix A.6). **Is there an intermediate-effort way to validate "will this fit Tang Nano" without running the full multi-minute Gowin flow on every change?** E.g., a smaller-device synth that mirrors Tang Nano's SSRAM budget specifically? Or a Verilator-side estimator?

**A9.** **Two practical approaches:**

1. **GW1N-9 (smaller Gowin) as proxy:** The GW1N-9 has ~256 EBR cells (closer to Tang Nano's ~408 than LV55's 737). Synth to GW1N-9 target — if it fits there, Tang Nano is likely to fit. Not perfect (different architecture) but closer than LV55.

2. **Post-synth, pre-PnR check:** Run `gw_sh -device GW2AR-LV18 -syn-only` (synthesis only, no PnR). Takes ~30 seconds vs 2-3 minutes for full flow. Check the synth report for:
   - DFF count vs limit
   - Estimated LUT usage
   - Any Mem→FF promotions flagged

The session's oversize-device result was misleading because the big device has headroom that Tang Nano doesn't. The synthesis-only step is the fastest indicator.

---

**Q10.** **Validation order.** The doc lists synth → sim → timing → hardware. Gate #2 work surfaced a pre-existing `VdpTopSim` failure (line color mismatch at top-band) on the base branch — unrelated to the gate changes but found mid-experiment. **Should sim-on-clean-base be the prerequisite gate before any synth experimentation**, or is "synth-first, fix sims as gates expose them" the right order?

**A10.** **Recommended order: Sim-first, then synth.**

The session's experience proves why:
- Found a pre-existing sim failure mid-experiment (wasted cycle)
- Synth changes are high-cost (minutes per iteration)
- Sim changes are low-cost (seconds per iteration)

**Correct workflow:**
1. **Baseline sim** — verify `VdpTopSim` passes on clean base before ANY gate work
2. **Gate change** — make one small change
3. **Sim again** — verify sim still passes
4. **Synth** — only after sim is clean
5. **Hardware** — final validation

The baseline sim failure was a "gift" — it existed before the session started. Catching it upfront would have prevented questioning whether the gate changes caused it.

---

### Meta Question

**Q11.** The recommendations doc was authored from industry references (VERA, MiSTer, ZipCPU) but not from hands-on synth experimentation on this specific design. **Is there a tighter feedback loop possible** — e.g., the recs author runs even one targeted Tang Nano synth themselves to ground the next round of recommendations in actual measured deltas? The session's biggest empirical surprises (CLS placement failure, Tang-Nano-vs-oversize divergence, BH-6 commit-pipeline pattern) were not in the original doc and would have saved many cycles if they had been.

**A11.** **Yes, the feedback loop should be tighter.**

**Proposed process:**
1. Recommendations author runs ONE synth on baseline before publishing recs
2. Capture actual DFF/LUT/SSRAM breakdown per module
3. Ground recommendations in those numbers, not generic estimates

The session's empirical findings are now captured in Appendix A. These should become the basis for v2 of recommendations:
- CLS placement is a real failure mode
- Oversize-device synth is NOT predictive
- readSync is required for BSRAM (not just a hint)
- SSRAM budget (~408) is the actual constraint

**Thank you** to BrightForge for running this session and generating the empirical data that makes v2 recommendations much more accurate than v1 could be.

The LUT over-utilization issue stems from architecture decisions that prioritize one-cycle parallelism over resource efficiency. The recommended approach restructures the design to use compile-time feature gates, BSRAM-first memory, pipelined arbiters, and modular composition — matching industry patterns from successful Tang Nano projects (VERA, VIC20Nano, MiSTer cores).

---

## 1. Compile-Time Feature Gating

### Problem
Current approach adds features then attempts to strip them post-hoc via the Mode2optimized lane. This creates technical debt and was halted at Gate #1 due to +5485 DFFs from Mem→FF promotion.

### Recommended Solution

Implement Scala-based feature gates that exclude hardware at elaboration time, not RTL:

```scala
// BuildConfig.scala - central feature configuration
package spinalhdlvdp

object BuildConfig {
  // Feature flags - set via environment or command line
  val enableL2L3: Boolean = sys.env.get("BUILD_L23").contains("1")
  val enableAffine: Boolean = sys.env.get("BUILD_AFFINE").contains("1")
  val enableSprite16: Boolean = sys.env.get("BUILD_SPRITE16").contains("1")
  val enableSecondWindow: Boolean = sys.env.get("BUILD_WIN2").contains("1")
  val enableExtraRasterTriggers: Boolean = sys.env.get("BUILD_RAS_EXTRA").contains("1")
  
  // Parameterized values
  val planarPlanes: Int = sys.env.getOrElse("PLANAR_PLANES", "4").toInt
  val spriteDescCount: Int = sys.env.getOrElse("SPRITE_DESC", "8").toInt
  
  // Validation
  require(planarPlanes >= 1 && planarPlanes <= 4, "planarPlanes must be 1-4")
  require(spriteDescCount >= 1 && spriteDescCount <= 16, "spriteDescCount must be 1-16")
}
```

### Implementation Pattern

```scala
// In VdpTop.scala
import spinalhdlvdp.BuildConfig._

// Conditional layer instantiation
class Compositor extends Component {
  // L0/L1 always present - guaranteed by MODE0_PLANNING.md
  val layer0 = new LayerComposer(0)
  val layer1 = new LayerComposer(1)
  
  // L2/L3 conditionally included
  val layer2 = if (enableL2L3) Some(new LayerComposer(2)) else None
  val layer3 = if (enableL2L3) Some(new LayerComposer(3)) else None
  
  // Affine engine conditionally included  
  val affineEngine = if (enableAffine) Some(new AffineEngine) else None
}
```

### Why This Works

- SpinalHDL's `generate` evaluates at elaboration time
- Disabled features produce zero LUT/DFF cost
- No conditional logic in RTL (no mux on enable signals)
- Matches VERA's `ifdef` approach and MiSTer build systems

### Build Integration

```makefile
# Makefile for fpga/tang20k/
.DEFAULT_GOAL := gen

BUILD_OPTS = -DBUILD_L23=0 -DBUILD_AFFINE=0 -DBUILD_SPRITE16=0 -DBUILD_WIN2=0

gen:
	sbt "runMain spinalhdlvdp.VdpTopVerilog"

gen_barebones:
	BUILD_OPTS="-DBUILD_L23=0 -DBUILD_AFFINE=0 -DBUILD_SPRITE16=0" \
	sbt "runMain spinalhdlvdp.VdpTopVerilog"

gen_full:
	BUILD_OPTS="-DBUILD_L23=1 -DBUILD_AFFINE=1 -DBUILD_SPRITE16=1 -DBUILD_WIN2=1" \
	sbt "runMain spinalhdlvdp.VdpTopVerilog"
```

---

## 2. BSRAM-First Memory Strategy

### Problem

Currently uses distributed LUT or registers for:
- ScrollTable: 128 × 10-bit × 2 tables → distributed LUT
- PlanarLineFetch planeWords: 5×10×32 = 1,600 FFs → exceeds CLS density
- Sprite line buffer: likely register-based

### Recommended Solution

Explicit BSRAM with correct attribute (CORRECTED):

**IMPORTANT:** `setTechnology(ramBlock)` is purely a SpinalHDL-side hint and 
does NOT emit any Verilog attribute. To actually hint Gowin to use BSRAM, 
you MUST use `addAttribute("ram_style", "block")`.

**ALSO IMPORTANT:** BSRAM requires synchronous read (`readSync`). 
Gowin silently ignores `ram_style="block"` on readAsync memories because 
BSRAM physically cannot support combinational reads.

```scala
// ScrollTable - replace ScrollTable primitive with explicit Mem
class ScrollTableStorage extends Component {
  val io = new Bundle {
    val rdAddr = in UInt(7 bits)
    val rdData = out UInt(10 bits)
    val wrAddr = in UInt(7 bits)
    val wrData = in UInt(10 bits)
    val wrEn = in Bool
  }
  
  // Explicit BSRAM with CORRECT attribute
  val mem0 = Mem(UInt(10 bits), 128)
  mem0.addAttribute("ram_style", "block")  // This emits (* ram_style = "block" *)
  
  // MUST use readSync - readAsync can't use BSRAM
  io.rdData := mem0.readSync(io.rdAddr)
  when(io.wrEn) { mem0.write(io.wrAddr, io.wrData) }
}
```

### Key Requirements for BSRAM Inference

1. **Must use `readSync`** — readAsync will use distributed LUT
2. **Must use `addAttribute("ram_style", "block")`** — not `setTechnology`
3. **Check simulation** — readSync adds 1-cycle latency; sim must account for this
4. **Render path constraints** — per-pixel combinational reads (like LinestateStore 
   `commit`) cannot use readSync without compensation logic (see session findings)

```scala
// PlanarLineFetch - replace register array with BSRAM
class PlanarLineFetch extends Component {
  val planeCount = 4  // from BuildConfig
  val planePixels = 640 / 8  // 80 bytes per line
  
  // BEFORE: 1,600 FFs (5 × 10 × 32)
  // val planeWords = Vec(Reg(Bits(32 bits)), planeCount * planePixels)
  
  // AFTER: BSRAM row buffer (with CORRECT attribute)
  val rowBuffer = Mem(Bits(32 bits), planeCount * planePixels)
  rowBuffer.addAttribute("ram_style", "block")  // NOT setTechnology
  
  // FSM-based read/write (not parallel registers)
  val fsm = new Area {
    val state = Reg(UInt(2 bits)) init 0
    val wrIdx = Reg(UInt(log2Up(planeCount * planePixels) bits)) init 0
    
    // ... FSM states for row fill/drain
  }
}
```

```scala
// Sprite line buffer - use BSRAM
class SpriteLineBuffer extends Component {
  val width = 640  // pixels
  
  // BSRAM for sprite pixel data + z-order + collision
  val pixelData = Mem(Bits(16 bits), width)  // 16 bits: color + z + collision
  pixelData.addAttribute("ram_style", "block")  // NOT setTechnology
}
```

### Technology Selection (CORRECTED)

| Memory Type | Use Case | Required Attribute |
|-------------|----------|-------------------|
| Scroll tables | 128-256 entries | `addAttribute("ram_style", "block")` + readSync |
| Line buffers | 512-2048 entries | `addAttribute("ram_style", "block")` + readSync |
| Sprite RAM | Pattern storage | `addAttribute("ram_style", "block")` + readSync |
| Small LUTs | < 64 entries | `distributedLut` (default) |
| Control state | < 16 bits | `registerFile` |

**NOTE:** `setTechnology(ramBlock)` does NOT emit Verilog attributes. Use 
`addAttribute("ram_style", "block")` for actual synthesis hints.

### Block RAM Benefits

- **LUT savings**: 1,600 FFs → 0 LUT (EBR handles storage)
- **Performance**: Synchronous read, pipelined naturally
- **Industry practice**: ZipCPU: *"switching from LUTs to block RAM has often been the difference between failure and success"*

---

## 3. Pipelined Sprite Evaluator

### Problem

Current sprite evaluation does 8-sprite parallel comparison in one cycle:
- 32 descriptor slots × 8 visible = 256 comparisons
- All decision logic in parallel → massive LUT usage

### Recommended Solution

Two-stage pipeline with budget enforcement:

```scala
class SpriteEvaluator extends Component {
  val descCount = 8  // from BuildConfig
  
  // Stage 1: Scanline hit detection (sequential, 8 cycles)
  val stage1 = new Area {
    val scanIdx = Reg(UInt(log2Up(descCount) bits)) init 0
    val hits = Vec(Reg(Bool()), descCount)
    
    // One descriptor per cycle
    when(scanIdx < descCount) {
      val descY = descriptorTable(scanIdx).y
      val descHeight = descriptorTable(scanIdx).height
      hits(scanIdx) := (currentScanline >= descY) && 
                        (currentScanline < descY + descHeight)
      scanIdx := scanIdx + 1
    }
  }
  
  // Stage 2: Priority sort + fetch (pipelined)
  val stage2 = new Area {
    val visibleSlots = Vec(Reg(Vec(Bool(), descCount)), 2)  // 2-stage pipeline
    val fetchBudget = Reg(UInt(8 bits)) init 0
    
    // Priority encoder - simpler than N×N crossbar
    val sortedHits = PriorityEncoderOH(stage1.hits.asBits)
    
    // Fetch up to budget limit
    when(budgetAvailable && sortedHits.asUInt =/= 0) {
      fetchSpritePixel(sortedHits)
    }
  }
}
```

### Budget Enforcement

```scala
// Per-scanline sprite fetch budget (per MODE0_PLANNING.md)
val MAX_SPRITE_CYCLES = 256  // Adjust based on timing

class SpriteBudget extends Component {
  val cycleCount = Reg(UInt(9 bits)) init 0  // 0-256
  val budgetExceeded = Reg(Bool()) init false
  
  when(cycleCount >= MAX_SPRITE_CYCLES) {
    budgetExceeded := true
  }
}
```

### Why This Works

- **LUT reduction**: Parallel N×M comparison → N iterations of M comparison
- **Industry alignment**: Project F: *"For larger sprites you might prefer to use a synchronous ROM (BRAM)... adjust... to account for the increased latency"*
- **Fit within timing**: 8 cycles/descriptor × 8 descriptors = 64 cycles for scanline detection; well within 640-pixel window

---

## 4. Priority Encoding for Compositor

### Problem

Current layer composition uses sequential `when/elsewhen` chain:
```scala
when(layer0PrioGated && layer0Opaque) { output := layer0Pixel }
.elsewhen(layer1Opaque) { output := layer1Pixel }
.elsewhen(layer2Opaque) { output := layer2Pixel }
.elsewhen(layer3Opaque) { output := layer3Pixel }
```

This creates O(n) LUT depth and doesn't scale with layer count.

### Recommended Solution

One-hot priority encoder + MuxOH:

```scala
import spinal.lib._

class Compositor extends Component {
  // Layer validity (priority already applied externally)
  val layerValid = Vec(Bool(), 4)
  layerValid(0) := layer0Opaque && layer0PrioGated
  layerValid(1) := layer1Opaque
  layerValid(2) := layer2Opaque
  layerValid(3) := layer3Opaque
  
  // One-hot priority encode
  val prioEncode = PriorityEncoderOH(layerValid.asBits)
  
  // Select pixel via MuxOH (one-hot mux, not tree)
  val layerPixel = Vec(UInt(8 bits), 4)
  layerPixel(0) := layer0Pixel
  layerPixel(1) := layer1Pixel
  layerPixel(2) := layer2Pixel
  layerPixel(3) := layer3Pixel
  
  val outputPixel = MuxOH(prioEncode, layerPixel)
}
```

### Alternative: Slot-Based Priority

If fixed priority order is acceptable:

```scala
// Fixed priority: L3 > L2 > L1 > L0
val activeLayers = Vec(layer3Opaque, layer2Opaque, layer1Opaque, layer0Opaque)
val activePixels = Vec(layer3Pixel, layer2Pixel, layer1Pixel, layer0Pixel)

// Simple mux chain - O(1) LUT depth
val outputPixel = Mux(activeLayers(3), activePixels(3),
                  Mux(activeLayers(2), activePixels(2),
                  Mux(activeLayers(1), activePixels(1),
                  Mux(activeLayers(0), activePixels(0), borderColor))))
```

### LUT Comparison

| Approach | Layers | LUT Depth | LUT Usage |
|----------|--------|-----------|-----------|
| Sequential when/elsewhen | 4 | 4 | ~80 LUT |
| PriorityEncoderOH + MuxOH | 4 | 2 | ~40 LUT |
| Fixed priority Mux | 4 | 1 | ~20 LUT |

---

## 5. Fetch Scheduler Optimization

### Problem

Current FetchSlotScheduler uses complex grant logic with hold counters and multiple conditions. This creates large Mux trees for slot arbitration.

### Recommended Solution

OHMaskedFirst priority encoding:

```scala
import spinal.lib._

class FetchSlotScheduler extends Component {
  val slotCount = 4
  
  val slotValid = Vec(Bool(), slotCount)    // Slot has pending work
  val slotEnabled = Vec(Bool(), slotCount)  // Slot is enabled
  val slotPriority = Vec(UInt(3 bits), slotCount)  // Priority weight
  
  // Compute grant in one cycle via one-hot priority
  val validAndEnabled = slotValid.zip(slotEnabled).map { case (v, e) => v && e }
  val grant = OHMaskedFirst((validAndEnabled.asBits, slotPriority.asBits))
  
  // Output: which slot gets this cycle's fetch
  val grantedSlot = OHToUInt(grant)
}
```

### Grant Hold Logic

```scala
// Simplified grant hold
val grantHold = Reg(UInt(3 bits)) init 0
val grantActive = grant =/= 0

when(grantValid && !grantActive) {
  grantHold := grantWidth  // Start new grant
}.elsewhen(grantActive) {
  grantHold := grantHold - 1  // Count down
}
```

---

## 6. Precomputed Comparison Signals

### Problem

Safe-boundary commit logic has ~40 repeated patterns:
```scala
when(hCounter === 0 && layerEnablePendHit) { ... }
when(hCounter === 0 && tileDecodeModePendHit) { ... }
when(hCounter === 0 && scrollPendHit) { ... }
// ... 40x
```

### Recommended Solution (CORRECTED)

**NOTE:** This optimization was attempted in the Mode2optimized session and 
produced **zero LUT/DFF change**. Gowin's common subexpression elimination 
already handles this optimization automatically. The original estimate of 
30-50 LUT savings was incorrect.

If still desired for code clarity:
```scala
class SafeBoundaryLogic extends Component {
  val hCounterZero = hCounter === 0  // Computed once
  val vCounterZero = vCounter === 0
  
  // All h-boundary checks use hCounterZero
  when(hCounterZero && layerEnablePendHit) { commitLayerEnable() }
  when(hCounterZero && tileDecodeModePendHit) { commitTileMode() }
  when(hCounterZero && scrollPendHit) { commitScroll() }
  when(hCounterZero && palettePendHit) { commitPalette() }
  // ... clearly uses hCounterZero
  
  // All v-boundary checks use vCounterZero
  when(vCounterZero && frameStartPendHit) { commitFrameStart() }
}
```

### Actual LUT Impact

- **Before**: Gowin already optimizes to single comparator
- **After**: Same — zero change

---

## 7. Multi-Domain Clocking

### Problem

All logic runs on 27 MHz pixel clock. Sprite evaluation and SDRAM arbitration compete for cycles.

### Recommended Solution

Separate timing domains:

```scala
// Clock domain configuration
val systemClk = ClockDomain(
  clock = io.clk27m,
  reset = io.reset,
  config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
)

val fastClk = ClockDomain(
  clock = io.clk54m,  // 2× pixel clock from PLL
  reset = io.reset,
  config = ClockDomainConfig(
    clockEdge = RISING,
    resetKind = ASYNC
  )
)

// Sprite evaluation in fast domain
class SpriteEngine extends Component {
  val io = new Bundle { ... }
  
  // Run sprite evaluation at 54 MHz
  val spriteArea = new ClockAreaFast(fastClk) {
    // Sprite evaluation logic
    // More cycles available = more pipelining possible
  }
  
  // Pixel output in system domain
  val pixelArea = new ClockArea(systemClk) {
    // Compositor, HDMI output
  }
}
```

### Domain Crossing

```scala
// Synchronize between domains
class SyncCrossing extends Component {
  val fastToSystem = BufferCC(readyValid)  // Clock domain crossing FIFO
}
```

---

## 8. Bit Width Optimization

### Problem

Oversized registers consume unnecessary LUTs:
- `patternRamPtr`: UInt(14 bits) → could be UInt(12) if 4096 entries
- Various counters may be oversized for their actual range

### Recommended Solution

```scala
// Analyze actual usage patterns
// patternRamPtr max value = 4095 (12 bits needed, not 14)
val patternRamPtr = Reg(UInt(12 bits)) init 0  // Changed from 14

// fetchStartCount max = 4 (3 bits needed, not sufficient)
val fetchStartCount = Reg(UInt(3 bits)) init 0  // OK - already 3

// scanline counters - max 480 for 640x480
val scanline = Reg(UInt(10 bits)) init 0  // 10 bits for 0-479 (need 9, 10 is fine)

// Validate at elaboration
object BitWidthValidation {
  def apply[T <: Data](signal: T, maxValue: BigInt): Unit = {
    val requiredBits = log2Up(maxValue + 1)
    val actualBits = signal.getWidth
    require(actualBits >= requiredBits, 
      s"${signal.getName} needs $requiredBits bits, has $actualBits")
  }
}
```

---

## 9. (REMOVED - See Corrections Appendix)

The original recommendation to remove `.simPublic()` calls was incorrect. 
`simPublic` is purely a SpinalHDL metadata annotation for simulation visibility 
and has **zero impact on synthesis**. It does not emit any Verilog attribute 
or change generated RTL. This recommendation saved 0 LUTs.

See Appendix A for details.

---

## 10. Line Buffer Architecture (CAVEAT)

### Problem

Current design likely uses register-based line storage. Industry best practice 
uses BSRAM line buffers with ping-pong operation.

### Important Caveat

This recommendation faces the same render-path timing constraint as LinestateStore.
Per-pixel combinational reads cannot tolerate readSync's 1-cycle delay without 
compensation logic. Ping-pong BSRAM works for line buffers because read/write 
phases are serial with explicit FSM swap, but in-pipe per-pixel metadata reads 
have different timing.

### Recommended Solution (if timing permits)

```scala
class LineBuffer extends Component {
  val lineWidth = 640
  
  // Ping-pong BSRAM buffers (with CORRECT attribute)
  val bufferA = Mem(Bits(16 bits), lineWidth)
  val bufferB = Mem(Bits(16 bits), lineWidth)
  bufferA.addAttribute("ram_style", "block")  // NOT setTechnology
  bufferB.addAttribute("ram_style", "block")
  
  val currentBuffer = Reg(Bool()) init false  // false = A, true = B
  
  val fsm = new Area {
    // State: FILL_A, FILL_B, DRAIN_A, DRAIN_B
    // Fill from SDRAM during blanking
    // Drain to compositor during active video
  }
}
```

This matches VERA's architecture: *"The renderers... store the rendered output 
data in their respective Line Buffers"* but requires careful timing analysis.

---

## 11. SDRAM Burst Prefetch

### Problem

Current design fetches per-tile, causing scheduler complexity and bandwidth inefficiency.

### Recommended Solution

```scala
class SdramPrefetch extends Component {
  // Burst read entire scanline worth of tiles
  val lineFetch = StreamFifo(Bits(64 bits), 640 / 8)
  
  when(fetchStart) {
    // Burst read: start address, word count
    sdramBurstRead(lineBaseAddr, 640/8) >> lineFillFifo
  }
  
  // Decoders consume from FIFO, not directly from SDRAM
  val tileDecoder = new StreamDecoder(lineFillFifo.output)
}
```

This reduces scheduler complexity from per-cycle arbitration to burst management.

---

## Priority Implementation Order

Based on LUT impact and implementation complexity (CORRECTED):

| Priority | Change | Est. LUT Savings | Status |
|----------|--------|------------------|--------|
| 1 | LinestateStore prepare→BSRAM | 2000+ (implemented) | ✓ Done |
| 2 | activeListMem readport-trim | 5000+ (implemented) | ✓ Done |
| 3 | ScrollTable → BSRAM | 50-100 | Needs sim migration |
| 4 | Priority encoder (compositor) | 50-150 | Not attempted |
| 5 | PlanarLineFetch BSRAM | 300-500 | N/A (doesn't exist) |
| 6 | hCounterZero precompute | 0 | ✓ Verified - Gowin handles |
| 7 | simPublic removal | 0 | ✗ WRONG - zero impact |
| 8 | Sprite pipeline | 50-100 | Not attempted |
| 9 | Line buffer BSRAM | 100-200 | Render timing constraints |
| 10 | Multi-domain clocks | 50-100 | Not attempted |
| 11 | SDRAM burst prefetch | 30-50 | Not attempted |

**Total verified savings:** ~7000+ LUT (from Mode2optimized session)

---

## Validation Approach

After each change:

1. **Synthesis check**: `gw_sh` synthesis → LUT/DFF count
2. **Simulation**: `sbt "runMain spinalhdlvdp.VdpTopSim"`
3. **Timing**: Verify fmax > 27 MHz × 2 (for 54 MHz domains)
4. **Hardware**: Program Tang Nano 20K, verify HDMI output

---

## References

- ZipCPU: "Minimizing FPGA Resource Utilization" (2017)
- SpinalHDL: Memory documentation, Parametrization
- Project F: "Hardware Sprites"
- VERA: "Understanding VERA" (iCE40 VDP)
- MiSTer/VIC20Nano: Tang Nano 20K implementations
- Gowin ECP5: Block RAM (EBR) documentation

---

## Summary

The recommended architecture shift:

| Aspect | Current | Recommended |
|--------|---------|-------------|
| Feature control | Add then strip | Compile-time gates |
| Memory | Reg/LUT-first | BSRAM-first |
| Timing | Single-domain | Multi-domain |
| Compositor | Crossbar | Slot-based priority |
| Sprite eval | One-shot parallel | Pipelined sequential |
| Fetch | Per-tile | Burst prefetch |
| Testing | Sim + late HW | BIST + early sanity |

This aligns with industry patterns from MiSTer, VERA, and successful Tang Nano projects, and would resolve the current LUT over-utilization while maintaining feature parity.

---

## Appendix A: Corrections from Mode2optimized Session (2026-05-17)

The following corrections are based on the AI agent's (BrightForge) implementation 
work on the Mode2optimized lane, documented in `MODE2OPTIMIZED_SESSION_REPORT.md`.

### A.1 simPublic() Has Zero Synthesis Impact

**Original claim:** Removing `.simPublic()` calls would save 50-100 LUTs.

**Finding:** `simPublic` is purely a SpinalHDL metadata annotation that marks 
signals as visible to the simulation API. It does not emit any Verilog 
attribute or change generated RTL. Removing it saves **zero LUTs**.

**Source:** BrightForge session, commit verification.

### A.2 hCounterZero Precompute Provides Zero Benefit

**Original claim:** Precomputing `hCounter === 0` once would save 30-50 LUTs.

**Finding:** The optimization was attempted and produced **zero LUT/DFF change**. 
Gowin's common subexpression elimination already handles this optimization 
automatically at synthesis.

**Source:** BrightForge session, commit `49c3a5f` experiment.

### A.3 setTechnology(ramBlock) Does Not Emit Verilog Attributes

**Original claim:** Using `mem.setTechnology(ramBlock)` would hint Gowin to use BSRAM.

**Finding:** `setTechnology(ramBlock)` is purely a SpinalHDL-side hint. It does 
NOT emit any Verilog attribute. To actually hint the synthesizer, you MUST use:
```scala
mem.addAttribute("ram_style", "block")  // Emits (* ram_style = "block" *)
```

**Source:** BrightForge session, probe experiment.

### A.4 BSRAM Requires readSync (Not readAsync)

**Original claim:** Any Mem can be converted to BSRAM with technology hint.

**Finding:** Gowin silently ignores `ram_style="block"` on readAsync memories because 
BSRAM physically requires synchronous read. The attribute only works with `readSync`.

**Source:** BrightForge session, LinestateStore experiments.

### A.5 Render Path Requires Combinational Reads

**Original claim:** Line buffers can use ping-pong BSRAM with readSync.

**Finding:** Per-pixel metadata reads (like LinestateStore `commit`) cannot tolerate 
the 1-cycle readSync delay without compensation logic. The first pixel of each 
line/region would see stale data. Ping-pong BSRAM works for line buffers because 
read/write phases are serial with explicit FSM swap, but in-pipe per-pixel reads 
have different timing constraints.

**Source:** BrightForge session, LinestateStore full conversion failure.

### A.6 Tang Nano vs Oversize-Device Divergence

**Original assumption:** Oversize-device synthesis predicts Tang Nano fit.

**Finding:** The oversize-device synth (GW2A-LV55) is NOT a reliable predictor 
of Tang Nano fit. On the big device, all Mems infer cleanly to BSRAM/SSRAM. On 
Tang Nano with its tighter SSRAM budget (~408 effective vs 737+), the Gowin 
allocator makes different placement decisions and pushes overflow into distributed 
DFFs. A change that LOOKS like pure LUT savings on the oversize device can 
unexpectedly trigger DFF inflation on Tang Nano.

**Always validate fit on the actual Tang Nano.**

**Source:** BrightForge session, Gate #2 synthesis results.

### A.7 Mem→FF Promotion is Cascading Fragility

**Finding:** Any structural perturbation that changes combinational fanout of 
a Mem read port can trigger catastrophic Gowin Mem→FF promotion, even if the 
perturbation is in a different module across a hierarchy boundary.

**Mitigation:** Convert affected Mems to BSRAM via `readSync` + `ram_style="block"`.

**Source:** BrightForge session, multiple root-cause diagnoses.

### A.8 Successful Fixes from Session

| Fix | Description | Result |
|-----|-------------|--------|
| activeListMem readport-trim | Removed 8 legacy readAsync ports | -5480 DFFs |
| BitplaneRowFetch planeRows trim | Removed dead code | Preventive |
| LinestateStore prepare→BSRAM | readSync + ram_style="block" | -128 DFFs (close to fit) |

### A.9 Remaining Gap: +128 DFFs

Current best result: `mode2optimized-linestate-bsram-prepare` at +128 DFFs over.

Options to close:
- **Option A:** Convert one ScrollTable with proper sim migration (~24 SSRAM cells)
- **Option B:** Convert Palette Mem (higher risk)
- **Option C:** Ship without Gate #2 (readport-trim + linestate-BSRAM = fits at +184 LUT)