# ESP32-P4 QSPI Host Interface — VDP Link Proof

This directory contains the ESP32-P4 firmware that drives the Tang Nano 20K VDP over a **1-1-4 quad-SPI (QSPI) transport**. It replaces the legacy ESP32-S3 i80 parallel interface for the current spinalhdlVDP implementation lane.

**Active transport (Option A / word-drain):** `QspiSlaveSync` + `QspiTransportCore` (proven at 40–80 MHz link rates; bulk SDRAM upload is sink-bound to ~8 MHz). The legacy oversampled `QspiSlave` path attempted in Option B has been retired because its quad+LEN header cannot be emitted by the ESP32-P4 GPSPI driver within the 32-bit address limit.

This document is the single consolidated reference for the P4-side host interface. It is intended both for the link-certification campaign and as the hand-off for anyone writing a P4 QSPI host driver for VdpTop.

---

## 0. Reproducible build

From this directory, with ESP-IDF v6.0.2 exported:

```bash
source "$IDF_PATH/export.sh"
idf.py set-target esp32p4
idf.py build
sha256sum build/esp32p4_qspi_proof.elf build/esp32p4_qspi_proof.bin
```

The build consumes the app-local HAM fixture at `assets/ham6_320x240_codes_words_le.bin`
and the custom `partitions.csv`; no repository-level asset path is required.

Flashing is a hardware-gated operation. Only after the Option A bitstream
hardware-ready packet and TopazCliff acknowledgement may the paired firmware
be flashed with:

```bash
idf.py -p /dev/ttyACM0 flash
```

Do not use this command against the retired legacy HAM bitstream.

---

## 1. Physical interface

| Signal | P4 GPIO | Direction | Description |
|---|---|---|---|
| `SCLK` | 21 | Host → FPGA | QSPI clock. Mode 0 (CPOL=0, CPHA=0). Link proven up to 80 MHz for register/status traffic; bulk SDRAM upload is sink-bound to ~8 MHz. |
| `CS#`  | 20 | Host → FPGA | Active-low chip-select. Async reset of the FPGA slave FSM when high. |
| `IO0`  | 32 | Bidir | MOSI / data0. Used for CMD and ADDR phases, then data. |
| `IO1`  | 33 | Bidir | MISO / data1. Used for CMD and ADDR phases, then data. |
| `IO2`  | 22 | Bidir | `quadwp` / data2. Must be configured for quad mode. |
| `IO3`  | 23 | Bidir | `quadhd` / data3. Must be configured for quad mode. |

All four `IO` pins are routed through the ESP32-P4 GPIO matrix; the QSPI mode is selected with `SPI_TRANS_MODE_QIO`. CS# is bit-banged by the driver (`spics_io_num = PIN_CS`).

### Bus initialization requirements
- Set `.data4_io_num` through `.data7_io_num = -1` in `spi_bus_config_t`. Leaving them at `0` makes the driver interpret GPIO0 as an octal lane and emit conflict warnings.
- Use `SPI_DMA_CH_AUTO` and allocate TX/RX buffers from DMA-capable memory (`MALLOC_CAP_DMA`).

---

## 2. Protocol overview

Transactions are half-duplex and CS#-framed:

1. **Command phase** — 8 bits, single-lane MSB-first.
2. **Address phase** — 24 bits, single-lane MSB-first.
3. **LEN phase** (writes only) — 2 bytes, quad-lane, little-endian. `LEN` is a **word count**.
4. **Payload phase** — quad-lane data, 2 bytes per word, high-nibble first on each `IO`. Reads have a 2-cycle dummy delay before the FPGA drives data.
5. **CS# deassert** — terminates the transaction.

### Two transports, two frames

The repo has built two different QSPI front-ends. The P4 firmware must use the frame that matches the flashed bitstream.

| Build | Status | Bitstream example | Front-end | Read transaction format |
|---|---|---|---|---|
| **Word-drain** | **Active** | `b6ffc0b` | `QspiSlaveSync` + `QspiTransportCore` | `[CMD:1][ADDR:3][DUMMY:2][RDATA:4]` — no LEN phase on reads. |
| Legacy | Retired (Option B) | `1716d09` (HAM HDMI top) | Oversampled `QspiSlave` + `QspiDecoder` | `[CMD:1][ADDR:3][LEN:2][DUMMY:2][RDATA:4]` — **LEN=0 required** on reads. |

