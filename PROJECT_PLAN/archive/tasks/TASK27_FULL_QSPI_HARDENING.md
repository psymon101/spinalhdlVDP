# Task 27 — Full-QSPI Hardening (IO2/IO3)

## Lane Open: Full-QSPI Hardening

### Background

Task 26 proved the QSPI write-path works end-to-end on a **2-wire degraded path** (IO0 and IO1 only). The proven VDP project used all four QSPI data lines (IO0..IO3). spinalhdlVDP's CST currently ties IO2 and IO3 to `0` internally, which corrupts any payload nibble with bits 2 or 3 set (e.g. `0x05` → `0x01`). This lane restores full 4-bit QSPI fidelity.

### Scope Boundary

**In scope:**
- Add IO2 (Tang pin 51) and IO3 (Tang pin 54) to `tang20k_hdmi.cst`
- Update `TopTang20kHdmi` to connect all 4 QSPI data lines (remove `B"00" ## I_qspi_io1 ## I_qspi_io0` tie-off)
- Verify no pin conflicts with existing HDMI/SDRAM/LED assignments
- Simulation proof that 4-bit payload bytes (nibbles with bits 2/3 set) assemble correctly
- Hardware proof: QSPI-driven register write with payload value exercising all 4 bits visibly toggles

**Out of scope:**
- Protocol redesign (keep existing REG_WRITE / READ_STATUS contract)
- Bulk asset streaming
- Readback / bidirectional IOBUF work (still deferred unless explicitly added later)
- Unrelated rendering changes

### Required Proof

**Simulation:**
- `QspiRegWriteSim` or equivalent passes with payload bytes containing set bits in positions 2/3
- Example payload: `0x0005`, `0x000F`, `0x00C0`

**Hardware:**
- Tang Nano 20K bitstream with 4-wire QSPI
- Pico smoke firmware toggles a register with values that exercise bits 2/3
- Visible HDMI toggle confirmed by capture analysis (bimodal gap ≥ 5.0)

### Audit Focus

- CST pin LOC correctness (pins 51/54 vs proven VDP project)
- No electrical conflicts with existing assignments
- SpinalHDL top-level wiring is structurally correct (no accidental tie-offs)
- Simulation uses full 4-bit `spi_io_in` stimulus

### Checkpoints

- **A — Control contract:** CST + HDL wiring clean, Verilog generates, P&R passes
- **B — Simulation proof:** 4-bit payload bytes assemble and decode correctly
- **C — Hardware proof:** Full-fidelity QSPI register write visible on HDMI

### Expected Next Deliverable

- Checkpoint A by BrightForge once coding authorized

### Coding Authorized

**NO** — wait for CyanPeak audit of this artifact doc.

---

## Pin Mapping Reference

| Signal | Tang Pin | Pico GPIO | Proven VDP CST |
|--------|----------|-----------|----------------|
| SCK    | 41       | GP8       | qspi_sck       |
| CS_N   | 42       | GP9       | qspi_cs_n      |
| IO0    | 48       | GP10      | qspi_io[0]     |
| IO1    | 49       | GP11      | qspi_io[1]     |
| IO2    | **51**   | GP12      | qspi_io[2]     |
| IO3    | **54**   | GP13      | qspi_io[3]     |

Pins 51 and 54 were proven working in the parent VDP project. They are currently unused in spinalhdlVDP.

---

## Known Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Pin 51 or 54 conflict with uncommitted HDL | Low | Search all SpinalHDL sources for pin usage before adding LOC |
| Physical jumper wiring not available | Medium | User must connect Pico GP12→Tang pin 51 and GP13→Tang pin 54 |
| Gowin P&R fails with 4 new inputs | Low | Proven VDP project used same pins successfully |

---

## Dependencies

- Task 26 DONE (proven 2-wire QSPI write path)
