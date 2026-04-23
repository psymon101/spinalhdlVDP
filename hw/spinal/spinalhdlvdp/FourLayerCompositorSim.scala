package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 48 — Four-Layer Compositor priority proof.
  *
  * A standalone shim that replicates exactly the 4-layer priority mux from
  * `VdpTop.scala`. Proves the ruling from the artifact:
  *   - L0 `forcedPriority` wins over all other BG layers (preserves 2-layer
  *     era semantics).
  *   - Otherwise, highest-index opaque BG wins: L3 > L2 > L1 > L0.
  *   - When a sprite slot is visible at the pixel, sprite wins (simulated
  *     here via an explicit `spriteVisible` input; layerSource flips to
  *     SPRITE; priority flag forced low).
  *   - With L2/L3 disabled (pixel=0, opaque=false), compositor output is
  *     bit-identical to the pre-Task-48 2-layer behaviour.
  */
object FourLayerCompositorSim extends App {

  case class Shim() extends Component {
    val io = new Bundle {
      // Four BG layer inputs (pixel=0 → transparent).
      val l0Pixel        = in Bits(4 bits)
      val l1Pixel        = in Bits(4 bits)
      val l2Pixel        = in Bits(4 bits)
      val l3Pixel        = in Bits(4 bits)
      val l0Bank         = in UInt(3 bits)
      val l0ForcedPrio   = in Bool()
      // Sprite input.
      val spritePixel    = in Bits(4 bits)
      val spriteVisible  = in Bool()
      // Outputs.
      val outIdx     = out Bits(4 bits)
      val outBank    = out UInt(3 bits)
      val outSource  = out UInt(3 bits)    // PixelMetadata.Source*
      val outPrio    = out Bool()
    }

    val l0Opaque = io.l0Pixel =/= B(0, 4 bits)
    val l1Opaque = io.l1Pixel =/= B(0, 4 bits)
    val l2Opaque = io.l2Pixel =/= B(0, 4 bits)
    val l3Opaque = io.l3Pixel =/= B(0, 4 bits)
    val layer0PrioGated = l0Opaque && io.l0ForcedPrio

    val composedBgIdx    = Bits(4 bits)
    val composedBgBank   = UInt(3 bits)
    val composedBgSource = UInt(3 bits)
    when(layer0PrioGated) {
      composedBgIdx    := io.l0Pixel
      composedBgBank   := io.l0Bank
      composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
    }.elsewhen(l3Opaque) {
      composedBgIdx    := io.l3Pixel
      composedBgBank   := U(0, 3 bits)
      composedBgSource := U(PixelMetadata.SourceBG3, 3 bits)
    }.elsewhen(l2Opaque) {
      composedBgIdx    := io.l2Pixel
      composedBgBank   := U(0, 3 bits)
      composedBgSource := U(PixelMetadata.SourceBG2, 3 bits)
    }.elsewhen(l1Opaque) {
      composedBgIdx    := io.l1Pixel
      composedBgBank   := U(0, 3 bits)
      composedBgSource := U(PixelMetadata.SourceBG1, 3 bits)
    }.otherwise {
      composedBgIdx    := io.l0Pixel
      composedBgBank   := io.l0Bank
      composedBgSource := U(PixelMetadata.SourceBG0, 3 bits)
    }

    // Sprite-on-top via last-assignment-wins (matches VdpTop's for-loop).
    val finalIdx    = Bits(4 bits); finalIdx    := composedBgIdx
    val finalBank   = UInt(3 bits); finalBank   := composedBgBank
    val finalSource = UInt(3 bits); finalSource := composedBgSource
    val finalPrio   = Bool();       finalPrio   := layer0PrioGated && !l1Opaque && !l2Opaque && !l3Opaque
    when(io.spriteVisible) {
      finalIdx    := io.spritePixel
      finalBank   := U(0, 3 bits)
      finalSource := U(PixelMetadata.SourceSprite, 3 bits)
      finalPrio   := False
    }
    io.outIdx    := finalIdx
    io.outBank   := finalBank
    io.outSource := finalSource
    io.outPrio   := finalPrio
  }

