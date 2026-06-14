# VDP Soft-Reset #3 — SDRAM Zero-Fill Engine (design for CyanPeak review)

Lane **VDP-SOFT-RESET-135**, increment #3. Stage 3 of the soft reset: zero **all
of SDRAM** (TopazCliff Q2 = true clean slate). Stages 1 (handshake) + 2 (on-chip
Mem clear) are done + sim-proven. This note is **for CyanPeak's timing review
before implementation** — SDRAM timing + CDC are her domain.

## Target
- SDRAM: 8 MB, byte-addressed, `addr[22:0]` (2²³ = 8,388,608 byte cells), `din[7:0]`.
- Controller (`fpga/tang20k/third_party/sdram/sdram.v`) runs in `sdramClockDomain`
  (~40.5 MHz). Command iface: `wr`, `rd`, `refresh`, `addr`, `din`, `busy`
  (0 = ready), `data_ready` (6 cyc after `wr`). **Writes are byte-wide** (no write
  burst; `burstLen` is read-only).

## Proposed architecture — Option C (controller-direct fill, arbiter bypassed)
During a soft reset the display is quiescent (layers will be re-init'd by host
after reset), so the fill can take the full SDRAM bus:

1. **Fill FSM in `sdramClockDomain`** (alongside the arbiter in `sdramArbArea`):
   sweeps `addr` 0…2²³−1, drives `din=0`, issues `wr` gated on `!busy`, asserts
   `fillDone` when the sweep completes.
2. **Command mux at the controller input:** when `fillActive`, the controller's
   `wr/addr/din/refresh` come from the fill FSM; otherwise from the arbiter
   (`sdramArbiter.io.sdram*`). Normal clients are simply not granted during reset.
3. **Cross-domain handshake** (the crux): the soft-reset controller lives in
   `pixelClockDomain` (VdpTop); the fill FSM in `sdramClockDomain`. Two new
   VdpTop ports — `out sdramFillStart` (level) and `in sdramFillDone` (level) —
   crossed with `BufferCC` both ways (precedent: existing pixel→sdram `BufferCC`
   at `TopTang20kHdmi.scala:851`). Sequence: Stage-2 mem-clear completes →
   controller raises `sdramFillStart` + holds `softResetBusy` → sdram-domain FSM
   sees synced start, runs the sweep, raises `fillDone` → synced back → controller
   drops `softResetBusy` + auto-clears `VDP_CTRL[2]`.

## Open questions for CyanPeak (need rulings before I build)
- **Q1 — duration vs BronzeGate's 500 ms poll timeout (the headline risk).**
  Byte-wide writes ⇒ 8.39 M write commands. Floor is ~207 ms (1 cyc/write @
  40.5 MHz); with command overhead + interleaved refresh it likely lands in the
  **300–600 ms** range — i.e. it may **exceed the 500 ms timeout in
  `vdp_mode0_soft_reset()`**. Options: (a) raise the host timeout; (b) add a
  **write-burst** to the controller (≈8× faster, but a controller change + cosim);
  (c) accept and bound. What's your call on the throughput target?
- **Q2 — refresh during the fill.** The fill exceeds the 64 ms retention window,
  so cells written early could decay before the sweep ends (DRAM decays toward a
  precharged state, not necessarily 0). Plan: the fill FSM **interleaves
  auto-refresh** to meet 4096 refresh/64 ms (~1 per 15 µs). Confirm cadence +
  that interleaving refresh into the write stream is the right mechanism (vs.
  routing the arbiter's `burstRefresh` path through during fill).
- **Q3 — CDC handshake.** `sdramFillStart`/`sdramFillDone` as 2-FF-synced levels
  with a full req/ack (FSM starts only on stable start; holds done until start
  deasserts). Any concern vs. a pulse-based scheme?
- **Q4 — command mux ownership.** Bypassing the arbiter for `wr/addr/din` during
  fill — confirm the refresh input ownership during the bypass and that leaving
  the arbiter ungranted (no client `wr`) is clean.

## Sim/proof plan
- SDRAM-model cosim (the burst lane's `sdram_model.v` + CAS delay line precedent):
  trigger reset, watch the fill sweep addr 0…end, verify representative cells read
  back 0 via the existing SDRAM debug readback, confirm `fillDone`/`softResetBusy`
  timing + that refresh interleaves. Then STA on the i80 top.

## Recommendation
Option C is isolated to the reset path and gives full bandwidth, but **Q1
(duration vs timeout)** likely forces a decision: I lean toward **(a) raise the
host timeout** for this lane (simplest, correct) and file the write-burst (b) as
a separate optimization, unless you want the burst now. Your ruling on Q1/Q2
drives the rest.
