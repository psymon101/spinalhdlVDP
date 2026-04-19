# Firmware GOTCHAS

Proven pitfalls captured during the Task 26/27/34/35/36/38/39 hardening
cycles. Each item has cost non-trivial debug time at least once. Read
this list before hand-rolling a custom PIO transaction or a second
simulation seed.

---

## 1. PIO pin function restore after bit-bang READ_STATUS

**Symptom:** the first QSPI TX after a `vdp_read_status()` call drives
nothing — the Tang decoder reports no new command, counters don't
advance, and the PIO state machine looks stalled.

**Cause:** `vdp_read_status()` switches IO0..IO3 from PIO function to
SIO for the bit-bang turnaround + nibble reads. It must restore both
the pin function AND the `pio_sm_set_consecutive_pindirs()` direction
mask before the next TX.

**Fix (in `vdp_qspi.c:vdp_read_status()`):** after the read completes,
re-call `pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_IOn)` for each IO
line, then set the consecutive pindirs. See Task 38c commits for the
exact sequence.

**Rule of thumb:** any function that flips pin function away from PIO
is responsible for restoring it fully before return.

---

## 2. SpinalHDL literal-cache bug (sim infrastructure)

**Symptom:** the first `Config.sim.compile(VdpTop())` call in an sbt
invocation succeeds. A second compile of `VdpTop()` in the same JVM
errors with:

```
Null value encountered in {??? : Bits[8 bits]}
  at spinal.core.Mem.walk$1(Mem.scala:253)
  at ... VdpTop.<init> line 543 (affineTexture.init)
```

**Cause:** `AffineAssets.textureInit` is a static `Seq[Bits]` built at
object init time. SpinalHDL internally caches literal nodes; the
second elaborate re-uses the same cached nodes but hits dangling
references from the previous elaboration context.

**Mitigation:** run one compile per sbt/JVM invocation. If a multi-
seed sweep is needed, launch sbt per seed from a shell script, or
restructure `textureInit` as a `def` that rebuilds the `B(...)`
literals each call.

**Where this bit us:** `RegBusStressSim` (Task 36 CP-B) originally ran
two seeds in one main and hit this on the second compile. The final
version runs one seed with documentation noting how to run a second.

---

## 3. CS hold time after QSPI frame

**Symptom:** intermittent `last_error` nonzero, framing-mismatched
register writes, and sometimes a whole REG_WRITE dropped on the floor.
Seen during Task 26 early debug with fast back-to-back writes.

**Cause:** CS_N was being deasserted before the Tang decoder finished
latching the last byte of the header or payload.

**Fix:** `vdp_reg_write()` calls `sleep_us(10)` after CS_N deassert.
Do not shorten this without re-validating on hardware. Measured
minimum margin at 2 MHz SCK is around 3–4 µs; 10 µs is a conservative
3× margin.

---

## 4. PIO OSR drain margin (`vdp_pio_wait_sm_idle()`)

**Symptom:** the last nibble of a PIO TX is truncated / not driven
onto the IO lines, so the Tang decoder sees a short header and either
retries or errors.

**Cause:** `pio_sm_is_tx_fifo_empty()` returns true as soon as the
last word is consumed by the OSR, but the OSR itself still needs time
to shift the bits out onto the pins.

**Fix:** `vdp_pio_wait_sm_idle()` spins on FIFO-empty, then sleeps an
additional 20 µs. At 2 MHz SCK with 10 clocks per nibble, the final
nibble takes ~5 µs to shift out of the OSR, so 20 µs is a 4× margin.
Exposed as a public API in `vdp_qspi.h` (Task 42); all custom PIO TX
paths MUST call it before CS deassertion or pin-function switch.

---

## 5. Pico USB-CDC printf shares Bus 002 with the HDMI capture card

**Symptom:** OpenCV-side capture pipeline sees ~30 ms late frames at a
rate of ~1–2 per 30 s window when the Pico firmware is running even a
light printf cadence, during otherwise-clean QSPI stress.

**Cause:** on this workstation, the Pico Debug Probe (`2e8a:000c`,
USB CDC) and the UltraSemi HDMI capture card both sit on Bus 002. USB
CDC output from the Pico contends with the capture card for the same
USB host scheduler; each printf burst can cause the capture side to
miss a frame.

**Fix:** in tight stress loops, suppress all printf after a single
boot banner. Flush the CDC buffer with one `sleep_ms(50)` before going
silent. A silent stress firmware is statistically indistinguishable
from a Pico-halted baseline (stddev ~0.4 ms in both cases, 1 late
frame per 30 s in both cases). Task 36 CP-C refinement commit
`864f7d4` captures this fix.
