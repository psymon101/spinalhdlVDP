# libvdp Architecture

## Public layers

```text
application
    ↓
vdp_<platform>_* adapters
    ↓
vdp_mode0_* / vdp_copper_* / vdp_status_* / upload helpers
    ↓
vdp_host_* / vdp_reg_* / vdp_sdram_*
    ↓
selected transport backend
```

## Design rules

- applications do not hand-frame host packets;
- platform adapters remain thin;
- generic functions return actionable errors where possible;
- ABI and capabilities are checked during initialization;
- blocking/asynchronous behavior is documented;
- callback and reentrancy rules are explicit;
- transport limitations do not silently leak into platform APIs.

## Required build targets

- authoritative host;
- at least one secondary host;
- unit-test/mock transport target;
- examples for Generic Mode0 and every closed platform.
