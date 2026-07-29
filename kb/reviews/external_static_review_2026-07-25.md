# External Static Review — Tang Nano 20K VDP Implementation

**Source:** User-provided review from another AI assistant.  
**Date:** 2026-07-25  
**Scope:** `TopTang20kHdmi.scala`, `VdpTop.scala`, `BasicPatternSource.scala`, `LineBuffer.scala`, `ScrollWrap.scala`, `PixelRepeatScaler.scala`, `Tang20kHdmiTx.scala`  
**Status:** Pending team review (BrightForge technical assessment, CyanPeak code-to-spec review, CoralReef doc impact).

---

# Executive Summary

The design is fundamentally viable on a Tang Nano 20K, but several implementation choices currently create blank-output, startup, scaling, and alignment failures.

The most important findings are:

1. **The top-level intentionally boots blank and waits for a host.**
2. **Layer 0 is forced to use SDRAM even when SDRAM initialization is skipped.**
3. **The scaler is architecturally incorrect for scaling greater than 1×.**
4. **The scaler has inconsistent latency between fresh and replayed lines.**
5. **The scaler counters are reset at sync timing rather than visible-area boundaries.**
6. **The merged line buffer uses a non-power-of-two depth that may be unsafe with Gowin BSRAM inference.**
7. **The bootstrap linestate loop is unreachable because `lastStepIdx` is set incorrectly.**
8. **Layer 1 uses the Layer 0 pixel-address output.**
9. **The HDMI transmitter reset can release before the divided pixel clock is active.**
10. **`BasicPatternSource` uses two dependent asynchronous memory reads in one pixel path.**

The recommended strategy is to first produce a reliable standalone 1× test-pattern build, then reintroduce SDRAM, then replace the current sink-side scaler with source-coordinate scaling.

---

# Current Video Pipeline

The current relevant path is approximately:

```text
Raster counters
    |
    +--> fillLine = next scanline
    |
    +--> background / bitmap / planar / sprite composition
    |
    +--> ping-pong line buffer
    |
    +--> palette lookup or RGB565 direct-color bypass
    |
    +--> window and color math
    |
    +--> PixelRepeatScaler
    |
    +--> registered RGB / sync / DE
    |
    +--> HDMI clean-start mute
    |
    +--> Tang20k HDMI transmitter black box
```

The design fills line `N+1` while displaying line `N`.

---

# Priority 0 — Create a Reliable Standalone Diagnostic Build

Before debugging SDRAM, QSPI, bitmap uploads, sprites, or scaling, create a deterministic build that requires no host.

## Problem

`TopTang20kHdmi.scala` explicitly uses host-controlled startup:

```scala
private val useHostInit: Boolean = true
```

This results in:

- The bootstrap FSM being skipped.
- `layerEnableReg` remaining at its reset value of all zeroes.
- SDRAM asset initialization being skipped.
- Layer 0 still being forced to SDRAM.
- The test-pattern override remaining disabled.

The effective behavior is:

```text
No bootstrap
+ no enabled layers
+ no SDRAM initialization
+ SDRAM selected as Layer 0 source
= blank or undefined output
```

## Required change

Change:

```scala
private val useHostInit: Boolean = true
```

to:

```scala
private val useHostInit: Boolean = false
```

## Diagnostic configuration

For the first standalone hardware test, bypass SDRAM entirely:

```scala
video.io.layer0UseSdram := False
video.io.layer0TestPatternEnable := True
video.io.layer0TestPatternSelect := U(1, 3 bits)
```

Keep Layer 1 disabled:

```scala
video.io.layer1UseSdram := False
```

Force 1× scaling:

```scala
scaler.io.scaleXReg := U(1, 3 bits)
scaler.io.scaleYReg := U(1, 3 bits)
scaler.io.autoCenter := False
```

If the scaler configuration is only available through VDP registers, temporarily replace the scaler output in `VdpTop.scala` with a single registered bypass:

