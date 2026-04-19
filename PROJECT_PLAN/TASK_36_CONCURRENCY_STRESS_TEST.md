# Task 36 — Register Write Concurrency Stress Test

**Status:** Artifact phase
**depends_on:** [26, 33]
**scope_boundary:** Validation-only task. No new HDL, no new firmware features. No new rendering primitives. Existing bus arbiter and safe-boundary commit logic are exercised but not modified.
**delivers:**

- Sim scenario with QSPI + Copper + Animator all writing registers on the same frame
- Proof that safe-boundary commit absorbs concurrent writes without glitches
- Explicit bandwidth analysis under maximum write traffic
- Multi-master bus stress under concurrent SDRAM fetch + sprite evaluation load

**validation:**

- Sim: 10k-frame randomized stress with all three masters → zero commit glitches
- Sim: status readback is correct while masters are actively writing
- Hardware: rapid alternating writes from QSPI and Copper → visual stability

---

## 1. Goal

Prove that the Mode0 register bus — with its `RegBusArbiter` priority mux, copper FIFO drain, and safe-boundary shadow+commit mechanism — remains glitch-free under maximum concurrent write pressure from all masters.

This is a **confidence task**: the structural pieces (Task 32b arbiter, Task 33 HDMA/copper integration) are already proven individually. Task 36 proves they work correctly *together* under adversarial conditions.

## 2. Scope

### 2.1 In scope

1. **Deterministic concurrency sim** — QSPI + Animator + Copper script all write different registers on the same frame; assert correct final values after safe-boundary commit
2. **Randomized stress sim** — 10k frames of pseudo-random concurrent writes; assert zero commit glitches (no mid-line visible state changes, no register corruption)
3. **Bandwidth ceiling measurement** — count cycles where `extHit` or `copperPopped` are asserted; derive max sustained writes/line and max writes/frame
4. **Status-readback integrity** — assert that `STATUS_STICKY` reads remain correct while masters are actively writing other registers
5. **Hardware stability proof** — rapid QSPI-driven register toggles concurrent with copper/HDMA line effects; 30s capture confirms no visible tearing or jitter

### 2.2 Out of scope (deferred)

- New bus master addition (Task 30 pre-announced arbiter is deferred)
- SDRAM bandwidth contention analysis (covered by Task 23 stress scene)
- Formal property checking (SpinalHDL formal / SVA — future toolchain upgrade)
- Firmware-side stress generation (libvdp already supports rapid write sequences)

## 3. Architecture Under Test

### 3.1 Master topology (current baseline)

```
TopTang20kHdmi:
  ┌─────────────────────────────────────────────────────────┐
  │  RegBusArbiter(3) — priority mux                        │
  │    master 0 = bootstrap   (highest priority)            │
  │    master 1 = qspi                                      │
  │    master 2 = animator   (lowest arbiter priority)      │
  │                    │                                    │
  │                    ▼                                    │
  │              Mode0RegBus ──► VdpTop.io.regBus           │
  └─────────────────────────────────────────────────────────┘
                              │
VdpTop:
  ┌─────────────────────────────────────────────────────────┐
  │  extHit = io.regBus.enable   (arbiter output)           │
  │                                                          │
  │  copperFifo (depth=32)                                   │
  │    push ← copper.io.regWr (script + HDMA output mux)     │
  │    pop  ← safeNow && !extHit   (drain at hCounter==0)   │
  │                                                          │
  │  copperPopped = copperFifo.io.pop.fire                   │
  │                                                          │
  │  effWrite = extHit || copperPopped                       │
  │  effAddr  = Mux(extHit, io.regBus.addr,  fifo.addr)     │
  │  effData  = Mux(extHit, io.regBus.data,  fifo.data)     │
  │                                                          │
  │  ▼ All register consumers decode from effAddr/effData   │
  │     • linestate prepare (0x0000..0x01DF)                │
  │     • LAYER_ENABLE shadow (0x0300)                      │
  │     • VDP_TILE_MODE shadow (0x0311)                     │
  │     • VDP_ATTR_MODE shadow (0x0312)                     │
  │     • STATUS_ENABLE shadow (0x0321)                     │
  │     • Color-math + window shadows (0x0330..0x0334)      │
  │     • Affine matrix shadows (0x0340..0x0346)            │
  │     • HDMA control (0x0380..0x03FF)                     │
  │                                                          │
  │  Safe-boundary commit (hCounter===0):                    │
  │    foreach pending shadow register:                      │
  │      if (pendHit) { reg := pend; pendHit := False }     │
  └─────────────────────────────────────────────────────────┘
```

### 3.2 Concurrency hazard model

| Hazard | Mechanism | Mitigation | Test target |
|---|---|---|---|
| **Arbiter priority inversion** | Lower-priority master overrides higher | Priority foldLeft guarantees index order | Assert master(0) wins when all three assert |
| **Copper FIFO overflow** | HDMA/script burst > 32 entries | FIFO `full` backpressure ignored by copper (drop) | Assert FIFO never overflows under test load |
| **Mid-line commit** | Shadow applied before hCounter==0 | All consumer regs committed only at boundary | Pixel-level sim asserts no mid-line RGB change |
| **Read-while-write** | STATUS_STICKY read during active write | Status regs are combinational readback | Assert read data stable during concurrent write |
| **Same-address collision** | Two masters write same reg same cycle | Arbiter priority + last-write-wins shadow | Assert final committed value = priority winner |

### 3.3 Bandwidth model

