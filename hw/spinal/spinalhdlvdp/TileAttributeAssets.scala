package spinalhdlvdp

import spinal.core._

/** R4 proof-scene assets: 4bpp tile rows + tile map + attribute map +
  * multi-bank palette.
  *
  * Deliberately disjoint from `BasicPatternSource` (3bpp) so the L1 / on-chip
  * L0 paths stay bit-identical to the pre-R4 baseline. The R4 fetch engine
  * boot-copies its own data into SDRAM at the addresses below (per CoralReef
  * #6755); the old 3bpp regions become dead but harmless.
  *
  * Proof scene: palette-bank checkerboard. Four 20×15 tile quadrants share
  * the same tile index (Tile 0, a 0..15 horizontal gradient) but carry
  * different attribute bytes so each quadrant renders from a different
  * palette bank. Priority bits are populated on a subset of tiles so the
  * optional metadata proof has something to test.
  */
object TileAttributeAssets {
  // --- Tile geometry ---------------------------------------------------------
  val TilePixelBits = 4                          // R4 bit depth
  val TileWidth     = 16
  val TileHeight    = 16
  val TileCount     = 4                          // power-of-two, room for variants
  val TileRowBits   = TileWidth * TilePixelBits  // 64
  val TileRowBytes  = TileRowBits / 8            // 8 bytes per row
  val TileRowStride = TileRowBytes               // SDRAM bytes per row slot

  // --- Map geometry ----------------------------------------------------------
  val MapTilesX = 40
  val MapTilesY = 30
  val MapPixelsX = MapTilesX * TileWidth   // 640
  val MapPixelsY = MapTilesY * TileHeight  // 480
  val MapEntries = MapTilesX * MapTilesY   // 1200

  // --- SDRAM layout (per #6755) ----------------------------------------------
  val TileMapBase      = 0x6000
  val AttributeMapBase = 0x7000
  val TileRowBase      = 0x8000

  // --- Palette geometry ------------------------------------------------------
  val PaletteBankBits = 3
  val PaletteBanks    = 1 << PaletteBankBits        // 8
  val PaletteEntries  = 16                           // 4bpp index space
  val PaletteDepth    = PaletteBanks * PaletteEntries // 128 (power-of-two, GT-022)

  // --- Attribute byte layout -------------------------------------------------
  // [2:0] paletteBank, [3] priority, [7:4] reserved
  def packAttr(bank: Int, priority: Boolean): Int = {
    require(bank >= 0 && bank < PaletteBanks)
    (bank & 0x7) | (if (priority) 0x8 else 0)
  }
  def attrBank(b: Int): Int     = b & 0x7
  def attrPriority(b: Int): Boolean = ((b >> 3) & 0x1) == 1

  // --- Tile patterns (4bpp) --------------------------------------------------
  // Tile 0: horizontal gradient 0..15 (each column = column index). Makes all
  // 16 palette slots in whichever bank is active visible side-by-side.
  private def tileGradient: Seq[Seq[Int]] =
    (0 until TileHeight).map { _ =>
      (0 until TileWidth).map(x => x & 0xF)
    }

  // Tile 1: diagonal stripes through all 16 slots.
  private def tileDiagonal: Seq[Seq[Int]] =
    (0 until TileHeight).map { y =>
      (0 until TileWidth).map(x => (x + y) & 0xF)
    }

  // Tile 2: concentric rings (distance from center, clamped to 0..15).
  private def tileRings: Seq[Seq[Int]] =
    (0 until TileHeight).map { y =>
      (0 until TileWidth).map { x =>
        val dx = x - 7
        val dy = y - 7
        val d2 = dx * dx + dy * dy
        val d  = math.sqrt(d2.toDouble).round.toInt
        d & 0xF
      }
    }

  // Tile 3: checkerboard toggling index 0 vs 15 so priority-over-bg contrast
  // is easy to see.
  private def tileCheck: Seq[Seq[Int]] =
    (0 until TileHeight).map { y =>
      (0 until TileWidth).map(x => if (((x >> 1) + (y >> 1)) % 2 == 0) 0 else 15)
    }

  private val tilePatterns: Seq[Seq[Seq[Int]]] =
    Seq(tileGradient, tileDiagonal, tileRings, tileCheck)

  require(tilePatterns.length == TileCount,
    s"tilePatterns.length=${tilePatterns.length} must equal TileCount=$TileCount")

  /** Tile row bytes in SDRAM order: tile `t`, row `y`, byte `b` at
    * offset `(t*TileHeight + y)*TileRowBytes + b`. Bytes are little-endian
    * within the 64-bit row (low-order pixels first).
    */
  def tileRowBytesInit: Seq[Bits] = tilePatterns.flatMap { tile =>
    tile.flatMap { rowPixels =>
      val packed = rowPixels.reverse.foldLeft(BigInt(0)) { case (acc, v) =>
        require(v >= 0 && v < (1 << TilePixelBits))
        (acc << TilePixelBits) | BigInt(v)
      }
      (0 until TileRowBytes).map(i => B((packed >> (i * 8)) & BigInt(0xFF), 8 bits))
    }
  }