```scala
val displayRgbScaled = RegNext(displayRgb) init B(0, 24 bits)
```

Keep the existing matching sync/DE pipeline stage.

## Expected result

- Stable 640×480 HDMI signal.
- Visible test pattern.
- Cyan transport canary at the bottom-right.
- No dependence on QSPI or SDRAM.
- No random startup behavior.

---

# Priority 1 — Fix the Bootstrap Range

## Problem

The current bootstrap code defines:

```scala
val linestateBase = colorMathIdx + 1
val lastStepIdx   = colorMathIdx
```

This makes the linestate upload range unreachable because the first linestate index is already greater than `lastStepIdx`.

The boot counter stops before any linestate entries are written.

## Required change

Replace:

```scala
val lastStepIdx = colorMathIdx
```

with:

```scala
val lastStepIdx =
  linestateBase + U(LinestateCount - 1, 7 bits)
```

## Validate widths

Ensure all operands are explicitly resized to the width of `bootIdx` if SpinalHDL reports width errors:

```scala
val lastStepIdx = (
  linestateBase.resize(7) +
  U(LinestateCount - 1, 7 bits)
).resize(7)
```

## Acceptance test

After boot:

- The register bootstrap writes execute.
- `LAYER_ENABLE` becomes `0x0001`.
- The intended linestate entries are written.
- The boot FSM reaches `bootDoneR := True`.

---

# Priority 2 — Fix Layer 1 Pixel Address Wiring

## Problem

The Layer 1 fetch engine is wired to the Layer 0 pixel-address output:

```scala
fetchL1.io.pixelAddr := video.io.layer0FetchPixelAddr
```

Today both may track the same horizontal counter, but this bypasses the independent Layer 1 scheduling interface.

## Required change

Replace it with:

```scala
fetchL1.io.pixelAddr := video.io.layer1FetchPixelAddr
```

## Acceptance test

- Layer 0 and Layer 1 fetch modules use their matching scheduler surfaces.
- Future Layer 1 timing changes do not silently depend on Layer 0.

---

# Priority 3 — Correct HDMI Reset Sequencing

## Problem

The high-speed PLL can report lock before the pixel-clock divider is released.

Current behavior is effectively:

```text
PLL locks
    |
    +--> HDMI transmitter reset releases immediately
    |
    +--> pixel clock divider remains reset for 16 high-speed cycles
```

The HDMI transmitter may leave reset while its pixel clock is not yet running.

## Required change

Create a shared clock-ready signal:

```scala
val clockReady =
  pll.LOCK && (pllResetArea.clkdivResetCounter === U(15, 4 bits))

clkdiv.RESETN := clockReady
```

Then use:

```scala
val pixelReset = !clockReady
```

instead of:

```scala
val pixelReset = !pll.LOCK
```

## Better reset release

The preferred implementation is:

1. Assert reset asynchronously.
2. Wait for PLL lock.
3. Release `CLKDIV`.
4. Wait for at least two valid pixel-clock edges.
5. Deassert pixel-domain and HDMI resets synchronously.

Conceptual implementation:

```scala
val pixelClockDomainRaw = ClockDomain(
  clock = clkdiv.CLKOUT,
  reset = !clockReady,
  config = ClockDomainConfig(
    resetKind = ASYNC,
    resetActiveLevel = HIGH
  )
)

val pixelReleaseArea = new ClockingArea(pixelClockDomainRaw) {
  val release = Reg(UInt(2 bits)) init 0

  when(release =/= 3) {
    release := release + 1
  }

  val ready = release === 3
}
```

Use a clean synchronized reset derived from this stage for VDP and HDMI logic.

## Acceptance test

- HDMI starts consistently after every power cycle.
- No need to press reset or reload the bitstream multiple times.
- Capture devices consistently detect the signal.

---

# Priority 4 — Replace the Current Scaling Architecture

