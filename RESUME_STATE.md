<state_snapshot>
    <identity>
        Canonical agent: BronzeGate
        Role: MCU firmware / ESP32-S3 bench owner for spinalhdlVDP
        Repo: /home/itadmin/github/spinalhdlVDP
        Mail project key: /home/itadmin/github/spinalhdlVDP
        Source-of-truth order: latest mail, PROJECT_PLAN/TASKS.md live lane, repo state
    </identity>

    <reboot_resume_context>
        User requested: save state before reboot and resume after system returns.
        Current date: 2026-06-18
        Latest mailbox action: replied as #12865 to TopazCliff, cc BrightForge.
        Required next action after reboot: check mail first, then continue only from latest mail.
    </reboot_resume_context>

    <active_i80_state>
        Current classification:
        - Sparse i80 loopback failures are bench wiring signal-integrity / simultaneous-switching / crosstalk / ground-bounce.
        - Not firmware timing, not FPGA sampling, not SDRAM.
        - Stop i80 code experiments until the physical harness is improved.

        Evidence:
        - BrightForge #12855 reproduced the issue.
        - 4 MHz baseline: 8 failures / 33 bit errors, fixed 0xA55A/0x1234 clean.
        - 4 MHz plus extra WR setup delay: 7 failures / 25 bit errors, fixed pair clean.
        - 1 MHz writes: 7 failures / 20 bit errors, fixed pair clean.
        - Rate-independent, value-dependent, high-byte-only failures.

        Physical next step:
        - Shorten i80 data/WR jumpers.
        - Add solid ground returns between signals, especially around WR and data.
        - Consider 33-100 ohm series resistors on data + WR.
        - After harness work, rerun test12 at 4 MHz baseline.
        - Sweep upward only after 4 MHz baseline is clean.
    </active_i80_state>

    <current_flashed_firmware>
        ESP32-S3 currently flashed with:
        firmware/esp32s3_test13_i80_scope_toggle/esp32s3_test13_i80_scope_toggle.ino

        Purpose:
        - Owner-facing scope diagnostic.
        - Pure GPIO free-run, no FPGA protocol interaction.
        - All D0-D7 plus WR toggle continuously at 5 MHz.
        - CS# and RD# held idle high, DC held low.

        Boot proof:
        ESP32S3_I80_SCOPE_TOGGLE
        target_hz=5000000
        half_period_cycles=24
        mode=all_data_plus_wr
        data_mask=0xFF0
        wr_pin=17

        Compile:
        arduino-cli compile --fqbn esp32:esp32:esp32s3 --library firmware/libvdp firmware/esp32s3_test13_i80_scope_toggle

        Flash:
        arduino-cli upload --fqbn esp32:esp32:esp32s3 -p /dev/ttyACM0 firmware/esp32s3_test13_i80_scope_toggle

        Single-line baseline:
        - In test13 set kToggleAllDataLines=false.
        - Set kSingleDataBit to 0..7.
        - Rebuild and flash.
    </current_flashed_firmware>

    <test12_state>
        Test12 source:
        firmware/esp32s3_test12_i80_link_stress/esp32s3_test12_i80_link_stress.ino

        Current source state:
        - Trace discriminator source exists locally.
        - kTraceHz=4000000.
        - kTraceWrites=4096.
        - Safe readback at 1000000 Hz.
        - 50 us loopback settle.
        - Runs LFSR trace and fixed 0xA55A/0x1234 alternation.

        Important:
        - ESP32 is not currently flashed with test12.
        - Reflash test12 only after harness work or explicit user/PM instruction.
    </test12_state>

    <latest_mail_decisions>
        #12857 BronzeGate to BrightForge:
        - Reported test13 scope-toggle firmware implemented and flashed.
        - Included source path, compile/flash commands, and boot proof.

        #12861 TopazCliff to BronzeGate:
        - Stop i80 code experiments.
        - Accept wiring SI classification.
        - Improve physical harness before claiming max-clean throughput.
        - After harness work, rerun test12 4 MHz baseline and report before/after.

        #12865 BronzeGate to TopazCliff cc BrightForge:
        - Confirmed no more i80 code experiments.
        - Current ESP32 remains test13 all-data-plus-WR 5 MHz.
        - Next BronzeGate i80 action is rerun test12 only after harness improvement.

        #12859 NATIVE-640-BITMAP-148:
        - Lane opened.
        - BrightForge owns gated RTL feasibility first.
        - BronzeGate role is later firmware/HW proof checkpoint.

        #12864 RTL-BSRAM-OPTIMIZATION-149:
        - Lane opened.
        - BrightForge owns RTL/sim/synth.
        - BronzeGate role is later hardware basics smoke regression.

        #12862 TopazCliff:
        - Native 640 corruption is unsupported-width architecture, not firmware stride/fetch bug.
        - Do not debug native 640 in firmware.
        - Use supported 320x240 source with 2x scaler for capture-chain validation.
    </latest_mail_decisions>

    <working_tree_notes>
        Worktree is dirty with many unrelated pre-existing docs/archive changes and untracked firmware/capture artifacts.
        Do not revert unrelated changes.

        BronzeGate-created/active firmware artifacts:
        - firmware/esp32s3_test12_i80_link_stress/
        - firmware/esp32s3_test13_i80_scope_toggle/

        The resume file was refreshed for BronzeGate reboot state on 2026-06-18.
    </working_tree_notes>

    <resume_steps>
        1. Read AGENTS.md and AGENTS/BronzeGate.md if context was lost.
        2. Use team-mailbox skill.
        3. Fetch BronzeGate inbox for /home/itadmin/github/spinalhdlVDP with include_bodies=true.
        4. Acknowledge any ack_required mail.
        5. If no newer instruction supersedes #12861, keep ESP32 in test13 scope-toggle state until physical harness work is done.
        6. After harness improvement, reflash test12 and run 4 MHz baseline before any upward sweep.
    </resume_steps>
