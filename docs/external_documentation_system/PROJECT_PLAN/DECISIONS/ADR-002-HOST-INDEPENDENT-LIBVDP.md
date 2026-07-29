# ADR-002 — `libvdp` is the universal host SDK

## Status

Accepted.

## Decision

The public SDK remains `libvdp` with the `vdp_*` prefix. Platform APIs are thin
layers such as `vdp_atarist_*` and `vdp_amiga_*`. A parallel `retro_vdp_*`
library will not be created.

## Consequences

Transport differences remain below the generic and platform APIs.
