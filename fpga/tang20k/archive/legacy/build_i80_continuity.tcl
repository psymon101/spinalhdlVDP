set DEVICE "GW2AR-LV18QN88C8/I7"
set DEVICE_NAME "GW2AR-18C"
set TOP "top_tang20k_i80_cont"

set script_dir [file dirname [file normalize [info script]]]
cd $script_dir

# Lane P21 side-lane (TopazCliff #12039): throwaway i80 pin-continuity exerciser.
# No SDRAM / HDMI / PLL — just clk + LEDs + the i80 pads.
add_file ../../hw/gen/top_tang20k_i80_cont.v
add_file tang20k_i80_continuity.cst

set_device $DEVICE -name $DEVICE_NAME
set_option -top_module $TOP
set_option -verilog_std sysv2017
set_option -use_sspi_as_gpio 1
set_option -multi_file_compilation_unit 0

run all
