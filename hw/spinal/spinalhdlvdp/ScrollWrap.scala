package spinalhdlvdp

import spinal.core._

/** R5.4 Scroll-Wrap primitive.
  *
  * Pure combinational `(coord + scroll) mod mapWidth`. Consolidates the
  * scroll-wrap math that previously lived ad-hoc in `BasicPatternSource`,
  * `SdramTileAttributeFetch`, and `TopTang20kHdmi`.
  *
  * Elaboration-time safety:
  *   - Uses `+^` (expanding add) so the internal sum never truncates
  *   - Computes the required wrap-tree depth from the operand widths so any
  *     sum up to `(2^coordWidth - 1) + (2^scrollWidth - 1)` folds correctly
  *     into `[0, mapWidth)`. Previous ad-hoc code assumed ≤2 wraps; callers
  *     with large coord+scroll combos (e.g. `tileIdx*16 + scrollX`) could
  *     reach 3+ wraps which silently corrupted outputs
  *
  * Interface:
  *   - `coord`  : coord within the map space (e.g. hCounter, tileIdx*TileWidth)
  *   - `scroll` : scroll offset in the same space (0..any)
  *   - `result` : `(coord + scroll) mod mapWidth`, width = log2Up(mapWidth)
  *
  * `coordWidth` and `scrollWidth` default to the widths that cover the
  * map fully, but callers can override to match their upstream signals
  * exactly and avoid extra resize wrappers.
  */
case class ScrollWrap(
    mapWidth:    Int,
    coordWidth:  Int = -1,
    scrollWidth: Int = -1
) extends Component {
  require(mapWidth > 0, s"mapWidth must be positive; got $mapWidth")

  // Default widths cover the map's pixel range.
  private val cWidth = if (coordWidth  < 0) log2Up(mapWidth)     else coordWidth
  private val sWidth = if (scrollWidth < 0) log2Up(mapWidth + 1) else scrollWidth

  // Maximum possible sum and required wrap count.
  private val maxCoord  = (1L << cWidth) - 1L
  private val maxScroll = (1L << sWidth) - 1L
  private val maxSum    = maxCoord + maxScroll
  private val maxQ      = (maxSum / mapWidth.toLong).toInt      // largest needed multiplier

  // Enough bits to hold the un-wrapped sum AND all intermediate thresholds.
  private val thresholdMax = maxQ.toLong * mapWidth.toLong
  private val sumBits      = log2Up(math.max(maxSum, thresholdMax) + 1)
  private val outBits      = log2Up(mapWidth)

  val io = new Bundle {
    val coord  = in  UInt(cWidth bits)
    val scroll = in  UInt(sWidth bits)
    val result = out UInt(outBits bits)
  }

  // Expanding add prevents truncation; resize to a width that also covers
  // the largest threshold constant we'll compare against.
  val sum = (io.coord +^ io.scroll).resize(sumBits)

  // Wrap tree — walk thresholds from largest (maxQ * mapWidth) down to 1*mapWidth.
  // `foldLeft` starts from the no-wrap default (sum) and wraps with each
  // larger branch, so the outermost Mux tests the LARGEST threshold first,
  // which is what we want: any sum falling in [k*mapWidth, (k+1)*mapWidth)
  // matches exactly one branch and yields a result in [0, mapWidth).
  val wrapped: UInt = (1 to maxQ).foldLeft[UInt](sum) { (acc, q) =>
    val threshold = q * mapWidth
    Mux(
      sum >= U(threshold, sumBits bits),
      (sum - U(threshold, sumBits bits)).resize(sumBits),
      acc
    )
  }

  io.result := wrapped.resize(outBits)
}