## Confirmed architectural problem

`PixelRepeatScaler` attempts to scale after the compositor while the compositor continues advancing at one source pixel per physical clock.

For 2× horizontal scaling, the current behavior is approximately:

```text
Input:   P0 P1 P2 P3 P4 P5
Output:  P0 P0 P2 P2 P4 P4
```

The correct result is:

```text
Output:  P0 P0 P1 P1 P2 P2
```

Vertical scaling has the same issue:

```text
Current: source lines 0, 0, 2, 2, 4, 4
Correct: source lines 0, 0, 1, 1, 2, 2
```

A sink-side latch cannot recover source pixels that the upstream compositor has already skipped.

## Required architecture

Scaling must generate logical source coordinates before rendering.

```text
Physical hCounter/vCounter
        |
        v
Scale and centering coordinate generator
        |
        +--> logicalX
        +--> logicalY
        +--> insideScaledViewport
        |
        v
Tile / bitmap / planar / sprite rendering
        |
        v
Final RGB
```

## Recommended outputs from the scaler

Replace the current RGB scaler with a coordinate generator that outputs:

```scala
val sourceX = out UInt(10 bits)
val sourceY = out UInt(10 bits)
val sourceValid = out Bool()

val borderX0 = out UInt(10 bits)
val borderX1 = out UInt(10 bits)
val borderY0 = out UInt(10 bits)
val borderY1 = out UInt(10 bits)

val scaleXEffOut = out UInt(3 bits)
val scaleYEffOut = out UInt(3 bits)
```

## Coordinate calculation

For a centered scaled image:

```text
visibleWidth  = logicWidth  × scaleX
visibleHeight = logicHeight × scaleY

offsetX = (hActive - visibleWidth) / 2
offsetY = (vActive - visibleHeight) / 2
```

During active video:

```text
sourceValid =
    hCounter >= offsetX
&&  hCounter <  offsetX + visibleWidth
&&  vCounter >= offsetY
&&  vCounter <  offsetY + visibleHeight
```

Logical coordinates are:

```text
sourceX = (hCounter - offsetX) / scaleX
sourceY = (vCounter - offsetY) / scaleY
```

Because scale is limited to 1 through 6, division can be implemented with counters or small constant-select logic.

## Preferred counter implementation

Avoid per-pixel general division.

Maintain:

```scala
physicalRepeatX
logicalX
physicalRepeatY
logicalY
```

At the first visible pixel of a line:

```scala
physicalRepeatX := 0
logicalX := 0
```

For each physical pixel inside the viewport:

```scala
when(physicalRepeatX === scaleXEff - 1) {
  physicalRepeatX := 0
  logicalX := logicalX + 1
} otherwise {
  physicalRepeatX := physicalRepeatX + 1
}
```

At the first visible line:

```scala
physicalRepeatY := 0
logicalY := 0
```

At each new visible line:

```scala
when(physicalRepeatY === scaleYEff - 1) {
  physicalRepeatY := 0
  logicalY := logicalY + 1
} otherwise {
  physicalRepeatY := physicalRepeatY + 1
}
```

## Important integration requirement

The renderer must consume `logicalX` and `logicalY`, not the original physical counters.

For example:

```scala
layer0.io.x := logicalX
layer0.io.y := logicalY

layer1.io.x := logicalX
layer1.io.y := logicalY

testPattern.io.x := logicalX
testPattern.io.y := logicalY
```

Bitmap, planar, sprite, and tile-fetch addressing must follow the same logical coordinates.

## Why this is preferable

- Correct horizontal and vertical repetition.
- Reduced SDRAM bandwidth for scaled modes.
- Consistent sprite and background alignment.
- No replay-line BRAM latency.
- No stale first pixel.
- No alternating fresh/replay line timing.
- Easier centering and border handling.

---

# Priority 5 — Remove or Disable the Current Replay-Line Scaler

