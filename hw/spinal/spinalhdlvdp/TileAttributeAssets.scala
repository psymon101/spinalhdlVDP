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

  /** Tile map for the palette-bank checkerboard proof scene.
    *
    * All 1200 tiles reference tile 0 (gradient). The visible differentiation
    * comes from the attribute map's paletteBank field.
    */
  def tileMapBytesInit: Seq[Bits] =
    Seq.fill(MapEntries)(B(0, 8 bits))

  /** Attribute map: 2×2 quadrants (20×15 tiles each) pick palette banks 0/1/2/3.
    * Priority bit set on the bottom-right quadrant only, so the optional
    * metadata proof can tell priority from non-priority at a glance.
    */
  def attributeMapBytesInit: Seq[Bits] = {
    val out = new scala.collection.mutable.ArrayBuffer[Bits]()
    for (ty <- 0 until MapTilesY; tx <- 0 until MapTilesX) {
      val left = tx < (MapTilesX / 2)
      val top  = ty < (MapTilesY / 2)
      // Banks 1-4 (not 0) so bank 0 remains reserved for the legacy L1 16-color
      // palette; the checkerboard quadrants render from the R4 color ramps.
      val bank = (top, left) match {
        case (true,  true)  => 1  // top-left:     reds
        case (true,  false) => 2  // top-right:    greens
        case (false, true)  => 3  // bottom-left:  blues
        case (false, false) => 4  // bottom-right: grayscale
      }
      val priority = !top && !left
      out += B(packAttr(bank, priority), 8 bits)
    }
    out.toSeq
  }

  // --- Palette ----------------------------------------------------------------
  // 128 entries (8 banks × 16). Each bank is visually distinct so the
  // checkerboard quadrants render as obviously different color families.
  //
  // Bank layout for banks used by the proof scene (0..3). Higher banks are
  // populated with placeholder ramps so every index in the 128-entry Mem has a
  // defined value (GT-022 safety + clean Verilog init).
  private def rgb(r: Int, g: Int, b: Int): BigInt =
    (BigInt(b & 0xFF) << 16) | (BigInt(g & 0xFF) << 8) | BigInt(r & 0xFF)

  // Bank 0 preserves the legacy VdpTop 16-color palette so L1 (still 3bpp +
  // implicit bank 0) renders bit-identical to pre-R4 hardware. Only the low 8
  // entries are semantically used by L1; 8..15 are filler for the 4bpp index
  // space.
  private val legacyPalette: Seq[BigInt] = Seq(
    rgb(0x00, 0x00, 0x00), // 0 black
    rgb(0xFF, 0xFF, 0xFF), // 1 white
    rgb(0xFF, 0x00, 0x00), // 2 red
    rgb(0x00, 0xC0, 0x00), // 3 green
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

  private def ramp(bank: Int): Seq[BigInt] = (0 until PaletteEntries).map { i =>
    val t = (i * 255) / 15
    bank match {
      case 0 => legacyPalette(i)
      case 1 => rgb(t, 0, 0)             // reds
      case 2 => rgb(0, t, 0)             // greens
      case 3 => rgb(0, 0, t)             // blues
      case 4 => rgb(t, t, t)             // grayscale
      case 5 => rgb(t, t, 0)             // yellows
      case 6 => rgb(t, 0, t)             // magentas
      case 7 => rgb(0, t, t)             // cyans
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
    val tileIdx = 0  // all tiles are Tile 0 in the proof scene
    val attr = attrByteAt(tileX, tileY)
    val idx = tilePatterns(tileIdx)(pixelY)(pixelX)
    (idx, attrBank(attr), attrPriority(attr))
  }

  def attrByteAt(tx: Int, ty: Int): Int = {
    val left = tx < (MapTilesX / 2)
    val top  = ty < (MapTilesY / 2)
    val bank = (top, left) match {
      case (true,  true)  => 1
      case (true,  false) => 2
      case (false, true)  => 3
      case (false, false) => 4
    }
    packAttr(bank, priority = !top && !left)
  }

  // Sanity counts
  require(tileRowBytesInit.length == TileCount * TileHeight * TileRowBytes,
    s"tileRowBytes size mismatch: ${tileRowBytesInit.length}")
  require(tileMapBytesInit.length == MapEntries)
  require(attributeMapBytesInit.length == MapEntries)
  require(paletteInit.length == PaletteDepth)
}
