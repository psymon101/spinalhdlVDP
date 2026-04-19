# MODE0_REGISTER_BUS_SPEC.md

**Status:** Stable contract — locked by Task 32a (commit landing this file)
**Governing task:** Task 32a — Mode0 Register Bus: Spec & Naming Lock
**Scope:** Write-path control surface for Mode0. The READ_STATUS response surface is defined by `QspiDecoder` sel mapping and is referenced here for completeness but is not part of the register bus itself.

This document is the authoritative naming and semantic contract for the Mode0 write-path register bus. Tasks 33 (Copper-lite), 34 (QSPI asset upload), 35 (Host IRQ / Status Registers), and 37 (Affine Sprite Path) MUST target this contract without ad-hoc drift. Task 32b is the separate lane that will refactor the HDL so all masters reference a common bundle; 32a defines WHAT they target, 32b defines HOW.

---

## 1. Signal Contract

The register bus is a single-cycle pulse-based write contract. Every master drives one pulse per intended write; `VdpTop` and its sub-consumers sample on the pulse and commit on the safe boundary.

| Signal | Width | Direction (master → `VdpTop`) | Clock domain |
|---|---|---|---|
| `regWriteAddr` | `UInt(15 bits)` | in to `VdpTop.io.regWriteAddr` | pixel clock |
| `regWriteData` | `Bits(16 bits)` | in to `VdpTop.io.regWriteData` | pixel clock |
| `regWriteEnable` | `Bool()` | in to `VdpTop.io.regWriteEnable` | pixel clock |

- **Address width is 15 bits** — covers `0x0000..0x7FFF`. Larger spaces (bulk SDRAM asset upload per Task 34) use a different transport, not this bus.
- **Data width is 16 bits** — a single register slot. Wider registers use multiple consecutive addresses (e.g. `last_addr` in the QSPI READ_STATUS response is 16 bits of the 32-bit response word, reserved for Task 34 to extend if needed).
- **Enable is a one-cycle pulse**, not a level. A master asserts it for exactly one pixel-clock cycle when `regWriteAddr`/`regWriteData` carry a valid write.

The 3-tuple name pattern `regWrite{Addr,Data,Enable}` is the frozen naming. Future masters MUST use this exact naming at the top level of `VdpTop` integration. Internal module-level signals may use different names (e.g. `qspiDec.io.regWriteEnable`, `bootWrite`) as long as they fold into this 3-tuple at the mux boundary.

---

## 2. Masters

### 2.1 Current masters

| Master | Source | Active window | Notes |
|---|---|---|---|
| **Bootstrap** | `TopTang20kHdmi` `bootWrite` block | Power-on, ends when `bootDoneR=1` | Loads scenario-specific scene config; gates all other masters via `regWriteFromBoot` |
| **QSPI Decoder** | `QspiDecoder.io.regWrite*` | `bootDoneR=1` and host issues REG_WRITE | Bit-exact write of host-supplied data |
| **Animator** | `TopTang20kHdmi` `animWrite*` | Per-scenario, pixel-clock-periodic | In-FPGA register updates for Sc1..Sc17 animated scenes |

### 2.2 Master priority (mux at `TopTang20kHdmi.scala:534-539`)

Priority is **bootstrap > qspi > animator**, implemented as two nested `Mux`es feeding `video.io.regWriteAddr` and `video.io.regWriteData`. `regWriteEnable` is the OR of the three masters' enables.

```
regWriteFromBoot   : highest — bootstrap wins during boot window
qspiActive         : next — bootDoneR && qspiDec.regWriteEnable
animWriteActive    : lowest — in-FPGA animator
```

### 2.3 Rules for new masters

Any new master added by Task 33 (Copper-lite), Task 34 (asset upload side-writes), or Task 37 (affine sprite registers) MUST:

1. Drive its own `regWriteAddr`/`regWriteData`/`regWriteEnable` signals with the same shape.
2. Be added to the `TopTang20kHdmi` mux tree with an explicit priority ranking.
3. Document its priority-vs-others in its artifact doc.
4. Not override bootstrap under any circumstances — bootstrap is always highest.

