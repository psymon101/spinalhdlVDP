# SpinalHDL VDP — Synthesis & Conversion GOTCHAs

Generic patterns and pitfalls encountered while porting / refactoring this
codebase. Tang Nano / Gowin specifics live in `kb/gowin/GOTCHAS.md`; this file
covers SpinalHDL-level patterns that show up at the RTL / elaboration layer.

---

## GOTCHA-14: `Mem.readAsync` → `Mem.readSync` FSM lookahead-address conversion

**Context.** Gowin BSRAM only supports synchronous reads. `Mem.readAsync` in
SpinalHDL therefore forces the inferencer into LUTRAM / distributed SSRAM,
which is fine for small Mems but (a) wastes BSRAM budget, (b) is more
synthesis-fragile (the cascading Mem → FF promotion documented in
`[[feedback_mem_ff_cascade]]`), and (c) can constrain timing on the
combinational read path. Many `readAsync` sites are easy to flip to
`readSync + ram_style="block"` — but only if the consumer can tolerate the
1-cycle read latency.

When the consumer is an FSM that emits results in the *same combinational
cycle* as the read (boot-ROM copy, mid-frame raster RMW, single-cycle DMA
emit), a naive `readAsync → readSync` flip mis-aligns the data by one
iteration. The fix is the **lookahead-address pattern**, modeled after
`BlitterEngine.srcRam` (see `BlitterEngine.scala:152-160, 178-204`).

### The pattern

```scala
// Mem declaration — flip to readSync + ram_style="block"
val romMem = Mem(Bits(8 bits), initialContent = someInit)
romMem.addAttribute("ram_style", "block")

// Address signal: combinational, defaulted to the CURRENT iteration counter
// so the readSync port stays stable across arbitrary stalls (sdramBusy,
// refresh detours, cmd-clear waits).
val romAddr = UInt(log2Up(RomDepth) bits)
romAddr := iterCounter.resize(log2Up(RomDepth))  // default

// Registered readSync output — `romData` reflects `romMem[addressReg]`
// where addressReg was sampled at the previous clock edge.
val romData = romMem.readSync(romAddr)

// FSM fire path
fsm.activeState.whenIsActive {
  when(!stalled) {
    when(iterCounter < RomDepth) {
      cmdWr   := True
      cmdAddr := base + iterCounter
      cmdDin  := romData                                  // valid for iter N
      iterCounter := iterCounter + 1
      romAddr := (iterCounter + 1).resize(...)            // lookahead for N+1
    }
  }
}
```

### Why this works

- `romAddr := iterCounter` is the default at the top of the area. During any
  stall cycle (cmd held / sdramBusy / refresh detour), `iterCounter` doesn't
  change, so `romAddr` holds, so `addressReg` re-samples the same value, so
  `romData` keeps reflecting `romMem[iterCounter]`. By the time the fire
  guard opens, `romData` is the value for the current iteration.
- On the fire cycle, `iterCounter := iterCounter + 1` AND
  `romAddr := iterCounter + 1` (overriding the default) — both sampled at the
  same edge. Next active cycle, `addressReg = iterCounter+1` and
  `romData = romMem[iterCounter+1]`, matching the new `iterCounter`.
- Initial priming is automatic if the FSM has a "wait" state where
  `iterCounter` is already 0 (e.g., `sPowerWait`): by the time the FSM
  enters the boot-copy state, `addressReg` has already sampled 0 and the
  first `romData` is `romMem[0]`.

### Common pitfalls

- **Forgetting the default.** Without `romAddr := iterCounter` as a default,
  `addressReg` retains whatever it last sampled — usually wrong after a
  refresh detour or any state-transition stall.
- **Putting `romAddr := iterCounter + 1` outside the fire guard.** Then
  the address advances even on stall cycles, and `romData` is for the wrong
  iteration when the FSM finally fires.
- **Forward references.** The `romAddr` signal must be declared *after*
  `iterCounter` in Scala source order; SpinalHDL elaborates top-to-bottom.
  If the Mem declaration sits at the top of the area but the counter is
  declared later, put the `romAddr` / `readSync` wiring next to the counter,
  not next to the Mem.

### Validation

Sim against the original behavioral testbench is sufficient when the boot
contents are checked byte-for-byte (see `SdramTileFetchSim` lines 162-180
for the canonical pattern). The converted module should produce a
byte-identical write sequence and matching `bootDone` / `memtestPass` cycle
counts — any timing drift indicates the lookahead is mis-issued.

### Reference

- CP-B(2) demonstration: `SdramTileFetch.scala` `tileMapRom`, audit
  mail #10772, lane closed in commit
  `<populated-at-merge>` on `brightforge/readasync-conv-cpb2`.
