#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include "misc_utils.h"
#include "platform.h"
#include <sys/mman.h>

// Pin thread to specific core
void set_core(int core, char *print_info) {

  // Define your cpu_set bit mask
  cpu_set_t my_set;

  // Initialize it all to 0, i.e. no CPUs selected
  CPU_ZERO(&my_set);

  // Set the bit that represents core
  CPU_SET(core, &my_set);

  // Set affinity of tihs process to the defined mask
  sched_setaffinity(0, sizeof(cpu_set_t), &my_set);

  // Print this thread's CPU
  printf("Core %2d for PID %d %s \n", sched_getcpu(), getpid(), print_info);
}

void open_devmem_selfpage(void) {
  dev_mem_fd = open("/dev/mem", O_RDWR);
  if(dev_mem_fd < 0) { printf("open(/dev/mem) failed.\n"); exit(1); }

  self_pagemap_fd = open("/proc/self/pagemap", O_RDONLY);
  if(self_pagemap_fd < 0) { printf("open(/proc/self/pagemap) failed\n"); exit(1); }

  pagesize =  getpagesize();

  l2ctrl_base = (char *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, 
                             MAP_SHARED,              dev_mem_fd,      L2_CTRL_ADDR);
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); exit(1); }

  //printf("PID %d mem_fd %d pagemap_fd %d pagesize %d\n", getpid(), dev_mem_fd, self_pagemap_fd, pagesize);
}

void close_devmem_selfpage() {
  munmap(l2ctrl_base, L2_CTRL_SIZE);
  close(dev_mem_fd);
  close(self_pagemap_fd);
}

void* virt2phy(const void *virtaddr) {
  long long page;
  long long virt_pfn = (unsigned long)virtaddr / pagesize;
  if(lseek(self_pagemap_fd, sizeof(uint64_t) * virt_pfn, SEEK_SET) == -1) return (void *)-1;
  read(self_pagemap_fd, &page, 8);
  return (void *)(((page & 0x7fffffffffffffULL) * pagesize) + ((unsigned long)virtaddr % pagesize));
}

// Measure time elapsed for experiment (not used for cache timing measurements)
double time_diff_ms(struct timespec begin, struct timespec end)
{
	double timespan;
	if ((end.tv_nsec-begin.tv_nsec)<0) {
		timespan  = (end.tv_sec -begin.tv_sec  - 1				   )*1.0e3 ;
		timespan += (end.tv_nsec-begin.tv_nsec + 1000000000UL)*1.0e-6;
	} else {
		timespan  = (end.tv_sec -begin.tv_sec                )*1.0e3 ;
		timespan += (end.tv_nsec-begin.tv_nsec               )*1.0e-6;
	}
	return timespan;
}

int comp(const void * a, const void * b) {
  return ( *(uint64_t*)a - *(uint64_t*)b );
}

int comp_double(const void * a, const void * b) {
  if (*(double*)a > *(double*)b)
    return 1;
  else if (*(double*)a < *(double*)b)
    return -1;
  else
    return 0;  
}

inline int median(int *array, int len) {
  qsort(array, len, sizeof(int), comp);
  return array[len/2];
}

