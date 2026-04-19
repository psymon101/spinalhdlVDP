# Task 38b — Bidirectional QSPI: Status Surface Expansion

**Opened:** 2026-04-19  
**Opened by:** BronzeGate #7607  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Purpose

Task 38a proved the electrical bidirectional path. Task 38b expands the `READ_STATUS` response surface so the host can read back diagnostic and operational state beyond the single magic word. This is the data-layer foundation for Task 38c firmware readback and for all later host-driven status polling.

---

## Scope

- **in scope:** `QspiDecoder` `READ_STATUS` response expansion to `sel=0..4`
- **in scope:** `sel=0` → magic `0x51560002` (retained from Task 27)
- **in scope:** `sel=1` → `rx_cmd_cnt`
- **in scope:** `sel=2` → `last_addr`
- **in scope:** `sel=3` → `last_data`
- **in scope:** `sel=4` → `last_error`
- **in scope:** Simulation proof for all `sel=0..4` values
- **in scope:** Hardware proof that high-nibble status bits are electrically visible (IO3 alive confirmation)
- **out of scope:** Firmware read helper / host-side read transactions (Task 38c)
- **out of scope:** IOBUF / CST / top-level electrical changes (Task 38a)
- **out of scope:** Asset upload work (Task 34)
- **out of scope:** Any rendering or compositor changes

---

## Dependencies

- Task 38a — Bidirectional QSPI: HDL IOBUF + CST must be DONE

---

## Interfaces / State

- `QspiDecoder.io.readStatusSel` — 3-bit selector input
- `QspiDecoder.io.readStatusData` — 32-bit response data output
- Internal state: `rx_cmd_cnt`, `last_addr`, `last_data`, `last_error` (already captured in decoder)
- Response FSM: Idle → Load → Wait → Shift (existing, expanded data source only)

---

## Timing / Memory Notes

- No new clock domains
- No memory or bandwidth impact
- Response latency: one `sel` sample per READ_STATUS command, 4-byte response

---

## Risks

- `sel` decode must be glitch-free during response state transitions
- Internal state (`last_addr`, `last_data`) may update during a multi-byte READ_STATUS response; response must snapshot at load time
- `rx_cmd_cnt` width must match response field size
- Accidentally breaking the `sel=0` magic word behavior

---

## Validation

- **sim:** `READ_STATUS` with `sel=0..4` returns expected values for each selector
- **sim:** No regression of `sel=0` magic word (`0x51560002`)
- **sim:** Response snapshot behavior proven (internal state changes mid-response do not corrupt output)
- **hardware:** High-nibble response value (e.g. `0x51`) visible on readback, confirming IO3 path electrical life through expanded status surface

---

## Audit Focus

- Exact `sel` mapping matches plan: 0=magic, 1=cmd_cnt, 2=addr, 3=data, 4=error
- No regression of proven write path or `sel=0` behavior
- No hidden dependency on firmware-side helper work
- Response snapshot behavior is correct under concurrent QSPI traffic

---

## Exit Condition

This task is done when the `READ_STATUS` command returns correct fields for `sel=0..4` in simulation and a high-nibble status response is proven electrically visible on hardware.
