# Source facts

- Source commit: `4f205a08dbc396e2dffa76133fb553947936c487`.
- `firmware/libvdp/vdp_crc8.h` implements CRC-8-CCITT, polynomial `0x07`,
  init `0x00`, MSB-first, no reflection/final XOR.
- `firmware/libvdp/vdp_host_p4.c:115-142` computes the CRC over the wire-order
  command/address/frame, appends it, polls selector `0x0B` before/after the
  transaction, and retries once when the 16-bit CRC status counter changes.
- `vdp_reg_write_burst()` and `vdp_sdram_write()` both call `write_frame()`;
  the scaler proof's 4 MHz bulk path calls `vdp_sdram_write()` for both planes.
- No firmware or RTL source was changed for this fact/stress checkpoint.
