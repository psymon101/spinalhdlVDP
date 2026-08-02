# Lane 1 prime campaign result

Date: 2026-08-02
Owner: BronzeGate

## Verdict

PASS: all 10 PM-authorized reconfiguration cycles passed. Each cycle loaded
the preserved `a5a047a2` SRAM bitstream, held GPIO20 high for 1200 ms before
SPI initialization, performed the discard prime, and used the second magic
read as the gate. No abort condition occurred.

Per cycle, the serial log contained:

- `CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200`
- `LANE1_PRIME_DISCARD raw=0x22222222 err=0` (discarded first read)
- `scaler proof mode=0 magic=0x51560002`
- `HEALTH_BEFORE_UPLOAD`, `HEALTH_AFTER_UPLOAD`, and `HEALTH_AFTER_ENABLE`
  all `raw=0x00000000`
- six `READBACK PASS addr=` lines
- `SCALER_PROOF mode=0 pass=1`
- a 720x480 YUYV capture of exactly 2,073,600 bytes

The prime consistently consumed the previously mis-framed `0x22222222`; the
second read returned the approved magic on all ten cycles.

## Cycle artifact hashes

All loader logs have SHA-256
`f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b`.

| Cycles | Serial log SHA-256 | Capture SHA-256 |
|---|---|---|
| 01–02, 10 | `39c68715e2ae92534c12aacbb5a7c1539ffd2c359ad898f2ce96df0b70cc07c4` | 01 `a2094a30934f9dc6f0a81eac913fd03cd802d9b07ff15e2c597f8f0baffe5b19`; 02 `a2094a30934f9dc6f0a81eac913fd03cd802d9b07ff15e2c597f8f0baffe5b19`; 10 `59a4c3a070ba88f7c8ba7e6635da918e90b7569c5e4caf884726cb8d1d58c39c` |
| 03–04 | `5a2222d2548aea0d4a9d1e421c521cf151f5bc4f667303daacdee366aa8e8be6` | 03 `59a4c3a070ba88f7c8ba7e6635da918e90b7569c5e4caf884726cb8d1d58c39c`; 04 `a2094a30934f9dc6f0a81eac913fd03cd802d9b07ff15e2c597f8f0baffe5b19` |
| 05–09 | `cfdc5380a6dd051b31dd3e81e05531bcbc8d6abee6dcf3054fd5d18c5959268f` | 05 `a2094a30934f9dc6f0a81eac913fd03cd802d9b07ff15e2c597f8f0baffe5b19`; 06 `59a4c3a070ba88f7c8ba7e6635da918e90b7569c5e4caf884726cb8d1d58c39c`; 07–09 `a2094a30934f9dc6f0a81eac913fd03cd802d9b07ff15e2c597f8f0baffe5b19` |

## Matched pair and procedure

- Firmware source commit: `9babcbeec436906271114cb4b146bc0234e1e4be`
- Firmware build: `firmware/LANE1_PRIME_BUILD.md`
- Authority bitstream: `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs`
- Authority bitstream SHA-256: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`
- Runner: `run_ten_cycles_prime.sh`
- Hardware: Tang Nano 20K + ESP32-P4, `/dev/ttyACM0`, `/dev/video0`
- Loader: `openFPGALoader --board tangnano20k --bitstream ...`
- Capture: `ffmpeg` v4l2 `yuyv422`, 720x480, 3 frames, raw output

The initial detached runner invocation exited before loading produced output;
this was an infrastructure launch issue and produced no serial or capture
artifact. A direct loader check then passed, and the controlled runner was
rerun once from the foreground. The recorded campaign itself is the clean
10/10 pass above; its loader and serial artifacts are the proof of record.

— BronzeGate
