create_clock -name clk_pixel -period 39.6825 -waveform {0 19.84125} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5 -period 7.9365 -waveform {0 3.96825} [get_pins {pll/CLKOUT}]
create_clock -name I_clk -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
# Task 15: 64.8 MHz SDRAM clock from tang20k_sdram_pll's rPLL.CLKOUT.
# Period = 1000 / 64.8 = 15.432 ns. Instance path to be verified against Gowin synthesis log.
create_clock -name clk_sdram -period 15.432 -waveform {0 7.716} [get_pins {sdramPll/rpll_inst/CLKOUT}] -add

# #11123 Finding 3 (#11168): the SDRAM CHIP captures on CLKOUTP (180-deg PLL
# output, wired to O_sdram_clk), NOT on CLKOUT. The controller's output regs
# launch on clk_sdram/CLKOUT; the chip samples them ~half a period (7.716 ns)
# later on CLKOUTP. Declare that capture clock as a generated clock 180-deg from
# clk_sdram so STA models the REAL capture edge for the IO delays below — the
# prior constraints referenced CLKOUT and never proved the true relationship.
create_generated_clock -name clk_sdram_io -source [get_pins {sdramPll/rpll_inst/CLKOUT}] -phase 180 [get_pins {sdramPll/rpll_inst/CLKOUTP}]

# #11123 FIX 2 (BronzeGate #11120 Finding 2 / CyanPeak #11122 Finding 4):
# clk_sdram is asynchronous to the pixel/HDMI clock group. All pixel<->SDRAM
# crossings go through CDC primitives (StreamFifoCC upload, BufferCC controls),
# so the PnR tool must NOT try to close inter-domain timing — otherwise it can
# vary SDRAM data/clock phase alignment to satisfy a meaningless cross path.
set_clock_groups -asynchronous -group [get_clocks {clk_pixel clk_x5}] -group [get_clocks {clk_sdram clk_sdram_io}]

# #11123 FIX 2 part 2 — off-chip SDRAM IO timing closure. Values are CyanPeak's
# EM638325 datasheet-derived numbers (#11127, relayed #11130), referenced to
# clk_sdram (the 180-deg SDRAM output-clock relationship is baked into the
# values). Without these, PnR never proves FPGA<->SDRAM setup/hold margin, so
# the interface was placement/PVT-marginal (the root cause, #11116).
#
# Writes (FPGA launches, SDRAM captures): addr/BA/cmd/DQM/write-DQ.
set_output_delay -clock clk_sdram_io -max  1.5 [get_ports {O_sdram_addr[*]}]
set_output_delay -clock clk_sdram_io -min -1.0 [get_ports {O_sdram_addr[*]}]
set_output_delay -clock clk_sdram_io -max  1.5 [get_ports {O_sdram_ba[*]}]
set_output_delay -clock clk_sdram_io -min -1.0 [get_ports {O_sdram_ba[*]}]
set_output_delay -clock clk_sdram_io -max  1.5 [get_ports {O_sdram_dqm[*]}]
set_output_delay -clock clk_sdram_io -min -1.0 [get_ports {O_sdram_dqm[*]}]
set_output_delay -clock clk_sdram_io -max  1.5 [get_ports {O_sdram_cs_n O_sdram_ras_n O_sdram_cas_n O_sdram_wen_n O_sdram_cke}]
set_output_delay -clock clk_sdram_io -min -1.0 [get_ports {O_sdram_cs_n O_sdram_ras_n O_sdram_cas_n O_sdram_wen_n O_sdram_cke}]
set_output_delay -clock clk_sdram_io -max  1.5 [get_ports {IO_sdram_dq[*]}]
set_output_delay -clock clk_sdram_io -min -1.0 [get_ports {IO_sdram_dq[*]}]
# Reads (SDRAM launches, FPGA captures): read-DQ.
set_input_delay  -clock clk_sdram_io -max  5.4 [get_ports {IO_sdram_dq[*]}]
set_input_delay  -clock clk_sdram_io -min  2.5 [get_ports {IO_sdram_dq[*]}]
