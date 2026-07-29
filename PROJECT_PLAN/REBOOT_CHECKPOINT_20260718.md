# Reboot Checkpoint — BrightForge Session 2026-07-18

**Agent:** BrightForge  
**Lane:** QSPI-SI-CEILING-183 bitstream  
**Status at save:** BLOCKED on bitstream location; RTL eval delivered.

## Current task state

- Fetch-after-upload discriminator ran (#14129). Result: upload-vs-display-fetch contention confirmed, but shimmer not eliminated.
- Correction issued (#14130): 720×480 native capture still shows localized shimmer at color-transition rows and ±1–2 px vertical shifts.
- PM authorized vertical-flip RGB565 discriminator (#14146).
- **Blocker:** cannot locate requested baseline bitstream `0504d89e` (#14147). Searched all `.fs` files in `spinalhdlVDP`, `ham-build-171`, `.worktrees`. None match.
- RTL eval #14143 delivered: `PROJECT_PLAN/QSPI_CLK_DOMAIN_EVAL.md` / mail #14148. Verdict: `SPI_CLK`-as-clock + async FIFO would not fix the proven SDRAM arbitration failure.

## Files to preserve

- `PROJECT_PLAN/STATUS.md` — updated lane status.
- `PROJECT_PLAN/QSPI_CLK_DOMAIN_EVAL.md` — RTL assessment.
- `PROJECT_PLAN/REBOOT_CHECKPOINT_20260718.md` — this file.
- `fpga/tang20k/captures/fetch_after_upload_decider_20260718/` — captures + metrics + correction.

## Next actions after reboot

1. Wait for reply to #14147 with bitstream `0504d89e` path/build instructions.
2. Flash bitstream + vertical-flip firmware `04cf95c6…` (#14141).
3. Capture native 640×480 YUYV and compute vertical-shift std.
4. Compare flipped vs non-flipped peak row positions; report S7/S5 vs S4/S9 verdict.

## Hardware state

- FPGA: currently loaded with `2ec0b347…` (ham-build-171 Option A word-drain) after last reset.
- P4: last flashed with `firmware/esp32p4_rainbow_test/build/esp32p4_rainbow_test.bin` (vertical-flip build, SHA-256 `04cf95c6…`).
- Capture device: `/dev/video0` Guermok USB2 Video, supports 640×480 YUYV @ 60 fps.
- P4 serial: `/dev/ttyACM0`.
- Tang Nano 20K JTAG/UART: `/dev/ttyUSB0`/`/dev/ttyUSB1` (FT2232).

## Important notes

- Do NOT use 720×480 references from prior runs; PM deprecated them.
- Do NOT trust the premature #14129 closeout; use #14130 correction instead.
- 8-bpp indexed mode is not supported by RTL; do not attempt.
- esptool is shadowed by `/home/itadmin/.local/bin/esptool.py`; use `PYTHONPATH=/home/itadmin/.local/lib/python3.12/site-packages python3 -m esptool`.

— BrightForge
