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
| F4 | HDMI reset sequencing weakness | Confirmed ordering issue; measure first | Add reset-sequence requirements to hardware bring-up notes if `clockReady` gating is applied | Open (Tier B) |
| F5 | Scaler architecture incorrect for >1× | Dormant; production runs 1× | Major spec/impl doc update only if scaler rewrite is productized | Open (Tier C) |
| F6 | `LineBuffer` 1280-deep BSRAM inference | Confirm netlist inference first | Document BSRAM inference policy if padding to 2048 + `ram_style=block` is applied | Open (Tier B) |
| F7 | `BasicPatternSource` dependent async reads | Off production path; low priority | Document tile-memory pipeline latency if pipelined | Open (Tier C) |
| F8 | Sync/DE/metadata latency table | Valid hygiene | Add explicit pipeline-latency table to `VDP_PROGRAMMING_GUIDE.md` §pipeline timing | Open (Tier B/C) |
| F9 | RGB565 `bitmapWritePipelineDelay` | Validate with co-sim first | Document the parameter value and validation procedure once measured | Open (Tier B) |
| — | `ScrollWrap` comment mismatch | **Confirmed cosmetic, fixed `10756d1`** | None (code comment only) | **Done** |

## Completed doc updates (Tier A)

### `VDP_PROGRAMMING_GUIDE.md`

- Added note after `vdp_mode0_set_layer_enable` describing the independent Layer 1 fetch scheduling surface and the `layer1FetchPixelAddr` fix.
- Added note in §8 (Host-Triggered Soft Reset) describing the internal bootstrap linestate upload, the `lastStepIdx` range bug, and its fix.

### `firmware/GOTCHAS.md`

- Added **GOTCHA-037: External static review Tier A latent fixes**, documenting the `lastStepIdx` and Layer 1 pixel-address fixes and noting they are latent in production.

### `CHANGELOG.md`

- Added 2026-07-25 entry summarizing the external review, Tier A fixes, proof, and doc updates.

## Pending doc updates

- Tier B measurement results (F4, F6, F9).
- Tier C scaler-rewrite docs (F5) if the feature is productized.
- Optional standalone diagnostic build procedure (F1) if adopted.
- Optional pipeline-latency table (F8).