Until the coordinate scaler is implemented, do not use the existing `PixelRepeatScaler` for scale values greater than 1.

## Existing scaler defects

### Defect A: source skipping

The scaler samples only when `xRep == 0`, while `io.inRgb` changes every physical clock.

### Defect B: wrong horizontal phase

`xRep` resets when horizontal sync begins, not when visible pixels begin.

The number of clocks from sync start to the next active pixel is not guaranteed to be divisible by every supported scale value.

### Defect C: wrong vertical phase

`yRep` resets at vertical sync rather than visible line zero.

### Defect D: inconsistent latency

Fresh-line path:

```text
freshOut -> outRgbReg
```

Replay-line path:

```text
readSync -> replayOut -> outRgbReg
```

The replay path is one cycle deeper.

### Defect E: stale first pixel

If `xRep != 0` at active pixel zero, the output can use a previous-line `freshLatch`.

## Interim safe implementation

Use only a registered pass-through:

```scala
val outRgbReg = RegNext(io.inRgb) init B(0, 24 bits)
io.outRgb := outRgbReg
```

Force effective scaling to 1×.

---

# Priority 6 — Harden `LineBuffer` for Gowin BSRAM

## Current implementation

`LineBuffer` combines two 640-pixel buffers into one memory:

```text
depth = 2 × 640 = 1280
```

The ping-pong selection logic is conceptually correct.

The concern is Gowin handling of non-power-of-two inferred BSRAM depths.

The project already encountered a non-power-of-two BSRAM issue with a 1,200-entry tile map, so the 1,280-entry line buffer should be treated as suspect.

## Required diagnostic hardening

Use a power-of-two physical depth:

```scala
val logicalDepth  = 2 * lineWidth
val physicalDepth = 1 << log2Up(logicalDepth)

val buf = Mem(Bits(pixelWidth bits), physicalDepth)
buf.addAttribute("ram_style", "block")
```

For a 640-pixel line:

```text
logicalDepth  = 1280
physicalDepth = 2048
```

Keep the existing address mapping:

```scala
val lineBase = U(lineWidth, log2Up(physicalDepth) bits)
val zeroBase = U(0, log2Up(physicalDepth) bits)
```

Resize the read and write addresses to the physical address width before addition.

Example:

```scala
val memAddrWidth = log2Up(physicalDepth)

val writeBase = Mux(writeSel, lineBase, zeroBase)

buf.write(
  address = (
    writeBase +
    io.writeAddr.resize(memAddrWidth)
  ).resize(memAddrWidth),
  data = io.writeData,
  enable = io.writeEnable
)
```

The same applies to the read address.

## Existing swap logic

This part is reasonable:

```scala
val writeSelNext = Mux(io.swap, !writeSel, writeSel)
val readBase = Mux(writeSelNext, zeroBase, lineBase)
```

It correctly attempts to read the buffer that is not being written after a swap.

## Acceptance test

- No corruption beginning near a memory-address boundary.
- No right-side corruption on alternating lines.
- No first-pixel corruption after line swaps.
- Gowin synthesis report shows BSRAM rather than LUT RAM.

---

# Priority 7 — Pipeline `BasicPatternSource`

## Current risk

`BasicPatternSource` performs two dependent asynchronous memory reads:

```scala
val tileIndex = tileMap.readAsync(tileAddress).asUInt
val rowAddress = (tileIndex.asBits ## pixelY.asBits).asUInt
val rowData = tileRows.readAsync(rowAddress)

io.pixelIndex := rowData.subdivideIn(PixelBits bits)(pixelX)
```

The path is:

```text
coordinate wrap
 -> tile coordinate
 -> tile address arithmetic
 -> asynchronous tile map memory
 -> row address
 -> asynchronous tile row memory
 -> 16:1 pixel mux
```

This may:

