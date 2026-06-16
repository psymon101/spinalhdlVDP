# spinalhdlVDP Activity Log

**Purpose:** Chronological record of all significant project activity — agent mail, builds, simulations, code changes, PM decisions, and errors. This is the source of truth for "what happened when."

**How to read:** Entries are newest-first. Each entry links to evidence (mail IDs, commit hashes, artifact paths). Use `scripts/log_activity.py query` to filter.

**Agents:** When you complete a checkpoint, hit a blocker, or observe an error, append an entry. One line of summary is enough — the links do the heavy lifting.

---

<!-- END HEADER -->

## 2026-06-16T18:07:57-04:00 | CyanPeak | RESEARCH
**Tags:** `project-audit-141`

Code-to-spec audit passed for BronzeGate's host-neutral API cleanup (vdp_host, platform aliases, and docs).

**Evidence:**
- **Mail:** #12727

---
## 2026-06-16T17:58:18-04:00 | CyanPeak | RESEARCH
**Tags:** `project-audit-141`

Code-to-spec audit passed. Uncommitted firmware/RTL aligns with documentation regarding host naming, soft-reset, and register bit-widths.

**Evidence:**
- **Mail:** #12721

---
## 2026-06-16T14:24:58-04:00 | CyanPeak | DECISION
**Tags:** `qspi-deprecate`

Lane officially closed by TopazCliff. Code-to-spec review PASS. Cleanup items tracked for future hygiene lane.

**Evidence:**
- **Mail:** #12707

---
## 2026-06-16T09:47:57-04:00 | CyanPeak | RESEARCH
**Tags:** `qspi-deprecate`

Verified vdp_host.h/c encapsulates existing CMD 0x01/0x02/0x04 register and status contracts. Deprecated aliases in vdp_qspi.h maintain backward compatibility. I80 implementation verified.

**Evidence:**
- **Mail:** #12706

---
## 2026-06-16T07:15:56-04:00 | CyanPeak | RESEARCH
**Tags:** `qspi-deprecate`

Verified HOST_READY/ERROR renames in headers, JSON, and docs. Confirmed Lx_TRANS_KEY (4-bit) and PLANAR_WIDTH (10-bit) bit-widths in RTL and firmware. Soft-reset docs verified.

**Evidence:**
- **Mail:** #12684

---
## 2026-05-25T20:34:37-04:00 | TopazCliff | MERGE
**Tags:** `scaler-lane`

Scaler re-land Step 1 merged to main

**Evidence:**
- **Mail:** #10702
- **Commit:** `a29e293701a8ded384e7238428055920b730aeb7`

---
## 2026-05-25T23:20:24-04:00 | TopazCliff | DECISION
**Tags:** `scaler-lane` `pm-decision`

Authorized scaler re-land Step 1 (OOB-write guard + 1x bypass + 3-build panel) and answered BrightForge's three questions: same-commit fix, inspect POR during impl, libvdp gap deferred to follow-up firmware lane.

**Evidence:**
- **Mail:** #10701
- **Commit:** `661907d` (main baseline for branch)

---

## 2026-05-25T23:15:52-04:00 | TopazCliff | MAIL
**Tags:** `scaler-lane` `reminder`

Sent reminder to BrightForge and BronzeGate to respond to #10696 scaler re-land authorization. Project idle until response.

**Evidence:**
- **Mail:** #10698

---

## 2026-05-25T22:57:06-04:00 | TopazCliff | RESEARCH
**Tags:** `scaler-lane` `hypothesis-assessment`

Assessed BrightForge's scaler #10590 hypothesis (palette readAsync caused black-screen, not scaler). Verdict: STRONG support. Found BlitterEngine `bab5c5f` as exact precedent. Identified ~20 remaining readAsync Mems in tree. Noted BronzeGate found independent OOB-write bug in PixelRepeatScaler lineBuf.

**Evidence:**
- **Mail:** #10696
- **Commit:** `dc1fba8` (scaler disconnect, root-cause note)
- **Commit:** `bab5c5f` (BlitterEngine readSync precedent)

---

## 2026-05-25T22:52:52-04:00 | TopazCliff | DECISION
**Tags:** `palette-lane` `merge`

Acknowledged BrightForge merge confirmation. Palette lane officially closed.

**Evidence:**
- **Mail:** #10694
- **Commit:** `661907d` (merge commit on main)

---

## 2026-05-25T22:02:18-04:00 | TopazCliff | DECISION
**Tags:** `palette-lane` `cp-c`

CP-C hardware proof accepted. Authorized merge of `brightforge/palette-readsync` to `main`. Palette lane closed. Listed three candidate next lanes (scaler unsuspend, reset-pin, planar hardening).

**Evidence:**
- **Mail:** #10692
- **Mail:** #10691 (CP-C final packet)
- **Commit:** `93e5924` (palette readSync + ram_style)

---

## 2026-05-25T21:12:27-04:00 | TopazCliff | MAIL
**Tags:** `palette-lane` `urgent`

Sent urgent reminder to BrightForge: critical path blocked on CP-C packet. Explicit 2-hour turnaround estimate.

**Evidence:**
- **Mail:** #10689

---

## 2026-05-25T20:32:29-04:00 | TopazCliff | DECISION
**Tags:** `palette-lane` `cp-c`

Authorized BrightForge to execute CP-C hardware proof (option 1). Defined 3-step scope: single build + 2-scene capture, BSRAM inference comparison, 3-build determinism panel. Explicit stop conditions listed.

**Evidence:**
- **Mail:** #10688

---

## 2026-05-25T20:02:33-04:00 | TopazCliff | DECISION
**Tags:** `palette-lane` `pm-decision`

Replied to BrightForge #10685 with answers to three questions: merge-commit, CONVENTIONS.md in merge commit, readSync + ram_style="block". Authorized Phase 1–3 (merge canary + open palette lane + CP-B sim proof).

**Evidence:**
- **Mail:** #10686

---

## 2026-05-25T19:23:27-04:00 | BrightForge | MAIL
**Tags:** `canary-lane` `status`

Detailed status report. Waiting for operator OK to merge canary to main. Three open questions for PM resolution. Proposed CONVENTIONS.md addition text. Plan: Phase 1 (merge) → Phase 2 (palette) → Phase 3 (CP-B sim) → Phase 4 (CP-C hardware).

**Evidence:**
- **Mail:** #10685

---

## 2026-05-25T18:59:03-04:00 | BrightForge | BUILD
**Tags:** `canary-lane` `3-build-panel`

3-build determinism panel complete: 3/3 TRANSPORT_PASS. Four distinct bitstream sha1s from identical source confirm Gowin non-determinism, but canary path is functionally stable (cyan_fraction 0.923 on all builds).

**Evidence:**
- **Mail:** #10683
- **Artifact:** `fpga/tang20k/captures/canary_cp_a_3ff6d45b.json`

---

## 2026-05-25T16:11:00-04:00 | TopazCliff | CODE
**Tags:** `mode0-docs` `cleanup`

Created MODE0_questions.md consolidating all questions from MODE0_REGISTER_BUS_SPEC.md and MODE0_T20_EXTERNAL_ENGINEER_QUESTIONS.md. Cleaned spec docs of inline questions. Archived TEST_PATTERN_POLICY.md.

**Evidence:**
- **Artifact:** `PROJECT_PLAN/MODE0_questions.md`
- **Artifact:** `PROJECT_PLAN/ACTIVITY_LOG.md` (this file)

---
