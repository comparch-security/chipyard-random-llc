import sys
import re

# check arguments
if len(sys.argv) != 5:
    print("Wrong arguments\nbmm_gen in out bus-width mem-size")
    exit()

# read the ramb search result
f = open(sys.argv[1], "r")
lines = f.readlines()
f.close()

rams = []


total = 0
isarray = False
isline  = False

for i, line in enumerate(lines):
    ram_match = re.match(r"(\S+)ram_reg_(\d+)", line)
    if ram_match:
        dimen = line.count('_') -1
        if dimen == 1 :
            isline=True
            total=total+1
        if dimen == 2 :
            isarray=True
            total=total+1



if isarray == isline:
    print("ERROR")
    exit()
#elif isarray:
#    print("isarray")
#else:
#    print("isline")
 
#"chiptop/system/bootRAMDomainWrapper/bootram/mem/ram_reg_0")
if isline: 
    for i, line in enumerate(lines):
    	ram_match = re.match(r"(\S+)ram_reg_(\d+)", line)
    	if ram_match:
    		loc_match = re.match(r"LOC[\w\s]+RAMB(\d+)_X(\d+)Y(\d+)", lines[i+2])
    		if loc_match:
    			end_pos = line.rfind('_')+1
    			ramid = (line[end_pos:])
    			rams.append((int(ramid), loc_match.group(2), loc_match.group(3)))

#"chiptop/system/bootRAMDomainWrapper/bootram/mem/ram_reg_0_0")
if isarray : 
    temp=0
    for i, line in enumerate(lines):
		ram_match = re.match(r"(\S+)ram_reg_(\d+)_(\d+)", line)
    		if ram_match:
			loc_match = re.match(r"LOC[\w\s]+RAMB(\d+)_X(\d+)Y(\d+)", lines[i+2])
			if loc_match:
				end_pos = line.rfind('_') - 1
				ramid = (line[end_pos:]).split('_')
				rams.append((int(temp), loc_match.group(2), loc_match.group(3)))
				temp=temp+1


print(rams)
# get the bit-width of each

if int(sys.argv[3]) % len(rams) != 0:
    print("Cannot divide memory bus evenly into BRAMs!")
    exit()

DW = int(sys.argv[3]) / len(rams)
MS = "%#010x"%(int(sys.argv[4]) - 1)

rams = sorted(rams, key=lambda r: r[0], reverse=True)

f = open(sys.argv[2], "w")
f.write('ADDRESS_SPACE BOOTRAM RAMB32 [0x00000000:{0}]\n'.format(MS))
f.write("  BUS_BLOCK\n")
for r in rams:
    f.write('    ram_reg_{0} [{1}:{2}] LOC = X{3}Y{4};\n'.format(r[0], r[0]*DW+DW-1, r[0]*DW, r[1], r[2]))
f.write("  END_BUS_BLOCK;\n")
f.write("END_ADDRESS_SPACE;\n")
f.close()
