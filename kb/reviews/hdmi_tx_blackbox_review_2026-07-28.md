# HDMI TX black-box review — 2026-07-28

**Lane:** `external-review-hdmi-tx-blackbox-review` (owner BrightForge)
**Trigger:** external static review §"`Tang20kHdmiTx` Review Limitation" — the black-box
internals behind `Tang20kHdmiTx.scala` were never reviewed.
**Type:** read-only assessment (no RTL changed).

## VERDICT: **OK** — no black-box internal poses a risk to HDMI stability; the current wrapper is sufficient.

## Actual implementation identified (not a Gowin IP core)

The HDMI transmitter is open-source SystemVerilog compiled into the build (`fpga/tang20k/build.tcl` lines 9-10):

- `fpga/tang20k/tang20k_hdmi_tx.sv` — board wrapper: 3× TMDS encoders + 3× OSER10 serializers + differential output buffers. Matches the SpinalHDL black-box `hw/spinal/spinalhdlvdp/Tang20kHdmiTx.scala` port-for-port.
- `fpga/tang20k/third_party/hdl_util_hdmi/tmds_channel.sv` — TMDS 8b/10b encoder, Sameer Puri's `hdl-util/hdmi` (MIT, `third_party/hdl_util_hdmi/LICENSE-MIT`).

## Item-by-item review (the 6 flagged items)

1. **TMDS encoder** (`tmds_channel.sv`): faithful, direct implementation of HDMI Spec v1.4a §5.4 (Figure 5-7), a widely-used reference. Running disparity `acc` is reset to 0 whenever `mode != 1` (blanking) — correct per §5.4.1. Control symbols (§5.4.2) match the spec constants. `tmds` register init = `10'b1101010100` (control-0), so power-up before any clock edge is already a valid TMDS control symbol — no startup garbage. TERC4/guard-band paths exist but are **dead code** in this DVI configuration (see item on mode). **OK.**

2. **Serializer primitive** (`OSER10` ×3): standard Gowin 10:1 serializer, one per TMDS data lane; `D0..D9` = the 10 encoded bits, `Q` = serial out, `PCLK = clk_pixel`, `FCLK = clk_pixel_x5`, `RESET = reset`. Standard/correct usage. **OK.**

3. **Clock phase assumptions**: `clk_pixel` (`clkdiv.CLKOUT`) and `clk_pixel_x5` (`pll.CLKOUT`) both derive from **one rPLL**; the Gowin `CLKDIV` produces a phase-aligned ÷5 of `pll.CLKOUT` (`TopTang20kHdmi.scala:88-90`). This is exactly the phase-aligned PCLK = FCLK/5 relationship OSER10 requires — guaranteed **by construction**, not by an assumption that could drift. **OK.**

4. **Reset behavior**: `hdmiTx.reset = pixelReset = !pll.LOCK` (`:106,758`) → OSER10s are held in reset until the PLL locks. `clkdiv.RESETN` is additionally held low for 16 `pll.CLKOUT` cycles after lock (`:100-104`, CyanPeak #8123) so the PLL output settles before `clk_pixel` toggles. The TMDS encoders have no reset but self-initialize to valid values (`acc=0`, `tmds=`control-0). RGB/sync/DE into the TX are registered with safe init (hsync/vsync init True, de init False, rgb 0), so the TX sees valid blanking during reset. **OK.** (See "F4 note" below.)

5. **Pixel-to-5× clock crossing**: handled inside the `OSER10` primitive (PCLK load / FCLK shift). The 10-bit `tmds_internal` word is produced in the `clk_pixel` domain and consumed by OSER10 on PCLK; because PCLK and FCLK are the same-PLL phase-aligned ÷5 pair (item 3), the crossing is a Gowin-characterized primitive operation, not an ad-hoc CDC. **OK.**

6. **OSER10 / output-buffer configuration**: `ELVDS_OBUF tmds_bufds[3:0]` drives 4 differential pairs — the 3 serialized data lanes plus the TMDS **clock** lane driven from the **raw `clk_pixel`** (not serialized), i.e. one clock period per pixel. Standard DVI/HDMI TMDS clocking on Tang Nano 20K. **OK.**

### DVI-mode observation (not a defect)
`mode = de ? 3'd1 : 3'd0` and `data_island_data = 0`, so only video (§5.4.1) and control (§5.4.2) symbols are ever emitted — this is **DVI signalling**, no HDMI data islands / audio / InfoFrames. Channel 0 (blue) carries `{vsync, hsync}` during blanking; channels 1/2 carry `00`. This is the intended, correct configuration for a video-only display transport; the TERC4 and guard-band logic in `tmds_channel.sv` is inert.

## Comparison against observed HDMI behavior

- The design has been observed to bring up HDMI reliably: `external-review-tierB-measure` ran **N=10 POR-reconfigure cold starts → 10/10 clean, byte-identical locked frames** (proof packet `5128ff4`, CyanPeak concurrence #14427, PM accept). Additional clean `/dev/video0` and RTSP captures across many lanes corroborate stable lock.
- The black-box internals reviewed here are consistent with that stability: valid power-up symbol, PLL-lock-gated serializer reset, phase-aligned clocks by construction.

## F4 reset-ordering note (known, measured, already dispositioned)

`pixelReset` deasserts on `pll.LOCK` while `clkdiv.RESETN` may still be within its 16-cycle
hold, so the OSER10s leave reset ~16 `pll.CLKOUT` cycles before `clk_pixel` (PCLK) begins
toggling. This is **benign**: with no PCLK edges, OSER10 performs no serialization during the
window and simply waits for the first stable PCLK edge. CyanPeak flagged this as F4 and
proposed `pixelReset = !clkdiv.RESETN`; `external-review-tierB-measure` **measured** cold-start
reliability (10/10 clean) and the PM accepted **Outcome A — no gating change**. This review
finds no new evidence to reopen it.

## Recommendation

- **No fix and no new measurement required.** The HDMI TX black-box is a faithful, standard,
  MIT-licensed TMDS/DVI transmitter with correct Gowin serialization and PLL-gated reset; the
  observed 10/10 POR stability corroborates.
- The F4 reset-ordering remains available to reopen only if true power-on (not POR-reconfigure)
  cold-start flakiness is ever observed — already tracked in `external-review-tierB-measure`.
- No follow-up lane proposed.

— BrightForge
