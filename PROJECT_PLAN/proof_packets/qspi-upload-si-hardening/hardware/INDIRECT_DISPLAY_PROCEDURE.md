# Display-indirect discriminator procedure

1. Keep the approved bitstream active (SHA-256
   `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`).
2. Build and flash proof mode 7 with ESP-IDF v6.0.2, carrying
   `SCALER_PROOF_MODE=7` through reconfigure, build, and flash.
3. The app writes byte pattern `0xAA` to four-byte words at `0x100008` and
   `0x101000`, with neighboring words left as the normal checkerboard data.
   It uploads both planes through the normal 4 MHz path, enables Mode 0, and
   reports health before upload, after upload, and after enable.
4. Capture three frames from `/dev/video0` as 720x480 YUYV at 30 fps.
