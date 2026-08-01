# External Review Response — Lane 1 first-cycle magic anomaly

Date: 2026-08-01  
Reviewer: external AI reviewer  
Files reviewed:
- `spinalhdlVDP/PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/source_bundle.md`
- `spinalhdlVDP/PROJECT_PLAN/external_review/lane1_magic_anomaly_2026-08-01/issue_description.md`

---

## 1. Plausibility of the post-reconfigure settle/early-read explanation

**Yes — BrightForge’s explanation is the most probable cause.**

Evidence from the source bundle:

- The magic constant is an unchanging RTL literal. In `QspiTransportCore.scala` line 200 the `sel=0` read is hard-wired to `B"32'h51560002"`.
- The read-responder mux is combinational on `slave.io.cmdAddr(7 downto 0)` (`QspiTransportCore.scala` lines 196–209). Once `QspiSlaveSync` has decoded a valid `READ_STATUS` header, the data path cannot return anything other than `0x51560002` for selector 0.
- `QspiSlaveSync` (generated Verilog, lines 1149–1158) explicitly handles `CMD_READ_STATUS = 0x04` by setting `area_lenR <= 16'h0`, asserting `area_cmdValidR`, and entering the dummy phase. The current Option A transport therefore does **not** have the legacy single-lane/no-LEN framing mismatch that stalled the old `QspiSlave` at `0x22222222`.
- The same `a5a047a2…` bitstream previously returned the correct magic in an approved 4 MHz run. A deterministic RTL defect would likely reproduce every time the bitstream loads.

`0x22222222` is nibble `0x2` repeated eight times, i.e. `IO[3:0] = 0010` sampled on every nibble. That pattern is consistent with the master reading the bus while the FPGA pads are still high-impedance, held, or pre-configured to a weak state immediately after `openFPGALoader` finishes SRAM configuration but before the internal clocks/reset have fully settled and the `GowinIobuf` tri-state outputs are actively driven. The slave FSM is reset by `io_csn` (`QspiSlaveSync` lines 1115–1136), so if the first CS assertion occurs during that window the responder may simply never enter `Phase_RDATA`, leaving the host to sample whatever DC state is on the quad lines.

**Alternative mechanisms** are less likely but not impossible:
- A corrupted bitstream load — unlikely because `openFPGALoader` exited 0 and the bitstream hash is verified.
- A persistent electrical fault on the QSPI lines — would probably not produce the neatly uniform `0x22222222` pattern and would likely also corrupt later transactions.
- An RTL misconfiguration of `QspiTransportCore` — contradicted by the earlier successful run with the same bitstream.

---

## 2. Read-only diagnostics to distinguish the three hypotheses

No code changes are required; the existing firmware already exposes the right selectors.

| Hypothesis | Diagnostic | What to look for |
|------------|------------|------------------|
| **Genuine RTL issue** | Read `SEL_TRANSPORT_HEALTH` (0x0A), `SEL_CRC8_STATUS` (0x0B), and `SEL_HEADER_PARITY` (0x07) after the bad magic. The health selector surfaces `push.malformed` (bit 1) and `push.overflow` (bit 0) (`QspiTransportCore.scala` lines 204, 237–238); the CRC selector surfaces sticky/count (`QspiTransportCore.scala` lines 121–123, 205); the parity selector surfaces `hdrErrSticky`/`hdrErrCount` (`QspiTransportCore.scala` lines 133–135, 201). | Persistent or increasing error counts, or `malformed`/`overflow` set, point to a real transport defect. Clean sticky flags support a startup-timing explanation. |
| **Host-side timing issue** | Loop `vdp_read_status(SEL_MAGIC)` several times immediately after `vdp_host_init()` and log every value. Also log `SEL_TRANSPORT_HEALTH` on each iteration. | If the value transitions from `0x22222222` to `0x51560002` within a few reads, the FPGA simply was not ready for the first transaction. |
| **Bus/electrical issue** | Inspect the `openFPGALoader` log for any DONE/status warnings. If hardware access is available, scope `CS`, `SCLK`, and `IO[3:0]` around the first transaction and verify that CS and SCLK are quiescent before the host asserts CS. | Spurious SCLK/CS toggles during or just after reconfigure, or non-uniform line values during the response window, indicate noise or contention. A clean, idle bus followed by a uniform `0x22222222` points to an unready responder. |