- Infer distributed memory instead of BSRAM.
- Produce a long placement-sensitive combinational path.
- Work in simulation and fail on hardware.
- Create nondeterministic timing between bitstreams.

## Recommended pipeline

Use synchronous reads with explicit coordinate alignment.

### Stage 0

Calculate:

```scala
tileAddress
pixelX
pixelY
```

Register `pixelX` and `pixelY`.

### Stage 1

Read tile index:

```scala
val tileIndex = tileMap.readSync(tileAddress)
```

Calculate row address:

```scala
val rowAddress = (tileIndex ## pixelYStage1).asUInt
```

Register `pixelX` again.

### Stage 2

Read tile row:

```scala
val rowData = tileRows.readSync(rowAddress)
```

Extract the pixel using aligned `pixelXStage2`.

### Example structure

```scala
val pixelX1 = RegNext(pixelX) init 0
val pixelY1 = RegNext(pixelY) init 0

val tileIndex = tileMap.readSync(tileAddress).asUInt

val rowAddress =
  (tileIndex.asBits ## pixelY1.asBits).asUInt

val pixelX2 = RegNext(pixelX1) init 0
val rowData = tileRows.readSync(rowAddress)

val pixelIndex =
  rowData.subdivideIn(PixelBits bits)(pixelX2)

io.pixelIndex := RegNext(pixelIndex) init 0
```

Exact latency depends on how SpinalHDL maps each memory and whether the final output is registered.

## Integration requirement

Delay or align any associated metadata by the same number of cycles:

- Palette bank
- Layer priority
- Transparency
- Layer enable
- Source identifier
- Fill/write address

Because the current architecture writes the composed pixel into a line buffer, the simplest integration may be to adjust the fill write address by the pipeline depth instead of delaying the raster counters globally.

## Better tile optimization

A tile row is reused for 16 pixels.

A more bandwidth-efficient implementation:

1. Fetch the next tile index before the current tile ends.
2. Fetch the next tile row.
3. Load the 48-bit row into a shift register.
4. Emit one 3-bit pixel per cycle.

This reduces row-memory activity from one read per pixel to one read per tile.

---

# Priority 8 — Preserve Pixel, Sync, and Metadata Alignment

The display path contains several registered stages:

- Line-buffer synchronous read.
- Palette synchronous read.
- Direct-color compensation registers.
- Window/color-math input registers.
- Scaler output register.
- VDP sync/DE first stage.
- VDP sync/DE second stage.
- Top-level HDMI boundary registers.

Every RGB source and control signal must represent the same pixel.

## Signals that must be aligned

- RGB
- `de`
- `hsync`
- `vsync`
- `x`
- `y`
- sprite-wins
- direct-color-active
- layer-mask-active
- border-active
- raster-trigger state
- palette address/result
- metadata from the line buffer

## Recommendation

Create an explicit latency table in comments and tests.

Example:

| Stage | RGB/data | Sync/DE | Coordinates |
|---|---|---|---|
| 0 | current compositor result | raw timing | raw h/v |
| 1 | palette/direct-color alignment | `hsyncR`, `deR` | `hCounterR` |
| 2 | color/scaler output | `hsyncRR`, `deRR` | second coordinate register |
| 3 | HDMI boundary register | top-level registered sync/DE | optional top-level coordinates |

Do not describe the pipeline only in comments. Add simulation assertions that check expected coordinate/color alignment.

---

# Priority 9 — Validate the RGB565 Direct-Color Delay

`VdpTop` exposes:

```scala
bitmapWritePipelineDelay: Int = 0
```

Comments in the source indicate that a value of `3` was measured for one direct-color path, while other comments say `0` is aligned.

Do not change this blindly.

## Required validation

Create a deterministic RGB565 source where each column has a predictable value.

Examples:

```text
Column 0 = red
Column 1 = green
Column 2 = blue
Column 3 = white
repeat
```

or encode the low bits of the X coordinate into RGB565.

Capture the HDMI output and compare the expected transition columns.

