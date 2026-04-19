# Task 38c — Bidirectional QSPI: Firmware Read Helper + Bit-3 Proof

**Opened:** 2026-04-19  
**Opened by:** CoralReef (auto-progression per BronzeGate fast-lane policy #7620)  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Purpose

Task 38a proved the electrical bidirectional path. Task 38b expanded the status surface. Task 38c closes the host-side readback loop by providing a firmware helper that can issue READ_STATUS commands and receive the response over QSPI. This is the final step that makes QSPI bidirectional readback operationally real from the host perspective, and it provides the first direct hardware proof that IO3 carries bit-3 data electrically.

---

## Scope

- **in scope:** Pico firmware QSPI read helper
- **in scope:** PIO RX program (or bit-bang equivalent) for QSPI read transactions
- **in scope:** Firmware reads magic `0x51560002` via READ_STATUS sel=0
- **in scope:** Firmware reads status registers (sel=1..4) — `rx_cmd_cnt`, `last_addr`, `last_data`, `last_error`
- **in scope:** `pio_wait_sm_idle()` drain helper replacing ad-hoc `sleep_us(10)` margin
- **in scope:** Bit-3 hardware observability: use a status value with high-nibble bit 3 set to confirm IO3 path is electrically alive
- **out of scope:** HDL changes (Tasks 38a/38b cover all HDL)
- **out of scope:** CST changes
- **out of scope:** Asset upload protocol (Task 34)
- **out of scope:** Host driver library abstraction (Task 39)

---

## Dependencies

- Task 38b — Bidirectional QSPI: Status Surface Expansion must be DONE

---

## Interfaces / State

- Pico 2 QSPI PIO state machine (RX program)
- `qspi_bus.c` reference implementation from `/home/itadmin/github/VDP/src/mode0/firmware/src/qspi_bus.c`
- Host-visible: serial/UART output of READ_STATUS response bytes

---

## Timing / Memory Notes

- No HDL timing concerns
- PIO RX program timing must align with FPGA QSPI slave SCK rate (2 MHz)
- Turnaround cycle: host deasserts CS, FPGA sees CS rise, then host reasserts CS for read command

---

## Risks

- PIO RX program bit mapping must match FPGA `spi_io_out` nibble order (MSB-first, high-nibble first per QSPI protocol)
- SCK rate may need adjustment for reliable readback (currently 2 MHz)
- `pio_wait_sm_idle()` must correctly detect SM idle without false positives
- Bit-3 observability requires a status response byte with bit 3 set (e.g., `0x51` in magic, or `last_addr`/`last_data` with high nibble ≥ 8)
- CS turnaround timing: FPGA must release OE before host starts driving SCK for read command

---

## Validation

- **hardware:** Pico issues READ_STATUS sel=0 and receives magic `0x51560002` correctly over QSPI
- **hardware:** Pico issues READ_STATUS sel=1..4 and receives correct status register values
- **hardware:** High-nibble bits in response are not silently zeroed — bit-3 alive proof (e.g., `0x51` byte or other high-nibble value with bit 3 set)
- **hardware:** `pio_wait_sm_idle()` helper works correctly in place of `sleep_us(10)`

---

## Audit Focus

- Firmware read helper correctly assembles READ_STATUS command (opcode + addr + len + sel)
- RX path captures all 4 response bytes correctly
- Bit-3 observability is unambiguous (not inferred from lower bits)
- No regression of write-path behavior (smoke test still passes)
- `pio_wait_sm_idle()` is robust under varying PIO FIFO depths

---

## Exit Condition

This task is done when Pico firmware successfully reads back the magic word and status registers over QSPI, and a response byte with bit 3 set is proven electrically visible on hardware.
