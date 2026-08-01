# Corrected double-read diagnostic results

The corrected proof-only firmware is source commit `2d066b5e` and uses the
existing overlap fix `619f76b8`. The approved bitstream remained active:
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`.
The run used `/dev/ttyACM0`, ESP-IDF v6.0.2, 4 MHz bulk upload, and 2 MHz
control/read transactions.

## Run validity

The first corrected run is **invalid as a write/readback proof** because the
bitmap upload failed at offset 1518 with `VDP_HOST_ERR_TX` (`err=5`). Health
was still `raw=0x00000000`, `overflow=0`, `malformed=0`, but a clean upload is
required before interpreting target contents. Per the first-failure rule, no
second hardware attempt was made.

## Diagnostic context from the invalid run

Despite the upload failure, the full-call sequence executed 8 repeats × 6
addresses. The expected nonzero words still returned `first=0x00000000` and
`second=0x00000000`, so the run ended `DOUBLE_READ_RESULT pass=0`. The
dummy-neighbor pairs `(0x100004, 0x100008)` and `(0x100FFC, 0x101000)` showed
`lag_matches=16/16` and `target_matches=0/16`; this is consistent with the
proposed one-word readback pattern, but cannot prove SDRAM contents after an
unclean upload.

No production firmware, host-interface, or RTL change was made. The corrected
discriminator remains pending a reviewed rerun with a clean upload.
