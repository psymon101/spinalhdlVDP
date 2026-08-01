# Captured READ_DONE mode-8 serial proof

```text
Firmware ELF SHA-256: fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a
FPGA SRAM bitstream SHA-256: 0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2
I (291) p4_scaler_proof: scaler proof mode=8 magic=0x51560002
I (301) p4_scaler_proof: scale=1x explicit logic=640x480 ctrl=0x00
I (301) p4_scaler_proof: READ_DONE_GEOMETRY bitmap_base=0x100000 attr_base=0x110000 image_words=15360 chunk_words=253 chunk_bytes=506
I (311) p4_scaler_proof: READ_DONE_HEALTH_BEFORE_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (351) p4_scaler_proof: bitmap uploaded bytes=30720 clock=4000000
I (381) p4_scaler_proof: attr uploaded bytes=30720 clock=4000000
I (381) p4_scaler_proof: READ_DONE_HEALTH_AFTER_UPLOAD raw=0x00000000 overflow=0 malformed=0
I (391) p4_scaler_proof: READ_DONE_START selector=0x0C bit=0 polarity=high arm=0x0327 data_selector=0x08 repeats=8
I (401) p4_scaler_proof: READ_DONE_POLL addr=0x100008 poll=1 raw=0x00000001 done=1 reserved_zero=1 err=0
I (411) p4_scaler_proof: READ_DONE_READ repeat=0 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (421) p4_scaler_proof: READ_DONE_POLL addr=0x101000 poll=1 raw=0x00000001 done=1 reserved_zero=1 err=0
I (421) p4_scaler_proof: READ_DONE_READ repeat=0 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (441) p4_scaler_proof: READ_DONE_READ repeat=1 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (461) p4_scaler_proof: READ_DONE_READ repeat=1 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (481) p4_scaler_proof: READ_DONE_READ repeat=2 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (501) p4_scaler_proof: READ_DONE_READ repeat=2 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (521) p4_scaler_proof: READ_DONE_READ repeat=3 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (541) p4_scaler_proof: READ_DONE_READ repeat=3 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (561) p4_scaler_proof: READ_DONE_READ repeat=4 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (581) p4_scaler_proof: READ_DONE_READ repeat=4 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (601) p4_scaler_proof: READ_DONE_READ repeat=5 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (621) p4_scaler_proof: READ_DONE_READ repeat=5 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (641) p4_scaler_proof: READ_DONE_READ repeat=6 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (661) p4_scaler_proof: READ_DONE_READ repeat=6 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (681) p4_scaler_proof: READ_DONE_READ repeat=7 addr=0x100008 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (701) p4_scaler_proof: READ_DONE_READ repeat=7 addr=0x101000 expected=0x55555555 got=0x55555555 polls=1 pass=1 err=0
I (711) p4_scaler_proof: READ_DONE_RESULT pass=1 repeats=8 addresses=2 total_polls=16 max_polls=1
I (721) p4_scaler_proof: READ_DONE_HEALTH_AFTER_READ raw=0x00000000 overflow=0 malformed=0
I (731) p4_scaler_proof: READ_DONE_PROOF pass=1
```