## Adjustment

If the direct-color image is shifted right by three columns, instantiate:

```scala
val video = VdpTop(
  sdramCd = sdramClockDomain,
  enableL1Fetch = enableL1Fetch,
  withExtraRasterTriggers = withExtraRasterTriggers,
  enableL2L3 = enableL2L3,
  scaleCtrlInit = scaleCtrlInit,
  logicWidthInit = logicWidthInit,
  logicHeightInit = logicHeightInit,
  borderCtrlInit = borderCtrlInit,
  bitmapWritePipelineDelay = 3
)
```

Do not use this parameter to compensate for unrelated HDMI or scaler latency.

---

# `ScrollWrap` Review

`ScrollWrap` is functionally reasonable.

For a 640-pixel map with 10-bit coordinate and scroll inputs:

```text
maximum coordinate = 1023
maximum scroll     = 1023
maximum sum        = 2046
```

Required wrap thresholds are:

```text
640
1280
1920
```

The generated combinational mux tree handles those thresholds.

## Minor issue

The comments refer to `foldRight`, but the implementation uses `foldLeft`.

The resulting nesting still gives the largest threshold priority, but the comment should be corrected.

## Optimization note

The current generalized wrap tree is more logic than needed if upstream scroll registers are already constrained to the map width.

For fixed map widths and constrained inputs, a simpler one- or two-subtract implementation may reduce logic depth.

This is not a priority defect at 25.2 MHz.

---

# `Tang20kHdmiTx` Review Limitation

`Tang20kHdmiTx.scala` is only a SpinalHDL black-box declaration.

It does not reveal:

- TMDS encoder implementation.
- Serializer primitive configuration.
- Clock phase assumptions.
- Reset behavior.
- Pixel-to-5× clock crossing.
- Gowin-specific `OSER10` or output-buffer configuration.

The actual implementation file, likely named something similar to:

```text
tang20k_hdmi_tx.v
```

must be reviewed if HDMI remains unstable after fixing reset sequencing.

---

# Recommended Implementation Order

## Phase 1 — Prove HDMI and on-chip rendering

1. Set `useHostInit = false`.
2. Fix `lastStepIdx`.
3. Force Layer 0 test pattern.
4. Disable Layer 0 SDRAM.
5. Disable Layer 1.
6. Force scale to 1×.
7. Keep the cyan canary enabled.
8. Build and test repeated cold starts.

### Pass criteria

- HDMI always locks.
- Test pattern is stable.
- Canary is visible.
- No random colors.
- No intermittent black screen.

---

## Phase 2 — Prove the line buffer

1. Pad line-buffer physical depth to 2048.
2. Force `ram_style = block`.
3. Render vertical color bars.
4. Render a unique first and last pixel.
5. Check alternating lines and the right edge.

### Pass criteria

- Pixel 0 and pixel 639 are correct.
- No alternating-line corruption.
- No corruption after buffer swaps.
- BSRAM usage appears in synthesis report.

---

## Phase 3 — Re-enable initialized SDRAM tile rendering

1. Keep `useHostInit = false`.
2. Re-enable Layer 0 SDRAM.
3. Keep scaling at 1×.
4. Verify SDRAM initialization completes.
5. Compare SDRAM tile output with the on-chip pattern source.

### Pass criteria

- SDRAM output matches expected tile map.
- No garbage regions.
- No corruption around tile-map address 1024.
- Scrolling is stable at 1 pixel per frame.

---

## Phase 4 — Pipeline on-chip tile memories

1. Convert `BasicPatternSource` memories to synchronous reads.
2. Add explicit pipeline registers.
3. Align pixel address and metadata.
4. Confirm timing and BSRAM inference.

### Pass criteria

- Functional simulation matches `expectedPixelIndex`.
- Hardware image is bit-exact.
- Timing is deterministic across repeated builds.

---

## Phase 5 — Replace scaling

