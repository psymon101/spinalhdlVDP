# I80-CDC-FIX-175 A/B Failure — Systematic Hypothesis Matrix

**Date:** 2026-06-21  
**Status:** **ABANDONED / REVERTED** (#13256). The CDC-fix commits (`3fbeec4` + `a3a2866`) were a regression and are reverted. The visible display scramble is a downstream bulk-write/scanout issue, now tracked in lane **`I80-DIRECTCOLOR-SCRAMBLE-176`**.

**Context:** BronzeGate ran the first A/B of CDC-fix bitstream `f152b333` vs baseline `65502b18` using `firmware/esp32s3_rgb565_left_edge_markers`. Both produced scrambled RGB565 marker output. The i80 CDC fix may be correct but the visible failure has another cause. This matrix drives ordered, falsifiable experiments.

**Rule:** no iteration without evidence. Each experiment must produce a clear PASS/FAIL and either kill a hypothesis or send us down a narrower branch.

---

## Terminology

| Term | Meaning |
|------|---------|
| **scramble** | visible RGB565 marker output is not the expected clean grid/marker image |
| **CDC glitch floor** | the ~0.2% speed-independent multi-bit word corruption seen in `esp32s3_i80_basic_read` loopback stress on `65502b18` |
| **loopback** | write value V to a default register (e.g. `0x0347`), read it back |
| **bulk-write** | the i80 block-write / `SDRAM_WRITE` path that streams pixel data into SDRAM |

---

## Hypotheses (in test order)

### H1 — The CDC fix did not remove the i80 glitch floor
**Assertion:** `f152b333` still has the ~0.2% multi-bit CDC corruption; the visual scramble is just the streaming-burst amplification of that floor.  
**Test:** Run the same 512-round-trip speed-stress loopback on `f152b333` that BronzeGate ran on `65502b18` (#13224/#13225). Sweep 2→4→6→8→10→12→14→16→20 MHz; record pass/fail per speed.  
**Result (#13247):** `f152b333` failed hard: 28–42 failures per 512 samples at every speed, far worse than the baseline sparse ~0.2% floor.  
**Critical follow-up:** Before concluding the CDC RTL is broken, re-run the **same sketch** on `65502b18` baseline. If baseline now also shows high failure rates, the test harness/sketch/classifier changed and is corrupting the comparison. If baseline stays sparse, the regression is real in `f152b333`.  
**Owner:** BronzeGate (re-run) + BrightForge (analyze).  
**Effort:** low — reuses existing sketch; one flash back to baseline.  
**Next if regression confirmed TRUE:** CDC fix is ineffective or harmful. Use `PROJECT_PLAN/I80_CDC_HANDSHAKE_COMPONENT_CHECKLIST.md` to decompose the handshake into primitive components; identify the failing component with evidence before proposing a fix.  
**Next if regression falsified (baseline also bad):** fix the test harness; then re-run H1/H2.  
**Next if H1 PASS after fix:** CDC fix works; reject H1 and move to H2.

---

### H2 — The marker sketch itself uploads bad data / has a config bug
**Assertion:** `esp32s3_rgb565_left_edge_markers` has a firmware bug (plane split, stride, scale, or register setup) that scrambles the image independent of the i80 receiver.  
**Test:** Build and flash a **minimal solid-color full-frame directcolor sketch** on `f152b333`:
- Set `LAYER_ENABLE=1`, `BITMAP_CTRL` bpp=2, scale=640×480 (logic = display size).
- Upload a single RGB565 value to **one full plane** (e.g., 0xF800 red, 0x07E0 green, 0x001F blue, 0xFFFF white, 0x0000 black).
- Observe whether the screen is a clean solid color or scrambled.
**Result (#13247):** Solid red (`0xF800`) was not clean — persistent horizontal banding and bottom artifacts.  
**Expected if H2 TRUE:** solid color is clean; marker sketch/config is the fault.  
**Expected if H2 FALSE:** solid color is also scrambled; problem is in upload path or display/SDRAM path.  
**Owner:** BronzeGate (sketch) + BrightForge (review expected result).  
**Effort:** low-medium — new tiny sketch, ~30 min.  
**Next if TRUE:** fix/replace marker sketch, then re-run A/B.  
**Next if FALSE:** reject H2 and move to H3 (but only after H1 regression is resolved).

---

### H3 — The host bulk-SDRAM-write path corrupts streaming pixel data
**Assertion:** i80 register loopback is clean, but the `SDRAM_WRITE` burst transaction loses/duplicates words when streaming large pixel blocks.  
**Test:** With display/fetch idle, write a known unique pattern (e.g., LFSR sequence or address-derived words) to one full RGB565 plane at the normal base, then read it back and compare.  
- Must use a **reliable read-back method**; the current `0x0328/0x0329` debug read is starvation-prone during active display, so either idle the display or use a guaranteed-slot read mechanism.
- If no reliable read path exists, this test is blocked and becomes a new diagnostic sub-lane.
**Expected if H3 TRUE:** read-back mismatches, especially burst-desync signatures (all subsequent words shifted).  
**Expected if H3 FALSE:** read-back matches exactly.  
**Owner:** BronzeGate + BrightForge (if read-path tooling needed).  
**Effort:** medium — may need a small firmware helper or RTL debug-read fix.  
**Next if TRUE:** fix bulk-write path or its handshake.  
**Next if FALSE:** reject H3 and move to H4.

---

### H4 — The FPGA directcolor fetch/display path has a real-hardware issue not caught by co-sim
**Assertion:** `DirectColorFrameCoSim` PASS used an ideal SDRAM model; real SDRAM read timing/refresh/SI exposes a fetch bug.  
**Test:** Generate a test pattern **inside the FPGA** (no host upload) and display it. If the internal pattern is clean, the fetch/display path is healthy.  
- Option A: use any existing built-in test-pattern mode / FPGA self-test if available.
- Option B: flash a known-good earlier bitstream that displayed a clean directcolor image (e.g., RGB565-FULLFRAME-132 hardware proof) and verify it still works with the current board/cables.
**Expected if H4 TRUE:** internal pattern is scrambled, or the old known-good bitstream now also scrambles.  
**Expected if H4 FALSE:** internal/known-good pattern is clean.  
**Owner:** BrightForge.  
**Effort:** low if option A/B exists; medium if new RTL test needed.  
**Next if TRUE:** debug directcolor fetch/SDRAM read path.  
**Next if FALSE:** reject H4 and move to H5.

---

### H5 — Build / place-and-route non-determinism produced a bad bitstream
**Assertion:** `65502b18` (baseline) and/or `f152b333` are bad P&R outcomes; another build from the same source may be clean.  
**Test:** Rebuild each source 3× (Gowin P&R effort 2) and test **one** new build of each with the simplest proven test (solid color from H2, or basic loopback).  
- If H2 already produced clean solid color on `f152b333`, H5 is already falsified for the fix build.
- If H2 still scrambles, try a fresh build.
**Expected if H5 TRUE:** a fresh build behaves differently (clean or different corruption).  
**Expected if H5 FALSE:** all builds reproduce the same behavior.  
**Owner:** BrightForge (builds) + BronzeGate (flash).  
**Effort:** high — ~30 min per build × 3; only run if H1–H4 are inconclusive.  
**Next if TRUE:** adopt 3-build policy and keep the good build; root-cause the bad P&R seed.  
**Next if FALSE:** reject H5; problem is deterministic source/firmware/config, not P&R.

---

## Decision tree summary

```
A/B FAIL (both scrambled)
  │
  ▼
H1: loopback stress on f152b333
  ├─ still glitches → CDC fix broken → BrightForge fixes RTL
  └─ clean → CDC fix works
       │
       ▼
  H2: solid-color full-frame test
       ├─ clean → marker sketch bug → BronzeGate fixes sketch
       └─ scrambled → upload/display path issue
            │
            ▼
       H3: bulk-SDRAM write/read-back test
            ├─ mismatches → bulk-write path bug → fix
            └─ matches → data in SDRAM is correct
                 │
                 ▼
            H4: internal FPGA test pattern / known-good bitstream
                 ├─ scrambled → real-hardware fetch/display bug → BrightForge
                 └─ clean → host upload/config issue (H2/H3 already narrowed)
                      │
                      ▼
                 H5: 3× rebuild + retest (only if above inconclusive)
```

---

## Evidence log

| Step | Hypothesis | Build | Result | Date | Owner |
|------|------------|-------|--------|------|-------|
| 0 | Baseline vs fix A/B marker test | 65502b18 / f152b333 | both scrambled | 2026-06-21 | BronzeGate |
| 1 | H1: CDC fix removed glitch floor | f152b333 / 65502b18 | **REGRESSION CONFIRMED** — `f152b333` fails hard (28–42/512 at all speeds); baseline `65502b18` is clean through 12 MHz with only isolated failures at 15 MHz clamp (#13253/#13255). Failure is in the CDC-fix RTL, not the test harness. | 2026-06-21 | BronzeGate |
| 2 | H2: marker sketch/config bug | f152b333 | **FAIL / solid red not clean** — persistent horizontal banding and bottom artifacts; likely downstream consequence of H1 or a separate structural issue | 2026-06-21 | BronzeGate |
| 3 | H3: bulk-SDRAM write corruption | — | **N/A — lane abandoned** before H3; downstream scramble now tracked in `I80-DIRECTCOLOR-SCRAMBLE-176` | 2026-06-21 | BronzeGate / BrightForge |
| 4 | H4: real-hardware fetch/display bug | — | **N/A — lane abandoned** before H4 | 2026-06-21 | BrightForge |
| 5 | H5: P&R non-determinism | — | **N/A — lane abandoned** before H5 | 2026-06-21 | BrightForge / BronzeGate |

---

## Conclusion

- **H1 root cause:** The CDC fix (commit `3fbeec4`) made `wrCapture.data` and `wrCapture.toggle` transition on the same WR# rising edge. The pixel-domain FSM then samples `dInS` in the same cycle that `wrRise` detects the toggle change, while the 8-bit `BufferCC(wrCapture.data)` synchronizer is still resolving per-bit metastability. This reproduces the multi-bit CDC hazard the fix was intended to remove, but at a much higher rate (~7% vs ~0.2%).
- **Decision:** Revert `3fbeec4` + `a3a2866`; keep `c8b5c0c` (HDMI left-edge delay=2). The baseline `65502b18` bitstream is loopback-clean and becomes the working base.
- **Residual display scramble:** Because baseline loopback is clean yet the marker display still scrambles, the visible corruption is a **separate downstream issue** (bulk SDRAM-write path or directcolor scanout), not the i80 byte-capture. Follow-on investigation is now lane **`I80-DIRECTCOLOR-SCRAMBLE-176`** (owner: BrightForge).

## Constraints

- Do not start H3 until H1 and H2 are done.
- Do not start H5 until H1–H4 are done or explicitly blocked.
- Every result must be posted to the `i80-cdc-fix` mail thread with the exact command/sketch, observed output, and a clear conclusion for the hypothesis tested.
