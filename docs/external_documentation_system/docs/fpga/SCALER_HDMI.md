# Scaler and HDMI Output

## Responsibilities

- stable output timing;
- integer scaling;
- centering;
- outer/inner borders;
- logical clipping;
- HDMI/TMDS serialization.

## Contract

The FPGA continues output even when the host is idle. Platform frontends never
control physical HDMI timing directly.

## Tests

- supported logical dimensions;
- scale factors;
- center offsets;
- border dimensions;
- blanking/sync;
- reset;
- mode switch;
- long soak on direct display and secondary capture.
