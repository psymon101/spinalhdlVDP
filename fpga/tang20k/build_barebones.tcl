set DEVICE "GW2AR-LV18QN88C8/I7"
set DEVICE_NAME "GW2AR-18C"
set TOP "top_tang20k_barebones"

set script_dir [file dirname [file normalize [info script]]]
cd $script_dir

# SpinalHDL-generated barebones top.
add_file ../../hw/gen/top_tang20k_barebones.v

# TMDS encode + OSER10 wrapper (shared with the main HDMI path).
add_file third_party/hdl_util_hdmi/tmds_channel.sv
add_file tang20k_hdmi_tx.sv

# No SDRAM in barebones — sdram_pll / nand2mario controller intentionally absent.
# No QSPI either — barebones has no host transport.

add_file tang20k_barebones.cst
add_file tang20k_barebones.sdc

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
