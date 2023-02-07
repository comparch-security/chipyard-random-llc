# See LICENSE for license details.

# check RISCV environment variable
ifndef VCS_HOME
    $(error VCS is not ready yet)
endif

#--------------------------------------------------------------------
# global define
#--------------------------------------------------------------------

default: sim

base_dir = $(abspath ..)
sim_dir = .
output_dir = $(sim_dir)/output

.PHONY: default

#--------------------------------------------------------------------
# Sources
#--------------------------------------------------------------------

verilog_srcs = \
	$(sim_dir)/../../generated-src/chipyard.TestHarness.RocketConfig/chipyard.TestHarness.RocketConfig.top.mems.v \
	$(sim_dir)/../../generated-src/chipyard.TestHarness.RocketConfig/chipyard.TestHarness.RocketConfig.top.v \

test_verilog_srcs = \
	$(sim_dir)/dttrace_tb.sv \


#--------------------------------------------------------------------
# Build DPI
#--------------------------------------------------------------------

$(sim_dir)/libdpi.so : $(test_verilog_srcs) 
	g++ -Wa,-W -fPIC -m64 -O1 -std=c++11 -shared \
	-DVERBOSE_MEMORY

junk += $(sim_dir)/libdpi.so


#--------------------------------------------------------------------
# Build rules (VCS)
#--------------------------------------------------------------------
VCS = vcs
VCS_FLAG = \
	-cpp g++-4.8 -cc gcc-4.8 -LDFLAGS -Wl,--no-as-needed \
	-sverilog -ntb_opts -full64 \
	-timescale=1ns/10ps \
	-debug \

#--------------------------------------------------------------------
# Build the simulator (VCS)
#--------------------------------------------------------------------
sim: $(verilog_headers) $(test_verilog_srcs)
	$(VCS) $(VCS_FLAG) $(verilog_srcs) $(test_verilog_srcs) \
	-top tb \
        -o $@ 

.PHONY: sim

#--------------------------------------------------------------------
# Run (VCS)
#--------------------------------------------------------------------
exec_simv = ./sim
exec_simv_vcd = ./sim +vcd
timeout_cycles=200000


sim.vcs.out: sim
	$(exec_simv_vcd) +max-cycles=$(timeout_cycles) 

sim.vcs.vcd: sim
	$(exec_simv_vcd) +vcd_name=$@ +max-cycles=$(timeout_cycles) 

sim.vcs.vpd: sim
	$(exec_simv_vcd) +vcd_name=$@ +max-cycles=$(timeout_cycles)

.PHONY: sim

junk += sim*



#--------------------------------------------------------------------
# clean up
#--------------------------------------------------------------------

clean:
	rm -rf $(junk) .nodeIdDynamicRanges opendatabase.log ucli.key \
	vc_hdrs.h vcs.man

.PHONY: clean