**Active path:** the word-drain transport is being integrated into the HDMI/HAM top (Option A). It is already hardware-proven on the barebones transport top (#13938: magic, 1M loopback, 128 KiB @ 80 MHz write, all with `sel=10 = 0`).

**Retired path:** the legacy slave requires a quad-mode CMD/ADDR phase plus a 2-byte `LEN` field even on `READ_STATUS`. The ESP32-P4 GPSPI cannot emit that frame within its 32-bit address limit, so the legacy path was abandoned after the canary failure (#13963/#13966/#13969).

### Header parity

The **word-drain** bitstream optionally uses even parity over `{CMD[7:0], ADDR[22:0]}`, with the parity bit placed in `ADDR[23]`. The current P4 firmware (`s_use_header_parity = true`) matches the parity-enabled word-drain build. The **legacy** HAM bitstream has no parity support, but that path is no longer active.

```c
uint8_t parity31(uint32_t value) {
    uint8_t p = 0;
    for (int i = 0; i < 31; ++i) p ^= (value >> i) & 1;
    return p;
}

// Build header: cmd = 0x01, addr = target reg address
uint32_t header = ((uint32_t)cmd << 23) | (addr & 0x7FFFFF);
addr_field = (addr & 0x7FFFFF) | ((uint32_t)parity31(header) << 23);
```

`parity31()` is implemented in `main.c`. Use `s_use_header_parity = true` only for word-drain builds with parity enabled; use `false` for the legacy HAM bitstream. The bitstream SHA must be recorded in every proof packet.

---

## 3. Opcodes

| Opcode | Name | Direction | LEN | Payload |
|---|---|---|---|---|
| `0x01` | `REG_WRITE` | Host → FPGA | Word count (2 bytes LE) | `2 × LEN` bytes: `{lo_byte, hi_byte}` per 16-bit word. First written word goes to `addr`, then `addr+1`, etc. |
| `0x02` | `SDRAM_WRITE` | Host → FPGA | Word count (2 bytes LE) | `2 × LEN` bytes. Written to the SDRAM bridge; on the bring-up top this only lights the `everSdram` LED. Content readback requires VdpTop (`VdpTop-184`). |
| `0x04` | `READ_STATUS` | FPGA → Host | `0` | 4 bytes returned after 2 dummy cycles. `ADDR[7:0]` selects the status word. |

Note: a retired legacy front-end required a 2-byte `LEN=0` field on reads; the active word-drain front-end omits it. The byte-serial legacy protocol used the same opcodes but with separate opcode/addr/data byte phases.

---

## 4. `READ_STATUS` selection table

`READ_STATUS` returns a 32-bit word. Only the selections used by the P4 campaign are shown; diagnostic sels not wired on the bring-up top return `0`.

| Sel (`ADDR[7:0]`) | Returned value | Use |
|---|---|---|
| `0` | Magic `0x51560002` | Basic transport alive check. |
| `4` | `{24'b0, last_error[7:0]}` | Last unknown-opcode / framing error byte. Legacy front-end only; not implemented on active word-drain transport. |
| `6` | `{28'b0, upload_overflow, upload_error, upload_done, upload_busy}` | SDRAM upload bridge status. Legacy front-end only; not implemented on active word-drain transport. |
| `7` | `{1'b0, hdrErrSticky, hdrErrCount[14:0]}` | Header-parity error sticky + count. Word-drain parity build only. |
| `8` | 32-bit SDRAM word at the address armed by register writes to `0x0326` (LO) and `0x0327` (HI) | Readback-enabled word-drain bitstream `aaa0fea2`; HI write arms the one-shot read. |
| `9` | `{lastData[15:0], lastAddr[15:0]}` | Loopback: final `REG_WRITE` data and address. Used for content-exact verification. |
| `10` | `{30'b0, malformed, overflow}` | **Transport health word.** `overflow` = SCLK→`clk_sys` FIFO overflowed. `malformed` = odd-byte framing detected. **Active word-drain transport.** |

The readback-enabled word-drain bitstream (`project.fs` SHA-256 `aaa0fea2336081dfb2905246555ecc8e31d3c11528149600a56ef207ba86004a`) exposes `sel=8`. Write the low address to `0x0326`, then the high 7 address bits to `0x0327`; the high write arms a one-shot SDRAM read. Read `sel=8` after a short settling delay. The legacy HAM top used `sel=4` (last_error) and `sel=6` (upload status) instead of `sel=10`, but that path is retired.

---

## 5. Frequency / reconfigure rules

The P4 GPSPI peripheral source options are `XTAL` (40 MHz), `RC_FAST` (~20 MHz), and `SPLL` (480 MHz). There is **no `PLL_F160M`** source for GPSPI on the P4.

Because the driver applies a source pre-divider when the source is faster than 160 MHz, requesting 53.3 MHz from `SPI_CLK_SRC_SPLL` does **not** produce 53.3 MHz. Always query the actual frequency:

```c
uint32_t actual = spi_device_get_actual_freq_hz(handle);
```

The harness reconfigures the SPI device (remove + add) for each major frequency point. Per-transaction `override_freq_hz` is available in IDF v6.0.2 but adds ~30 µs overhead per transaction and does **not** update `spi_device_get_actual_freq_hz()`; use it only if you genuinely need mixed frequencies within one CS-active window.

### Recommended campaign frequencies

| Mode | Request | Typical actual | Use |
|---|---|---|---|
| Sanity | 40 MHz | 40 MHz | Baseline smoke test. |
| Register / status traffic | 40–80 MHz | 40 / 80 MHz | Fast register writes and `READ_STATUS` polls. |
| SDRAM_WRITE bulk upload | ~8 MHz | ~8 MHz | **SDRAM-sink bound**, not link bound. The `QspiSdramBridge` accepts one byte per SDRAM write command; sustained throughput above ~8 MHz overflows the ingress FIFO and trips `sel=10` `overflow=1`. |
| Stress | 60 MHz or 48 MHz | exact | Use exact SPLL divisors; avoid 53.333 MHz. |

---

## 6. P4-specific gotchas

1. **Active frame is word-drain.** The retired legacy path required a quad-mode CMD/ADDR plus a `LEN=0` phase on reads that the P4 GPSPI cannot emit. The active word-drain frame is `[CMD:1][ADDR:3][DUMMY:2][RDATA:4]` with no LEN on reads. See §2.1.
2. **`address_bits` cap.** The GPSPI `usr_addr_bitlen` field is 5 bits wide, so the maximum usable `address_bits` is 32. The protocol therefore uses a 24-bit address field, not the 40-bit header attempted earlier.
3. **No `SPI_CLK_SRC_PLL_F160M`.** Use `SPI_CLK_SRC_SPLL` and accept/verify the actual frequency.
4. **DMA alignment.** DMA buffers must be in `MALLOC_CAP_DMA` memory. CyanPeak notes 64-byte alignment is advisable due to P4 cache-line size.
5. **`dummy_bits = 2`** is required for `READ_STATUS` to give the FPGA time to load the response shifter.
6. **CS# setup/hold.** Use `cs_ena_pretrans = 2` and `cs_ena_posttrans = 2` to meet the async-reset slave timing.
7. **`data4–7 = -1`.** Always initialize the unused octal pins to `-1` in `spi_bus_config_t` to avoid GPIO0 conflict warnings.
8. **Bulk upload is SDRAM-sink-bound (~8 MHz), not link-bound.** Do not run sustained `SDRAM_WRITE` above ~8 MHz even though the link itself is clean at 80 MHz; the bridge FIFO will overflow. Register/status traffic may run at 40–80 MHz.
9. **Parity bit must match the bitstream.** The active parity-enabled word-drain build uses `ADDR[23]` parity. Set `s_use_header_parity = true` for that build.

---

## 7. P4 vs. ESP32-S3 i80 migration notes

| Item | ESP32-S3 i80 (legacy) | ESP32-P4 QSPI (current) |
|---|---|---|
| Physical bus | 8-bit parallel + WR#/RD#/DC# | 4-bit quad SPI + SCLK + CS# |
| Register write framing | Opcode byte + addr byte + data byte(s) | 24-bit header (CMD+ADDR+parity) + LEN + payload words |
| Register read | i80 read strobe + 16-bit data | `READ_STATUS` with `sel` |
| Status polling | `vdp_status_read()` via i80 | `READ_STATUS` `sel=10` for transport health, `sel=9` for loopback. (`sel=4`/`sel=6` were used only by the retired legacy front-end.) |
| Max header address | Full 16-bit register space | 22-bit address embedded in 24-bit header (sufficient for current register map); 24-bit address phase hard-limited by P4 GPSPI `usr_addr_bitlen` width. |
| Large uploads | i80 burst writes | `SDRAM_WRITE` quad-lane bursts, up to the P4 GPSPI transaction-length limit |
| Host library | `libvdp/vdp_host.c` | Raw QSPI transactions in `main.c`; `libvdp` P4 facade not yet implemented |

---

## 8. HAM6 link-closure test procedure

This is the concrete test that closes the **QSPI transport link section** using the VDP's HAM6 video mode.

### Goal
Prove that the P4 QSPI link can reliably configure VdpTop registers and upload a large, known HAM6 frame to SDRAM while maintaining `READ_STATUS sel=10 == 0x00000000` and producing a recognizable HDMI output.

### Prerequisites
- P4 QSPI firmware built with the watchdog fix (`vTaskDelay(1)` or `pdMS_TO_TICKS(10)`; see #13929).
- HAM-enabled bitstream (`TopTang20kHdmi` + `QspiTransportCore` + `QspiSlaveSync` + `VdpTop` + `HamDecoder`) flashed. The active build uses the word-drain front-end. SHA recorded.
- Reference HAM6 image converted to VDP byte-plane and C array (e.g. `scripts/assets/png_to_vdp_assets.py`).

### Register sequence (P4 QSPI REG_WRITE) — word-drain transport

All writes are `REG_WRITE` transactions, LEN = word count, data little-endian per word.
The example below targets a **320×240 HAM6 image** uploaded to SDRAM base `0x100000`.
The VDP fetch engine halves the display X/Y coordinates in HAM6 mode, so the
320×240 source automatically fills the 640×480 active area. Leave `SCALE_CTRL`
(`0x0349`) at its reset value of `0x0000`.

Register and status traffic can run at **40–80 MHz**. The `SDRAM_WRITE` bulk
upload must run at the SDRAM-safe rate of **~8 MHz** to avoid overflowing the
`QspiSdramBridge` ingress FIFO.

The ESP32-P4 GPSPI master also limits each QIO TX data phase to **32,767
bytes** (`SPI_MS_DATA_BITLEN`, 18 bits). `SDRAM_WRITE` consumes a 2-byte
little-endian word-count prefix, and its payload must contain an even number
of bytes, so split larger uploads into payload chunks of at most **32,760
bytes**. The 76,800-byte HAM6 plane therefore uses three consecutive writes:
32,760 bytes at `0x100000`, 32,760 bytes at `0x107FF8`, and 11,280 bytes at
`0x10FFF0`.

```c
// Helper: single 16-bit REG_WRITE (LEN = 1, so no address auto-increment).
static void vdp_reg_write(uint32_t reg_addr, uint16_t value) {
    qspi_reg_write(reg_addr, (const uint8_t *)&value, 1, QSPI_CLOCK_HZ);
}

// Helper: burst N 16-bit words starting at reg_addr (address auto-increments).
static void vdp_reg_write_burst(uint32_t reg_addr, const uint16_t *data, uint16_t words) {
    qspi_reg_write(reg_addr, (const uint8_t *)data, words, QSPI_CLOCK_HZ);
}

// 1. Native Mode0 adapter (default, but make it explicit).
vdp_reg_write(0x0313, 0x0000);          // MODE_SELECT = Native Mode0

// 2. Logical canvas size.
vdp_reg_write(0x034A, 640);             // LOGIC_WIDTH
vdp_reg_write(0x034B, 480);             // LOGIC_HEIGHT

// 3. Bitmap layer: HAM6, base 0x100000, stride 320, height 240.
vdp_reg_write(0x0351, 0x0000);          // BITMAP_BASE_LO
vdp_reg_write(0x0352, 0x0010);          // BITMAP_BASE_HI -> 0x100000
vdp_reg_write(0x0353, 0x0000);          // ATTR_BASE_LO   (don't-care for HAM6)
vdp_reg_write(0x0354, 0x0020);          // ATTR_BASE_HI   -> 0x200000, non-overlapping
vdp_reg_write(0x0355, 320);             // BITMAP_STRIDE  (source bytes per row)
vdp_reg_write(0x0357, 240);             // BITMAP_HEIGHT
vdp_reg_write(0x0350, 0x0007);          // BITMAP_CTRL = enable + BPP=0b11 (HAM6)

// 4. Linestate: enable L0 on every display line. 480 entries × 16 bits at 0x0000.
{
    uint16_t linestate[480];
    for (int i = 0; i < 480; ++i) linestate[i] = 0x0800; // L0 enable
    vdp_reg_write_burst(0x0000, linestate, 480);
}

// 5. Load 16 HAM6 base colours into palette[0..15].
//    Palette memory is 24-bit R8G8B8.  Each entry needs two single writes to
//    0x0600 (low half = G:B, high half = R).  Do NOT use a REG_WRITE burst:
//    the QSPI REG_WRITE address auto-increments per word, so a burst to 0x0600
//    would hit 0x0601 (PALETTE_PTR) and corrupt the pointer.
static const uint32_t ham_palette[16] = {
    0x000000, 0xFF0000, 0x00FF00, 0xFFFF00,
    0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    0x800000, 0x008000, 0x000080, 0x808000,
    0x800080, 0x008080, 0xC0C0C0, 0x404040,
};
vdp_reg_write(0x0601, 0);               // PALETTE_PTR = 0
for (int i = 0; i < 16; ++i) {
    uint8_t r = (ham_palette[i] >> 16) & 0xFF;
    uint8_t g = (ham_palette[i] >>  8) & 0xFF;
    uint8_t b = (ham_palette[i]      ) & 0xFF;
    vdp_reg_write(0x0600, (g << 8) | b); // low half (auto-increments pointer)
    vdp_reg_write(0x0600, r);            // high half (commits entry)
}

// 6. Global layer enable: L0 on.
vdp_reg_write(0x0300, 0x0001);          // LAYER_ENABLE

// 7. Upload the HAM6 byte plane via SDRAM_WRITE.
//    LEN = bytes / 2.  For 320×240 = 76800 bytes = 38400 words.
//    Run at the SDRAM-safe rate (~8 MHz), NOT the link rate.
qspi_sdram_write(0x100000, ham6_plane, 38400, QSPI_UPLOAD_CLOCK_HZ);

// 8. Poll transport health after every phase.
//    The active word-drain transport implements sel=10.
uint32_t health = qspi_read_status(10);
assert(health == 0x00000000);           // overflow=0, malformed=0
```

**Key register addresses used above**

| Register | Address | Value | Meaning |
|---|---|---|---|
| `MODE_SELECT` | `0x0313` | `0x0000` | Native Mode0 adapter |
| `LOGIC_WIDTH` | `0x034A` | `640` | Logical canvas width |
| `LOGIC_HEIGHT` | `0x034B` | `480` | Logical canvas height |
| `BITMAP_BASE_LO` | `0x0351` | `0x0000` | Low 16 bits of bitmap base |
| `BITMAP_BASE_HI` | `0x0352` | `0x0010` | High bits → effective base `0x100000` |
| `ATTR_BASE_LO` | `0x0353` | `0x0000` | Don't-care for HAM6 |
| `ATTR_BASE_HI` | `0x0354` | `0x0020` | Non-overlapping with bitmap |
| `BITMAP_STRIDE` | `0x0355` | `320` | Source bytes per row |
| `BITMAP_HEIGHT` | `0x0357` | `240` | Source rows |
| `BITMAP_CTRL` | `0x0350` | `0x0007` | Enable + `BPP=0b11` (HAM6) |
| linestate table | `0x0000..0x01DF` | `0x0800` | L0 enabled per line |
| `PALETTE_PTR` | `0x0601` | `entry * 2` | Half-pointer into palette RAM |
| `PALETTE_DATA` | `0x0600` | half-word | Auto-incrementing palette half-write |
| `LAYER_ENABLE` | `0x0300` | `0x0001` | Global L0 enable |

**Geometry verification notes**

- `0x034B LOGIC_HEIGHT` is decoded at `VdpTop.scala:565`.
- `BITMAP_CTRL` bits[6:3] (`cellWidth` log2) default to `0` and are not consumed by the current RTL (`VdpTop.scala:939`; no `cellWidth` usage in `BitmapRowFetch` or `VdpTop`), so `0x0007` is safe.
- The 320→640 and 240→480 stretch is performed by the HAM fetch path: `BitmapRowFetch.scala:458` halves `pendingLine`, and the HAM/byte address path halves the horizontal column. No `SCALE_CTRL` change is required.

### Pass/fail criteria (word-drain transport)

| Check | Pass condition |
|---|---|
| Magic / transport alive | `READ_STATUS sel=0` == `0x51560002` at the start of the test. |
| Transport health | `READ_STATUS sel=10` == `0x00000000` after register config and after upload. The firmware selector is `0x0A`; `sel=6` is legacy-only. |
| Content sanity | `READ_STATUS sel=9` after the final `REG_WRITE` returns the expected `{lastData, lastAddr}` if a loopback register was written. |
| Visual output | HDMI capture matches the reference HAM6 image within agreed tolerance (e.g. no macroscopic color corruption, no tearing). |
| No watchdog resets | P4 log shows no `Task watchdog got triggered` messages. |

### Readback proof on the current bitstream
- `READ_STATUS sel=8` is part of the current readback-enabled proof surface. Compare returned words against the exact uploaded bitmap/attribute bytes at upper and lower rows; the high-address write arms the one-shot SDRAM read.
- The indexed proof performs this readback while fetch is disabled (`BITMAP_CTRL=0x0002`, layer enable `0`), before line-state and visible-layer enable. This is discriminator #1 for separating upload corruption from a display-fetch interaction.
- `READ_STATUS sel=4` and `sel=6`: these are legacy-front-end diagnostics; the active word-drain transport uses `sel=10` for health.

### Proof packet
Record in `PROJECT_PLAN/STATUS.md` and the closeout mail:
- Bitstream file + SHA-256 confirming the **word-drain** front-end (`QspiTransportCore` + `QspiSlaveSync`).
- Firmware ELF file + SHA-256, plus the `s_use_header_parity` value.
- P4 campaign log path.
- Final `sel=10` raw value (must be `0x00000000`) after register config and after upload.
- HDMI capture file or photo hash.

---

## 9. Files in this directory

| File | Purpose |
|---|---|
| `main.c` | P4 QSPI master harness: bus/device init, register/SDRAM write helpers, frequency reconfigure, campaign loops, status readout. |
| `main/CMakeLists.txt` | Component registration and embedded HAM fixture. |
| `CMakeLists.txt` | ESP-IDF project entry point. |
| `sdkconfig.defaults` | ESP32-P4 defaults, console, and custom partition selection. |
| `partitions.csv` | Reproducible 32 MB flash layout. |
| `assets/ham6_320x240_codes_words_le.bin` | Embedded 320x240 HAM6 upload fixture. |
| `README.md` | This host-interface reference. |

---

## 10. References

- `PROJECT_PLAN/MODE0_SPEC.md` — VDP Mode 0 architecture and register map.
- `PROJECT_PLAN/archive/MODE0_REGISTER_BUS_SPEC.md` — Detailed register descriptions including `BITMAP_CTRL`, `BITMAP_BASE`, palette layout, and HAM6 notes.
- `.worktrees/native-640-firmware/VDP_PROGRAMMING_GUIDE.md` §14 — Legacy HAM6 programming walkthrough (same VDP core, different host transport).
- `hw/spinal/spinalhdlvdp/QspiTransportCore.scala` — RTL for the word-drain QSPI transport core.
- `hw/spinal/spinalhdlvdp/QspiSlaveSync.scala` — SCLK-domain slave front-end (word-drain, no LEN on reads).
- `hw/spinal/spinalhdlvdp/QspiSlave.scala` — Oversampled pixel-clock slave front-end (legacy HAM top, requires LEN on reads).
- `hw/spinal/spinalhdlvdp/QspiDecoder.scala` — Command decoder and `READ_STATUS` response FSM.