  // Map ROMs are padded to the next power-of-two depth (2048) per GT-022. The
  // padding entries are unused at runtime since address math stays in
  // [0, MapEntries). Identical fix pattern to BasicPatternSource.tileMapInit.
  val MapRomDepth = 2048
  require(isPow2(MapRomDepth))

  /** Tile map. R4.1d Checkpoint C: 2×2 repeating pattern of tile indices
    * 0/1/2/3 so the bitplane-checkerboard diagnostic exposes every dual-plane
    * sub-field across the full screen. The pattern is:
    *
    *     tx even, ty even → tile 0  (pixel value 0b00)
    *     tx odd,  ty even → tile 1  (pixel value 0b01)
    *     tx even, ty odd  → tile 2  (pixel value 0b10)
    *     tx odd,  ty odd  → tile 3  (pixel value 0b11)
    *
    * Packed 4bpp mode still renders meaningfully (gradient/diagonal/rings/
    * checker tiles selected per quadrant) — only the planar/shuffled mode
    * needs the diagnostic semantics this layout supports.
    */
  def tileMapBytesInit: Seq[Bits] = {
    val out = new scala.collection.mutable.ArrayBuffer[Bits]()
    for (ty <- 0 until MapTilesY; tx <- 0 until MapTilesX) {
      val tileIdx = (ty & 1) * 2 + (tx & 1)
      out += B(tileIdx, 8 bits)
    }
    val padded = out.toSeq ++ Seq.fill(MapRomDepth - MapEntries)(B(0, 8 bits))
    padded
  }

  /** R4.1c-ready attribute map: packed-friendly byte pattern so both linear
    * and packed-2×2 decode modes produce unambiguous, testable output.
    *
    * Byte layout chosen: `0xE4` for all non-BR tiles, `0xEC` for the bottom-
    * right quadrant (priority bit set for linear-mode priority proof).
    *
    *   0xE4 = 0b11100100 → packed fields TL=00, TR=01, BL=10, BR=11
    *                       → banks 0, 1, 2, 3 per 2×2 block
    *                       linear: bank=4 (grayscale), priority=0
    *   0xEC = 0b11101100 → packed fields TL=00, TR=11, BL=10, BR=11
    *                       linear: bank=4, priority=1
    *
    * Linear-mode R4 appearance: a uniform grayscale bank-4 field with
    * priority-1 in the BR quadrant (bank assignment is uniform, but the mode
    * is still exercised; the four-way quadrant demo was swapped for a
    * packed-compatible asset per R4.1c Stage 5 hardware-proof mandate).
    * Packed-mode R4.1c appearance: every 2×2 tile block renders with four
    * distinct palette banks (0,1,2,3), producing the bank-checkerboard the
    * hardware proof was designed to make visible.
    */
  val NonBrAttrByte = 0xE4
  val BrAttrByte    = 0xEC

  def attributeMapBytesInit: Seq[Bits] = {
    val out = new scala.collection.mutable.ArrayBuffer[Bits]()
    for (ty <- 0 until MapTilesY; tx <- 0 until MapTilesX) {
      val left = tx < (MapTilesX / 2)
      val top  = ty < (MapTilesY / 2)
      val byte = if (!top && !left) BrAttrByte else NonBrAttrByte
      out += B(byte, 8 bits)
    }
    val padded = out.toSeq ++ Seq.fill(MapRomDepth - MapEntries)(B(0, 8 bits))
    padded
  }

  // --- Palette ----------------------------------------------------------------
  // 128 entries (8 banks × 16). Each bank is visually distinct so the
  // checkerboard quadrants render as obviously different color families.
  //
  // Bank layout for banks used by the proof scene (0..3). Higher banks are
  // populated with placeholder ramps so every index in the 128-entry Mem has a
  // defined value (GT-022 safety + clean Verilog init).
  // VdpTop unpacks palette bits as [red(23:16), green(15:8), blue(7:0)], so
  // red must be the MSByte.
  private def rgb(r: Int, g: Int, b: Int): BigInt =
    (BigInt(r & 0xFF) << 16) | (BigInt(g & 0xFF) << 8) | BigInt(b & 0xFF)

