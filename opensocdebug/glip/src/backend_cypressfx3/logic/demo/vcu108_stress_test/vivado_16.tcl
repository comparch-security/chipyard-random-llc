# vivado_16.tcl: Tcl script for re-creating project 'vcu108_fx3_stress_test'
#
# This file contains the Vivado Tcl commands for re-creating the project to the state*
# when this script was generated. In order to re-create the project, please source this
# file in the Vivado Tcl Shell.
#
# * Note that the runs in the created project will be configured the same way as the
#   original project, however they will not be launched automatically. To regenerate the
#   run results please launch the synthesis/implementation runs as needed.
#

# Set the reference directory for source file relative paths (by default the value is script directory path)
set origin_dir "."

# Set the directory path for the original project from where this script was exported
set orig_proj_dir "[file normalize "$origin_dir/vcu108_fx3_stress_test_16"]"

# Create project
create_project vcu108_fx3_stress_test ./vcu108_fx3_stress_test_16

# Set the directory path for the new project
set proj_dir [get_property directory [current_project]]

# Set project properties
set obj [get_projects vcu108_fx3_stress_test]
set_property "default_lib" "xil_defaultlib" $obj
set_property "part" "xcvu095-ffva2104-2-e" $obj
set_property "sim.ip.auto_export_scripts" "1" $obj
set_property "simulator_language" "Mixed" $obj
set_property "source_mgmt_mode" "DisplayOnly" $obj

# Create 'sources_1' fileset (if not found)
if {[string equal [get_filesets -quiet sources_1] ""]} {
  create_fileset -srcset sources_1
}

# Set 'sources_1' fileset object
set obj [get_filesets sources_1]
set files [list \
 "[file normalize "$origin_dir/../../verilog/glip_cypressfx3_toplevel.sv"]"\
 "[file normalize "$origin_dir/vcu108_stress_test_clock.v"]"\
 "[file normalize "$origin_dir/vcu108_fx3_stress_test.v"]"\
 "[file normalize "$origin_dir/../../../../common/logic/stress_test/io_stress_test.v"]"\
 "[file normalize "$origin_dir/../../../../common/logic/stress_test/stress_test_lfsr.v"]"\
 "[file normalize "$origin_dir/../../../../common/logic/fifo/verilog/fifo_dualclock_fwft.sv"]"\
 "[file normalize "$origin_dir/../../../../common/logic/fifo/verilog/fifo_dualclock_standard.sv"]"\
]
add_files -norecurse -fileset $obj $files

# Set 'sources_1' fileset file properties for remote files
# None

# Set 'sources_1' fileset file properties for local files
# None

# Set 'sources_1' fileset properties
set obj [get_filesets sources_1]
set_property "generic" "WIDTH=16" $obj
set_property "top" "vcu108_fx3_stress_test" $obj

# Create 'constrs_1' fileset (if not found)
if {[string equal [get_filesets -quiet constrs_1] ""]} {
  create_fileset -constrset constrs_1
}

# Set 'constrs_1' fileset object
set obj [get_filesets constrs_1]

# Add/Import constrs file and set constrs file properties
set file "[file normalize "$origin_dir/vcu108.xdc"]"
set file_added [add_files -norecurse -fileset $obj $file]
set file "$origin_dir/vcu108.xdc"
set file [file normalize $file]
set file_obj [get_files -of_objects [get_filesets constrs_1] [list "*$file"]]
set_property "file_type" "XDC" $file_obj

# Add/Import constrs file and set constrs file properties
set file "[file normalize "$origin_dir/../../boards/vcu108/fmc_hpc1.xdc"]"
set file_added [add_files -norecurse -fileset $obj $file]
set file "$origin_dir/../../boards/vcu108/fmc_hpc1.xdc"
set file [file normalize $file]
set file_obj [get_files -of_objects [get_filesets constrs_1] [list "*$file"]]
set_property "file_type" "XDC" $file_obj

# Set 'constrs_1' fileset properties
set obj [get_filesets constrs_1]

# Create 'sim_1' fileset (if not found)
if {[string equal [get_filesets -quiet sim_1] ""]} {
  create_fileset -simset sim_1
}

# Set 'sim_1' fileset object
set obj [get_filesets sim_1]
# Empty (no sources present)

# Set 'sim_1' fileset properties
set obj [get_filesets sim_1]
set_property "runtime" "" $obj
set_property "top" "vcu108_fx3_stress_test" $obj
set_property "xelab.nosort" "1" $obj
set_property "xelab.rangecheck" "1" $obj
set_property "xelab.unifast" "" $obj
set_property "xsim.elaborate.rangecheck" "1" $obj
set_property "xsim.simulate.runtime" "" $obj

# Do not flatten design
set obj [get_runs synth_1]
set_property "steps.synth_design.args.flatten_hierarchy" "none" $obj

puts "INFO: Project created:vcu108_fx3_stress_test"
