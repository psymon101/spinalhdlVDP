# P4 Proof Asset

`ham6_320x240_codes_words_le.bin` is the deterministic HAM6 upload fixture
used by `main.c`. It contains 76,800 source bytes packed as 38,400
little-endian 16-bit words for the `SDRAM_WRITE` proof.

- Source geometry: 320x240
- Display geometry: 640x480 after the VDP HAM 2x fetch
- SHA-256: `486adfed0a94b9f20ed2fc7a7b876f2085ba02a292322d6e5c61e3fbadefcf9e`
- Generator/reference: `firmware/assets/ham_decoder_171/`

The binary is copied into this app directory so a clean checkout can build
without reaching outside `firmware/esp32p4_qspi_proof/`.
