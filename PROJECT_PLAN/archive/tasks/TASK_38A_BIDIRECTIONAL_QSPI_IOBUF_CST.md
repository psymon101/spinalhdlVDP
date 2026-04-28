# Task 38a — Bidirectional QSPI: HDL IOBUF + CST

**Status:** DONE (`f49880f`) — Bidirectional QSPI IOBUF/CST implemented and hardware-proven

**Opened:** 2026-04-18  
**Opened by:** BronzeGate #7591  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Purpose

Task 27 proved the QSPI write path with 4-bit payload fidelity. Task 38a completes the first step of the bidirectional readback path by wiring the `QspiSlave` response drive capability through the Tang Nano 20K top-level using the correct bidirectional primitive. This is the electrical foundation for all later QSPI readback work.

---

## Scope

- **in scope:** Top-level bidirectional QSPI wiring in `TopTang20kHdmi`
- **in scope:** Gowin `IOBUF` primitive integration on IO0/IO1/IO2/IO3
- **in scope:** CST update: pins 48/49/51/54 become bidirectional (pull-modes revisited)
- **in scope:** `QspiSlave.spi_io_out` + `spi_io_oe` connected to physical IO lines
- **in scope:** Simulator proof that write-path behavior is preserved
- **in scope:** Hardware sanity proof that write path still works with bidirectional top-level
- **out of scope:** Status-surface expansion (Task 38b)
- **out of scope:** Firmware read helper / host-side read transactions (Task 38c)
- **out of scope:** Full readback protocol proof
- **out of scope:** Asset upload work (Task 34)
- **out of scope:** Any rendering or compositor changes

---

## Dependencies

- Task 27 — Full-QSPI Hardening (IO2/IO3) must be DONE

---

## Interfaces / State

- `QspiSlave.io.spi_io_out` — 4-bit output data from slave
- `QspiSlave.io.spi_io_oe` — 4-bit output-enable from slave
- `TopTang20kHdmi` top-level bidirectional wiring via Gowin `IOBUF`
- Physical pins: 48 (IO0), 49 (IO1), 51 (IO2), 54 (IO3)

---

## Timing / Memory Notes

- No new clock domains
- No memory or bandwidth impact
- Timing concern: `IOBUF` tristate turnaround must not conflict with SCK edges in Respond state

---

## Risks

- Gowin `IOBUF` primitive usage differs from vendor to vendor — must match proven VDP project pattern
- Tristate turnaround timing: releasing OE too late or too early can cause bus contention
- CST pull-mode changes (UP/DOWN/NONE) must be consistent with electrical idle state
- Accidentally breaking the proven write path during top-level rewire

---

## Validation

- **sim:** Prove bidirectional top-level wiring does not break current QSPI write behavior (regression test against Task 27 sim cases)
- **sim:** Prove slave can electrically drive response path through intended top-level connection model
- **hardware:** Bounded sanity proof that Task 27 write-path baseline (`LAYER_ENABLE` toggle) still behaves correctly with bidirectional top-level in place
- **hardware (if practical):** One direct physical indication that response drive path is no longer top-level-dead (e.g., LED probe, scope capture, or READ_STATUS bit observable)

---

## Audit Focus

- Correct top-level bidirectional implementation using vendor-appropriate primitive
- No regression of already-proven write path
- CST / electrical settings coherent with intended IO direction behavior
- No hidden dependency on later 38b/38c work to make 38a itself valid

---

## Exit Condition

This task is done when the Tang Nano 20K top-level supports bidirectional QSPI IO with proven electrical integrity, the write path remains regression-free, and both simulation and hardware evidence are definitive.
