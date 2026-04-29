# Capture-Path Hardening Guide

**Purpose:** Prevent the team from chasing phantom scene bugs caused by capture-path failures.

**Authority:** BrightForge #8740, BronzeGate #8731, BronzeGate #8735.

---

## 1. The Problem

Our primary capture path is a cheap **Guermok USB2 HDMI capture card** feeding an RTSP/MJPEG stream at 1920×1080.

These devices have a **TMDS-lock fragility**:

- When the HDMI data lines carry **uniform or near-uniform pixel values** for too many scanlines, the capture card's clock-recovery PLL loses lock.
- The device does **not** report an error. Instead, it emits a **synthesized uniform black frame** (pixel value 6 in TV-range YUV, or 0 in full-range) to userspace.
- A monitor connected in parallel to the same HDMI output may be showing real content perfectly.

**This means a black capture is NOT proof that the FPGA is producing black pixels.**

### Historical impact

- **2026-04-28:** ~6 hours of investigation into "sc45 bitmap all-black" was actually a capture-path lock failure. Adding a 16×16 cyan corner canary at the final RGB mux made previously-dark canary stripes light up, revealing the FPGA had been producing content all along (#8739).
- **Task 44b audit (#8183):** Accepted black bitmap on canary evidence alone. Historical analysis later showed sc45 was **never visibly working on hardware** before v3 (#8724).

---

## 2. Transport Canary

### Specification

A **bright-cyan** (R=0, G=255, B=255) output-stage canary is injected at the **final RGB mux** in `TopTang20kHdmi.scala`, after all scenario-specific overlays and immediately before `hdmiCleanStart`.

#### v1 (committed, #8738)

- **Bottom-right 16×16 block** at FPGA active coordinates (624, 464) .. (639, 479).
- Gated only on `video.io.de`.
- Independent of scenarioId, BitmapRowFetch, palette, copperFifo, BITMAP_CTRL.

#### v2 (uncommitted, #8740)

Extends v1 with a **1-pixel frame border** around the entire 640×480 active window:

- Left edge:   `video.io.x === 0`
- Right edge:  `video.io.x === 639`
- Top edge:    `video.io.y === 0`
- Bottom edge: `video.io.y === 479`

**Why a frame border:** A 16×16 corner block only adds TMDS transitions on 16 of 480 active scanlines. The other 464 lines remain at risk on a sufficiently uniform scene. The frame border guarantees **every scanline** has at least 2 cyan pixels (left + right edges) and **every column** has at least 2 cyan pixels (top + bottom edges). This keeps the receiver locked regardless of scene content.

### Capture coordinate mapping

In 1920×1080 RTSP captures, the v1 corner block maps approximately to:

- `capture x ∈ [1872, 1920)`
- `capture y ∈ [1044, 1080)`

The v2 frame border maps to the outer edges of the active region in capture space.

---

## 3. Three-Gate Proof Flow

For every hardware proof going forward, evaluate in this **strict order**:

### Gate 1: Transport

> Is the cyan frame border + bottom-right corner block visible?

- **Absent** → **STOP.** Treat as transport / receiver / output-stage problem.
  - Do NOT reflash the FPGA.
  - Do NOT bisect commits.
  - Do NOT analyze the scene path.
  - Fix the transport path first (check monitor, check cables, check receiver lock).
- **Present** → Proceed to Gate 2.

**Automated check:**
```bash
python3 scripts/regression/check_transport.py captures/<name>.mp4 --frame -1
```

Exit codes:
- `0` = transport PASS
- `1` = transport FAIL or NEEDS_REVIEW
- `2` = runtime error

### Gate 2: Content

> Are scenario-specific canaries / overlays / borders rendering correctly?

Examples:
- sc45: 6-stripe canary band (RED/GREEN/BLUE/PURPLE/YELLOW/ORANGE) at y<40
- sc50: cycling border colors
- sc60: timing-band overlay

- **Absent** while transport is lit → content / probe-path / FSM issue.
- **Present** → Proceed to Gate 3.

### Gate 3: Scene

> Only now evaluate scenario rendering for correctness.

---

## 4. Memory Rule for Future Sessions

> Any RTSP/Guermok capture with `std=0` and a single uniform pixel value (typically 6 = TV-black, or 0 = full-black) is a capture-path lock failure, not the FPGA producing zeros. **Do not reflash, do not bisect, do not blame the scene path until the transport canary is verified.** Pair-verify with a monitor or webcam if uncertain.

---

## 5. Quick Reference: Failure Signatures

| Symptom | Likely Cause | First Action |
|---|---|---|
| Uniform black, `std=0`, `median=6` or `0` | Guermok TMDS-lock failure | Check monitor in parallel. Do not reflash. |
| Uniform black, but canary **is** visible | True scene-path bug | Proceed to content gate, then scene analysis. |
| Non-black but no canary (old bitstream) | Canary not yet built | Rebuild with transport canary before trusting capture. |
| Intermittent black frames in motion scene | PLL marginal lock | Check cable quality, try shorter cable. |
| Cyan canary present but content canaries missing | Content/probe bug | Focus on scenario-specific FSM / data path. |

---

## 6. Further Hardening (Tier 2 / Tier 3)

| Item | Effort | Payoff | Status |
|---|---|---|---|
| `scripts/regression/check_transport.py` | small | Auto-classify transport failure | **DONE** #8740 |
| 4-bit frame-counter heartbeat block | small | Distinguish frozen from live capture | Pending |
| `CAPTURE.md` (this doc) | small | Future agents read before chasing phantom bugs | **DONE** #8740 |
| Replace Guermok with Magewell / Elgato Cam Link | hardware purchase | Eliminates receiver-fragility class entirely | Deferred to PM |
| Pair-verify process: cross-check against webcam-on-monitor | process | Catches Guermok-only failures with no code change | Standing rule |

---

*Last updated: 2026-04-29 by CoralReef (#8740 Tier-2 support).*