  // Bank 0 preserves the legacy VdpTop 16-color palette so L1 (still 3bpp +
  // implicit bank 0) renders bit-identical to pre-R4 hardware. Only the low 8
  // entries are semantically used by L1; 8..15 are filler for the 4bpp index
  // space.
  private val legacyPalette: Seq[BigInt] = Seq(
    rgb(0x00, 0x00, 0x00), // 0 black
    rgb(0xFF, 0xFF, 0xFF), // 1 white
    rgb(0xFF, 0x00, 0x00), // 2 red
    rgb(0x00, 0xFF, 0x00), // 3 green
    rgb(0x00, 0x00, 0xFF), // 4 blue
    rgb(0xFF, 0xFF, 0x00), // 5 yellow
    rgb(0x00, 0xFF, 0xFF), // 6 cyan
    rgb(0xFF, 0x00, 0xFF), // 7 magenta
    // Filler 8..15: low-chroma repeats so an accidental high index does not
    // emit garbage.
    rgb(0x40, 0x40, 0x40),
    rgb(0x80, 0x80, 0x80),
    rgb(0xC0, 0xC0, 0xC0),
    rgb(0x80, 0x00, 0x00),
    rgb(0x00, 0x80, 0x00),
    rgb(0x00, 0x00, 0x80),
    rgb(0x80, 0x80, 0x00),
    rgb(0x80, 0x00, 0x80)
  )

  // R4.1b stage 4: banks 1-4 use a 4-step ramp (indices 0..3 span 0→255) with
  // saturation on indices 4..15. This way:
  //   - Planar 2bpp (indices 0..3): 4 distinct shades spanning the full range
  //   - Packed 4bpp (indices 0..15): same 4 shades at the low end + saturated
  //     color at the high end — visually busier than planar
  // Task 40 v1.1 — Pepto palette (2001), 16 entries, circuitry-accurate
  // VIC-II colors per Philip Timmermann's resistor-network model.
  // Source: CyanPeak #8288 / #8294 (Path D ruling).
  // Installed in Bank 7 only; banks 0..6 remain bit-identical to pre-Task-40.
  val peptoPalette: Seq[BigInt] = Seq(
    0x000000, // 0: Black
    0xFFFFFF, // 1: White
    0x68372B, // 2: Red
    0x70A4B2, // 3: Cyan
    0x6F3D86, // 4: Purple
    0x588D43, // 5: Green
    0x352879, // 6: Blue
    0xB8C76F, // 7: Yellow
    0x6F4F25, // 8: Orange
    0x433900, // 9: Brown
    0x9A6759, // 10: Light Red
    0x444444, // 11: Dark Grey
    0x6C6C6C, // 12: Grey
    0x9AD284, // 13: Light Green
    0x6C5EB5, // 14: Light Blue
    0x959595  // 15: Light Grey
  ).map(BigInt(_))

  def ramp(bank: Int): Seq[BigInt] = (0 until PaletteEntries).map { i =>
    val t = math.min(i * 85, 255)   // 0, 85, 170, 255, 255, ..., 255
    bank match {
      case 0 => legacyPalette(i)
      case 1 => rgb(t, 0, 0)             // reds (4-step in planar)
      case 2 => rgb(0, t, 0)             // greens
      case 3 => rgb(0, 0, t)             // blues
      case 4 => rgb(t, t, t)             // grayscale
      case 5 => rgb(t, t, 0)             // yellows
      case 6 => rgb(t, 0, t)             // magentas
      case 7 => peptoPalette(i)          // Task 40 v1.1: Pepto-2001 palette
      case _ => BigInt(0)
    }
  }

  def paletteInit: Seq[Bits] = {
    require(isPow2(PaletteDepth), s"PaletteDepth=$PaletteDepth must be power-of-two (GT-022)")
    (0 until PaletteBanks).flatMap(b => ramp(b)).map(v => B(v, 24 bits))
  }

  // --- Software reference (sim cross-check) ----------------------------------
  def expectedPixel(x: Int, y: Int): (Int, Int, Boolean) = {
    val tileX = x / TileWidth
    val tileY = y / TileHeight
    val pixelX = x % TileWidth
    val pixelY = y % TileHeight
    val tileIdx = (tileY & 1) * 2 + (tileX & 1)  // R4.1d Checkpoint C 2×2 map
    val attr = attrByteAt(tileX, tileY)
    val idx = tilePatterns(tileIdx)(pixelY)(pixelX)
    (idx, attrBank(attr), attrPriority(attr))
  }

  def attrByteAt(tx: Int, ty: Int): Int = {
    val left = tx < (MapTilesX / 2)
    val top  = ty < (MapTilesY / 2)
    if (!top && !left) BrAttrByte else NonBrAttrByte
  }

  // Sanity counts
  require(tileRowBytesInit.length == TileCount * TileHeight * TileRowBytes,
    s"tileRowBytes size mismatch: ${tileRowBytesInit.length}")
  require(tileMapBytesInit.length == MapRomDepth)
  require(attributeMapBytesInit.length == MapRomDepth)
  require(paletteInit.length == PaletteDepth)
}