Additional lightweight checks:
- Read `SEL_LOOPBACK` (0x09) after performing one deliberate register write. If loopback returns the correct `{data, addr}` pair, the full `SCLK → CDC → clk_sys → decoder → SCLK` path is functional.
- Read `SEL_READ_DONE` (0x0C). Bit 0 should be 0 before any SDRAM debug read is armed; non-zero reserved bits would indicate a read-side problem.

---

## 3. Retry conditions and preconditions

A **≥1 s post-SRAM-load delay is a reasonable first step** and is consistent with normal FPGA bring-up practice. The delay gives the Gowin device time to complete internal initialization and for clocks/PLLs to lock before the ESP32 drives the first transaction.

Before counting a cycle toward the ≥10-cycle gate, the following preconditions should be met:

1. **`openFPGALoader` reports successful 100 % configuration** and the preserved bitstream hash matches `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`.
2. **Bus idle guarantee:** ensure `CS` is high and `SCLK` is low for at least a few microseconds before the first `vdp_read_status(SEL_MAGIC)`. The host firmware currently initializes the SPI bus in `vdp_host_init()` (`vdp_host_p4.c` lines 1386–1414); a short `vTaskDelay` after init and before the first read would provide this.
3. **Magic read passes:** the very first read of `SEL_MAGIC` must return `0x51560002`. If it returns `0x22222222` or any other value, the cycle is discarded and the procedure stops per TopazCliff’s escalation rule.
4. **Transport health is clean:** read `SEL_TRANSPORT_HEALTH` immediately after the magic check and confirm both `overflow` and `malformed` bits are 0.
5. If the loader supports it, **verify FPGA DONE** before releasing the host from its settle wait.

I do **not** think a specific `openFPGALoader` reset option is required at this stage; the issue is almost certainly FPGA-side startup timing, not a programming-mode problem.

---

## 4. Safety of continuing the ≥10-cycle reproof

Continuing is **acceptable with the following safeguards**, and no production RTL/firmware edits are needed if the anomaly does not recur.

Recommended capture/health checks for every counted cycle:

1. **Pre-upload health:** call `health()` (read `SEL_TRANSPORT_HEALTH`) and log the raw value, `overflow`, and `malformed` flags. This function already exists in `main.c` (lines 1564–1572).
2. **Magic stability:** after the initial good magic, optionally re-read `SEL_MAGIC` once more before the first upload. A mid-test reversion to `0x22222222` is a hard stop condition.
3. **Per-upload health:** the diagnostic modes already log `SEL_CRC8_STATUS` before and after each frame (`upload_plane_diagnostic`, `main.c` lines 1614–1649). For the standard reproof path, at minimum log `SEL_TRANSPORT_HEALTH` after bitmap and attribute uploads and again after display enable.
4. **Post-upload readback:** the standard path already calls `verify_readback()`; keep it. Add logging of any readback value that equals `0x22222222` or `0x00000000` as an anomaly.
5. **End-of-cycle health:** log `SEL_TRANSPORT_HEALTH`, `SEL_CRC8_STATUS`, and `SEL_MAGIC` one final time before declaring pass.
6. **Stop rule:** if any read returns `0x22222222`, or if `overflow`/`malformed` ever set, stop the campaign and escalate to BrightForge for RTL investigation.

The existing `main.c` already implements much of this for the special `SCALER_PROOF_MODE` builds; the same logging discipline should be applied to the default mode that performs the actual ≥10-cycle gate.

---

## Summary verdict

- The `magic = 0x22222222` observation on cycle 1 is best explained by the ESP32 issuing `READ_STATUS` before the FPGA’s QSPI responder was fully ready after SRAM reconfigure, not by an RTL defect.
- The correct next action is the authorized ≥1 s settle-delay retry with strict preconditions (good magic + clean transport health before counting the cycle).
- If the anomaly repeats after settle, or recurs mid-test, then an RTL/electrical investigation is warranted; until then, no code changes are justified.
