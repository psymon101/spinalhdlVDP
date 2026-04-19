# Task 34 — QSPI Host-Driven Asset Upload

**Status:** Artifact phase
**depends_on:** [27, 38c]
**scope_boundary:** Bulk SDRAM write via QSPI only. No new rendering primitives, no protocol redesign, no SDRAM controller replacement.
**delivers:**

- QSPI command path for writing SDRAM directly (textures, tilemaps, tile rows, palette entries/banks)
- Addressed burst write protocol with progress/status
- Hardware proof: upload a small texture/tileset and palette entry/bank via QSPI and render it

**validation:**

- Sim: QSPI burst write lands in SDRAM model, fetched data matches
- Hardware: uploaded asset renders correctly on Tang Nano 20K

---

## 1. Goal

Enable the host (Raspberry Pi Pico 2) to upload assets directly into FPGA SDRAM over the proven QSPI link, without requiring a full FPGA bitstream rebuild. This closes the host-control substrate: after Tasks 38a–c (bidirectional QSPI) and 35 (IRQ/status), the host can now both control the VDP and deliver content to it.

## 2. Scope

### 2.1 In scope

1. **New QSPI opcode** `CMD=0x02 = SDRAM_WRITE` in `QspiDecoder`
2. **Header-packet format** extension carrying SDRAM target address + word count
3. **Decoder routing** — payload bytes forwarded to an SDRAM bridge instead of the register bus
4. **New bridge component** (`QspiSdramBridge`) between QSPI payload stream and `SdramController` write interface
5. **SDRAM controller arbitration** — mux between fetch-engine reads and upload writes
6. **Sideband status** — upload-in-progress / done bit in the existing Task 35 sticky status surface
7. **Firmware helper** on Pico 2 to stream asset bytes over QSPI
8. **Simulation** — `SdramUploadSim` proving bytes land at correct offsets
9. **Hardware proof** — upload a small test asset during blanking, confirm render

### 2.2 Out of scope (deferred)

- CRC checksum of uploaded assets (nice-to-have; may be added later)
- Multi-region atomic swap / double-buffering
- SDRAM arbitration refactor beyond simple blanking gating (full arbiter = Task 30)
- New asset formats (bulk bytes into existing tile/planar/palette/scroll regions only)
- Host library abstraction (Task 39)
- Read-back of uploaded assets over QSPI (host verifies by observing render output)

## 3. Protocol Specification

### 3.1 QSPI SDRAM_WRITE packet format

```
Header (6 bytes):
  [0]     = 0x02                        CMD = SDRAM_WRITE
  [1:3]   = target SDRAM byte address   (24-bit, LSB first)
  [4:5]   = LEN in 16-bit words         (little-endian, up to 64K words = 128 KB burst)

Payload (2 × LEN bytes):
  little-endian 16-bit words streamed into SDRAM at the target address
  Auto-increment per word (like REG_WRITE but landing in SDRAM)
```

**Properties:**
- Reuses the proven QSPI quad-mode + header format from Tasks 26/27/38
- `QspiSlave` needs zero changes (emits payload bytes regardless of opcode)
- `QspiDecoder` grows a new opcode path that forwards payloads to the SDRAM bridge
- Back-pressure: at 2 MHz SCK, SDRAM write latency (~µs/byte) matches SCK rate, so a shallow FIFO suffices

### 3.2 Response and status

- No mid-burst response. Host polls `READ_STATUS sel=5` (sticky status) for `UPLOAD_DONE`.
- Error path: malformed opcode or collision raises `QSPI_ERROR` (sticky bit 3) via `last_error`.
- `last_error` clear: writing to `UPLOAD_CTRL` register (`0x0350`) resets the error latch as a side effect.

### 3.3 SDRAM address mapping

The SDRAM controller exposes a 23-bit byte address (`addr[22:0]`). Internal organization is 32-bit words with bank/row/col decomposition. The bridge performs simple byte-addr → SDRAM-word mapping:

```
sdram_word_addr = byte_addr >> 2   // word index
sdram_byte_in_word = byte_addr & 3 // byte lane (for 8-bit controller din)
```

The controller's `din` is 8-bit; the bridge streams bytes one at a time with appropriate byte-enable semantics (if supported) or relies on word-level writes.