1. Remove sink-side RGB replay scaling.
2. Generate logical source coordinates.
3. Feed logical coordinates into all render sources.
4. Add centered viewport and border output.
5. Test 1× through 6×.

### Pass criteria

For 2×:

```text
P0 P0 P1 P1 P2 P2
```

For 3×:

```text
P0 P0 P0 P1 P1 P1
```

Vertical lines repeat identically.

Sprites, tiles, bitmap pixels, and borders use the same logical coordinate system.

---

## Phase 6 — Re-enable host-controlled startup

Only after standalone mode is stable:

1. Restore `useHostInit = true` for production.
2. Ensure firmware initializes:
   - layer enables
   - palette
   - tile/bitmap modes
   - SDRAM assets
   - scaling
   - borders
3. Add a timeout or diagnostic status if the host never initializes the VDP.

### Recommended production behavior

Instead of a fully black uninitialized state, consider keeping the cyan canary or a small diagnostic status indicator until host initialization completes.

---

# Hardware Debug Interpretation

## No HDMI signal

Likely causes:

- PLL configuration.
- Clock divider not released.
- HDMI transmitter reset ordering.
- Pin constraints.
- Missing or incorrect black-box RTL.
- Serializer primitive issue.

## Black screen with cyan canary

HDMI transport works.

Likely causes:

- All layers disabled.
- Host bootstrap did not run.
- Layer source selected incorrectly.
- Palette or content is blank.

## Garbage image with cyan canary

Likely causes:

- SDRAM selected but not initialized.
- SDRAM arbitration/fetch issue.
- Address or byte-lane problem.
- Non-power-of-two BSRAM mapping.
- CDC issue in SDRAM fetch path.

## Correct image at 1×, broken at 2× or higher

The existing `PixelRepeatScaler` is the cause.

## Alternating line shift

Likely causes:

- Replay-line scaler latency.
- Line-buffer read-ahead mismatch.
- Fresh/replay path depth mismatch.

## Image shifted only in RGB565 mode

Likely cause:

- `bitmapWritePipelineDelay`.

## Tile corruption only

Likely causes:

- `BasicPatternSource` asynchronous dependent reads.
- Tile data bit ordering.
- Tile map BSRAM inference.
- Pixel X/Y pipeline mismatch.

---

# Suggested Simulation Tests

## Test 1 — Standalone startup

Assert after reset that:

```text
bootDoneR eventually becomes true
layerEnableReg bit 0 becomes true
```

## Test 2 — Line-buffer swap

Write known values to line 0:

```text
pixel[x] = x mod 256
```

After swap, assert that read output corresponds to the same X coordinate.

Specifically test:

- X = 0
- X = 1
- X = 638
- X = 639
- First read immediately after swap

## Test 3 — Scaling

Input sequence:

```text
10, 20, 30, 40
```

Expected 2× output:

```text
10, 10, 20, 20, 30, 30, 40, 40
```

Expected 3× output:

```text
10, 10, 10, 20, 20, 20, 30, 30, 30
```

Test across at least two lines.

## Test 4 — Tile pipeline

Compare hardware-generated output with:

```scala
BasicPatternSource.expectedPixelIndex(...)
```

for:

- Every pixel in a complete 16×16 tile.
- Tile boundaries at X = 15/16.
- Tile-map row boundaries.
- Scroll values near 639 and 479.
- Scroll values greater than one full map dimension.

## Test 5 — Direct-color alignment

Generate an RGB565 X-coordinate ramp and assert that each HDMI output coordinate receives the intended source column.

## Test 6 — Reset

Simulate:

- PLL lock.
- Delayed pixel-clock divider release.
- Pixel-domain reset release.
- HDMI reset release.

Assert no logic leaves reset before the pixel clock is active.

---

# Exact Minimal Patch Set for First Hardware Test

## `TopTang20kHdmi.scala`

