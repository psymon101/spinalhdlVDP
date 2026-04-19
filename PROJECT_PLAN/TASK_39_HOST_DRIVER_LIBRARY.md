# Task 39 — Host Driver Library

**Status:** Artifact phase
**depends_on:** [34, 35, 38c]
**scope_boundary:** Host-side library only. No HDL changes, no new rendering primitives, no new protocol opcodes.
**delivers:**

- `libvdp_mode0_host.{c,h}` — firmware-agnostic driver above QSPI transport
- Packet framing, register map abstraction, status polling helpers
- Asset upload protocol with burst + progress callbacks
- IRQ handling hooks
- Reusable `pio_wait_sm_idle()` drain helper

**validation:**

- Firmware builds and links against the library with zero warnings
- Hardware: library-driven upload + register write + status read cycle proves end-to-end host control

---

## 1. Goal

Unify the ad-hoc QSPI helpers scattered across `test_qspi_smoke.c` into a clean, reusable host driver library. The library encapsulates the proven transport contract (Tasks 26, 27, 38a–c, 35, 34) so that future platform adapters (Task 40) and application code don't re-implement packet framing, status polling, or vblank sync.

## 2. Scope

### 2.1 In scope

1. **Library source files** (`firmware/libvdp/`):
   - `vdp_qspi.c/h` — QSPI transport init, register write, status read, bulk upload
   - `vdp_status.c/h` — Status polling, sticky-bit helpers, IRQ wait
   - `vdp_upload.c/h` — Asset upload with vblank pacing and progress callbacks
   - `vdp_platform.h` — Pin map, clock constants, board-specific defines
2. **Refactor** existing `test_qspi_smoke.c` to link against the library (proof of reuse)
3. **Clean separation** between transport layer (PIO/bit-bang) and protocol layer (packet framing)
4. **Documented API** in header comments

### 2.2 Out of scope (deferred)

- Multi-platform abstraction (RP2350-only for now; generic MCU wrapper = future task)
- DMA-based upload (PIO TX FIFO is sufficient at 2 MHz SCK)
- USB/native CDC integration (stdio over debug probe is the current path)
- Flash/file-system asset storage (host manages its own buffers)

## 3. Architecture

### 3.1 Layer diagram

```
Application / Platform Adapter (Task 40)
   │
   ▼
+------------------+------------------+------------------+
│  vdp_upload.h    │  vdp_status.h    │  vdp_qspi.h      │
│  Asset upload    │  Status polling  │  Transport       │
│  with vblank sync│  IRQ wait        │  init/write/read │
+------------------+------------------+------------------+
│                    vdp_platform.h                      │
│              Pin map, clocks, constants                │
+--------------------------------------------------------+
│              Pico SDK (PIO, GPIO, clocks)              │
+--------------------------------------------------------+
```

### 3.2 API contract

```c
/* vdp_qspi.h */
void vdp_qspi_init(void);
void vdp_reg_write(uint32_t addr, uint16_t data);
uint32_t vdp_read_status(uint8_t sel);
void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words);

/* vdp_status.h */
bool vdp_wait_vblank(uint32_t timeout_us);
bool vdp_wait_sticky(uint8_t bit_mask, uint32_t timeout_us);
void vdp_clear_sticky(uint16_t mask);

/* vdp_upload.h */
typedef void (*vdp_upload_cb)(uint16_t words_sent, uint16_t words_total);
bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words,
                      uint16_t num_words, vdp_upload_cb cb);

/* vdp_platform.h */
#define VDP_PIN_QSPI_SCK   8
#define VDP_PIN_QSPI_CS_N  9
#define VDP_PIN_QSPI_IO0  10
#define VDP_PIN_QSPI_IO1  11
#define VDP_PIN_QSPI_IO2  12
#define VDP_PIN_QSPI_IO3  13
#define VDP_QSPI_SCK_HZ    2000000u
```

### 3.3 Key implementation notes

