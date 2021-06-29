#########################################################################################
# fpga prototype makefile
#########################################################################################

#########################################################################################
# general path variables
#########################################################################################
base_dir=$(abspath ..)
sim_dir=$(abspath .)
osd_dir=$(base_dir)/opensocdebug/hardware
glip_dir=$(base_dir)/opensocdebug/glip/src

# do not generate simulation files
sim_name := none

#########################################################################################
# include shared variables
#########################################################################################
SUB_PROJECT ?= genesys2

ifeq ($(SUB_PROJECT),vcu118)
	SBT_PROJECT       ?= fpga_platforms
	MODEL             ?= VCU118FPGATestHarness
	VLOG_MODEL        ?= VCU118FPGATestHarness
	MODEL_PACKAGE     ?= chipyard.fpga.vcu118
	CONFIG            ?= RocketVCU118Config
	CONFIG_PACKAGE    ?= chipyard.fpga.vcu118
	GENERATOR_PACKAGE ?= chipyard
	TB                ?= none # unused
	TOP               ?= ChipTop
	BOARD             ?= vcu118
	FPGA_BRAND        ?= xilinx
endif

ifeq ($(SUB_PROJECT),bringup)
	SBT_PROJECT       ?= fpga_platforms
	MODEL             ?= BringupVCU118FPGATestHarness
	VLOG_MODEL        ?= BringupVCU118FPGATestHarness
	MODEL_PACKAGE     ?= chipyard.fpga.vcu118.bringup
	CONFIG            ?= RocketBringupConfig
	CONFIG_PACKAGE    ?= chipyard.fpga.vcu118.bringup
	GENERATOR_PACKAGE ?= chipyard
	TB                ?= none # unused
	TOP               ?= ChipTop
	BOARD             ?= vcu118
	FPGA_BRAND        ?= xilinx
endif

ifeq ($(SUB_PROJECT),arty)
	# TODO: Fix with Arty
	SBT_PROJECT       ?= fpga_platforms
	MODEL             ?= ArtyFPGATestHarness
	VLOG_MODEL        ?= ArtyFPGATestHarness
	MODEL_PACKAGE     ?= chipyard.fpga.arty
	CONFIG            ?= TinyRocketArtyConfig
	CONFIG_PACKAGE    ?= chipyard.fpga.arty
	GENERATOR_PACKAGE ?= chipyard
	TB                ?= none # unused
	TOP               ?= ChipTop
	BOARD             ?= arty
	FPGA_BRAND        ?= xilinx
endif

ifeq ($(SUB_PROJECT),genesys2)
	# TODO: Fix with Arty
	SBT_PROJECT       ?= fpga_platforms
	MODEL             ?= GENESYS2FPGATestHarness
	VLOG_MODEL        ?= GENESYS2FPGATestHarness
	MODEL_PACKAGE     ?= chipyard.fpga.genesys2
	CONFIG            ?= RocketGENESYS2Config
	CONFIG_PACKAGE    ?= chipyard.fpga.genesys2
	GENERATOR_PACKAGE ?= chipyard
	TB                ?= none # unused
	TOP               ?= ChipTop
	BOARD             ?= genesys2
	FPGA_BRAND        ?= xilinx
endif

include $(base_dir)/variables.mk

# default variables to build the arty example
# setup the board to use

.PHONY: default
default: $(mcs)

#########################################################################################
# misc. directories
#########################################################################################
fpga_dir := $(base_dir)/fpga/fpga-shells/$(FPGA_BRAND)
fpga_common_script_dir := $(fpga_dir)/common/tcl

#########################################################################################
# import other necessary rules and variables
#########################################################################################
include $(base_dir)/common.mk

#########################################################################################
# copy from other directory
#########################################################################################
all_vsrcs := \
	$(sim_vsrcs) \
	$(base_dir)/generators/sifive-blocks/vsrc/SRLatch.v \
	$(fpga_dir)/common/vsrc/PowerOnResetFPGAOnly.v \

#########################################################################################
# vivado rules
#########################################################################################

bootram_dir= $(base_dir)/fpga/src/main/resources/$(BOARD)/sdboot/bootram

# combine all sources into single .f
synth_list_f := $(build_dir)/$(long_name).vsrcs.f
$(synth_list_f): $(sim_common_files) $(all_vsrcs)
	$(foreach file,$(all_vsrcs),echo "$(file)" >> $@;)
	cat $(sim_common_files) >> $@

#BIT_FILE := $(build_dir)/$(MODEL).runs/impl_1/$(MODEL).bit
BIT_FILE := $(build_dir)/obj/$(MODEL).bit
$(BIT_FILE): $(synth_list_f)
	make -C $(bootram_dir) fat32elf.mem
	mkdir -p $(build_dir)/obj/
	ln -fs $(bootram_dir)/build/fat32elf.mem  $(build_dir)/obj/boot.mem
	cd $(build_dir); vivado \
		-nojournal -mode batch \
		-source $(fpga_common_script_dir)/vivado.tcl \
		-tclargs \
			-top-module "$(MODEL)" \
			-F "$(synth_list_f)" \
			-ip-vivado-tcls "$(shell find '$(build_dir)' -name '*.vivado.tcl')" \
			-board "$(BOARD)"

.PHONY: bitstream
bitstream: $(BIT_FILE)

.PHONY: debug-bitstream
debug-bitstream: $(build_dir)/obj/post_synth.dcp
	cd $(build_dir); vivado \
		-nojournal -mode batch \
		-source $(sim_dir)/scripts/run_impl_bitstream.tcl \
		-tclargs \
			$(build_dir)/obj/post_synth.dcp \
			$(BOARD) \
			$(build_dir)/debug_obj \
			$(fpga_common_script_dir)

#########################################################################################
# general cleanup rules
#########################################################################################
.PHONY: clean
clean:
	rm -rf $(gen_dir)




#########################################################################################
# Debug helpers
#########################################################################################


boot_bmm:= $(build_dir)/obj/boot.bmm
$(boot_bmm):
	vivado -mode batch -source $(fpga_common_script_dir)/search_ramb.tcl \
               -tclargs $(build_dir) $(MODEL) > $(build_dir)/search-ramb.log
	python $(fpga_common_script_dir)/bmm_gen.py $(build_dir)/search-ramb.log $(build_dir)/obj/boot.bmm 64 65536

BIT_FILE_NEW := $(build_dir)/obj/$(MODEL).new.bit
$(BIT_FILE_NEW): $(build_dir)/obj/boot.mem $(build_dir)/obj/boot.bmm
	data2mem -bm $(build_dir)/obj/boot.mem -bd $(build_dir)/obj/boot.mem -bt $(BIT_FILE) -o b $@

bitstream-update: $(BIT_FILE_NEW)


.PHONY: bitstream-update BIT_FILE_NEW boot_bmm


#--------------------------------------------------------------------
# Load examples
#--------------------------------------------------------------------

EXAMPLES  = jump dram baresd fat32txt fat32bin fat32elf
MEM_FILES =  $(addsuffix .mem,   $(EXAMPLES))   ##fpga need

$(EXAMPLES):
	make -C $(bootram_dir) $@.mem
	ln -fs $(bootram_dir)/build/$@.mem  $(build_dir)/obj/boot.mem
	make bitstream-update
	vivado -mode batch -source $(fpga_common_script_dir)/program.tcl -tclargs "xc7k325t_0" $(BIT_FILE_NEW)

.PHONY: $(EXAMPLES)
