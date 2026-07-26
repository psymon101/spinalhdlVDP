# Capability and ABI Model

The host must discover the connected bitstream rather than assume every feature
is present.

## Required capability data

- magic value;
- ABI major/minor;
- build/profile identifier;
- feature bitmap;
- adapter bitmap;
- SDRAM byte count;
- maximum logical width/height;
- maximum layers and sprites;
- supported bitmap formats;
- supported planar layouts and plane count;
- Copper/HDMA/Blitter availability;
- supported transport read/status functions.

## Compatibility

- major mismatch: initialization fails;
- newer minor version: allowed only when required feature bits are present;
- missing feature: platform initialization fails with a specific error;
- unknown reserved bits: ignored unless the ABI says otherwise.

## Required firmware API

```c
bool vdp_get_capabilities(vdp_capabilities_t *out);
bool vdp_require_features(uint32_t required);
bool vdp_adapter_supported(vdp_adapter_id_t adapter);
```
