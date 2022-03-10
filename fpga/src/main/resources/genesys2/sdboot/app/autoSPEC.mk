#!/bin/sh

all:
	./atdect.riscv
	./autoSPEC.riscv
	if [ ! -e "/dev/mem" ]; then echo "mknod /dev/mem c 1 1"; mknod /dev/mem c 1 1; fi
	echo "acc0   : ./atdect.riscv   0         0 1         0 0    0";
	./atdect.riscv   0         0 1         0 0    0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc0    1>log_acc0     2>&1
	echo "acc5   : ./atdect.riscv   0         0 1     40960 0    0";
	./atdect.riscv   0         0 1     40960 0    0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc5    1>log_acc5     2>&1
	echo "ev5    : ./atdect.riscv   1     40960 0         0 0    0";
	./atdect.riscv   1     40960 0         0 0    0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_ev5     1>log_ev5      2>&1
	echo "static : ./atdect.riscv   0         0 0         0 0    0";
	./atdect.riscv   0         0 0         0 0    0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_static  1>log_static   2>&1
