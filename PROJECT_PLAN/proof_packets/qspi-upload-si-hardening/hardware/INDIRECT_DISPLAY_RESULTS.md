# Display-indirect discriminator results

Accepted run: mode 7, source commit `3b246fc7`, internal copy fix
`619f76b8`, approved bitstream SHA-256
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`, serial
port `/dev/ttyACM0`.

The application reported the intended targets and pattern:
`targets=0x100008,0x101000 color=palette2 byte_pattern=0xAA neighbors=word+-1`.
The normal 4 MHz bitmap and attribute uploads completed with health
`raw=0x00000000`, `overflow=0`, and `malformed=0` before upload, after upload,
and after enable. Serial readiness was `INDIRECT_DISPLAY_READY pass=1`.

Three consecutive 720x480 YUYV captures were byte-identical. The accepted
frame is in `captures/indirect_display_frame_01.png` with SHA-256
`a2a67849bdeb2f6ef4bbaba841797948181d22dd19b41f5d7443f73eb0a3e49b`.
The capture remained grayscale with the known cyan canary; no distinctive
palette-2 red/magenta target block was observed. Because the physical capture
does not provide a clean positive color discriminator, this result is
ambiguous/negative for display-indirect confirmation and must not be treated
as proof of SDRAM contents. A physical bus capture or PM disposition is still
required.
