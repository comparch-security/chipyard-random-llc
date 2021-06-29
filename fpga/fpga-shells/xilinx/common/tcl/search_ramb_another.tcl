##https://linmingjie.cn/index.php/archives/159/
##updatemem -meminfo rom.mmi -data ss.elf -bit rom_top.bit -proc dummy -out new.bit -force

set proj_dir [lindex $argv 0]
set project_name [lindex $argv 1]


# open project
open_project $proj_dir/$project_name.xpr

puts $proj_dir
puts $project_name

# open implemented design
open_run impl_1

proc write_mmi {cell_name  max_addr} {
#    set proj [$proj_dir/$project_name.xpr]
    puts $cell_name
    set filename "${cell_name}.mmi"
    set fileout [open $filename "w"]
    set brams [get_cells -hierarchical -filter [list REF_NAME =~ RAMB* && NAME =~ "*${cell_name}*"]]
        puts $fileout "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        puts $fileout "<MemInfo Version=\"1\" Minor=\"0\">"
        puts $fileout "  <Processor Endianness=\"Little\" InstPath=\"dummy\">"
    puts $fileout "  <AddressSpace Name=\"bram\" Begin=\"0\" End=\"${max_addr}\">"
    puts $fileout "    <BusBlock>"
    foreach cell $brams {
        puts $cell
        puts $fileout "      <BitLane MemType=\"RAMB32\" Placement=\"[get_property LOC $cell]\">"
        puts $fileout "        <DataWidth LSB=\"0\" MSB=\"31\"/>"
        puts $fileout "        <AddressRange Begin=\"0\" End=\"4095\"/>"
        puts $fileout "        <Parity NumBits=\"0\" ON=\"false\"/>"
        puts $fileout "      </BitLane>"
        puts $fileout ""
    }
    puts $fileout "    </BusBlock>"
    puts $fileout "  </AddressSpace>"
    puts $fileout "</Processor>"

        puts $fileout "<Config>"
        puts $fileout "  <Option Name=\"Part\" Val=\"[get_property PART [current_project ]]\"/>"
        puts $fileout "</Config>"
        puts $fileout "</MemInfo>"
        puts "MMI file ($filename) created successfully."
    close $fileout
}


write_mmi boot 4096




