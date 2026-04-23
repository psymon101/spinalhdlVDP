# MODE0_HARDENING_BACKLOG.md

**Updated:** 2026-04-23  
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

### Priority A — Fetch Envelope Hardening

**Why first:**

- fetch strength is central to several high-value future adapters
- the coverage matrix marks planar and shuffled fetch as only `Usable`, not `Strong`
- if this envelope is weak, adapters will start demanding platform-specific paths

**Main pressure served:**

- Amiga
- Atari ST
- ZX Spectrum
- stronger bitmap/C64 cases

**Main question to answer:**

- are the current planar / shuffled / bitmap+attribute fetch paths strong enough for serious adapter work, or only for bounded proof scenes?

**Expected outputs from the future task lane:**

- explicit gap analysis of planar fetch versus Amiga/ST pressure
- explicit gap analysis of shuffled/bitmap+attribute fetch versus ZX Spectrum pressure
- confirmation of what is adapter-local and what still belongs in substrate hardening
- evidence that the resulting strengthened path still fits `MODE0_STOPLINES.md`

**Why this is not an adapter lane:**

- the same strengthened fetch envelope benefits multiple platforms
- this is the clearest example of "shared primitive first, adapter later"

---

### Priority B — Sprite Envelope Hardening

**Why second:**

- the sprite system is already real and useful, but the coverage matrix still marks it only `Usable`
- many future adapters pressure this category harder than the first C64 proof did

**Main pressure served:**

- Amiga
- Genesis
- Neo Geo
- stronger NES/C64/console edge cases

**Main question to answer:**

- is the current sprite envelope strong enough to serve higher-pressure adapters without splitting into multiple platform-specific sprite engines?

**Expected outputs from the future task lane:**

- measure current descriptor/visibility/priority/metadata envelope against stronger target pressure
- identify which missing fields or rules belong in shared sprite machinery
- identify which platform quirks should remain adapter-local
- produce a clear stop-line-aware recommendation for any growth

**Why this is not just "do an Amiga adapter":**

- if the sprite envelope is weak, an Amiga adapter will either cheat or demand substrate rework mid-lane
- the same strengthening benefits several platforms

---

### Priority C — Color / Window Envelope Hardening

**Why third:**

- the current repo has color-math/window work closed enough for groundwork, but the coverage matrix still calls it `Usable`
- this matters most once higher-layer and richer-adapter work becomes serious

**Main pressure served:**

- SNES
- Genesis

**Main question to answer:**

- is the current color-math / window / post-compositor stage broad enough for honest SNES/Genesis-style semantics?

**Expected outputs from the future task lane:**

- enumerate what is already generic enough
- identify missing shared hooks
- quantify cost/risk against stop-lines before broadening this stage

---

### Priority D — Beam-Driven Automation Hardening

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

If the project opens exactly one next bounded planning/engineering lane, it should be:

- **Mode0 Fetch Envelope Hardening**

If the project opens a second after that, it should be:

- **Mode0 Sprite Envelope Hardening**

This keeps work aligned with the new project rule:

- shared capability first
- adapter-specific semantics second

---

## What Not To Do

- Do not treat this backlog as permanent; update it if the coverage matrix changes materially.
- Do not convert every planning question into a giant refactor.
- Do not let a future adapter task silently absorb a shared hardening question just because the team is impatient.
