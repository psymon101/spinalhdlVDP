# VDP Soft-Reset Stage 4 — Core Register Reset Partition Plan (for CyanPeak review)

Lane **VDP-SOFT-RESET-135**, Stage 4 (final). Goal: on soft reset, return the
VDP's registers to their SpinalHDL `init` values, WITHOUT resetting the parts
that must stay live to run the reset + keep the host synchronized. Builds on
CyanPeak's partition sketch (#12589). **Design-first — review before I build.**

## Partition boundary (LIVE vs RESETTABLE)
**LIVE (must NOT be reset — they drive/observe the reset or keep host sync):**
- Soft-reset controller regs (in VdpTop): `softResetRequest`, `softResetBusy`,
  `softResetMemClear`, `softResetMemAddr`, `softResetFillStage` + the FSM, and the
  `VDP_CTRL[2]` request-decode. (If these reset, the reset can't run or auto-clear.)
- The `0x0310` busy-status path feeding the i80 readback (so the host can poll
  *during* reset).
- (Already external to VdpTop's pixel domain, untouched:) `I80HostInterface`,
  `RegBusArbiter`/decoder, the SDRAM controller + arbiter (sdram clock domain).

**RESETTABLE (→ `init`):**
- The host-visible config register file (~40-60 regs + pend/commit shadows):
  `layerEnableReg`, `modeSelectReg`/`modeSelectFlagsReg`, `bitmap*/attr*` geometry,
  scroll regs, `border*`/`innerBorder*`, `win*`/`win2*`/`winComb`, `colorMathReg`,
  `layerMaskReg`, `backdropIndexReg`, `scaleCtrlReg`, `logicWidth/HeightReg`,
  `copperCtrlReg`, `tileDecodeModeReg`/`attributeModeReg`, raster-trigger config, …
  — plus the new #3/#4 regs (`L0..L3_TRANS_KEY` 0x0314-0x0317 → 0, `PLANAR_WIDTH`
  0x0D4B → 320) once they land.
- Internal pipeline/FSM regs: `hCounter`/`vCounter`/`fillLine`, copper `pc`/`activeBank`,
  compositor/scaler pipeline regs.

## Mechanism — the decision I need CyanPeak to rule
VdpTop has **no existing ClockingArea** around its body; the ~64 config-commit
sites + pipeline regs are all in the default (pixel) domain, and the controller
is embedded among them. Two ways to reset them to `init`:

- **Option A — ClockDomain `softReset` (comprehensive).** Create
  `coreCd = ClockDomain.current.copy(softReset = coreSoftReset)` and wrap VdpTop's
  resettable body in `new ClockingArea(coreCd)`, lifting the ~5 controller regs +
  the `0x0310` decode/status OUT into the default domain. **Pro:** truly *all*
  regs → init, matches the contract literally; one mechanism. **Con:** a large
  structural refactor of a proven ~2400-line component (indentation/scoping of the
  whole body) — real regression risk across the entire VDP.

- **Option B — targeted explicit reset (surgical).** Add
  `when(coreSoftReset) { reg := <init>; pendHit := False }` at each config
  register's existing site (~40-60 regs + shadows). **Pro:** low structural risk,
  no refactor, localized + reviewable diffs. **Con:** must enumerate each config
  reg (and match its init expression); internal pipeline/counter regs are NOT
  reset — they re-settle naturally within one frame (video re-locks), which is
  POR-equivalent for host-visible behavior but is *not* literally "all registers."

**My recommendation: Option B**, on risk grounds — the memories (Stages 2/3) +
config regs are the host-visible state that produces artifacts; the pipeline regs
re-settle within a frame and never hold host config. Option A's whole-component
refactor is high-risk for marginal benefit (resetting counters that re-settle
anyway). But this hinges on whether "all registers → init" is a hard contract
requirement or a means to POR-equivalence — **your call.**

## Reset assert/release (both options)
- `coreSoftReset` asserts as a controller stage AFTER the SDRAM fill completes
  (chain: memClear → SDRAM fill → **core reg reset** → done). Hold for ≥1 cycle.
- **Release synchronously**, timed so video outputs don't glitch a short pulse
  (CyanPeak #12589 safety rule) — assert/deassert on a clean boundary (e.g., at
  `hCounter==0`), then let the pipeline re-lock before `softResetBusy` drops.
- `softResetBusy` stays high across this stage; `VDP_CTRL[2]` auto-clears after.

## Proof plan
- Extend `SoftResetHandshakeSim`: preset config regs (e.g. `layerEnableReg`,
  `borderX0Reg`, scroll) non-zero via the reg bus, trigger reset, assert they read
  back `init` after `softResetBusy` drops; controller regs survive; no deadlock.
- Re-run full regression + i80 STA (timing of the added reset fan-out).

## Open questions for CyanPeak
1. **Option A vs B** — comprehensive-but-invasive vs surgical-config-only? (I lean B.)
2. If B: confirm leaving pipeline/counter regs to re-settle is acceptable (they
   hold no host config; video re-locks within a frame).
3. Reset-release boundary: `hCounter==0` + N-cycle relock window — acceptable?
