# BronzeGate Step C source proof

Source commit: `a5f2aaa93e89d3afbb4b0adf041eb19582508251`

The firmware lane synchronized the host-facing contract with the approved
cleanup RTL/specification:

- `vdp_host.h` documents QSPI `READ_STATUS` selectors `0x05` (sticky) and
  `0x06` (upload), limits the `0x0323` clear mask to W1C bits 2/3, and keeps
  `vdp_reg_read()` active for i80 parity and its explicit QSPI write-only
  limitation.
- `vdp_status.h` defines the canonical selector constants; `vdp_status.c`
  uses the sticky selector constant rather than a magic literal.
- `vdp_i80.h` matches the canonical bits 2/3 clear mask.
- `mode0_regs.json` states that status reads return the current value and that
  bits 4/5 are RESERVED-0.

No RTL, generated HDL, FPGA integration, or planning-task files were changed.
The pre-existing worktree changes in the 2bpp planning task and external-review
report were left untouched.
