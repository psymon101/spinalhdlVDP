# External Hardware Engineer Question Set — Mode0-T20

**Task:** BronzeGate #9998  
**Author:** TopazCliff  
**Date:** 2026-05-15  
**Constraint:** Produced without reading `PROJECT_PLAN/MODE0_PLANNING.md`

---

## 1. Question Set

### Output & Timing

1. **What is the exact pixel clock, and what is the timing margin at that frequency after P&R?** The repo states 25.2 MHz pixel clock from a 27 MHz input via PLL/CLKDIV (`VdpTop.scala` line 212; `README.md`). An engineer needs to know setup/hold slack, not just that timing "passes."

2. **Is the output strictly 640×480@60 progressive, or does the timing generator have headroom for other CVT-RB modes?** Retro platforms have non-square pixels and non-60 Hz refresh (PAL 50 Hz). If the timing generator is hard-coded to one mode, adapters for 50 Hz platforms will have frame-rate mismatch or need external scaling.

3. **What is the measured jitter on the HDMI TMDS clock after the Gowin PLL, and has it been verified against HDMI CTS pixel-clock stability requirements?** FPGA PLLs can have significant period jitter depending on feedback divider settings. This affects long cable runs and sink compatibility.

### Memory Architecture

4. **What is the exact SDRAM bandwidth budget per scanline, and how is it allocated across fetch clients?** The repo has a static slot scheduler (`FetchSlotScheduler.scala`) and arbiter (`sdramArbiter`). An engineer needs the worst-case per-line cycle budget and what happens when a client exceeds its slot — does it stall, drop, or corrupt?

5. **What is the end-to-end latency from a host QSPI register write to the corresponding visible pixel change?** The path is: QSPI decoder → register bus → Mode0 state → compositor → HDMI output. For raster effects (e.g., mid-line palette swap), latency determines whether the change lands on the intended pixel or the next line.

6. **How much BSRAM is free for expansion, and which blocks are the largest consumers?** Current baseline shows 17/46 BSRAM blocks used (`MODE0_PLANNING.md` §2, cited from prior packet research). An engineer needs a block-by-block breakdown: sprite pattern RAM, palette, line buffers, affine texture, tile ROMs, scroll tables, copper tables.

### Layer / Compositor

7. **Why do L2 and L3 lack SDRAM fetch, and what is the architectural barrier to giving them the same fetch capability as L0/L1?** `VdpTop.scala` lines 1170-1180 show L2/L3 are `BasicPatternSource` (on-chip tile only) while L0/L1 can be SDRAM-backed. For SNES modes 0-3 honest proof, four independent tile+attribute layers are typically required.

8. **What is the compositor's transparency rule, and is it consistent across all four layers?** The code uses `pixel =/= B(0, 4 bits)` as the opaque test (`VdpTop.scala` lines 1327-1330). An engineer needs to know if color 0 is always transparent, if per-layer transparency can be disabled, and how sprite/BG priority interacts with layer priority.

### Sprite System

9. **Why is the sprite evaluator limited to 8 descriptors and 8 visible per line, and what is the quantitative DFF cost of increasing to 16 or 32?** Task 57 settled on descCount=8 after hitting 111% DFF load at descCount=64. An engineer evaluating this for a product needs the LUT/FF delta per descriptor to model expansion trade-offs.

10. **How does the sprite rasterizer handle pattern-ROM read conflicts when multiple sprites on the same line share the same pattern index?** The current design uses a single shared pattern RAM with one read port (`spritePatternRams(0)` in `VdpTop.scala` line 1483). A sequential rasterizer reads one pattern per cycle. An engineer needs to know the exact cycle budget for 8 sprites × 16 pixels wide and whether pattern-index duplication causes stalls.

### Host Interface

11. **What is the guaranteed QSPI throughput in sustained register-write mode, and what is the burst-length limit before the CS-hold or OSR-drain timeout fires?** The contract specifies 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain (`firmware/GOTCHAS.md`). At 2 MHz, a 16-bit register write takes ~8 µs (8 SCK edges + overhead). An engineer needs the maximum sustained frame rate for register updates.

12. **Can the QSPI interface sustain a full palette reload (128 × 24-bit entries) within one vertical blanking interval?** Palette upload is done via auto-incrementing writes at 0x0600/0x0601 (`VdpTop.scala` lines 1687-1694). At QSPI throughput, 128 entries × 2 writes × ~8 µs = ~2 ms. V-blank at 640×480@60 is ~1.3 ms. This may not fit.

### Color / Palette

