# QSPI Host-Control Frontend — Phase Artifact

**Status:** DONE — QSPI host control implemented, audited, and hardware-proven (Tasks 26–27, 38A–38C)  
**Depends on:** Mode0 substrate closure (Tasks 1–23, Scenarios 26–42 DONE)  
**Out of scope for first lane:** bulk asset streaming, protocol expansion, new rendering primitives, FIFO model redesign  

---

## 1. Purpose

Add a QSPI slave transport shim to the Tang Nano 20K so an external host (e.g., Raspberry Pi Pico 2) can drive the existing VDP register / FIFO / copper control path at runtime. This reuses the physical QSPI wiring and PIO program proven in the previous VDP project, but maps into the `spinalhdlVDP` indirect-register contract rather than the retired direct-SDRAM path.

## 2. Inherited Electrical / Control Assumptions

From the previous VDP project (`VDP/src/mode0/fpga/rtl/m0_qspi_slave.v`):

| Parameter | Value | Source |
|---|---|---|
| Clock domain | `clk_pixel` (74.25 MHz) | Oversampled SCK — zero CDC |
| Mode | Quad (4-bit parallel) | Always-on QSPI, no SPI fallback needed |
| SCK sampling | Rising edge | FPGA samples `IO[3:0]` on SCK rise |
| Byte order | High-nibble first | One byte = 2 SCK edges |
| Multi-byte fields | Little-endian | Address and length fields |
| Turnaround | 2 dummy SCK edges | Between header and read response |
| Pin mapping (Tang) | `CS=42`, `SCK=41`, `IO0=48`, `IO1=49` | Proven VDP project tang20k.cst |
| Pin mapping (Pico PIO) | `GP8=SCK`, `GP9=CS`, `GP10=IO0`, `GP11=IO1`, `GP12=IO2`, `GP13=IO3` | `qspi_quad.pio` |
| Max SCK | ~12.5 MHz | `clkdiv=5` @ 125 MHz sys_clk |

**Assumption:** the first lane does **not** change the electrical contract. The shim is a drop-in replacement for the bootstrap FSM as the `regWriteEnable` source.

## 3. Packet / Word Format

### 3.1 Transaction header (6 bytes)

| Byte | Field | Width | Notes |
|---|---|---|---|
| 0 | `CMD` | 8 | `0x01` = REG_WRITE, `0x04` = READ_STATUS |
| 1..3 | `ADDR` | 24 | Little-endian byte address into register space |
| 4..5 | `LEN` | 16 | Little-endian: number of 16-bit data words to write |

### 3.2 REG_WRITE payload

After the 6-byte header, each 16-bit word is sent little-endian (low byte first, high byte second). The QSPI decoder assembles them into `(addr, data)` pairs and asserts the VDP register-write interface.

```
Host → FPGA:  [CMD=0x01] [ADDR=0x000300] [LEN=0x0001] [DATA_LO=0x07] [DATA_HI=0x00]
Result:       regWriteAddr = 0x0300, regWriteData = 0x0007, regWriteEnable = 1 cycle
```

### 3.3 READ_STATUS response

When `CMD=0x04` and `LEN=0`, the FPGA drives `IO[3:0]` after a 2-edge turnaround:

| `sel` | Response | Bytes |
|---|---|---|
| 0 | Magic / version | `0x51560002` (bump from v1) |
| 1 | Runtime status | `{ready, last_cmd[7:0], last_error[7:0], rx_cmd_count[7:0]}` |
| 2 | Last accepted write echo | `{last_addr[15:0], last_data[15:0]}` |

Status readback is primarily for host bring-up and smoke-test validation.

## 4. Mapping into Existing VDP Contract

The QSPI decoder lives **inside** `TopTang20kHdmi` and wires directly to `VdpTop.io.regWrite*`:

```scala
// Existing VDP register-write interface (unchanged)
video.io.regWriteAddr   := Mux(qspiActive, qspiAddr,  bootAddr)
video.io.regWriteData   := Mux(qspiActive, qspiData,  bootDataMux)
video.io.regWriteEnable := qspiActive || regWriteFromBoot || animWriteActive
```

During bootstrap (`!bootDoneR`), the bootstrap FSM owns the bus. After bootstrap completes, the QSPI shim may assert `regWriteEnable`. The existing safe-boundary shadow registers inside `VdpTop` (`layerEnableReg`, `tileDecodeModeReg`, `attributeModeReg`, etc.) continue to gate commits to `hCounter === 0`, so QSPI-fed traffic enjoys the same tear-free guarantees as bootstrap-fed traffic.