```diff
- private val useHostInit: Boolean = true
+ private val useHostInit: Boolean = false
```

```diff
- fetchL1.io.pixelAddr := video.io.layer0FetchPixelAddr
+ fetchL1.io.pixelAddr := video.io.layer1FetchPixelAddr
```

```diff
- video.io.layer0UseSdram := True
+ video.io.layer0UseSdram := False

- video.io.layer0TestPatternEnable := False
- video.io.layer0TestPatternSelect := U(0, 3 bits)
+ video.io.layer0TestPatternEnable := True
+ video.io.layer0TestPatternSelect := U(1, 3 bits)
```

Replace immediate PLL-lock reset release:

```diff
- val pixelReset = !pll.LOCK
+ val clockReady =
+   pll.LOCK && (pllResetArea.clkdivResetCounter === U(15, 4 bits))
+
+ val pixelReset = !clockReady
```

Use:

```scala
clkdiv.RESETN := clockReady
```

## Bootstrap index

```diff
- val lastStepIdx = colorMathIdx
+ val lastStepIdx =
+   (linestateBase.resize(7) +
+    U(LinestateCount - 1, 7 bits)).resize(7)
```

## `LineBuffer.scala`

```diff
- val depth = 2 * lineWidth
- val buf = Mem(Bits(pixelWidth bits), depth)
- val lineBase = U(lineWidth, log2Up(depth) bits)
- val zeroBase = U(0, log2Up(depth) bits)
+ val logicalDepth = 2 * lineWidth
+ val physicalDepth = 1 << log2Up(logicalDepth)
+ val memAddrWidth = log2Up(physicalDepth)
+
+ val buf = Mem(Bits(pixelWidth bits), physicalDepth)
+ buf.addAttribute("ram_style", "block")
+
+ val lineBase = U(lineWidth, memAddrWidth bits)
+ val zeroBase = U(0, memAddrWidth bits)
```

Resize address operands as needed.

## `VdpTop.scala`

Temporarily bypass scaling:

```scala
val displayRgbScaled =
  RegNext(displayRgb) init B(0, 24 bits)
```

Keep matching sync/DE latency.

---

# Items Not Yet Proven

The static review cannot fully verify the following without additional source or reports:

1. The internal RTL of `tang20k_hdmi_tx`.
2. Gowin PLL parameters and exact output frequencies.
3. Pin constraints.
4. Timing-analysis reports.
5. SDRAM-controller timing and DQ phase.
6. Scheduler/arbitration fairness.
7. Exact measured RGB565 pipeline delay.
8. Generated Verilog RAM inference.
9. Host firmware initialization sequence.

Do not compensate for those unknowns by adding arbitrary pixel delays.

---

# Final Target Architecture

```text
Host/QSPI/i80
     |
     v
VDP registers and DMA
     |
     v
Physical timing counters
     |
     v
Logical coordinate scaler
     |
     +--> logical X/Y
     +--> viewport valid
     +--> border region
     |
     v
Tile / bitmap / planar / sprite sources
     |
     v
Priority compositor
     |
     v
Line buffer
     |
     v
Palette or direct RGB
     |
     v
Window / color math
     |
     v
Registered RGB + aligned DE/sync
     |
     v
HDMI transmitter
```

The scaler should control source coordinates, not replay the final RGB stream.

---

# Definition of Done

The implementation is complete when:

- The board starts reliably from cold power.
- Standalone test mode does not require a host.
- HDMI is stable on both a monitor and capture device.
- 1× rendering is pixel aligned.
- Layer 0 and Layer 1 use independent fetch interfaces.
- Line buffers infer BSRAM and have no boundary corruption.
- Tile ROMs use deterministic synchronous pipelines.
- 2× through 6× scaling repeats every source pixel and line correctly.
- RGB565 and indexed paths align at the same display coordinate.
- Host-controlled startup can be re-enabled without creating a permanent black screen.
