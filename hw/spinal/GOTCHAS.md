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
