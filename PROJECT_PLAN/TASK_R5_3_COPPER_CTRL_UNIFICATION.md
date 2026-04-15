# TASK_R5_3_COPPER_CTRL_UNIFICATION.md

**Status:** OPEN (CyanPeak drafting)
**Created:** 2026-04-15
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R5.3 Copper Control Unification (`VDP_CTRL` register)

---

## 2. Purpose

Clean up the top-level control interface by replacing the standalone `io.copperEnable` input with a bit in a unified control register (`VDP_CTRL` at `0x0310`). This follows the architectural direction of indirect register control and ensures that enabling/disabling the copper coprocessor is subject to the same safe-boundary commit logic as other rendering state.

**Why now:**
- R5.2 resolved the copper combinational-write stutter.
- R4.1b/c successfully used the safe-boundary commit pattern for new mode registers.
- R5.3 is the final planned "cleanup" of the R5 Host Interface lane.

---

## 3. Primitive Boundary

### In Scope

- **`VDP_CTRL` Register**:
  - Address: `0x0310`.
  - Bit[0]: `copperEnable`.
  - Implementation: uses the shadow + commit-at-`hCounter===0` pattern.
- **Top-level Cleanup**:
  - Remove `copperEnable` from `VdpTop` and `TopTang20kHdmi` IO bundles.
  - Wire the live `copperEnableReg(0)` bit to the `copper.io.enabled` port.
- **Bootstrap Update**:
  - Update `TopTang20kHdmi` bootstrap FSM to write `0x0310 = 1` instead of driving a direct port.
- **Sim Verification**:
  - Update `UnifiedRegMapSim` to verify `VDP_CTRL` safe-boundary commit.
  - Regression check of all R5 copper simulation cases.

### Explicitly Out of Scope

- **NO changes to Copper opcodes**.
- **NO new VDP features**.
- **NO changes to other registers**.

---

## 4. Dependencies

- R4.1c verified baseline (`0e4d9dc`).
- R5.2 safe-boundary register pattern.

---

## 5. Interfaces

### Updated `VdpTop` IO

```scala
case class VdpTop() extends Component {
  val io = new Bundle {
    // ...
    // REMOVED: val copperEnable = in Bool()
    // ...
  }
}
```

### New Register

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0310` | `VDP_CTRL` | 16 | bit[0]=1 Copper Enable |

---

## 6. Data Model

- **`copperEnableReg`**: live bit driving the copper.
- **`copperEnablePend`**: shadow register latched on bus write.

---

## 7. Timing Model

- **Atomic Commit**: Changes to copper enable state take effect only at the start of a scanline (`hCounter === 0`).

---

## 8. Memory / Bandwidth Impact

- **None**: Logic refactor.

---

## 9. Platform Reuse

- All Mode0 platforms.

---

## 10. Failure Modes / Risks

- **Bootstrap race**: Ensure the bootstrap FSM writes `VDP_CTRL` *after* the copper program is fully uploaded.
- **Sim regression**: Breaking existing tests that expect the `copperEnable` port.

---

## 11. Validation Plan

- **`UnifiedRegMapSim`**: New case verifying `0x0310` write → shadow → commit sequence.
- **Full regression**: All 11 sims must pass with the port removed and replaced by register writes.

---

## 12. Hardware Proof

- Existing 60fps proof scene: verify copper still starts and runs correctly using the register-based enable.

---

## 13. Audit Questions

- Is `io.copperEnable` completely removed from the IO bundles?
- Does the `VDP_CTRL` register correctly use the safe-boundary commit logic?
- Is the bootstrap FSM updated to match?

---

## 14. Constraints / Gotcha Check

- **GT-022**: Logic-only, no memory impacts.

---

## 15. Exit Condition

- This task is done when the `copperEnable` port is removed, replaced by `VDP_CTRL @ 0x0310`, and verified clean across all sims and hardware.
