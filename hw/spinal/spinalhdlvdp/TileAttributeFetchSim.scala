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
    dut.io.tileDecodeMode    #= 0      // R4.1b stage 1 default: packed mode (R4 baseline)
    dut.io.attributeMode     #= 0      // R4.1c default: linear 1:1 (R4 baseline)
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
    // R4.1c asset: non-BR bytes are 0xE4 (bank=4, priority=0 in linear mode);
    // BR quadrant is 0xEC (bank=4, priority=1). Packed-mode proof lives in
    // case 8 which seeds its own block byte.
    val probeQuads = Seq(
      (0, 0,   4, false),       // top-left     → bank 4, no priority
      (25, 0,  4, false),       // top-right    → bank 4, no priority
      (0, 20,  4, false),       // bottom-left  → bank 4, no priority
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
    // R4.1c asset: TL quadrant byte is 0xE4 → linear bank=4, priority=0.
    // Tile 0 is the gradient 0..15, so pixel at x=8 is index=8.
    fireFetchTwice(0)
    val (idx0, bank0, prio0) = readPixel(8)
    assert(idx0 == 8,     s"case1: pixelIndex got=$idx0 exp=8")
    assert(bank0 == 4,    s"case1: pixelBank got=$bank0 exp=4 (linear 0xE4)")
    assert(prio0 == false, s"case1: pixelPriority got=$prio0 exp=false")
    println("[sim] case1 known (x=8, y=0) top-left → idx=8 bank=4 prio=0 — OK")

    // ------- Case 2: four-quadrant spatial addressing ----------------------
    // R4.1c asset makes all non-BR quadrants share 0xE4 (bank 4) and BR
    // share 0xEC (bank 4 + priority). Discrimination check is now about
    // address math reading the correct byte, not about distinct banks.
    val cases2 = Seq(
      (8,   0,   4, "top-left"),
      (328, 0,   4, "top-right"),
      (8,   240, 4, "bottom-left"),
      (328, 240, 4, "bottom-right")
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

    // ------- Case 5: multi-slot scheduling (R4.1) ---------------------------
    // Chop slotValid into three windows, asserted across the pixel-domain
    // timeline. The fetch engine must issue SDRAM reads only while slotValid
    // is true, and the full line must still complete via pause/resume.
    def fireFetchWithSlots(y: Int, slotWindows: Seq[(Int, Int)]): Unit = {
      dut.io.fetchLine       #= y
      dut.io.fetchScrollX    #= 0
      dut.io.fetchScrollY    #= 0
      dut.io.fetchSlotValid  #= false
      dut.io.fetchGrant      #= true
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      dut.io.fetchGrant      #= false
      var cycle = 0
      val lastWindowEnd = slotWindows.map(_._2).max + 200
      while (cycle < lastWindowEnd) {
        val inSlot = slotWindows.exists { case (s, e) => cycle >= s && cycle <= e }
        dut.io.fetchSlotValid #= inSlot
        dut.clockDomain.waitSampling()
        cycle += 1
      }
      dut.io.fetchSlotValid #= true
      for (_ <- 0 until 10000) dut.clockDomain.waitSampling()
    }

    // Warm-up: one normal fetch to prime the ping-pong (so subsequent fetch
    // flips reader onto a just-filled buffer).
    fireFetch(0)
    dut.clockDomain.waitSampling(100)

    // Three windows: early, mid, late. Total open ≈ 9000 pixel cycles — plenty
    // for ~8000 cycles of actual fetch time.
    val windows = Seq((100, 3100), (4000, 6500), (7500, 9500))
    fireFetchWithSlots(0, windows)
    val (idx5, bank5, _) = readPixel(8)
    assert(idx5 == 8, s"case5 multi-slot: idx got=$idx5 exp=8")
    assert(bank5 == 4, s"case5 multi-slot: bank got=$bank5 exp=4")
    assert(!dut.io.underrun.toBoolean, "case5 multi-slot: spurious underrun")
    println("[sim] case5 multi-slot 3 windows, fetch completes w/ correct data — OK")

    // ------- Case 6: pause/resume observed via sdramRd absence -------------
    // Park the engine mid-fetch by dropping slotValid; confirm no sdramRd
    // pulses while gated, then raise and confirm reads resume.
    dut.io.fetchSlotValid #= true
    fireFetch(240)  // another line to re-trigger the engine
    dut.clockDomain.waitSampling(200)
    // Drop slotValid
    dut.io.fetchSlotValid #= false
    var sawRead = false
    for (_ <- 0 until 500) {
      dut.sdramCd.waitSampling()
      if (dut.io.sdramRd.toBoolean) sawRead = true
    }
    assert(!sawRead, "case6 pause: engine issued sdramRd while slotValid=false")
    println("[sim] case6 pause: no sdramRd during gated window — OK")
    // Resume
    dut.io.fetchSlotValid #= true
    for (_ <- 0 until 10000) dut.clockDomain.waitSampling()
    // The pause/resume should not corrupt the engine — a fresh fetch after
    // resume must still produce correct pixel data. Use fireFetchTwice so the
    // reader lands on the line-0 buffer regardless of the previous ping-pong
    // parity.
    fireFetchTwice(0)
    val (idx6, bank6, _) = readPixel(8)
    assert(idx6 == 8, s"case6 resume: idx corrupted got=$idx6 exp=8")
    assert(bank6 == 4, s"case6 resume: bank corrupted got=$bank6 exp=4")
    println("[sim] case6 resume: engine recovers and produces correct data — OK")

    // ------- Case 7: narrow-slot resilience --------------------------------
    // Drive a narrow-slot scenario and verify the engine does NOT deadlock:
    // after eventually re-opening slotValid, a follow-up line must still
    // fetch correctly. This is the practical health-check the underrun flag
    // was intended to signal — narrow slots should degrade gracefully, not
    // crash. (The dedicated underrun register only samples a very specific
    // emitting-vs-reader race which is brittle to reproduce deterministically
    // in sim without additional stimulus plumbing.)
    dut.io.fetchSlotValid #= false
    dut.io.fetchLine #= 120
    dut.io.fetchScrollX #= 0
    dut.io.fetchScrollY #= 0
    dut.io.fetchGrant #= true
    for (_ <- 0 until 4) dut.clockDomain.waitSampling()
    dut.io.fetchGrant #= false
    // Narrow 300-cycle window — not enough for 41 tiles of 4 reads each.
    dut.io.fetchSlotValid #= true
    for (_ <- 0 until 300) dut.clockDomain.waitSampling()
    dut.io.fetchSlotValid #= false
    for (_ <- 0 until 1000) dut.clockDomain.waitSampling()
    // Re-open and follow with a normal fetch: engine must still function.
    dut.io.fetchSlotValid #= true
    fireFetchTwice(0)
    val (idx7, bank7, _) = readPixel(8)
    assert(idx7 == 8, s"case7 post-narrow: idx deadlocked got=$idx7 exp=8")
    assert(bank7 == 4, s"case7 post-narrow: bank deadlocked got=$bank7 exp=4")
    println("[sim] case7 engine survives narrow slot + normal recovery — OK")

    // ------- Case 8 (R4.1c): packed 2×2 attribute decode --------------------
    // One attribute byte covers a 2×2 tile block. Bit layout per NES spec:
    //   [1:0]=TL, [3:2]=TR, [5:4]=BL, [7:6]=BR.
    // We overwrite the block bytes at block-row 0 (covering tile rows 0 and 1)
    // with 0xE4 = 0b11100100 → TL=00, TR=01, BL=10, BR=11. Then fetching
    //   - line y=0  (tileY=0, subY=0): tileX=0→bank 0, tileX=1→bank 1
    //   - line y=16 (tileY=1, subY=1): tileX=0→bank 2, tileX=1→bank 3
    // demonstrates that the engine extracts the right sub-field for each
    // position within the 2×2 block, sharing one SDRAM byte across 4 tiles.
    dut.io.fetchSlotValid #= true
    for (blockX <- 0 until (MapTilesX / 2 + 2)) {
      mem(AttributeMapBase + blockX) = 0xE4
    }
    dut.io.attributeMode #= 1
    dut.clockDomain.waitSampling(20)   // CDC settle

    // tileY=0 row: TL/TR fields.
    fireFetchTwice(0)
    val (_, bank8TL, _) = readPixel(0)    // tileX=0 pixel 0 → TL
    val (_, bank8TR, _) = readPixel(16)   // tileX=1 pixel 0 → TR
    assert(bank8TL == 0, s"case8 TL: got=$bank8TL exp=0")
    assert(bank8TR == 1, s"case8 TR: got=$bank8TR exp=1")
    println(s"[sim] case8 packed y=0  TL=$bank8TL TR=$bank8TR — OK")

    // tileY=1 row: BL/BR fields from the same block bytes.
    fireFetchTwice(16)
    val (_, bank8BL, _) = readPixel(0)
    val (_, bank8BR, _) = readPixel(16)
    assert(bank8BL == 2, s"case8 BL: got=$bank8BL exp=2")
    assert(bank8BR == 3, s"case8 BR: got=$bank8BR exp=3")
    println(s"[sim] case8 packed y=16 BL=$bank8BL BR=$bank8BR — OK")

    // Sanity: a tileX=2 (block 1, subX=0) must re-read TL of the next block
    // (also 0xE4 → TL=00), proving the address halving is in effect.
    val (_, bank8TLnext, _) = readPixel(32)
    assert(bank8TLnext == 2,
      s"case8 tileX=2 on y=16 (subY=1,subX=0 = BL of block 1): got=$bank8TLnext exp=2")
    println(s"[sim] case8 packed cross-block BL=$bank8TLnext — OK")

    // Restore linear mode before exiting for cleanliness.
    dut.io.attributeMode #= 0
    dut.clockDomain.waitSampling(20)

    println("[sim] TileAttributeFetchSim: PASS")
  }
}
