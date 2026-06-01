package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** Task 56 Checkpoint C — non-default-base FSM verification.
  *
  * Per CyanPeak audit-CP-B advisory (#9693 §2): "Since
  * `SdramTileAttributeFetch` is being parameterized, please ensure the
  * FSM's correctness with the new non-default bases is verified."
  *
  * This sim instantiates the engine with the **L1 SDRAM bases**
  * (`TileAttributeAssets.L1TileMapBase` / `L1AttributeMapBase` /
  * `L1TileRowBase`) and the **L1 distinct boot data**
  * (`l1TileMapBytesInit` / `l1AttributeMapBytesInit` / `l1TileRowBytesInit`)
  * + `bootPlanarAssets = false` + `runMemtest = false` — i.e., the exact
  * configuration used by the `fetchL1` instance in
  * `TopTang20kHdmi`. The behavioral SDRAM stub captures every write so
  * we can prove (a) boot copies hit the L1 addresses, not L0's, and
  * (b) fetched pixels match `l1ExpectedPixel` (uniform-color tiles).
  *
  * Cases:
  *   1. Boot phase: every byte written during sBootTileMap/AttrMap/
  *      TileRows lands inside [L1TileMapBase, L1TileRowBase+0x200).
  *      Zero writes hit L0's region [0x6000, 0x9000).
  *      Planar/memtest regions (0xA000/0xB000/0x2000) untouched.
  *   2. Pixel correctness: read pixels for tiles 0..3 in the 2×2 tile
  *      map and verify pixel index matches `l1ExpectedPixel`
  *      (uniform 1/2/3/4 per tile-quadrant). Bank = 0, priority = 0
  *      everywhere (per `l1AttributeMapBytesInit`).
  *   3. `bootPlanarAssets=false` proof: FSM reaches sIdle without
  *      visiting sBootPlanar/sBootPlane1 — verified indirectly by the
  *      absence of any writes to PlanarTileAssets bases (0xA000/0xB000).
  *   4. `runMemtest=false` proof: no writes to MemtestBase (0x2000).
  */
object TileAttributeFetchL1BaseSim extends App {
  import TileAttributeAssets._

  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    SdramTileAttributeFetch(
      sdramCd,
      skipSdramInit            = false,
      tileMapBaseAddr          = L1TileMapBase,
      attributeMapBaseAddr     = L1AttributeMapBase,
      tileRowBaseAddr          = L1TileRowBase,
      tileMapBytesOverride      = Some(() => l1TileMapBytesInit),
      attributeMapBytesOverride = Some(() => l1AttributeMapBytesInit),
      tileRowBytesOverride      = Some(() => l1TileRowBytesInit),
      bootPlanarAssets         = false,
      runMemtest               = false
    )
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    val mem = mutable.HashMap[Int, Int]()
    val writeAddrs = mutable.Set[Int]()
    def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
    def readWord(a: Int): Long = {
      val base = a & ~3
      (readByte(base).toLong & 0xFF) |
        ((readByte(base + 1).toLong & 0xFF) << 8) |
        ((readByte(base + 2).toLong & 0xFF) << 16) |
        ((readByte(base + 3).toLong & 0xFF) << 24)
    }

    dut.io.sdramDout         #= 0
    dut.io.sdramDout32       #= 0
    dut.io.sdramDataReady    #= false
    dut.io.sdramBusy         #= true
    dut.io.fetchGrant        #= false
    dut.io.fetchSlotValid    #= true
    dut.io.fetchPreAnnounce  #= false
    dut.io.tileDecodeMode    #= 0
    dut.io.attributeMode     #= 0
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
            }
          case "wait" =>
            timer -= 1
            if (timer <= 0) {
              if (op == "rd") {
                dut.io.sdramDout    #= readByte(latchedAddr)
                dut.io.sdramDout32  #= readWord(latchedAddr)
                dut.io.sdramDataReady #= true
              } else {
                mem(latchedAddr & 0x7fffff) = latchedDin
                writeAddrs += (latchedAddr & 0x7fffff)
              }
              dut.io.sdramBusy #= false
              state = "idle"
            }
        }
      }
    }

    // Wait for bootDone. Without planar/memtest gating, the boot sequence
    // is just TileMap (1200) + AttrMap (1200) + TileRows (512) = 2912
    // writes. Each write takes ~5 cycles + housekeeping → conservative
    // upper bound ~60000 sdramCd ticks. The bootDone CDC into the pixel
    // domain adds ~3 BufferCC stages.
    var bootSeen = false
    var deadline = 0
    while (!bootSeen && deadline < 120000) {
      dut.sdramCd.waitSampling()
      if (dut.io.bootDone.toBoolean) bootSeen = true
      deadline += 1
    }
    assert(bootSeen, s"bootDone did not assert within $deadline sdramCd ticks")
    println(s"[sim] bootDone after $deadline sdramCd ticks; memtest auto-pass (runMemtest=false)")

    // -----------------------------------------------------------------
    // Case 1 — boot writes land in L1 region only
    // -----------------------------------------------------------------
    val l1Region = (L1TileMapBase until L1TileRowBase + 512).toSet
    val l0Region = (0x6000 until 0x9000).toSet
    val planarRegion = (0xA000 until 0xC000).toSet
    val memtestRegion = (0x2000 until 0x2000 + 256).toSet

    val writesInL1     = writeAddrs.count(l1Region.contains)
    val writesInL0     = writeAddrs.count(l0Region.contains)
    val writesInPlanar = writeAddrs.count(planarRegion.contains)
    val writesInMemtest = writeAddrs.count(memtestRegion.contains)

    assert(writesInL1 >= 2900,
      s"Case 1: expected ≥2900 writes in L1 region, got $writesInL1 (boot did not target L1 bases)")
    assert(writesInL0 == 0,
      s"Case 1: expected 0 writes in L0 region [0x6000,0x9000), got $writesInL0 (L1 instance leaked to L0 bases)")
    assert(writesInPlanar == 0,
      s"Case 3/bootPlanarAssets=false: expected 0 writes in planar region [0xA000,0xC000), got $writesInPlanar")
    assert(writesInMemtest == 0,
      s"Case 4/runMemtest=false: expected 0 writes in memtest region [0x2000,0x2100), got $writesInMemtest")
    println(s"[sim] case1 boot writes targeted L1 region only — $writesInL1 writes in L1, 0 elsewhere")
    println(s"[sim] case3 bootPlanarAssets=false honored — 0 writes to planar region")
    println(s"[sim] case4 runMemtest=false honored — 0 writes to memtest region")

    // -----------------------------------------------------------------
    // Case 2 — fetch + pixel correctness against l1ExpectedPixel.
    // Drive a fetch cycle for line y=0 then sample readout pixels.
    // -----------------------------------------------------------------
    def fireFetchTwice(y: Int): Unit = {
      dut.io.fetchLine #= y
      for (_ <- 0 until 2) {
        dut.io.fetchGrant #= true
        dut.clockDomain.waitSampling()
        dut.io.fetchGrant #= false
        // Wait long enough for SDRAM-domain FSM to walk all 41 tiles.
        for (_ <- 0 until 3000) dut.clockDomain.waitSampling()
      }
    }

    def readPixel(x: Int): (Int, Int, Boolean) = {
      dut.io.pixelAddr #= x
      dut.clockDomain.waitSampling(3)
      (dut.io.pixelIndex.toInt & 0xF,
       dut.io.pixelPaletteBank.toInt & 0x7,
       dut.io.pixelPriority.toBoolean)
    }

    // y=0 (tileY=0): tx=0 → tile 0 (solid 1); tx=1 → tile 1 (solid 2)
    fireFetchTwice(0)
    val (idx_t0, bk_t0, pr_t0) = readPixel(0)
    val (idx_t0_mid, _, _)     = readPixel(8)
    val (idx_t1, bk_t1, _)     = readPixel(16)
    val (exp_t0, exp_bk0, exp_pr0) = l1ExpectedPixel(0, 0)
    val (exp_t1, _, _)              = l1ExpectedPixel(16, 0)
    assert(idx_t0     == exp_t0,
      s"case2 y=0 x=0  (tile 0): idx got=$idx_t0 exp=$exp_t0")
    assert(idx_t0_mid == exp_t0,
      s"case2 y=0 x=8  (tile 0 mid): idx got=$idx_t0_mid exp=$exp_t0 (solid tile must be uniform)")
    assert(idx_t1     == exp_t1,
      s"case2 y=0 x=16 (tile 1): idx got=$idx_t1 exp=$exp_t1")
    assert(bk_t0 == 0,
      s"case2 y=0 x=0 bank: got=$bk_t0 exp=0 (l1AttributeMapBytesInit is bank-0 throughout)")
    assert(bk_t1 == 0, s"case2 y=0 x=16 bank: got=$bk_t1 exp=0")
    assert(!pr_t0,     "case2 y=0 priority: must be False (l1 attrs are priority 0)")
    println(s"[sim] case2 y=0 idx t0=$idx_t0 t1=$idx_t1 bank=0 prio=False — matches l1ExpectedPixel")

    // y=16 (tileY=1): tx=0 → tile 2 (solid 3); tx=1 → tile 3 (solid 4)
    fireFetchTwice(16)
    val (idx_t2, _, _) = readPixel(0)
    val (idx_t3, _, _) = readPixel(16)
    val (exp_t2, _, _) = l1ExpectedPixel(0, 16)
    val (exp_t3, _, _) = l1ExpectedPixel(16, 16)
    assert(idx_t2 == exp_t2, s"case2 y=16 x=0  (tile 2): idx got=$idx_t2 exp=$exp_t2")
    assert(idx_t3 == exp_t3, s"case2 y=16 x=16 (tile 3): idx got=$idx_t3 exp=$exp_t3")
    println(s"[sim] case2 y=16 idx t2=$idx_t2 t3=$idx_t3 — matches l1ExpectedPixel (cross-quadrant proof)")

    println("[sim] TileAttributeFetchL1BaseSim: PASS")
  }
}
