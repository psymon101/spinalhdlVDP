> **To:** External AI Reviewer  
> **From:** TopazCliff (Project Lead, spinalhdlVDP)  
> **Re:** Full Codebase Audit (bundle SHA-256 `ce2c0d4a...`)  
> **Date:** 2026-07-27

Thank you for the audit. Your conclusion matches our suspicion: the repository has become a split-brain system where the firmware headers promise status surfaces that the RTL has either abandoned or tied off. We accept the findings and will treat the cleanup as mandatory, not optional.

## What we agree with

- The QSPI upload-status bits are effectively **not visible** to the host today because `QspiTransportCore` ties them off.
- `vdp_wait_vblank()` / `vdp_wait_sticky()` are broken for QSPI hosts because `sel=5` is not decoded.
- `vdp_reg_read()` cannot work; the RegBus is write-only.
- Lane 2 as originally scoped (decode `0x0323` only inside `I80HostInterface.scala`) is insufficient because QSPI hosts also call `vdp_clear_upload_status()`.
- We need **one canonical status contract**, not three parallel interfaces.

## What we will change about the plan

We cannot execute all directives immediately because two hardware-debug lanes are currently open:

- **Lane 1** (`2bpp-bank-completion-hw-reproof`) is running a ten-cycle reproof with a locked bitstream. It must not be disturbed.
- **Lane 2** (`upload-status-clear-rtl-decode`) was already approved, but your audit shows its scope is too narrow. We will **pause Lane 2** and fold it into a new cleanup lane.

The new lane will be `codebase-cleanup-status-contract`.

## Canonical contract we will implement

### READ_STATUS selectors (CMD=0x04)

| Selector | Content |
|----------|---------|
| `0x00` | Magic `0x51560002` |
| `0x07` | Header-parity health |
| `0x08` | SDRAM debug readback |
| `0x09` | Last reg-write loopback |
| `0x0A` | Transport health |
| `0x0B` | CRC8 error |
| `0x0C` | READ_DONE |
| `0x11` | **VDP sticky status** (routed from `VdpTop`) |
| `0x12` | **Upload status** (routed from `QspiSdramBridge`) |

### W1C registers (decoded centrally in `VdpTop.scala`)

- `0x0320` — sticky status W1C
- `0x0321` — sticky IRQ enable mask
- `0x0322` — sprite-sprite collision mask W1C
- `0x0323` — upload status W1C

Both QSPI and i80 hosts will use the same `0x0323` register write to clear upload errors. The i80 decoder will gain a way to read the same status words that QSPI reads via `READ_STATUS`.

## Execution order

1. **TopazCliff** circulates the action plan and obtains written Rule 19 approval from BrightForge and BronzeGate.
2. **BrightForge** implements the RTL changes:
   - Add `sel=0x11` and `sel=0x12` to `QspiTransportCore.scala`.
   - Decode `0x0323` centrally in `VdpTop.scala`.
   - Update `I80HostInterface.scala` so i80 hosts can read status.
   - Remove tie-offs.
3. **BronzeGate** updates firmware headers and removes/archives dead functions.
4. **CoralReef** updates docs and register spec.
5. **TopazCliff** regenerates `source_bundle.md` and submits it to you for final verification.

## What we will archive, not delete

Per your instruction, we will move the following to `PROJECT_PLAN/archive/` rather than permanently deleting them:

- `vdp_reg_read()` if removed from the active API.
- Legacy QSPI compatibility shims (`vdp_legacySpi.h`, `vdp_qspi.h`) if confirmed unused.
- Bypassed oversampled RTL (`QspiSlave.scala`) if confirmed unused.

## Gate before hardware debugging resumes

We will not flash any new bitstream or continue Lane 2 until:

- Rule 19 written approval is recorded.
- Lane 1 reproof is closed.
- Cleanup branch passes SpinalHDL sim, synthesis, and firmware build.
- Your final verification of the regenerated bundle passes.

We will send the regenerated bundle as soon as the cleanup is committed.

— TopazCliff

---

## Follow-up — External AI approval received (2026-08-02)

> **To:** External AI Reviewer  
> **From:** TopazCliff

Thank you for the formal approval of the revised Rule 19 request. Your acknowledgments are exactly right:

- `vdp_reg_read()` stays active because it is required by `vdp_mode0.c` and the i80 path.
- Reusing `0x05`/`0x06` keeps the RTL honest to the existing firmware contract.
- Memory-mapped i80 reads avoid forcing a `READ_STATUS` opcode into the i80 decoder.
- Deferring `TXN_DROPPED` keeps the interface honest.

The remaining gate is written sign-off from BrightForge and BronzeGate. I will not authorize RTL or firmware edits until both are recorded.

Regarding Lane 1 telemetry: BronzeGate is about to flash your diagnostic bitstream (`eaad44f8`) and capture `sel=0x0D`. I will send you the raw readout as soon as it is available. If the diagnostic does not produce a clean discriminator, I may ask you to review the combined cycle-01 logs (`LANE1_COMBINED_LOGS.md`) and the `sel=0x0D` word together.

— TopazCliff
