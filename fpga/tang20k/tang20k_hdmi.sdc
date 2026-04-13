create_clock -name clk_pixel -period 39.6825 -waveform {0 19.84125} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name I_clk -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
# Task 15: 64.8 MHz SDRAM clock from tang20k_sdram_pll's rPLL.CLKOUT.
# Period = 1000 / 64.8 = 15.432 ns. Instance path to be verified against Gowin synthesis log.
create_clock -name clk_sdram -period 15.432 -waveform {0 7.716} [get_pins {sdramPll/rpll_inst/CLKOUT}] -add
