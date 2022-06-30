# RISCV environment variable must be set
CUR_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BROM_DIR := $(CUR_DIR)/..
BUILD_DIR  := $(CUR_DIR)/build
DRIVER_DIR := $(BROM_DIR)/driver

CC=$(RISCV)/bin/riscv64-unknown-linux-gnu-gcc
OBJCOPY=$(RISCV)/bin/riscv64-unknown-linux-gnu-objcopy
OBJDUMP=$(RISCV)/bin/riscv64-unknown-linux-gnu-objdump
CFLAGS= -static -lpthread -g -O2 -I$(BROM_DIR)/include -I$(DRIVER_DIR)

TARGETS = led pfc atdect autoSPEC

c_source+= $(DRIVER_DIR)/pfc.c $(DRIVER_DIR)/pfc.h

RISCV_FILES =  $(addsuffix .riscv, $(TARGETS))
DUMP_FILES  =  $(addsuffix .dump,  $(TARGETS))

$(RISCV_FILES): %.riscv: %.c $(c_source)
	mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) -o $(BUILD_DIR)/$@ $(c_source) $< 
	ln -fs $(BUILD_DIR)/$@ ./$@

$(DUMP_FILES): %.dump: %.riscv
	$(OBJDUMP) -D -S -l $< > $(BUILD_DIR)/$@

clean:
	rm -rf $(BUILD_DIR)
.PHONY: clean
