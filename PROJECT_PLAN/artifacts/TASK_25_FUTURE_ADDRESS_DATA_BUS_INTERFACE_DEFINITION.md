# Task 25 — Future Address/Data Bus Interface Definition

**Artifact version:** 1.0-draft  
**Author:** BronzeGate  
**Date:** 2026-04-23  
**Status:** artifact draft — awaiting CyanPeak audit  
**Coding authorized:** NO

---

## 1. Executive Summary

Task 25 defines how a future **parallel host bus** should attach to `spinalhdlVDP` without bypassing the safety guarantees already established by the QSPI / Host Interface + Copper work. The recommended strategy is:

- keep the existing host-side shadow register model (`VDP_ADDR`, `VDP_DATA`, `VDP_INC`, `VDP_STATUS`, `HOST_CTRL`)
- keep the existing internal **register-write FIFO** and **safe-boundary CommandParser** contract
- treat the future parallel bus as an **alternate host transport** into that same host register model, not as a new direct path into `VdpTop`

This preserves line-safe register application, avoids mid-line corruption, keeps Copper and host writes unified, and prevents a second incompatible control surface from appearing beside the QSPI path.

**Scope boundary:** definition only. No RTL implementation, no board pin reservation, no direct-write fast path into Mode0 primitives.

---

## 2. Problem Statement

The current repo already has a proven direction for host control:

- Task 24 was retired into the broader R5 host interface + Copper path
- later tasks already depend on the host-control surface as a real subsystem
- the current host model is intentionally **indirect** and **safe-boundary applied**

What remains undefined is the shape of a future **address/data style external bus** for hosts that want lower software overhead than serialized QSPI transactions.

Task 25 therefore answers:

1. what bus model should be exposed externally
2. how that bus maps onto the existing host shadow register model
3. what reads/writes are allowed
4. what must remain explicitly out of scope until a later implementation task

---

## 3. Current-State Constraints

### 3.1 Existing host-path contract

`TECH_SPEC_HOST_INTERFACE_AND_COPPER.md` already defines the key invariant:

- host writes do **not** mutate live VDP state directly
- host writes enqueue `{addr,data}` pairs into a FIFO
- the pixel-domain `CommandParser` applies them only at safe boundaries

That contract is more important than the transport. QSPI is just the current transport.

### 3.2 Existing host shadow register model

The current spec already defines these host-facing registers:

| Offset | Name | Purpose |
|---|---|---|
| `0x00` | `VDP_ADDR` | target address in internal VDP register space |
| `0x02` | `VDP_DATA` | write data; enqueue into FIFO |
| `0x04` | `VDP_INC` | auto-increment after each `VDP_DATA` write |
| `0x06` | `VDP_STATUS` | small readable status set |
| `0x08` | `HOST_CTRL` / `VDP_CTRL` | host-side control shadow bits |

Task 25 should not invent a second programming model when this one already exists.

### 3.3 Why a direct memory-mapped bus is rejected

A naive external parallel bus that directly drives `Mode0RegBus` or raw `VdpTop` internal state would:

- reintroduce mid-line corruption risk
- create different semantics between QSPI and parallel-bus hosts
- force every internal target to care about host timing
- split validation and documentation across two incompatible host paths

That would regress the architectural cleanup already achieved by the R5 host-interface direction.

---

## 4. Recommended Architecture

### 4.1 Top-level rule

> The future parallel bus is an **alternate host transport** that terminates in the same host shadow register block and FIFO semantics as QSPI.

Equivalent high-level path:

```text
External Host Bus
  -> Parallel Bus Adapter
  -> Host Shadow Registers
  -> Register-Write FIFO
  -> CommandParser (pixel domain)
  -> internal VDP register space
```

### 4.2 External bus model

The bus should be defined as a **host-asynchronous, MCU-friendly, non-burst-required interface**:

- `busAddr[N-1:0]`
- `busDataIn[15:0]`
- `busDataOut[15:0]`
- `busWr`
- `busRd`
- `busCs`
- `busReady` or `busWait`

Minimum data width recommendation:

- **16-bit data bus**

Rationale:

- the internal host shadow model is 16-bit oriented
- almost all current control/state writes are 16-bit words
- an 8-bit bus would force byte-strobe semantics or split writes everywhere

Address width recommendation:

- enough to expose the small host shadow window only; not the entire internal VDP register map directly

In other words, the external host addresses the **adapter register block**, not raw Mode0 internals.

### 4.3 External-visible address map

The parallel-bus adapter should expose the same host shadow window as QSPI:

| External offset | Name | Access | Meaning |
|---|---|---|---|
| `0x00` | `VDP_ADDR` | RW | target internal VDP register-space address |
| `0x02` | `VDP_DATA` | W | enqueue `(VDP_ADDR, data)` into FIFO |
| `0x04` | `VDP_INC` | RW | post-write address increment |
| `0x06` | `VDP_STATUS` | R | FIFO + timing status |
| `0x08` | `HOST_CTRL` | RW | host-side control shadow |

Optional future expansion may add:

- IRQ acknowledge/status mirror
- bulk-upload helper registers
- adapter-local configuration bits

