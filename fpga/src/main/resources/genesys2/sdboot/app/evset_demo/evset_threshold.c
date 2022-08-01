// See LICENSE for license details.

#include "platform.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/mman.h>

#include "misc_utils.h"
#include "cache_utils.h"
#include "list_struct.h"

int main(int argc, char **argv) {
  set_core(0, "");
  nice(19);

  cacheBlock_t*    probe;
  cacheBlock_t*    target;
  uint8_t    evsize;
  uint32_t   accesses;
  uint32_t   i;
  uint32_t   j;
  uint64_t   time[2];
  register uint64_t delay;
  uint8_t    reqevsize ;
  uint16_t   reqtest   ;
  uint32_t dev_fd = open("/dev/mem", O_RDWR);


  reqevsize = strtol(argv[1],0 ,0);
  reqtest   = strtol(argv[2],0 ,0);
  if(reqevsize < WAYS  )  reqevsize = WAYS;
  if(reqevsize > EVSIZE)  reqevsize = EVSIZE;
  if(reqtest > 1000)      reqtest   = 1000;

  if(dev_fd < 0) { printf("open(/dev/mem) failed.\n"); return 0; }

  uint8_t *gpio_base   = (uint8_t *)mmap((void*)GPIO_CTRL_ADDR,   GPIO_CTRL_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, GPIO_CTRL_ADDR );
  uint8_t *l2ctrl_base = (uint8_t *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, L2_CTRL_ADDR   );
  //uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED | MAP_HUGETLB, dev_fd, DRAM_TEST_ADDR );
  uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, DRAM_TEST_ADDR );
  uint8_t *dram_base2  = (uint8_t *)mmap(NULL,                    DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, DRAM_TEST_ADDR );

  if(gpio_base   == MAP_FAILED)  { printf("GPIO   mmap_fail!\n"); return 0; }
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); return 0; }
  if(dram_base   == MAP_FAILED)  { printf("DRAM   mmap_fail!\n"); return 0; }
  printf("gpio_base   %p\n", gpio_base);
  printf("l2ctrl_base %p\n", l2ctrl_base);
  printf("dram_base   %p\n", dram_base);

  *(volatile uint32_t *)(GPIO_CTRL_ADDR + GPIO_IOD_LED) = reqtest;
 
  target = (cacheBlock_t*)dram_base;
  probe  = target + 1;

                                   printf("state %d\n", clcheck_f((void *)target));
  accessWithfence((void *)target); printf("state %d\n", clcheck_f((void *)target));
  clflush_f((void *)target);       printf("state %d\n", clcheck_f((void *)target));

 accessWithfence((void *)dram_base);
 accessWithfence((void *)dram_base2);

  i = 0;
  printf("%p/%p\n", dram_base,    rte_mem_virt2phy(dram_base));
  printf("%p/%p\n", dram_base2,   rte_mem_virt2phy(dram_base2));
  printf("%p/%p\n", &dram_base,   rte_mem_virt2phy(&dram_base));
  printf("%p/%p\n", &dram_base2,  rte_mem_virt2phy(&dram_base2));
  for(cacheBlock_t* p = (cacheBlock_t*)dram_base; i < 32; p = p + 64, i++) printf("[%p/%p]\n", p, rte_mem_virt2phy(p));

  accesses = 0;
  while(1) {
    for(evsize = 0; evsize < reqevsize; ) {
      accessWithfence((void *)target);
      for(i = 0; i < evsize; i++) {
        if(i < WAYS) accessWithfence((void *)(evset[i]));
      }
      while(1) {
        asm volatile ("fence");
        for(delay = 0; delay<10; delay++);
        accessNofence((void *)probe);
        if(clcheck_f((void *)target) == 0) break;
        accessNofence((void *)target);
        probe++;
        accesses++;
        if(probe >= (cacheBlock_t*)(DRAM_TEST_ADDR + DRAM_TEST_SIZE) - 2) probe = target + 1;
      }
      evset[evsize++] = (uint64_t *)probe;
      if(accesses > 32*BLKS) break;
    }
    if(evsize >= WAYS) {
      for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
      for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
      for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
      accessWithfence((void *)target);
      for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
      if(clcheck_f((void *)target) == 0) break;
    }
    for(i = 0; i < evsize; i++) printf("%d : %p\n",i, evset[i]);
    close(dev_fd);
    munmap(gpio_base,   GPIO_CTRL_SIZE);
    munmap(l2ctrl_base, L2_CTRL_SIZE  );
    munmap(dram_base,   DRAM_TEST_ADDR);
    printf("wrong\n"); return 0;
  }
  printf("accesses %d\n", accesses);

  printf("L1_HIT CYCLES");
  usleep(1000);
  timeAccess(target);
  asm volatile ("fence");
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    asm volatile ("fence");
    for(delay = 0; delay<60; delay++);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L1_HIT CYCLES NOFENCE");
  usleep(1000);
  timeAccess(target);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(delay = 0; delay<60; delay++);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_HIT CYCLES");
  usleep(1000);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    clflushl1_f(target);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS CYCLES");
  usleep(1000);
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    clflush_f(target);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS AND EVICT L2 CLEAN BLOCK CYCLES");
  usleep(1000);
  accessWithfence((void *)target);
  for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(j = 0; j < evsize; j++) {
      accessNofence(evset[j]);
      clflushl1_f(evset[j]);
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS AND EVICT L2 DIRTY BLOCK CYCLES");
  usleep(1000);
  accessWithfence((void *)target);
  for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(j = 0; j < evsize; j++) {
      ((cacheBlock_t*)evset[j])->line[0] = 0;
      clflushl1_f(evset[j]);
    }
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS AND EVICT L1 L2 CLEAN BLOCK CYCLES");
  usleep(1000);
  accessWithfence((void *)target);
  for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(j = 0; j < evsize; j++) {
      accessWithfence(evset[j]);
    }
    for(delay = 0; delay<60; delay++);
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS AND EVICT L1 L2 DIRTY BLOCK CYCLES");
  usleep(1000);
  accessWithfence((void *)target);
  for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(j = 0; j < evsize; j++) {
      ((cacheBlock_t*)evset[j])->line[0] = 0;
      asm volatile ("fence");
    }
    if(clcheck_f((void *)target) != 0) break;
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  printf("L2_MISS AND EVICT L1 L2 DIRTY BLOCK CYCLES NOFENCE");
  usleep(1000);
  accessWithfence((void *)target);
  for(i = 0; i < evsize; i++) accessWithfence((void *)(evset[i]));
  time[0] = 0; time[1] = 0;
  for(i = 0; i < reqtest; i++) {
    for(j = 0; j < evsize; j++) {
      ((cacheBlock_t*)evset[j])->line[0] = 0;
    }
    if(clcheck_f((void *)target) != 0) break;
    time[0] = timeAccess(target);
    time[1] = time[1] + time[0];
    if(i < 16) printf(" %d ", time[0]);
  }
  printf(" %d \n", time[1]);

  close(dev_fd);
  munmap(gpio_base,   GPIO_CTRL_SIZE);
  munmap(l2ctrl_base, L2_CTRL_SIZE  );
  munmap(dram_base,   DRAM_TEST_ADDR);
}
