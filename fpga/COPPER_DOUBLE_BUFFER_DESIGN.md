# Copper double-buffered program — parked design

**Status:** parked, not implemented. No active demand.

**Origin:** scoped 2026-05-18 in the context of "could the copper-bars demo emulate Amiga bouncing copper bars / demoscene effects." See mail thread 10189 for the bench debug that led here.

**Trigger to implement:** a demo or scene that needs glitch-free animated copper effects (bouncing bars, gradient skies, mid-screen mode changes, wobble text). Don't open a lane until there's a specific use case.

## Why this exists

The current Copper allows program upload only while `io.enabled == False` (`Copper.scala:81`). For animated effects, the host would need to `disable → wait → re-upload → enable` every frame. With ESP8266 1-bit bit-bang QSPI, that cycle takes ~5ms — larger than the ~3ms vblank window — so re-upload during active video is visible as tearing.

Amiga's superpower was live in-place modification of the running copper list (Blitter or CPU writes while Copper executed). This design replicates that with a double-buffered program RAM and an atomic bank-swap.

## Design

```
prog: Mem(Bits(16 bits), 1024)    // was 512
  bank 0: addr 0x000..0x1FF
  bank 1: addr 0x200..0x3FF

readAddr  = activeBank ## pc                  (10 bits)
writeAddr = writeBank  ## io.progAddr         (10 bits)

writeBank logic (preserves back-compat):
  io.enabled == False  → activeBank   (existing path: disabled host fills active)
  io.enabled == True   → !activeBank  (new path: enabled host fills inactive)

activeBank swap:
  triggered by host writing 1 to VDP_CTRL bit[1] (COPPER_SWAP_REQUEST)
  commits at vCounter==vSyncStart && hCounter==0 (frame-atomic, matches MODE_SELECT precedent)
  on commit: activeBank toggles, pc := 0, swapPending clears
```

### Register map delta

`VDP_CTRL @ 0x0310`:

| Bit | Name | Sem |
|-----|------|-----|
| 0 | `COPPER_ENABLE` | unchanged |
| 1 | `COPPER_SWAP_REQUEST` | host writes 1 → swap pending → FSM swaps `activeBank` at next `vSyncStart`, then HW auto-clears |

Host can write `0x0003` to swap + stay enabled in a single transaction.

### Host workflow

```c
// Boot: upload to bank 0 (active), enable
vdp_copper_upload(prog_v0, n);              // disabled → bank 0
vdp_copper_enable(true);                     // copper running

// Per-frame in vblank, no disable:
build_prog_for_frame(prog_vN);
vdp_reg_write_burst(0x0400, prog_vN, n);    // enabled → bank 1 (inactive)
vdp_reg_write(0x0310, 0x0003);              // request swap; copper stays enabled
// FSM picks up bank 1 at next vSyncStart; pc resets to 0
```

## Effort

| Phase | Work | Duration |
|---|---|---|
| RTL (`Copper.scala`, `VdpTop.scala`) | ~40 LoC surgical | 0.5 day |
| `CopperSim` extension (5 new cases) | live-upload doesn't disturb running, swap atomicity, pc reset on swap, swap_request auto-clear, back-compat | 0.5 day |
| New VdpTop integration sim case | end-to-end live update → BORDER_CTRL chain | 0.5 day |
| MemReport + resource verification | confirm 1024×16 fits one BSRAM | 0.25 day |
| Gowin synth + PnR run | mostly waiting | 0.5 day |
| libvdp helper (`vdp_copper_swap_request`) + sketch update | TopazCliff lane | 0.5 day |
| Bench validation | TopazCliff lane | 0.5 day |
| Audit + close | CoralReef lane | 0.25 day |

**Total:** ~3.5 days FPGA (BrightForge) + 1 day firmware (TopazCliff) + 0.25 day audit (CoralReef). Calendar: 1 week serialized, 3–4 days parallel.

## Resource budget

**MemReport-verified, post-CP-C** (`VdpTop` standalone, 2026-05-18):

| Component | Pre-CP-C estimate (wrong) | Pre-CP-C actual (`readAsync`) | Post-CP-C actual (`readSync` + `ram_style="block"`) |
|---|---|---|---|
| `prog` Mem inference | distributed | **distributed** | **BSRAM block** |
| BSRAM blocks | 1 (claimed) | 0 | **1** |
| SSRAM cells | ~0 (claimed) | **256** | **0** |

