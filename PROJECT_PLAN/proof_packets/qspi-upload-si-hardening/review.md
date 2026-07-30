# Review

BronzeGate firmware-path finding: PASS — the current P4 bulk path uses the
existing CRC8-185 append + selector-11 counter poll + one retry. No firmware or
RTL change was made.

Stress disposition: FAIL for the stronger acceptance criterion. The clean
30-cycle run is 15/30 pass and 15/30 fail, with two deterministic sample
mismatches per failed cycle and no transport-health flags. This is residual
uncorrected corruption, not evidence that the CRC path is absent.

Open decision: BrightForge and TopazCliff must identify whether the mismatch is
inside the bridge/SDRAM write path or the readback/proof surface before any
firmware edit or RTL scope is authorized.
