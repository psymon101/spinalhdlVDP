# READ_DONE CDC co-sim + compile proof (hardware-ready gate #14574)

**Lane:** qspi-upload-si-hardening (option 4) · **Owner:** BrightForge · **Date:** 2026-08-01
**RTL source commit:** `5ef5db2a` (branch `brightforge/read-done-diag`)
**Sim source:** `hw/spinal/spinalhdlvdp/ReadDoneCdcSim.scala`
**Raw log:** `simulation/read_done_cdc_cosim.log`

## `sbt compile` — PASS

```
[success] Total time: 2 s, completed Aug 1, 2026, 9:58:35 AM
```

Full RTL + sim tree compiles clean (SpinalHDL v1.12.3, JVM 16 GiB).

## `ReadDoneCdcSim` — ALL PASS

Models the exact hardened `dbgResultPixArea` handoff from `TopTang20kHdmi`:
- **sdram domain (40.5 MHz):** `dataReg` set when a read completes; `resultToggle` flips **one**
  sdram cycle later (the marginal data→toggle lead that produces the confirmed `sel=8` 1-read lag).
- **pixel domain (25.2 MHz):** hardened latch — `dbgResultHold := dataSync` only after a **2-cycle
  settle** past the synchronized toggle edge; `READ_DONE` set after that settled latch, cleared on
  the `0x0327` arm.

Test arms → completes a read with a known value → polls `READ_DONE` → checks the held word.
Values deliberately alternate so a 1-read lag (returning the PRIOR value) would be caught:

```
=== ReadDoneCdcSim: arm -> complete -> poll READ_DONE -> read (hardened handshake) ===
    white     : READ_DONE=true got=0x55555555 exp=0x55555555 pollcyc=7  OK
    black     : READ_DONE=true got=0x00000000 exp=0x00000000 pollcyc=7  OK
    white2    : READ_DONE=true got=0x55555555 exp=0x55555555 pollcyc=7  OK
    sentinel  : READ_DONE=true got=0xDEADBEEF exp=0xDEADBEEF pollcyc=7  OK
    white3    : READ_DONE=true got=0x55555555 exp=0x55555555 pollcyc=7  OK
    stale-guard: READ_DONE=false before completion (host correctly waits)  OK
=== ReadDoneCdcSim: ALL PASS (handshake logically correct; HW is the timing arbiter) ===
```

- Each armed read returns the **just-completed** value, never the prior one → the settled latch +
  `READ_DONE` poll eliminate the 1-read lag **logically**.
- `stale-guard`: `READ_DONE` stays low after arm until completion → host correctly blocks.

## HONESTY CAVEAT (must travel with this result)

Verilator models `BufferCC` as an **ideal 2-FF synchronizer with no metastability and no real-timing
margin**. This co-sim therefore proves the **handshake logic** (settle depth, arm/clear ordering,
poll-before-read discipline) is correct — it does **not** prove the real-silicon timing margin is
adequate. The hardware test at `0x100008` / `0x101000` is the arbiter of whether the lag is actually
eliminated on the board.

## Interpretation reminder (unchanged from #14566)

The corrected firmware double-read (#14563) still returned `0x00` on the second call at the target
addresses with `lag_matches=16/16`, `target_matches=0/16`. That leans the fork toward the
**write-side / physical** side, not a pure readback illusion. Expected outcome of the HW test is
therefore most likely `0x00` (reopen physical write-side investigation), but option 4 is the
definitive discriminator either way:
- `0x55555555` at both targets → SDRAM good; defect was the `sel=8` readback/CDC path.
- `0x00000000` at either target → SDRAM really holds `0x00`; reopen physical write-side hunt.
