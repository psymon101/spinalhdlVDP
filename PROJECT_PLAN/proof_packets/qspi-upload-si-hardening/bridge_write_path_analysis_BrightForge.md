# QSPI upload SI hardening — bridge/SDRAM write-path analysis (BrightForge)

**Lane:** qspi-upload-si-hardening (lane 3) · **Date:** 2026-07-30 · **Author:** BrightForge (RTL)
**Requested by:** TopazCliff #14509 · **Input data:** BronzeGate stress #14508
**Scope:** read-only RTL analysis; Rule 19 open (no RTL/firmware edits until agreement).

## Symptom (from #14508)

- 30 cold cycles, each uploads 30720 B bitmap + 30720 B attr at 4 MHz, readback at 2 MHz.
- **15/30 FAIL.** Every failure is the **same two word addresses**: `0x100008` and `0x101000`
  (bitmap base `0x100000`), expected `0x55555555`, got **`0x00000000`** (whole word zero).
- All other sampled words pass. No CRC-retry failure. Health `raw=0/overflow=0/malformed=0`
  (read at word-drain `sel=0x0A`). CRC counter (`sel=0x0B`) engaged; retry present.

## Write-path topology (verified in code)

```
QspiTransportCore (SCLK→sysCd, contains QspiSlaveSync CRC)
  → QspiSdramBridge (pixel domain): per-byte wrCmd = addr(23)##din(8), addrReg += 1/byte
     - byteFifo depth 128 (ingress, NO flow control on byteValid)
     - hdrFifo depth 8 (re-anchors addr/len per header — #11321 back-to-back fix)
     - allowUpload := True  (TopTang20kHdmi:497; blanking gate REMOVED #11246 F5)
  → uploadCc StreamFifoCC depth 128 (pixel→sdram, addr+data atomic — #11123)
  → uploadPopArea.canAccept (sdram domain): pop.ready = !ctrl.busy && !anyClientActive && !dbgRead
  → SdramArbiter client 4 (UPLOAD DMA, priority below refresh+fetch), burstRefresh=true
  → sdram.v (third-party black box, 2048 rows), 32-bit data, O_sdram_dqm[3:0] byte-lane mask
```
Readback path: `dbgReadArea` client 5 (sel=8), returns 32-bit `ctrl.io.dout32`; fires only when
`sdramIdle && uploadDrained`.

## Q1 — Is there a bridge/SDRAM retry state hazard that leaves a word at zero despite a passing CRC?

**Bridge itself: no plausible retry hazard.** A host retry is a fresh `SDRAM_WRITE` frame with its
own header; the bridge re-anchors `addrReg`/`bytesLeft` per header via `hdrFifo` (the #11321 fix).
Byte writes are idempotent (per-byte, no read-modify-write in the bridge), so re-writing the same
address just overwrites with the same value. Nothing in the bridge FSM leaves a previously-written
word at zero on a same-address retry.

**But there is a real observability gap.** The bridge's `fifoOverflow`, `uploadError`, `uploadDone`
outputs are **not routed to any host-readable status** in the MVP top (`sel=6 not carried` —
TopTang20kHdmi:505-507, 877-880). So BronzeGate's `overflow=0/malformed=0` at `sel=0x0A` is the
**word-drain** health, **not** the bridge. Any bridge-side drop (ingress `byteFifo` overflow with
no flow control on `byteValid`, or a stall-watchdog abort) would be **silent to the host**.
- *However*, a dropped ingress byte would DESYNC the stream (shift every subsequent address by one),
  which contradicts "only 2 words wrong, everything else byte-aligned." So an ingress drop is an
  unlikely mechanism — but the observability gap should still be closed (route `sel=6`) so we can
  positively rule it out rather than infer it.

## Q2 — Do `+0x8` / `+0x1000` map to a frame/burst/FIFO/row boundary?

- **`0x101000` = base + `0x1000` = 4096 B.** This is the classic **4 KB SDRAM row/page boundary**
  and is the *first word of a new row*. Row crossings force PRECHARGE + ACTIVATE (extra `ctrl.busy`
  latency); the first write into a freshly-activated row is the canonical point for a lost write
  (tRCD not yet met) or a readback-across-row artifact. Strongest structural signal in the data.
- **`0x100008` = base + 8 B** (3rd 32-bit word). Near upload start; not a 4 KB boundary. Candidates:
  first-frame/first-row transient, or alignment with the fetch read-burst or the host frame chunk.
  **Cannot be pinned from RTL alone** — needs (a) the exact `sdram.v` row/bank/column geometry and
  (b) the host frame chunk size + which upload frame covers each failing address.

## Q3 — Write-path bug, readback artifact, or remaining SI?

**Not classic SI.** Signal-integrity margin failures produce *random* bit-flips at *random*
addresses that vary per run. A clean `0x00000000` at **two fixed addresses** across 15/30 cold boots
is **address-deterministic → structural**, not analog. I explicitly revise the blanket #14266
"physical SI" attribution *for this specific residual*: the determinism is the tell. (SI may still
set the overall ceiling, but it does not explain fixed-address whole-word zeros.)

**Two leading structural candidates, roughly equally weighted pending BronzeGate's re-read:**
1. **Lost upload write at a specific arbiter/controller timing point** — same *class* as the
   already-fixed CyanPeak GT-17 (registered `sdramRd/Wr` → pop swallowed by `sdram.v` rd|wr ternary)
   and #11144. The current `canAccept` guards fetch clients (current+next) and arbiter-owned refresh,
   so the *known* gaps are closed. A residual would be an *uncovered* window — e.g. planar is gated
   current-only (line 1158-1162; not a live client for 2bpp so unlikely here), a row-activate latency
   that `ctrl.busy` doesn't fully cover at the `0x101000` crossing, or a burst-refresh-in-vblank
   interaction. Would yield stable-zero-in-SDRAM.
2. **Readback-path artifact** — the `sel=8` debug read (`dbgReadArea`/`dout32`) has documented
   fixed-data history (#10928), is **32-bit-word granular** while writes are **per-byte**, and reads
   `0x101000` exactly across a row boundary. Would yield *varying* re-reads or method-dependent zeros.

## Recommended discrimination (before any delta)

1. **BronzeGate re-read the two failing words 5-10× at 2 MHz** (already tasked #14509):
   - stable `0x00000000` ⇒ data is genuinely zero in SDRAM ⇒ **write/SDRAM path**;
   - varying values ⇒ **readback path** (`sel=8`) suspect.
2. Provide the **`sdram.v` geometry** (rows/banks/columns, page size) and the **host frame chunk size**
   + which frame covers `0x100008` and `0x101000`, with the per-frame CRC-delta correlation.
3. **Close the observability gap:** route the bridge `sel=6` status (fifoOverflow/uploadError) so a
   bridge-side drop can be positively ruled in/out on the next run. (Small, non-behavioral surface add
   — still gated behind the Rule 19 checkpoint.)

## My next RTL step (conditional, after the log)

- If **write-path** confirmed: I build a targeted **upload-vs-(row-activate/refresh) collision co-sim**
  (extending `SdramUploadSim`/`QspiUploadIntegritySim`) to reproduce the fixed-address loss
  deterministically, then scope the **minimal** guard fix — not a rebuild.
- If **readback-path** confirmed: the fix lives in the debug-read path or the firmware readback method,
  likely **no** write-path RTL change.

**Bottom line:** the lane's own #14266 "physical SI" framing does not fit this residual; it looks
structural (write-path or readback). One re-read experiment settles which. Holding RTL per Rule 19.
