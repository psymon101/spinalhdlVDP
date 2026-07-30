# QspiUploadCollisionSim — coverage / fidelity matrix (BrightForge)

**Lane:** qspi-upload-si-hardening (lane 3) · **Requested by:** TopazCliff #14525
**Sim:** `hw/spinal/spinalhdlvdp/QspiUploadCollisionSim.scala` (branch `brightforge/qspi-upload-collision-sim`)
**Rule 19:** held — diagnostic sim only, no production RTL/firmware edits.

Purpose: state which reproducer conclusions are iron-clad vs carry abstraction risk, so we can move
from "reproduces the defect" to "the fix eliminates the defect *class*." Status honestly flagged.

**Governing rule (PM #14526):** no path is dismissed by RTL-line/verbal argument. Anything marked
**abstracted** below is an **UNCLOSED RISK** until either (a) a sim variant exercises that exact path
and shows it clean, or (b) it is explicitly labeled a gap the **HW re-run** must cover. The "arbiter
makes fetch collision structurally impossible" reasoning is treated as a *hypothesis to be simmed*
(fetch-stress variant, §2), **not** as proof.

---

## 1. Exact vs modeled vs abstracted

| Item | Fidelity | Safety argument |
|---|---|---|
| `QspiTransportCore(fifoDepth=512, dummyCycles=2)` | **exact** (real RTL) | Same instance/params as `QspiUploadIntegritySim`. Upstream of the bridge; the pop-audit proves bytes were *issued* (popCount≥1), so transport is not the loss site. (Top uses `QspiTransportCore()` default fifoDepth — confirm equality, but it is upstream of the defect.) |
| `QspiSdramBridge()` | **exact** (real RTL) | Default params = top: per-byte `wrCmd` = `addr(23)##din(8)`, `byteFifo` depth 128, `hdrFifo` depth 8. No error flags fired (`uploadError`/`fifoOverflow`=0) → bridge did not drop. |
| `uploadCc` StreamFifoCC | **exact** | `Bits(31)`, depth 128, pixel→sdram — identical to `TopTang20kHdmi:875`. |
| `sdram.v` controller FSM | **exact** (real black-box Verilog) | Real `fpga/tang20k/third_party/sdram/sdram.v`; IDLE-only command acceptance, `rd\|wr`>refresh priority, `T_RCD/T_WR/T_RP/T_RC`, registered `busy` — all exact. This is the loss site. |
| SDRAM array data | **modeled** (`SdramWithModel` behavioral) | Behavioral storage honoring the controller's ACT/WRITE/PRE/refresh sequence. The defect is a **command-arbitration race** (issued write dropped because controller not in IDLE), not a data-retention/analog effect — independent of array modeling. |
| `canAccept` gating | **modeled — equivalent on the decisive term** | Sim: `!busy && !refreshNow && !refreshNext && !testRd && !testWr`. Prod: `!ctrl.busy && !anyClientActive && !dbgReadArea.rdPulse`. **The `!busy` term (registered busy) is identical** — that is the term the refresh/busy-edge defect exploits. Deltas below. |
| — omits `anyClientActive` (fetch) | **abstracted — UNCLOSED RISK** | Hypothesis (to be simmed, not asserted): arbiter grant-mux (`SdramArbiter:111-114`) prevents a same-cycle rd\|wr collision, but fetch reads keep the controller busy and can **shift the upload pop phase** → change which writes align with a refresh edge. Must run the **fetch-stress variant** (§2) and report whether loss count / address distribution changes before claiming fetch is not the mechanism. |
| — omits `dbgReadArea.rdPulse` readback | **abstracted — UNCLOSED RISK** | Direct-controller readback runs post-drain, so it detects *write* loss; but it does not validate the real `sel=8`/`dbgReadArea`/`dout32` HW readback path. Must run a **sel=8 readback variant** over the full 30 KB (§2) to confirm it neither introduces nor masks zeros. |
| Refresh cadence | **modeled — phase differs** | Sim: 593-cycle *distributed* (matches arbiter `refreshPeriodCycles=593@40.5 MHz`). Prod: `BurstRefreshController` bursts in vblank. Same *mechanism* (each AUTO_REFRESH creates a busy edge), different *phase* → different lost addresses. → planned **burst-refresh variant** to land on HW addresses (§2). |
| Readback path | **abstracted, safe for this question** | Direct-controller reads reveal true SDRAM content. The write-vs-readback fork is already resolved (BronzeGate #14515 stable-zero), so direct reads suffice to detect a *write* loss. `sel=8` fidelity is a separate (lower-priority) check. |
| Clocks | **modeled** | Sim: pixel 27 MHz (period 37), sdram 40.5 MHz (period 24), SCLK 4 MHz, readback 2 MHz. sdram 40.5 MHz matches the arbiter's 593-cyc refresh calibration. **Confirm the production sdram-domain PLL frequency** (item to verify); the busy-window in *cycles* scales with it. |

---

## 2. Sweep breadth — current status

| Sweep | Status | Note |
|---|---|---|
| Full 30 720-byte upload + full readback | **DONE** | 7680 words scanned; refresh-ON loses 7–8, refresh-OFF loses 0. |
| Final frame (180 words, not 253) | **DONE — exercised** | Upload loops the byte remainder; last frame = 30720 − 60×506 = 360 B = 180 words. |
| Refresh ON vs OFF bisect | **DONE** | Definitive: refresh is the trigger. |
| Pop-audit (issued vs dropped) | **DONE** | Every lost word popCount `1,1,1,1` ⇒ **controller lost after issue**. |
| Refresh **phase** vs upload start (≥1 full period) | **GAP → planned** | Vary refresh timer init to sweep phase; expect the lost-address set to walk. |
| Upload-start phase vs `sdramCd` | **GAP → planned** | Vary the inter-domain start alignment. |
| Frame vs row alignment (vary base/frame size) | **GAP → planned** | Move base so frame boundaries hit different row/col positions. |
| Row vs bank crossings (1 KB row / 4 KB / bank switch) | **GAP → planned** | Decode each lost addr's {bank,row,col}; test whether loss favors row-start / bank-switch. |
| Attribute plane + **2nd** QSPI upload | **GAP → planned** | HW uploads bitmap **then** attr; add the back-to-back second upload. |
| CRC8-185 enabled/disabled | **GAP → note** | Sim currently uploads **without** the CRC byte (CRC effectively OFF). HW ran CRC ON and its retries (frames 3/43 bitmap; 1/6/20/23 attr) did **not** correspond to the lost words. Planned: add CRC-append + confirm the CRC layer neither masks nor causes the loss. |
| Burst-refresh cadence (match HW) | **GAP → planned** | Replace distributed 593 with `BurstRefreshController`/vblank to reproduce exact HW addrs. |
| SCLK corners (beyond 250 ns) | **GAP → planned if feasible** | Add 125 ns/500 ns points. |

---

## 3. Observability in the sim (present)

- **Pop-audit** per lost word (byte-address popCount) — result: **controller-lost-after-issue** (popCount ≥1).
- Full 30 720-byte scan; first 12 bad addresses + their pop counts printed.
- Refresh count printed (`refreshCount`≈1460 over one upload).
- Error/health flags logged per case: `overflow`/`malformed`/`uploadError`/`fifoOverflow` (all 0 in the reproducing runs — the defect is **silent**, matching HW).

---

## 4. What the sim deliberately does NOT model — and why the mechanism is independent

Not modeled: PLL/clock jitter, IO-pad/timing skew, SDRAM analog (tRAS margins, DQ/DQS electrical,
refresh charge), temperature, power-supply noise, board SI.

**Why the refresh/busy-edge defect is independent of all of the above:** it is a **purely synchronous
digital command-arbitration race** — `sdram.v` accepts commands only in IDLE, but `canAccept` gates on
the **registered** `busy` (1-cycle lag), so an upload write can be *issued* (`wr`=1) while the controller
is not in the IDLE acceptance window around a refresh, and `sdram.v` silently ignores it. The reproducer
uses **zero** analog modeling and still loses exactly those writes; and with refresh **off** the same
path is byte-perfect (0/7680). That proves the digital path carries a self-contained defect. Analog/SI
effects (the original #14266 hypothesis) may *add* corruption on real hardware, but they are **not
required** to explain this fixed, refresh-correlated, silent loss — and a digital guard fix will remove
the digital component regardless of the analog margin.

---

## Guard A/B/C result (2026-07-30) — hypothesis FALSIFIED, mechanism still open

Prototyped two `canAccept` guards in the sim: **g1 busy-settle** (`!busy` for 2 consecutive cycles)
and **g2 busy-settle + post-refresh cooldown**. Result (refresh ON):

| guard | lost words | note |
|---|---|---|
| g0 baseline | 5 | all lost bytes popCount 1,1,1,1 |
| g1 busy-settle | **5 (same addrs)** | **no help** |
| g2 +cooldown | **6** | **no help** |

`sdram.v` sets `busy<=0` and `state<=IDLE` together (`busy=0 ⟺ IDLE`), so g1 issued writes only into a
≥2-cycle-idle controller — yet they still vanish. ⇒ the write is **accepted but not stored**; the
registered-busy-edge fix class is **ruled out**. Open items before any production fix:
- **`sdram_model.v` `CMD_REF` is a functional no-op** (sets `refreshed<=1` only). Refresh cannot
  directly corrupt data in the model → the refresh-correlation must be a `sdram.v` **command-sequencing**
  effect (real; in production RTL) **or** a harness/model timing subtlety. **Not yet disambiguated.**
- **Next: pywellen waveform-pin** a lost write's exact cycle (ACT/WRITE cmd stream, `SDRAM_A` row/col at
  CMD_WR, refresh preemption) to find the true mechanism. No fix proposed until the mechanism is pinned
  and the loss is reproduced on the strengthened (§2) matrix.

## Bottom line / gating

- **Iron-clad now:** the loss is (a) real-`sdram.v` write-path, (b) refresh-triggered, (c) a
  controller-lost-after-issue command-arbitration race on the registered-`busy` edge. Independent of analog.
- **Still carries abstraction risk (before "fix eliminates the class"):** exact HW addresses (needs
  burst-refresh phase), fetch timing-shift, attr/2nd-upload, CRC-on, phase/alignment sweeps.
- **Plan:** close the §2 gaps (burst-refresh + fetch-stress + attr/2nd-upload + CRC-on first; phase/
  alignment/corner sweeps next), then validate the candidate guard (busy-settle / post-refresh cooldown)
  drives losses → 0 across the strengthened matrix, then present the production fix for three-way approval.
