# BronzeGate #8486 Mode0 Hardening planar proof timing constraints.
# Identical to the Slice B 720p proof: 74.25 MHz pixel / 371.25 MHz x5 / 27 MHz I_clk.
create_clock -name clk_pixel -period 13.4680 -waveform {0 6.7340} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5    -period 2.6936  -waveform {0 1.3468} [get_pins {pll/CLKOUT}]
create_clock -name I_clk     -period 37.037  -waveform {0 18.5185} [get_ports {I_clk}] -add
