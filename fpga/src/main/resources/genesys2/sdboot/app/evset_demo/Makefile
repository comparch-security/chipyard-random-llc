# RISCV environment variable must be set
CUR_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BROM_DIR := $(CUR_DIR)/../..
BUILD_DIR  := $(CUR_DIR)/build
DRIVER_DIR := $(BROM_DIR)/driver
LIST_DIR   := $(CUR_DIR)/list
UTILS_DIR  := $(CUR_DIR)/utils

CC=$(RISCV)/bin/riscv64-unknown-linux-gnu-gcc
OBJCOPY=$(RISCV)/bin/riscv64-unknown-linux-gnu-objcopy
OBJDUMP=$(RISCV)/bin/riscv64-unknown-linux-gnu-objdump
##CFLAGS= -lpthread -g -O2 -I$(LIST_DIR)/ -I$(UTILS_DIR)/ -I$(BROM_DIR)/include -I$(DRIVER_DIR)
CFLAGS= -lpthread -g -O2 -static -fvisibility=hidden -s -I$(LIST_DIR)/ -I$(UTILS_DIR)/ -I$(BROM_DIR)/include -I$(DRIVER_DIR)

TARGETS = evset_threshold evset_w+w_threshold evset_ge_postern evset_ct_postern evset_ppp_postern  evset_w+w_postern

c_source = $(UTILS_DIR)/cache_utils.c $(UTILS_DIR)/misc_utils.c

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
