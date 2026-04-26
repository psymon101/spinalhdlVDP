# Task 35 — Host-Facing IRQ and Status Registers

**Status:** DONE — Host IRQ status registers implemented and integrated

**Opened:** 2026-04-19  
**Opened by:** CoralReef (auto-progression per BronzeGate fast-lane policy #7620 + CyanPeak #7637 directive)  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Purpose

Task 35 makes the R1 Raster Trigger Unit and the QSPI status surface actually usable by the host. R1 already has beam comparators that can match raster position, but the host cannot see the match or receive an interrupt. Task 35 adds the host-visible IRQ line and sticky status registers that close this gap. This is the control-surface completion step that makes Mode0 primitives observable and interruptible.

---

## Scope

- **in scope:** Host-visible IRQ line output from `VdpTop`
- **in scope:** Sticky status registers (sprite overflow, raster match, QSPI ready, etc.)
- **in scope:** Clear-on-read or write-to-clear semantics for status registers
- **in scope:** Status readable under maximum fetch load (not just idle)
- **in scope:** QSPI readback path integration: status registers readable via READ_STATUS command
- **out of scope:** New automation engines (Copper/HDMA is Task 33)
- **out of scope:** Register bus master refactor (Task 32b)
- **out of scope:** New raster trigger comparators (R1 already delivers these)
- **out of scope:** Compositor or fetch engine changes

---

## Dependencies

- Task 18 — Per-Line Raster Control must be DONE (R1 trigger unit exists)
- Task 32a — Mode0 Register Bus: Spec & Naming Lock must be DONE

---

## Interfaces / State

- `VdpTop.io.irq` — output Bool, asserted when any enabled status bit is set
- Status register bank — sticky bits for:
  - `RASTER_MATCH` — raster comparator matched programmed position
  - `SPRITE_OVERFLOW` — sprite per-line limit exceeded
  - `QSPI_READY` — QSPI slave idle / command processed
  - `QSPI_ERROR` — QSPI protocol error detected
- Clear semantics: read-side auto-clear or write-to-clear per bit
- Register bus integration: status registers mapped into 32a register bus address space

---

## Timing / Memory Notes

- IRQ is combinatorial OR of enabled sticky bits, output directly from `VdpTop`
- Status bit set is synchronous to pixel clock; safe-boundary rules apply
- Status read via QSPI READ_STATUS must be stable under concurrent SDRAM fetch

---

## Risks

- IRQ deassertion timing: host must see at least one clock of assertion; glitch suppression needed
- Status register bit width vs QSPI response width: 32-bit status word must pack cleanly into READ_STATUS 4-byte response
- Concurrent read-modify-clear: host clears status while new event wants to set it — atomicity required
- Status read under load: QSPI read must not glitch when FPGA is under maximum SDRAM + sprite load

---

## Validation

- **sim:** Raster trigger asserts IRQ at programmed line; host readback sees correct status bits
- **sim:** Status read is stable under concurrent SDRAM fetch + sprite evaluation load
- **sim:** Clear-on-read correctly resets sticky bits without losing new events
- **hardware:** Pico reads status register over QSPI and prints expected values
- **hardware:** IRQ line observable (e.g., via GPIO toggle or scope) when raster match fires

---

## Audit Focus

- Status register mapping is coherent with register bus spec (32a)
- IRQ assertion is unambiguous and glitch-free
- Clear semantics work correctly under host-FPGA race conditions
- No regression of write path or readback behavior
- Status readable under maximum fetch load

---

## Exit Condition

This task is done when the host can reliably read status registers via QSPI, receive an IRQ on raster match, and clear status without losing events, with both simulation and hardware evidence.
