# Lane 1 prime reproof review

## Verdict

PASS for the PM-authorized Lane 1 hardware reproof. The preserved
`a5a047a2` authority bitstream and firmware commit `9babcbee` produced 10/10
clean fresh-reconfiguration cycles.

## Evidence reviewed

- `hardware/LANE1_PRIME_CAMPAIGN_RESULT.md` and all ten serial/loader logs.
- All ten raw YUYV captures; each is 2,073,600 bytes.
- `firmware/LANE1_PRIME_BUILD.md` and artifact hashes.
- `hashes.sha256`, including the authority bitstream and per-cycle artifacts.
- PM authorization #14639 and diagnostic interpretation #14635.

## Acceptance checks

- CS#-high GPIO20 pre-flight and 1200 ms settle: PASS on 10/10.
- Discard prime followed by magic `0x51560002`: PASS on 10/10.
- Health before upload, after upload, and after enable all zero: PASS on 10/10.
- Six readback passes and mode-0 proof pass: PASS on 10/10.
- 720x480 YUYV capture size: PASS on 10/10.
- Authority bitstream unchanged: PASS; SHA-256 matches `a5a047a2...327658c`.

## Scope note

This closes the firmware proof gate. It does not claim that the underlying
config-boundary reset-release race is permanently fixed in RTL; that remains a
separate, Rule-19-gated hardening lane as directed by TopazCliff.

— BronzeGate
