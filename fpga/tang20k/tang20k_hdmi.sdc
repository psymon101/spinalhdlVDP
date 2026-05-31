create_clock -name clk_pixel -period 39.6825 -waveform {0 19.84125} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5 -period 7.9365 -waveform {0 3.96825} [get_pins {pll/CLKOUT}]
create_clock -name I_clk -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
# Task 15: 64.8 MHz SDRAM clock from tang20k_sdram_pll's rPLL.CLKOUT.
# Period = 1000 / 64.8 = 15.432 ns. Instance path to be verified against Gowin synthesis log.
create_clock -name clk_sdram -period 15.432 -waveform {0 7.716} [get_pins {sdramPll/rpll_inst/CLKOUT}] -add

# #11123 FIX 2 (BronzeGate #11120 Finding 2 / CyanPeak #11122 Finding 4):
# clk_sdram is asynchronous to the pixel/HDMI clock group. All pixel<->SDRAM
# crossings go through CDC primitives (StreamFifoCC upload, BufferCC controls),
# so the PnR tool must NOT try to close inter-domain timing — otherwise it can
# vary SDRAM data/clock phase alignment to satisfy a meaningless cross path.
set_clock_groups -asynchronous -group [get_clocks {clk_pixel clk_x5}] -group [get_clocks {clk_sdram}]

# TODO FIX 2 (substantive, pending CyanPeak EM638325 values): off-chip SDRAM IO
# timing — set_output_delay for addr/BA/cmd/DQM/write-DQ and set_input_delay for
# read-DQ, referenced to the 180-deg SDRAM output clock (tSU/tHD/tAC/tOH). This
# is what actually closes the marginal interface; add before flash.
