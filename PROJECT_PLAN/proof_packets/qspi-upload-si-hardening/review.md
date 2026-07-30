# Review

BronzeGate firmware-path finding: PASS — the current P4 bulk path uses the
existing CRC8-185 append + selector-11 counter poll + one retry. No firmware or
RTL change was made.

Stress disposition: FAIL for the stronger acceptance criterion. The clean
30-cycle run is 15/30 pass and 15/30 fail, with two deterministic sample
mismatches per failed cycle and no transport-health flags. This is residual
uncorrected corruption, not evidence that the CRC path is absent.

Focused discriminator: PASS. BronzeGate's proof-mode run re-read 13 addresses
eight times at 2 MHz (104 successful reads) and observed stable zeros at all
six expected-`0x55555555` suspect words. The frame map is exact: `0x100008`
is in bitmap frame 0 and `0x101000` is in bitmap frame 8. Selector `0x0B`
counter deltas were logged around all 122 upload frames; six deltas occurred,
with no host API errors and no transport-health flags.

Disposition: the PM discriminator selects the real SDRAM/write-path branch,
not a varying readback artifact. This is a scope decision, not a root-cause
claim. BrightForge and TopazCliff must agree on the minimal next delta before
any production firmware or RTL edit is authorized.

Proof details: `hardware/DIAGNOSTIC_RESULTS.md`.
