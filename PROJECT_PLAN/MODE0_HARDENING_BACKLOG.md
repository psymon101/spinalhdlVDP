# MODE0_HARDENING_BACKLOG.md

**Updated:** 2026-04-28  
**Purpose:** Prioritized backlog for closing the most important remaining shared `Mode0` gaps before opening harder future adapter lanes. This file converts the max-capabilities spec and coverage matrix into a practical work order.

---

## Why This Exists

The project now has enough planning structure to stop choosing future work by intuition:

- `MODE0_MAX_CAPABILITIES.md` defines how far `Mode0` should go
- `MODE0_COVERAGE_MATRIX.md` shows which categories are already strong, usable, or partial
- `MODE0_STOPLINES.md` defines how far we are allowed to push on Tang Nano 20K

This file turns that into:

- a prioritized hardening order
- bounded questions to answer next
- a rule for when to prefer substrate hardening over adapter implementation

---

## Decision Rule

Use this backlog when the project is at PM reassessment with no active lane.

Default rule:

- if a future adapter would mainly be blocked by a shared `Mode0` weakness, prefer the relevant hardening item here before opening that adapter
- if the shared primitive is already `Strong` and the remaining work is mainly semantic/presentation, an adapter lane is reasonable

This file is planning guidance, not status authority. `TASKS.md` remains the live execution ledger.

---

## Priority Order

### Priority A — Fetch Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. See `TASKS.md` live-lane history.*

**Why first:**

- fetch strength is central to several high-value future adapters
- the coverage matrix marks planar and shuffled fetch as only `Usable`, not `Strong`
- if this envelope is weak, adapters will start demanding platform-specific paths

**Main pressure served:**

- Amiga
- Atari ST
- ZX Spectrum
- stronger bitmap/C64 cases

**Outcome:**

- Planar, shuffled, and bitmap+attribute fetch paths were strengthened and hardware-proven
- Tasks 44/44B (raw bitmap + SDRAM fetch) completed and closed
- Gap analysis confirmed substrate is adapter-ready for fetch-dependent platforms

---

### Priority B — Sprite Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. See `TASKS.md` live-lane history. Followed by Sprite Phase 2 + 2-bis (pattern memory foundation + bppSel/priority hardening, also DONE).*

**Why second:**

- the sprite system is already real and useful, but the coverage matrix still marks it only `Usable`
- many future adapters pressure this category harder than the first C64 proof did

**Main pressure served:**

- Amiga
- Genesis
- Neo Geo
- stronger NES/C64/console edge cases

**Outcome:**

- Sprite descriptor envelope expanded (bppSel, priority levels, palette bank plumbing)
- Sprite pattern memory foundation rebuilt with BSRAM-backed storage
- Hardware proof delivered (Scenario 50, commit `39a7242`)
- Stop-line confirmed: envelope fits within Tang Nano 20K limits

---

### Priority C — Color / Window Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. BrightForge owner. All six sub-features proven (CW-1 through CW-6). Commit `0f5dc65`.*

**Why third:**

- the current repo has color-math/window work closed enough for groundwork, but the coverage matrix still calls it `Usable`
- this matters most once higher-layer and richer-adapter work becomes serious

**Main pressure served:**

- SNES
- Genesis

**Outcome:**

- CW-1: Runtime-writable palette RAM ✅
- CW-2: Sprite palette bank consumer ✅
- CW-3: `mathEnable` metadata → ColorMath gate ✅
- CW-4: Highlight mode ✅
- CW-5: Dual window + combination logic ✅
- CW-6: Per-layer window masking ✅
- Hardware proof: Scenarios 51 and 52 with RTSP capture evidence
- CyanPeak audit PASS #8654

---

### Priority D — Beam-Driven Automation Hardening ✅ DONE

**Why fourth:**

- raster triggers and Copper/HDMA-class groundwork already exist
- the current question is less "do we have it?" and more "is the envelope broad enough for richer use?"

**Main pressure served:**

- Amiga
- SNES
- Genesis
- Atari ST

**Main question to answer:**

- does the current beam-driven automation model need bounded table/channel hardening before stronger adapters rely on it heavily?

**Why after A/B/C:**

- there is already usable beam-driven machinery
- fetch and sprite pressure are more likely to force bad architectural decisions sooner

---

### Priority E — Adapter Lane Selection

Only after the higher-leverage shared hardening questions above are answered should the project choose its next harder adapter lane.

Most likely candidates after shared hardening:

- ZX Spectrum first serious adapter
- Amiga readiness-adjacent adapter work
- Genesis or SNES-class bounded adapter lane

The choice should be based on which hardening result says the substrate is now strong enough.

---

## Non-Priorities Right Now

These are specifically **not** the recommended next move:

- opening a hard Amiga adapter immediately
- opening a hard SNES adapter immediately
- adding platform-specific engines to work around shared-primitive weakness
- expanding `Mode0` in red-zone ways before the relevant envelope is measured

---

## Stop-Line Reminder

Every hardening item above must still pass `MODE0_STOPLINES.md`.

That means:

- no approval by optimism
- no SDRAM-heavy widening without a clear per-line budget
- no major growth without estimated LUT/FF/BSRAM/DSP impact
- no "we'll see if it fits later" planning

---

## Current PM Recommendation

The hardening backlog execution order has been:

1. **Mode0 Fetch Envelope Hardening** — DONE
2. **Mode0 Sprite Envelope Hardening** — DONE (including Phase 2 + 2-bis)
3. **Color / Window Envelope Hardening** — DONE (BrightForge, audit PASS #8654)
4. **Beam-Driven Automation Hardening** — DONE (BrightForge implementation `7c2a18b..6345fcc`; CyanPeak audit PASS #8660)

Only after Beam Hardening closes should adapter lanes open.

---

## What Not To Do

- Do not treat this backlog as permanent; update it if the coverage matrix changes materially.
- Do not convert every planning question into a giant refactor.
- Do not let a future adapter task silently absorb a shared hardening question just because the team is impatient.
