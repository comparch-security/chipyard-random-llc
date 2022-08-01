#define _GNU_SOURCE
#include <stdio.h>
#include <unistd.h>
#include <stdint.h>
#include <stdlib.h>
#include <fcntl.h>

#include "misc_utils.h"

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
  printf("Core %2d for %s\n", sched_getcpu(), print_info);
}
   
#define PFN_MASK_SIZE   8

uint64_t rte_mem_virt2phy(const void *virtaddr) {
  int fd, retval;
  uint64_t page, physaddr;
  unsigned long virt_pfn;
  int page_size;
  off_t offset;

  /* standard page size */
  page_size = getpagesize();
  fd = open("/proc/self/pagemap", O_RDONLY);
  if(fd < 0) {
    //printf("open failed\n");
    return -1;
  }

  virt_pfn = (unsigned long)virtaddr / page_size;
  offset = sizeof(uint64_t) * virt_pfn;
  if(lseek(fd, offset, SEEK_SET) == (off_t) -1) {
    //printf("lseek failed\n");
    return -1;
  }

  retval = read(fd, &page, PFN_MASK_SIZE);
  close(fd);

  /*
   * the pfn (page frame number) are bits 0-54 (see
   * pagemap.txt in linux Documentation)
   */
  /*
  printf("pagesize %p\n", page_size);
  printf("virt_pfn %p\n",  virt_pfn);
  printf("offset   %p\n",    offset);
  printf("page     %p\n",      page);
  printf("retval   %p\n",    retval);*/
  if((page & 0x7fffffffffffffULL) == 0) {
    printf("pfn failed\n");
    return -1;
  }

  physaddr = ((page & 0x7fffffffffffffULL) * page_size) + ((unsigned long)virtaddr % page_size);

  return physaddr;
}