**Transport (`vdp_qspi.c`):**
- Reuses existing `qspi_quad.pio` program unchanged
- `vdp_qspi_init()` replaces `qspi_hw_init()` with identical behavior
- `vdp_reg_write()` replaces `reg_write_word()` — frames 8-byte packet, asserts CS, sends, deasserts
- `vdp_read_status()` replaces `qspi_read_status()` — bit-bang header, 2-edge turnaround, 4-byte response
- `vdp_sdram_write()` replaces `sdram_upload()` — frames 6-byte header + payload, pads to 4-byte boundary

**Status (`vdp_status.c`):**
- `vdp_wait_vblank()` — polls `READ_STATUS sel=5` bit 0 (RASTER_MATCH), with timeout. Clears sticky before poll.
- `vdp_wait_sticky()` — polls `sel=5` until specified bits are set, with timeout.
- `vdp_clear_sticky()` — write-1-to-clear to `0x0320`.

**Upload (`vdp_upload.c`):**
- `vdp_upload_asset()` — chunks upload into `WORDS_PER_VBLANK`-sized bursts, syncs to vblank via `vdp_wait_vblank()`, calls optional progress callback after each chunk.
- Default `WORDS_PER_VBLANK = 8` (256 µs at 2 MHz, well inside 1.4 ms vblank).
- Host buffer must remain valid for the duration of the upload (library does not copy).

## 4. Validation Plan

### 4.1 Build validation
- Library compiles with `-Wall -Wextra` zero warnings
- `test_qspi_smoke.c` refactored to use library headers — still builds

### 4.2 Hardware proof
Refactored `test_qspi_smoke.c` must reproduce all existing proofs using library calls:

| Proof | Library call sequence | Expected result |
|---|---|---|
| Magic read | `vdp_read_status(0)` | `0x51560002` |
| Layer toggle | `vdp_reg_write(0x0300, val)` | Visible layer on/off |
| Sticky status | `vdp_read_status(5)` | Bit 2 set after each write |
| Sticky clear | `vdp_clear_sticky(0x000F)` | Bits clear, re-set on next read |
| IRQ enable | `vdp_reg_write(0x0321, 0x000C)` | LED(3) tracks sticky state |
| Asset upload | `vdp_upload_asset(0x7000, payload, 32, NULL)` | Visible HDMI delta per Task 34 |
| Vblank sync | `vdp_wait_vblank(20000)` | Returns true within one frame |

### 4.3 Regression check
- All existing sim cases still PASS (library is firmware-only; no HDL change)
- Existing bitstream works without rebuild (library runs on Pico, not FPGA)

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Library API changes break existing smoke test | Build failure | Refactor smoke test in same commit; CI build check |
| `vdp_upload_asset` chunking differs from manual loop | Upload timing changes | Hardware proof reproduces Task 34 visible delta |
| Header include path complexity | Build system fragility | Simple `firmware/libvdp/` directory; CMake `target_include_directories` |
| Callback latency during upload | Miss vblank window | Callback is optional; default NULL means no host-side delay |

## 6. Checkpoints

- **A:** artifact + scope lock (this document)
- **B:** library implementation + smoke-test refactor + build validation
- **C:** hardware proof — all 7 proof rows above pass on Tang Nano 20K

## 7. Task Metadata

| Field | Value |
|---|---|
| **Estimated diff size** | `firmware/libvdp/` NEW ~300 lines; `test_qspi_smoke.c` −200 / +50 lines (refactor to use library) |
| **Hardware target** | Tang Nano 20K + Raspberry Pi Pico 2 (RP2350) |
| **Build system** | CMake (`firmware/test_qspi_smoke/CMakeLists.txt`) |
| **Dependencies** | Pico SDK, `qspi_quad.pio` (unchanged) |

## 8. Open Questions (for implementation to resolve)

1. **Library directory structure:** `firmware/libvdp/` or `firmware/lib/`? Prefer `libvdp/` for namespacing.
2. **Error handling:** Return `bool` with `vdp_last_error()` global, or return status codes? Prefer `bool` + `vdp_last_error()` for simplicity.
3. **Upload chunk size:** Hardcode `WORDS_PER_VBLANK = 8` or make it a parameter? Parameter with default 8.
4. **Status poll interval:** `busy_wait_us_32(50)` inside poll loop — acceptable or should it yield? Acceptable at 2 MHz SCK rates.