### 4.1 No new FIFO required (first lane)

The copper already has a `StreamFifo(depth=4)` for copper-generated writes. External QSPI writes are treated as **immediate** (`extHit`) because:
- The host controls transaction timing and can avoid mid-line bursts.
- The safe-boundary shadow registers inside `VdpTop` already absorb the write.
- Adding a second FIFO would complicate arbitration without improving safety.

If hardware proves otherwise, a QSPI-side FIFO can be added in a later lane without changing the external protocol.

## 5. Required Proof

### 5.1 Simulation proof

- `QspiSlaveSim` — unit test of the QSPI slave decoding the 6-byte header and emitting `(cmd, addr, len)` plus payload bytes.
- `QspiRegWriteSim` — integration test: QSPI writes to `0x0300` (LAYER_ENABLE), verify `layerEnableReg` updates after safe-boundary commit.
- `UnifiedRegMapSim` extended with QSPI stimulus — confirm existing register map behaves identically under QSPI vs. bootstrap writes.

### 5.2 Hardware proof

- **Smoke test:** Pico 2 runs `test_qspi_smoke` equivalent, reads status `sel=0` magic, writes one register, reads back echo.
- **Register live-update:** Host changes `LAYER_ENABLE` via QSPI mid-frame, verify visual layer toggle on next frame boundary.
- **Copper programming:** Host uploads a short copper program via QSPI to `0x0400+`, then toggles `VDP_CTRL` to run it.

## 6. Out-of-Scope List (First Lane)

The following are **explicitly excluded** from the first QSPI lane:

| Item | Why excluded | When it might enter scope |
|---|---|---|
| Bulk SDRAM asset upload | Requires DMA-like streaming protocol and SDRAM arbiter changes | Later lane with dedicated asset-stream opcode |
| Sprite descriptor live update | Needs atomic multi-register commit; doable via `CMD_COMMIT` later | After basic register path is proven |
| Linestate per-line uploads | Large payload, needs flow control | After register path proven |
| Protocol version negotiation | v1 is sufficient for bring-up | If host ecosystem requires it |
| Interrupt / async notify from FPGA | Host polls status registers for now | If latency-sensitive use cases arise |
| Redesign of copper FIFO or safe-boundary logic | Existing logic is proven; QSPI is just a new source | Only if measured glitches appear |

## 7. Suggested Implementation Order

1. **SpinalHDL `QspiSlave` component** — oversampled SCK, quad IO, header decode, payload byte stream.
2. **SpinalHDL `QspiDecoder` component** — opcode dispatch, register-write pulse generation, status readback mux.
3. **Wire into `TopTang20kHdmi`** — after bootstrap completes, QSPI owns the `regWrite*` bus.
4. **Verilog generation** — `TopTang20kHdmiScenarioQspiVerilog` or similar generator entrypoint.
5. **Gowin constraints** — add QSPI pin LOCations to `tang20k_hdmi.cst`.
6. **Pico 2 smoke firmware** — minimal C test using existing `qspi_quad.pio`.

## 8. Checkpoint Plan

| Checkpoint | Owner | What it proves |
|---|---|---|
| A — Control contract | BrightForge | QSPI slave + decoder generate clean Verilog; pin mapping matches constraints |
| B — Simulation | BrightForge | `QspiSlaveSim` and `QspiRegWriteSim` pass; register writes land at safe boundary |
| C — Hardware | BrightForge | Smoke test PASS on Tang Nano 20K + Pico 2; status readback and single-register write verified |

## 9. Risks and Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| SCK oversampling fails at 12.5 MHz on Gowin | Low | Previous project proved same approach; keep SCK ≤ 12.5 MHz |
| QSPI pin LOC conflicts with existing HDMI/SDRAM pins | Low | Previous project used same pinout; verify in constraints |
| Safe-boundary glitches under rapid QSPI writes | Low | Shadow registers already absorb this; test in sim first |
| Pico 2 firmware build drift | Medium | Pin firmware SDK version and reuse existing `qspi_quad.pio` |

## 10. Lane-Open Checklist

- [ ] Artifact reviewed and approved by CyanPeak
- [ ] `TASKS.md` updated with new task entry
- [ ] `SCENARIO_*.md` created for smoke-test and register-live-update scenes
- [ ] No open Mode0 substrate lanes (confirmed — all Tasks 1–23 closed)
