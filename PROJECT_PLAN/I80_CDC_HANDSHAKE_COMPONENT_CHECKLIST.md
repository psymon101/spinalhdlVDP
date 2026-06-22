# I80-CDC-FIX-175 CDC Handshake — Component-Level Checklist

**Date:** 2026-06-21  
**Status:** **ABANDONED / REVERTED** (#13256). The CDC fix was itself a regression. The visible display scramble is a downstream bulk-write/scanout issue tracked in lane **`I80-DIRECTCOLOR-SCRAMBLE-176`**. This checklist is preserved as a post-mortem reference for the CDC-fix design.

**Purpose:** Decompose the `f152b333` CDC handshake into the smallest independently verifiable components, assign each check to the agent whose role owns it, and record results before any fix is attempted.  
**Rule:** no component is "assumed OK" without evidence.

---

## Handshake architecture (from `I80HostInterface.scala` `3fbeec4`)

```
Host pads
  │
  ├── io.wr  ──► wrClockDomain ──► wrCapture.data   (RegNext(io.dIn))
  │                                wrCapture.dc     (RegNext(io.dc))
  │                                wrCapture.toggle (toggles each WR# rising)
  │
  └── io.cs/io.rd/io.dc ──► BufferCC ──► csS / rdS / dcS (pixel domain)

Pixel domain
  dInS      = BufferCC(wrCapture.data)
  dcAtWr    = BufferCC(wrCapture.dc)
  dcS       = BufferCC(io.dc)            // continuous DC for read path
  capToggle = BufferCC(wrCapture.toggle)
  wrRise    = capToggle =/= RegNext(capToggle)
```

Every numbered item below maps to one primitive component of this path.

---

## Component checks

### 1. Host-side WR# timing and signal integrity
**What:** verify the ESP32-S3 actually produces a clean WR# strobe with D stable around the rising edge, at the requested speeds.  
**Owner:** BronzeGate (firmware / bench).  
**Method:**
- Report requested vs achieved half-cycle count for each speed (already in stress output).
- Confirm `vdp_host_set_speed_hz` does not change CS/DC/data timing relative to WR#.
- If a scope/logic analyzer is available, capture one CS-low / WR pulse at 2 MHz and 15 MHz; otherwise state unavailable.
**Pass:** achieved speed matches request, no reported host-side timing changes between baseline and fix runs.  
**Result:** **PASS on baseline / regression on fix** — baseline clean to 12 MHz with isolated 15 MHz clamp failures; fix fails hard at all speeds (#13253 / #13255).

### 2. FPGA pin-level input buffer / tri-state wiring
**What:** confirm `io.wr` reaches the FPGA fabric cleanly and `io.dIn` is wired to the correct pins in the Tang Nano 20K CST.  
**Owner:** BrightForge (RTL / constraints).  
**Method:**
- Verify `tang20k_i80.cst` maps `I_i80_wr` to a global-clock-capable pin and `I_i80_dIn[7:0]` to the expected D0-D7 pins.
- Check that no other build flag or renamed signal has shifted the data pin mapping between `65502b18` and `f152b333`.
**Pass:** pinout identical to the baseline that passed 7/7 loopback smoke.  
**Result:** **Not root cause** — baseline loopback clean on same pinout; no mapping change between `65502b18` and `f152b333`.

### 3. WR# clock domain creation
**What:** confirm `ClockDomain(clock = io.wr, config = ClockDomainConfig(clockEdge = RISING, resetKind = BOOT))` is valid for an active-low strobe used as a clock.  
**Owner:** BrightForge (RTL) / CyanPeak (code-to-spec).  
**Method:**
- Verify the synthesized netlist treats `io.wr` as a clock and routes it on a global clock net (already reported by synthesis as PRIMARY global clock for `f152b333`).
- Confirm `BOOT` reset is acceptable: the domain has no dedicated reset, so initial values must be valid.
- Check whether `clockEdge = RISING` is correct for an active-low WR# signal (data is latched when WR# returns high).
**Pass:** synthesis reports WR# on global clock, no reset issues, edge convention matches i80 contract.  
**Result:** **PASS** — CyanPeak #13251: RISING edge / BOOT reset correct for active-low WR#.

### 4. WR#-edge data latch (`wrCapture.data`)
**What:** confirm `RegNext(io.dIn)` in the WR# domain captures a coherent byte on the WR# rising edge without per-bit metastability.  
**Owner:** BrightForge (RTL).  
**Method:**
- Inspect synthesis/STA report for `wrCapture.data`: should be a simple DFF clocked by `io.wr`, no combinational logic between pad and DFF.
- Confirm the i80 contract gives enough setup/hold for this latch (host must hold D stable around WR# rising edge).
**Pass:** DFF-based capture, timing constraints cover WR# setup/hold.  
**Result:** **Contributes** — `RegNext(io.dIn)` transitions same WR# edge as toggle; no settle margin before pixel-domain sample (#13252).

### 5. WR#-edge DC latch (`wrCapture.dc`)
**What:** same as #4 but for `wrCapture.dc`. DC must be stable at the write edge so opcode detection is reliable.  
**Owner:** BrightForge (RTL).  
**Method:**
- Inspect netlist; confirm `wrCapture.dc` is a single DFF on WR# domain.
- Check that the host guarantees DC stable before WR# rising (documented contract).
**Pass:** DFF-based capture, DC setup/hold met.  
**Result:** **Not isolated** — same crossing as data but single-bit; not independently the root cause.

### 6. Toggle generator (`wrCapture.toggle`)
**What:** confirm the toggle flips exactly once per WR# rising edge and never glitches.  
**Owner:** BrightForge (RTL).  
**Method:**
- Inspect synthesis result: `toggle := !toggle` should map to one DFF with inverted feedback.
- Verify no async load, no clock gating, no reset that could leave it stuck.
- Simulate a burst of WR# edges and check `toggle` is a clean divide-by-2.
**Pass:** one clean toggle per WR# edge, no missed or extra edges.  
**Result:** **FAIL (root)** — toggles same WR# edge as data with no source-side skew; creates the race condition (#13252).

### 7. Pixel-domain synchronizers (`BufferCC` on data/DC/toggle)
**What:** confirm the 2-FF synchronizers from WR# domain to pixel domain are correct and that the data is held stable until after the toggle is seen.  
**Owner:** BrightForge (RTL) / CyanPeak (code-to-spec).  
**Method:**
- Confirm `dInS` is sampled from `wrCapture.data` (already WR#-latched, stable), not from `io.dIn` directly.
- Confirm `dcAtWr` is sampled from `wrCapture.dc`, not `io.dc`.
- Confirm `capToggle` is sampled from `wrCapture.toggle`.
- Check synthesis ensures each `BufferCC` is two back-to-back DFFs with no combinational bypass.
**Pass:** all three signals pass through proper 2-FF sync; data/DC are sourced from the WR#-clocked registers.  
**Result:** **FAIL (root)** — 8 independent `BufferCC` bits resolve in different cycles; `dInS` is sampled before all bits settle (#13251).

### 8. Toggle edge detector (`wrRise`)
**What:** confirm `wrRise = capToggle =/= RegNext(capToggle)` produces exactly one pulse per new WR#-latched byte.  
**Owner:** BrightForge (RTL).  
**Method:**
- Verify the `RegNext(capToggle)` is clocked in the pixel domain.
- Check initialization: `init(False)` vs the initial toggle value; ensure the first transaction does not produce a spurious `wrRise`.
- Simulate back-to-back WR# edges at 2 MHz and confirm one `wrRise` per edge.
**Pass:** one `wrRise` per WR# edge, no missed or doubled pulses.  
**Result:** **FAIL (root)** — asserts while `dInS` bits still resolving metastability; zero settle delay (#13252).

### 9. FSM reaction to `wrRise`
**What:** confirm the FSM advances on `csActive && wrRise` and does not depend on `dcS` except at `sOpcode`.  
**Owner:** BrightForge (RTL).  
**Method:**
- Review `sAddrLo`, `sAddrHi`, `sDataLo`, `sDataHi`, `sBlkA0..L1`, `sBlkDat` guards: all must use `wrRise` only.
- Confirm `sOpcode` uses `!dcAtWr` (WR#-sampled DC) and not `!dcS` (continuous DC).
**Pass:** all internal write states advance on `wrRise` alone; opcode gate uses `dcAtWr`.  
**Result:** **FAIL (root)** — FSM samples `dInS` in same cycle as `wrRise`, zero settle delay (#13252).

### 10. CS deassert / abort behavior
**What:** confirm CS deassert (`!csActive`) returning the FSM to `sOpcode` does not corrupt an in-flight byte or leave `wrCapture` state mismatched.  
**Owner:** BrightForge (RTL).  
**Method:**
- Check whether `wrCapture` registers are reset or held when CS is high.
- Simulate a transaction aborted mid-byte, then a new transaction; confirm no stale toggle/data is consumed.
**Pass:** CS deassert cleanly aborts without consuming a ghost byte.  
**Result:** **Not root cause** — not implicated by the failure pattern.

### 11. SDC constraints for WR# domain
**What:** confirm `tang20k_i80.sdc` correctly constrains `io.wr` as a clock and declares it asynchronous to pixel/SDRAM clocks.  **Owner:** BrightForge (constraints) / CyanPeak (code-to-spec).  **Method:**
- Verify `create_clock -name i80_wr` targets the correct port.
- Verify `set_clock_groups -asynchronous` includes `i80_wr` vs `clk_pixel`, `clk_x5`, `clk_sdram`.
- Check whether input delay constraints are needed for `io.dIn` / `io.dc` relative to `i80_wr`.
**Pass:** WR# is a constrained primary clock; cross-domain paths are false-pathed through async groups.  
**Result:** **PASS / best-practice gap** — async groups correct; input delay constraints missing but not the cause (#13251).

### 12. Host-side register-write timing in firmware
**What:** confirm the firmware's register-write sequence (opcode + addr-lo + addr-hi + data-lo + data-hi) meets the i80 timing contract and does not violate setup/hold at the host pin.  
**Owner:** BronzeGate (firmware) / CyanPeak (code-to-spec).  **Method:**
- Review `vdp_host.c` i80 write routine: order of DC/CS/WR/D changes.
- Confirm data is stable before WR# goes low and remains stable until after WR# goes high.
- Confirm DC is stable before the first WR# edge of each byte.
**Pass:** firmware drive sequence matches the documented i80 contract.  
**Result:** **PASS** — DC/data stable before/after WR# rising; matches i80 contract (#13255 / #13251).

### 13. Simulation coverage gap
**What:** confirm the existing sims (`I80DataLatchSim`, `I80HostInterfaceSim`) exercise the actual failure mode (back-to-back writes at 2 MHz into the new toggle handshake).  **Owner:** BrightForge (RTL/sim).  **Method:**
- Review the two sims for write rate and pattern coverage.
- If they do not cover back-to-back register writes at host speed, create a targeted sim that does.
**Pass:** a sim reproduces either clean behavior or the observed corruption.  
**Result:** **FAIL (coverage gap)** — Verilator cannot model multi-bit metastability; sim PASS was blind to the hazard (#13252).

---

## Evidence summary table

| # | Component | Owner | Result | Evidence (commit/mail/line) |
|---|-----------|-------|--------|-----------------------------|
| 1 | Host WR# timing | BronzeGate | PASS on baseline / regression on fix | #13253/#13255: baseline clean to 12 MHz, only isolated 15 MHz clamp failures |
| 2 | Pin mapping / input buffer | BrightForge | not root cause | baseline loopback clean on same pinout |
| 3 | WR# clock domain | BrightForge / CyanPeak | PASS | CyanPeak #13251: RISING edge / BOOT reset correct for active-low WR# |
| 4 | Data latch `wrCapture.data` | BrightForge | contributes | `RegNext(io.dIn)` transitions same edge as toggle; no settle margin |
| 5 | DC latch `wrCapture.dc` | BrightForge | not isolated | same crossing as data but single bit |
| 6 | Toggle generator | BrightForge | FAIL (root) | toggles same WR# edge as data, no source-side skew (#13252) |
| 7 | Pixel-domain synchronizers | BrightForge / CyanPeak | FAIL (root) | CyanPeak #13251: 8 independent `BufferCC` bits resolve in different cycles |
| 8 | Toggle edge detector `wrRise` | BrightForge | FAIL (root) | asserts while `dInS` bits still resolving metastability (#13252) |
| 9 | FSM `wrRise` usage | BrightForge | FAIL (root) | FSM samples `dInS` in same cycle as `wrRise`, zero settle delay (#13252) |
| 10 | CS abort | BrightForge | not root cause | not implicated by failure pattern |
| 11 | SDC constraints | BrightForge / CyanPeak | PASS / best-practice gap | CyanPeak #13251: async groups correct; input delays missing but not cause |
| 12 | Firmware drive timing | BronzeGate / CyanPeak | PASS | #13255/#13251: DC/data stable before/after WR# rising; matches i80 contract |
| 13 | Simulation coverage | BrightForge | FAIL (coverage gap) | Verilator cannot model multi-bit metastability; PASS is blind to hazard (#13252) |

---

## Decision

- Failing components identified: #6, #7, #8, #9, plus #13 coverage gap.
- Root cause: the data+toggle handshake samples the multi-bit data synchronizer output before it has settled.
- Resolution: **revert** the CDC fix (`3fbeec4` + `a3a2866`). The baseline `65502b18`/`c8b5c0c` is loopback-clean and becomes the working base. A future CDC-hardening lane must use a metastability-safe crossing (e.g., asynchronous FIFO or delayed toggle sampling with CDC-lint/formal verification) and must not rely on Verilator alone.

## Gate rule

No RTL fix may be proposed until the checklist is at least 80% complete and the failing component is identified with evidence. If multiple components are suspicious, rank them by likelihood and test the highest-likelihood one first.
