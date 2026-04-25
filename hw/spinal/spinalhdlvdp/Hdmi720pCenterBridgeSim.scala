package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Slice-C unit sim for `Hdmi720pCenterBridge`.
  *
  * Six bounded cases that pin down the windowed mux behaviour:
  *   1. Pixel deep inside the centered window passes content RGB.
  *   2. Pixel in the left  border (x < 320)        outputs black.
  *   3. Pixel in the right border (x ≥ 320 + 640)  outputs black.
  *   4. Pixel in the top    border (y < 120)        outputs black.
  *   5. Pixel in the bottom border (y ≥ 120 + 480)  outputs black.
  *   6. de=0 forces black even at an in-window coordinate.
  *
  * Plus four boundary checks that prove off-by-one correctness:
  *   - x = 319         → black (last left  border column)
  *   - x = 320         → content (first inner column,  contentX = 0)
  *   - x = 320 + 640 − 1 → content (last inner column, contentX = 639)
  *   - x = 320 + 640   → black (first right border column)
  *   And the symmetric pair on the y axis.
  */
object Hdmi720pCenterBridgeSim extends App {
  Config.sim.compile(Hdmi720pCenterBridge()).doSim { dut =>
    // No clock domain matters — the bridge is pure combinational. Drive
    // a stimulus clock anyway so SpinalSim's settle model behaves.
    dut.clockDomain.forkStimulus(period = 10)

    val contentR = 0xAA
    val contentG = 0xBB
    val contentB = 0xCC
    dut.io.contentRed   #= contentR
    dut.io.contentGreen #= contentG
    dut.io.contentBlue  #= contentB

    def drive(x: Int, y: Int, de: Boolean): Unit = {
      dut.io.x  #= x
      dut.io.y  #= y
      dut.io.de #= de
      sleep(1)
    }
    def expectBlack(label: String): Unit = {
      assert(!dut.io.inWindow.toBoolean, s"$label: inWindow should be false")
      assert(dut.io.red.toInt   == 0,    s"$label: red expected 0, got 0x${dut.io.red.toInt.toHexString}")
      assert(dut.io.green.toInt == 0,    s"$label: green expected 0")
      assert(dut.io.blue.toInt  == 0,    s"$label: blue expected 0")
    }
    def expectContent(label: String, cx: Int, cy: Int): Unit = {
      assert(dut.io.inWindow.toBoolean,        s"$label: inWindow should be true")
      assert(dut.io.red.toInt   == contentR,   s"$label: red expected 0x$contentR%X")
      assert(dut.io.green.toInt == contentG,   s"$label: green expected 0x$contentG%X")
      assert(dut.io.blue.toInt  == contentB,   s"$label: blue expected 0x$contentB%X")
      assert(dut.io.contentX.toInt == cx,      s"$label: contentX expected $cx, got ${dut.io.contentX.toInt}")
      assert(dut.io.contentY.toInt == cy,      s"$label: contentY expected $cy, got ${dut.io.contentY.toInt}")
    }

    // Case 1: deep inside content area.
    drive(640, 360, true)
    expectContent("Case 1 deep inside", cx = 640 - 320, cy = 360 - 120)
    println("[sim] Case 1 deep-inside content passes RGB — OK")

    // Case 2: left border.
    drive(0, 360, true)
    expectBlack("Case 2 left border x=0")
    drive(100, 360, true)
    expectBlack("Case 2 left border x=100")
    println("[sim] Case 2 left  border black — OK")

    // Case 3: right border.
    drive(960, 360, true)
    expectBlack("Case 3 right border x=960")
    drive(1279, 360, true)
    expectBlack("Case 3 right border x=1279")
    println("[sim] Case 3 right border black — OK")

    // Case 4: top border.
    drive(640, 0, true)
    expectBlack("Case 4 top border y=0")
    drive(640, 119, true)
    expectBlack("Case 4 top border y=119")
    println("[sim] Case 4 top    border black — OK")

    // Case 5: bottom border.
    drive(640, 600, true)
    expectBlack("Case 5 bottom border y=600")
    drive(640, 719, true)
    expectBlack("Case 5 bottom border y=719")
    println("[sim] Case 5 bottom border black — OK")

    // Case 6: de=0 forces black even inside window.
    drive(640, 360, false)
    expectBlack("Case 6 de=0 inside window")
    println("[sim] Case 6 de=0 forces black — OK")

    // Boundary x edges.
    drive(319, 360, true);  expectBlack("Boundary x=319 (last left border)")
    drive(320, 360, true);  expectContent("Boundary x=320 (first content)", 0, 240)
    drive(959, 360, true);  expectContent("Boundary x=959 (last content)", 639, 240)
    drive(960, 360, true);  expectBlack("Boundary x=960 (first right border)")
    println("[sim] Boundary x edges (319/320/959/960) — OK")

    // Boundary y edges.
    drive(640, 119, true);  expectBlack("Boundary y=119 (last top border)")
    drive(640, 120, true);  expectContent("Boundary y=120 (first content)", 320, 0)
    drive(640, 599, true);  expectContent("Boundary y=599 (last content)", 320, 479)
    drive(640, 600, true);  expectBlack("Boundary y=600 (first bottom border)")
    println("[sim] Boundary y edges (119/120/599/600) — OK")

    println("[sim] Hdmi720pCenterBridgeSim: PASS")
  }
}