**Note:** The current `SdramController` BlackBox uses 8-bit `din` and 32-bit `dout32`. Writes are byte-wide. For 16-bit word payload from QSPI, the bridge writes low byte then high byte to consecutive byte addresses.

## 4. HDL Architecture

### 4.1 Component diagram

```
Host (Pico 2)
   │ QSPI
   ▼
QspiSlave ──► QspiDecoder
                  │
    ┌─────────────┼─────────────┐
    │             │             │
    ▼             ▼             ▼
REG_WRITE    READ_STATUS    SDRAM_WRITE (new)
    │             │             │
    ▼             ▼             ▼
regWriteBus   tx_byte       QspiSdramBridge (new)
                                │
                                ▼
                         SDRAM Controller
                         (wr / addr / din)
                                ▲
                                │
                    ┌───────────┘
                    │
              SdramTileAttributeFetch
              (rd / addr / dout)
```

### 4.2 QspiDecoder changes

Add `SDRAM_WRITE = B"8'h02"` to `Op` object.

On `cmd_valid` with `SDRAM_WRITE`:
- Latch `sdramAddr = cmd_addr(22 downto 0)` (24-bit header → 23-bit SDRAM addr)
- Latch `sdramLen = cmd_len`
- Set `activeSdramWrite = True`

On `payload_valid` with `activeSdramWrite`:
- Forward bytes to `QspiSdramBridge` via a new stream interface (e.g. `sdram_byte`, `sdram_valid`)
- Assemble 16-bit words (low byte first, high byte second) before presenting to bridge, OR present raw bytes and let bridge assemble

**Decision for artifact:** Present raw bytes to bridge; bridge assembles words and drives SDRAM controller. This keeps decoder complexity minimal.

### 4.3 QspiSdramBridge (new component)

```scala
case class QspiSdramBridge() extends Component {
  val io = new Bundle {
    // From QspiDecoder
    val byte_in  = in Bits(8 bits)
    val valid    = in Bool()
    val addr_init = in UInt(23 bits)   // latched at cmd_valid
    val len_init  = in UInt(16 bits)   // latched at cmd_valid
    val active   = in Bool()           // true during SDRAM_WRITE transaction

    // To SDRAM controller
    val sdram_wr   = out Bool()
    val sdram_addr = out UInt(23 bits)
    val sdram_din  = out Bits(8 bits)
    val sdram_busy = in Bool()

    // Status
    val upload_done = out Bool()
    val upload_busy = out Bool()
  }
}
```

**FSM:**
1. `Idle` — wait for `active`
2. `Assemble` — collect low byte, then high byte → 16-bit word
3. `WriteLo` — write low byte to SDRAM at `addr`, wait for `!busy`
4. `WriteHi` — write high byte to SDRAM at `addr+1`, wait for `!busy`
5. `Incr` — `addr += 2`, `len -= 1`; if `len == 0` → `Done`, else → `Assemble`
6. `Done` — assert `upload_done` for one cycle, return to `Idle`

### 4.4 Top-level arbitration

Current wiring in `TopTang20kHdmi` (simplified):
```scala
sdramArea.ctrl.io.rd   := pixelArea.fetch.io.sdramRd
sdramArea.ctrl.io.wr   := pixelArea.fetch.io.sdramWr
sdramArea.ctrl.io.addr := pixelArea.fetch.io.sdramAddr
sdramArea.ctrl.io.din  := pixelArea.fetch.io.sdramDin
```

**New wiring:**
```scala
val uploadWr   = qspiBridge.io.sdram_wr
val uploadAddr = qspiBridge.io.sdram_addr
val uploadDin  = qspiBridge.io.sdram_din

// Arbitration: fetch engine has priority; upload gated to blanking
val inVblank = !pixelArea.video.io.activeVideo  // or use vsync signal
val allowUpload = inVblank && uploadBridge.io.upload_busy

sdramArea.ctrl.io.rd   := pixelArea.fetch.io.sdramRd
sdramArea.ctrl.io.wr   := pixelArea.fetch.io.sdramWr || (allowUpload && uploadWr)
sdramArea.ctrl.io.addr := Mux(allowUpload, uploadAddr, pixelArea.fetch.io.sdramAddr)
sdramArea.ctrl.io.din  := Mux(allowUpload, uploadDin,  pixelArea.fetch.io.sdramDin)
```

