# Task 32b — Mode0 Register Bus: Master Refactor

**Status:** DONE — Register bus refactor completed and integrated
**depends_on:** [32a]
**scope_boundary:** Refactor existing masters to the named bus. No new primitives, no new rendering features, no protocol changes.
**delivers:**

- Bootstrap write path, QSPI `regWriteEnable` mux, copper RAM writes, animator writes, linestate prepare/commit, affine register set, and all existing control surfaces refactored onto the named bus
- All existing simulations pass after refactor

**validation:**

- Sim: all existing scenario simulations pass with zero behavioral change
- Hardware: at least one existing scenario re-proven on Tang Nano 20K

---

## 1. Goal

Refactor the ad-hoc register-write mux in `TopTang20kHdmi` into a clean, named bus contract as defined in `MODE0_REGISTER_BUS_SPEC.md` (Task 32a). The 3-tuple `{regWriteAddr[15], regWriteData[16], regWriteEnable}` currently exists as disconnected wires at the top level; Task 32b unifies them into a first-class SpinalHDL bundle that all masters target and `VdpTop` consumes.

## 2. Scope

### 2.1 In scope

1. **New bus bundle** `Mode0RegBus` — SpinalHDL `Bundle` with `addr: UInt(15 bits)`, `data: Bits(16 bits)`, `enable: Bool()`
2. **Master interface standardization** — each master exposes `io.regBus: Mode0RegBus` instead of individual `regWrite*` signals
3. **Priority mux component** `RegBusArbiter` — encapsulates bootstrap > QSPI > animator priority in a reusable module
4. **Top-level wiring cleanup** — `TopTang20kHdmi` instantiates the arbiter and connects masters
5. **VdpTop consumption** — `VdpTop.io.regBus` replaces `VdpTop.io.regWriteAddr/Data/Enable`
6. **All downstream consumers updated** — copper program RAM, linestate store, affine registers, layer enable, control registers, status registers, tile mode, attr mode

### 2.2 Out of scope (deferred)

- Adding new masters (Task 33 Copper-lite will be the first new master post-refactor)
- Changing priority order (remains bootstrap > QSPI > animator)
- Bus-width changes (remains 15-bit addr, 16-bit data)
- Safe-boundary commit logic (remains in VdpTop at `hCounter===0`)
- New register addresses or semantics

## 3. Architecture

### 3.1 Current state (pre-refactor)

```scala
// TopTang20kHdmi.scala — ad-hoc priority mux
video.io.regWriteAddr   := Mux(regWriteFromBoot, bootAddr,
                           Mux(qspiActive, qspiDec.io.regWriteAddr, animWriteAddr))
video.io.regWriteData   := Mux(regWriteFromBoot, bootDataMux,
                           Mux(qspiActive, qspiDec.io.regWriteData, animWriteData))
video.io.regWriteEnable := regWriteFromBoot || qspiActive || animWriteActive
```

Problems:
- Masters don't declare a common interface — each invents its own `regWrite*` signals
- Priority logic is inline and can't be reused
- Adding a fourth master (Task 33 Copper-lite) requires editing the mux tree
- No type safety — addr/data/enable can be mismatched silently

### 3.2 Target state (post-refactor)

```scala
// Mode0RegBus.scala — shared bundle
case class Mode0RegBus() extends Bundle {
  val addr   = UInt(15 bits)
  val data   = Bits(16 bits)
  val enable = Bool()
}

// RegBusArbiter.scala — priority mux component
case class RegBusArbiter(masterCount: Int) extends Component {
  val io = new Bundle {
    val masters = Vec(slave(Mode0RegBus()), masterCount)
    val out     = master(Mode0RegBus())
  }
  // Priority: lower index = higher priority (bootstrap=0, qspi=1, animator=2)
  // Default: addr=0, data=0, enable=False
}

// TopTang20kHdmi.scala — clean wiring
val arbiter = RegBusArbiter(3)
arbiter.io.masters(0).addr   := bootAddr
arbiter.io.masters(0).data   := bootDataMux
arbiter.io.masters(0).enable := regWriteFromBoot
arbiter.io.masters(1).addr   := qspiDec.io.regBus.addr
arbiter.io.masters(1).data   := qspiDec.io.regBus.data
arbiter.io.masters(1).enable := qspiActive
arbiter.io.masters(2).addr   := animWriteAddr
arbiter.io.masters(2).data   := animWriteData
arbiter.io.masters(2).enable := animWriteActive
video.io.regBus <> arbiter.io.out
```