</state_snapshot>


<state_snapshot>
    <identity>
        Canonical agent: TopazCliff
        Role: Technical project manager for spinalhdlVDP
        Repo: /home/itadmin/github/spinalhdlVDP
        GitHub root: /home/itadmin/github
        Mail project key: /home/itadmin/github/spinalhdlVDP
        Source-of-truth order: latest user instruction, AGENTS.md, PROJECT_PLAN/TASKS.md, repo state
    </identity>

    <reboot_resume_context>
        User requested: save state before reboot and resume after system returns.
        Saved: 2026-06-16
        Active conversation context: Antigravity `agy` CLI now runs on this host and is the CyanPeak launch target. Antigravity MCP config was migrated to `/home/itadmin/.gemini/config/mcp_config.json`; the complete `team-mailbox` skill tree was installed under both `/home/itadmin/.gemini/antigravity-cli/skills/team-mailbox/` and `/home/itadmin/.gemini/skills/team-mailbox/`.
    </reboot_resume_context>

    <active_lanes>
        1. RTL-BSRAM-OPTIMIZATION-149 — IN-PROGRESS
           Owner: BrightForge (RTL/sim/synth)
           Scope: Refactor 3 (LineBuffer double-buffer fold), then Refactor 2 (SpriteEvaluator matrix pack). Refactor 1 dropped.
           Expected reclaim: ~5–7 BSRAM blocks.
           Validation: existing sims PASS; synth ≤46/46 BSRAM; no timing regression; HARDWARE-BASICS-144 smoke regression PASS.
           Next: BrightForge runs sim/synth on Refactor 3.

        2. NATIVE-640-BITMAP-148 — IN-PROGRESS
           Owner: BrightForge (RTL feasibility first) / BronzeGate (later firmware/HW proof) / CyanPeak (audit) / CoralReef (docs)
           Scope: Native 640×480 1:1 bitmap path. Initial target indexed 2bpp; RGB565 native gated by bandwidth/BSRAM feasibility.
           BSRAM feasibility gate waits for RTL-BSRAM-OPTIMIZATION-149 baseline.
           Next: BrightForge bandwidth co-sim (indexed 2bpp + RGB565).

        3. BronzeGate pending: fix i80 bench wiring (harness SI) then rerun test12 throughput sweep.
           Current flashed firmware (per BronzeGate resume): test13 i80 scope toggle.
    </active_lanes>

    <repo_state>
        Branch: task/sdram-clock-63
        Latest commit: 37d5029 — chore(project): revise RTL-BSRAM-OPTIMIZATION-149 scope, sequence NATIVE-640 behind 149 BSRAM
        Working tree: dirty (uncommitted).
        Notable uncommitted changes:
        - Modified: PROJECT_PLAN/ACTIVITY_LOG.md
        - Modified: firmware/libvdp/vdp_mode0.c, firmware/libvdp/vdp_mode0.h
        - Deleted: many old PROJECT_PLAN markdown files (moved to PROJECT_PLAN/archive/ as untracked)
        - Untracked: PROJECT_PLAN/PROJECT_VISION.md, PROJECT_PLAN/archive/ tree, firmware/esp32s3_test01..test13/ directories, fpga/tang20k/captures/ subdirectories, vdp_efficiency_report.md
        Do not revert these without checking with the user — they include active test firmware and capture artifacts.
    </repo_state>

    <agent_tooling_state>
        - setup_agents_full.sh launches CyanPeak with `agy --dangerously-skip-permissions`.
        - `agy` smoke test PASS: `agy --print 'Reply with exactly: AGY_OK' --print-timeout 45s` returned `AGY_OK`.
    </agent_tooling_state>

    <resume_steps>
        1. Read AGENTS.md and AGENTS/TopazCliff.md if context was lost.
        2. Fetch TopazCliff inbox for /home/itadmin/github/spinalhdlVDP (include_bodies=true).
        3. Acknowledge any ack_required mail.
        4. Read PROJECT_PLAN/TASKS.md live lane state.
        5. Verify BrightForge status on RTL-BSRAM-OPTIMIZATION-149 Refactor 3 and NATIVE-640-BITMAP-148 co-sim.
        6. Verify BronzeGate i80 harness-fix progress before any further test12 sweeps.
    </resume_steps>
</state_snapshot>
