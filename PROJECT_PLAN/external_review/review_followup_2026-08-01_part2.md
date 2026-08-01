# Follow-up #2 for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Your previous reply predicted that calling `readback_word()` twice for the same address would flush the 1-read lag and the **second call would return `0x55555555`** at `0x100008`/`0x101000`. We ran that exact test cleanly. The prediction did **not** hold.

---

## What we ran

Firmware helper (proof-only Mode 6, commit `2d066b5e`, rerun commit `1fa4f2be`):

```c
static bool readback_word_twice(uint32_t addr, uint32_t *first,
                                uint32_t *second)
{
    /* Each call rewrites REG_SDRAM_READ_ADDR_LO/HI, so each arms a new read. */
    if (!readback_word(addr, first)) return false;
    return readback_word(addr, second);
}
```

Test conditions:
- 4 MHz upload, both 30 720-byte planes completed successfully.
- Health before/after: `raw=0x00000000`, `overflow=0`, `malformed=0`.
- 8 repeats × 6 addresses.
- Targets: `0x100004`, `0x100008`, `0x10000C`, `0x100FFC`, `0x101000`, `0x101004`.

## Results

| Address | Expected | First call | Second call |
|---|---|---|---|
| `0x100004` | `0x00000000` | `0x00000000` | `0x00000000` |
| `0x100008` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x10000C` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x100FFC` | `0x00000000` | `0x00000000` | `0x00000000` |
| `0x101000` | `0x55555555` | `0x00000000` | `0x00000000` |
| `0x101004` | `0x55555555` | `0x00000000` | `0x00000000` |

- `pass=0` (no second-call `0x55555555` anywhere).
- Dummy-neighbor lag pairs (`0x100004 → 0x100008`, `0x100FFC → 0x101000`):
  - `lag_matches = 16/16`
  - `target_matches = 0/16`

Full artifacts: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`

---

## Why this matters

The simple 1-read pipeline-lag hypothesis predicted:
- First call returns stale previous value.
- Second call returns freshly fetched value.

If SDRAM contained `0x55` at the targets, the second call should have shown `0x55555555`. It did not. Either:

1. SDRAM **really does contain `0x00`** at those addresses (write-side/physical issue), or
2. The `sel=8` readback path has a **more persistent artifact** than a single-read lag — for example, it reads the wrong address, corrupts specific data patterns, or returns `0x00` deterministically for certain addresses regardless of how many times the read is re-armed.

The `lag_matches=16/16` is interesting: it shows the path is not transparent, but it does not prove the 1-read lag is the only effect.

---

## What is still ruled in / ruled out

Ruled out:
- CRC8/retry layer as the catcher (failing frames do not trigger CRC counter).
- Readback SCLK sensitivity (stable zeros at 2 / 1 / 0.5 / 0.25 MHz).
- Host-side framing/address/CRC miscalculation.
- RTL transport/bridge/`sdram.v` write path under faithful refresh (61 frames, 7680 words, 0 mismatches in Line-2 faithful pivot).
- The simplest form of the 1-read lag (double-read would have flushed it).

Still open:
- SDRAM content really `0x00` at those addresses.
- `sel=8` debug readback returning `0x00` deterministically.

Physical QSPI bus capture is infeasible on the current host.

---

## What we are doing next

We are convening a **Rule 19 checkpoint** to add a small, robust diagnostic readback surface that bypasses `sel=8` entirely:

- Reuse existing arm registers `0x0326`/`0x0327`.
- Add one host-readable `READ_DONE` status bit.
- Harden the CDC by latching the result only after it is settled.
- Host polls `READ_DONE`, then reads the 32-bit word.

This is a host-visible change, so it requires independent approval from both the RTL owner (BrightForge) and the firmware owner (BronzeGate) before implementation.

---

## Questions for you

1. **Given that the second call also returned `0x00`, what is the next most likely mechanism?**
   - Could the `sel=8` path be reading the **wrong SDRAM address** for those specific values (e.g., address-handoff corruption, or the HI address register not taking effect)?
   - Could there be a **pattern-sensitive data corruption** in the result CDC (e.g., multi-bit synchronizer failing on `0x55` but passing `0x00`)?
   - Could the SDRAM controller itself return `0x00` for those specific addresses due to a subtle DQM/mask or refresh-row interaction not reproduced in the faithful sim?

2. **Is there any other software-only discriminator we have missed?**
   - We have now used: `sel=8`, SCLK sweep, double-read, display output.
   - Is there a way to use the existing `0x0328`/`0x0329` i80 readback registers, or a side effect of the upload command, to read back without `sel=8`?

3. **When the Rule 19 diagnostic interface runs, what result would make you believe each fork?**
   - If the new interface returns `0x55555555` at `0x100008`, that proves SDRAM is good and `sel=8` is broken.
   - If it returns `0x00000000`, does that definitively prove SDRAM content is `0x00`, or could the new interface also be affected by the same underlying issue?

4. **Is there a specific RTL signal or CDC corner you would probe in a focused sim to decide between the two forks?** We can ask BrightForge to target a small simulation, but we need to know exactly what to instrument.

---

## Files updated since last follow-up

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DOUBLE_READ_RESULTS.md`
- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/firmware/DOUBLE_READ_BUILD.md`
- `PROJECT_PLAN/STATUS.md`
- `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`
