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

void prepare(uint8_t speed, uint8_t evsize, cacheBlock_t** evtarget) { //fill congruent addresses
  if(speed < 2) {
    *(evtarget + 1) = *evtarget;
    accessWithfence((void *)(*evtarget));
    if(speed == 1) {
      for(uint8_t j = 0; j < evsize; j++) {
        accessWithfence((void *)(*evtarget));
        if(j >= (WAYS - 1)) break;
        accessWithfence((void *)(evset[j]));
      }
    }
  }
}

void main(int argc, char **argv) {

  set_core(0, "");
  nice(19);
  uint32_t   i;
  uint32_t   j;
  uint32_t   k;
  uint8_t    speed   ;
  uint8_t    evsize[2]     ;
  uint32_t   evsizeAll[2]  ;
  uint8_t    failed  ;
  uint16_t   fails[2];
  uint32_t   acctime;
  uint8_t    hit[2]    ;
  uint16_t   reqtest   ;
  cacheBlock_t*    target[2];
  cacheBlock_t*    probe[2];
  cacheBlock_t*    drain;
  cacheBlock_t*    drainStart;
  clock_t    start_time[2], end_time[2], all_time[2] = {0};

  reqtest   = strtol(argv[1],0 ,0);
  if(reqtest > 6500)      reqtest   = 6500;

  uint32_t dev_fd = open("/dev/mem", O_RDWR);
  if(dev_fd < 0) { printf("open(/dev/mem) failed.\n"); return; }

  uint8_t *l2ctrl_base = (uint8_t *)mmap((void*)L2_CTRL_ADDR,     L2_CTRL_SIZE,    PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, L2_CTRL_ADDR   );
  uint8_t *dram_base   = (uint8_t *)mmap((void*)DRAM_TEST_ADDR,   DRAM_TEST_SIZE,  PROT_READ | PROT_WRITE, MAP_SHARED,               dev_fd, DRAM_TEST_ADDR );
  if(l2ctrl_base == MAP_FAILED)  { printf("L2CTRL mmap_fail!\n"); return; }
  if(dram_base   == MAP_FAILED)  { printf("DRAM   mmap_fail!\n"); return; }
  printf("l2ctrl_base %p\n", l2ctrl_base);
  printf("dram_base   %p\n", dram_base);

  target[0] = (cacheBlock_t*)dram_base;
  probe[0]  = target[0] + 1;
  printf("evset_ct in lru cache TESTS %d\n", reqtest);

  failed      = 1;
  fails[0]    = 0; fails[1]    = 0;
  target[0]   = (cacheBlock_t*)dram_base;
  probe[0]    = (cacheBlock_t*)dram_base;
  drainStart  = (cacheBlock_t*)(dram_base + DRAM_TEST_SIZE) - 32*BLKS;
  drain       = drainStart;
  evsizeAll[0] = 0; evsizeAll[1]=0;
  for(uint16_t tests = 0, speed = 0; tests < reqtest; tests++) {
    speed = (speed+1) & 0x01;
    if(++target[0] > drainStart - 10) target[0] = (cacheBlock_t*)dram_base;
    for(i = 0; i<EVSIZE; i++) evset[i]=NULL;
    evsize[speed] = 0;
    for(speed = 0; speed < 2; speed++) {
      start_time[speed] = clock();
      for(evsize[speed] = 0; evsize[speed] < EVSIZE; ) {
        if(++probe[0] >= drainStart - 2*BLKS) probe[0] = (cacheBlock_t*)dram_base;
        if(((uint64_t)(probe[0]) & 0x0fff) == ((uint64_t)target[0] & 0x0fff)) probe[0]++;

        //tlb
        /*for(cacheBlock_t* p = (cacheBlock_t*)((((uint64_t)probe[0] >> 12) << 12) | ((uint64_t)target[0] & 0X0fff)); p < probe[0] + 2*BLKS; p = p + 64) {
          if(p > drainStart - 1) break;
          accessNofence((void*)p);
        }*/

        //conflict test
        for(hit[speed] = 1, prepare(speed, evsize[speed], target); hit[speed] != 0; ) {
          if(++probe[0] == target[0]) probe[0]++;
          //if(((uint64_t)(++probe[0]) & 0x0fff) == ((uint64_t)target[0] & 0x0fff)) probe[0]++;
          if(probe[0] >= drainStart - 1) probe[0] = (cacheBlock_t*)dram_base;
          accessWithfence((void *)probe[0]);
          if(clcheck_f((void *)target[0]) == 0) hit[speed] = 0;
          acctime = timeAccessNoinline((void *)target[0]);
          //if(60 < acctime && acctime < 130) hit[speed] = 0;
        }
        for(i = 0; i < evsize[speed]; i++) { if((uint64_t *)probe == evset[i]) break; }
        if(i == evsize[speed] && probe[0] != target[0]) evset[evsize[speed]++] = (uint64_t *)(probe[0]);

        //check
        hit[speed] = 255;
        if(evsize[speed] >= WAYS) {
          hit[speed] = 0;
          //if(drain > drainStart + 8*BLKS) drain = drainStart;
          //for(i = 0; i < SETS*3; accessNofence((void *)drain++), i++);
          for(i = 0; i < 2; i++) { for(j = 0; j < evsize[speed] && evset[j] != 0; accessWithfence(evset[j]), j++); }
          accessWithfence((void *)target[0]);
          for(i = 0; i < 4; i++) {
            for(j = 0; j < 3; j++) { for(k =0; k < evsize[speed] && evset[k] != 0; accessWithfence(evset[k++])); }
            if(clcheck_f((void *)target[0]) == 1) hit[speed] ++;
            acctime = timeAccessNoinline((void *)target[0]);
          }
        }
        if(hit[speed] == 0) break;
      }
      end_time[speed] = clock();
      all_time[speed] = all_time[speed] + end_time[speed] - start_time[speed];
      evsizeAll[speed]  = evsizeAll[speed] + evsize[speed];
      //drain out
      if(drain > drainStart + 8*BLKS) drain = drainStart;
      for(i = 0; i < 3*BLKS; accessNofence((void *)drain++), i++);
      sched_yield();
    }

    printf("%4d %p ", tests+1, target[0]);
    for(speed = 0; speed < 2; speed++) {
      if(hit[speed]!=0)  { printf("\033[0m\033[1;31m%s\033[0m", "fail:"); fails[speed]++;}
      else                 printf("\033[0m\033[1;32m%s\033[0m", "succ:");
      printf("[%3d/%3d/%3d/%4.1f", fails[speed], hit[speed], evsize[speed], (float)evsizeAll[speed]/(tests+1));
      printf("/%5.2fs/%5.2fs]",     (float)(end_time[speed] - start_time[speed])/CLOCKS_PER_SEC, (float)all_time[speed]/CLOCKS_PER_SEC/(tests+1));
    }
     printf("\n");

  }

  munmap(l2ctrl_base, L2_CTRL_SIZE  );
  munmap(dram_base,   DRAM_TEST_ADDR);
  return;
}
