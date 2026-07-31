# BronzeGate firmware framing/readback audit

Date: 2026-07-31  
Lane: `qspi-upload-si-hardening`  
Owner: BronzeGate  
Source basis: `firmware/libvdp/vdp_host_p4.c`, `firmware/esp32p4_scaler_proof/main/main.c`

## Scope and verdict

This proof-only audit was assigned by TopazCliff #14539 after the deterministic
hardware failures at `0x100008` and `0x101000` were shown to be independent of
display workload. It traces the checkerboard source buffer, 253-word upload
framing, parity-encoded addresses, CRC placement, and the available P4
readback surfaces. No production firmware, RTL, register, or command change was
made.

Verified facts:

- The checkerboard source contains `0x55` at bitmap byte offsets 8–11 and at
  the 48-byte offset within frame 8 that maps to `0x101000`.
- Frame 0 starts at `0x100000`; target `0x100008` is byte offset 8 (word
  offset 4). Frame 8 starts at `0x100FD0`; target `0x101000` is byte offset 48
  (word offset 24).
- `upload_plane_diagnostic()` uses `frame * 253` words and
  `base + frame * 253 * 2` for every frame. The final frame is truncated only
  at the end of the plane, not at either target.
- `vdp_sdram_write()` emits a little-endian two-byte word count followed by
  little-endian word bytes. `write_frame()` copies that payload unchanged and
  appends the CRC byte after it; the CRC cannot overwrite or shift payload
  bytes.
- The computed wire details are:

  | frame | host address | wire address | payload length | target payload bytes | CRC-8-185 |
  |---:|---:|---:|---:|---|---:|
  | 0 | `0x100000` | `0x100000` | 508 bytes | `0x55 0x55 0x55 0x55` at payload bytes 10–13 | `0xDF` |
  | 8 | `0x100FD0` | `0x900FD0` (parity bit set) | 508 bytes | `0x55 0x55 0x55 0x55` at payload bytes 50–53 | `0x67` |

The two failing addresses therefore do not align with a host memcpy boundary,
the appended CRC byte, a retry-state reset, or a frame-length truncation in the
current firmware path.

## Readback-path audit

The diagnostic application writes `0x0326`/`0x0327` and reads `READ_STATUS`
selector `0x08` for the SDRAM debug value. The P4 backend's `vdp_reg_read()` is
an explicit `VDP_HOST_ERR_RX` stub, so it cannot issue a second register-read
command. The existing status surfaces do not provide an approved alternate
SDRAM-content readback path: selector `0x08` is the SDRAM debug surface and the
transport's selector `0x09` is loopback/status, not SDRAM data.

Consequently, this audit cannot distinguish a deterministic `sel=8` readback
artifact from deterministic SDRAM content loss. Adding a readback command,
register, or diagnostic bitstream would be a host-visible interface change and
requires the independent BrightForge + BronzeGate Rule 19 checkpoint with
TopazCliff authorization. No such change was inferred or implemented.

## Prior-art search (Rule 10)

The search covered `PROJECT_PLAN/TASKS_HISTORY.md`,
`PROJECT_PLAN/TASKS/*`, `PROJECT_PLAN/archive/artifacts/`, `archive/tasks/`,
`firmware/GOTCHAS.md`, `kb/`, MCP memory, and git history. Relevant prior art:

- `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/bridge_write_path_analysis_BrightForge.md`
  records the existing `sel=8` readback caveat and the #10928 readback class;
  it does not expose another approved P4 command.
- `hw/spinal/spinalhdlvdp/Qspi2bppReadbackSim.scala`,
  `QspiWriteStatusReproSim.scala`, and `SdramHandshakeProofSim.scala` cover
  earlier readback/write-status investigations, but do not provide a second
  production P4 host surface.
- `firmware/GOTCHAS.md` GOTCHA-030/031/035 document transport, SDRAM, and
  canonical-clock pitfalls; none documents this fixed-address framing pattern.
- MCP memory entries `ec8e03a63795bfc627e5f6efed31962b62811b5aeee7bf4d6caac913913a86d5`
  (prior `sel=8` readback blocker),
  `59dc46fba7f407e832eb99bddba2a97e35ec65663e62c77be06cbc548ff8347c`
  (proof-packet correction), and
  `73e7c7f2c3867a8b5963d05be03b66c554d2a00ac9ae9a7eb2e2fd06d8afe9d9`
  (CRC8-185 proof) were consulted. They support keeping this
  result as an audit/blocker, not declaring a new root cause or fix.

## Evidence and disposition

- Hardware evidence remains the 30-cycle mode-4/mode-0 cross-check in
  `hardware/REFRESH_PRESSURE_RESULTS.md`: both conditions fail at the same two
  addresses with clean transport health.
- BrightForge's faithful bulk-upload proof #14542 reports 61 frames, 7680
  words, and zero mismatches through the real transport/bridge/SDRAM model.
- The lane remains blocked on an approved alternate readback surface or a
  physical-layer test. No production firmware edit is justified by this audit.

Related mail: #14539 assignment, #14540 audit findings, #14542 faithful
transport proof, #14543 PM coordination.

— BronzeGate
