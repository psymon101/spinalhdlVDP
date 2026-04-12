create_clock -name clk_pixel -period 39.6825 -waveform {0 19.84125} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name I_clk -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