**Open question for Task 33:** Copper-lite is beam-synchronous and fires inside the active frame. Its priority relative to QSPI and animator is unspecified; the Task 33 artifact MUST decide. This spec does NOT predetermine that choice.

---

## 3. Address Map (current + reserved)

All addresses below are 15-bit; high bit is always 0 within current use.

### 3.1 Allocated

| Range | Purpose | Owning task | Reference |
|---|---|---|---|
| `0x0000..0x01DF` | Linestate prepare (480 lines × per-line `{l0en, l1en, l0scrollX[9:0]}`) | Task 14 | `VdpTop.scala:43` |
| `0x01E0..0x02FF` | **Reserved** — linestate expansion buffer | — | — |
| `0x0300` | `LAYER_ENABLE` — `data[0]=L0, data[1]=L1, data[2]=sprite` | Task 13 / R5 | `VdpTop.scala:44,221` |
| `0x0301..0x030F` | **Reserved** — layer-group overrides | — | — |
| `0x0310` | `VDP_CTRL` — `data[0]=copperEnable` (R5.3) | Task R5.3 | `VdpTop.scala:172,245` |
| `0x0311` | `VDP_TILE_MODE` — 2-bit packed/planar/shuffled | Task R4.1b/c/d | `VdpTop.scala:225,232` |
| `0x0312` | `VDP_ATTR_MODE` — 1-bit linear/packed-2×2 | Task R4.1c | `VdpTop.scala:61,240` |
| `0x0313..0x031F` | **Reserved** — global-control expansion | — | — |
| `0x0320..0x032F` | **Reserved for Task 35** — status registers, IRQ enables, sticky bits | Task 35 | — |
| `0x0330..0x033F` | **Reserved for Task 33** — Copper-lite control (run/stop, PC, triggers) | Task 33 | — |
| `0x0340..0x03FF` | **Reserved** — future host-surface registers | — | — |
| `0x0400..0x05FF` | Copper program RAM (512 × 16-bit instructions) | Task R5 | `VdpTop.scala:45,182` |
| `0x0600..0x07FF` | **Reserved** — Copper secondary tables (HDMA-style, Task 33) | Task 33 | — |
| `0x0800..0x0FFF` | **Reserved for Task 37** — affine sprite descriptors | Task 37 | — |
| `0x1000..0x7FFF` | **Reserved** — future Mode0 expansion (palette banks, sprite attr, etc.) | — | — |

### 3.2 Allocation rules

- Any new task that adds register addresses MUST reserve a contiguous block in its artifact and reference that block here via a commit touching this spec.
- Single-register additions outside a task's reserved block are forbidden — pick up a reserved range or open 32a (or a named extension of it) to claim one.
- Task 32b refactor MAY rename the existing HDL signals but MUST NOT change any address above.

---

## 4. Semantics

### 4.1 Write ordering and commit

All register writes are **safe-boundary committed** at `hCounter === 0` (VdpTop.scala:348). A write pulsing at arbitrary pixel position lands in a shadow register; the shadow transfers to the live register at the start of the next scanline. This prevents mid-line artifacts when host / Copper / animator fire during active video.

**Exception:** linestate prepare (`0x0000..0x01DF`) uses a different two-phase commit — prepare into line N+1, commit at end of line N (`hCounter === hTotal - 1`, see `VdpTop.scala:169`). This is intentional and predates Task 32a; Task 32a documents it, does not modify it.

### 4.2 Multi-master on the same cycle

Priority mux (§2.2) resolves address/data. If two masters pulse `regWriteEnable` in the same cycle:
- OR of enables → single pulse visible to `VdpTop`.
- Addr/data mux picks the higher-priority master's values.
- Lower-priority master's write is silently dropped that cycle.

**Task 36 (Register Write Concurrency Stress)** must prove this doesn't corrupt safe-boundary commits under max traffic.

### 4.3 Write acknowledgement

The bus has no ack path. Masters pulse and assume the write lands. The QSPI read-path (`READ_STATUS sel=1..4`) provides indirect verification — host can read `last_addr`/`last_data` to confirm the most recent QSPI write committed.

