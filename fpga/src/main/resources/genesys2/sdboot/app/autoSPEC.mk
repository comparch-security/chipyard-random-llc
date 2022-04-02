#!/bin/sh

all:
	./atdect.riscv
	./autoSPEC.riscv
	if [ ! -e "/dev/mem" ]; then echo "mknod /dev/mem c 1 1"; mknod /dev/mem c 1 1; fi
	echo "acc0   : ./atdect.riscv   0         0 1         0 0   5  4096 5";
	./atdect.riscv                  0         0 1         0 0   5  4096 5
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc0    1>/dev/null    2>&1
	echo "acc5   : ./atdect.riscv   0         0 1     40960 0   5  4096 5";
	./atdect.riscv                  0         0 1     40960 0   5  4096 5
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc5    1>/dev/null    2>&1
	echo "ev5dt  : ./atdect.riscv   1     40960 0         0 1   5  4096 5";
	./atdect.riscv                  1     40960 0         0 1   5  4096 5
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_ev5dt   1>/dev/null    2>&1
	echo "ev5    : ./atdect.riscv   1     40960 0         0 0   5  4096 5";
	./atdect.riscv                  1     40960 0         0 0   5  4096 5
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_ev5     1>/dev/null    2>&1
	echo "static : ./atdect.riscv   0         0 0         0 0   5  4096 5";
	./atdect.riscv                  0         0 0         0 0   5  4096 5
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_static  1>/dev/null    2>&1
