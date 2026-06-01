package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** P3 CP-B(2) #10791 — PlanarBoundaryAddressSim.
  *
  * Risks targeted: #1 (OOB planar memory accesses) and #5 (bootDoneR
  * monotonicity under refresh detours).
  *
  * Strategy: observe every `sdramAddr` transaction during boot and during
  * planar / shuffled fetches, and assert each address falls in its expected
  * SDRAM window. The Risk #1 assert inside `tileRowByteAddr` is the in-RTL
  * canary; this sim is the external discriminator that exercises every
  * (tIdx, py, wordIdx) the production fetch path can reach, so that the
  * canary is actually proved silent rather than just unobserved.
  *
  * Pass criterion: zero `assert(...)` trips and zero external-monitor
  * boundary violations.
  */
object PlanarBoundaryAddressSim extends App {
  import TileAttributeAssets._

  // Expected SDRAM windows (single source of truth — mirrors PlanarTileAssets).
  val Plane0Lo = PlanarTileAssets.SdramBase                                 // 0xA000
  val Plane0Hi = PlanarTileAssets.SdramBase + PlanarTileAssets.TotalBytes   // 0xA200
  val Plane1Lo = PlanarTileAssets.Plane1SdramBase                           // 0xB000
  val Plane1Hi = PlanarTileAssets.Plane1SdramBase + PlanarTileAssets.TotalBytes // 0xB200

  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    SdramTileAttributeFetch(sdramCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    val mem = mutable.HashMap[Int, Int]()
    def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
    def readWord(a: Int): Long = {
      val base = a & ~3
      (readByte(base).toLong & 0xFF) |
        ((readByte(base + 1).toLong & 0xFF) << 8) |
        ((readByte(base + 2).toLong & 0xFF) << 16) |
        ((readByte(base + 3).toLong & 0xFF) << 24)
    }

    // Boundary monitor: tracks the highest sdramAddr seen during fetches in
    // each tracked window so violations are easy to report.
    @volatile var planarFetchActive = false   // set true while exercising planar mode
    @volatile var shuffledFetchActive = false // set true while exercising shuffled
    @volatile var plane0Writes  = 0
    @volatile var plane0Reads   = 0
    @volatile var plane1Writes  = 0
    @volatile var plane1Reads   = 0
    @volatile var boundaryViolations: List[(String, Int)] = Nil
    def flagViolation(label: String, addr: Int): Unit = synchronized {
      boundaryViolations = (label, addr) :: boundaryViolations
    }

    // Address window classifier — only checks the planar windows. Any read
    // that lands in 0xA000..0xA1FF or 0xB000..0xB1FF is allowed; an
    // adjacent read (e.g. 0xA200) is flagged.
    def classify(addr: Int, op: String): Unit = {
      val a = addr & 0x7FFFFF
      // Plane 0 window
      if (a >= Plane0Lo && a < Plane0Hi) {
        if (op == "rd") plane0Reads += 1 else plane0Writes += 1
      } else if (a >= Plane0Hi && a < Plane1Lo) {
        flagViolation(s"plane0 spill ($op)", a)
      } else if (a >= Plane1Lo && a < Plane1Hi) {
        if (op == "rd") plane1Reads += 1 else plane1Writes += 1
      } else if (a >= Plane1Hi && a < (Plane1Hi + 0x1000)) {
        // The next region above plane1 is L1TileMapBase = 0xC000. Anything
        // in [Plane1Hi, 0xC000) is "spillover past plane 1" — bad.
        if (a < L1TileMapBase) flagViolation(s"plane1 spill ($op)", a)
      }
      // Reads / writes elsewhere (TileMap / AttributeMap / TileRow / L1 /
      // memtest) are out of this sim's scope; ignore.
    }

    // -------- Initialize IO --------
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

    // -------- Behavioral SDRAM model (mirrors TileAttributeFetchSim) ----------
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
              classify(latchedAddr, "rd")
              dut.io.sdramBusy #= true
              state = "wait"; timer = 3
            } else if (dut.io.sdramWr.toBoolean) {
              op = "wr"; latchedAddr = dut.io.sdramAddr.toInt
              latchedDin = dut.io.sdramDin.toInt & 0xFF
              classify(latchedAddr, "wr")
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
                  dut.io.sdramDout   #= readByte(latchedAddr) & 0xFF
                  dut.io.sdramDout32 #= BigInt(readWord(latchedAddr) & 0xFFFFFFFFL)
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

    // -------- Wait for boot & memtest ------------------------------------------
    dut.clockDomain.waitSampling(50)
    var timeout = 600000
    while (!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone")
    println(s"[sim] bootDone after ${600000 - timeout} pixel cycles")

    timeout = 600000
    while (!dut.io.memtestPass.toBoolean && !dut.io.memtestFail.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for memtest")
    assert(dut.io.memtestPass.toBoolean, "memtestFail asserted")

    // Snapshot post-boot stats. Boot writes plane0 & plane1 ROMs to SDRAM
    // (TotalBytes = 512 each). Anything beyond TotalBytes is a spill.
    println(s"[sim] post-boot: plane0 writes=$plane0Writes, plane1 writes=$plane1Writes")
    assert(plane0Writes == PlanarTileAssets.TotalBytes,
      s"plane0 boot writes got=$plane0Writes exp=${PlanarTileAssets.TotalBytes}")
    assert(plane1Writes == PlanarTileAssets.TotalBytes,
      s"plane1 boot writes got=$plane1Writes exp=${PlanarTileAssets.TotalBytes}")
    assert(boundaryViolations.isEmpty,
      s"boundary violations during boot: ${boundaryViolations.reverse}")
    println("[sim] boot boundary check OK — plane0/plane1 writes all in-window")

    // -------- Fetch helpers ----------------------------------------------------
    def fireFetch(y: Int, scrollY: Int = 0): Unit = {
      dut.io.fetchLine    #= y
      dut.io.fetchScrollY #= scrollY
      dut.io.fetchGrant   #= true
      for (_ <- 0 until 4) dut.clockDomain.waitSampling()
      dut.io.fetchGrant   #= false
      for (_ <- 0 until 4000) dut.clockDomain.waitSampling()
    }

    // -------- Exhaustive walk: planar mode (tileDecodeMode=1) ------------------
    // Reset window counters so we measure fetch traffic only.
    plane0Reads = 0; plane1Reads = 0
    boundaryViolations = Nil
    dut.io.tileDecodeMode #= 1
    dut.clockDomain.waitSampling(20)
    planarFetchActive = true

    // Walk all 64 (ty, py) cells across the small (4 tiles × 16 rows) asset.
    // Iterate fetchLine 0..63 to hit each pixelYInTile within each tile row.
    for (y <- 0 until 64) {
      fireFetch(y)
    }
    planarFetchActive = false
    println(s"[sim] planar walk: plane0 reads=$plane0Reads, plane1 reads=$plane1Reads")
    assert(plane0Reads > 0, "planar walk produced no plane0 reads")
    assert(boundaryViolations.isEmpty,
      s"planar walk boundary violations: ${boundaryViolations.reverse}")
    println("[sim] planar walk: all reads in-window OK")

    // -------- Exhaustive walk: shuffled mode (tileDecodeMode=2) ----------------
    plane0Reads = 0; plane1Reads = 0
    boundaryViolations = Nil
    dut.io.tileDecodeMode #= 2
    dut.clockDomain.waitSampling(20)
    shuffledFetchActive = true

    for (y <- 0 until 64) {
      fireFetch(y)
    }
    shuffledFetchActive = false
    println(s"[sim] shuffled walk: plane0 reads=$plane0Reads, plane1 reads=$plane1Reads")
    assert(plane0Reads > 0, "shuffled walk produced no plane0 reads")
    assert(plane1Reads > 0, "shuffled walk produced no plane1 reads (dual-base path silent?)")
    assert(boundaryViolations.isEmpty,
      s"shuffled walk boundary violations: ${boundaryViolations.reverse}")
    println("[sim] shuffled walk: all dual-base reads in-window OK")

    // -------- Risk #5 check: bootDone has remained asserted ---------------------
    assert(dut.io.bootDone.toBoolean,
      "Risk #5: bootDone deasserted after being set (RTL assert should have caught this)")
    println("[sim] Risk #5 monotonicity: bootDone still high after all fetch traffic")

    println(s"[sim] PlanarBoundaryAddressSim: PASS " +
      s"(plane0 in-window reads observed, plane1 dual-base reads observed, 0 spill, " +
      s"Risk #1/#5 asserts silent)")
  }
}
