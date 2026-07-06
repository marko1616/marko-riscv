create_clock -period 5.0 -name clock [get_ports clock]
set_input_delay  -clock clock -max 0.5 [all_inputs -no_clocks]
set_output_delay -clock clock -max 0.5 [all_outputs]