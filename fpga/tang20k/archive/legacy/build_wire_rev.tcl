set DEVICE "GW2AR-LV18QN88C8/I7"
set DEVICE_NAME "GW2AR-18C"
set TOP "top_tang20k"

set script_dir [file dirname [file normalize [info script]]]
cd $script_dir

# Reverse-wire diagnostic: minimal top, no SDRAM / HDMI / etc.
add_file ../../hw/gen/top_tang20k.v

add_file tang20k_wire_rev.cst
# No SDC — wire test runs at glacial speed, no timing constraints apply.

set_device $DEVICE -name $DEVICE_NAME
set_option -top_module $TOP
set_option -verilog_std sysv2017
set_option -use_sspi_as_gpio 1
set_option -multi_file_compilation_unit 0
set_option -place_option 0
set_option -route_option 0
set_option -timing_driven 1
set_option -correct_hold_violation 1

run all
