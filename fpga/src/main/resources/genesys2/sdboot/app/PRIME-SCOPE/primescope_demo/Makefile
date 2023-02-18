THIS_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
EVSET_DIR := ../evsets
UTILS_DIR := ../utils
CC = riscv64-unknown-linux-gnu-gcc

##################
# Build directory
##################
OBJDIR  = obj
CFLAGS += -I./$(OBJDIR) 
CFLAGS += -I$(EVSET_DIR)/$(OBJDIR)
CFLAGS += -I$(UTILS_DIR)/$(OBJDIR)
CFLAGS += -Wall 
CFLAGS += -static -fvisibility=hidden -s -O3
LDFLAGS += -static -fvisibility=hidden -s -O3

####################
# Files and folders
####################

SRCS_APP = 	main.c \
						attacker_inclusive.c attacker_noninclusive.c \
						attacker_helper.c victim.c prime.c atdect_config.c
OBJS_APP = 	$(addprefix $(OBJDIR)/,$(patsubst %.c,%.o,$(SRCS_APP)))
OBJS_EVS = 	$(addprefix $(EVSET_DIR)/$(OBJDIR)/,$(notdir $(patsubst %.c,%.o,$(shell find $(EVSET_DIR)/*.c $(EVSET_DIR)/list/*.c))))
OBJS_UTL = 	$(addprefix $(UTILS_DIR)/$(OBJDIR)/,$(notdir $(patsubst %.c,%.o,$(shell find $(UTILS_DIR)/*.c))))

SRCS_TEST_EVS = test-ps-evset.c \
						attacker_inclusive.c attacker_noninclusive.c \
						attacker_helper.c victim.c prime.c atdect_config.c
OBJS_TEST_EVS = $(addprefix $(OBJDIR)/,$(patsubst %.c,%.o,$(SRCS_TEST_EVS)))

##########
# Targets
##########
all: clean utils evsets app test-ps-evset

# Compiling targets
app: $(OBJS_APP) $(OBJS_EVS) $(OBJS_UTL)
	$(CC) -o $@ $^ $(LDFLAGS) -lrt -lm

test-ps-evset: $(OBJS_TEST_EVS) $(OBJS_EVS) $(OBJS_UTL)
	$(CC) -o $@ $^ $(LDFLAGS) -lrt -lm

utils:
	$(MAKE) -C $(UTILS_DIR)  "CC = $(CC)"

evsets:
	$(MAKE) -C $(EVSET_DIR) CONFIG_FILE=$(THIS_DIR)/configuration.h "CC = $(CC)"

$(OBJDIR)/%.o: %.c | objdir
	$(CC) $(CFLAGS) -c $< -o $@

objdir:
	@mkdir -p $(OBJDIR) 

# Cleaning targets

clean:
	rm -rf app
	rm -rf test-ps-evset
	rm -rf $(OBJDIR) log/*
	$(MAKE) clean -C $(EVSET_DIR)/
	$(MAKE) clean -C $(UTILS_DIR)/

.PHONY: all clean