### 4.4 Atomicity within a single register

16-bit writes are atomic — the entire `regWriteData` word is captured in a single pulse. Host does not need to split writes.

---

## 5. Read-path companion (informational)

The register bus is write-only. Read-back is provided by the QSPI READ_STATUS response surface, not by this bus. Per Task 38b:

| sel | Response contents |
|---|---|
| `0` | Magic `0x51560002` (host transport ID) |
| `1` | `rx_cmd_cnt[7:0]` |
| `2` | `last_addr[15:0]` |
| `3` | `last_data[15:0]` |
| `4` | `last_error[7:0]` |
| `5..255` | Reserved — zero response |

Task 35 status registers MUST be readable both by mapping into this sel table (extending to sel=5+) AND by appearing in the allocated `0x0320..0x032F` write-path block for clear-on-write semantics.

---

## 6. Naming Conventions

Lock: use the prefixes below when adding new register addresses.

| Prefix | Domain |
|---|---|
| `VDP_*` | Global Mode0 control (e.g. `VDP_CTRL`, `VDP_TILE_MODE`, `VDP_ATTR_MODE`) |
| `LAYER_*` | Per-layer config (`LAYER_ENABLE`, future `LAYER_PRIORITY`) |
| `STATUS_*` | Task 35 status + IRQ (`STATUS_ENABLE`, `STATUS_CLEAR`, `STATUS_STICKY`) |
| `COPPER_*` | Task 33 Copper-lite control (`COPPER_RUN`, `COPPER_PC`) |
| `SPRITE_*` | Task 37 sprite-side registers |
| `ASSET_*` | Task 34 asset upload control (handshake, not bulk data itself) |

Names MUST be uppercase, ASCII, underscore-separated, and MUST NOT conflict with any Scala identifier in `VdpTop`. A follow-up (optional) `object RegAddr { final val LAYER_ENABLE = 0x0300; ... }` constants file is a Task 32b candidate.

---

## 7. Forward-compatibility notes

### 7.1 Data width

Locked at **16 bits**. Task 34 (asset upload) wanting wider bursts MUST use an out-of-band transport (e.g. a separate SDRAM burst path via QSPI bulk write), not this bus.

### 7.2 Address width

Locked at **15 bits**. If Mode0 evolves to need more, that's a v2 bus — opened via a new 32-series task, not an extension of 32a.

### 7.3 Bus evolution rule

Any change to §1 (Signal Contract) requires:
1. A new task doc proposing the change
2. Mutual-coverage review by CoralReef + CyanPeak
3. PM sign-off
4. A version bump of this spec with historical table

Version bumps MUST preserve the existing allocated addresses in §3.1 unchanged.

---

## 8. Validation baseline

At the time of this spec lock, the following simulations prove zero behavioral drift:

| Sim | Cases | Coverage |
|---|---|---|
| `QspiRegWriteSim` | 13 | Write-path regression + READ_STATUS sel=0..4 + snapshot |
| `QspiSlaveSim` | 4 | Slave framing + Respond-state drive contract |
| `AffineRegSim` | (existing) | Affine register writes via bus |
| `AffineVdpTopSim` | (existing) | Full-stack bus routing |

All pass as of commit `4cee22e` (Task 38c closeout). Task 32a does not introduce HDL changes; these baselines remain green by construction.

---

## 9. Open questions deferred to later tasks

- **§2.3:** Copper-lite master priority relative to QSPI / animator — Task 33 artifact.
- **§3.1:** Palette bank addressing (currently hardcoded in `VdpTop.scala:755+`) — future task if palette animation moves to host control.
- **§5:** Status register clear semantics (write-1-to-clear vs read-to-clear vs auto-clear) — Task 35 artifact.
- **§7.1:** If Task 34 bulk asset upload needs a sideband write register (e.g. `ASSET_ADDR` pointer), its placement at `0x0340..0x034F` is suggested but not locked.

---

*End of Mode0 Register Bus Spec v1.0.*
