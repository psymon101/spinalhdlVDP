# BAD_APPLE_MODE0_DEMO.md

**Updated:** 2026-04-23  
**Purpose:** Define a bounded, explicitly non-roadmap demo experiment for playing a monochrome Bad Apple-style video directly on `Mode0` using the existing host-upload and bitmap-fetch infrastructure.

---

## Status

**Classification:** Fun / experimental demo  
**Roadmap status:** Not part of the formal adapter or substrate backlog  
**Intent:** Stress-test and showcase the existing host-upload + bitmap-fetch path without redefining core project scope

---

## Why This Exists

This demo asks a simple question:

> Can we stream a monochrome preprocessed video into `Mode0` and display it on-screen using the current host + SDRAM + bitmap infrastructure?

This is **not** a C64 adapter goal and **not** a formal new `Mode0` primitive. It is a bounded experiment that may still teach useful lessons about:

- host upload throughput
- SDRAM write/update cadence
- bitmap fetch practicality
- what kinds of lightweight effects are possible on top of streamed content

---

## Scope

### In Scope

- monochrome video playback
- direct `Mode0` bitmap presentation
- preprocessed host-streamed frames or frame deltas
- low-fps proof first
- optional visual effects layered on top later

### Explicitly Out of Scope

- audio playback
- C64-faithful presentation
- formal adapter work
- proving sustained high-bandwidth full-motion video as a guaranteed board capability
- changing project roadmap priority because the demo exists

---

## Recommended First-Cut Format

### Video Format

- resolution: `320x200`
- bit depth: `1bpp`
- palette: black / white
- source: offline-converted Bad Apple video frames

### Frame Size

`320 * 200 / 8 = 8000 bytes` per frame

### Initial Playback Goal

- first target: **10 fps**
- stretch target: **15 fps**
- do **not** assume `30 fps` as the first success criterion

---

## Playback Architecture

### High-Level Flow

```text
Bad Apple source video
  -> offline frame extraction + monochrome conversion
  -> frame packing / delta generation
  -> Pico 2 host upload over QSPI
  -> SDRAM bitmap region
  -> Mode0 bitmap fetch
  -> fixed HDMI output
```

### Recommended First Strategy

Start with the simplest viable path:

1. preprocess source video into `320x200 1bpp` frames
2. upload full frames at low fps if necessary
3. once proven, move to row-delta or block-delta upload

### Better Practical Strategy

After first proof, switch to **delta updates**:

- changed rows, or
- changed `8x8` / `16x16` blocks

This reduces upload bandwidth and is much more realistic for a sustained demo.

---

## Why `Mode0` Directly

This demo should target `Mode0` directly, not the C64 adapter, because:

- the goal is generic bitmap playback, not platform emulation
- `Mode0` already has bitmap-oriented substrate paths
- effects can be layered generically without pretending they are C64 semantics

This makes it a much cleaner stress/demo case.

---

## SDRAM / Fetch Model

### First-Cut Memory Model

- one SDRAM region for the current bitmap frame
- `Mode0` bitmap fetch reads from that region
- host overwrites the region with the next frame or delta updates

### Optional Future Improvement

- dual frame regions with explicit swap control

This is not required for the first proof.

---

## Bandwidth Sanity Check

### Raw Full-Frame Cost

- frame size: `8000 bytes`
- `10 fps` -> `~80 KB/s`
- `15 fps` -> `~120 KB/s`
- `30 fps` -> `~240 KB/s`

These numbers exclude protocol overhead and control traffic.

### Interpretation

- low-fps monochrome playback is plausibly within reach
- raw full-frame `30 fps` should **not** be assumed without measurement
- delta upload is the preferred real path after initial proof

---

## Visual Presentation

### First Proof

- black / white monochrome
- no attempt to mimic C64, ZX, or any historical machine
- centered or letterboxed inside the fixed output raster

### Later Fun Additions

Once the basic stream works, allowed demo-only effects include:

- palette tinting
- raster splits
- overlay text/HUD
- sprite overlays
- window reveals / masks
- simple affine or distortion-style presentation if it can be layered cheaply

These remain demo effects, not new scope commitments.

---

## Implementation Phases

### Phase A — Static Bitmap Proof

Goal:

- host uploads one `320x200 1bpp` frame to SDRAM
- `Mode0` displays it correctly

Exit:

- clear visible static image on the HDMI output

### Phase B — Low-FPS Full-Frame Playback

Goal:

- host uploads successive full frames
- playback is visibly recognizable as motion

Exit:

- a short recognizable monochrome animation at low fps

### Phase C — Delta-Update Playback

Goal:

- host sends only changed rows or blocks
- effective playback smoothness improves without naive full-frame resend

Exit:

- visibly improved motion at the same or lower link budget

### Phase D — Fun Effects

Goal:

- layer one or more generic `Mode0` effects over the streamed video

Exit:

- a visibly stylized demo without redefining mainline project scope

---

## Success Criteria

For the first success claim, the demo only needs to prove:

1. a preprocessed monochrome video frame can be uploaded by the host
2. `Mode0` can display the uploaded bitmap on screen
3. repeated updates produce recognizable motion

It does **not** need to prove:

- maximum fps
- zero tearing
- production-quality streaming protocol
- general-purpose video playback as a formal project feature

---

## Non-Goals / Guardrails

- Do not let this demo silently become a formal backlog commitment.
- Do not treat success here as proof that full-framebuffer video streaming is generally cheap on Tang Nano 20K.
- Do not widen `Mode0` for demo-only convenience without a separate architectural decision.
- Do not let the fun demo override `MODE0_STOPLINES.md`.

---

## Recommended Next Concrete Step

If the team chooses to try this, the first implementation target should be:

- **Phase A — Static Bitmap Proof**

That will answer the first real question quickly:

- can the current host-upload + bitmap-fetch path display a streamed `320x200 1bpp` image cleanly enough to make the rest of the demo worth pursuing?
