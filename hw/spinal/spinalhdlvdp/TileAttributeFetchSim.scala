package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** R4 `SdramTileAttributeFetch` validation sim.
  *
  * Per CoralReef #6765 direction: focus on **attribute variation** so the
  * "bank-2 uniformity" hardware observation (#6763) can be isolated as either
  * init/ROM bug or address/math bug BEFORE any more hardware iteration.
  *
  * Case ordering follows #6765:
  *   1. Known tile, known attribute — fetch one tile at a known (x,y),
  *      assert pixelIndex + pixelPaletteBank match expected init data
  *   2. Checkerboard quadrant — fetch tiles from each of the 4 macro-quadrants,
  *      assert each returns its distinct palette bank (1, 2, 3, 4)
  *   3. Priority bit propagation — pixelPriority matches the attribute MSB
  */
object TileAttributeFetchSim extends App {
  import TileAttributeAssets._

  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(64800000 Hz))
    SdramTileAttributeFetch(sdramCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    // ------- Behavioral SDRAM model -----------------------------------------
    val mem = mutable.HashMap[Int, Int]()
    def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
    def readWord(a: Int): Long = {
      val base = a & ~3
      (readByte(base).toLong & 0xFF) |
        ((readByte(base + 1).toLong & 0xFF) << 8) |
        ((readByte(base + 2).toLong & 0xFF) << 16) |
        ((readByte(base + 3).toLong & 0xFF) << 24)
    }

    // Initialize IO
    dut.io.sdramDout         #= 0
    dut.io.sdramDout32       #= 0
    dut.io.sdramDataReady    #= false
    dut.io.sdramBusy         #= true
    dut.io.fetchGrant        #= false
    dut.io.fetchSlotValid    #= true   // rollback: readGate ties True, but drive true anyway
    dut.io.fetchPreAnnounce  #= false
    dut.io.fetchLine         #= 0
    dut.io.fetchScrollX      #= 0
    dut.io.fetchScrollY      #= 0
    dut.io.pixelAddr         #= 0

    fork {
      for (_ <- 0 until 30) dut.sdramCd.waitSampling()
      dut.io.sdramBusy #= false

      var state = "idle"
      var timer = 0
      var op = ""
      var latchedAddr = 0
      var latchedDin = 0

      while (true) {
        dut.sdramCd.waitSampling()
        dut.io.sdramDataReady #= false

        state match {
          case "idle" =>
            if (dut.io.sdramRd.toBoolean) {
              op = "rd"; latchedAddr = dut.io.sdramAddr.toInt
              dut.io.sdramBusy #= true
              state = "wait"; timer = 3
            } else if (dut.io.sdramWr.toBoolean) {
              op = "wr"; latchedAddr = dut.io.sdramAddr.toInt
              latchedDin = dut.io.sdramDin.toInt & 0xFF
              dut.io.sdramBusy #= true
              state = "wait"; timer = 5
            } else if (dut.io.sdramRefresh.toBoolean) {
              op = "rf"
              dut.io.sdramBusy #= true
              state = "wait"; timer = 4
            }

          case "wait" =>
            timer -= 1
            if (timer == 0) {
              op match {
                case "rd" =>
                  dut.io.sdramDout      #= readByte(latchedAddr) & 0xFF
                  dut.io.sdramDout32    #= BigInt(readWord(latchedAddr) & 0xFFFFFFFFL)
                  dut.io.sdramDataReady #= true
                  state = "rdDone"
                case "wr" =>
                  mem(latchedAddr & 0x7fffff) = latchedDin
                  dut.io.sdramBusy #= false
                  state = "idle"
                case "rf" =>
                  dut.io.sdramBusy #= false
                  state = "idle"
              }
            }

          case "rdDone" =>
            dut.io.sdramBusy #= false
            state = "idle"
        }
      }
    }

    // Let reset propagate + BufferCC stages settle.
    dut.clockDomain.waitSampling(50)

    // ------- Wait for boot-copy complete ------------------------------------
    var timeout = 600000
    while (!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone")
    println(s"[sim] bootDone; mem entries=${mem.size}")

    // ------- Wait for memtest to pass ---------------------------------------
    timeout = 600000
    while (!dut.io.memtestPass.toBoolean && !dut.io.memtestFail.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for memtest")
    assert(dut.io.memtestPass.toBoolean, "memtestFail")
    println("[sim] memtestPass")

    // ------- Boot-copy spot-checks ------------------------------------------
    // Every tile map byte should be 0 (all tiles are tile 0 in the proof scene).
    for (i <- Seq(0, 1, 799, 800, 1199)) {
      assert(readByte(TileMapBase + i) == 0, s"tileMap[$i] nonzero")
    }
    println("[sim] boot: tileMap all-zero OK")

    // Attribute map boot-copy: verify per-quadrant bank values landed in SDRAM.
    // This isolates "init/ROM bug" from "address/math bug".
    def expectedAttr(tx: Int, ty: Int): Int = attrByteAt(tx, ty)
    val probeQuads = Seq(
      (0, 0,   1, false),       // top-left     → bank 1, no priority
      (25, 0,  2, false),       // top-right    → bank 2, no priority
      (0, 20,  3, false),       // bottom-left  → bank 3, no priority
      (25, 20, 4, true)         // bottom-right → bank 4, priority SET
    )
    for ((tx, ty, expBank, expPrio) <- probeQuads) {
      val byte = readByte(AttributeMapBase + ty * MapTilesX + tx)
      val bank = byte & 0x7
      val prio = (byte & 0x8) != 0
      assert(bank == expBank, s"boot attr[$tx,$ty]: bank got=$bank exp=$expBank (byte=0x${byte.toHexString})")
      assert(prio == expPrio, s"boot attr[$tx,$ty]: prio got=$prio exp=$expPrio")
    }
    println("[sim] boot: attribute map per-quadrant values OK (init is correct)")

    // ------- Fetch helpers --------------------------------------------------
    def fireFetch(y: Int, scrollX: Int = 0, scrollY: Int = 0): Unit = {
      dut.io.fetchLine    #= y
      dut.io.fetchScrollX #= scrollX
      dut.io.fetchScrollY #= scrollY
      dut.io.fetchGrant   #= true
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      dut.io.fetchGrant   #= false
      // Wait for the engine to finish pushing the whole line through the FIFO.
      for (_ <- 0 until 30000) dut.clockDomain.waitSampling()
    }

    // The fetch engine fills a ping-pong line buffer. Ping-pong: each grant
    // flips the write buffer, so we need TWO grants for the just-fetched line
    // to be visible on the read side.
    def fireFetchTwice(y: Int): Unit = {
      fireFetch(y)
      dut.clockDomain.waitSampling(100)
      fireFetch(y)
    }

    def readPixel(x: Int): (Int, Int, Boolean) = {
      dut.io.pixelAddr #= x
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()   // readSync 1-cycle latency
      (dut.io.pixelIndex.toInt, dut.io.pixelPaletteBank.toInt, dut.io.pixelPriority.toBoolean)
    }

    // Park pixelAddr out of the underrun trigger zone while fetching.
    dut.io.pixelAddr #= 0

    // ------- Case 1: known tile + known attribute @ (x=8, y=0) --------------
    // At y=0, tileY=0, the top-left quadrant. tx=0 → bank=1 (reds), prio=0.
    // Tile 0 is the gradient 0..15, so pixel at x=8 is index=8.
    fireFetchTwice(0)
    val (idx0, bank0, prio0) = readPixel(8)
    assert(idx0 == 8,     s"case1: pixelIndex got=$idx0 exp=8")
    assert(bank0 == 1,    s"case1: pixelBank got=$bank0 exp=1 (top-left reds)")
    assert(prio0 == false, s"case1: pixelPriority got=$prio0 exp=false")
    println("[sim] case1 known (x=8, y=0) top-left → idx=8 bank=1 prio=0 — OK")

    // ------- Case 2: four-quadrant palette-bank checker ---------------------
    // Pick a tile x,y from each macro-quadrant and assert the returned bank
    // matches the attribute map. This is the authoritative test for the
    // bank-uniformity bug observed on hardware (#6763).
    val cases2 = Seq(
      (8,   0,   1, "top-left (reds)"),
      (328, 0,   2, "top-right (greens)"),
      (8,   240, 3, "bottom-left (blues)"),
      (328, 240, 4, "bottom-right (grayscale)")
    )
    for ((px, py, expBank, label) <- cases2) {
      fireFetchTwice(py)
      val (_, bank, _) = readPixel(px)
      assert(bank == expBank,
        s"case2 $label: pixelBank got=$bank exp=$expBank at (px=$px, py=$py)")
      println(s"[sim] case2 ($px,$py) $label bank=$bank — OK")
    }

    // ------- Case 3: priority bit propagation -------------------------------
    // Bottom-right quadrant has priority=1. Bottom-left has priority=0.
    fireFetchTwice(240)
    val (_, _, prioBR) = readPixel(328)
    val (_, _, prioBL) = readPixel(8)
    assert(prioBR == true,  s"case3: bot-right prio got=$prioBR exp=true")
    assert(prioBL == false, s"case3: bot-left  prio got=$prioBL exp=false")
    println("[sim] case3 priority bit propagates BR=1 BL=0 — OK")

    println("[sim] TileAttributeFetchSim: PASS")
  }
}
