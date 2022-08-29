THIS_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BROM_DIR := $(THIS_DIR)/../../..
##CC = riscv64-unknown-linux-gnu-gcc

##################
# Build directory
##################
OBJDIR = obj
CFLAGS = -I./$(OBJDIR) -I$(BROM_DIR)/include -fPIC
CFLAGS += -static -fvisibility=hidden -s -O3

####################
# Files and folders
####################
SRCS = $(shell find ./*.c)
OBJS = $(addprefix $(OBJDIR)/,$(patsubst %.c,%.o,$(SRCS)))

##########
# Targets
##########
all: $(OBJS)

$(OBJDIR)/%.o: %.c | objdir
	$(CC) $(CFLAGS) -c $< -o $@

objdir:
	@mkdir -p $(OBJDIR) 

clean:
	rm -rf $(OBJDIR)