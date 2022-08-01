THIS_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

##################
# Build directory
##################
OBJDIR = obj
CFLAGS = -I./$(OBJDIR) -fPIC

CC=$(RISCV)/bin/riscv64-unknown-linux-gnu-gcc
OBJDUMP=$(RISCV)/bin/riscv64-unknown-linux-gnu-objdump

####################
# Files and folders
####################
SRCS = $(shell find ./*.c)
OBJS  = $(addprefix $(OBJDIR)/,$(patsubst %.c,%.o,$(SRCS)))
DUMPS = $(addprefix $(OBJDIR)/,$(patsubst %.c,%.dump,$(SRCS)))

##########
# Targets
##########
all: $(OBJS) $(DUMPS)

$(OBJDIR)/%.o: %.c | objdir
	$(CC) $(CFLAGS) -c $< -o $@

$(OBJDIR)/%.dump: %.o | objdir
	$(OBJDUMP) -D -S -l $< > $@

objdir:
	@mkdir -p $(OBJDIR) 

clean:
	rm -rf $(OBJDIR)

output:
	@echo $(OBJS)
	@echo $(DUMPS)
