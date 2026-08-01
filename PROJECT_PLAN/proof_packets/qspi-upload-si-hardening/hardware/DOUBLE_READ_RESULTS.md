# Corrected double-read diagnostic results

The corrected proof-only firmware is source commit `2d066b5e` and uses the
existing overlap fix `619f76b8`. The approved bitstream remained active:
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`.
The accepted rerun used `/dev/ttyACM0`, ESP-IDF v6.0.2, 4 MHz bulk upload,
and 2 MHz control/read transactions. Rerun serial transcript SHA-256:
`0fa2965cbc2a220293ba2e63ae31bbc1d1472d9eee1a1b70d7532b9a57e35954`.

## TX-failure debug

The first corrected run failed at bitmap upload offset 1518 with
`VDP_HOST_ERR_TX` (`err=5`). The proof-only Mode 6 changes are after the
upload path; comparison against the prior clean `3b246fc7` run showed no
production upload-path delta. A controlled reflash/reconfigure/build/rerun
was therefore used to test whether the failure reproduced. It did not.

## Accepted clean rerun

Both bitmap and attribute uploads completed at 4 MHz. Health before and after
upload was `raw=0x00000000`, `overflow=0`, `malformed=0`. Across 8 repeats × 6
addresses, all reads completed without errors. For every expected
`0x55555555` word (`0x100008`, `0x10000C`, `0x101000`, `0x101004`), both the
first and second full `readback_word()` calls returned `0x00000000`. No second
call returned `0x55555555`.

Result: `DOUBLE_READ_RESULT pass=0 repeats=8 addresses=6`.

The dummy-neighbor pairs `(0x100004, 0x100008)` and
`(0x100FFC, 0x101000)` produced `lag_matches=16/16` and
`target_matches=0/16`. This is consistent with a persistent one-word
readback pattern, but the corrected full-call double-read did not confirm the
reviewer's expected `0x55555555` result. The write-side vs readback-side fork
therefore remains unresolved.

No production firmware, host-interface, or RTL change was made. The next
decision is the Rule 19 checkpoint on BrightForge's option-4 completion-poll
readback surface, unless PM requests another bounded firmware-only test.
