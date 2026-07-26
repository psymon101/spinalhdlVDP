# Host Transport ABI

## Logical operations

- initialize/deinitialize;
- register read/write/burst;
- SDRAM write;
- optional readback;
- status/capability read;
- speed/configuration control;
- wait/interrupt integration.

## Backend interface target

A backend should provide a function table or equivalent compile-time contract:

```c
typedef struct {
    bool (*init)(void *ctx);
    bool (*reg_write)(void *ctx, uint32_t addr, uint16_t value);
    bool (*reg_read)(void *ctx, uint32_t addr, uint16_t *value);
    bool (*reg_write_burst)(void *ctx, uint32_t addr,
                            const uint16_t *words, size_t count);
    bool (*sdram_write)(void *ctx, uint32_t addr,
                        const void *data, size_t bytes);
    bool (*read_status)(void *ctx, uint8_t selector, uint32_t *value);
} vdp_transport_ops_t;
```

The final API may differ, but every backend must satisfy the same semantics.

## Required backend record

- target/SDK;
- pins;
- bus rate;
- max transaction;
- DMA support;
- read support;
- vblank/status method;
- electrical constraints;
- known limitations;
- acceptance test results.
