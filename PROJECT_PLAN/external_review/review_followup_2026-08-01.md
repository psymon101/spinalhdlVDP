# Follow-up for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Previous review identified a likely 1-read pipeline lag in the `sel=8` debug readback path as the explanation for deterministic zeros at `0x100008` and `0x101000`. We implemented the proposed confirmation tests. They did **not** confirm the hypothesis, so we need the next most-likely root-cause hypothesis and a practical discriminator.

---

## What we tried since your last review

### 1. Fix the `memcpy` overlap bug in `write_frame()`
- Changed `memcpy(s_tx_buf, frame, frame_len)` → `memmove(...)` where `frame` can alias `s_tx_buf`.
- Commit: `619f76b8`.
- Result: build passed; observed zeros unchanged.

### 2. `sel=8` double-read diagnostic (Mode 6)
- For each target address, we issued `READ_STATUS sel=8` **twice** and reported both 32-bit values.
- Tested `0x100004`, `0x100008`, `0x10000C`, `0x100FFC`, `0x101000`, `0x101004`.
- Result: **both first and second reads returned `0x00000000` every time** across 8 repeats, with clean health (`raw=0`, `overflow=0`, `malformed=0`).
- Implication: a simple one-read pipeline lag does **not** explain the observation, unless the second transaction also re-triggers the same lag/artifact.

### 3. Display-output indirect readback (Mode 7)
- Painted the target words (`0x100008`, `0x101000`) with palette index `0xAA` and rendered the bitmap in normal Mode 0.
- Result: three identical 720×480 HDMI captures showed **no distinctive palette-2 block**; images remained grayscale/cyan.
- Implication: ambiguous/negative. The display path (color LUT, scaler, capture) adds too many confounders to be definitive.

---

## What is still true / still ruled out

Still true:
- Bulk QSPI upload of a 320×240 2bpp checkerboard from ESP32-P4 to FPGA SDRAM base `0x100000`.
- After upload, `READ_STATUS sel=8` reads of `0x100008` and `0x101000` return `0x00000000`; all other sampled words return `0x55555555`.
- Transport health is clean: no `fifoOverflow`, no `uploadError`, no `malformed`, no CRC8 status-counter change on the failing frames.
- The failures are **deterministic and workload-independent** (30/30 in both display-off and display-active modes).

Ruled out:
- CRC8/retry layer (already engaged; failing frames do not trigger it).
- Classic SI/timing on readback SCLK (stable zeros at 2 / 1 / 0.5 / 0.25 MHz).
- Host-side framing/address/CRC miscalculation (host buffer has `0x55`; wire addresses and CRC recomputed and match).
- RTL transport/bridge/`sdram.v` write path under faithful refresh (Line-2 faithful pivot: 61 frames, 7680 words, 0 mismatches).
- Simple 1-read pipeline lag in `sel=8` (double-read would have flushed it).

---

## Current fork

1. **SDRAM really contains `0x00`** at those addresses (write-side physical/SDRAM/controller issue that the faithful sim does not reproduce), **or**
2. **`sel=8` debug readback deterministically returns `0x00`** for those addresses via a more persistent CDC/address-decode/data-corruption bug, **or**
3. **Some other systematic readback illusion** we have not considered.

---

## What we are considering next

- **Option A — Physical QSPI/SDRAM bus capture:** observe whether the FPGA drives `0x55` or `0x00` on the response wire during a `sel=8` read. Conclusive, but instrumentation may not be available.
- **Option B — Rule-19-approved temporary diagnostic interface:** add a small, robust host-accessible SDRAM word-read register that bypasses the `sel=8` CDC path entirely. Requires independent BrightForge + BronzeGate approval before implementation (Rule 19 interface checkpoint).

---

## Questions for you

1. **Given that the double-read did not flush the zeros, what is the next most likely mechanism?**
   - Could `sel=8` be reading the *wrong* SDRAM address for those two specific values (e.g., address-handoff corruption, parity/wire-address decode, or an off-by-one in the SDRAM command)?
   - Could the 2-FF `dataSync` synchronizer in `dbgResultPixArea` be corrupting specific bit patterns deterministically?
   - Could the issue be earlier in the chain: the QSPI decoder latching the wrong 32-bit word for those addresses?

2. **Is there a cheaper software/firmware-only discriminator we have missed?**
   - We have used `sel=8`, SCLK sweep, double-read, and display output. Is there another existing register or side effect we can observe without adding a new host interface?

3. **If you had to bet, which fork is correct — SDRAM content `0x00` or readback illusion — and why?**

4. **What would you instrument in a focused RTL sim to decide between the two?** We would prefer not to write a new large testbench, but a small, targeted simulation of the exact upload + `sel=8` read sequence would be acceptable if you can specify the exact signals to probe.

---

## Files that have changed since last review

- `firmware/libvdp/vdp_host_p4.c` — `memcpy` → `memmove` fix (`619f76b8`).
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/INDIRECT_DISPLAY_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/DOUBLE_READ_BUILD.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/INDIRECT_DISPLAY_BUILD.md`

The bundled source files (`firmware_source.txt`, `spinalhdl_source.txt`, `rtl_source.txt`) are still current; only the `vdp_host_p4.c` copy-overlap fix is new.
