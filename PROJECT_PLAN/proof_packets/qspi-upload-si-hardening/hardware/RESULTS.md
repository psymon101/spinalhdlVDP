# Stress results

The clean-baseline run completed all 30 cycles. Cycles 01–15 passed; cycles
16–30 failed. Every failed cycle had two readback mismatches. All health samples
were zero (`raw=0x00000000 overflow=0 malformed=0`).

```text
cycle 01-15: PASS (15 cycles)
cycle 16-30: FAIL (15 cycles; 2 readback mismatches each)
```

Representative raw serial excerpt from a failing cycle:

```text
I (300) p4_scaler_proof: HEALTH_BEFORE_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (340) p4_scaler_proof: bitmap uploaded bytes=30720 clock=4000000
I (370) p4_scaler_proof: attr uploaded bytes=30720 clock=4000000
I (370) p4_scaler_proof: HEALTH_AFTER_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (370) p4_scaler_proof: READBACK PASS addr=0x100000 value=0x00000000
E (380) p4_scaler_proof: READBACK FAIL addr=0x100008 expected=0x55555555 got=0x00000000
I (390) p4_scaler_proof: READBACK PASS addr=0x100010 value=0x00000000
E (390) p4_scaler_proof: READBACK FAIL addr=0x101000 expected=0x55555555 got=0x00000000
I (400) p4_scaler_proof: READBACK PASS addr=0x106400 value=0x00000000
I (410) p4_scaler_proof: READBACK PASS addr=0x106480 value=0x00000000
I (420) p4_scaler_proof: HEALTH_AFTER_ENABLE raw=0x00000000 overflow=0 malformed=0
I (420) p4_scaler_proof: SCALER_PROOF mode=0 pass=0
```

Interpretation: the existing CRC append/status polling/retry path is engaged,
but it did not prevent or report these residual content mismatches. The lane
must remain open for BrightForge/TopazCliff to scope the next delta.

## Focused discriminator (2026-07-30)

Proof mode 4 completed the PM-assigned discriminator on the same approved
bitstream. It uploaded bitmap and attribute planes in 61 frames each, with
253 words (506 bytes) per full frame, and logged selector-`0x0B` counter values
around every frame. Six frame counter deltas were observed; all API writes
returned success and transport health remained zero.

The 13 requested neighbor addresses were read eight times each at 2 MHz. All
104 reads succeeded and were identical across repetitions. The expected
`0x55555555` words at `0x100008`, `0x10000C`, `0x100018`, `0x10001C`,
`0x101000`, and `0x101004` all returned `0x00000000`; expected-zero neighbors
also remained zero. This is the PM-defined stable-zero result and selects the
real SDRAM/write-path branch over a varying readback artifact.

Detailed frame mapping, CRC transitions, exact samples, and geometry are in
`DIAGNOSTIC_RESULTS.md`. No production firmware or RTL fix was made.
