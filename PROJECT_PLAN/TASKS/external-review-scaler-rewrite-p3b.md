# external-review-scaler-rewrite-p3b

**Owner:** BrightForge  
**PM:** TopazCliff  
**Interface checkpoint partner:** BronzeGate  
**Status:** OPEN  
**Opened:** 2026-07-28  
**Parent lane:** `external-review-scaler-rewrite` / `scaler-rewrite-hw-proof`  
**Source branch:** `topazcliff/scaler-rewrite`  

---

## Purpose

The parent `external-review-scaler-rewrite` lane replaced the sink-side `PixelRepeatScaler` with a source-coordinate `ScaleCoordGen` and proved the procedural/testpattern path correct for 1×/2×/3× scaling on both simulation and real hardware. It deliberately scoped out **bitmap/indexed fetch-side scaling** (P3b) because that path requires changes to the SDRAM fetch geometry and, more importantly, a host-visible semantic decision.

This lane completes the scaler story for bitmap/indexed content by:
1. Defining the host-visible semantics of `SCALE_CTRL` for bitmap/indexed layers.
2. Implementing fetch-side coordinate remapping so source rows/columns repeat correctly at scale > 1.
3. Proving the result in co-sim and PnR (hardware flash is out of scope unless PM directs otherwise).

## Background

In the current design, bitmap/indexed content is authored at 320×240 source pixels and displayed at 640×480 via a fixed 2× vertical line-doubling path. The parent scaler lanes proved that the *render* side can scale logical coordinates; however, the *fetch* side still keys off physical scan position:

- `pixelWithinByte := RegNext(hCounter(2:0))` (VdpTop) — uses raw physical column, not `logicalX`.
- `bitmapFetchLineReg := fillLine` — uses physical display line, not `logicalY`.
- Bitmap fetch grant is gated by `vCounter(0)` for the built-in 2× line doubling.

At scale > 1, these physical indices no longer map 1:1 to source content, so source rows/columns are not repeated correctly.

## Semantics Decision (BronzeGate Interface Checkpoint)

Before any RTL change, BrightForge and BronzeGate must agree on the host-visible behavior and document it in the register spec / `firmware/GOTCHAS.md`.

The core question is:

> For a bitmap authored at 320×240, what does `SCALE_CTRL scaleY=2` mean?

Two candidate semantics:

- **Option A — Replace:** `SCALE_CTRL` replaces the built-in 2× doubling. A 320×240 bitmap with `scaleY=2` is displayed at 640×480 (same as today), but the generic scaler controls the repeat. `scaleY=1` would show only 240 source lines stretched/centered into 480 display lines (effectively half-height). This makes `SCALE_CTRL` the single source of truth for scaling.
- **Option B — Compose:** `SCALE_CTRL` is applied *on top of* the built-in 2× doubling. A 320×240 bitmap with `scaleY=2` is displayed at 1280×960 effective source rate (4× total). This preserves backward compatibility for existing 320×240 assets but is harder to explain and may exceed SDRAM bandwidth.

**Decision rule:** Choose the semantics that is simplest to specify, least surprising to a host developer, and achievable within current SDRAM bandwidth. The default production behavior (1× / no `SCALE_CTRL`) must remain byte-identical to the HW-proven `a5a047a2` baseline.

## Scope

**In scope:**
- Host-visible semantics proposal for `SCALE_CTRL` applied to bitmap/indexed layers.
- BronzeGate interface checkpoint to approve and document the semantics.
- RTL changes to remap bitmap/indexed fetch coordinates from physical to logical:
  - `pixelWithinByte` derived from `logicalX`.
  - `bitmapFetchLineReg` derived from `logicalY`.
  - Fetch grant cadence driven by `logicalY` step boundaries instead of `vCounter(0)`.
- Co-sim proof: deterministic bitmap/indexed pattern at 1×/2×/3× showing correct source-row/column repetition and no skip/dup artifacts.
- PnR proof: TNS=0, no unexpected resource growth.
- Proof packet under `PROJECT_PLAN/proof_packets/external-review-scaler-rewrite-p3b/`.

**Out of scope:**
- Changes to the production 1× path. It must remain byte-identical to `a5a047a2`.
- Changes to procedural/testpattern/tile layers (already proven in parent lanes).
- Hardware flash (this lane is sim+PnR unless PM opens a separate HW gate).
- Audio, Copper, sprite, or Layer 1 paths.

## Dependencies

- `external-review-scaler-rewrite` DONE.
- `scaler-rewrite-hw-proof` DONE.
- BronzeGate availability for interface checkpoint and firmware-side spec review.
- CyanPeak availability for code-to-spec review.

## Interfaces / State

- Reuses existing `SCALE_CTRL` (`0x0349`), `LOGIC_WIDTH` (`0x034A`), `LOGIC_HEIGHT` (`0x034B`) register fields.
- Requires a documented answer to whether `scaleY=1` on a bitmap means 320×240-in-640×480 (Option A) or something else.
- May require `libvdp` helper or example app update to make the semantics usable.

## Risks

- **Semantic deadlock:** Option A vs Option B both have merits; if BronzeGate and BrightForge disagree, the lane is blocked on PM.
- **SDRAM bandwidth:** repeating source rows at >1× may increase effective fetch rate; must verify against existing `Indexed2bppBacklogCoSim` budgets.
- **CDC / 3-bank rotation:** the fetch-ahead/grant machinery is sensitive to line boundaries; logicalY step boundaries must not violate the bank-ready/row-tag contracts hardened in `2bpp-bank-completion-rtl`.
- **Intra-byte modes:** 2bpp/1bpp modes depend on `pixelWithinByte`; remapping to `logicalX` must preserve byte/half-byte alignment.
- **Backward compatibility:** any change must not regress the existing 320×240 bitmap → 640×480 display behavior at scale 1×/default.

## Validation

- **Interface checkpoint:** BronzeGate concurs on semantics; spec updated in `docs/firmware/HOST_TRANSPORT_ABI.md` and `firmware/GOTCHAS.md`.
- **Sim:** new or extended co-sim driving `VdpTop` with bitmap/indexed content at 1×/2×/3×. Assertions:
  - 1× byte-identical to baseline.
  - >1× source rows/columns repeat correctly (no skip/dup).
  - Auto-center/bezel math matches predictions.
- **PnR:** TNS=0 all clocks; BSRAM delta tracked.
- **Review:** CyanPeak code-to-spec review.

## Audit Focus

- CyanPeak to review the semantic decision and its correspondence to the RTL implementation.
- BronzeGate to review host ABI impact and `libvdp`/example-app clarity.

## Exit Condition

This task is done when the bitmap/indexed fetch-side scaling semantics are agreed upon, documented, implemented, proven in co-sim and PnR, and reviewed by CyanPeak and BronzeGate.