The original "BSRAM: still 1 block" estimate ignored a critical detail: Gowin BSRAM does not support async read. **A `Mem` with any `readAsync` port falls back to distributed/LUTRAM inference regardless of size or the `ram_style` attribute.** This is the same trap that `hdmaDataArray`, `palette`, `blitterEngine/srcRam`, and several other `readAsync`-port Mems in this codebase fall into — see MemReport output for the full list.

To actually land the `prog` Mem in BSRAM, two changes are required together:

1. `prog.readAsync(addr)` → `prog.readSync(addr)` (gets the registered-output read port)
2. `prog.addAttribute("ram_style", "block")` (Gowin hint — mirrors `LinestateStore.prepare`'s pattern from `49c3a5f`)

`readSync` adds 1 cycle of read latency. To preserve the pre-CP-C zero-latency FSM dispatch semantics — so `fetchWord` still matches the *current* `pc` rather than lagging by one cycle — the FSM uses a **predictive `pcNext` / `activeBankNext` pattern**: `pc` and `activeBank` are split into Reg + combinational `Next` wires, and the read port is addressed with the next-cycle values. This makes the conversion semantically transparent — observable FSM behavior (including CopperSim case 6's `hCounter≈3` dispatch latency) is identical pre- and post-CP-C.

- BSRAM: 1 block (1024×16 fits one Gowin BSRAM block, **only with readSync + ram_style="block"**)
- LUT: ~20 (swap FSM + muxes) + ~5 (pcNext / activeBankNext default-and-override wires)
- DFF: ~3 (`activeBank`, `swapPending`, swap-edge latch)
- **Net SSRAM vs HEAD: −128 cells** (HEAD's 512-word readAsync used ~128 cells; CP-C's 1024-word readSync uses 0)
- Well within current Tang Nano headroom per `TASKS.md`: 5874 LUT (28%), 3791 Reg (24%), 6888 CLS (67%) on `mode2optimized-gate2-enableL2L3 @ 22afb90`.

## Open design questions (decide at lane-open time, not now)

1. **`COPPER_SWAP_REQUEST` auto-clear vs sticky.** HW-auto-clear is cleaner but the host has no signal that the swap actually happened. Consider adding bit[2] = `COPPER_SWAP_DONE` status (read-only, sticky, host clears by writing 1) if observability matters.
2. **Behavior of `COPPER_SWAP_REQUEST` while disabled.** Simpler: ignore until enable. Alternative: latch and apply on next enable.
3. **Bank count.** Two banks is the minimum useful design. Four banks (2-bit select) costs ~5 more LUT and 1 more bit and enables pre-staged scene cycles. Probably YAGNI.
4. **Swap commit cadence.** vSyncStart is frame-atomic and matches the MODE_SELECT pattern. hCounter==0 of any line is lower-latency but riskier. Recommend frame-atomic for v1; relax later if a demo needs sub-frame swap.

## Anti-pattern to avoid

**Don't** just delete the `!io.enabled` gate in `Copper.scala:81` as a shortcut (this was 3a in the discussion). The Mem `readAsync` race semantics are not well-defined for mid-instruction multi-word reads (WAIT_PX, WRITE, WRITE_SEQ are 2+ words each), and the resulting "fetch a half-old / half-new opcode" failure mode is impossible to reason about. Double-buffering eliminates the race entirely; do that or nothing.

**Don't** double the `prog` Mem without converting to `readSync` + `ram_style="block"` at the same time. The first attempt during CP-C doubled to 1024 words while keeping `readAsync` — Gowin inferred the Mem as distributed RAM and added **+128 SSRAM cells of pressure** rather than landing in a BSRAM block. The readSync conversion is what makes the doubling effectively free.

## Cross-references

- Bench-validated rule that motivates this: [host program Y-1 adjustment is single-shot-only](../) — see BrightForge memory `reference_copper_loop_y_semantics.md` and mail #10199.
- Existing safe-boundary commit precedent: `VdpTop.scala:737` (per-scanline at `hCounter===0`), `VdpTop.scala:1894` (frame-atomic at `vCounter===vSyncStart && hCounter===0` for MODE_SELECT).
- Current Copper architecture: `Copper.scala` package comment block, especially the host upload section.