But those should remain adapter-local and must not expose a bypass around the FIFO contract.

### 4.4 Read policy

Reads should remain intentionally narrow:

- `VDP_STATUS`
- adapter-local revision / capability words
- explicitly mirrored host shadow registers where useful

Reads should **not** imply a general read-back path into every internal Mode0 primitive.

If wide register read-back becomes necessary later, that should be a separate bounded task with its own architectural review.

---

## 5. Internal Attachment Strategy

### 5.1 Adapter responsibilities

The future `ParallelHostInterface` block should own:

- external bus timing / handshake
- CDC into the host-domain control block if needed
- host shadow registers
- auto-increment behavior
- FIFO enqueue on `VDP_DATA` writes
- status exposure on reads

It should **not** own:

- raster-timing decisions
- direct mutation of line state mid-line
- special-case writes into one primitive but not another

### 5.2 Reuse rule

Task 25 recommends that QSPI and parallel bus converge on a shared abstraction:

- common host shadow register block
- common FIFO-entry format
- common status register semantics
- common `CommandParser` / safe-boundary write path

That lets the project support multiple physical host transports without multiplying internal control models.

### 5.3 Arbitration rule

If both QSPI and a future parallel bus exist simultaneously, they must converge before the FIFO boundary. Recommended ordering:

```text
QSPI Host Adapter ----\
                        -> host-side arbiter -> shared FIFO -> CommandParser
Parallel Bus Adapter --/
```

The key rule is:

- only one shared FIFO / CommandParser path owns entry into the internal VDP register space

This avoids duplicated write-order logic and prevents QSPI vs parallel bus semantic drift.

---

## 6. Bus-Semantics Recommendation

### 6.1 Preferred protocol shape

Preferred future implementation target:

- simple asynchronous or strobed SRAM-like bus
- single outstanding transaction model
- explicit `ready/wait` backpressure
- no mandatory pipelining

Reason:

- easiest fit for small MCUs / CPLDs / retro host adapters
- easy to bridge into the existing host shadow register model
- avoids overcommitting to Wishbone/AXI-lite style semantics before there is a real integration need

### 6.2 Why not Wishbone / AXI-lite right now

Task 25 is a project-internal planning artifact, not a general IP packaging effort.

Defining Wishbone or AXI-lite now would add:

- protocol-specific complexity
- extra read/response semantics
- stronger assumptions about future integration environments

without any current board-level need. A thin local bus definition is sufficient for the repo’s present goals.

### 6.3 Byte-lane policy

For the first implementation task, require **full 16-bit word accesses only**.

Do not require byte enables in the first cut unless a real host integration proves they are necessary.

Reason:

- keeps adapter logic simpler
- matches current register granularity
- avoids partial-word corner cases in FIFO enqueue semantics

---

## 7. Timing / CDC Rules

The future implementation task must preserve these rules:

1. external bus timing is terminated inside the adapter
2. FIFO is the only required host-to-pixel crossing for queued writes
3. queued writes preserve strict order
4. live VDP state is updated only through the existing safe-boundary apply model
5. no transport may introduce a direct mid-line write path

If a future board runs the host bus in the same clock domain as the host adapter, the FIFO still remains the semantic isolation point.

---

## 8. Interaction With R5 / Copper

Task 25 does not replace or bypass the R5 direction. It complements it.

- R5 defines the internal register/control model and beam-driven automation
- Task 25 defines how a different **external transport** can feed that same model

Specific rule:

- the parallel bus writes the same host shadow registers that QSPI writes
- the Copper continues to share the same safe-boundary internal write path

This keeps beam-driven automation and host-driven updates architecturally aligned.

---

## 9. Explicitly Out of Scope

Task 25 does **not** authorize or define:

- direct external access to raw `Mode0RegBus`
- general internal read-back bus from all Mode0 primitives
- DMA over the external bus
- bus-mastering by the VDP
- cache-coherent host/VDP shared memory semantics
- board pin allocation or top-level FPGA integration details
- replacing QSPI as the sole supported host transport in the current repo state

Those can become later tasks if the need is real.

---

## 10. Proposed Follow-On Implementation Slice

If Task 25 is accepted, the later implementation task should stay bounded to:

1. add `ParallelHostInterface.scala`
2. mirror the existing host shadow register map
3. feed the existing FIFO / `CommandParser` path
4. expose only `VDP_STATUS` and adapter-local status reads
5. prove that parallel-bus writes and QSPI writes have equivalent downstream semantics

Required validation for that future task:

- simulation of write ordering, auto-increment, and backpressure
- proof that no active-line direct mutation path exists
- hardware proof on the active target board or a dedicated integration carrier, if one is added later

---

## 11. Decision Summary

Task 25 recommends the following project decision:

- **Adopt a transport-agnostic host programming model**
- **Keep QSPI and future parallel bus semantically identical above the transport layer**
- **Reject any future proposal that bypasses the shared FIFO + safe-boundary write path without a separate architectural re-approval**

This is the lowest-risk way to gain a faster or easier host attachment later without undoing the architectural cleanup already built into the repo.