**Open question:** Should upload be gated to vblank-only, or can it interleave with fetch reads? Artifact recommends **vblank-only for Checkpoint C** — simplest, safest, no arbitration complexity. Future tasks (Task 30) can explore interleaved or shadow-bank approaches.

### 4.5 Status integration

Extend `QspiDecoder` READ_STATUS sel=5 to include upload-done bit:

```scala
is(U(5, 8 bits)) { rxWord := B(0, 15 bits) ## upload_done ## io.status_sticky(14 downto 0) }
```

Or add `sel=6` for extended status if sel=5 is full.

**Alternative (recommended):** Add `sel=6` → `{0, upload_done, upload_busy, 0, status_sticky[13:0]}` to avoid modifying Task 35's sticky layout.

## 5. Validation Plan

### 5.1 Simulation

**`SdramUploadSim` (new):**
1. Reset, bootstrap initial tile data
2. Issue SDRAM_WRITE header targeting a known tile row address
3. Stream 16 words of test pattern
4. Issue SDRAM_READ (via fetch engine) to same address
5. Assert received data matches uploaded pattern
6. Assert no fetch-path regression (adjacent rows still valid)

**`QspiRegWriteSim` extension:**
- Add cases for SDRAM_WRITE opcode decoding (header parsing, addr/len latch)
- Verify REG_WRITE and READ_STATUS remain unaffected

### 5.2 Hardware proof

1. Build bitstream with Task 34 changes
2. Firmware: `sdram_upload(addr, data_ptr, len_words)` helper
3. Upload a small 1 KB tile row pattern during vblank
4. Trigger scene re-render (or wait for next frame)
5. Capture HDMI output showing the new pattern
6. Verify `/dev/video2` capture differs from pre-upload baseline

## 6. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| SDRAM write collides with fetch read | Visual corruption / crash | Vblank-only gating for Checkpoint C |
| 8-bit `din` vs 16-bit payload mismatch | Data misalignment | Bridge assembles bytes explicitly; verify sim |
| `last_error` persists across uploads | False error sticky | UPLOAD_CTRL write clears error latch |
| Mid-burst CS deassert | Partial write | Drop burst, set error, require host retry |
| SDRAM controller busy timing | Bridge deadlock | Timeout counter in bridge FSM; error flag |
| Address translation bug | Wrong memory region written | Sim verifies addr mapping; hardware capture confirms |

## 7. Checkpoints

- **A:** Artifact + scope lock (this document)
- **B:** HDL — `QspiSdramBridge` + decoder opcode + top-level arbitration + `SdramUploadSim`
- **C:** Hardware — upload test asset during blanking, confirm render on Tang Nano 20K

## 8. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `QspiDecoder.scala` +30 lines; `QspiSdramBridge.scala` NEW ~150 lines; `TopTang20kHdmi.scala` +20 lines; `VdpTop.scala` +5 lines (status); Firmware +100 lines; `SdramUploadSim.scala` NEW ~100 lines |
| **Hardware target** | Tang Nano 20K (Gowin GW2AR-LV18) |
| **QSPI pins** | Tang 41(SCK)/42(CS)/48(IO0)/49(IO1)/51(IO2)/54(IO3) ↔ Pico GP8/GP9/GP10/GP11/GP12/GP13 |
| **SDRAM** | Embedded 32 Mbit SDRAM via `SdramController` BlackBox |

## 9. Open Questions (for implementation to resolve)

1. **Exact vblank gate signal:** Use `!activeVideo` or dedicated `vsync`? Verify in `VdpTop` which signal is stable during the safe upload window.
2. **Sel=5 vs sel=6 for upload status:** Prefer adding sel=6 to avoid Task 35 sticky layout change, or extend sel=5 if bits remain?
3. **Upload address range validation:** Should the bridge reject addresses outside valid SDRAM regions (e.g. > 8 MB)?
4. **Word vs byte LEN:** Header LEN is in 16-bit words per protocol; verify this matches firmware streaming logic.
5. **Post-upload refresh:** Does the SDRAM controller need an explicit refresh cycle after a long write burst? Check `sdram.v` behavior.
