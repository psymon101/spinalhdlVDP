set DEVICE "GW2AR-LV18QN88C8/I7"
set DEVICE_NAME "GW2AR-18C"
set TOP "top_tang20k_i80_hs"

set script_dir [file dirname [file normalize [info script]]]
cd $script_dir

# Lane P21 side-lane (owner-directed): handshake pin-continuity walker.
# clk + LEDs + i80 data bus only.
add_file ../../hw/gen/top_tang20k_i80_hs.v
add_file tang20k_i80_handshake.cst

set_device $DEVICE -name $DEVICE_NAME
set_option -top_module $TOP
set_option -verilog_std sysv2017
set_option -use_sspi_as_gpio 1
set_option -multi_file_compilation_unit 0

run all