- Prior art: `BlitterEngine.scala:152-160, 178-204` (CLS optimization
  by BronzeGate, mail #10445).
- Audit inventory: `// readAsync — AUDIT #10772:` comment blocks at all
  remaining 20 sites, classified by conversion difficulty
  (Class 1/2/3/4 — see commit `72adf70`).

### When NOT to apply

- **Class 2 (per-pixel display path).** The consumer is the active video
  raster. Even a 1-cycle pipeline delay typically requires a paired
  `RegNext` on every downstream consumer and produces a per-line first-pixel
  artifact unless the entire raster path is re-aligned. See
  `LinestateStore.commit` (`LinestateStore.scala:39-50, 77-80`) for an
  explicit "stays readAsync" rationale from a prior author.
- **Class 4 (proof-top scaffolding).** Not in the production bitstream;
  conversion risk outweighs benefit.

---

## GOTCHA-15: Ping-pong write buffer race vs emission drain

**Context.** Multiple fetch engines in this design use a ping-pong buffer
architecture: one buffer is being filled by an SDRAM fetch (`writeBuf`) while
the other is being drained to the compositor (`!writeBuf`). The flip logic
typically triggers on `fetchStartRise` (the arrival of a new SDRAM grant).

If a new grant arrives before the prior row's emission has fully completed
(`emitting=True`), a naive `writeBuf := !writeBuf` flip switches the
compositor's reader to the buffer that the new fetch is about to overwrite.
This causes mid-line corruption (reproduced in `PlanarWriteBufRaceSim`,
#10804).

### The pattern (Option α: Latch-and-flip)

The fix is an explicit **drain-complete interlock**. The flip request is
latched on the grant edge but deferred until the previous row has cleared
the emission pipeline and the word FIFO is empty.

```scala
// Latch the flip request on the grant edge
val pendingFlip = Reg(Bool()) init False
when(fetchStartRise) {
  pendingFlip := True
}

// Interlock: flip only when drain is complete
when(pendingFlip && !emitting && !wordFifo.io.pop.valid) {
  writeBuf := !writeBuf
  pendingFlip := False
}

// Invariant canary (post-fix)
assert(
  assertion = !emitting || (writeBuf === RegNext(writeBuf)),
  message   = "writeBuf changed while emitting=True (drain-complete gate broken)",
  severity  = ERROR
)
```

### Why this works

- **Safety.** The `pendingFlip` mechanism ensures the reader/writer roles never
  swap while the reader (compositor) is still active.
- **Performance.** Under production timing (e.g., `FetchSlotScheduler`), the
  inter-grant gap is typically much larger than the emission time (e.g., 28.6×
  safety ratio in `SdramTileAttributeFetch`). The stall only occurs during
  pathological stress cases and resolves as soon as the drain finishes, with
  zero impact on production bandwidth.

### Validation

Simulate using a **race-reproducer Sim** that pulses `fetchGrant` back-to-back
without waiting for emission to finish. The in-RTL `assert` must be silent
under stress stimulus.

### Reference

- Priority 3 Planar Hardening: `SdramTileAttributeFetch.scala:176-223`,
  audit mail #10809, lane closed in commit `1efa9c1`.
- Sim discriminator: `PlanarWriteBufRaceSim.scala` (reproduced race in #10804).

---

## GOTCHA-16: SDRAM co-sim — Verilator reads tri-stated `SDRAM_DQ` as `0`, causing false `0x00` write loss

**Context.** The SDRAM co-sims wire the **real** `sdram.v` controller to the behavioral
`sdram_model.v` chip through the internal `SDRAM_DQ` bus (`SdramWithModel` / `sdram_with_model.v`).
`sdram.v` drives write data on `SDRAM_DQ` for **exactly one cycle** at `{WRITE, T_RCD}`
(`sdram.v:247-248`, `dq_oen<=0`, `dq_out<={din×4}`), then **tri-states** it at `{WRITE, T_RCD+1}`
(`sdram.v:251`, `dq_oen<=1`): `assign SDRAM_DQ = dq_oen ? 32'bz : dq_out` (`sdram.v:85`). The model
samples `SDRAM_DQ` on `posedge clk_sdram` (the 180°-phased chip clock).

**The trap.** **Verilator is 2-state: it resolves high-Z (`'z`) as `0`.** If a co-sim shifts a
write's DQ-valid window off the model's `clk_sdram` sample edge — e.g. a **hand-rolled, racing
refresh** driven from a free-running timer while a bridge autonomously pops writes — the model
samples DQ during the tri-state window and latches `0x00`. The word then reads back as a false
`0x00000000` "write loss." **The RTL is correct; sdram.v drives the right data.** A real chip / a
4-state simulator latches DQ during the valid window and never sees this.

**Discriminator (all textual, no waveform needed):**
- Lost word reads `0x00000000` **exactly** (Verilator Z-read), *not* `0xDEADBEEF` (= `sdram_model` mem
  init = a genuinely un-written word). `got=0xDEADBEEF` ⇒ dropped/never-issued; `got=0x00` ⇒ issued
  then Z-sampled.
- The correct data *was* issued (the popped `din` = the expected byte) — corruption is downstream of
  issue, in the DQ handoff.
- Refresh-correlated (refresh-off = 0 losses) because the racing refresh is what shifts the alignment.
- A busy-edge `canAccept` guard does **not** fix it (the loss is the DQ sample, not command acceptance).
- Waveform confirmation: `dq_out` holds `0x55` while `SDRAM_DQ` is `0x55` (`dq_oen=0`), then `SDRAM_DQ`
  goes `0x00` the next cycle when `dq_oen=1`.

**Fix / how to avoid.** Drive writes and refresh **non-racing**, like `BurstRefreshDataSurvivalSim`:
`waitIdle()` between every command so a write fully completes before a refresh, and never interleave a
testbench refresh with an autonomous write stream. Or source refresh from the real
`SdramArbiter`/`BurstRefreshController` (single-transaction controller ownership). Do **not** build a
co-sim that pulses `bb.io.refresh` from a free-running timer against an autonomous upload bridge.

### Reference

- Lane `qspi-upload-si-hardening`, mail #14518→#14539. False reproducer:
  `QspiUploadCollisionSim.scala` (branch `brightforge/qspi-upload-collision-sim`, artifact evidence).
- Proven-clean non-racing pattern: `BurstRefreshDataSurvivalSim.scala` (SDRAM-BURST-REFRESH P16, main
  `6e6a1f3`) — real write+refresh+readback EXACT.
- Prior art / same class: `[[project_2bpp_backlog_cosim_lane]]` ("never drop/mis-time a request
  coincident with refresh; only delay").
- Memory: `[[reference_verilator_sdram_dq_z_artifact]]`.
