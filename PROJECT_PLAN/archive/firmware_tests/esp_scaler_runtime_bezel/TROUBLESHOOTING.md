## ESP32-S3 linker failure (bad cached core)

Symptom: `arduino-cli compile` for `esp32:esp32:esp32s3` fails with missing
core symbols (`delay`, `digitalWrite`, `app_main`, `String`, etc.).

Fix: Delete the bad cached core archive and rebuild.

```sh
rm -rf ~/.cache/arduino/cores/esp32_esp32_esp32s3_*
```

Root cause: Corrupted precompiled Arduino core archive, not a source bug.