Per line (800 cycles @ 25 MHz pixel clock):
- **Safe boundary window**: `hCounter === 0` = 1 cycle where `copperDrain` can fire
- **Arbiter throughput**: one write/cycle when `enable` asserted (combinational mux)
- **Copper FIFO drain**: 1 entry/line maximum (constrained by `safeNow && !extHit`)
- **QSPI throughput**: 1 write per ~20 SPI clocks ≈ 1 per 40 pixel cycles at 2 MHz SCK
- **Animator throughput**: scenario-dependent; Sc12 fires 6 writes/frame at vsync

**Theoretical max sustained**: ~20 arbiter writes/line (if QSPI saturated) + 1 copper drain/line = 21 effective register touches/line.

## 4. Validation Plan

### 4.1 Simulation — Deterministic concurrency (`RegBusConcurrencySim`)

**New sim** extending `VdpTopSim` harness.

**Setup:**
1. Bootstrap completes; `bootDoneR = True`.
2. Animator master (Sc12-style) fires 6 writes to affine registers at vsync.
3. QSPI master fires a burst of 10 writes to color-math + window registers mid-frame.
4. Copper script fires a `WRITE_SEQ` burst to LAYER_ENABLE + scroll registers mid-frame.

**Assertions:**
- All 6 animator writes commit at next `hCounter===0` after vsync.
- All 10 QSPI writes commit at next `hCounter===0` after their respective `enable` pulses.
- All copper writes commit at next `hCounter===0` after FIFO drain.
- No pixel RGB value changes except at line boundaries.
- Final committed register values reflect arbiter priority (bootstrap > qspi > animator > copper).

### 4.2 Simulation — Randomized stress (`RegBusStressSim`)

**New sim** using `scala.util.Random` seed.

**Setup:**
1. Run for 10,000 frames.
2. Each frame, random number (0..5) of QSPI writes to random addresses in safe range.
3. Each frame, copper script random-length burst (0..8 writes) to random addresses.
4. Animator fires deterministic 6-write burst every 180 frames (Sc12 cadence).

**Assertions:**
- `commitGlitchCounter == 0` — incremented whenever a shadow register commits outside `hCounter===0`.
- `midLineRgbChangeCounter == 0` — incremented whenever RGB changes while `de` is high and `hCounter != 0`.
- `fifoOverflowCounter == 0` — incremented whenever `copperFifo.io.push.ready` is false during a push.
- Status readback (0x0320 sticky, 0x0321 enable) matches expected values at random sample points.

### 4.3 Simulation — Status readback integrity

**Extends `StatusRegSim`:**
1. Drive concurrent QSPI writes to color-math registers while reading STATUS_STICKY.
2. Assert read data is stable and correct (not corrupted by concurrent write traffic).

### 4.4 Hardware proof

**Scenario 36** (`TopTang20kHdmiScenario36Verilog`):
1. Bootstrap initializes a stable scene (e.g., Sc15 mixed-mode bands).
2. Copper script configures HDMA ch0 → `COLOR_MATH_CTRL` with 4 entries (same as Sc33).
3. QSPI host (via `libvdp` or direct Pico script) rapid-fires alternating writes to `COLOR_MATH_CTRL` and `LAYER_ENABLE` at ~10 Hz.
4. HDMA provides the stable banded baseline; QSPI toggles provide the stress perturbation.

**Evidence:**
- 30-second HDMI capture (1500 frames @ 1080p50).
- OpenCV analysis: band positions stable ±0 lines; no tearing, no flicker.
- Mean brightness delta from Sc33 baseline < 2% (perturbation is bounded).

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Random stress finds race condition in arbiter | High — may require HDL fix | Deterministic sim first; random only after deterministic PASS |
| Copper FIFO drops under burst | Medium — phantom HDMA failure | Assert FIFO level never exceeds threshold under test load |
| Status readback corruption under write pressure | Medium — host sees garbage | Explicit read-during-write test in sim |
| Hardware QSPI rate too slow to stress meaningfully | Low — sim proves correctness, HW proves stability | Use `libvdp` rapid-write mode; if still too slow, accept sim as primary evidence |
| 10k-frame sim too slow for CI | Low — run as extended test, not per-commit | Gate on deterministic sim; stress sim runs on-demand |

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** sim implementation — `RegBusConcurrencySim` + `RegBusStressSim` PASS
- **C:** hardware proof — Sc36 stable under QSPI + HDMA concurrent load

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | 2 new sim files (~300 lines each); 1 new scenario in `TopTang20kHdmi.scala` (~40 lines); no HDL changes |
| **Hardware target** | Tang Nano 20K (Gowin GW2AR-LV18) |
| **Dependencies** | Task 26 (QSPI frontend), Task 32b (RegBusArbiter), Task 33 (Copper/HDMA) |
| **Primary owner** | BrightForge (coding), CyanPeak (audit) |

## 8. Open Questions (for implementation to resolve)

1. **Stress sim duration:** 10k frames = ~200 seconds at 10 ns/cycle in Verilator. Is this acceptable for the extended-test tier, or should we cap at 1k frames with stronger assertions?
2. **Hardware QSPI rate:** What is the practical max sustained register-write rate from Pico? If < 5 writes/frame, the HW proof is more "stability under occasional perturbation" than true stress.
3. **Animator master in stress sim:** Sc12's 6-write burst every 180 frames is the only animator source today. Should the stress sim synthesize a faster animator-like master, or is the existing cadence sufficient?
