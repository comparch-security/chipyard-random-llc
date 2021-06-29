set proj_dir [lindex $argv 0]
set project_name [lindex $argv 1]


#set synth_checkpoint_file $proj_dir/obj/post_synth.dcp
#set opt_checkpoint_file   $proj_dir/obj/post_opt.dcp
#set place_checkpoint_file $proj_dir/obj/post_place.dcp
set route_checkpoint_file $proj_dir/obj/post_route.dcp



# open project
#open_project $proj_dir/$project_name.xpr

#puts $proj_dir
#puts $project_name

# open implemented design ```can not find impl_1 in chipyard
#open_run impl_1

#open_checkpoint $opt_checkpoint_file
#open_checkpoint $place_checkpoint_file
open_checkpoint $route_checkpoint_file
#open_checkpoint $synth_checkpoint_file

# search for all RAMB blocks
foreach m [get_cells -hierarchical -filter { NAME =~  "*boot*" && PRIMITIVE_TYPE == BMEM.BRAM.RAMB36E1 } ]\
           { put $m; report_property $m {LOC} }

