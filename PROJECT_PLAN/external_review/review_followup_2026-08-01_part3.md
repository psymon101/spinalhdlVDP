# Follow-up #3 for External Reviewer — spinalhdlVDP lane 3

**Date:** 2026-08-01  
**Context:** Team has not yet produced a mode-8 hardware result. The first hardware attempt failed with an FTDI/JTAG driver-contention error before any FPGA programming occurred. We are asking for your guidance on how to proceed while waiting for the hardware retry, and on what to conclude if the new diagnostic returns either fork.

---

## What has happened since follow-up #2

1. **Rule-19-approved diagnostic interface was implemented.**
   - BrightForge added a dedicated `READ_STATUS` selector `0x0C` with bit 0 = `READ_DONE`.
   - The existing `0x0326`/`0x0327` arm mechanism is reused.
   - The pixel-domain result latch (`dbgResultPixArea` in `TopTang20kHdmi.scala`) was hardened so `READ_DONE` asserts only after the settled result is available.
   - CDC co-sim `ReadDoneCdcSim` passes (with the usual Verilator ideal-2-FF caveat).
   - 3-build STA is TNS=0, BSRAM 40/46 (no regression).
   - Bitstream: `fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`, SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`.
   - RTL source commit: `5ef5db2a`; generated Verilog SHA-256 `ff01ab71…`.

2. **BronzeGate built the matching proof firmware.**
   - `SCALER_PROOF_MODE=8` (commit `158b9d7c`).
   - Sequence per address: write `0x0326` (LO) → write `0x0327` (HI, arms + clears `READ_DONE`) → poll `READ_STATUS sel=0x0C` bit 0 until `1` → read 32-bit result via `sel=8`.
   - Targets remain `0x100008` and `0x101000`.

3. **First hardware attempt failed before programming.**
   - Command: `openFPGALoader --board tangnano20k --bitstream fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`
   - Error: `unable to open ftdi device: -6 (ftdi_usb_reset failed)` / `JTAG init failed`.
   - Diagnosis: `ftdi_sio`/`usbserial` kernel modules were loaded and likely claimed the FT2232 interface, causing libftdi’s reset to return busy. `openFPGALoader --detect` succeeded, so the device and permissions are otherwise healthy.
   - PM-authorized recovery: `sudo rmmod ftdi_sio usbserial`, re-verify `openFPGALoader --detect`, retry SRAM load, then run mode-8 proof.
   - **No mode-8 result has been reported yet.**

---

## The current fork (unchanged in substance)

When the hardware retry eventually runs, the result will be one of:

- **`0x55555555` at the targets** ⇒ SDRAM contains the expected data; the defect is in the `sel=8`/CDC/readback path.
- **`0x00000000` at the targets** ⇒ SDRAM genuinely contains zeros; the defect is on the write/physical side.

BrightForge notes that the corrected double-read already leaned write-side, but the new interface is the definitive discriminator.

---

## What we are asking now

Because hardware is currently blocked on a host-side driver issue and may take time to retry, we want your input on two things:

### 1. What should we do *while waiting* for the hardware retry?

- Are there any **additional software/firmware-only discriminators** we can run with the existing bitstream or the already-built mode-8 firmware, short of physical bus capture?
- Is there a **focused RTL simulation** BrightForge should run now to expose a likely mechanism? If so, which exact signals/conditions should be probed?
- Should we attempt to **vary the upload pattern** (e.g., non-`0x55` data at the targets, or writing only the target words) to learn more before the READ_DONE result arrives?

### 2. How should we interpret the two possible READ_DONE outcomes?

- If `READ_DONE` returns **`0x55555555`**, we will conclude `sel=8` is the culprit and document/harden that path. Is there any reason that conclusion could be wrong?
- If `READ_DONE` returns **`0x00000000`**, we will reopen the physical write-side investigation. What is the **smallest next experiment** you would recommend to localize between:
  - QSPI physical-layer corruption during the long 30 720-byte burst,
  - SDRAM controller/address-decode issue,
  - FPGA-side bridge timing issue that only manifests in real silicon,
  - host firmware/driver issue that leaves the bytes on the wire intact but causes the FPGA to write the wrong place?

### 3. Are we missing any existing diagnostic surface?

We have now used or considered:
- `READ_STATUS sel=8` direct readback,
- `sel=8` SCLK sweep (2 / 1 / 0.5 / 0.25 MHz),
- corrected double-read (`readback_word()` twice per address),
- display-output indirect readback,
- transport health (`sel=0x0A`),
- CRC8-185 status (`sel=0x0B`),
- the new `READ_DONE` (`sel=0x0C`) + settled-latch interface.

Is there any other **existing** register, selector, or side effect in the FPGA or firmware that could reveal whether the zeros are written or read incorrectly?

---

## Key file pointers (current)

- Host firmware: `firmware/libvdp/vdp_host_p4.c` (mode-8 proof changes in commit `158b9d7c`).
- SpinalHDL top / debug readback: `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` (`READ_DONE` changes in commit `5ef5db2a`).
- QSPI transport / selector map: `hw/spinal/spinalhdlvdp/QspiTransportCore.scala`.
- SDRAM bridge: `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`.
- Live status: `PROJECT_PLAN/STATUS.md`.
- Lane task file: `PROJECT_PLAN/TASKS/qspi-upload-si-hardening.md`.
- Proof packet: `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/`.

---

## Note on bundled source files

The previously bundled `firmware_source.txt`, `spinalhdl_source.txt`, and `rtl_source.txt` pre-date the `READ_DONE` changes. The current source of truth for the new diagnostic is the committed files above and the proof-packet synthesis/simulation records. If you need refreshed bundled dumps, let us know and we will regenerate them.
