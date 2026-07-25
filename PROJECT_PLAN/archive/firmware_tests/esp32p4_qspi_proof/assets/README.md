# P4 Proof Asset

`ham6_320x240_codes_words_le.bin` is the verified selfie HAM6 upload fixture
used by `main.c`. It contains 76,800 source bytes packed as 38,400
little-endian 16-bit words for the `SDRAM_WRITE` proof.

- Source geometry: 320x240
- Display geometry: 640x480 after the VDP HAM 2x fetch
- SHA-256: `694432e42f59030d3025dcec1087fb5be487dc839b1dd18317804384e45b3838`
- Palette: median-cut RGB444 palette paired with the selfie asset
- Generator/reference: BrightForge `selfie_ham6_320x240.bin` and preview

The binary is copied into this app directory so a clean checkout can build
without reaching outside `firmware/esp32p4_qspi_proof/`.
