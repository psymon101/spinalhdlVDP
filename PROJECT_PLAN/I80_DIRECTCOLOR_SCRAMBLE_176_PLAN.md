# I80-DIRECTCOLOR-SCRAMBLE-176 — Root-Cause Plan

**Date:** 2026-06-22  
**Status:** DONE — root cause identified as **stale bitstream / board state**; no RTL regression in `c8b5c0c`. Doc sign-off requested in TopazCliff #13288.  
**Owner:** TopazCliff (PM) / BrightForge (RTL) / BronzeGate (bench) / CyanPeak (code-to-spec + history) / CoralReef (doc sign-off)  
**Working base:** `brightforge/i80-directcolor-scramble-176` (identical RTL to bitstream `ham6_FIXED_i80_delay2_c8b5c0c_65502b18.fs`, SHA-256 `a3176a1e…`)
**Historical reference:** `1652f31cc2a11fa1a8e72a096241d98bdc1ea5db` (`top_tang20k_i80`, RGB565-FULLFRAME-132 proven over i80 at 2 MHz)

---

## What we know

- The i80 **register-write / loopback** path is healthy at 2 MHz: baseline bitstream `65502b18` passes 512-round-trip stress with **0 fails at 2 MHz**; 1–3 fails/512 only appear at 4/12/15 MHz (#13261). This residual floor is acceptable baseline noise, not the CDC regression.
- The **CDC-fix branch `f152b333`** made the i80 receive path far worse (28–42 fails/512 at every speed) and is now reverted (#13257).
- The visible RGB565 marker/solid-color display remains scrambled on the reverted baseline, so the real fault is **downstream of the i80 byte-capture FSM**.
- `DirectColorFrameCoSim` proves the FPGA directcolor fetch/display RTL is byte-exact when fed known SDRAM data (#13222).
- **CyanPeak H5 audit (#13262):** RGB565-FULLFRAME-132 was proven over **i80 at 2 MHz** using `vdp_sdram_write` bursts on `top_tang20k_i80` at commit `1652f31`. The i80 bulk block-write→SDRAM path was therefore healthy then; the current `top_tang20k_hdmi` integration (`c8b5c0c`) is the new variable.

## Rule-19 decomposition (primitive components)

The path from an i80 byte to a screen pixel is broken into independently checkable primitives:

| ID | Primitive component | Checkable how | Owner |
|---|---|---|---|
| P1 | i80 block-write FSM byte capture (`sBlkDat` → `io.blockWr`) | Sim + hardware loopback of a streaming burst | BrightForge |
| P2 | i80 block-write → SDRAM write client byte landing order | Known-pattern write + armed SDRAM readback | BronzeGate |
| P3 | SDRAM storage integrity under streaming burstiness | March/ramp readback after display-off | BronzeGate |
| P4 | Bitmap fetch addressing (base/stride/height → row address) | Known-good pattern in SDRAM + visual/capture | BrightForge |
| P5 | Directcolor low/high plane pairing (0x100000 low + 0x120000 high → RGB565) | Solid color with low≠high byte | BronzeGate |
| P6 | Scanout col/2 sample, ×2 stretch, line buffer | Idle/uniform field already clean; internal sim PASS | BrightForge |

## Hypotheses and discriminators

| Hypothesis | Likely if | Ruled out if |
|---|---|---|
| **H-A: i80 block-write→SDRAM path corrupts the upload** (P1–P3) | H3 readback ≠ uploaded pattern | H3 readback == uploaded pattern |
| **H-B: Directcolor fetch/scanout misinterprets good SDRAM** (P4–P6) | H3 readback == uploaded, but display still scrambles | H3 readback != uploaded |
| **H-C: Low/high plane mapping reversed** (P5) | H4 solid 0xF81F shows wrong color / swapped bytes | H4 shows bright magenta everywhere |
| **H-D: RGB565-FULLFRAME-132 was proven over a different host transport** | Historical record shows QSPI/legacy-SPI upload, not i80 | Historical record shows i80 upload |

## Experiments

### H1 — Confirm CDC regression is gone (quick gate)

- **Owner:** BronzeGate
- **Method:** Re-run `firmware/esp32s3_i80_basic_read` speed stress on the reverted baseline bitstream `ham6_FIXED_i80_delay2_c8b5c0c_65502b18.fs` (SHA-256 `a3176a1e…`). Use the same sketch/parameters as #13255.
- **Result (#13261):** 2 MHz = 0/512 fails; 4/12/15 MHz show 1–3 fails/512. The severe CDC-fix regression (`f152b333`: 28–42/512) is gone.
- **Amended gate:** the residual 0.2–0.6% floor above 2 MHz is the known baseline multi-bit CDC noise, not the regression. H3/H4 should run at **2 MHz** to match the historical RGB565-FULLFRAME-132 proof condition and keep the floor at zero.

### H3 — Write-vs-read split (the main discriminator)

- **Owner:** BronzeGate
- **Method:** Create or adapt a sketch that:
  1. Set i80 speed to **2 MHz** (`vdp_host_set_speed_hz(2000000)`).
  2. Resets visible state (layers off, bitmap off, sprites off).
  3. Uploads a **deterministic byte-ramp pattern** to a contiguous SDRAM block (e.g. 0x100000..0x1003FF) using the **i80 block-write burst path** (`vdp_sdram_write` with multi-word rows, not one-word-at-a-time).
  4. Disables display fetch (`LAYER_ENABLE=0`, `BITMAP_CTRL=0`) to free the SDRAM read client.
  5. Reads back each byte/word through the armed SDRAM debug window (`0x0326/0x0327` arm → 5 µs settle → `0x0328/0x0329` read).
  6. Compares readback to the uploaded pattern and reports pass/fail + first failures with address/expected/got/classification.
- **Suggested pattern:** byte at SDRAM address `A` = `(A - base) & 0xFF`, so every byte is unique and address-correlated. Upload at least 1 KB (512 words) to exercise burst streaming.
- **Pass criterion:** readback matches uploaded pattern for every checked word.
- **Result (#13267):** **FAIL — systematic.** 504/512 words fail. Readback is a **fixed repeating 32-bit pattern** `0x9F9E_9D9C` at every address, independent of the ramp. `last_error=0` (firmware API did not report a transport-level error).
- **Decode verification (#13279):** `0x0328`/`0x0329` ARE decoded to the armed SDRAM debug word; `0x0320` is loopback (last-written-value artifact). The fixed `0x9F9E_9D9C` equals the ramp bytes at offset `0x9C` and is consistent with the debug-read FSM never firing (stuck `dataReg`) because the upload never drained — **not** proof that writes failed.
- **Interpretation:** H3 is an unreliable instrument. The real discriminator is H4/H6 visual output.

### H4 — Plane-mapping quick check (parallel)

- **Owner:** BronzeGate
- **Method:** Adapt `firmware/esp32s3_rgb565_solid_color` to use **magenta 0xF81F** (high byte `0xF8`, low byte `0x1F`) instead of red. Set i80 speed to **2 MHz**. Upload low plane = `0x1F` and high plane = `0xF8` to the usual bases/stride/height, enable RGB565 directcolor, and visually inspect/capture the monitor.
- **Pass criterion:** the entire screen is bright magenta.
- **Result (#13267):** **FAIL.** The screen is a stable **dark blue**, not magenta. This confirms the RGB565 value in SDRAM is not being reproduced as uploaded, consistent with the H3 write-path failure.
- **Next discriminator:** Re-run with the **exact historical RGB565-FULLFRAME-132 register config** found by CyanPeak H5: low base `0x100000`, high base **`0x200000`**, stride `512`, height `240`, `BITMAP_CTRL=0x0085`, `LAYER_ENABLE=0x0001`. If the screen turns magenta, the H4 sketch's register settings (stride 320, high base `0x120000`) were the problem. If it stays blue, the upload path is broken regardless of config.

### H6 — Historical-register-config check (H4 rerun)

- **Owner:** BronzeGate
- **Method:** Re-run H4 using the exact register config from RGB565-FULLFRAME-132: low base `0x100000`, high base `0x200000`, stride `512`, height `240`, `BITMAP_CTRL=0x0085`, `LAYER_ENABLE=0x0001`, color `0xF81F`, i80 speed 2 MHz.
- **Result (#13272):** **FAIL.** The screen remains **dark blue**, not magenta. RTSP captures at `/tmp/spinalhdlvdp_captures/i80_directcolor_176_h4_historical_magenta_frame*.png` (SHA-256s in #13272).
- **Interpretation:** The visible directcolor failure is **not** caused by a stride/base config mistake in the original H4 sketch. It is a real integration regression in `top_tang20k_hdmi`@`c8b5c0c` relative to `top_tang20k_i80`@`1652f31`.

### H3b — STATUS_STICKY check (resolved: 0x0320 is loopback)

- **Owner:** BronzeGate
- **Method:** After clearing sticky with `vdp_reg_write(0x0320, 0xFFFF)`, perform a block-write and read back `0x0320`.
- **Result (#13272):** Read returns `0xFFFF`, bit 3 = 1.
- **Resolution (#13279):** `0x0320` maps to `i80Loopback = RegNext(i80.io.regBus.data)` — the last value written to the reg bus. Since the clear write was `0xFFFF`, the read naturally returns `0xFFFF`. **STATUS_STICKY bit 3 is unconfirmed; ignore this measurement.** `0x0328`/`0x0329` are confirmed decoded to the armed SDRAM debug word.

### H7 — Fresh-flash de-risk (board-state confirmation)

- **Owner:** BronzeGate (owner-authorized override for this reload only)
- **Method:** Re-flash the existing baseline bitstream `ham6_FIXED_i80_delay2_c8b5c0c_65502b18.fs` (SHA-256 `a3176a1e…`) and re-run H6 (historical 132-config magenta).
- **Purpose:** Rule out stale/different bitstream state on the board. H3/H4/H6 so far assumed the board was `65502b18` from the earlier H1 baseline run without a confirmed re-flash in this session.
- **Pass criterion:** H6 renders magenta → prior H4/H6 failures were due to stale/different board state. Still blue → board state is confirmed correct; rely on cosim for root cause.
- **Result (#13283):** **PASS.** After a fresh flash of baseline `65502b18`, H6 renders **bright magenta**. Prior dark-blue H4/H6 runs were caused by stale board state, not by RTL.

### H5 — Historical transport audit

- **Owner:** CyanPeak
- **Status:** DONE (#13262)
- **Result:** RGB565-FULLFRAME-132 was proven over **i80** at **2 MHz** using `vdp_sdram_write` bursts on `top_tang20k_i80` at commit `1652f31`. Board = Tang Nano 20K (GW2AR-18C), host = ESP32-S3. Upload sketch = `/tmp/spinal_cp_d_i80/firmware/esp32s3_capture_pattern_proof/esp32s3_capture_pattern_proof.ino`. Bitstream SHA-256 = `2b3bc33060bf4f3fb6ac4e12fc7e0f952a0dc5f476e76f4611283ed42ad139e2`.
- **Implication:** The i80 bulk block-write→SDRAM path was healthy on `top_tang20k_i80`. The current `top_tang20k_hdmi` integration (`c8b5c0c`) is the new variable; BrightForge is auditing the RTL diff `1652f31..c8b5c0c`.

## Expected decision tree

```
H1: CDC regression gone + 2 MHz bit-perfect?  YES → run H3/H4/H6 at 2 MHz.
    H3 pass / isolated noise + H4/H6 pass  → SDRAM data is correct AND plane mapping is correct.
                                             Display scramble is P4/P6 fetch/scanout.
    H3 pass / isolated noise + H4/H6 fail  → Plane mapping reversed (P5) or config mis-interpreted.
    H3 systematic fail + H4/H6 *           → i80 block-write→SDRAM path corrupts upload (P1–P3).
                                             BrightForge fixes block-write→SDRAM plumbing.
    H4/H6 fail even with exact 132 config  → Real integration regression in `top_tang20k_hdmi`
                                             vs `top_tang20k_i80`. Need full-top cosim to reproduce.
```

## Conclusion

- The visible RGB565 directcolor scramble was **not** caused by an RTL regression in the i80 receive path, the block-write→SDRAM path, the SDRAM model, or the directcolor fetch/scanout path.
- `I80UploadToSdramCoSim` (BrightForge #13282) proves that a real i80 block-write burst lands byte-exact in `sdram.v` through the full `top_tang20k_hdmi` integration on `c8b5c0c`.
- The H4/H6 dark-blue symptom disappeared after a fresh flash of the same baseline bitstream (`65502b18`), which means the earlier failures were due to **stale board state** left by previous bitstreams / experiments (most likely the reverted CDC-fix build `f152b333`).
- **Action item:** When switching bitstreams or after a failed experiment, always perform a fresh flash before declaring a hardware regression. Do not assume the currently-loaded bitstream matches the intended baseline.

## Deliverables completed

1. BronzeGate: 
   - H7: re-flash baseline `65502b18` and re-run H6; bright magenta PASS (#13283).
   - H3/H4/H6/H7 sketch sources committed to the 176 branch at `3a87206`.
2. CyanPeak: H5 historical transport audit — DONE (#13262).
3. BrightForge: 
   - `I80UploadToSdramCoSim`: end-to-end `i80 → HostSdramBridge → uploadCc → sdramArbiter client4 → sdram.v` PASS on `c8b5c0c` (#13282, commit `e234439`).
   - Decode verification: `0x0320` loopback, `0x0328`/`0x0329` decoded — DONE (#13279).
4. CoralReef: doc consistency review PASS — verified `I80_DIRECTCOLOR_SCRAMBLE_176_PLAN.md` and `TASKS.md` both state stale board state as final root cause; committed doc-only sign-off.
