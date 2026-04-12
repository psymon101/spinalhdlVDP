package spinalhdlvdp

import spinal.core._

case class BasicPatternSource() extends Component {
  val io = new Bundle {
    val x = in UInt(10 bits)
    val y = in UInt(10 bits)
    val scrollX = in UInt(10 bits)
    val scrollY = in UInt(10 bits)
    val pixelIndex = out Bits(3 bits)
  }

  import BasicPatternSource._

  val scrolledXRaw = (io.x + io.scrollX).resize(10 bits)
  val scrolledYRaw = (io.y + io.scrollY).resize(10 bits)

  // Wrap to tilemap pixel dimensions (640 x 480) for seamless scrolling.
  val scrolledX = UInt(10 bits)
  when(scrolledXRaw >= U(MapTilesX * TileWidth, 10 bits)) {
    scrolledX := scrolledXRaw - U(MapTilesX * TileWidth, 10 bits)
  } otherwise {
    scrolledX := scrolledXRaw
  }

  val scrolledY = UInt(10 bits)
  when(scrolledYRaw >= U(MapTilesY * TileHeight * 2, 10 bits)) {
    scrolledY := scrolledYRaw - U(MapTilesY * TileHeight * 2, 10 bits)
  }.elsewhen(scrolledYRaw >= U(MapTilesY * TileHeight, 10 bits)) {
    scrolledY := scrolledYRaw - U(MapTilesY * TileHeight, 10 bits)
  }.otherwise {
    scrolledY := scrolledYRaw
  }

  val tileX = scrolledX(9 downto 4).resize(log2Up(MapTilesX) bits)
  val tileY = scrolledY(8 downto 4).resize(log2Up(MapTilesY) bits)
  val pixelX = scrolledX(3 downto 0)
  val pixelY = scrolledY(3 downto 0)

  val tileMap = Mem(Bits(TileIndexBits bits), initialContent = tileMapInit)
  val tileRows = Mem(Bits(TileRowWidth bits), initialContent = tileRowInit)

  val tileAddress = (tileY * U(MapTilesX, log2Up(MapTilesX + 1) bits) + tileX.resized).resized
  val tileIndex = tileMap.readAsync(tileAddress).asUInt
  val rowAddress = (tileIndex.asBits ## pixelY.asBits).asUInt
  val rowData = tileRows.readAsync(rowAddress)

  io.pixelIndex := rowData.subdivideIn(PixelBits bits)(pixelX)
}

object BasicPatternSource {
  val TileWidth = 16
  val TileHeight = 16
  val MapTilesX = 40
  val MapTilesY = 30
  val TileCount = 8
  val PixelBits = 3
  val TileIndexBits = log2Up(TileCount)
  val TileRowWidth = TileWidth * PixelBits

  private val colorBlack = 0
  private val colorWhite = 1
  private val colorRed = 2
  private val colorGreen = 3
  private val colorBlue = 4
  private val colorYellow = 5
  private val colorCyan = 6
  private val colorMagenta = 7

  private def row(values: Seq[Int]): Bits = {
    require(values.length == TileWidth)
    val packed = values.reverse.foldLeft(BigInt(0)) { case (acc, value) =>
      require(value >= 0 && value < (1 << PixelBits))
      (acc << PixelBits) | BigInt(value)
    }
    B(packed, TileRowWidth bits)
  }

  private def tileRowsFrom(f: Int => Seq[Int]): Seq[Seq[Int]] =
    (0 until TileHeight).map(y => f(y))

  private val tile0 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      if (((x >> 2) + (y >> 2)) % 2 == 0) colorRed else colorBlack
    }
  }

  private val tile1 = tileRowsFrom { _ =>
    (0 until TileWidth).map { x =>
      if ((x & 0x3) < 2) colorGreen else colorWhite
    }
  }

  private val tile2 = tileRowsFrom { y =>
    (0 until TileWidth).map { _ =>
      if ((y & 0x3) < 2) colorBlue else colorWhite
    }
  }

  private val tile3 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      if (((x + y) & 0x7) < 4) colorYellow else colorBlack
    }
  }

  private val tile4 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      if (x == 0 || x == 15 || y == 0 || y == 15) colorWhite
      else if ((x >= 4 && x <= 11) && (y >= 4 && y <= 11)) colorCyan
      else colorBlack
    }
  }

  private val tile5 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      if (x == y || x + y == 15) colorMagenta else colorBlack
    }
  }

  private val tile6 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      if (x == 7 || x == 8 || y == 7 || y == 8) colorWhite else colorBlue
    }
  }

  private val tile7 = tileRowsFrom { y =>
    (0 until TileWidth).map { x =>
      val dx = x - 7
      val dy = y - 7
      if ((dx * dx + dy * dy) <= 20) colorYellow
      else if ((dx * dx + dy * dy) <= 40) colorRed
      else colorBlack
    }
  }

  private val tilePatterns: Seq[Seq[Seq[Int]]] = Seq(tile0, tile1, tile2, tile3, tile4, tile5, tile6, tile7)

  val tileRowInit: Seq[Bits] = tilePatterns.flatten.map(row)

  val tileMapInit: Seq[Bits] = (0 until MapTilesY).flatMap { tileY =>
    (0 until MapTilesX).map { tileX =>
      val tile = ((tileX >> 1) + tileY) & 0x7
      B(tile, TileIndexBits bits)
    }
  }

  def expectedPixelIndex(x: Int, y: Int, scrollX: Int = 0, scrollY: Int = 0): Int = {
    val mapPixelW = MapTilesX * TileWidth   // 640
    val mapPixelH = MapTilesY * TileHeight  // 480
    val sxRaw = (x + scrollX) & 0x3FF
    val syRaw = (y + scrollY) & 0x3FF
    val sx = if (sxRaw >= mapPixelW) sxRaw - mapPixelW else sxRaw
    val sy0 = if (syRaw >= mapPixelH * 2) syRaw - mapPixelH * 2 else syRaw
    val sy = if (sy0 >= mapPixelH) sy0 - mapPixelH else sy0
    val tileX = sx / TileWidth
    val tileY = sy / TileHeight
    val pixelX = sx % TileWidth
    val pixelY = sy % TileHeight
    val tile = ((tileX >> 1) + tileY) & 0x7
    tilePatterns(tile)(pixelY)(pixelX)
  }
}
