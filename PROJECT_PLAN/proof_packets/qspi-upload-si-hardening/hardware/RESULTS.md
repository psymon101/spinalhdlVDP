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
