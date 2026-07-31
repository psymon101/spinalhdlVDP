# spinalhdlVDP — External Review Prompt

## 1. What the project is

`spinalhdlVDP` is a SpinalHDL-based video display processor targeting the Sipeed Tang Nano 20K FPGA. It drives a 320×240 HDMI display from an external MCU host over a QSPI-like interface.

Key building blocks (all in `hw/spinal/spinalhdlvdp/` and generated RTL):
- **QSPI host interface** (`QspiTransportCore`, `QspiDecoder`) — commands, register writes, and bulk upload over a 4-wire SPI bus.
- **QSPI-to-SDRAM bridge** (`QspiSdramBridge`) — packetizes incoming bytes into SDRAM write commands.
- **SDRAM controller** (`sdram.v` black-box) — a hand-written 405 MHz SDRAM controller.
- **SDRAM arbiter** (`SdramArbiter`) — multiplexes refresh, display fetch, planar fetch, tile fetch, upload writes, and a debug readback port.
- **Debug readback surface** (`READ_STATUS sel=8` in `TopTang20kHdmi`) — allows the host to read a single 32-bit SDRAM word back for diagnostics.
- **Display pipeline** — bitmap, planar/tile, and direct-color modes feeding HDMI.

Host firmware lives in `firmware/libvdp/` and is written for the Raspberry Pi Pico. The current lane uses `firmware/libvdp/vdp_host_p4.c`, which uploads a 2bpp checkerboard to SDRAM base `0x100000` and then reads back selected words via `sel=8`.

## 2. The active lane: QSPI upload SI hardening

**Goal:** eliminate a residual data corruption in bulk QSPI upload.

**Observed symptom:**
- Upload a 320×240 2bpp checkerboard (30 720 bytes bitmap + 30 720 bytes attribute plane) from the Pico host to FPGA SDRAM base `0x100000`.
- After upload, selected 32-bit words read back via `READ_STATUS sel=8` are **always `0x00000000`** at two fixed addresses:
  - `0x100008` (byte 8 of the bitmap, third word)
  - `0x101000` (byte 0 of SDRAM row 1028, inside bitmap frame 8)
- All other readback sample addresses are correct (`0x55555555`).
- Transport health is clean: no `fifoOverflow`, no `uploadError`, no CRC8 mismatch counter change for the failing frames.

**Why this matters:** the defect is deterministic and silent. CRC8-185 and retry are already engaged and do not catch it, so the corruption happens after the CRC layer or is invisible to it.

## 3. What has been tried (with evidence)

| Step | Result | Evidence location |
|------|--------|-------------------|
| Engaged CRC8-185 + one retry in firmware | Still fails at the same addresses | `firmware/libvdp/vdp_host_p4.c`, proof packet manifest |
| BronzeGate discriminator: re-read 13 addresses 8× at 2 MHz | Values stable `0x00`; expected-zero neighbors stable `0x00` | `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/DIAGNOSTIC_RESULTS.md` |
| BrightForge bridge/retry analysis | No bridge hazard found; `sel=6` bridge status not host-visible | `bridge_write_path_analysis_BrightForge.md` |
| Co-sim reproducer (`QspiUploadCollisionSim`) | Refresh ON ⇒ 7–8 lost words; refresh OFF ⇒ clean | Later proven to be a **Verilator-Z artifact** — the 2-state model samples tri-stated DQ as `0x00` | `sim_coverage_matrix_BrightForge.md`, `SCLK_SWEEP_RESULTS.md` context |
| Coverage/fidelity matrix | Exact RTL modeled; unclosed risks labeled (fetch contention, `sel=8` readback) | `sim_coverage_matrix_BrightForge.md` |
| Line-2 faithful pivot (`BurstRefreshDataSurvivalSim` extended to bulk upload) | **61 frames, 7680 words, 0 mismatches** — transport/bridge/`sdram.v` path proven clean | mail #14542, `PROJECT_PLAN/STATUS.md` |
| Workload cross-check (Mode 4 layer disabled vs Mode 0 full fetch) | 30/30 failures at the same addresses in both modes | `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/hardware/REFRESH_PRESSURE_RESULTS.md` |
| Firmware framing/address/CRC audit | Host buffer has `0x55`; frame, address, and CRC calculations correct | `firmware/FRAMING_READBACK_AUDIT.md`, mail #14540 |
| `sel=8` readback SCLK sweep (2 / 1 / 0.5 / 0.25 MHz) | **Stable zeros at all rates** — rules out readback SCLK/timing sensitivity | `SCLK_SWEEP_RESULTS.md` |

## 4. What we are currently stuck on

The evidence has narrowed the problem to two remaining forks:

1. **SDRAM really contains `0x00`** at those addresses (write-side issue: a subtle controller/physical-layer/address-decode bug that the faithful RTL sim does not reproduce), **or**
2. **`sel=8` debug readback returns `0x00`** even though SDRAM contains `0x55` (a deterministic readback bug in the debug-read CDC/arbitration path).

The next authorized discriminator is **display-output indirect readback** (#14552): upload a distinctive 2bpp asset where the failing words are painted a unique color, then observe the screen in normal display mode. If the screen shows the unique color, SDRAM is good and `sel=8` is lying. If the screen is wrong, SDRAM really contains `0x00`.

We have not yet run that test. If it is inconclusive, the fallback is physical QSPI/SDRAM bus capture or a Rule-19-approved temporary debug interface.

## 5. What we are asking the reviewer

Please read the bundled source files and tell us:
- Are there any obvious bugs in `vdp_host_p4.c` upload framing, address calculation, or CRC append that could silently zero-out specific words?
- Are there known CDC/arbitration corner cases in the `sel=8` debug readback path (`dbgReadArea` in `TopTang20kHdmi`) that could return `0x00` for specific SDRAM addresses while other addresses read correctly?
- Are there any address-mapping or byte-lane subtleties in the SDRAM controller or bridge that would explain why only `0x100008` and `0x101000` are affected?
- Are there other existing diagnostic surfaces (besides `sel=8`) that could discriminate the two forks without requiring a new host-visible interface?

## 6. Key file pointers

- Host firmware: `firmware/libvdp/vdp_host_p4.c`
- SpinalHDL top: `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- Debug readback block: search `dbgReadArea` in `TopTang20kHdmi.scala`
- SDRAM bridge: `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`
- QSPI transport: `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`
- Generated RTL for the same modules is in `rtl_source.txt`
- Live status: `PROJECT_PLAN/STATUS.md`
- Lane task file: `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`
- Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`

## 7. Project rules that constrain the lane

- **Rule 10 (Prior Art Search):** any root-cause/mechanism/fix claim must include citations from `TASKS_HISTORY.md`, `archive/artifacts/`, `GOTCHAS.md`, `memory`, and git history.
- **Rule 19 (Interface Checkpoint):** host-visible changes need independent BrightForge + BronzeGate approval before implementation.
- **No-assumptions rule:** a path cannot be dismissed as "structurally impossible" without a sim or HW run that exercises it and shows it clean.

No production RTL or firmware edits are authorized until a concrete mechanism is identified and a fix survives proof.
