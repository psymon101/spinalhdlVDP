# Video Pipeline

## Common internal path

```text
platform/native memory
    ↓
frontend fetch and decode
    ↓
per-layer pixel candidate
(index/RGB, opaque, priority, source)
    ↓
sprite candidate
    ↓
platform priority mapping
    ↓
common compositor
    ↓
color math/effects
    ↓
logical pixel stream
    ↓
integer scaler/centering/border
    ↓
HDMI timing and output
```

## Internal pixel candidate

A shared candidate should contain enough information for:

- palette index or direct RGB;
- transparency;
- priority;
- layer/source identifier;
- collision participation;
- validity.

The exact Bundle definition is locked in the SpinalHDL architecture document.

## Timing rule

A frontend may have native memory and register behavior, but it must deliver
pixels to the common pipeline at the documented latency and must never alter
HDMI timing.
