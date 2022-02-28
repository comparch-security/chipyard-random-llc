set_property top TestDriver [get_filesets sim_1]
set_property top_lib xil_defaultlib [get_filesets sim_1]
update_compile_order -fileset sim_1

set compile_simlib_dir    $sim_dir/../generated-src/$long_name/$top.cache/compile_simlib/vcs
set ip_user_files_dir     $sim_dir/../generated-src/$long_name/$top.ip_user_files
set ipstatic_source_dir   $sim_dir/../generated-src/$long_name/$top.ip_user_files/ipstatic

compile_simlib -simulator vcs -simulator_exec_path $vcs_home/bin -family all -language all -library all -dir $compile_simlib_dir -no_ip_compile -force

export_simulation  -lib_map_path $compile_simlib_dir -directory $sim_dir -simulator vcs -ip_user_files_dir $ip_user_files_dir -ipstatic_source_dir $ipstatic_source_dir -use_ip_compiled_libs
