set LIB libs/OpenROAD-flow-scripts/flow/platforms/nangate45/lib/NangateOpenCellLibrary_typical.lib

read_liberty $LIB
read_verilog core/generated/top_mapped.v
link_design MarkoRvCore
read_sdc assets/nangate45.sdc

report_checks -path_delay min_max -format full_clock_expanded -digits 3
report_wns
report_tns
report_checks -unconstrained -format full_clock_expanded -digits 3
report_check_types -max_slew -max_capacitance -max_fanout -violators -digits 3
report_power
exit
