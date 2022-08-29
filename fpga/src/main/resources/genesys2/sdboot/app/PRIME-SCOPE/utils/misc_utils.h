#pragma once

#define _GNU_SOURCE
#include <stdio.h>
#include <sched.h>
#include <time.h>

int  dev_mem_fd;
int  self_pagemap_fd;
int  pagesize;
char *l2ctrl_base;

void set_core(int core, char *print_info);
void open_devmem_selfpage(void);
void close_devmem_selfpage(void);
void* virt2phy(const void *virtaddr);

double time_diff_ms(struct timespec begin, struct timespec end);
int comp(const void * a, const void * b);
int comp_double(const void * a, const void * b);

int median(int *array, int len);