13. **Is the 24-bit RGB palette output gamma-corrected or linear, and is there a programmable color-lookup stage for analog-look emulation?** The palette stores RGB888 directly (`VdpTop.scala` line 1704). Retro platforms had non-linear DACs and analog artifacts. An engineer needs to know if the output is intended to be mathematically accurate to the original hardware values or if gamma/response curves are adapter-local.

14. **How does the color-math stage handle overflow and underflow in add/subtract modes?** The color-math / shadow-highlight stage (R6) is marked DONE but an engineer needs to know the exact arithmetic: is it clamped, wrapped, or saturated? Different platforms (SNES vs Genesis) have different overflow behaviors.

### Raster Effects

15. **Why is there only one raster trigger unit, and how do platforms that need multiple simultaneous raster splits (e.g., SNES with HDMA on multiple channels) map to this constraint?** `RasterTriggerUnit.scala` provides one line/pixel comparator. SNES HDMA can have up to 8 channels. An engineer needs to know if the single trigger drives a table-driven automator (Copper-lite) or if simultaneous multi-channel effects are impossible.

### Build / Resource

16. **What is the power consumption breakdown by major subsystem, and what is the thermal headroom on the Tang Nano 20K at the current utilization?** Reference projects on the same board (A2FPGA) show VDP + VRAM as significant power consumers. The repo has no power analysis. An engineer evaluating this for a portable or enclosed device needs mW numbers.

17. **What is the toolchain reproducibility story?** The build uses `sbt` + `gw_sh` + `openFPGALoader`. An engineer needs to know: does a clean clone + `make` produce bit-identical `project.fs`? Are there non-deterministic Gowin optimizations (packing, placement) that change timing between builds?

### Verification

18. **What is the hardware proof standard for claiming a platform adapter is "honest"?** The repo has sim harnesses and `TEST_PATTERN_POLICY.md` (not read per task constraint, but referenced in `AGENTS.md`). An engineer needs the exact acceptance criteria: sim-only, 30-second capture freeze=0, visual comparison, or something else? And who signs off?

---

## 2. Why These Questions Matter

| Group | Why it matters |
|---|---|
| Output & Timing | Determines sink compatibility and whether the design can drive real displays reliably under all conditions |
| Memory Architecture | SDRAM bandwidth is the primary bottleneck for multi-layer/multi-format fetch; BSRAM is the primary capacity limit on this board |
| Layer / Compositor | The 2+2 layer split (SDRAM vs on-chip) is the most visible architectural asymmetry; it directly affects which platforms can be honestly supported |
| Sprite System | Sprite capacity is the most constrained resource after the Task 57 DFF optimization; understanding expansion cost is critical for product planning |
| Host Interface | QSPI throughput bounds the rate of dynamic scene updates; palette reload in vblank is a common operation that must fit |
| Color / Palette | Color accuracy is central to retro platform identity; overflow behavior and gamma affect visual honesty |
| Raster Effects | Single trigger vs multi-channel HDMA is a significant functional gap for SNES-class adapters |
| Build / Resource | Power and reproducibility are gate criteria for any design moving from prototype to product |
| Verification | Without a clear hardware proof standard, "honest adapter" claims are unverifiable |

---

## 3. Source Basis

| Source Type | Exact Sources | What they informed |
|---|---|---|
| Code inspection | `VdpTop.scala` (timing, layers, compositor, palette, sprite system) | Questions 1, 4, 7, 8, 9, 10, 12, 13, 15 |
| Code inspection | `RasterTriggerUnit.scala` | Question 15 |
| Code inspection | `TileAttributeAssets.scala` | Question 6 (palette depth) |
| Code inspection | `BasicPatternSource.scala` | Question 7 (L2/L3 limitation) |
| Code inspection | `BitplaneRowFetch.scala` | Question 4 (fetch bandwidth) |
| Code inspection | `SpriteRasterizer.scala` | Question 10 (pattern RAM read) |
| Repo docs | `README.md` (output spec, build flow) | Questions 1, 17 |
| Repo docs | `firmware/GOTCHAS.md` (QSPI timing) | Questions 11, 12 |
| Repo docs | `firmware/README.md` (host platforms) | Question 11 |
| Repo docs | `AGENTS.md` (verification rules, proof standards) | Question 18 |
| Online research | A2FPGA power/utilization analysis (deepwiki.com) | Question 16 (power breakdown precedent) |
| Online research | FPGA development lifecycle guide (adiuvoengineering.com) | Question 17 (reproducibility, verification strategy) |
| Online research | Columbia FPGA game design paper (ForestFireIce) | Question 10 (sprite engine evaluation criteria) |
| General engineering | HDMI CTS requirements, retro VDP architecture references | Questions 2, 3, 14 (industry-standard evaluation criteria) |
