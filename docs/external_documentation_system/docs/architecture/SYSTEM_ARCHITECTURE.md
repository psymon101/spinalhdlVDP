# System Architecture

## Product model

```text
Any host MCU/CPU/SBC/custom machine
        ↓
platform API or generic Mode0 API
        ↓
libvdp transport-neutral operations
        ↓
transport backend
        ↓
FPGA host bridge
        ↓
register state + FPGA SDRAM
        ↓
platform frontend/shared engines
        ↓
compositor → scaler → HDMI
```

## Responsibility split

### Host

- chooses Mode0 or a platform visual adapter;
- supplies graphics memory and assets;
- writes registers and optional timed-event programs;
- performs high-level asset conversion where appropriate;
- handles application logic.

### FPGA

- stores video state needed for continuous scanout;
- fetches and decodes native formats;
- applies deterministic layer, sprite, palette, raster, and priority behavior;
- generates continuous display timing and HDMI output;
- reports status, overflow, late events, and errors.

### libvdp

- hides packet framing and transport details;
- exposes generic and platform-oriented helpers;
- enforces ABI/capability compatibility;
- provides safe commit, upload, status, and error behavior.

## Non-goals

- complete computer emulation;
- CPU execution;
- audio emulation;
- storage or OS emulation;
- universal cycle-exact bus contention.
