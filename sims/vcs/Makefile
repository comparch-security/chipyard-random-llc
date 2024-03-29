#########################################################################################
# vcs makefile
#########################################################################################

#########################################################################################
# general path variables
#########################################################################################
base_dir=$(abspath ../..)
sim_dir=$(abspath .)

#########################################################################################
# include shared variables
#########################################################################################
include $(base_dir)/variables.mk

#########################################################################################
# name of simulator (used to generate *.f arguments file)
#########################################################################################
sim_name = vcs

#########################################################################################
# vcs simulator types and rules
#########################################################################################
sim_prefix = simv
sim = $(sim_dir)/$(sim_prefix)-$(MODEL_PACKAGE)-$(CONFIG)
sim_debug = $(sim_dir)/$(sim_prefix)-$(MODEL_PACKAGE)-$(CONFIG)-debug

include $(base_dir)/vcs.mk

.PHONY: default debug
default: $(sim)
debug: $(sim_debug)

#########################################################################################
# import other necessary rules and variables
#########################################################################################
include $(base_dir)/common.mk

#########################################################################################
# vcs binary and arguments
#########################################################################################
VCS = vcs -full64 -cpp g++-4.8 -cc gcc-4.8 -LDFLAGS -Wl,--no-as-needed

VCS_OPTS = $(VCS_CC_OPTS) $(VCS_NONCC_OPTS) $(PREPROC_DEFINES)
ifdef VERDI_HOME
VCS_OPTS += $(VCS_NOVAS_OPTS)
endif
#########################################################################################
# vcs build paths
#########################################################################################
model_dir = $(build_dir)/$(long_name)
model_dir_debug = $(build_dir)/$(long_name).debug

#########################################################################################
# vcs simulator rules
#########################################################################################
$(sim): $(sim_vsrcs) $(sim_common_files) $(dramsim_lib) $(EXTRA_SIM_REQS)
	rm -rf $(model_dir)
	$(VCS) $(VCS_OPTS) $(EXTRA_SIM_SOURCES) -o $@ -Mdir=$(model_dir)

$(sim_debug): $(sim_vsrcs) $(sim_common_files) $(dramsim_lib) $(EXTRA_SIM_REQS)
	rm -rf $(model_dir_debug)
	$(VCS) $(VCS_OPTS) $(EXTRA_SIM_SOURCES) -o $@ -Mdir=$(model_dir_debug)  \
	+define+DEBUG

#########################################################################################
# create a vcs vpd rule
#########################################################################################
.PRECIOUS: $(output_dir)/%.vpd %.vpd
$(output_dir)/%.vpd: $(output_dir)/% $(sim_debug)
	(set -o pipefail && $(sim_debug) $(PERMISSIVE_ON) $(SIM_FLAGS) +loadelf=$< $(EXTRA_SIM_FLAGS) $(VERBOSE_FLAGS) +fsdbfile=$@  +vcdplusfile=$@ $(PERMISSIVE_OFF) $(EMPTY) </dev/null 2> >(spike-dasm > $<.out) | tee $<.log)

#########################################################################################
# general cleanup rules
#########################################################################################
.PHONY: clean clean-sim clean-sim-debug
clean:
	rm -rf $(gen_dir) $(sim_prefix)-* ucli.key

clean-sim:
	rm -rf $(model_dir) $(build_dir)/vc_hdrs.h $(sim) $(sim).daidir ucli.key

clean-sim-debug:
	rm -rf $(model_dir_debug) $(build_dir)/vc_hdrs.h $(sim_debug) $(sim_debug).daidir ucli.key
