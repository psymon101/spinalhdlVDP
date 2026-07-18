# QSPI Clock-Domain Evaluation — QSPI-SI-CEILING-183

**Author:** BrightForge  
**Date:** 2026-07-18  
**Trigger:** TopazCliff PM ask #14143 — evaluate external advice to route ESP32-P4 `SPI_CLK` directly to a Gowin GCLK, assemble QSPI nibbles in `SPI_CLK` domain, and cross into the FPGA internal clock via a dual-clock async FIFO.

## 1. Current architecture

The current QSPI slave lives in `hw/spinal/spinalhdlvdp/QspiSlave.scala` (active in the `ham-build-171` QSPI Option A worktree).

- **Clocking:** all QSPI state machines run on `clk_pixel` (25.2 MHz pixel clock). The incoming `SPI_CLK` (up to ~20 MHz from the ESP32-P4) is treated as an asynchronous data signal.
- **Synchronisation:** 2-stage synchronisers on `spi_cs_n`, `spi_sck`, and the four `spi_io_in` pins (`QspiSlave.scala:49-62`).
- **Sampling:** SCK rising/falling edges are detected by comparing the synchronised SCK sample to its delayed value (`sck_prev`) in the `clk_pixel` domain (`QspiSlave.scala:64-73`).
- **Data recovery:** on each detected SCK rising edge, a 4-bit nibble is shifted into an 8-bit byte; after two edges the byte is emitted as `payload_byte`/`payload_valid`.
- **Downstream:** `QspiDecoder` interprets the byte stream, `QspiSdramBridge` buffers writes and emits SDRAM write commands, and `QspiTransportCore` / `uploadCc` cross into the SDRAM clock domain.

In short: the current slave is **oversampled/synchroniser-based**, not **clock-domain-based**.

## 2. Would `SPI_CLK`-as-clock + async FIFO fix the upload contention?

**No. The current failure mode is not in QSPI pin sampling.**

Evidence from the bench:
- `RAINBOW_MAGIC = 0x51560002` is received cleanly (transport-layer CRC/framing passes).
- `malformed = 0`, `pass = 1` in the host protocol — bytes are recovered correctly.
- With fetch enabled during upload, the image does not land in SDRAM; with fetch disabled during upload, it lands cleanly.

This proves the QSPI-to-FPGA byte path is working. The failure is downstream: **SDRAM writes from the upload client lose arbitration to active display fetch clients** when bitmap fetch is enabled. Re-clocking the QSPI input does not change the SDRAM arbitration problem.

A dual-clock async FIFO would only move the CDC boundary from "QSPI byte stream in pixel domain → SDRAM writes in sdram domain" to "QSPI nibbles in SPI_CLK domain → SDRAM writes in sdram domain". The contention point (SdramArbiter / FetchSlotScheduler) remains identical.

## 3. Estimated RTL effort if pursued anyway

| Area | Effort / Risk |
|---|---|
| New `SPI_CLK` clock domain | Add a GCLK input buffer and a SpinalHDL `ClockDomain`. Must constrain `SPI_CLK` as a primary clock in SDC. |
| Nibble-to-byte assembly | 2-stage shift register on `SPI_CLK` (4 bits → 8 bits). Straightforward. |
| Async FIFO | Gowin primitive or SpinalHDL `StreamFifoCC`/`FifoCC`. Depth ~16–64 entries. CDC constraints required. |
| CS framing / protocol state | Currently runs on `clk_pixel`. Either move CS edge detection to `SPI_CLK` domain or keep it synchronised. Moving it adds CDC complexity for transaction start/end. |
| Response path (READ_STATUS) | Currently turnaround is timed in `clk_pixel`. Would need a second async FIFO or a CDC-safe handshaking path back to `SPI_CLK` for driving IO outputs. |
| Files touched | `QspiSlave.scala`, `TopTang20kHdmi.scala` (clock routing), `tang20k_hdmi.sdc`, possibly `QspiTransportCore.scala`. |
| Sim gate | New `QspiSlave` unit test, FIFO CDC formal, top-level `QspiHamIntegrationSim` regression. |
| Bitstream validation | STA, re-run QSPI word-drain / HAM integration sims, bench flash at 8 MHz/20 MHz/40 MHz. |
| Schedule estimate | 1–2 days RTL + sim, 1 day STA/PnR, 1 day bench validation (optimistic). |

## 4. Comparison of four options

### A. Firmware-only fix: upload with fetch disabled
- **Status:** Already proven on the bench (#14129/#14130). Upload `0x0350=0x0002`, then enable `0x0350=0x0005`.
- **Cost:** Zero RTL/BSRAM; one firmware sequencing change.
- **Risk:** Low. Requires disciplined host driver sequencing.
- **Verdict:** Do this now for production.

### B. RTL write-priority / upload-window in `SdramArbiter`
- **Status:** `SdramArbiter` is currently a simple mux; scheduling is done by `FetchSlotScheduler` (`FetchSlotScheduler.scala`).
- **Approach:** Add a programmable "upload window" register. When asserted, the scheduler prioritises the upload client (client 4) over display fetch slots, or `SdramArbiter` suppresses display `rd` when an upload write is pending.
- **Cost:** Small RTL change (~1 file + scheduler config register), no pinout changes.
- **Risk:** Must not break display timing; need sim + STA + visual proof.
- **Verdict:** Good defense-in-depth; cheaper than clock-domain rework.

### C. Hardware Busy/Rdy pin
- **Status:** Requires an FPGA output and a free ESP32-P4 GPIO. BronzeGate identified GPIO34 as a candidate pending schematic check (#14145).
- **Approach:** FPGA deasserts ready when upload FIFO/SDRAM backpressure is high; P4 pauses QSPI clock or CS.
- **Cost:** One FPGA output pin, one P4 GPIO input, firmware interrupt/polling logic.
- **Risk:** Board wiring dependency; flow-control protocol design.
- **Verdict:** Useful if we want robust pacing without relying on host discipline, but requires hardware change.

### D. `SPI_CLK`-as-clock + async FIFO
- **Status:** External advice; not implemented.
- **Cost:** Highest (new clock domain, CDC FIFOs, response-path CDC, SDC, full regression).
- **Risk:** Does not address the proven failure mode (SDRAM arbitration). Adds complexity and CDC/STA risk.
- **Verdict:** Not recommended for the current problem.

## 5. Recommended priority order

1. **Firmware-only fix** — immediate production fix, zero hardware cost.
2. **RTL upload-window / write-priority** — defense-in-depth, protects against misbehaved host drivers.
3. **Hardware Busy/Rdy pin** — if bench wiring allows and we want hardware-paced flow control.
4. **`SPI_CLK`-as-clock + async FIFO** — only if future QSPI speeds exceed the oversampling margin of the current slave.

## 6. Blockers if option D is pursued

- Gowin primitive async FIFO availability in SpinalHDL vs direct primitive instantiation.
- `SPI_CLK` pin is currently routed to a general FPGA input, not necessarily a GCLK-capable pin on the Tang Nano 20K. CST/PCB check required.
- Response-path CDC for READ_STATUS turnaround.
- Full sim/STA/bench regression before any claim of improvement.

---

**Summary:** The external `SPI_CLK`-as-clock proposal is technically feasible but does not address the root cause proven by the fetch-after-upload discriminator. Recommend the firmware fix first, with optional RTL write-priority as defense-in-depth.

— BrightForge
