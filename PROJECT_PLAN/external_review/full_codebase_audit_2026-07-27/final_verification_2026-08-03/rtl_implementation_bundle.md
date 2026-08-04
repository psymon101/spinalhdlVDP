# Status-Contract Cleanup — RTL Implementation Bundle (external-AI final verification)

**Lane:** `codebase-cleanup-status-contract` (Step B, RTL) · **Author:** BrightForge · **Date:** 2026-08-03
**Branch:** `brightforge/status-contract-cleanup` · **Base:** `main` `fd39d2b0`
**Purpose:** final implementation bundle for the external-AI re-check (the only remaining gate before PM
merge authorization, per #14652). Gates already passed: CyanPeak code-to-spec review (#14647/#14649),
BronzeGate Step C firmware (#14650).

---

## 1. Canonical status contract implemented (per `rule19_signoff_request.md`)

| Surface | Content | Source |
|---|---|---|
| `READ_STATUS sel=0x05` | VDP sticky status (16b) | `VdpTop.statusStickyReg` |
| `READ_STATUS sel=0x06` | Upload status `[3:0]=busy,done,error,overflow` | `QspiSdramBridge` |
| reg `0x0320` R / W1C | VDP sticky read / write-1-to-clear | `VdpTop` (already existed) |
| reg `0x0323` R / W1C | Upload status read / W1C (bits 2/3) | `VdpTop` (new, centralized) |
| i80 read `0x0320`/`0x0323` | same words via `readData` mux | `TopTang20kHdmi` |

- Removed the `QspiTransportCore` MVP tie-offs (`dec.io.status_sticky := B(0)`, `dec.io.upload_* := False`).
- `0x0323` W1C decoded **centrally in `VdpTop`** so both QSPI and i80 writes (shared reg-bus) clear the
  same bridge stickies. Bit 2 → `upload_error`, bit 3 → `fifoOverflow`. **Set-wins-on-tie** in the bridge.
- Bits 4/5 RESERVED-0 (write ignored); `TXN_DROPPED` deferred (no detector). No `0x11`/`0x12`.
  `vdp_reg_read()` and `QspiSlave.scala` untouched.

## 2. Source diff

Full diff: `rtl_source.diff` (261 lines). Summary — **5 files, +155/−5**:

```
Qspi0x0323StatusClearSim.scala  | 98 +   (new sim)
QspiSdramBridge.scala           |  7 +   (uploadErrorClear/fifoOverflowClear inputs + W1C set-priority)
QspiTransportCore.scala         | 26 +/- (un-tie + status inputs + BufferCC + sel=0x05/0x06 responder cases)
TopTang20kHdmi.scala            | 15 +   (statusSticky+bridge stickies -> qspiCore; VdpTop clear -> bridge; i80 read mux)
VdpTop.scala                    | 14 +   (0x0323 W1C decode + uploadErrorClear/fifoOverflowClear outputs)
```

Commits on branch: `2366f104`, `77405bb3`, `1bd5d73b`.

## 3. Simulation

**`Qspi0x0323StatusClearSim` — PASS (7/7):**
```
[ok] fifoOverflow SET on wrCmd downstream stall
[ok] fifoOverflow CLEARED by W1C strobe
[ok] uploadError SET on watchdog stall abort
[ok] uploadError CLEARED by W1C strobe
[ok] fifoOverflow also cleared before tie test
[ok] fifoOverflow SET (tie setup)
[ok] SET-WINS-ON-TIE: fifoOverflow stays SET (clear pulsed while set active)
Qspi0x0323StatusClearSim: PASS
```

**Full affected regression — PASS** (each run in its own sbt invocation):
- `Indexed2bppFineCoSim`: MATCH on rows 100/240/400; INTRA-BYTE CLEAN.
- `Indexed2bppCheckerCoSim`: CHECKER-EDGE CLEAN (interior runs 64±1 px, no spurious runs).
- `Indexed2bppFrameCoSim`: ROW-CODED bestDv=3 (479/480, 1 startup); **SHEAR_SPAN=0px**.

`sbt compile` PASS; both tops (`TopTang20kHdmiVerilog` production + `TopTang20kI80Verilog`) elaborate clean.

## 4. Synthesis / PnR (Gowin V1.9.12.01, GW2AR-LV18QN88C8/I7)

- **TNS = 0.000 on ALL clocks** (clk_pixel, clk_x5, I_clk, clk_sdram, qspi_sck + all PLL generated clocks).
- Resources: Logic 11329/20736 (55%), Register 5629/15915 (36%), CLS 7661/10368 (74%),
  **BSRAM 40/46 (= production baseline, NO new BSRAM)**, **DSP 12/24 (NO new DSP)**.
- Reports: `fpga/tang20k/impl/pnr/project_tr_content.html` (timing), `project.rpt.txt` (resources).

## 5. Hashes

| Artifact | SHA-256 |
|---|---|
| Generated Verilog `hw/gen/top_tang20k.v` | `670c9c8c0175adacd5fc1817a8cb28e786f91616c9460cb8272efe9ea84210f7` |
| Bitstream `project_be997838_statuscontract.fs` | `be9978382fb16c463b238adc23a95275663dfdd540a3b6cff91e23a987c0fb5a` |

Firmware (BronzeGate Step C, `a5f2aaa9`): ELF `cb5a52d5…`, BIN `a3fda3dd…` (recorded in `firmware/`).

## 6. Notable implementation facts (for the reviewer)

- The **`QspiTransportCore.rxWordSel` switch is the authoritative READ_STATUS responder** (drives
  `slave.io.rxWord`); the internal `QspiDecoder`'s own sel response is vestigial — the new `sel=0x05`/
  `0x06` cases were added to `rxWordSel`, and `status_sticky`/`upload_*` cross sys→SCLK via `BufferCC`
  (same pattern as the existing sel=8/sel=11 selectors).
- `VdpTop.statusStickyReg` + `0x0320` W1C pre-existed; only the un-tie/wiring + `sel=5` case + the new
  `0x0323` decode were added.
- i80 `readData` is a parent-driven input; the existing `TopTang20kHdmi` mux (0x0328/9/0x0310/0x035C)
  was extended with `0x0320`/`0x0323` — a real mux entry, not a stub.