### 3.3 Master interface changes

| Master | Current signals | New interface |
|---|---|---|
| Bootstrap | `bootAddr`, `bootDataMux`, `regWriteFromBoot` | `boot.io.regBus` |
| QSPI Decoder | `regWriteAddr`, `regWriteData`, `regWriteEnable` | `qspiDec.io.regBus` |
| Animator | `animWriteAddr`, `animWriteData`, `animWriteActive` | `anim.io.regBus` |
| VdpTop (consumer) | `regWriteAddr`, `regWriteData`, `regWriteEnable` | `regBus` |

### 3.4 VdpTop internal wiring

All internal consumers currently reference `io.regWriteAddr`, `io.regWriteData`, `io.regWriteEnable`. These become:
- `io.regBus.addr`
- `io.regBus.data`
- `io.regBus.enable`

No semantic change — purely structural renaming via the bundle.

## 4. Validation Plan

### 4.1 Simulation regression

All existing sims must pass with **zero behavioral change**:

| Sim | Cases | Expected |
|---|---|---|
| `QspiRegWriteSim` | 17 | PASS |
| `QspiSlaveSim` | 4 | PASS |
| `StatusRegSim` | 5 | PASS |
| `SdramUploadSim` | 3 | PASS |
| `VdpTopSim` | — | PASS |
| `CopperSim` | — | PASS |
| `AffineVdpTopSim` | — | PASS |
| **Total** | **29+** | **ALL PASS** |

### 4.2 Hardware proof

Rebuild bitstream and re-run one existing scenario on Tang Nano 20K:
- Option A: Sc16 (current default) — visible scrolling pattern should remain identical
- Option B: Test pattern toggle via QSPI — layer on/off behavior should remain identical
- Capture HDMI output and verify no visual regression vs pre-refactor baseline

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Bundle syntax breaks SpinalHDL inference | Build failure | Test with `sbt run` after each file change |
| Vec/master/slave direction mismatch | Wrong Verilog direction | Verify generated Verilog has correct in/out |
| Regression in sim due to renamed signals | False failures | Run full sim suite before claiming Checkpoint B |
| Priority order accidentally reversed | Wrong master wins | Explicit index documentation in arbiter; sim coverage |
| Copper-lite (Task 33) later needs 4th master | Arbiter too small | Parameterize `masterCount`; set to 3 now, 4 later |

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** HDL refactor — `Mode0RegBus` bundle, `RegBusArbiter`, master interface updates, VdpTop consumption update, all sims pass
- **C:** hardware proof — rebuild bitstream, verify no visual regression

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `Mode0RegBus.scala` NEW ~15 lines; `RegBusArbiter.scala` NEW ~30 lines; `TopTang20kHdmi.scala` −20 / +25 lines; `VdpTop.scala` ~40 lines renamed; `QspiDecoder.scala` ~5 lines; master components ~10 lines each |
| **Hardware target** | Tang Nano 20K (Gowin GW2AR-LV18) |
| **Dependencies** | `MODE0_REGISTER_BUS_SPEC.md` v1.0+ (Task 32a) |

## 8. Open Questions (for implementation to resolve)

1. **Bundle naming:** `Mode0RegBus` or `VdpRegBus`? Prefer `Mode0RegBus` to match spec terminology.
2. **Arbiter parameterization:** Fixed 3-master or generic `Vec`? Generic `Vec` with `masterCount` parameter.
3. **Copper as master:** Current copper writes go through the copper FIFO + `extHit` mux inside VdpTop. Should copper be a formal master on the bus, or keep its internal path? Keep internal for now — Task 33 will formalize copper-lite as a bus master.
4. **Affine registers:** Currently updated via `effWrite` inside VdpTop. Should they be exposed as a bus consumer? Already consumed via `effWrite` which sources from `extHit` (the bus). No change needed.
