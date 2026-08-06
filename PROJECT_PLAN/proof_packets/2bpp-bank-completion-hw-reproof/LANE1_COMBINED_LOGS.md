# Lane 1 magic-anomaly combined log bundle

Generated: 2026-08-01
Bitstream: project_a5a047a2_bankcompletion.fs
Bitstream SHA-256: a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c

## Artifact index

| Artifact | Path | Note |
|---|---|---|
| Campaign cycle 01 summary | hardware/CAMPAIGN_CYCLE_01.md | First 0x22222222 failure |
| CS#-high idle diagnostic result | hardware/CS_IDLE_RESULTS.md | Mode-9 pass |
| Post-init CS# probe build | firmware/CS_POST_INIT_PROBE_BUILD.md | Mode-0 probe firmware 48ce715a |
| Cycle 01 serial | firmware/cycle_01_serial.log | 7a93cfc1, magic=0x22222222 |
| Settled cycle 01 serial | firmware/settled_cycle_01_serial.log | Old checkerboard image, unrelated |
| CS idle serial | firmware/cs_idle_serial.log | 08ee736a, mode-9 pass |
| CS post-init serial | firmware/cs_post_init_probe_serial.log | 48ce715a, mode-0 pass |
| Loader log cycle 01 | hardware/cycle_01_openfpgaloader.log | Binary, see below |
| Loader log settled | hardware/settled_cycle_01_openfpgaloader.log | Binary, see below |
| Hashes | hashes.sha256 | All artifact hashes |

---

## hashes.sha256
```
dc4a06edb0294acf62eef428b7f628f3ce9d4d6048a84a7946a7e06c2ca0cbb6  firmware/esp32p4_scaler_proof/main/main.c
4ffa999762818835bbc3043b54aeeb039cbe2e3bbc133519e248ccbfd226cfea4  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.elf
9f7c9645e9eea548414cabbe9351cd2aa123db2c4d34ca3f59a8087dacd61c0f  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.bin
fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17  firmware/esp32p4_scaler_proof/build/partition_table/partition-table.bin
3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55  firmware/esp32p4_scaler_proof/build/bootloader/bootloader.bin
a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c  fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs
e3f8000d3b4cb778249888b7b6bf8510ad3a386a823c86a4b8f68457a21a9a91  firmware/cs_idle_serial.log
5d94cc3d24d7cba9427f30f55b4416efe5fccd54e63fb55177882536d4de66a4  firmware/esp32p4_scaler_proof/main/main.c
2eadbe69dccca5325ca8499c71ca433a17f8c0a3b17f7c605f2525a712d0338c  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.elf
ceaeed136ecba097e2fe1acedec903fa8c8804d2e33cd4dd78c22515ba3bf440  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.bin
854fdd503c4359e0a9f89ce9dc5d251b0974c82398a2e2a10635461a7e13b32b  firmware/cs_post_init_probe_serial.log
```

## hardware/CAMPAIGN_CYCLE_01.md

```markdown
# Lane 1 reproof campaign — cycle 01 blocker

Date: 2026-08-01  
Assignment: TopazCliff #14605  
Campaign runner: `run_ten_cycles.sh`, commit `38e00925`  
Firmware source: `7a93cfc1`  
Bitstream: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`

The first PM-authorized campaign cycle was stopped at the mandatory magic
precondition. The explicit SRAM load completed successfully and the firmware
logged the mandatory CS#-high pre-flight (`GPIO20 level=1`, 1200 ms). However,
the first magic read again returned `0x22222222` rather than `0x51560002`.

Evidence:

- Serial: `firmware/cycle_01_serial.log`, SHA-256
  `54ac6f38762a1b351f5abb4a3982141d69fbc8e0261d85e9288cf8b2bcd2e171`.
- SRAM loader: `hardware/cycle_01_openfpgaloader.log`, SHA-256
  `f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b`.

The same serial capture continued to show clean health and content checks
after the wrong magic (`HEALTH_BEFORE_UPLOAD`, `HEALTH_AFTER_UPLOAD`, and
`HEALTH_AFTER_ENABLE` all `raw=0x00000000`; six `READBACK PASS` lines; final
`SCALER_PROOF mode=0 pass=1`). These later checks are not valid campaign proof
because the first magic precondition failed.

Per #14605, no second cycle was attempted. The ten-cycle gate remains open and
the lane is escalated to TopazCliff/BrightForge for review.

