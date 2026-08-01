# Double-read diagnostic procedure

1. Keep the approved bitstream active: SHA-256
   `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`.
2. Build and flash proof mode 6 with ESP-IDF v6.0.2, carrying
   `SCALER_PROOF_MODE=6` through reconfigure, build, and flash.
3. Observe `/dev/ttyACM0` at the serial console. The application uploads the
   bitmap and attribute planes through the normal 4 MHz path, then calls the
   complete `readback_word(addr, &value)` routine twice for each address. Each
   call rewrites `REG_SDRAM_READ_ADDR_HI` (0x0327), arms a new read, and polls
   `sel=8`; the second call is the lag-flush result.
4. Repeat the six-address diagnostic eight times. Also run the two pairs
   `(0x100004 -> 0x100008)` and `(0x100FFC -> 0x101000)` as dummy-neighbor
   followed by target reads. Control/read transactions use the existing 2 MHz
   clock and CS post-transaction idle of 8.

This is proof-only firmware. It exercises the existing interface and does not
change the production host API or FPGA contract.
