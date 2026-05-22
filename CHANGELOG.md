# spinalhdlVDP Changelog

## 2026-05-20 — R5.4 Copper Double-Buffer Integration into descCount=32 Line (DONE)

- **R5.4 Copper Integration** — DONE (BronzeGate #10398 closeout accepted)
  - Cherry-pick `d32616d` (R5.4 copper double-buffer) onto `mode2optimized-gate2-enableL2L3` @ `b1b054b`
  - Integration branch: `mode2optimized-gate2-r54copper` @ `5a35c1b`
  - Sim regression: 26/26 PASS (CopperSim 12/12, CopperHdmaSim 7/7, CopperBorderIntegrationSim 2/2, BorderRegSim 5/5)
  - PnR on Tang Nano 20K (`GW2AR-LV18QN88C8`):
    - Logic 9462 (46%, −927 vs baseline), LUT 5800 (−160), SSRAM 468 (−128), FF 3928 (+2), CLS 6676 (65%, −446)
    - BSRAM 26/46 (57%, +1), DSP 4/24 (17%, 0)
    - Setup violations: **0**, Hold violations: **0**, TNS: 0.000, WSS: +0.074 ns
  - RTL drift: zero — `git status hw/spinal/` clean on merged tree
  - One bitstream now carries **descCount=32 sprites + R5.4 copper double-buffer**; unblocks TopazCliff copper bounce demo

## 2026-05-19 — Active Documentation Cleanup (#10303)

- **Docs cleanup** — Reduced repetition and tightened verbose sections across active docs:
  - `PROJECT_PLAN/PLATFORM_ADAPTERS.md` — Removed redundant §3 "Per-Adapter Summary" (covered by §1 Index + §2 Honesty Matrix). Saved ~45 lines.
  - `PROJECT_PLAN/CONVENTIONS.md` — Tightened "Platform Adapter Fidelity Standards" preamble. Saved ~5 lines.
  - `kb/libvdp/README.md` — Tightened "All-in-one sprite upload", "Migration & Naming Plan" (converted to table), and "Critical Implementation Facts". Net ~15 lines leaner.
  - `PROJECT_PLAN/MODE0_PLANNING.md` — Consolidated quantified behavior tables, removed redundant register map. Saved ~230 lines.
  - `PROJECT_PLAN/ASSESSMENT.md` — Removed redundant internal report headers. Saved ~4 lines.

## 2026-05-19 — libvdp All-in-One Sprite Upload Helper + Per-Platform Palette LUTs

- **libvdp All-in-One Sprite Upload Helper** — DONE (BronzeGate #10296)
  - `vdp_sprite_upload()` wraps palette upload + pattern RAM upload + sprite descriptor config in one call (`c9e6702`)
  - Proof: `esp8266_sc62_sprite_flip` and `esp32_sc62_sprite_flip` updated to use helper; compile clean
  - `kb/libvdp/README.md` updated with API signature and usage notes

- **libvdp Per-Platform Palette LUT Helpers** — DONE (BronzeGate #10305/#10306)
  - `vdp_tms9918_load_palette()` — fixed 16-color TMS9918A palette
  - `vdp_sms_palette_write(idx, val)` — SMS 6-bit CRAM
  - `vdp_gg_palette_write(idx, val)` — Game Gear 12-bit CRAM
  - `vdp_atarist_palette_write(idx, val)` — Atari ST 9-bit
  - `vdp_atariste_palette_write(idx, val)` — Atari STE 12-bit
  - Bit-expansion policy documented: 2-bit replicate, 3-bit weighted, 4-bit nibble-duplicate
  - Proof: `esp8266_palette_lut_smoke` and `test_palette_lut_smoke` compile clean
  - Commit: `45f0d88`

## 2026-05-19 — 3b Copper Double-Buffer Closed + libvdp Helper-Surface Complete + Audit Checklist Landed

- **3b Copper Double-Buffer Live-Update** — DONE (BronzeGate #10270 CP-G closeout accepted)
  - RTL: `Copper.scala` + `VdpTop.scala` changes for double-buffered 2×512-word program RAM (`d32616d`)
  - Register: `VDP_CTRL[1] = COPPER_SWAP_REQUEST`, HW auto-clears after commit at vSyncStart
  - Firmware: `vdp_copper_swap_request()`, `vdp_copper_upload_and_swap()` helpers + bouncing-bars demo (`ec474c9`)
  - Docs: `kb/libvdp/README.md` updated with double-buffer facts and copper helper tables (`01f2e91`, `b68e102`)
  - A1/A4 helpers landed: `vdp_copper_write_op()`, `vdp_copper_skip_op()`, `vdp_copper_upload_and_swap()` (`94f401f`)
  - Bitstream produced at `d32616d` + `22afb90` merge; LUT 5583 (−291 vs baseline), CLS 6413 (−475)

- **libvdp Mode0 Helper-Surface Completion** — DONE (BronzeGate #10273)
  - Standalone control helpers: `vdp_mode0_set_color_math()`, `vdp_mode0_set_bitmap_ctrl()`, `vdp_mode0_set_border_ctrl()`, `vdp_mode0_set_vdp_ctrl_word()` (`6830b55`)
  - Pattern-RAM + VSCROLL + HDMA structured helpers: `vdp_mode0_set_pattern_ptr()`, `vdp_mode0_write_pattern_data()`, `vdp_mode0_set_vscroll_base()`, `vdp_mode0_set_hdma_ctrl()`, etc. (`9f6b86f`)
  - Bitmap base/stride standalone helpers: `vdp_mode0_set_bitmap_base()`, `vdp_mode0_set_attr_base()`, `vdp_mode0_set_bitmap_stride()`, `vdp_mode0_set_attr_stride()` (`29be453`)
  - `kb/libvdp/README.md` coverage tables updated to match

- **CoralReef Audit / Doc-Sync Checklist** — LANDED (`4ba550e`)
  - Created `PROJECT_PLAN/CORALREEF_AUDIT_CHECKLIST.md` per BrightForge #10274
  - Embeds libvdp doc-sync check into every lane audit/closeout
  - Verified BronzeGate #10174 / #10178 Copper upload-path doc sync is complete in tree

## 2026-05-17 — Mode2optimized Compile-Time Feature Strip Closed

- **Mode2optimized Compile-Time Feature Strip** — DONE (BrightForge #10142 / CoralReef audit closeout)
  - Goal: recover rich-top default build fit on GW2AR-LV18 via compile-time gates + Mem-inference hardening
  - Gate #1 (`withExtraRasterTriggers`) blocked by Mem-fragility (+5485 DFFs); diagnosed and fixed via `activeListMem` readport-trim (`40c0384`)
  - Gates 2-4 (`enableL2L3`, `planeRows` trim, `LinestateStore.prepare` BSRAM) stacked incrementally
  - Final fix: `SpriteRasterizer.slbA/slbB` BSRAM conversion via qa.md A-001 lookahead pattern (`22afb90`)
  - Tang Nano PnR passes with massive headroom: 5874 LUT (28%), 3791 Register (24%), 6888 CLS (67%)
  - Bitstream `project.fs` produced on branch `mode2optimized-gate2-enableL2L3` @ `22afb90`
  - GT-023 hardware constraint documented: GW2AR-18 0.75 FF/LUT cap
  - MemReport tool landed (`47f0a87`, `d32f446`)
- **Task 10026 — Barebones Simple Sprite over Background** — DONE (audit PASS #10117)
  - Procedural 16×16 white sprite with priority `sprite > L1 > L0`
  - Commits: `eda89d7` (RTL), `6119360` (ESP32 firmware), `40f1424` (ESP8266 firmware)
  - Hardware proof confirmed by TopazCliff #10116
- **libvdp API classification and migration plan** — DONE (CyanPeak `abae575`)
  - `vdp_barebones_*` vs `vdp_mode0_*` naming convention formalized in `kb/libvdp/README.md` and `firmware/GOTCHAS.md`
  - Sprite programming API gap documented
- **Agent roles updated** per PM override (2026-05-16):
  - `CoralReef` now owns authoritative audit / sign-off / memory curation
  - `TopazCliff` now owns MCU firmware / host-transport responsibilities
  - `FoggyWolf` retired from canonical firmware ownership

## 2026-05-14 — Multiple Lanes Closed

- **Task 54** — Sprite-Sprite Collision Detector — DONE (audit PASS #9672)
  - Commit `e556ff5`; sim-only proof per #9620
- **Task 56** — Multi-Layer SDRAM Fetch — DONE (audit PASS #9709)
  - Commits `93773d7`, `ee5820c`, `834c71e`; sim-only contract fulfilled
- **Host Platform Fidelity Requirements** — DONE (audit PASS #9891)
  - Commits `8afc432`, `4814dc2`
- **ESP8266 QSPI Transport Fix** — DONE (audit PASS #9875)
  - Commit `878e862`
- **Reference Localization** — DONE (audit PASS #9839)
  - Commit `304bac0`
- **Standards Compression** — DONE (audit PASS #9839)
  - Commits `cc099a8`, `805d5eb`
- **ZX Spectrum Firmware Host Flow (v1)** — DONE (#9797)
  - Commits `13989c1`, `zx_final_proof_v4.png`
- **320-pixel planar clipping mask** — DONE (#9768)
  - Commit `77bedae`

## 2026-05-09 — Task 57 Closed, Task 54 Active

- **Task 57** — Substrate DFF Optimization (GW2AR-LV18 recovery) — DONE (audit PASS #9617)
  - Path 5A: descCount=8 / visiblePerLine=8 / NUM_SLOTS=8
  - Commit `fae0585`; first sprite-enabled bitstream since Task 2b
  - Logic Register as FF: 6834 / 15552 (44%) — 56% headroom
- **Task 54** — Sprite-Sprite Collision Detector — IN-PROGRESS (Checkpoint B)
  - Checkpoint A audit PASS #9620; BrightForge proof packet #9625 landed
  - Awaiting CyanPeak Checkpoint B audit

## 2026-05-07 — Task 53 Closed, Task 55 Active

- **Task 53** — Sprite Pattern Address Width Expansion — DONE (audit PASS #9433)
  - `patIdxWidth` 4→6 (16→64 unique tiles)
  - Commit `26174a7`; artifact in `PROJECT_PLAN/archive/artifacts/TASK_53...md`
- **Task 55** — Sprite Masking + Tile-Fetch Budget Counter — DONE (audit PASS #9479)
  - Checkpoint A audit PASS #9445; Checkpoint B audit PASS #9461; hardware proof PASS

## 2026-05-06 — Task 3 Closed

- **Task 3** — Planar Fetch Hardening (2→5+ planes) — DONE (audit PASS #9406)
  - Fix: `VdpTop.scala:888` modulo wrap + `PlanarPixelIdxBoundsSim` discriminator
  - Commit `452c3db`; 0 timing violations; SMPTE bars proven

## 2026-04 — Sprite Capacity Expansion Epic Closed

- **Task 2b** — Sprite Capacity Bump (V=32/D=64) — DONE (audit PASS #9298)
- **Task 2c** — Sprite Evaluator Hardening — DONE (audit PASS #9278)
- **Task 2a** — Sprite Capacity Substrate Pre-Hardening — DONE (audit PASS #9250)

## 2026-04 — Adapter Infrastructure Complete

- **Task 50** — ZX Spectrum Adapter — DONE (#8976)
- **Task 51** — MODE_SELECT Runtime Adapter Selection — DONE (#9201)
- **Task 52** — Per-Sprite X/Y Flip Primitive — DONE (#9127)

## Earlier Closed Lanes (Selected)

| Task | Closeout | Commit | Key Evidence |
|------|----------|--------|--------------|
| Beam-Driven Automation Hardening | #8660 | `6345fcc` | Scenario 60 HW proof |
| Color/Window Hardening | #8654 | `0f5dc65` | Scenarios 51+52 HW proof |
| Sprite Phase 2 + 2-bis | #8638 | `39a7242` | Scenario 50 HW proof |
| Sprite Pattern Memory Foundation | #8605 | `e86fe49` | BSRAM-backed pattern RAM |
| Mode0 Sprite Envelope Hardening | #8589 | `d44a9c0` | Descriptor + evaluator upgrades |
| Mode0 Fetch Envelope Hardening | — | — | Tile/planar/shuffled/bitmap assessment |
| #9026 Zero-Footprint ROM Elimination | #9142 | — | Host-init bootstrap proven |

---

## Active Lane

See `PROJECT_PLAN/TASKS.md` §Live Lane State for the single active lane.

## Open Queue

See `PROJECT_PLAN/TASKS.md` §Next Up / Open Queue.

## Full History

For phase-by-phase narratives, proof records, and debug archaeology, see `PROJECT_PLAN/TASKS_HISTORY.md`.
