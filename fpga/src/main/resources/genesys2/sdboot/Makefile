# RISCV environment variable must be set
ROOT_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(ROOT_DIR)/build

CC=$(RISCV)/bin/riscv64-unknown-elf-gcc
OBJCOPY=$(RISCV)/bin/riscv64-unknown-elf-objcopy
OBJDUMP=$(RISCV)/bin/riscv64-unknown-elf-objdump
CFLAGS=-march=rv64ima -mcmodel=medany -O3 -std=gnu11 -Wall -nostartfiles
CFLAGS+= -fno-common -g -DENTROPY=0 -mabi=lp64 -DNONSMP_HART=0 -ffunction-sections -fdata-sections
CFLAGS+= -I $(ROOT_DIR)/include -I $(ROOT_DIR)/driver -I.
LFLAGS=-static -Wl,-gc-sections -nostdlib -L $(ROOT_DIR)/linker -T sdboot.elf.lds

#PBUS_CLK ?= 1000000 # default to 1MHz but really should be overridden
PBUS_CLK ?= 60000000 # default to 1MHz but really should be overridden

default: elf bin dump

elf := $(BUILD_DIR)/sdboot.elf
$(elf): head.S kprintf.c sd.c
	mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) -DTL_CLK="$(PBUS_CLK)" $(LFLAGS) -o $@ head.S sd.c kprintf.c $(ROOT_DIR)/driver/uart.c

.PHONY: elf
elf: $(elf)

bin := $(BUILD_DIR)/sdboot.bin
$(bin): $(elf)
	mkdir -p $(BUILD_DIR)
	$(OBJCOPY) -O binary --change-addresses=-0x10000 $< $@

.PHONY: bin
bin: $(bin)

dump := $(BUILD_DIR)/sdboot.dump
$(dump): $(elf)
	$(OBJDUMP) -D -S $< > $@

.PHONY: dump
dump: $(dump)

.PHONY: clean
clean::
	rm -rf $(BUILD_DIR)
