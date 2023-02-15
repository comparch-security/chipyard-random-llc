#!/bin/sh

all:
	./atdect.riscv
	./autoSPEC.riscv
	if [ ! -e "/dev/mem" ]; then echo "mknod /dev/mem c 1 1"; mknod /dev/mem c 1 1; fi
	echo "acc0   : ./atdect.riscv           1         1    4096   0   0   0   0";
	./atdect.riscv                          1         1    4096   0   0   0   0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc0     1>/dev/null    2>&1
	echo "acc10  : ./atdect.riscv           0    163840    4096   0   0   0   0";
	./atdect.riscv                          0    163840    4096   0   0   0   0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_acc10    1>/dev/null    2>&1
	echo "ev10    : ./atdect.riscv     163840         0    4096   0   0   0   0";
	./atdect.riscv                     163840         0    4096   0   0   0   0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_ev10      1>/dev/null    2>&1
	echo "ev10dt  : ./atdect.riscv     163840         0    4096   5   5   0   0";
	./atdect.riscv                     163840         0    4096   5   5   0   0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_ev10dt     1>/dev/null    2>&1
	echo "static : ./atdect.riscv           0         0    4096   0   0   0   0";
	./atdect.riscv                          0         0    4096   0   0   0   0
	./autoSPEC.riscv 1 55 5 10 /mnt/pfc_static  1>/dev/null    2>&1