— BronzeGate
```

## hardware/CS_IDLE_RESULTS.md

```markdown
# CS#-high QSPI reset diagnostic result

Date: 2026-08-01  
Board: Tang Nano 20K + ESP32-P4 v1.3  
Serial: `/dev/ttyACM0`  
Bitstream: `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs`  
Bitstream SHA-256: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`  
Firmware source commit: `08ee736ae35b62cb3e9257487110ddc73394ac92`

Procedure:

1. Loaded the preserved `a5a047a2` SRAM bitstream with
   `openFPGALoader --board tangnano20k --bitstream ...project_a5a047a2_bankcompletion.fs`.
2. Flashed the committed `SCALER_PROOF_MODE=9` image with `idf.py -p
   /dev/ttyACM0 flash`; all three writes verified.
3. Reset the ESP32-P4. The proof application drove GPIO20 high before
   `vdp_host_init()`, held it high for 1200 ms, then performed the first magic
   read and immediate transport-health read.

Serial evidence: `firmware/cs_idle_serial.log`, SHA-256
`e3f8000d3b4cb778249888b7b6bf8510ad3a386a823c86a4b8f68457a21a9a91`.

Observed result:

```text
CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
scaler proof mode=9 magic=0x51560002
CS_IDLE_PROOF magic_ok=1 health_raw=0x00000000 health_ok=1
CS_IDLE_PROOF_RESULT pass=1
```

The CS#-high pre-flight diagnostic passed on the previously failing
`a5a047a2` SRAM-loaded bitstream. This confirms the CS# reset hypothesis for
this reproduction and does not by itself authorize the ten-cycle reproof.

— BronzeGate
```

## firmware/CS_POST_INIT_PROBE_BUILD.md

```markdown
# Post-`vdp_host_init()` CS# probe

Date: 2026-08-01  
Assignment: TopazCliff #14610  
Source commit: `48ce715a`  
Proof mode: `SCALER_PROOF_MODE=0`

The proof image samples GPIO20 after `vdp_host_init()` returns and immediately
before the first `READ_STATUS` transaction. It also logs the configured P4 SPI
CS parameters. This is proof-only application instrumentation; no production
transport framing, register, command, or RTL was changed.

Build environment: ESP-IDF v6.0.2, ESP32-P4 v1.3, esptool v5.3.1.

Build and flash commands:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=0 idf.py build
idf.py -p /dev/ttyACM0 flash
```

Build result: PASS; partition-size check PASS. Flash result: PASS; bootloader,
partition table, and application writes each reported `Hash of data verified`.

Artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `main.c` | `5d94cc3d24d7cba9427f30f55b4416efe5fccd54e63fb55177882536d4de66a4` |
| `esp32p4_scaler_proof.elf` | `2eadbe69dccca5325ca8499c71ca433a17f8c0a3b17f7c605f2525a712d0338c` |
| `esp32p4_scaler_proof.bin` | `ceaeed136ecba097e2fe1acedec903fa8c8804d2e33cd4dd78c22515ba3bf440` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |
| `bootloader.bin` | `3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55` |
| `cs_post_init_probe_serial.log` | `854fdd503c4359e0a9f89ce9dc5d251b0974c82398a2e2a10635461a7e13b32b` |

The current FPGA was not reconfigured for this firmware-only discriminator;
campaign cycle 2 was not started.

