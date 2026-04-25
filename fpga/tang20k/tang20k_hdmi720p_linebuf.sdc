# BronzeGate #8505 Slice D-B1-L — line-buffer CDC proof timing.
# Two PLLs:
#   pllWriter  (126 MHz)    → clkdivWriter (25.2 MHz pixel writer domain)
#   pllReader  (371.25 MHz) → clkdivReader (74.25 MHz HDMI domain) +
#                             5x serial 371.25 MHz for OSER10
create_clock -name clk_writer -period 39.6825 -waveform {0 19.84125} [get_pins {clkdivWriter/CLKOUT}]
create_clock -name clk_pixel  -period 13.4680 -waveform {0 6.7340}   [get_pins {clkdivReader/CLKOUT}]
create_clock -name clk_x5     -period 2.6936  -waveform {0 1.3468}   [get_pins {pllReader/CLKOUT}]
create_clock -name I_clk      -period 37.037  -waveform {0 18.5185}  [get_ports {I_clk}] -add