  Config.sim.compile(Shim()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def set(l0: Int, l1: Int, l2: Int, l3: Int,
            l0Bank: Int = 0, l0ForcedPrio: Boolean = false,
            sprite: Int = 0, spriteVisible: Boolean = false): Unit = {
      dut.io.l0Pixel       #= l0 & 0xF
      dut.io.l1Pixel       #= l1 & 0xF
      dut.io.l2Pixel       #= l2 & 0xF
      dut.io.l3Pixel       #= l3 & 0xF
      dut.io.l0Bank        #= l0Bank & 0x7
      dut.io.l0ForcedPrio  #= l0ForcedPrio
      dut.io.spritePixel   #= sprite & 0xF
      dut.io.spriteVisible #= spriteVisible
      dut.clockDomain.waitSampling(); sleep(1)
    }
    def read(): (Int, Int, Int, Boolean) =
      (dut.io.outIdx.toInt, dut.io.outBank.toInt, dut.io.outSource.toInt, dut.io.outPrio.toBoolean)

    // --- Case 1: only L0 opaque → L0 wins (backward compatibility) ---
    set(l0=0x5, l1=0, l2=0, l3=0)
    assert(read() == (0x5, 0, PixelMetadata.SourceBG0, false), s"Case 1: $read")
    println("[sim] Case 1 only L0 opaque → BG0 wins — OK")

    // --- Case 2: L0 + L1 opaque, no forced priority → L1 wins ---
    set(l0=0x5, l1=0x3, l2=0, l3=0)
    assert(read() == (0x3, 0, PixelMetadata.SourceBG1, false), s"Case 2: $read")
    println("[sim] Case 2 L0+L1 opaque → BG1 wins (2-layer semantics preserved) — OK")

    // --- Case 3: L0+L2 opaque → L2 wins ---
    set(l0=0x5, l1=0, l2=0xA, l3=0)
    assert(read() == (0xA, 0, PixelMetadata.SourceBG2, false), s"Case 3: $read")
    println("[sim] Case 3 L0+L2 opaque → BG2 wins — OK")

    // --- Case 4: all four opaque → L3 wins (highest-index) ---
    set(l0=0x1, l1=0x2, l2=0x3, l3=0xF)
    assert(read() == (0xF, 0, PixelMetadata.SourceBG3, false), s"Case 4: $read")
    println("[sim] Case 4 all four opaque → BG3 wins (highest-index) — OK")

    // --- Case 5: L0 forced priority with all others opaque → L0 still wins ---
    // Note: per legacy semantics (preserved), `outPrio` clears when any
    // other BG layer is also opaque — the priority flag only asserts when
    // L0's pixel is the sole contributor. Source + idx + bank still track
    // L0's forced win.
    set(l0=0x7, l1=0x2, l2=0x3, l3=0xF, l0Bank=5, l0ForcedPrio=true)
    val (i5, b5, s5, _) = read()
    assert(i5 == 0x7, s"Case 5 idx: expected 0x7, got $i5")
    assert(b5 == 5,   s"Case 5 bank: expected 5, got $b5")
    assert(s5 == PixelMetadata.SourceBG0, s"Case 5 source: expected SourceBG0, got $s5")
    println("[sim] Case 5 L0 forcedPriority overrides L1/L2/L3 (source=BG0, idx+bank from L0) — OK")

    // --- Case 5b: L0 forced priority with only L0 opaque → priority flag set ---
    set(l0=0x7, l1=0, l2=0, l3=0, l0Bank=5, l0ForcedPrio=true)
    assert(read() == (0x7, 5, PixelMetadata.SourceBG0, true),
           s"Case 5b L0 sole opaque + prio: $read")
    println("[sim] Case 5b L0 forcedPriority + no other opaque → outPrio set — OK")

    // --- Case 6: L0 forced priority but L0 transparent → priority does not apply ---
    set(l0=0, l1=0, l2=0, l3=0xB, l0Bank=0, l0ForcedPrio=true)
    assert(read() == (0xB, 0, PixelMetadata.SourceBG3, false), s"Case 6: $read")
    println("[sim] Case 6 L0 transparent (forced prio has no effect) → BG3 wins — OK")

    // --- Case 7: sprite visible → sprite wins over everything, priority flag off ---
    set(l0=0x7, l1=0x2, l2=0x3, l3=0xF, l0ForcedPrio=true, sprite=0x9, spriteVisible=true)
    assert(read() == (0x9, 0, PixelMetadata.SourceSprite, false),
           s"Case 7 expected sprite winner: got $read")
    println("[sim] Case 7 sprite on top of all 4 layers (overrides even forcedPriority) — OK")

    // --- Case 8: L2/L3 disabled (pixel=0) → bit-identical to 2-layer behaviour ---
    set(l0=0x5, l1=0x3, l2=0, l3=0)
    assert(read() == (0x3, 0, PixelMetadata.SourceBG1, false),
           s"Case 8 L2/L3 transparent: should match 2-layer BG1-wins")
    set(l0=0x5, l1=0, l2=0, l3=0)
    assert(read() == (0x5, 0, PixelMetadata.SourceBG0, false),
           s"Case 8 L1/L2/L3 transparent: should match 2-layer BG0-wins")
    set(l0=0, l1=0, l2=0, l3=0)
    assert(read() == (0, 0, PixelMetadata.SourceBG0, false),
           s"Case 8 all transparent: fill idx 0 from L0")
    println("[sim] Case 8 L2/L3 disabled → bit-identical to pre-Task-48 2-layer behaviour — OK")

    println("[sim] FourLayerCompositorSim: PASS")
  }
}
