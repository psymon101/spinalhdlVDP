# PM #10026 mode0-barebones-step-1 timing constraints.
#
# Pixel clock: 25.175 MHz (period 39.722 ns)  — VGA 640x480@60
# 5x serial:   125.875 MHz (period 7.944 ns)  — OSER10.FCLK
# Source:      27 MHz Tang Nano 20K crystal (period 37.037 ns)
#
# Instance paths follow the SpinalHDL-generated Verilog naming for
# TopTang20kBarebones (`pll` and `clkdiv_1` after SpinalHDL renaming).
create_clock -name clk_pixel -period 39.722 -waveform {0 19.861} [get_pins {clkdiv_1/CLKOUT}]
create_clock -name clk_x5    -period 7.944  -waveform {0 3.972}  [get_pins {pll/CLKOUT}]
create_clock -name I_clk     -period 37.037 -waveform {0 18.5185} [get_ports {I_clk}] -add
