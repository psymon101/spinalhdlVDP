# BronzeGate #8482 Slice B — 720p output-shell proof timing constraints.
#
# Pixel clock: 74.25 MHz (period 13.4680 ns)
# 5x serial:   371.25 MHz (period 2.6936 ns) — drives OSER10.FCLK
# Source:      27 MHz Tang Nano 20K crystal (period 37.037 ns)
#
# Instance paths follow the SpinalHDL-generated Verilog naming pattern
# used by the main HDMI build (see tang20k_hdmi.sdc).
create_clock -name clk_pixel -period 13.4680 -waveform {0 6.7340} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5    -period 2.6936  -waveform {0 1.3468} [get_pins {pll/CLKOUT}]
create_clock -name I_clk     -period 37.037  -waveform {0 18.5185} [get_ports {I_clk}] -add
