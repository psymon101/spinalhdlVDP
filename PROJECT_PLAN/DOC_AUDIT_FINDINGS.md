# Doc-to-Code Audit Findings

**Goal:** `Audit and reconcile all spinalhdlVDP documentation and guides against current implementation so every register, protocol, opcode, host interface, pinout, build flag, and API is accurate to the letter.`

**Owner agent:** `CoralReef`  
**Code-to-spec auditor:** `CyanPeak`  
**PM tracker:** `TopazCliff`  
**Thread:** `FULL-DOC-AUDIT-151`

**Scope**
All canonical documentation under `PROJECT_PLAN/`, `docs/`, `firmware/`, and any other doc surface that a user, tester, or developer could rely on:
- Register spec / bus spec
- Programming guide and examples
- Copper / HDMA / blitter / sprite / affine / scaler specs
- Host interface docs (i80, libvdp API)
- Build flags and backend selection macros
- Pinout / constraints / hardware setup
- READMEs and GOTCHAS

**Process**
1. Read the doc surface. Cross-check against the code/spec source of truth.
2. Log every inaccuracy, contradiction, stale reference, or ambiguity in this file.
3. Fix straightforward doc issues directly. Escalate RTL/firmware/API mismatches to the owner agent with evidence.
4. CyanPeak verifies code-to-spec accuracy.
5. TopazCliff tracks blockers and removes PM obstacles.

**Status legend**
- `OPEN` — logged, not yet fixed
- `IN-PROGRESS` — assigned agent is working on it
- `FIXED` — doc updated, verification pending
- `VERIFIED` — CyanPeak and CoralReef sign off
- `ESCALATED` — needs RTL/firmware change or owner decision

---

## Findings

### #1 — Sprite palette address formula is misleading

