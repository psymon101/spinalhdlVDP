# Troubleshooting

Create one decision tree per failure class:

- SpinalHDL compile/generation;
- SpinalSim mismatch;
- generated RTL drift;
- Gowin synthesis/timing;
- FPGA programming;
- no/unstable HDMI;
- host initialization;
- register read/write mismatch;
- SDRAM upload;
- CRC/parity/short frame;
- vblank timeout;
- Copper late event;
- line-buffer underrun;
- sprite overflow;
- platform visual mismatch.

Every entry includes symptoms, diagnostic commands, known-good values, likely
causes, safe corrective actions, and evidence required for escalation.
