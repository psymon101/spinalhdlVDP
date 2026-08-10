# Defect lane: qspi-status-done-bit-fix

**Lane ID:** `qspi-status-done-bit-fix`  
**Owner:** BrightForge (RTL/sim) + BronzeGate (firmware build + HW proof)  
**PM:** TopazCliff  
**Opened:** 2026-08-10  
**Status:** RULE 19 SIGN-OFF PENDING (BronzeGate needs-changes — see #14674 gates below)  

## Problem

The `codebase-cleanup-status-contract` lane merged to `main` defines upload-status bit 1 (`DONE`) as a sticky bit that remains set after an upload completes until it is cleared (CoralReef #14669, CyanPeak #14670).

The current implementation in `QspiSdramBridge.scala` drives `io.uploadDone` from a single-cycle `donePulse`:

```scala
val donePulse = Reg(Bool()) init False
donePulse := False
...
sDone.whenIsActive {
  donePulse := True
  goto(sIdle)
}
...
io.uploadDone := donePulse
```

`TopTang20kHdmi.scala` wires `qspiCore.io.upload_done := qspiSdramBridge.io.uploadDone`, and `QspiTransportCore.scala` samples it with `BufferCC(io.upload_done, False)` into `upDoneCC` for `READ_STATUS sel=0x06` and for the i80 `0x0323` read mux.

Because the pulse occurs in the **pixel/sys clock domain** and the host samples it through an SCLK-domain `BufferCC` on a bus whose clock stops while CS# is idle, the pulse is almost always missed. An i80 host also cannot reliably poll a 1-cycle 25 MHz pulse. The practical result is that `DONE` reads as `0` even after a completed upload.

## Scope

Fix the `DONE` output so it is a true sticky level:
- Set when the bridge FSM reaches `sDone` (same event that currently creates `donePulse`).
- Clear automatically at the start of the next upload (`headerValid` accepted / `sIdle`→`sActive` transition), so a new upload begins with `DONE=0`.
- Optionally clear on the existing `0x0323` W1C path if the contract is extended; **not required** for this fix because the contract currently only specifies W1C for bits 2/3.

Do **not** change:
- The `BUSY`, `ERROR`, or `OVERFLOW` semantics.
- The `0x0323` W1C clear mask (still bits 2/3).
- The QSPI protocol, pinout, or selector map.

## Proposed RTL change

In `hw/spinal/spinalhdlvdp/QspiSdramBridge.scala`:

1. Add a sticky register `uploadDoneSticky`.
2. Set it in `sDone.whenIsActive` (or via the existing `donePulse` as set condition).
3. Clear it in `sIdle.whenIsActive` when a new header is popped (`hdrFifo.io.pop.valid`) — i.e., on the transition to `sActive`.
4. Drive `io.uploadDone := uploadDoneSticky`.
5. Update the ScalaDoc comment that currently says "uploadDone pulses one cycle" to describe the sticky level behavior.

If any internal sim or test relies on the pulse shape (not just the level), evaluate whether to add a separate `uploadDonePulse` debug output or update the test. The existing tests that wait for `uploadDone.toBoolean` true will still pass because the sticky bit stays high.

## Acceptance criteria

- [ ] Rule 19 sign-off from BrightForge and BronzeGate.
- [ ] `sbt compile` PASS.
- [ ] Existing affected simulations PASS:
  - `Qspi0x0323StatusClearSim` (must still pass; extend or add a DONE-bit readback case).
  - `QspiTransportBridgeSim`, `QspiUploadIntegritySim`, `SdramUploadSim`, `QspiDecoderSdramBoundSim` (level-only checks should still pass).
- [ ] New or extended sim proves `DONE` is sticky: after a completed upload and after CS# has been idle, a subsequent `READ_STATUS sel=0x06` returns `DONE=1`.
- [ ] Gowin PnR PASS (TNS=0; no new resources expected).
- [ ] Firmware build PASS (ESP-IDF v6.0.2 active target).
- [ ] `STATUS.md` and this task file updated to DONE with artifacts.
- [ ] `firmware/GOTCHAS.md` or `MODE0_REGISTER_BUS_SPEC.md` updated if the wording about `DONE` is ambiguous.

## Risks

- Changing `uploadDone` from pulse to level may affect logic outside the status path if another module uses it as an edge. Search before editing.
- If `DONE` is not cleared early enough, a host reading status after a previous upload could see an stale `DONE=1`. Clearing on new-upload start is sufficient because the host initiates every new upload.

## BronzeGate Rule 19 needs-changes (mail #14674)

Before re-requesting Rule 19 sign-off, close these gates:

1. **Contract/doc reconciliation.** ADR-009 says `DONE` is "Sticky until cleared," while the proposed implementation clears it automatically when the next upload is accepted and the existing `0x0323` W1C mask covers only `ERROR`/`OVERFLOW` (bits 2/3). Explicitly state in ADR-009, `MODE0_REGISTER_BUS_SPEC.md`, `firmware/libvdp/mode0_regs.json`, `firmware/GOTCHAS.md`, and this task file whether the contract is:
   - "sticky across CS# idle until the next accepted upload starts" (no W1C on bit 1), or
   - "sticky until W1C-cleared" (which requires authorizing W1C on bit 1 as a separate interface decision).
   The default fix path (clear on new-upload start) matches option A.

2. **Extended simulation/proof.** Show that `DONE` goes high after upload completion, remains high across CS# idle, is observable through QSPI `sel=0x06` and i80 `0x0323` reads, and clears at the next upload start. Prove `BUSY`/`ERROR`/`OVERFLOW` behavior is unchanged. Audit `QspiTransportBridgeSim`, `QspiUploadIntegritySim`, `QspiSdramBridgeSim`, and any other consumer that may still expect an edge/pulse on `io.uploadDone`.

3. **Firmware build gate.** No firmware source change is expected, but the active ESP-IDF v6.0.2 target (e.g., `firmware/esp32p4_scaler_proof`) must build cleanly with the exact artifact/toolchain result recorded.

## Artifacts

- Source branch: `brightforge/qspi-status-done-bit-fix`
- Base: `main`
- Task file: `PROJECT_PLAN/TASKS/qspi-status-done-bit-fix.md`