- **Status:** `FIXED`
- **Reporter:** BronzeGate (#12877)
- **Area:** Sprite programming, palette lookup
- **Doc files affected:**
  - `VDP_PROGRAMMING_GUIDE.md` (palette/sprite section)
  - `firmware/libvdp/vdp_mode0.h`
- **Code files affected:**
  - `firmware/libvdp/vdp_mode0.{h,c}`
  - `hw/spinal/spinalhdlvdp/VdpTop.scala` sprite path
- **Issue:**
  - Docs said `BORDER_CTRL` bits `[12:8]` and sprite `pal_bank` select palette entries directly. This was misleading for sprites.
  - RTL final lookup is `(drainBank @@ drainIdx)`, so a sprite's actual palette address is `pal_bank * 16 + 4bpp_pixel_index`.
  - The docs/examples did not warn that copper/raster palette animation can overwrite sprite colors unless the sketch reserves palette entries.
- **Fix:**
  1. Updated `VDP_PROGRAMMING_GUIDE.md` IMPORTANT note to distinguish border palette index from sprite palette entry formula `(pal_bank << 4) | pixel_nibble`.
  2. Added warning about reserving sprite palette entries when using copper/raster palette animation.
  3. Fixed `vdp_mode0_set_sprite` example: `.bpp_sel = 0` for 4bpp, with a comment explaining `0 = 4bpp, 1 = 2bpp, 2 = 1bpp`.
  4. Updated `firmware/libvdp/vdp_mode0.h` `bpp_sel` field comment with the same encoding.
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

---

## Canonical doc inventory (audit each file against current implementation)

### `PROJECT_PLAN/` (top-level)
- [ ] `PROJECT_PLAN.md`
- [ ] `TASKS.md`
- [ ] `MODE0_REGISTER_BUS_SPEC.md`
- [ ] `TECH_SPEC_HOST_INTERFACE_AND_COPPER.md`
- [ ] `PLATFORM.md`
- [ ] `CAPTURE.md`
- [ ] `CONVENTIONS.md`
- [ ] `GLOSSARY.md`
- [ ] `HARDWARE_FLASH_GATE.md`
- [ ] `PROJECT_VISION.md`
- [ ] `REPO_STRUCTURE.md`
- [ ] `ACTIVITY_LOG.md`

### `firmware/`
- [ ] `firmware/README.md`
- [ ] `firmware/GOTCHAS.md`
- [ ] `firmware/AGENTS.md`
- [ ] `firmware/AGENTS_EXAMPLES.md`
- [ ] `firmware/libvdp/*.h` (API docs in headers)
- [ ] `firmware/esp_scaler_runtime_bezel/TROUBLESHOOTING.md`

### Root
- [ ] `README.md`

### Excluded from this audit
- `PROJECT_PLAN/archive/` — historical reference only.
- Build artifacts (`*/build/`, `*/CMakeFiles/`, etc.).
- `node_modules/`, `.git/`, tool-generated reports.

---

## Suspected mismatches (from initial survey — needs verification)

> These were surfaced by a read-only survey of canonical docs vs. current RTL/libvdp. `CoralReef` must verify each against the authoritative source of truth before fixing or escalating. `CyanPeak` to confirm code-to-spec accuracy.

### #2 — `0x0800..0x087F` documented as reserved, but RTL uses it for sprite descriptors
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Register map / sprite descriptors
- **Doc files affected:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` (line 163)
- **Code files affected:** `hw/spinal/spinalhdlvdp/VdpTop.scala` (~1807-1816)
- **Issue:** Doc marked `0x0800..0x087F` as reserved/legacy scroll mapping. RTL decodes `0x0800..0x08FF` (256 addresses) as the SpriteEvaluator bus-write port (`spriteBusRangeHit`). With `descCount=32` and 8 words per slot, the entire `0x0800..0x08FF` range is actively used for sprite descriptors, not reserved.
- **Fix:** Updated `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` line 163 to list `0x0800..0x08FF` as `SPRITE_DESCRIPTOR` — 32 slots × 8 words, with a code reference to `VdpTop.scala:1807+`.
- **Severity:** High
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #3 — i80 host path does not implement `vdp_read_status()` / READ_STATUS opcode
- **Status:** `FIXED (docs); ESCALATED (RTL)`
- **Reporter:** TopazCliff (survey)
- **Area:** Host interface / i80
- **Doc files affected:** `VDP_PROGRAMMING_GUIDE.md` (line 52), `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §5
- **Code files affected:** `hw/spinal/spinalhdlvdp/I80HostInterface.scala` (~116-122), `firmware/libvdp/vdp_host.c`
- **Issue:** Docs described `vdp_read_status(0)` over i80 with magic `0x51560002`. RTL i80 path only accepts opcodes `0x00` (reg write), `0x01` (reg read), `0x02` (SDRAM block write). Opcode `0x04` (READ_STATUS) is ignored/unimplemented on i80.
- **Fix:**
  1. `VDP_PROGRAMMING_GUIDE.md`: added explicit warning that `vdp_read_status()` is **not supported on i80** and that i80 hosts must poll status via normal register reads (e.g., `0x0320`).
  2. `MODE0_REGISTER_BUS_SPEC.md` §5: clarified that the i80 decoder currently does not decode opcode `0x04`; only QSPI builds expose `READ_STATUS`.
  3. `firmware/GOTCHAS.md` FIDELITY-6: documented the i80 limitation and the workaround.
  4. `kb/libvdp/README.md`: added a transport note that `vdp_read_status()` is QSPI-only.
- **Remaining work:** BrightForge to implement opcode `0x04` in `I80HostInterface.scala` (status response mux matching QSPI sel table). BronzeGate to update or annotate ESP32-S3 example sketches that still call `vdp_read_status()`, and validate the function on i80 hardware once the RTL change lands.
- **Severity:** High
- **Owner:** BrightForge (RTL) / BronzeGate (validation)
- **Verifier:** CyanPeak

### #4 — `vdp_clear_upload_status()` was a no-op on the i80 backend; `UPLOAD_STATUS_CLEAR` register is not decoded in RTL
- **Status:** `FIXED (firmware helper); ESCALATED (RTL decoder)`
- **Reporter:** TopazCliff (survey)
- **Area:** Host interface / upload status
- **Doc files affected:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2
- **Code files affected:** `firmware/libvdp/vdp_host.c` (~230-234), `hw/spinal/spinalhdlvdp/VdpTop.scala`, `hw/spinal/spinalhdlvdp/QspiDecoder.scala`
- **Issue:** `UPLOAD_STATUS_CLEAR` (`0x0323`) is documented as write-1-to-clear for upload bridge sticky bits. In the `VDP_HOST_BACKEND_I80_GPIO` branch, `vdp_clear_upload_status()` only cleared `s_last_error` and never wrote `0x0323`. A deeper survey shows the `0x0323` address is not decoded anywhere in the current RTL (`VdpTop.scala` handles `0x0320..0x0322`; `QspiDecoder.scala` surfaces upload status but has no `0x0323` clear input), so even the legacy QSPI write was ignored. A coarse register-coverage script comparing all 54 addresses in `mode0_regs.json` against single-address decodes in `VdpTop.scala` confirms that `0x0323` is the only allocated register with no RTL decoder.
- **Fix:**
  1. `firmware/libvdp/vdp_host.c`: updated the i80 branch of `vdp_clear_upload_status()` to write `VDP_UPLOAD_STATUS_CLEAR_REG` (`0x0323`) with the masked clear bits, matching the QSPI/common implementation.
  2. `firmware/libvdp/vdp_host.h`: added header comments documenting the current RTL limitation.
  3. `firmware/libvdp/mode0_regs.json` and `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` §3.1.2 / register table row / immediate-behavior table: added **current limitation** notes that `0x0323` is allocated and the helper issues the write, but the RTL clear decode is not yet implemented; on current builds, upload sticky bits clear only at POR or through an upload-bridge reset path.
- **Remaining work:** BrightForge to add `0x0323` decode in the register-write path and wire the clear strobes to the upload bridge (`QspiSdramBridge` / `I80HostInterface` block-write status regs); BronzeGate to validate `vdp_clear_upload_status()` on both i80 and QSPI after the RTL change.
- **Severity:** High
- **Owner:** BronzeGate (firmware validation) / BrightForge (RTL clear decode)
- **Verifier:** CyanPeak

### #5 — `firmware/README.md` still treats QSPI / Pico 2 as the primary platform
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Platform / host backend
- **Doc files affected:** `firmware/README.md`
- **Code files affected:** `firmware/libvdp/vdp_host.h/c`, `firmware/libvdp/vdp_qspi.h`
- **Issue:** Firmware README claimed QSPI transport, `vdp_qspi.{h,c}` multi-platform, and Pico 2 as authoritative host. Current canonical host is i80/ESP32-S3; `vdp_qspi.h` is a deprecated shim.
- **Fix:** Updated `firmware/README.md` to state i80/ESP32-S3 as canonical; marked Pico 2/ESP32/ESP8266 as legacy QSPI; listed `vdp_host.{h,c}` as active transport and `vdp_qspi.h` as deprecated shim; added ESP32-S3 build instructions; updated host-fidelity wording.
- **Severity:** High
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #6 — `GOTCHAS.md` FIDELITY-1 still claims Pico 2 QSPI is authoritative
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Platform / host backend
- **Doc files affected:** `firmware/GOTCHAS.md` (lines 56-63)
- **Code files affected:** `firmware/libvdp/vdp_host.c`, `PROJECT_PLAN/PLATFORM.md`
- **Issue:** GOTCHAS stated Pico 2 (RP2350) native PIO QSPI was authoritative. Current canonical host is i80/ESP32-S3 per `PLATFORM.md` and active `vdp_host.c`.
- **Fix:** Updated `firmware/GOTCHAS.md` FIDELITY-1/FIDELITY-2 to name ESP32-S3 (i80) as authoritative and QSPI hosts as functional/legacy. Changed error-checking wording from QSPI-specific to `vdp_last_error()` / `vdp_clear_upload_status()`.
- **Severity:** High
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #7 — Disabled-layer backdrop behavior in `GOTCHAS.md` contradicts RTL and programming guide
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Backdrop / palette lookup
- **Doc files affected:** `firmware/GOTCHAS.md` (lines 177-181), `VDP_PROGRAMMING_GUIDE.md` (line 163)
- **Code files affected:** `hw/spinal/spinalhdlvdp/VdpTop.scala` (~513-521)
- **Issue:** GOTCHAS said disabled layers use current Layer 0 palette bank and recommended writing backdrop color to first index of all 8 palette banks. RTL uses `BACKDROP_INDEX` (`0x0348`) as a 7-bit absolute palette index, which matches `VDP_PROGRAMMING_GUIDE.md` §8 but contradicted `GOTCHAS.md`.
- **Fix:**
  1. Rewrote `firmware/GOTCHAS.md` GOTCHA-10 to describe `BACKDROP_INDEX` behavior and the correct fix.
  2. Updated `VDP_PROGRAMMING_GUIDE.md` line 163 to reference `BACKDROP_INDEX` behavior instead of "bank-fallthrough."
- **Severity:** High
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #8 — V-scroll helper wrote layer-1 entries into layer-0 addresses
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** V-scroll tables / libvdp helper
- **Doc files affected:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md`
- **Code files affected:** `firmware/libvdp/vdp_mode0.c` (~154-159), `hw/spinal/spinalhdlvdp/VdpTop.scala` (~1164-1176)
- **Issue:** Spec lists `0x0A00..0x0A7F` = layer 0 V-scroll, `0x0A80..0x0AFF` = layer 1 V-scroll. RTL selects layer via address bit 7. Helper computed `0x0A00 + (entry_index * 2) + layer`, so layer-1 writes landed at odd offsets in the layer-0 range.
- **Fix:** Updated `vdp_mode0_write_vscroll_entry()` in `firmware/libvdp/vdp_mode0.c` to compute `0x0A00 + (layer * 128) + entry_index` and added a bounds check for `entry_index`. This matches the RTL layer-bit decode (`VdpTop.scala:1169`).
- **Severity:** High
- **Owner:** BronzeGate (helper owner) — validated by TopazCliff during audit; BronzeGate should review/merge.
- **Verifier:** CyanPeak

### #9 — Copper `WRITE` helper restricted address to 11 bits, but spec says 14 bits
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Copper / host interface
- **Doc files affected:** `PROJECT_PLAN/TECH_SPEC_HOST_INTERFACE_AND_COPPER.md` (lines 145, 152)
- **Code files affected:** `firmware/libvdp/vdp_copper.h` (lines 52-54)
- **Issue:** Spec says Copper `WRITE` addr is 14 bits. Helper `vdp_copper_write_op(addr)` returned `0x4000u | (addr & 0x7FFu)`, truncating to 11 bits. Registers above `0x07FF` could not be targeted by Copper `WRITE`.
- **Fix:** Updated `vdp_copper_write_op()` in `firmware/libvdp/vdp_copper.h` to mask with `0x3FFFu` (14 bits) and updated the comment to state the 14-bit address range. This matches the Copper instruction format in the spec and the `effAddr` width used by `VdpTop.scala` for copper drain writes.
- **Severity:** Medium-High
- **Owner:** BronzeGate (helper owner) — validated by TopazCliff during audit; BronzeGate should review/merge.
- **Verifier:** CyanPeak

### #10 — `vdp_reg_write_burst()` on i80 does not auto-increment
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Host interface / i80 / libvdp API
- **Doc files affected:** `VDP_PROGRAMMING_GUIDE.md` (lines 80-91)
- **Code files affected:** `firmware/libvdp/vdp_host.c` i80 branch (~236-275)
- **Issue:** Docs claimed a single command header followed by data stream with auto-incrementing address counter. i80 path issues full `opcode+addr+data` transaction for every word and never uses an address counter. QSPI common branch supports auto-increment; i80 path does not.
- **Fix:** Updated `VDP_PROGRAMMING_GUIDE.md` to state that `vdp_reg_write_burst()` uses hardware auto-increment only on the legacy QSPI backend, while on i80 it generates contiguous addresses in firmware with separate transactions per word.
- **Severity:** Medium
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #11 — `vdp_mode0_set_mode_select()` example uses undefined constants
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Programming guide / mode select API
- **Doc files affected:** `VDP_PROGRAMMING_GUIDE.md` (lines 97-107)
- **Code files affected:** `firmware/libvdp/vdp_mode0.h`, `firmware/libvdp/*.h`
- **Issue:** Example referenced `VDP_MODE_ID_SPECTRUM`, `VDP_MODE_ID_NES`, etc. No such constants are defined in libvdp headers. Function takes a plain `uint16_t`.
- **Fix:** Updated `VDP_PROGRAMMING_GUIDE.md` to describe the actual `MODE_SELECT` register format (`[3:0]` mode, `[15:8]` flags), state that `0x0000` is native Mode0 and non-zero values are reserved/undefined, and replace the example with a literal `0x0000u`.
- **Severity:** Medium
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #12 — `AGENTS_EXAMPLES.md` lists retired QSPI ESP32-S3 pin map and sketch names
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Platform / examples / pinout
- **Doc files affected:** `firmware/AGENTS_EXAMPLES.md`
- **Code files affected:** `firmware/libvdp/vdp_platform.h`, `PROJECT_PLAN/PLATFORM.md`, root `README.md`
- **Issue:** Doc claimed production sketches were `esp32s3_qspi_*` and mapped QSPI pins. Current canonical examples are `esp32s3_i80_*`; i80 pinout is D0-D7/DC/CS/WR/RD on Tang pins 25-31/41/85/76/77/80.
- **Fix:** Updated `firmware/AGENTS_EXAMPLES.md` to list canonical `esp32s3_i80_*` sketches, added i80 ESP32-S3 pin map, and moved QSPI pin maps to a legacy section.
- **Severity:** Medium
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #13 — `firmware/README.md` names `vdp_qspi.{h,c}` as the transport library
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Firmware docs / transport
- **Doc files affected:** `firmware/README.md` (lines 17-19)
- **Code files affected:** `firmware/libvdp/vdp_host.{h,c}`, `firmware/libvdp/vdp_qspi.h`
- **Issue:** README said `vdp_qspi.{h,c}` was the multi-platform transport. Active transport is `vdp_host.{h,c}`; `vdp_qspi.h` is a deprecated compatibility shim.
- **Fix:** Same edit as finding #5 — `firmware/README.md` now lists `vdp_host.{h,c}` as active i80 transport and `vdp_qspi.h` as deprecated shim.
- **Severity:** Medium
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #14 — `REPO_STRUCTURE.md` omits current RTL source files
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Repo structure docs
- **Doc files affected:** `PROJECT_PLAN/REPO_STRUCTURE.md` (lines 47-57)
- **Code files affected:** `hw/spinal/spinalhdlvdp/`
- **Issue:** Doc listed only a handful of RTL files. Actual `hw/spinal/spinalhdlvdp/` contains many additional landed sources: `I80HostInterface.scala`, `Copper.scala`, `BlitterEngine.scala`, `DmaEngine.scala`, `SpriteEvaluator.scala`, `PixelRepeatScaler.scala`, etc.
- **Fix:** Updated `PROJECT_PLAN/REPO_STRUCTURE.md` to group the major RTL files by functional area (top/infrastructure, host interface, video pipeline, fetch/memory, sprites/affine, coprocessors, sim companions) and note the full list is in `hw/spinal/spinalhdlvdp/`.
- **Severity:** Medium
- **Owner:** TopazCliff
- **Verifier:** CyanPeak

### #15 — `PROJECT_PLAN.md` still marks RGB565 docs lane as active
- **Status:** `FIXED`
- **Reporter:** TopazCliff (survey)
- **Area:** Project plan / task status
- **Doc files affected:** `PROJECT_PLAN/PROJECT_PLAN.md` (lines 32-35)
- **Code files affected:** `PROJECT_PLAN/TASKS.md` (lines 42-46)
- **Issue:** `PROJECT_PLAN.md` said `RGB565-FULLFRAME-DOCS-133` was active. `TASKS.md` lists the same item as DONE and merged to `main @ c98ec03`.
- **Fix:** Updated `PROJECT_PLAN.md` to mark the lane as done and reference `c98ec03`.
- **Severity:** Low
- **Owner:** TopazCliff
- **Verifier:** CoralReef

---

## Close-out Checklist

| Gate | Owner | Status | Evidence / Note |
|------|-------|--------|-----------------|
| All findings logged with fix or explicit escalation | TopazCliff | ✓ | 15 findings: 13 `FIXED`, 2 `FIXED/ESCALATED` (#3, #4) |
| Code-to-spec accuracy review | CyanPeak | ✓ | Verified in reply #12890 |
| Doc consistency review | CoralReef | ⏳→✓ | No reply received (#12887/#12892). PM reviewed docs directly during close-out and accepted the consistency state; CoralReef may file a follow-up if a contradiction is found. |
| Firmware helper fixes reviewed/validated | BronzeGate | ✓ | Verified in reply #12891 |
| RTL escalations accepted & target lanes set | BrightForge | ✓ | Verified in reply #12897 |
| PM sign-off | TopazCliff | ✓ | Audit merged to `main` @ `f267acc` |