— BronzeGate
```

## firmware/cycle_01_serial.log

```text
ESP-ROM:esp32p4-eco2-20240710
Build:Jul 10 2024
rst:0x1 (POWERON),boot:0x10f (SPI_FAST_FLASH_BOOT)
SPI mode:DIO, clock div:1
load:0x4ff33ce0,len:0x15e0
load:0x4ff28ed0,len:0xe54
load:0x4ff2bbd0,len:0x35dc
entry 0x4ff28eda
I (25) boot: ESP-IDF v6.0.2 2nd stage bootloader
I (26) boot: compile time Jul 27 2026 22:15:56
I (26) boot: Multicore bootloader
I (27) boot: chip revision: v1.3
I (29) boot: efuse block revision: v0.3
I (32) boot.esp32p4: SPI Speed      : 80MHz
I (36) boot.esp32p4: SPI Mode       : DIO
I (40) boot.esp32p4: SPI Flash Size : 32MB
I (44) boot: Enabling RNG early entropy source...
I (48) boot: Partition Table:
I (51) boot: ## Label            Usage          Type ST Offset   Length
I (57) boot:  0 nvs              WiFi data        01 02 00009000 00006000
I (64) boot:  1 phy_init         RF data          01 01 0000f000 00001000
I (70) boot:  2 factory          factory app      00 00 00010000 00400000
I (78) boot: End of partition table
I (80) esp_image: segment 0: paddr=00010020 vaddr=40020020 size=0b748h ( 46920) map
I (96) esp_image: segment 1: paddr=0001b770 vaddr=30100000 size=00088h (   136) load
I (98) esp_image: segment 2: paddr=0001b800 vaddr=4ff00000 size=04818h ( 18456) load
I (107) esp_image: segment 3: paddr=00020020 vaddr=40000020 size=1ae34h (110132) map
I (129) esp_image: segment 4: paddr=0003ae5c vaddr=4ff04818 size=0b4f8h ( 46328) load
I (139) esp_image: segment 5: paddr=0004635c vaddr=4ff0fd80 size=02790h ( 10128) load
I (146) boot: Loaded app from partition at offset 0x10000
I (147) boot: Disabling RNG early entropy source...
I (159) cpu_start: Multicore app
I (168) cpu_start: GPIO 38 and 37 are used as console UART I/O pins
I (168) cpu_start: Pro cpu start user code
I (168) cpu_start: cpu freq: 360000000 Hz
I (170) app_init: Application information:
I (174) app_init: Project name:     esp32p4_scaler_proof
I (179) app_init: App version:      v0.2.0-90-g7a93cfc1
I (184) app_init: Compile time:     Aug  1 2026 17:01:32
I (189) app_init: ELF file SHA256:  096480390...
I (193) app_init: ESP-IDF:          v6.0.2
I (197) efuse_init: Min chip rev:     v1.0
I (201) efuse_init: Max chip rev:     v1.99 
I (205) efuse_init: Chip rev:         v1.3
I (209) heap_init: Initializing. RAM available for dynamic allocation:
I (215) heap_init: At 4FF22DB0 len 00018210 (96 KiB): RETENT_RAM
I (221) heap_init: At 4FF3AFC0 len 00004BF0 (18 KiB): RAM
I (226) heap_init: At 4FF40000 len 00060000 (384 KiB): RAM
I (231) heap_init: At 50108080 len 00007F80 (31 KiB): RTCRAM
I (236) heap_init: At 30100088 len 00001F78 (7 KiB): SPM
I (242) spi_flash: detected chip: gd
I (245) spi_flash: flash io: dio
I (248) sleep_gpio: Configure to isolate all GPIO pins in sleep state
I (254) sleep_gpio: Enable automatic switching of GPIO sleep configuration
I (261) main_task: Started on CPU0
I (291) main_task: Calling app_main()
I (291) p4_scaler_proof: CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
I (1491) p4_scaler_proof: scaler proof mode=0 magic=0x22222222
I (1491) p4_scaler_proof: scale=1x explicit logic=640x480 ctrl=0x00
I (1491) p4_scaler_proof: HEALTH_BEFORE_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (1531) p4_scaler_proof: bitmap uploaded bytes=30720 clock=4000000
I (1561) p4_scaler_proof: attr uploaded bytes=30720 clock=4000000
I (1561) p4_scaler_proof: HEALTH_AFTER_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (1571) p4_scaler_proof: READBACK PASS addr=0x100000 value=0x00000000
I (1571) p4_scaler_proof: READBACK PASS addr=0x100008 value=0x55555555
I (1581) p4_scaler_proof: READBACK PASS addr=0x100010 value=0x00000000
I (1581) p4_scaler_proof: READBACK PASS addr=0x101000 value=0x55555555
I (1591) p4_scaler_proof: READBACK PASS addr=0x106400 value=0x00000000
I (1601) p4_scaler_proof: READBACK PASS addr=0x106480 value=0x00000000
I (1601) p4_scaler_proof: LINESTATE PASS lines=480 chunks=2
I (1611) p4_scaler_proof: HEALTH_AFTER_ENABLE raw=0x00000000 overflow=0 malformed=0
I (1621) p4_scaler_proof: SCALER_PROOF mode=0 pass=1
```

## firmware/settled_cycle_01_serial.log

```text
ESP-ROM:esp32p4-eco2-20240710
Build:Jul 10 2024
rst:0x1 (POWERON),boot:0x10f (SPI_FAST_FLASH_BOOT)
SPI mode:DIO, clock div:1
load:0x4ff33ce0,len:0x15e0
load:0x4ff28ed0,len:0xe54
load:0x4ff2bbd0,len:0x35dc
entry 0x4ff28eda
I (25) boot: ESP-IDF v6.0.2 2nd stage bootloader
I (26) boot: compile time Jul 26 2026 15:48:56
I (26) boot: Multicore bootloader
I (27) boot: chip revision: v1.3
I (29) boot: efuse block revision: v0.3
I (32) boot.esp32p4: SPI Speed      : 80MHz
I (36) boot.esp32p4: SPI Mode       : DIO
I (40) boot.esp32p4: SPI Flash Size : 32MB
I (44) boot: Enabling RNG early entropy source...
I (48) boot: Partition Table:
I (51) boot: ## Label            Usage          Type ST Offset   Length
I (57) boot:  0 nvs              WiFi data        01 02 00009000 00006000
I (64) boot:  1 phy_init         RF data          01 01 0000f000 00001000
I (70) boot:  2 factory          factory app      00 00 00010000 00400000
I (78) boot: End of partition table
I (80) esp_image: segment 0: paddr=00010020 vaddr=40020020 size=0c218h ( 49688) map
I (97) esp_image: segment 1: paddr=0001c240 vaddr=30100000 size=00088h (   136) load
I (99) esp_image: segment 2: paddr=0001c2d0 vaddr=4ff00000 size=03d48h ( 15688) load
I (106) esp_image: segment 3: paddr=00020020 vaddr=40000020 size=1b01ch (110620) map
I (129) esp_image: segment 4: paddr=0003b044 vaddr=4ff03d48 size=0befch ( 48892) load
I (140) esp_image: segment 5: paddr=00046f48 vaddr=4ff0fc80 size=02790h ( 10128) load
I (147) boot: Loaded app from partition at offset 0x10000
I (147) boot: Disabling RNG early entropy source...
I (159) cpu_start: Multicore app
I (168) cpu_start: GPIO 38 and 37 are used as console UART I/O pins
I (169) cpu_start: Pro cpu start user code
I (169) cpu_start: cpu freq: 360000000 Hz
I (171) app_init: Application information:
I (174) app_init: Project name:     esp32p4_checkerboard
I (179) app_init: App version:      f735334
I (183) app_init: Compile time:     Jul 26 2026 15:48:49
I (188) app_init: ELF file SHA256:  be6bbc001...
I (193) app_init: ESP-IDF:          v6.0.2
I (197) efuse_init: Min chip rev:     v1.0
I (200) efuse_init: Max chip rev:     v1.99 
I (204) efuse_init: Chip rev:         v1.3
I (208) heap_init: Initializing. RAM available for dynamic allocation:
I (215) heap_init: At 4FF22CB0 len 00018310 (96 KiB): RETENT_RAM
I (220) heap_init: At 4FF3AFC0 len 00004BF0 (18 KiB): RAM
I (225) heap_init: At 4FF40000 len 00060000 (384 KiB): RAM
I (231) heap_init: At 50108080 len 00007F80 (31 KiB): RTCRAM
I (236) heap_init: At 30100088 len 00001F78 (7 KiB): SPM
I (242) spi_flash: detected chip: gd
I (244) spi_flash: flash io: dio
I (248) sleep_gpio: Configure to isolate all GPIO pins in sleep state
I (254) sleep_gpio: Enable automatic switching of GPIO sleep configuration
I (260) main_task: Started on CPU0
I (290) main_task: Calling app_main()
I (290) p4_checkerboard: ESP32-P4 checkerboard test starting
E (290) p4_checkerboard: magic mismatch got=0x22222222 expect=0x51560002
I (290) main_task: Returned from app_main()
```

## firmware/cs_idle_serial.log

```text
CAPTURE_BEGIN
ESP-ROM:esp32p4-eco2-20240710
Build:Jul 10 2024
rst:0x1 (POWERON),boot:0x10f (SPI_FAST_FLASH_BOOT)
SPI mode:DIO, clock div:1
load:0x4ff33ce0,len:0x15e0
load:0x4ff28ed0,len:0xe54
load:0x4ff2bbd0,len:0x35dc
entry 0x4ff28eda
I (25) boot: ESP-IDF v6.0.2 2nd stage bootloader
I (26) boot: compile time Jul 27 2026 22:15:56
I (26) boot: Multicore bootloader
I (27) boot: chip revision: v1.3
I (29) boot: efuse block revision: v0.3
I (32) boot.esp32p4: SPI Speed      : 80MHz
I (36) boot.esp32p4: SPI Mode       : DIO
I (40) boot.esp32p4: SPI Flash Size : 32MB
I (44) boot: Enabling RNG early entropy source...
I (48) boot: Partition Table:
I (51) boot: ## Label            Usage          Type ST Offset   Length
I (57) boot:  0 nvs              WiFi data        01 02 00009000 00006000
I (64) boot:  1 phy_init         RF data          01 01 0000f000 00001000
I (70) boot:  2 factory          factory app      00 00 00010000 00400000
I (78) boot: End of partition table
I (80) esp_image: segment 0: paddr=00010020 vaddr=40020020 size=0b470h ( 46192) map
I (96) esp_image: segment 1: paddr=0001b498 vaddr=30100000 size=00088h (   136) load
I (98) esp_image: segment 2: paddr=0001b528 vaddr=4ff00000 size=04af0h ( 19184) load
I (107) esp_image: segment 3: paddr=00020020 vaddr=40000020 size=19ffch (106492) map
I (129) esp_image: segment 4: paddr=0003a024 vaddr=4ff04af0 size=0af94h ( 44948) load
I (139) esp_image: segment 5: paddr=00044fc0 vaddr=4ff0fb00 size=02790h ( 10128) load
I (146) boot: Loaded app from partition at offset 0x10000
I (146) boot: Disabling RNG early entropy source...
I (158) cpu_start: Multicore app
I (167) cpu_start: GPIO 38 and 37 are used as console UART I/O pins
I (167) cpu_start: Pro cpu start user code
I (167) cpu_start: cpu freq: 360000000 Hz
I (169) app_init: Application information:
I (173) app_init: Project name:     esp32p4_scaler_proof
I (178) app_init: App version:      v0.2.0-87-g08ee736a
I (183) app_init: Compile time:     Aug  1 2026 16:09:35
I (188) app_init: ELF file SHA256:  4ffa99976...
I (192) app_init: ESP-IDF:          v6.0.2
I (196) efuse_init: Min chip rev:     v1.0
I (200) efuse_init: Max chip rev:     v1.99 
I (204) efuse_init: Chip rev:         v1.3
I (208) heap_init: Initializing. RAM available for dynamic allocation:
I (214) heap_init: At 4FF13B30 len 00027490 (157 KiB): RETENT_RAM
I (220) heap_init: At 4FF3AFC0 len 00004BF0 (18 KiB): RAM
I (225) heap_init: At 4FF40000 len 00060000 (384 KiB): RAM
I (230) heap_init: At 50108080 len 00007F80 (31 KiB): RTCRAM
I (235) heap_init: At 30100088 len 00001F78 (7 KiB): SPM
I (241) spi_flash: detected chip: gd
I (244) spi_flash: flash io: dio
I (247) sleep_gpio: Configure to isolate all GPIO pins in sleep state
I (253) sleep_gpio: Enable automatic switching of GPIO sleep configuration
I (260) main_task: Started on CPU0
I (290) main_task: Calling app_main()
I (290) p4_scaler_proof: CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
I (1490) p4_scaler_proof: scaler proof mode=9 magic=0x51560002
I (1490) p4_scaler_proof: CS_IDLE_PROOF magic_ok=1 health_raw=0x00000000 health_ok=1
I (1490) p4_scaler_proof: CS_IDLE_PROOF_RESULT pass=1
CAPTURE_END
```

## firmware/cs_post_init_probe_serial.log

```text
ESP-ROM:esp32p4-eco2-20240710
Build:Jul 10 2024
rst:0x1 (POWERON),boot:0x10f (SPI_FAST_FLASH_BOOT)
SPI mode:DIO, clock div:1
load:0x4ff33ce0,len:0x15e0
load:0x4ff28ed0,len:0xe54
load:0x4ff2bbd0,len:0x35dc
entry 0x4ff28eda
I (25) boot: ESP-IDF v6.0.2 2nd stage bootloader
I (26) boot: compile time Jul 27 2026 22:15:56
I (26) boot: Multicore bootloader
I (27) boot: chip revision: v1.3
I (29) boot: efuse block revision: v0.3
I (32) boot.esp32p4: SPI Speed      : 80MHz
I (36) boot.esp32p4: SPI Mode       : DIO
I (40) boot.esp32p4: SPI Flash Size : 32MB
I (44) boot: Enabling RNG early entropy source...
I (48) boot: Partition Table:
I (51) boot: ## Label            Usage          Type ST Offset   Length
I (57) boot:  0 nvs              WiFi data        01 02 00009000 00006000
I (64) boot:  1 phy_init         RF data          01 01 0000f000 00001000
I (70) boot:  2 factory          factory app      00 00 00010000 00400000
I (78) boot: End of partition table
I (80) esp_image: segment 0: paddr=00010020 vaddr=40020020 size=0b800h ( 47104) map
I (96) esp_image: segment 1: paddr=0001b828 vaddr=30100000 size=00088h (   136) load
I (98) esp_image: segment 2: paddr=0001b8b8 vaddr=4ff00000 size=04760h ( 18272) load
I (107) esp_image: segment 3: paddr=00020020 vaddr=40000020 size=1ae9ch (110236) map
I (129) esp_image: segment 4: paddr=0003aec4 vaddr=4ff04760 size=0b5b0h ( 46512) load
I (140) esp_image: segment 5: paddr=0004647c vaddr=4ff0fd80 size=02790h ( 10128) load
I (147) boot: Loaded app from partition at offset 0x10000
I (147) boot: Disabling RNG early entropy source...
I (159) cpu_start: Multicore app
I (168) cpu_start: GPIO 38 and 37 are used as console UART I/O pins
I (168) cpu_start: Pro cpu start user code
I (168) cpu_start: cpu freq: 360000000 Hz
I (170) app_init: Application information:
I (174) app_init: Project name:     esp32p4_scaler_proof
I (179) app_init: App version:      v0.2.0-93-g48ce715a-dirty
I (184) app_init: Compile time:     Aug  1 2026 17:39:06
I (189) app_init: ELF file SHA256:  2eadbe69d...
I (194) app_init: ESP-IDF:          v6.0.2
I (198) efuse_init: Min chip rev:     v1.0
I (201) efuse_init: Max chip rev:     v1.99 
I (205) efuse_init: Chip rev:         v1.3
I (209) heap_init: Initializing. RAM available for dynamic allocation:
I (216) heap_init: At 4FF22DB0 len 00018210 (96 KiB): RETENT_RAM
I (221) heap_init: At 4FF3AFC0 len 00004BF0 (18 KiB): RAM
I (226) heap_init: At 4FF40000 len 00060000 (384 KiB): RAM
I (232) heap_init: At 50108080 len 00007F80 (31 KiB): RTCRAM
I (237) heap_init: At 30100088 len 00001F78 (7 KiB): SPM
I (243) spi_flash: detected chip: gd
I (245) spi_flash: flash io: dio
I (249) sleep_gpio: Configure to isolate all GPIO pins in sleep state
I (255) sleep_gpio: Enable automatic switching of GPIO sleep configuration
I (261) main_task: Started on CPU0
I (291) main_task: Calling app_main()
I (291) p4_scaler_proof: CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
I (1491) p4_scaler_proof: CS_POST_INIT_PROBE cs_gpio=20 level=1
I (1491) p4_scaler_proof: SPI_CONFIG cs_io_num=20 cs_ena_pretrans=2 cs_ena_posttrans=8 mode=0 clock_hz=2000000 idle_policy=driver-default
I (1491) p4_scaler_proof: scaler proof mode=0 magic=0x51560002
I (1511) p4_scaler_proof: scale=1x explicit logic=640x480 ctrl=0x00
I (1511) p4_scaler_proof: HEALTH_BEFORE_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (1551) p4_scaler_proof: bitmap uploaded bytes=30720 clock=4000000
I (1581) p4_scaler_proof: attr uploaded bytes=30720 clock=4000000
I (1581) p4_scaler_proof: HEALTH_AFTER_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (1581) p4_scaler_proof: READBACK PASS addr=0x100000 value=0x00000000
I (1591) p4_scaler_proof: READBACK PASS addr=0x100008 value=0x55555555
I (1591) p4_scaler_proof: READBACK PASS addr=0x100010 value=0x00000000
I (1601) p4_scaler_proof: READBACK PASS addr=0x101000 value=0x55555555
I (1611) p4_scaler_proof: READBACK PASS addr=0x106400 value=0x00000000
I (1611) p4_scaler_proof: READBACK PASS addr=0x106480 value=0x00000000
I (1621) p4_scaler_proof: LINESTATE PASS lines=480 chunks=2
I (1621) p4_scaler_proof: HEALTH_AFTER_ENABLE raw=0x00000000 overflow=0 malformed=0
I (1631) p4_scaler_proof: SCALER_PROOF mode=0 pass=1
```

## hardware/cycle_01_openfpgaloader.log (binary — first 256 bytes hex + hash)

```hex
00000000: 656d 7074 790a 4a74 6167 2066 7265 7175  empty.Jtag frequ
00000010: 656e 6379 203a 2072 6571 7565 7374 6564  ency : requested
00000020: 2036 2e30 304d 487a 0020 2020 2d3e 2072   6.00MHz.   -> r
00000030: 6561 6c20 362e 3030 4d48 7a00 2020 0a50  eal 6.00MHz.  .P
00000040: 6172 7365 2066 696c 6520 5061 7273 6520  arse file Parse 
00000050: 2f68 6f6d 652f 6974 6164 6d69 6e2f 6769  /home/itadmin/gi
00000060: 7468 7562 2f73 7069 6e61 6c68 646c 5644  thub/spinalhdlVD
00000070: 502f 6670 6761 2f74 616e 6732 306b 2f69  P/fpga/tang20k/i
00000080: 6d70 6c2f 706e 722f 7072 6f6a 6563 745f  mpl/pnr/project_
00000090: 6135 6130 3437 6132 5f62 616e 6b63 6f6d  a5a047a2_bankcom
000000a0: 706c 6574 696f 6e2e 6673 3a20 0a44 6f6e  pletion.fs: .Don
000000b0: 650a 444f 4e45 0a45 7261 7365 2053 5241  e.DONE.Erase SRA
000000c0: 4d20 4c6f 6164 2053 5241 4d20 0d4c 6f61  M Load SRAM .Loa
000000d0: 6420 5352 414d 3a20 5b3d 3d3d 3d3d 3d3d  d SRAM: [=======
000000e0: 3d3d 3d3d 3d3d 3d3d 3d3d 3d3d 3d3d 3d20  =============== 
000000f0: 2020 2020 2020 2020 2020 2020 2020 2020                  
```

SHA-256: f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b

## hardware/settled_cycle_01_openfpgaloader.log (binary — first 256 bytes hex + hash)

```hex
00000000: 656d 7074 790a 4a74 6167 2066 7265 7175  empty.Jtag frequ
00000010: 656e 6379 203a 2072 6571 7565 7374 6564  ency : requested
00000020: 2036 2e30 304d 487a 0020 2020 2d3e 2072   6.00MHz.   -> r
00000030: 6561 6c20 362e 3030 4d48 7a00 2020 0a50  eal 6.00MHz.  .P
00000040: 6172 7365 2066 696c 6520 5061 7273 6520  arse file Parse 
00000050: 6670 6761 2f74 616e 6732 306b 2f69 6d70  fpga/tang20k/imp
00000060: 6c2f 706e 722f 7072 6f6a 6563 745f 6135  l/pnr/project_a5
00000070: 6130 3437 6132 5f62 616e 6b63 6f6d 706c  a047a2_bankcompl
00000080: 6574 696f 6e2e 6673 3a20 0a44 6f6e 650a  etion.fs: .Done.
00000090: 444f 4e45 0a45 7261 7365 2053 5241 4d20  DONE.Erase SRAM 
000000a0: 4c6f 6164 2053 5241 4d20 0d4c 6f61 6420  Load SRAM .Load 
000000b0: 5352 414d 3a20 5b3d 3d3d 3d3d 3d3d 3d3d  SRAM: [=========
000000c0: 3d3d 3d3d 3d3d 3d3d 3d3d 3d3d 3d20 2020  =============   
000000d0: 2020 2020 2020 2020 2020 2020 2020 2020                  
000000e0: 2020 2020 2020 2020 205d 2034 332e 3333           ] 43.33
000000f0: 250d 4c6f 6164 2053 5241 4d3a 205b 3d3d  %.Load SRAM: [==
```

SHA-256: 9c440acb8292411bc77ee7ae2e48bd230a990646246e3932f3c609c6568b9855

