# External Static Review — Documentation Impact Tracking

**Review source:** `kb/reviews/external_static_review_2026-07-25.md`  
**Technical assessment:** BrightForge #14317  
**PM disposition / Tier A lane:** #14318 / `external-review-tierA-fixes`  
**Doc impact assessment:** CoralReef #14316  

## Scope

This file tracks which findings from the external static review require documentation or compliance updates, and which are purely RTL/firmware/implementation work. It is a living document; update it as Tier B/C items are measured or closed.

## Verdict and doc-action summary

| ID | Finding | Verdict | Doc action | Status |
|---|---|---|---|---|
| F1 | `useHostInit=true` boots blank | Intentional production behavior; standalone `false` is diagnostic only | Document standalone diagnostic build as a bring-up procedure, not a default change | Pending PM / BrightForge |
| F2 | Bootstrap `lastStepIdx` range bug | **Confirmed, fixed `10756d1`** | `VDP_PROGRAMMING_GUIDE.md` §8 note; `firmware/GOTCHAS.md` GOTCHA-037 | **Done** |
| F3 | L1 fetch wired to L0 pixel address | **Confirmed, fixed `10756d1`** | `VDP_PROGRAMMING_GUIDE.md` §2 note on Layer 1 scheduling surface; `firmware/GOTCHAS.md` GOTCHA-037 | **Done** |
| F4 | HDMI reset sequencing weakness | Confirmed ordering issue; measured cold-start reliability | None (10/10 POR locks successful; no clock gating applied) | **Done** (proof packet `5128ff4`) |
| F5 | Scaler architecture incorrect for >1× | Dormant; production runs 1× | Major spec/impl doc update only if scaler rewrite is productized | Open (Tier C) |
| F6 | `LineBuffer` 1280-deep BSRAM inference | Audited netlist XML report; verified correct BSRAM mapping | None (no padding/decorations required; verified mapped to BSRAM) | **Done** (proof packet `5128ff4`) |
| F7 | `BasicPatternSource` dependent async reads | Off production path; low priority | Document tile-memory pipeline latency if pipelined | Open (Tier C) |
| F8 | Sync/DE/metadata latency table | Valid hygiene | Added pipeline-latency table to `VDP_PROGRAMMING_GUIDE.md` §8 and 2-cycle digital alignment assertion to `VdpInnerBorderCoSim.scala` | **Done** (commit `c009701`) |
| F9 | RGB565 `bitmapWritePipelineDelay` | Validated production default 0 is byte-exact via X-ramp co-sim | None (nonzero delay misaligns; production default 0 confirmed correct) | **Done** (proof packet `5128ff4`) |
| — | `ScrollWrap` comment mismatch | **Confirmed cosmetic, fixed `10756d1`** | None (code comment only) | **Done** |
| HDMI-TX | `Tang20kHdmiTx` black-box internals never reviewed | Reviewed: faithful MIT TMDS/DVI encoder (hdl-util/hdmi `tmds_channel.sv`) + Gowin `OSER10` serializers + phase-aligned ÷5 clocks (one rPLL) + PLL-lock-gated reset; **verdict OK** | None (no defect; wrapper sufficient; F4 reset-order already dispositioned; 10/10 POR corroborates) | **Done** (review `kb/reviews/hdmi_tx_blackbox_review_2026-07-28.md`) |

## Completed doc updates (Tier A)

### `VDP_PROGRAMMING_GUIDE.md`

- Added note after `vdp_mode0_set_layer_enable` describing the independent Layer 1 fetch scheduling surface and the `layer1FetchPixelAddr` fix.
- Added note in §8 (Host-Triggered Soft Reset) describing the internal bootstrap linestate upload, the `lastStepIdx` range bug, and its fix.

### `firmware/GOTCHAS.md`

- Added **GOTCHA-037: External static review Tier A latent fixes**, documenting the `lastStepIdx` and Layer 1 pixel-address fixes and noting they are latent in production.

### `CHANGELOG.md`

- Added 2026-07-25 entry summarizing the external review, Tier A fixes, proof, and doc updates.

## Pending doc updates

- Tier C scaler-rewrite docs (F5) if the feature is productized.
- Optional standalone diagnostic build procedure (F1) if adopted.

