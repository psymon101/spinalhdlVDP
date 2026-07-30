# Tang Nano 20K standalone diagnostic build (standalone-diagnostic-build lane).
#
# Native 640x480 no-host / no-QSPI / no-SDRAM 1x test-pattern build. Same device,
# pinout (tang20k_hdmi.cst), timing (tang20k_hdmi.sdc), PLL, and HDMI TX as the
# production build — only the top module differs (top_tang20k_diagnostic, generated
# by TopTang20kHdmiDiagnosticVerilog with diagnosticMode=true).
#
# Gowin writes to impl/; the Makefile `diagnostic` target moves it to impl_diagnostic/
# so it never disturbs the production impl/ artifacts.

set DEVICE "GW2AR-LV18QN88C8/I7"
set DEVICE_NAME "GW2AR-18C"
set TOP "top_tang20k_diagnostic"

set script_dir [file dirname [file normalize [info script]]]
cd $script_dir

add_file ../../hw/gen/top_tang20k_diagnostic.v
add_file third_party/hdl_util_hdmi/tmds_channel.sv
add_file tang20k_hdmi_tx.sv

# SDRAM PLL + controller are still instantiated (idle in diagnosticMode) so the
# netlist and clocking match production exactly.
add_file tang20k_sdram_pll.v
add_file third_party/sdram/sdram.v

add_file tang20k_hdmi.cst
add_file tang20k_hdmi.sdc

set_device $DEVICE -name $DEVICE_NAME
set_option -top_module $TOP
set_option -verilog_std sysv2017
set_option -use_sspi_as_gpio 1
set_option -multi_file_compilation_unit 0
# Production release effort (place=2/route=2) for the TNS=0 gate, matching build.tcl.
set_option -place_option 2
set_option -route_option 2
set_option -timing_driven 1
set_option -correct_hold_violation 1

run all
