# P3b RTL changes — bitmap/indexed fetch-side coordinate remapping

Branch `topazcliff/scaler-rewrite`. Single file: `hw/spinal/spinalhdlvdp/VdpTop.scala`
(~5 logic lines + comments; well under the 200-line ADR threshold). Governing
semantics: Option B (Compose), interface checkpoint #14466→#14471, documented in
`docs/firmware/HOST_TRANSPORT_ABI.md` §"Bitmap/indexed SCALE_CTRL semantics" +
`firmware/GOTCHAS.md` GOTCHA-039 (commit `e126477`).

## The three edits (all reduce to today's code at 1× → byte-identical by construction)

1. **`pixelWithinByte` ← `logicalX(2:0)`** (was `hCounter(2:0)`), still `RegNext`
   for the readSync byte latency. The intra-byte pixel-repetition now tracks the
   scaled source column. At 1× `logicalX == hCounter`.

2. **`bitmapFetchLineReg` ← `logicalY`** (was `fillLine`), keeping BitmapRowFetch's
   built-in `lineReg := pendingLine>>1` doubling → effective vertical scale = 2·scaleY
   (the Compose contract). At 1× `logicalY == fillLine`.

3. **Bitmap fetch grant gate: `vCounter(0)` → a `logicalY>>1` step-boundary detector**
   (`bitmapSrcRow = logicalY>>1`, `bitmapSrcRowPrev = RegNextWhen(bitmapSrcRow, hCounter==hTotal-1)`,
   fire when they differ). `bitmapSrcRow` is exactly the source row the fetcher targets
   (`pendingLine>>1`); firing on its advance grants once per composed source row. At 1×,
   `logicalY == fillLine == vCounter+1`, so `logicalY>>1` increments on odd output lines →
   the detector fires on exactly the same cycles as the old `vCounter(0)` → **1× grant
   cadence is bit-identical**, preserving the `2bpp-bank-completion-rtl` bank-ready/
   row-tag contracts.

The coord mux already yields `logicalX=hCounter, logicalY=fillLine` when `scaleActive`
is false (VdpTop:2742-2743), so at SCALE_CTRL=default(1×) the fetch indexing is
bit-for-bit the pre-P3b code → zero-risk 1× byte-identity.

## Elaboration
`sbt runMain spinalhdlvdp.TopTang20kHdmiVerilog` — clean (exit 0). Generated
`hw/gen/top_tang20k.v` sha `8bac5ca2…` (contains `bitmapSrcRow`).
