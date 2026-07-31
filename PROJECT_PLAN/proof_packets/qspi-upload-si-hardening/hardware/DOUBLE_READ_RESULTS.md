# Double-read diagnostic results

Accepted run: mode 6, source commit `3b246fc7`, internal copy fix
`619f76b8`, approved bitstream SHA-256
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`, serial
port `/dev/ttyACM0`.

The upload completed cleanly at 4 MHz. Transport health was
`raw=0x00000000`, `overflow=0`, and `malformed=0` before and after upload.
Across 8 repeats and 6 addresses, all reads completed without an error. The
four addresses expected to contain `0x55555555` were
`0x100008`, `0x10000C`, `0x101000`, and `0x101004`; each returned
`first=0x00000000`, `second=0x00000000` in every repeat. The zero-expected
addresses also returned zero.

Result: `DOUBLE_READ_RESULT pass=0 repeats=8 addresses=6`.

This run does **not** confirm that returning the second `sel=8` read exposes
the expected word. It therefore does not close the Rule 19 interface question
or authorize an RTL change. The result is consistent with the existing
readback anomaly, but does not by itself distinguish the proposed pipeline-lag
hypothesis from another readback/observation issue.
