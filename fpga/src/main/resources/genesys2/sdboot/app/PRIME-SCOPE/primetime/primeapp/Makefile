THIS_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
EVSET_DIR := ../../evsets/
UTILS_DIR := ../../utils/

CC = riscv64-unknown-linux-gnu-gcc
##################
# Build directory
##################
OBJDIR  = obj
CFLAGS += -I./$(OBJDIR) 
CFLAGS += -I$(EVSET_DIR)/$(OBJDIR)
CFLAGS += -I$(UTILS_DIR)/$(OBJDIR)
# CFLAGS += -Wall 
CFLAGS += -static -fvisibility=hidden -s -O3

####################
# Files and folders
####################
SRCS_APP = main.c attacker_arr.c victim.c prime.c
OBJS_APP = $(addprefix $(OBJDIR)/,$(patsubst %.c,%.o,$(SRCS_APP)))

OBJS_EVS = $(addprefix $(EVSET_DIR)/$(OBJDIR)/,$(notdir $(patsubst %.c,%.o,$(shell find $(EVSET_DIR)/*.c $(EVSET_DIR)/list/*.c))))
OBJS_UTL = $(addprefix $(UTILS_DIR)/$(OBJDIR)/,$(notdir $(patsubst %.c,%.o,$(shell find $(UTILS_DIR)/*.c))))

##########
# Targets
##########
all: clean utils evsets app

# Compiling targets

app: $(OBJS_APP) $(OBJS_EVS) $(OBJS_UTL)
	$(CC) -o $@ $^ $(LDFLAGS) -lrt -lm

utils:
	$(MAKE) -C $(UTILS_DIR) "CC = $(CC)"

evsets:
	$(MAKE) -C $(EVSET_DIR) CONFIG_FILE=$(THIS_DIR)/configuration.h "CC = $(CC)"

$(OBJDIR)/%.o: %.c | objdir
	$(CC) $(CFLAGS) -c $< -o $@

objdir:
	@mkdir -p $(OBJDIR)

# Execution targets

dirs:
	#rm -rf log/*
	mkdir -p log

run: dirs
	./app

# Cleaning targets

clean:
	rm -rf app 
	rm -rf $(OBJDIR)
	rm -rf log/output.txt
	$(MAKE) clean -C $(EVSET_DIR)/
	$(MAKE) clean -C $(UTILS_DIR)/

.PHONY: all clean
