create_clock -name clk_pixel -period 39.6825 -waveform {0 19.84125} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5 -period 7.9365 -waveform {0 3.96825} [get_pins {pll/CLKOUT}]
create_clock -name I_clk -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
# #11197 Option A: 40.5 MHz SDRAM clock (lowered from the retired 64.8 MHz target to widen the analog
# address-capture window 7.7->12.35ns and close the row aliasing). rPLL.CLKOUT.
# Period = 1000 / 40.5 = 24.691 ns. (IO-delay VALUES below are chip tIS/tHD/tAC/tOH,
# absolute ns — unchanged by frequency; the wider period gives more margin.)
create_clock -name clk_sdram -period 24.691 -waveform {0 12.345} [get_pins {sdramPll/rpll_inst/CLKOUT}] -add

# #11168 Finding 3 REVERTED (CyanPeak #11182): a create_generated_clock -phase
# clk on CLKOUTP produced STA EDGE-PAIRING ARTIFACTS — false -7.46/-4.777 ns on
# paths launched by an ALREADY-PRIMARY-global clock (~11 ns reg->pin arrivals
# that can't be real). The PnR report (sec.6) confirms sdramPll_clkout is PRIMARY
# global, so there was no routing problem to model. Source-synchronous SDRAM IO
# is modeled referenced to clk_sdram (the real launch clock) with the 180-deg
# capture relationship folded into the -max/-min delay VALUES below — the
# standard form, which closes cleanly.

# #11123 FIX 2 (BronzeGate #11120 Finding 2 / CyanPeak #11122 Finding 4):
# clk_sdram is asynchronous to the pixel/HDMI clock group. All pixel<->SDRAM
# crossings go through CDC primitives (StreamFifoCC upload, BufferCC controls),
# so the PnR tool must NOT try to close inter-domain timing — otherwise it can
# vary SDRAM data/clock phase alignment to satisfy a meaningless cross path.
set_clock_groups -asynchronous -group [get_clocks {clk_pixel clk_x5}] -group [get_clocks {clk_sdram}]

# #11123 FIX 2 part 2 — off-chip SDRAM IO timing closure. Values are CyanPeak's
# EM638325 datasheet-derived numbers (#11127, relayed #11130), referenced to
# clk_sdram (the 180-deg SDRAM output-clock relationship is baked into the
# values). Without these, PnR never proves FPGA<->SDRAM setup/hold margin, so
# the interface was placement/PVT-marginal (the root cause, #11116).
#
# Writes (FPGA launches, SDRAM captures): addr/BA/cmd/DQM/write-DQ.
set_output_delay -clock clk_sdram -max  1.5 [get_ports {O_sdram_addr[*]}]
set_output_delay -clock clk_sdram -min -1.0 [get_ports {O_sdram_addr[*]}]
set_output_delay -clock clk_sdram -max  1.5 [get_ports {O_sdram_ba[*]}]
set_output_delay -clock clk_sdram -min -1.0 [get_ports {O_sdram_ba[*]}]
set_output_delay -clock clk_sdram -max  1.5 [get_ports {O_sdram_dqm[*]}]
set_output_delay -clock clk_sdram -min -1.0 [get_ports {O_sdram_dqm[*]}]
set_output_delay -clock clk_sdram -max  1.5 [get_ports {O_sdram_cs_n O_sdram_ras_n O_sdram_cas_n O_sdram_wen_n O_sdram_cke}]
set_output_delay -clock clk_sdram -min -1.0 [get_ports {O_sdram_cs_n O_sdram_ras_n O_sdram_cas_n O_sdram_wen_n O_sdram_cke}]
set_output_delay -clock clk_sdram -max  1.5 [get_ports {IO_sdram_dq[*]}]
set_output_delay -clock clk_sdram -min -1.0 [get_ports {IO_sdram_dq[*]}]
# Reads (SDRAM launches, FPGA captures): read-DQ.
set_input_delay  -clock clk_sdram -max  5.4 [get_ports {IO_sdram_dq[*]}]
set_input_delay  -clock clk_sdram -min  2.5 [get_ports {IO_sdram_dq[*]}]

# QSPI-SI-CEILING-183 word-drain transport (#14206): I_qspi_sck is routed to a Gowin
# global clock inside QspiTransportCore (sclkGlobalCd) and used as a REAL clock domain
# for nibble->byte assembly, crossing to clk_sys via BufferCC/crossClockDomain. Constrain
# it so STA actually covers the SCLK-domain paths. The prior option-a build left SCLK
# UNCONSTRAINED, so its TNS=0 EXCLUDED these paths and is not an acceptable sign-off.
# Period 25 ns = 40 MHz (ESP32-P4 register/status ceiling per firmware audit #14145).
# If firmware drives register reads above 40 MHz, this must be tightened.
create_clock -name qspi_sck -period 25.0 -waveform {0 12.5} [get_ports {I_qspi_sck}] -add
# SCLK is a truly external, asynchronous clock from the P4 host; every crossing into the
# system/pixel/SDRAM domains goes through BufferCC/crossClockDomain CDC, so the tool must
# NOT try to close inter-domain timing against it (mirrors the clk_sdram async rule above).
set_clock_groups -asynchronous -group [get_clocks {qspi_sck}] -group [get_clocks {clk_pixel clk_x5 clk_sdram I_clk}]
